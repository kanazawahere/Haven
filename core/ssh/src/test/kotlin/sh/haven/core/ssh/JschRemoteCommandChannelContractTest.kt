package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.After

/**
 * [RemoteCommandChannelContractTest] on the JSch engine — the exec channel
 * behind `SshClient.openShellChannel(remoteCommand = ...)`, i.e. exactly what
 * [SshSessionManager] drives for a profile with a RemoteCommand configured.
 */
class JschRemoteCommandChannelContractTest : RemoteCommandChannelContractTest() {

    private var client: SshClient? = null

    override fun openRemoteCommand(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        requestPty: Boolean,
    ): ShellChannel {
        val c = SshClient()
        client = c
        runBlocking {
            c.connect(
                ConnectionConfig(
                    host = host,
                    port = port,
                    username = username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                ),
            )
        }
        return c.openShellChannel(
            term = "xterm",
            cols = 80,
            rows = 24,
            remoteCommand = command,
            requestPty = requestPty,
        )
    }

    @After
    fun closeClient() {
        try { client?.close() } catch (_: Exception) { /* best effort */ }
    }
}
