package sh.haven.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseModeTrackerTest {

    private val esc = "\u001b"

    /** Feed a string verbatim (caller includes ESC where needed). */
    private fun MouseModeTracker.feed(s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        process(b, 0, b.size)
    }

    @Test
    fun `alt screen toggles on 1049 enable and disable`() {
        val t = MouseModeTracker()
        assertFalse(t.altScreen.value)
        t.feed("$esc[?1049h")
        assertTrue(t.altScreen.value)
        t.feed("$esc[?1049l")
        assertFalse(t.altScreen.value)
    }

    @Test
    fun `legacy alt screen modes 1047 and 47 also toggle`() {
        val t = MouseModeTracker()
        t.feed("$esc[?47h")
        assertTrue(t.altScreen.value)
        t.feed("$esc[?47l")
        assertFalse(t.altScreen.value)

        t.feed("$esc[?1047h")
        assertTrue(t.altScreen.value)
        t.feed("$esc[?1047l")
        assertFalse(t.altScreen.value)
    }

    @Test
    fun `alt screen and mouse mode are independent`() {
        val t = MouseModeTracker()
        t.feed("$esc[?1049h$esc[?1000h") // vim: alt screen + basic mouse
        assertTrue(t.altScreen.value)
        assertTrue(t.mouseMode.value)
        // Leaving the app: mouse off, then back to primary screen.
        t.feed("$esc[?1000l$esc[?1049l")
        assertFalse(t.mouseMode.value)
        assertFalse(t.altScreen.value)
    }

    @Test
    fun `alt screen mode does not flip mouse mode`() {
        val t = MouseModeTracker()
        t.feed("$esc[?1049h")
        assertTrue(t.altScreen.value)
        assertFalse(t.mouseMode.value)
    }

    @Test
    fun `sequence split across buffer boundaries is detected`() {
        val t = MouseModeTracker()
        // ESC[?10  +  49h  arriving in two reads.
        t.feed("$esc[?10")
        t.feed("49h")
        assertTrue(t.altScreen.value)
    }

    @Test
    fun `multiple alt modes track via a set`() {
        val t = MouseModeTracker()
        t.feed("$esc[?47h$esc[?1049h")
        assertTrue(t.altScreen.value)
        // Only one of the two cleared — still on the alt screen.
        t.feed("$esc[?47l")
        assertTrue(t.altScreen.value)
        t.feed("$esc[?1049l")
        assertFalse(t.altScreen.value)
    }

    @Test
    fun `combined mode sequence enables alt screen and mouse together`() {
        val t = MouseModeTracker()
        t.feed("$esc[?1049;1000h")
        assertTrue(t.altScreen.value)
        assertTrue(t.mouseMode.value)
    }

    @Test
    fun `DECCKM toggles application cursor key mode`() {
        val t = MouseModeTracker()
        assertFalse(t.cursorKeyAppMode.value)
        t.feed("$esc[?1h")
        assertTrue(t.cursorKeyAppMode.value)
        t.feed("$esc[?1l")
        assertFalse(t.cursorKeyAppMode.value)
    }

    @Test
    fun `vim-style startup sets DECCKM and alt screen together`() {
        val t = MouseModeTracker()
        // vim emits smkx (ESC[?1h ESC=) then smcup (ESC[?1049h).
        t.feed("$esc[?1h$esc=$esc[?1049h")
        assertTrue(t.cursorKeyAppMode.value)
        assertTrue(t.altScreen.value)
        // :q restores both.
        t.feed("$esc[?1049l$esc[?1l")
        assertFalse(t.cursorKeyAppMode.value)
        assertFalse(t.altScreen.value)
    }

    @Test
    fun `arrow key bytes follow DECCKM encoding`() {
        assertTrue(arrowKeyBytes(true, false).contentEquals("$esc[A".toByteArray()))
        assertTrue(arrowKeyBytes(false, false).contentEquals("$esc[B".toByteArray()))
        assertTrue(arrowKeyBytes(true, true).contentEquals("${esc}OA".toByteArray()))
        assertTrue(arrowKeyBytes(false, true).contentEquals("${esc}OB".toByteArray()))
    }
}
