package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.PortForwarder
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.SshIoException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps Haven's stateful port-forwarding surface (#58, phase 6) onto sshlib's
 * `PortForwarder`-returning API.
 *
 * Haven's `SshConnection` exposes JSch-shaped forwarding: `setPortForwardingL`
 * returns the actual bound port and `delPortForwardingL(bindAddress, port)`
 * tears it down by address+port. sshlib instead hands back a [PortForwarder]
 * object per forward, so this keeps a map keyed the way Haven's `del*` calls
 * arrive — by the ACTUAL bound port (Haven passes `actualBoundPort` to del),
 * so an ephemeral `:0` request is torn down by the port sshlib chose.
 *
 * sshlib's built-in SOCKS5 dynamic forward replaces Haven's own
 * `DynamicForwardServer` for this engine.
 */
internal class SshlibPortForwarders(private val client: SshlibClient) {

    private val forwarders = ConcurrentHashMap<String, PortForwarder>()

    fun setLocal(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int {
        val fwd = runBlocking {
            client.localPortForward(InetSocketAddress(bindAddress, localPort), remoteHost, remotePort)
        } ?: throw SshIoException("sshlib: local forward $bindAddress:$localPort → $remoteHost:$remotePort failed")
        forwarders[localKey(bindAddress, fwd.boundPort)] = fwd
        return fwd.boundPort
    }

    fun delLocal(bindAddress: String, localPort: Int) {
        forwarders.remove(localKey(bindAddress, localPort))?.let { stop(it) }
    }

    fun setRemote(bindAddress: String, remotePort: Int, localHost: String, localPort: Int) {
        val fwd = runBlocking {
            client.remotePortForward(bindAddress, remotePort, localHost, localPort)
        } ?: throw SshIoException("sshlib: remote forward $bindAddress:$remotePort → $localHost:$localPort failed")
        forwarders[remoteKey(remotePort)] = fwd
    }

    fun delRemote(remotePort: Int) {
        forwarders.remove(remoteKey(remotePort))?.let { stop(it) }
    }

    fun setDynamic(bindAddress: String, bindPort: Int): Int {
        val fwd = runBlocking {
            client.dynamicPortForward(InetSocketAddress(bindAddress, bindPort))
        } ?: throw SshIoException("sshlib: dynamic (SOCKS) forward $bindAddress:$bindPort failed")
        forwarders[dynamicKey(bindAddress, fwd.boundPort)] = fwd
        return fwd.boundPort
    }

    fun delDynamic(bindAddress: String, bindPort: Int) {
        forwarders.remove(dynamicKey(bindAddress, bindPort))?.let { stop(it) }
    }

    /** Stop every forward — called when the owning connection is torn down. */
    fun closeAll() {
        forwarders.values.forEach { stop(it) }
        forwarders.clear()
    }

    private fun stop(fwd: PortForwarder) {
        runCatching { runBlocking { fwd.stop() } }
    }

    private companion object {
        fun localKey(bindAddress: String, port: Int) = "L:$bindAddress:$port"
        fun remoteKey(port: Int) = "R:$port"
        fun dynamicKey(bindAddress: String, port: Int) = "D:$bindAddress:$port"
    }
}
