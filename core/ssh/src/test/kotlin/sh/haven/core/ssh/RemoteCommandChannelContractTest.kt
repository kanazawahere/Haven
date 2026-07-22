package sh.haven.core.ssh

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * Engine-agnostic contract for the terminal-facing exec-with-command channel
 * (profile RemoteCommand, PR #436 re-port onto the #58 seam): the configured
 * command runs INSTEAD of a login shell, with or without a PTY, delivered on
 * the same [ShellChannel] shape the interactive shell uses.
 *
 * Mirrors [ShellChannelContractTest]'s rig: a real in-process MINA sshd whose
 * scripted [CommandFactory] emits deterministic bytes (no /bin/sh, no host-PTY
 * echo to reason about). The server ALSO carries a shell factory that
 * announces itself with a different banner, so an engine that mistakenly
 * requests a shell instead of the exec fails the banner assertion with the
 * shell banner in the message rather than hanging.
 *
 * [JschRemoteCommandChannelContractTest] pins the JSch engine;
 * [sh.haven.core.ssh.sshlib.SshlibRemoteCommandChannelContractTest] proves the
 * sshlib pty-req + exec pairing matches it before any factory flip.
 */
abstract class RemoteCommandChannelContractTest {

    /** A hung read is a failure, not a wedged CI run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    protected lateinit var server: SshServer
    protected var serverPort: Int = 0
    private var channel: ShellChannel? = null

    /** Dial + auth + open the exec channel for [command] on the engine under test. */
    protected abstract fun openRemoteCommand(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        requestPty: Boolean,
    ): ShellChannel

    /**
     * Whether the engine surfaces the RFC 4254 §6.10 exit-status report on
     * [ShellChannel.exitStatus]. JSch does; sshlib 0.3.1 drops it (upstreamed
     * as connectbot/cbssh#232), so its channel reports the documented -1
     * placeholder — the sshlib subclass overrides this to false and the
     * exit-status test asserts the placeholder instead of faking a value.
     */
    protected open val exitStatusSurfaced: Boolean = true

    @Before
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("remote-cmd-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            // Tell exec and shell apart by banner: a login shell must never
            // appear on a remote-command channel.
            shellFactory = ShellFactory { ScriptedCommand(SHELL_SENTINEL) }
            commandFactory = CommandFactory { _, command -> ScriptedCommand(command) }
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun stopServer() {
        try { channel?.disconnect() } catch (_: Exception) { /* best effort */ }
        if (::server.isInitialized) server.stop(true)
    }

    private fun open(command: String, requestPty: Boolean): ShellChannel =
        openRemoteCommand("127.0.0.1", serverPort, "tester", "secret", command, requestPty)
            .also { channel = it }

    /** Read from [input] until [needle] appears or EOF / the test timeout fires. */
    private fun readUntil(input: InputStream, needle: String): String {
        val seen = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return seen.toString(Charsets.UTF_8.name())
            if (n > 0) {
                seen.write(buf, 0, n)
                if (seen.toString(Charsets.UTF_8.name()).contains(needle)) {
                    return seen.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    /** Read [input] to end-of-stream, discarding the bytes. */
    private fun drainToEof(input: InputStream) {
        val buf = ByteArray(256)
        while (input.read(buf) >= 0) { /* discard */ }
    }

    @Test
    fun `the command runs instead of a login shell`() {
        val banner = readUntil(open(ECHO_COMMAND, requestPty = true).input, EXEC_BANNER)
        assertTrue("exec banner missing from: $banner", banner.contains(EXEC_BANNER))
        assertFalse("login shell answered the exec request: $banner", banner.contains(SHELL_SENTINEL))
    }

    @Test
    fun `PTY-backed command behaves like an interactive channel`() {
        val s = open(ECHO_COMMAND, requestPty = true)
        readUntil(s.input, EXEC_BANNER) // drain the banner first
        s.resize(120, 40) // must not throw
        s.output.write("hello\n".toByteArray())
        s.output.flush()
        val echoed = readUntil(s.input, "hello")
        assertTrue("echo missing from: $echoed", echoed.contains("hello"))
        assertTrue(s.isConnected)
    }

    @Test
    fun `command output is delivered without a PTY`() {
        val s = open(ECHO_COMMAND, requestPty = false)
        val banner = readUntil(s.input, EXEC_BANNER)
        assertTrue("exec banner missing from: $banner", banner.contains(EXEC_BANNER))
        // stdin still round-trips on a PTY-less exec channel.
        s.output.write("no-pty\n".toByteArray())
        s.output.flush()
        assertTrue(readUntil(s.input, "no-pty").contains("no-pty"))
    }

    @Test
    fun `exit status surfaces after the remote command exits`() {
        val s = open(EXIT_COMMAND, requestPty = true)
        drainToEof(s.input) // server exits right after its goodbye bytes
        // Give the close + exit-status a moment to propagate through the transport.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (s.isClosed && (!exitStatusSurfaced || s.exitStatus >= 0)) break
            Thread.sleep(20)
        }
        assertTrue("channel still open after remote exit", s.isClosed)
        if (exitStatusSurfaced) {
            assertEquals(EXIT_STATUS, s.exitStatus)
        } else {
            // sshlib 0.3.1 drops the exit-status report (cbssh#232); the
            // documented placeholder is -1. When an sshlib release ships the
            // fix, flip exitStatusSurfaced in the subclass and this branch dies.
            assertEquals(-1, s.exitStatus)
        }
    }

    protected companion object {
        const val EXEC_BANNER = "READY-EXEC\n"
        const val SHELL_SENTINEL = "READY-SHELL-NOT-EXEC\n"

        /** Scripted command: banner, then echo each line back; exits 0 on "exit". */
        const val ECHO_COMMAND = "echo-lines"

        /** Scripted command: one goodbye byte, then exit with [EXIT_STATUS]. */
        const val EXIT_COMMAND = "exit-now"
        const val EXIT_STATUS = 7

        /**
         * In-process command runner, deterministic and OS-independent (same
         * approach as ExecContractTest.ScriptedCommand, plus an echo loop so
         * PTY-interactivity can be asserted). Doubles as the tell-tale shell
         * (constructed with [SHELL_SENTINEL]) so a wrong channel type is
         * detected by banner, not by timeout.
         */
        private class ScriptedCommand(private val script: String) : Command {
            private lateinit var out: OutputStream
            private lateinit var input: InputStream
            private var exit: ExitCallback? = null

            @Volatile private var worker: Thread? = null

            override fun setInputStream(value: InputStream) { input = value }
            override fun setOutputStream(value: OutputStream) { out = value }
            override fun setErrorStream(value: OutputStream) {}
            override fun setExitCallback(value: ExitCallback) { exit = value }

            override fun start(channel: ChannelSession?, env: Environment?) {
                worker = Thread({
                    try {
                        when (script) {
                            SHELL_SENTINEL -> {
                                out.write(SHELL_SENTINEL.toByteArray()); out.flush()
                                echoLoop()
                            }
                            ECHO_COMMAND -> {
                                out.write(EXEC_BANNER.toByteArray()); out.flush()
                                echoLoop()
                            }
                            EXIT_COMMAND -> {
                                out.write("BYE\n".toByteArray()); out.flush()
                                exit?.onExit(EXIT_STATUS)
                            }
                            else -> {
                                out.write("unknown command\n".toByteArray()); out.flush()
                                exit?.onExit(127)
                            }
                        }
                    } catch (_: Exception) {
                        // channel torn down (disconnect / exit) — fine
                    }
                }, "scripted-remote-command").apply { isDaemon = true; start() }
            }

            /** Echo newline-terminated lines back verbatim; exit 0 on "exit". */
            private fun echoLoop() {
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        val text = line.toString().trimEnd('\r')
                        line.clear()
                        if (text == "exit") { exit?.onExit(0); break }
                        out.write((text + "\n").toByteArray()); out.flush()
                    } else {
                        line.append(c.toChar())
                    }
                }
            }

            override fun destroy(channel: ChannelSession?) { worker?.interrupt() }
        }
    }
}
