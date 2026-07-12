package sh.haven.core.local

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-manager launch wrapper (#294): tmux/zellij/etc that fail to
 * start must drop the user into a login shell instead of killing the tab.
 */
class SessionManagerWrapperTest {

    private val tmuxCmd =
        "tmux new-session -A -s haven-local \\; set -gq allow-passthrough on \\; set -gq mouse on"

    @Test
    fun `missing binary falls back to a login shell`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        assertTrue("guards on command -v", w.contains("command -v tmux"))
        assertTrue("else-branch execs a login shell", w.contains("else exec /bin/sh -l; fi"))
    }

    @Test
    fun `installed-but-failing session manager degrades to a shell instead of exiting`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The command is run (not exec'd) so a non-zero exit can trip the ||.
        assertTrue("does not exec the session manager", !w.contains("then exec $tmuxCmd"))
        assertTrue("runs the command", w.contains("then $tmuxCmd ||"))
        assertTrue("|| falls back to a login shell", w.contains("exec /bin/sh -l; }"))
        assertTrue("surfaces the failure", w.contains("exited unexpectedly"))
    }

    @Test
    fun `tmux command separators are preserved verbatim`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The escaped ';' must reach tmux as literal args, not shell separators.
        assertTrue(w.contains("new-session -A -s haven-local \\; set -gq allow-passthrough on"))
    }

    @Test
    fun `zellij is wrapped the same way`() {
        val w = sessionManagerWrapper("zellij", "zellij attach haven-local --create")
        assertTrue(w.contains("command -v zellij"))
        assertTrue(w.contains("zellij attach haven-local --create ||"))
    }
}
