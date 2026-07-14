package sh.haven.core.ssh

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SshNetworkErrorTest {

    /**
     * The exception #367 actually produced: a MacroDroid `run_command` against a
     * hotspot client whose DHCP lease had rotated. JSch rethrows the underlying
     * ConnectException as a JSchException whose message is the wrapped exception's
     * `toString()`, and Android renders EHOSTUNREACH as "No route to host" — so the
     * old check (top-level type + "refused"/"timed out"/"unreachable") matched
     * nothing, host rediscovery never fired, and the user had to retype the address.
     */
    @Test
    fun jschWrappedEhostunreachIsANetworkError() {
        val cause = ConnectException(
            "failed to connect to /10.252.250.53 (port 22) from /10.252.250.95 (port 44506) " +
                "after 9998ms: isConnected failed: EHOSTUNREACH (No route to host)",
        )
        val wrapped = JSchException(cause.toString(), cause)

        assertTrue("EHOSTUNREACH through a JSchException must trigger rediscovery", wrapped.isSshNetworkError())
    }

    @Test
    fun noRouteToHostTextAloneIsANetworkError() {
        // Same failure, but only the rendered text survives (some layers drop the cause).
        assertTrue(JSchException("java.net.ConnectException: No route to host").isSshNetworkError())
    }

    @Test
    fun wrappedTimeoutAndUnknownHostAreNetworkErrors() {
        assertTrue(JSchException("timeout", SocketTimeoutException("Read timed out")).isSshNetworkError())
        assertTrue(JSchException("unknown", UnknownHostException("comma.local")).isSshNetworkError())
        assertTrue(JSchException("java.net.ConnectException: Connection refused").isSshNetworkError())
    }

    @Test
    fun bareNetworkExceptionsStillMatch() {
        assertTrue(ConnectException("Connection refused").isSshNetworkError())
    }

    /**
     * The other half of the contract: an auth failure must NOT look like a network
     * failure, or a wrong password would silently send Haven hunting the subnet for
     * a new address instead of prompting.
     */
    @Test
    fun authFailureIsNotANetworkError() {
        assertFalse(JSchException("Auth fail for methods 'publickey,password'").isSshNetworkError())
        assertFalse(JSchException("USERAUTH fail").isSshNetworkError())
        assertFalse(JSchException("Incorrect passphrase provided.").isSshNetworkError())
    }

    @Test
    fun selfReferencingCauseDoesNotHang() {
        // Defensive: a cyclic cause chain must terminate rather than spin.
        val looper = object : RuntimeException("host key mismatch") {
            override val cause: Throwable get() = this
        }

        assertFalse(looper.isSshNetworkError())
    }
}
