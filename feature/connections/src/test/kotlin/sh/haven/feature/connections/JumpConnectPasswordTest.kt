package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * #381: a jump-host connect must not hand the jump profile's saved *login*
 * password to a key as its passphrase — that broke passphrase-protected keys
 * on the jump leg while a direct connect worked.
 */
class JumpConnectPasswordTest {

    private fun profile(
        authMethods: String = "PASSWORD",
        keyId: String? = null,
        ignoreSavedKeys: Boolean = false,
    ) = ConnectionProfile(
        id = "jump", label = "jump", host = "h", username = "u",
        connectionType = "SSH", authMethods = authMethods,
        keyId = keyId, ignoreSavedKeys = ignoreSavedKeys,
    )

    // ── jumpConnectPassword ─────────────────────────────────────────────

    @Test
    fun `a typed password always wins`() {
        assertEquals("typed", jumpConnectPassword("typed", jumpUsesKeyAuth = true, savedSshPassword = "saved"))
        assertEquals("typed", jumpConnectPassword("typed", jumpUsesKeyAuth = false, savedSshPassword = "saved"))
    }

    @Test
    fun `key-auth jump does NOT borrow the saved login password (#381)`() {
        // The regression: this used to return "saved", which then became the
        // encrypted key's passphrase and stopped it decrypting.
        assertEquals("", jumpConnectPassword("", jumpUsesKeyAuth = true, savedSshPassword = "saved"))
    }

    @Test
    fun `password-auth jump still borrows the saved password (#121)`() {
        assertEquals("saved", jumpConnectPassword("", jumpUsesKeyAuth = false, savedSshPassword = "saved"))
    }

    @Test
    fun `password-auth jump with no saved password yields empty`() {
        assertEquals("", jumpConnectPassword("", jumpUsesKeyAuth = false, savedSshPassword = null))
    }

    // ── profileUsesKeyAuth ──────────────────────────────────────────────

    @Test
    fun `legacy keyId counts as key auth`() {
        assertTrue(profileUsesKeyAuth(profile(keyId = "k1")))
    }

    @Test
    fun `a Key auth-method spec counts as key auth`() {
        assertTrue(profileUsesKeyAuth(profile(authMethods = "KEY:k1")))
    }

    @Test
    fun `password-only profile is not key auth`() {
        assertFalse(profileUsesKeyAuth(profile(authMethods = "PASSWORD")))
    }

    @Test
    fun `ignoreSavedKeys forces password even with a keyId`() {
        assertFalse(profileUsesKeyAuth(profile(keyId = "k1", ignoreSavedKeys = true)))
    }
}
