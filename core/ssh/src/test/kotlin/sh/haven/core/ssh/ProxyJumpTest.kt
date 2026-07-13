package sh.haven.core.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The jump channel's streams must be bound BEFORE the channel is connected.
 *
 * JSch installs the server→client pipe inside `Channel.getInputStream()`
 * (`io.setOutputStream(PassiveOutputStream)`), and `Channel.write()` swallows
 * the NullPointerException when that pipe isn't there yet — so bytes arriving
 * before it exists are silently discarded. The target's SSH version banner is
 * sent the moment the direct-tcpip channel opens, so connecting first and
 * fetching the streams afterwards races the banner into the void: the KEX read
 * then blocks forever, and a proxied session has no socket for the connect
 * timeout to fire on (#381).
 */
class ProxyJumpTest {

    @Test
    fun `binds channel streams before connecting the channel`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns input
        every { channel.outputStream } returns output

        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder("internal.example", 22) } returns channel

        val proxy = ProxyJump(session)
        proxy.connect(null, "internal.example", 22, 10_000)

        verifyOrder {
            channel.inputStream
            channel.outputStream
            channel.connect(10_000)
        }
        assertSame(input, proxy.inputStream)
        assertSame(output, proxy.outputStream)
    }

    @Test
    fun `close disconnects the channel`() {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder(any(), any()) } returns channel

        ProxyJump(session).apply {
            connect(null, "internal.example", 22, 10_000)
            close()
        }

        io.mockk.verify { channel.disconnect() }
    }
}
