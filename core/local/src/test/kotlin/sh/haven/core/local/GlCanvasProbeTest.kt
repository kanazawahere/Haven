package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlCanvasProbeTest {

    private fun fill(w: Int, h: Int, argb: Int) = IntArray(w * h) { argb }

    @Test
    fun flatFillIsBlank() {
        val v = GlCanvasProbe.analyze(fill(64, 64, 0xFFFFFFFF.toInt()), 64, 64)
        assertFalse("a uniform white frame must read blank", v.nonBlank)
        assertEquals(1, v.distinctColors)
    }

    @Test
    fun nearlyFlatWithSpeckleIsBlank() {
        // Mostly white with two stray dark pixels: distinct stays well below
        // the threshold, so the speckle must not flip the verdict to non-blank.
        val px = fill(64, 64, 0xFFFFFFFF.toInt())
        px[0] = 0xFF000000.toInt()
        px[8] = 0xFF000000.toInt()
        val v = GlCanvasProbe.analyze(px, 64, 64)
        assertFalse(v.nonBlank)
    }

    @Test
    fun gradientIsNonBlank() {
        val w = 64
        val h = 64
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 4) shl 16) or ((y * 4) shl 8)
        }
        val v = GlCanvasProbe.analyze(px, w, h)
        assertTrue("a colour gradient must read non-blank", v.nonBlank)
        assertTrue(v.distinctColors > 12)
        assertTrue(v.topColorFraction < 0.985)
    }

    @Test
    fun dominantBackgroundWithSmallContentStaysBlank() {
        // ~99% one colour, a small noisy block: topFraction guard keeps it blank
        // even if the block adds many distinct colours.
        val w = 100
        val h = 100
        val px = fill(w, h, 0xFF101010.toInt())
        for (y in 0 until 6) for (x in 0 until 6) {
            px[y * w + x] = (0xFF shl 24) or (x * 30 shl 16) or (y * 30 shl 8) or (x * y)
        }
        val v = GlCanvasProbe.analyze(px, w, h)
        assertFalse("a tiny content patch on a flat field stays blank", v.nonBlank)
    }
}
