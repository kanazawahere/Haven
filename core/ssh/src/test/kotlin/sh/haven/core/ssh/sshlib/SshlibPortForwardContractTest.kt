package sh.haven.core.ssh.sshlib

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.PortForwardContractTest

/**
 * [PortForwardContractTest] on the sshlib engine (#58 phase 6): the same
 * L/dynamic-SOCKS/R suite over [SshlibPortForwarders], proving it matches
 * JSch — and that sshlib's built-in SOCKS5 stands in for Haven's
 * DynamicForwardServer.
 */
class SshlibPortForwardContractTest : PortForwardContractTest() {

    private var client: SshlibClient? = null
    private var forwarders: SshlibPortForwarders? = null

    override fun connect(host: String, port: Int, username: String, password: String) {
        val config = ConnectionConfig(
            host = host,
            port = port,
            username = username,
            authMethod = ConnectionConfig.AuthMethod.Password(password),
        )
        val verifier = mockk<HostKeyVerifier> { coEvery { verify(any()) } returns HostKeyResult.Trusted }
        val c = runBlocking { SshlibSftpConnector.dialAndAuth(config, verifier) }
        client = c
        forwarders = SshlibPortForwarders(c)
    }

    private fun fwd() = forwarders ?: error("not connected")

    override fun setLocal(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int =
        fwd().setLocal(bindAddress, localPort, remoteHost, remotePort)

    override fun delLocal(bindAddress: String, localPort: Int) = fwd().delLocal(bindAddress, localPort)

    override fun setDynamic(bindAddress: String, bindPort: Int): Int = fwd().setDynamic(bindAddress, bindPort)

    override fun setRemote(bindAddress: String, remotePort: Int, localHost: String, localPort: Int) =
        fwd().setRemote(bindAddress, remotePort, localHost, localPort)

    override fun delRemote(remotePort: Int) = fwd().delRemote(remotePort)

    override fun disconnect() {
        forwarders?.closeAll()
        client?.let { runCatching { runBlocking { it.disconnect() } } }
    }
}
