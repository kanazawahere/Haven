package sh.haven.core.ssh

/**
 * A transport whose recovery is **not** covered by the service's standard
 * return-to-foreground probe ([SshSessionManager.probeAndReconnectStale]) or
 * network-available sweep ([SshSessionManager.requestReconnectAll]) — notably
 * the headless MCP reverse tunnel, which both of those paths deliberately skip
 * (it's owned by McpTunnelManager's own watchdog).
 *
 * [SshConnectionService] invokes every hook on return-to-foreground and on
 * network-available, so such a transport gets the same instant kick the
 * interactive SSH sessions already get, instead of waiting out its own
 * watchdog timer (a coroutine `delay` that Doze can defer well past its
 * nominal interval). Add a transport by contributing one `@IntoSet` binding,
 * not by editing the service.
 */
interface ForegroundReviveHook {
    /**
     * Proactively check liveness and reconnect now if needed. Must be cheap
     * and non-blocking — kick off any real work asynchronously; do not block
     * the caller (the service invokes this on the main thread / a debounced
     * collector).
     */
    fun reviveNow()
}
