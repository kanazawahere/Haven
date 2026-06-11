package sh.haven.core.data.mailrule

import sh.haven.core.data.db.entities.MailRulePendingAction

/**
 * Runs a queued destructive [MailRulePendingAction] now that a human has approved it.
 *
 * The Mail-Rules executor only *queues* destructive actions (move/delete/forward/run-command,
 * or a non-NEVER MCP tool) that match while Haven is backgrounded — nothing in the engine
 * re-runs them. This interface closes that loop: the rules UI surfaces the queue and calls
 * [runApproved] on the user's tap. It lives in `core/data` (not `app`) so the feature-layer
 * UI — which cannot depend on `app` — can trigger the app-layer executor.
 *
 * @return true if the action executed successfully and its pending row was cleared.
 */
interface MailRulePendingRunner {
    suspend fun runApproved(pending: MailRulePendingAction): Boolean
}

/**
 * Requests an immediate Mail-Rules poll. No-op when the master switch is off. Implemented by
 * the app-layer watch manager; lets the rules UI evaluate a freshly-saved or just-enabled rule
 * without waiting for the next poll cycle.
 */
interface MailWatchPoker {
    fun pokeNow()
}
