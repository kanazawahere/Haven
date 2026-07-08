package sh.haven.core.data.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #355: the consent/pairing sheets render in their own Compose windows, which
 * `dump_haven_ui` / `capture_haven_ui` cannot see, so `get_pending_consent`
 * projects [AgentConsentManager.pending] instead. These tests pin the data the
 * projection depends on — that a *held* request (backgrounded, #337 mechanism 3)
 * is observable while it waits, and that pairing requests are distinguishable.
 */
class ConsentQueueProjectionTest {

    @Test
    fun `a request held for foreground is visible in the pending queue`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val call = async {
            mgr.requestConsent("run_in_proot", "agent-A", "apt-get install foo", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        val pending = mgr.pending.value
        assertEquals(1, pending.size)
        val r = pending.single()
        assertEquals("run_in_proot", r.toolName)
        assertEquals("agent-A", r.clientHint)
        assertEquals("apt-get install foo", r.summary)
        assertTrue("requestedAt is stamped", r.requestedAt > 0)
        assertTrue("id is routable", r.id > 0)

        mgr.respond(r.id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, call.await())
        assertTrue("queue drains once answered", mgr.pending.value.isEmpty())
    }

    /**
     * The bug this file exists to prevent: with the request enqueued only AFTER
     * foreground returned, a held call was invisible — `pending` was empty and an
     * agent could not tell "waiting for the user" from "denied". Found by driving
     * a real backgrounded call on-device, not by the unit tests, which only
     * looked after foreground.
     */
    @Test
    fun `a request held while BACKGROUNDED is observable, not silently absent`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        // foreground defaults to false — the call holds.
        val call = async {
            mgr.requestConsent("run_in_proot", "agent-A", "echo hi", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        val held = mgr.pending.value
        assertEquals("a held request must appear in the queue", 1, held.size)
        assertEquals("run_in_proot", held.single().toolName)
        assertEquals("agent-A", held.single().clientHint)

        // It resolves against the SAME request once the user returns and answers.
        mgr.setForegroundActive(true)
        mgr.respond(held.single().id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, call.await())
    }

    @Test
    fun `pairing requests are distinguishable from tool consent`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val pair = async { mgr.requestClientPairing("laptop", "1.0", timeoutMs = Long.MAX_VALUE) }
        val r = mgr.pending.value.single()
        assertEquals(AgentConsentManager.PAIRING_TOOL_NAME, r.toolName)
        assertTrue("summary names the client", r.summary.contains("laptop"))

        mgr.respond(r.id, ConsentDecision.DENY)
        pair.await()
    }

    @Test
    fun `several queued requests are exposed oldest-first`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async { mgr.requestConsent("run_in_proot", "a", "one", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE) }
        val second = async { mgr.requestConsent("delete_sftp_file", "b", "two", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE) }

        val q = mgr.pending.value
        assertEquals(2, q.size)
        assertEquals("one", q[0].summary)   // the sheet renders q[0]; the cue counts the rest
        assertEquals("two", q[1].summary)
        assertTrue("ids ascend with age", q[0].id < q[1].id)

        q.toList().forEach { mgr.respond(it.id, ConsentDecision.DENY) }
        first.await(); second.await()
    }
}
