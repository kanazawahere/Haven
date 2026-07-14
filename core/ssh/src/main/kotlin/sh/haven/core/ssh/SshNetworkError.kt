package sh.haven.core.ssh

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Did the peer fail to answer, as opposed to answering and saying no?
 *
 * Callers use this to tell "the box moved / is off" apart from "your password is
 * wrong", which decides whether to prompt for credentials or to go looking for the
 * host at a new address (#376 rediscovery).
 *
 * Getting it right needs two things that the hand-rolled copies of this check all
 * got wrong, and that #367 fell straight through:
 *
 * 1. **JSch wraps.** `Session.connect()` rethrows a `java.net.*` failure as a
 *    `JSchException` whose message is the wrapped exception's `toString()`. A
 *    top-level `e is ConnectException` therefore never matches — the cause chain
 *    has to be walked.
 *
 * 2. **The message is not the one you'd guess.** Android surfaces `EHOSTUNREACH`
 *    as *"No route to host"*, which contains neither "unreachable" nor "timed
 *    out". A MacroDroid `run_command` against a hotspot client whose DHCP lease
 *    had rotated produced exactly:
 *
 *    ```
 *    java.net.ConnectException: failed to connect to /10.252.250.53 (port 22) from
 *    /10.252.250.95 (port 44506) after 9998ms: isConnected failed: EHOSTUNREACH
 *    (No route to host)
 *    ```
 *
 *    — matched by none of the old substrings, so host rediscovery never fired and
 *    the user had to retype the address by hand.
 */
fun Throwable.isSshNetworkError(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH) // a self-referencing cause would otherwise spin forever
        .any { cause ->
            cause is ConnectException ||
                cause is UnknownHostException ||
                cause is SocketTimeoutException ||
                cause is NoRouteToHostException ||
                cause is PortUnreachableException ||
                cause.message.orEmpty().matchesNetworkHint()
        }

private fun String.matchesNetworkHint(): Boolean =
    NETWORK_HINTS.any { contains(it, ignoreCase = true) }

/**
 * Substrings Android, the JDK and JSch actually emit for a peer that isn't there.
 * `EHOSTUNREACH`/`ENETUNREACH` are listed alongside their human renderings because
 * which one you get depends on the layer that formatted the message.
 */
private val NETWORK_HINTS = listOf(
    "refused",
    "timed out",
    "timeout",
    "unreachable",
    "no route to host",
    "ehostunreach",
    "enetunreach",
    "etimedout",
    "network is down",
)

private const val MAX_CAUSE_DEPTH = 8
