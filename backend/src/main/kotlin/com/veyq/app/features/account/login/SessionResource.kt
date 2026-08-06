package com.veyq.app.features.account.login

import com.veyq.app.generated.api.SessionApi
import com.veyq.app.shared.http.bearerToken
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for session lifecycle. Implements the generated [SessionApi]; reads the bearer access token and
 * revokes its family (logout). Idempotent. Deliberately `@PermitAll` (cr-0012) and reads the token **manually**:
 * logout must stay a 204 no-op for any token — a present-but-invalid token must not 401, so it can't go through
 * `@Authenticated`. This is the one endpoint that still parses the header itself.
 */
@PermitAll
@RateLimitExempt(
    reason = "Logout is a lenient, idempotent 204 no-op for ANY token — valid, expired or absent — so it is " +
        "no oracle and reveals nothing to spend requests on. The only work it does is revoking a session " +
        "family the caller already holds, which strictly REDUCES what an attacker can reach.",
)
class SessionResource(private val sessions: SessionService, private val cookie: SessionCookie) : SessionApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun logout(): Response {
        bearerToken(headers)?.let { sessions.revokeByAccessToken(it) }
        // Drop both web refresh cookies too (persistent + session, cr-0055) — a no-op for native callers that
        // never set one.
        return Response.noContent().cookie(*cookie.cleared()).build()
    }
}
