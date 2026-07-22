package sh.haven.core.ssh

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * Engine-agnostic contract for SSH port forwarding (#58 phase 6): a local
 * (`-L`) forward carries TCP bytes end-to-end and is torn down by `del`, a
 * dynamic (`-D`) SOCKS5 forward reaches an arbitrary target, and a remote
 * (`-R`) forward carries bytes back — all against a real MINA sshd with
 * forwarding enabled, forwarding to an in-process echo server.
 *
 * [JschPortForwardContractTest] pins today's behaviour; the sshlib subclass
 * proves [sh.haven.core.ssh.sshlib.SshlibPortForwarders] (and sshlib's
 * built-in SOCKS, which replaces Haven's DynamicForwardServer) match it.
 */
abstract class PortForwardContractTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    protected lateinit var server: SshServer
    protected var serverPort: Int = 0

    private lateinit var echo: ServerSocket
    protected var echoPort: Int = 0
    @Volatile private var echoRunning = true

    /** Establish/authenticate the engine's connection to 127.0.0.1:[serverPort]. */
    protected abstract fun connect(host: String, port: Int, username: String, password: String)
    protected abstract fun setLocal(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int
    protected abstract fun delLocal(bindAddress: String, localPort: Int)
    protected abstract fun setDynamic(bindAddress: String, bindPort: Int): Int
    protected abstract fun setRemote(bindAddress: String, remotePort: Int, localHost: String, localPort: Int)
    protected abstract fun delRemote(remotePort: Int)
    protected abstract fun disconnect()

    @Before
    fun setUp() {
        // Line-echo TCP server: reads a line, writes it straight back.
        echo = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        echoPort = echo.localPort
        thread(isDaemon = true, name = "echo-server") {
            while (echoRunning) {
                val socket = try { echo.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    socket.use { s ->
                        try {
                            val input = s.getInputStream()
                            val output = s.getOutputStream()
                            val buf = ByteArray(1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                output.flush()
                            }
                        } catch (_: Exception) { /* client closed */ }
                    }
                }
            }
        }

        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("fwd-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        server.start()
        serverPort = server.port
        connect("127.0.0.1", serverPort, "tester", "secret")
    }

    @After
    fun tearDown() {
        try { disconnect() } catch (_: Exception) {}
        echoRunning = false
        try { echo.close() } catch (_: Exception) {}
        if (::server.isInitialized) server.stop(true)
    }

    private fun roundtrip(socket: Socket): ByteArray {
        socket.use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply { write("ping\n".toByteArray()); flush() }
            val buf = ByteArray(5)
            var read = 0
            while (read < 5) {
                val n = s.getInputStream().read(buf, read, 5 - read)
                if (n < 0) break
                read += n
            }
            return buf.copyOf(read)
        }
    }

    @Test
    fun `local forward carries data and del tears it down`() {
        val bound = setLocal("127.0.0.1", 0, "127.0.0.1", echoPort)
        assertArrayEquals("ping\n".toByteArray(), roundtrip(Socket("127.0.0.1", bound)))

        delLocal("127.0.0.1", bound)
        // Listener gone: a fresh connect is refused (allow a moment to unbind).
        Thread.sleep(300)
        assertThrows(ConnectException::class.java) {
            Socket("127.0.0.1", bound).use { it.getInputStream().read() }
        }
    }

    @Test
    fun `dynamic SOCKS5 forward reaches an arbitrary target`() {
        val bound = setDynamic("127.0.0.1", 0)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", bound))
        val socket = Socket(proxy).apply { connect(InetSocketAddress("127.0.0.1", echoPort), 10_000) }
        assertArrayEquals("ping\n".toByteArray(), roundtrip(socket))
    }

    @Test
    fun `remote forward carries data back to a local target`() {
        // Pick a free port for the server-side bind, then forward it to the echo.
        val remotePort = ServerSocket(0).use { it.localPort }
        setRemote("127.0.0.1", remotePort, "127.0.0.1", echoPort)
        // The MINA server bound remotePort on 127.0.0.1 (same host in-process).
        Thread.sleep(300)
        assertArrayEquals("ping\n".toByteArray(), roundtrip(Socket("127.0.0.1", remotePort)))
        delRemote(remotePort)
    }
}
