package sh.haven.core.stepca

import org.json.JSONObject

/**
 * Minimal OIDC Discovery (RFC 8414 / OpenID Connect Discovery 1.0) parser.
 *
 * step-ca's provisioner config exposes a `configurationEndpoint` URL
 * — typically a full `.well-known/openid-configuration` URL on the IdP.
 * Bootstrap fetches that document and pulls the three fields Haven needs
 * to drive the authorization code + PKCE flow: `issuer`,
 * `authorization_endpoint`, `token_endpoint`. Everything else is either
 * derivable (registration is out of band) or unused.
 *
 * The HTTP fetch lives in [StepCaApiClient.bootstrap]; this object is
 * pure parsing so it's trivially unit-testable.
 */
object OidcDiscovery {

    data class Endpoints(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
    )

    fun parse(body: String): Endpoints {
        val json = JSONObject(body)
        val issuer = json.optString("issuer").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `issuer`")
        }
        val auth = json.optString("authorization_endpoint").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `authorization_endpoint`")
        }
        val token = json.optString("token_endpoint").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `token_endpoint`")
        }
        return Endpoints(issuer = issuer, authorizationEndpoint = auth, tokenEndpoint = token)
    }
}
