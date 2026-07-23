package sh.haven.feature.terminal

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-contract tests for [FloatingInputTextContextMenuProvider], the
 * `TextContextMenuProvider` that replaces the (silently broken inside a
 * focusable Popup) platform ActionMode toolbar on the NEW flag-gated
 * context-menu system — the one that is actually active on the pinned
 * foundation 1.11.4 (`ComposeFoundationFlags.isNewContextMenuEnabled`
 * compiles to `true` there).
 *
 * These are plain-JVM coroutine tests: they prove the suspend/session/state
 * contract (show parks a request, close/cancel resolves and clears it,
 * concurrent shows don't clobber each other). What they can NOT prove is
 * that Compose's selection machinery actually calls this provider on a real
 * device, nor that the menu renders and is tappable — this module has no
 * Robolectric/compose-ui-test setup, so that part needs a real
 * device/emulator (see docs/plans/floating-text-input-toolbar-fix.md).
 */
class FloatingInputTextContextMenuProviderTest {

    private class FakeDataProvider : TextContextMenuDataProvider {
        override fun position(destinationCoordinates: LayoutCoordinates): Offset = Offset.Zero

        override fun contentBounds(destinationCoordinates: LayoutCoordinates): Rect = Rect.Zero

        override fun data(): TextContextMenuData = TextContextMenuData(emptyList())
    }

    @Test
    fun `showTextContextMenu parks a request with the given data provider`() = runTest {
        val provider = FloatingInputTextContextMenuProvider()
        val dataProvider = FakeDataProvider()
        assertNull(provider.request)

        val job = launch { provider.showTextContextMenu(dataProvider) }
        testScheduler.runCurrent()

        val request = provider.request
        assertNotNull(request)
        assertSame(dataProvider, request!!.dataProvider)
        assertTrue(job.isActive) // still suspended while the menu is "shown"

        job.cancelAndJoin()
    }

    @Test
    fun `closing the session resumes the suspend and clears the request`() = runTest {
        val provider = FloatingInputTextContextMenuProvider()
        val job = launch { provider.showTextContextMenu(FakeDataProvider()) }
        testScheduler.runCurrent()
        val request = provider.request
        assertNotNull(request)

        request!!.session.close()
        job.join()

        assertTrue(job.isCompleted)
        assertNull(provider.request)
    }

    @Test
    fun `double close is harmless`() = runTest {
        val provider = FloatingInputTextContextMenuProvider()
        val job = launch { provider.showTextContextMenu(FakeDataProvider()) }
        testScheduler.runCurrent()
        val session = provider.request!!.session

        session.close()
        session.close() // must not throw or double-resume
        job.join()

        assertNull(provider.request)
    }

    @Test
    fun `cancellation by the caller clears the request`() = runTest {
        // This is the hide() path: TextContextMenuToolbarHandlerNode cancels
        // the coroutine when the selection machinery wants the menu gone.
        val provider = FloatingInputTextContextMenuProvider()
        val job = launch { provider.showTextContextMenu(FakeDataProvider()) }
        testScheduler.runCurrent()
        assertNotNull(provider.request)

        job.cancelAndJoin()

        assertNull(provider.request)
    }

    @Test
    fun `a newer concurrent show replaces the request and survives the older call's teardown`() = runTest {
        val provider = FloatingInputTextContextMenuProvider()
        val first = launch { provider.showTextContextMenu(FakeDataProvider()) }
        testScheduler.runCurrent()
        val firstRequest = provider.request
        assertNotNull(firstRequest)

        val secondData = FakeDataProvider()
        val second = launch { provider.showTextContextMenu(secondData) }
        testScheduler.runCurrent()
        val secondRequest = provider.request
        assertNotNull(secondRequest)
        assertTrue(firstRequest !== secondRequest)
        assertSame(secondData, secondRequest!!.dataProvider)

        // The older call being torn down must NOT wipe the newer request.
        first.cancelAndJoin()
        assertSame(secondRequest, provider.request)

        second.cancelAndJoin()
        assertNull(provider.request)
    }
}
