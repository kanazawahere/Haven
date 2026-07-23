package sh.haven.feature.terminal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-contract tests for [FloatingInputTextToolbar], the custom
 * `TextToolbar` that replaces the (silently broken inside a focusable Popup)
 * platform ActionMode toolbar for the floating text input dialog.
 *
 * These are plain-JVM tests: `androidx.compose.ui.geometry.Rect` and the
 * snapshot-state machinery both run without an Android runtime. What they can
 * NOT prove is that the menu actually renders and is tappable — this module
 * has no Robolectric/compose-ui-test setup, so that part needs a real
 * device/emulator (see docs/plans/floating-text-input-toolbar-fix.md,
 * "What to verify before calling this done").
 */
class FloatingInputTextToolbarTest {

    private val rect = Rect(10f, 20f, 110f, 60f)

    @Test
    fun `starts hidden with no menu request`() {
        val toolbar = FloatingInputTextToolbar()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertNull(toolbar.menuRequest)
    }

    @Test
    fun `showMenu exposes request, status and pass-through callbacks`() {
        val toolbar = FloatingInputTextToolbar()
        var copied = false
        var selectedAll = false

        toolbar.showMenu(
            rect = rect,
            onCopyRequested = { copied = true },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = { selectedAll = true },
        )

        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        val request = toolbar.menuRequest
        assertNotNull(request)
        request!!
        assertEquals(rect, request.rect)

        // Applicable actions pass straight through to the machinery's
        // callbacks; inapplicable ones (null) stay null so the menu can
        // omit those buttons, like the platform toolbar does.
        assertNotNull(request.onCopyRequested)
        assertNotNull(request.onSelectAllRequested)
        assertNull(request.onPasteRequested)
        assertNull(request.onCutRequested)

        request.onCopyRequested!!.invoke()
        request.onSelectAllRequested!!.invoke()
        assertTrue(copied)
        assertTrue(selectedAll)
    }

    @Test
    fun `hide clears the request and reports hidden`() {
        val toolbar = FloatingInputTextToolbar()
        toolbar.showMenu(rect, { }, { }, { }, { })
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        toolbar.hide()

        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertNull(toolbar.menuRequest)
    }

    @Test
    fun `subsequent showMenu replaces the previous request`() {
        val toolbar = FloatingInputTextToolbar()
        toolbar.showMenu(rect, { }, null, null, null)
        val first = toolbar.menuRequest

        val newRect = Rect(0f, 0f, 50f, 25f)
        toolbar.showMenu(newRect, null, { }, null, null)
        val second = toolbar.menuRequest

        assertNotNull(second)
        assertTrue(first !== second)
        assertEquals(newRect, second!!.rect)
        assertNull(second.onCopyRequested)
        assertNotNull(second.onPasteRequested)
    }
}
