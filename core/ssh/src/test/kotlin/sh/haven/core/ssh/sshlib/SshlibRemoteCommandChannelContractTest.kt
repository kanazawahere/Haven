package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient as SshlibClient
import org.connectbot.sshlib.SshClientConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import sh.haven.core.ssh.RemoteCommandChannelContractTest
import sh.haven.core.ssh.ShellChannel

/**
 * [RemoteCommandChannelContractTest] on the sshlib engine (#58): a raw
 * `org.connectbot.sshlib.SshClient` session channel driven through
 * [SshlibShell.requestExec] + [SshlibShell.open] — the same connect sequence
 * [SshlibCapabilitySpikeTest] uses. Like the sshlib shell path, this is
 * contract-tested directly without being wired into [sh.haven.core.ssh
 * .SshConnectionFactory] (whole-connection sshlib is #58 phase 5+).
 */
class SshlibRemoteCommandChannelContractTest : RemoteCommandChannelContractTest() {

    private var client: SshlibClient? = null

    /**
     * sshlib 0.3.1 drops the RFC 4254 exit-status report (upstreamed as
     * connectbot/cbssh#232); [SshlibShell.open]'s exitStatusProbe is the
     * documented -1 placeholder. Flip this when a release carries the fix —
     * the `GAP exec exit status` probe in [SshlibCapabilitySpikeTest] fails
     * at the same moment.
     */
    override val exitStatusSurfaced: Boolean = false

    private val trustAll = object : org.connectbot.sshlib.HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean = true
    }

    override fun openRemoteCommand(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        requestPty: Boolean,
    ): ShellChannel {
        val c = SshlibClient(
            SshClientConfig {
                this.host = host
                this.port = port
                hostKeyVerifier = trustAll
            },
        )
        client = c
        return runBlocking {
            val connect = c.connect()
            assertTrue("sshlib connect failed: $connect", connect is ConnectResult.Success)
            assertEquals(AuthResult.Success, c.authenticatePassword(username, password))
            val session = c.openSession()
                ?: throw AssertionError("sshlib openSession() returned null on an authed connection")
            SshlibShell.requestExec(session, command, requestPty, "xterm", 80, 24)
            SshlibShell.open(c, session, "xterm", 80, 24)
        }
    }

    @After
    fun closeClient() {
        client?.let { runBlocking { runCatching { it.disconnect() } } }
        client = null
    }
}
