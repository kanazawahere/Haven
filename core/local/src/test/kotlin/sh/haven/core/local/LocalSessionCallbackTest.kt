package sh.haven.core.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PTY output sink must be swappable so the proot shell can outlive the
 * emulator across an Activity/ViewModel teardown and be rewired to a fresh one
 * (#272) — instead of being killed and restarted blank. forkpty isn't available
 * off-device, so this drives the sink via [LocalSession.dispatchForTest] rather
 * than a real PTY.
 */
class LocalSessionCallbackTest {

    private fun newSession(onData: (ByteArray, Int, Int) -> Unit) = LocalSession(
        sessionId = "s1",
        profileId = "p1",
        label = "test",
        command = "/bin/sh",
        args = arrayOf("/bin/sh"),
        env = arrayOf("HOME=/"),
        onDataReceived = onData,
    )

    @Test
    fun `output goes to the initial sink`() {
        val received = mutableListOf<String>()
        val session = newSession { d, o, l -> received += String(d, o, l) }

        session.dispatchForTest("hello".toByteArray(), 0, 5)

        assertEquals(listOf("hello"), received)
    }

    @Test
    fun `replaceDataCallback rewires output to the new sink only`() {
        val original = mutableListOf<String>()
        val replacement = mutableListOf<String>()
        val session = newSession { d, o, l -> original += String(d, o, l) }

        session.dispatchForTest("before".toByteArray(), 0, 6)
        session.replaceDataCallback { d, o, l -> replacement += String(d, o, l) }
        session.dispatchForTest("after".toByteArray(), 0, 5)

        // The original sink saw only what arrived before the swap; everything
        // after the swap goes exclusively to the new (reattached) emulator.
        assertEquals(listOf("before"), original)
        assertEquals(listOf("after"), replacement)
    }

    @Test
    fun `dispatch honours offset and length`() {
        var got: ByteArray? = null
        val session = newSession { d, o, l -> got = d.copyOfRange(o, o + l) }

        session.dispatchForTest("XXpayloadYY".toByteArray(), 2, 7)

        assertArrayEquals("payload".toByteArray(), got)
    }
}
