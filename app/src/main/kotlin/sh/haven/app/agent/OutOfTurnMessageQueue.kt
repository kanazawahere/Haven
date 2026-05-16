package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.ssh.SshSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Out-of-turn message queue (#161). The MCP `queue_self_message` tool
 * lets an agent inject text into the very Claude Code (or other REPL)
 * session that's driving the MCP traffic, by watching the SSH
 * terminal output for a prompt and typing the queued text when it
 * appears. The premise: the SSH session that carries the MCP reverse
 * tunnel *is* the session Claude Code is running in, so its agent-
 * scoped scrollback ring is a window into Claude Code's stdout. When
 * a prompt appears at the tail of the scrollback after the agent's
 * own turn has flushed, the message gets sent into the session as if
 * the user typed it — which is exactly the surface a slash command
 * like `/mcp reconnect haven` needs.
 *
 * Power-user feature: gated by [UserPreferencesRepository
 * .agentAllowQueueSelfMessage] in the dispatcher, on top of EVERY_CALL
 * consent in the tool itself. Both must be on.
 *
 * Polling-based rather than callback-based — simpler, and the tail of
 * the scrollback ring is cheap to read.
 */
@Singleton
class OutOfTurnMessageQueue @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val consentManager: AgentConsentManager,
) {
    private data class QueuedMessage(
        val id: String,
        val sessionId: String,
        val text: String,
        val promptRegex: Regex,
        val deadline: Long,
        /**
         * Monotonic total-bytes-appended on the scrollback ring at
         * enqueue time. The watcher only fires once this value has
         * grown — guards against firing on a `❯` that was already on
         * screen at enqueue time (e.g. agent re-queueing during the
         * same idle prompt). Uses the ring's lifetime counter rather
         * than its snapshot size so it stays usable after the ring
         * fills to its capacity cap.
         */
        val baselineTotalBytes: Long,
        val clientHint: String?,
    )

    private val queue = ConcurrentHashMap<String, QueuedMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Log.i(TAG, "OutOfTurnMessageQueue starting poll loop (interval=${POLL_INTERVAL_MS}ms)")
        scope.launch {
            try {
                pollLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "poll loop died", e)
            }
        }
    }

    /**
     * Schedule [text] to be typed into [sessionId]'s SSH terminal when
     * the next output matching [promptPattern] (default: a line ending
     * in `> `) appears at the tail of the scrollback. Returns a queue
     * id the caller can use to [cancel] before delivery.
     *
     * The [baselineBytes] capture is the size of the agent scrollback
     * *at enqueue time* — the watcher only fires when new bytes have
     * been written since (so a prompt that was already on screen at
     * the moment the agent called this won't trigger an immediate
     * fire mid-turn; we wait for the agent's own turn output to flush
     * first).
     */
    fun enqueue(
        sessionId: String,
        text: String,
        promptPattern: String,
        timeoutSeconds: Int,
        clientHint: String?,
    ): String {
        val baseline = sshSessionManager.agentScrollbackTotalBytes(sessionId) ?: 0L
        val id = UUID.randomUUID().toString()
        queue[id] = QueuedMessage(
            id = id,
            sessionId = sessionId,
            text = text,
            promptRegex = Regex(promptPattern, RegexOption.MULTILINE),
            deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L,
            baselineTotalBytes = baseline,
            clientHint = clientHint,
        )
        Log.i(TAG, "enqueue $id: sessionId=$sessionId text='${text.take(40)}' pattern=$promptPattern timeout=${timeoutSeconds}s baselineTotalBytes=$baseline")
        return id
    }

    fun cancel(id: String): Boolean = queue.remove(id) != null

    fun pending(): List<String> = queue.keys.toList()

    private suspend fun pollLoop() {
        var lastTickLog = 0L
        while (true) {
            delay(POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            for ((id, msg) in queue.toMap()) {
                if (now > msg.deadline) {
                    queue.remove(id)
                    Log.i(TAG, "queue $id timed out without prompt match (pattern=${msg.promptRegex.pattern})")
                    continue
                }
                val currentTotal = sshSessionManager.agentScrollbackTotalBytes(msg.sessionId)
                if (currentTotal == null) {
                    if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                        Log.i(TAG, "queue $id: no scrollback ring for session ${msg.sessionId}")
                        lastTickLog = now
                    }
                    continue
                }
                if (currentTotal <= msg.baselineTotalBytes) {
                    if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                        Log.i(TAG, "queue $id: currentTotal=$currentTotal <= baseline=${msg.baselineTotalBytes}; waiting for new bytes")
                        lastTickLog = now
                    }
                    continue
                }
                val scrollback = sshSessionManager.readAgentScrollback(msg.sessionId, MAX_TAIL_BYTES) ?: continue
                val stripped = scrollback.decodeToString().let { stripAnsi(it) }
                // Look only at the last few lines — false positives from
                // earlier scrollback (`>` redirects in commands, code
                // snippets containing prompts) shouldn't ever fire.
                val tail = stripped.takeLast(TAIL_MATCH_CHARS)
                val matched = msg.promptRegex.containsMatchIn(tail)
                if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                    // Dump the *raw* tail (printable codepoints only) so
                    // we can see whether ❯ actually arrives in the
                    // scrollback ring after the agent's turn ends. The
                    // last 200 chars is enough to see the prompt block
                    // without spamming logcat.
                    val visible = tail.takeLast(200).map { c ->
                        when {
                            c == '\n' -> "\\n"
                            c == '\r' -> "\\r"
                            c == '\t' -> "\\t"
                            c.code < 0x20 -> "[%02x]".format(c.code)
                            else -> c.toString()
                        }
                    }.joinToString("")
                    Log.i(TAG, "queue $id tick: matched=$matched currentTotal=$currentTotal baseline=${msg.baselineTotalBytes} pattern=${msg.promptRegex.pattern} tail.last200=«$visible»")
                    lastTickLog = now
                }
                if (matched) {
                    // Pre-delivery consent — the typing target is also the
                    // user's input field. Without this gate we'd risk
                    // garbling whatever the user is mid-typing. The
                    // consent sheet offers an extra "Allow for N min"
                    // action so a power user iterating with an agent
                    // can authorise the agent to drive the REPL for a
                    // short collaborative window without the prompt
                    // re-firing every queue. (#161)
                    if (queue.remove(id) != null) {
                        val display = if (msg.text.length > 80) msg.text.take(77) + "…" else msg.text
                        val decision = try {
                            consentManager.requestConsent(
                                toolName = DELIVERY_CONSENT_TOOL_NAME,
                                clientHint = msg.clientHint,
                                summary = "Type into the SSH REPL session «${msg.sessionId.take(8)}…»:\n\n$display",
                                level = ConsentLevel.EVERY_CALL,
                                offerTimedAllow = true,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "queue $id consent prompt failed: ${e.message}")
                            ConsentDecision.DENY
                        }
                        if (decision != ConsentDecision.ALLOW) {
                            Log.d(TAG, "queue $id denied at pre-delivery consent")
                            continue
                        }
                        try {
                            // CR not LF: TUIs in raw mode (Claude Code,
                            // most readline-style REPLs) treat `\r` as the
                            // Enter key. `\n` lands in the input buffer
                            // but doesn't submit it — observed
                            // end-to-end via this queue's first
                            // successful delivery, which pasted but
                            // never submitted. (#161)
                            sshSessionManager.sendInput(msg.sessionId, msg.text + "\r")
                            Log.i(TAG, "queue $id delivered to ${msg.sessionId} (${msg.text.length} chars + CR)")
                        } catch (e: Exception) {
                            Log.w(TAG, "queue $id delivery failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

    companion object {
        private const val TAG = "OutOfTurnQueue"
        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_TAIL_BYTES = 4096
        // Throttle the per-tick diagnostic log so a 60s polling cycle
        // produces ~30 log lines per queued message, not 300.
        private const val LOG_TICK_INTERVAL_MS = 2_000L
        private const val TAIL_MATCH_CHARS = 512
        // Synthetic tool name used for the pre-delivery consent prompt.
        // Distinct from `queue_self_message` (the enqueue-time tool)
        // so the AgentConsentManager memoisation / timed-allow keys
        // don't collide.
        const val DELIVERY_CONSENT_TOOL_NAME = "queue_self_message:deliver"
        private val ANSI_REGEX = Regex("\\[[0-9;?]*[a-zA-Z]")
    }
}
