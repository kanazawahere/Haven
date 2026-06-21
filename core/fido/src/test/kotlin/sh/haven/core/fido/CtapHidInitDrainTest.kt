package sh.haven.core.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Channel-INIT must drain CTAPHID_KEEPALIVE (and stray frames) instead of
 * failing on the first non-INIT packet — that hard failure on a busy key
 * (reply 0xbb) was the FIDO-auth-on-reconnect storm seen on a flaky link.
 */
class CtapHidInitDrainTest {

    // Raw on-wire cmd bytes: init packets carry bit 7. INIT=0x06, KEEPALIVE=0x3B, ERROR=0x3F.
    private val INIT = 0x86.toByte()
    private val KEEPALIVE = 0xBB.toByte()
    private val ERROR = 0xBF.toByte()

    private fun frame(cmd: Byte, payload: ByteArray = ByteArray(17)) =
        Triple(byteArrayOf(0, 0, 0, 0), cmd, payload)

    private fun source(vararg frames: Triple<ByteArray, Byte, ByteArray>): () -> Triple<ByteArray, Byte, ByteArray> {
        val q = ArrayDeque(frames.toList())
        return { q.removeFirst() }
    }

    @Test
    fun `returns the init reply when it arrives first`() {
        val reply = ByteArray(17) { it.toByte() }
        val r = drainToInitReply(MAX_READS, source(frame(INIT, reply)))
        assertArrayEquals(reply, r.third)
    }

    @Test
    fun `drains keepalives then returns the init reply`() {
        val reply = ByteArray(17) { 9 }
        val r = drainToInitReply(
            MAX_READS,
            source(frame(KEEPALIVE), frame(KEEPALIVE), frame(KEEPALIVE), frame(INIT, reply)),
        )
        assertEquals(INIT, r.second)
        assertArrayEquals(reply, r.third)
    }

    @Test
    fun `skips a stray non-init frame then succeeds`() {
        val r = drainToInitReply(MAX_READS, source(frame(0x90.toByte()), frame(INIT)))
        assertEquals(INIT, r.second)
    }

    @Test(expected = IOException::class)
    fun `throws on ctaphid error during init`() {
        drainToInitReply(MAX_READS, source(frame(ERROR, byteArrayOf(0x06))))
    }

    @Test(expected = IOException::class)
    fun `throws after maxReads keepalives without an init reply`() {
        var n = 0
        drainToInitReply(4) { n++; frame(KEEPALIVE) }
    }

    private companion object {
        const val MAX_READS = 16
    }
}
