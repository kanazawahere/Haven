package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private const val TAG = "ProxyJump"

/**
 * JSch [Proxy] implementation that tunnels through an existing SSH session
 * using a `direct-tcpip` channel, equivalent to `ssh -J` (ProxyJump).
 *
 * The jump host [Session] must already be connected before this proxy is used.
 */
class ProxyJump(private val jumpSession: Session) : Proxy {

    private var channel: Channel? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        Log.d(TAG, "Opening direct-tcpip channel to $host:$port (timeout=${timeout}ms, jumpConnected=${jumpSession.isConnected})")
        val ch = jumpSession.getStreamForwarder(host, port)
        // Bind the streams BEFORE connecting: getInputStream() is what installs
        // JSch's server→client pipe, and Channel.write() swallows the NPE when
        // it isn't there yet — so the target's SSH banner, sent the moment the
        // channel opens, is silently dropped. JSch would then wait forever for
        // a banner that is never resent, and a proxied session has no socket for
        // the connect timeout to fire on. JSch says so itself: "getInputStream()
        // should be called before connect()". (#381)
        input = ch.inputStream
        output = ch.outputStream
        ch.connect(timeout)
        channel = ch
        Log.d(TAG, "Channel connected to $host:$port")
    }

    override fun getInputStream(): InputStream = input!!

    override fun getOutputStream(): OutputStream = output!!

    override fun getSocket(): Socket? = null

    override fun close() {
        channel?.disconnect()
        channel = null
        input = null
        output = null
    }
}
