package sh.haven.app.desktop

import sh.haven.core.spice.SpiceSession
import sh.haven.spice.MouseButton

/**
 * [RemoteDesktopSession] adapter over [SpiceSession]. Mirrors [RdpDesktopSession].
 *
 * Translation points:
 *  - `Int` button numbers (X11: 1=left, 2=middle, 3=right) → SPICE's [MouseButton].
 *  - Mouse wheel: the abstraction's `deltaY` sign carries direction; SPICE takes a
 *    single signed step (positive = up), so the sign passes straight through.
 *  - SPICE has no clipboard verb yet, so `sendClipboardText` is a no-op.
 */
class SpiceDesktopSession(private val session: SpiceSession) : RemoteDesktopSession {
    override fun sendMouseMove(x: Int, y: Int) {
        session.sendMouseMove(x, y)
    }

    override fun sendMouseButton(button: Int, pressed: Boolean) {
        session.sendMouseButton(toSpiceButton(button), pressed)
    }

    override fun sendMouseClick(x: Int, y: Int, button: Int) {
        session.sendMouseClick(x, y, toSpiceButton(button))
    }

    override fun sendMouseWheel(deltaY: Int) {
        if (deltaY == 0) return
        session.sendMouseWheel(if (deltaY > 0) 1 else -1)
    }

    // ponytail: SPICE transport has no clipboard verb yet — no-op until it does.
    override fun sendClipboardText(text: String) {}

    /** SPICE is server-push; there is no client-side throttle. */
    override fun pause() {}

    override fun resume() {}

    override fun close() {
        session.close()
    }

    private fun toSpiceButton(x11Button: Int): MouseButton = when (x11Button) {
        2 -> MouseButton.MIDDLE
        3 -> MouseButton.RIGHT
        else -> MouseButton.LEFT
    }
}
