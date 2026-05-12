package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OidcDiscoveryTest {

    /**
     * Realistic shape from Authentik's `.well-known/openid-configuration`
     * trimmed to the fields Haven actually uses. Test ensures we pull the
     * three endpoints and ignore everything else without crashing on the
     * larger doc real IdPs return.
     */
    @Test
    fun `parses issuer + authorization_endpoint + token_endpoint`() {
        val body = """
            {
              "issuer": "https://auth.example.com/application/o/step-ca/",
              "authorization_endpoint": "https://auth.example.com/application/o/authorize/",
              "token_endpoint": "https://auth.example.com/application/o/token/",
              "userinfo_endpoint": "https://auth.example.com/application/o/userinfo/",
              "scopes_supported": ["openid","email","profile"],
              "response_types_supported": ["code"],
              "grant_types_supported": ["authorization_code","refresh_token"]
            }
        """.trimIndent()

        val out = OidcDiscovery.parse(body)
        assertEquals("https://auth.example.com/application/o/step-ca/", out.issuer)
        assertEquals(
            "https://auth.example.com/application/o/authorize/",
            out.authorizationEndpoint,
        )
        assertEquals("https://auth.example.com/application/o/token/", out.tokenEndpoint)
    }

    @Test
    fun `missing token_endpoint throws with helpful message`() {
        val body = """
            {
              "issuer": "https://idp/",
              "authorization_endpoint": "https://idp/authorize"
            }
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            OidcDiscovery.parse(body)
        }
        val msg = ex.message ?: ""
        assertEquals(true, msg.contains("token_endpoint"))
    }
}
