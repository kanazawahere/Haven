package sh.haven.app.agent.mailrules

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.haven.core.data.mailrule.MailWatchPoker
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.MailRuleRepository
import sh.haven.core.mail.MailSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the Mail-Rules poll loop. While the master switch [UserPreferencesRepository.mailAutomationEnabled]
 * is on AND at least one enabled rule exists, it polls each connected email account's watched
 * folders every [POLL_INTERVAL_MS] and runs [MailRuleEngine.pollOnce]. Off by default → zero work.
 *
 * v1 polls **already-connected** sessions only; it runs while the app process is alive (foreground,
 * or kept up by another transport's foreground service). The headless WorkManager reconnect path
 * (Risk-1) is a deferred follow-up. [ProcessLifecycleOwner] supplies the foreground signal that
 * decides whether a destructive action runs now or is queued for approval.
 */
@Singleton
class MailWatchManager @Inject constructor(
    private val repo: MailRuleRepository,
    poller: RealMailRulePoller,
    executor: MailRuleActionExecutor,
    private val mailSessionManager: MailSessionManager,
    private val preferencesRepository: UserPreferencesRepository,
    biometricGate: sh.haven.core.data.keystore.BiometricGate,
) : MailWatchPoker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = MailRuleEngine(
        repo = repo,
        poller = poller,
        runner = executor,
        now = { System.currentTimeMillis() },
        foreground = { biometricGate.isForegroundActive() },
    )
    private var loopJob: Job? = null

    /** Idempotent. Observes the master switch; starts/stops the loop as it flips. */
    fun start() {
        scope.launch {
            preferencesRepository.mailAutomationEnabled.collectLatest { enabled ->
                if (enabled) runLoop() else loopJob?.cancel()
            }
        }
    }

    private fun runLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                runCatching { pollCycle() }.onFailure { Log.w(TAG, "poll cycle failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Request a poll now (e.g. on app-return-to-foreground); no-op when automation is off. */
    override fun pokeNow() {
        scope.launch {
            if (preferencesRepository.mailAutomationEnabled.first()) {
                runCatching { pollCycle() }
            }
        }
    }

    private suspend fun pollCycle() {
        if (repo.enabledRuleCount() == 0) return
        val connectedProfiles = mailSessionManager.activeSessions.map { it.profileId }.toSet()
        if (connectedProfiles.isEmpty()) return
        val enabled = repo.allEnabled()
        val targets = buildSet {
            for (pid in connectedProfiles) {
                for (rule in enabled) {
                    if (rule.accountProfileId == null || rule.accountProfileId == pid) add(pid to rule.folderId)
                }
            }
        }
        for ((profileId, folderId) in targets) {
            runCatching { engine.pollOnce(profileId, folderId) }
                .onFailure { Log.w(TAG, "pollOnce($profileId,$folderId) failed: ${it.message}") }
        }
        runCatching { repo.trimFiringLog() }
    }

    companion object {
        private const val TAG = "MailWatchMgr"
        const val POLL_INTERVAL_MS = 180_000L // 3 minutes while watching
    }
}
