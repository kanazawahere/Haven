package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.After

/** [PortForwardContractTest] on the JSch engine — the behaviour sshlib must match. */
class JschPortForwardContractTest : PortForwardContractTest() {

    private var client: SshClient? = null

    override fun connect(host: String, port: Int, username: String, password: String) {
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
    }

    private fun client() = client ?: error("not connected")

    override fun setLocal(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int =
        client().setPortForwardingL(bindAddress, localPort, remoteHost, remotePort)

    override fun delLocal(bindAddress: String, localPort: Int) =
        client().delPortForwardingL(bindAddress, localPort)

    override fun setDynamic(bindAddress: String, bindPort: Int): Int =
        client().setPortForwardingDynamic(bindAddress, bindPort)

    override fun setRemote(bindAddress: String, remotePort: Int, localHost: String, localPort: Int) =
        client().setPortForwardingR(bindAddress, remotePort, localHost, localPort)

    override fun delRemote(remotePort: Int) = client().delPortForwardingR(remotePort)

    override fun disconnect() { client?.disconnect() }

    @After
    fun closeClient() {
        try { client?.close() } catch (_: Exception) {}
    }
}
