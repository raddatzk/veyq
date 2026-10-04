package com.veyq.core.account.login

import com.veyq.core.generated.api.LoginApi
import com.veyq.core.generated.model.LoginChallenge
import com.veyq.core.generated.model.LoginChallengeRequest
import com.veyq.core.generated.model.LoginVerifyRequest
import com.veyq.core.generated.model.SessionFamily
import com.veyq.core.generated.model.SessionResponse
import com.veyq.core.http.FailureStatus.UNAUTHORIZED
import com.veyq.core.http.httpError
import com.veyq.core.http.httpFailure
import com.veyq.core.ratelimit.RateLimit
import com.veyq.core.ratelimit.RateLimitKey
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for challenge-response login. Implements the generated [LoginApi]; thin — maps to/from the
 * generated models and delegates to [LoginHandler] (ADR 0001). Field validation runs at the boundary. Public
 * (`@PermitAll`, cr-0012): login *establishes* the session, so it precedes authentication.
 */
@PermitAll
@RateLimit(key = RateLimitKey.IP)
class LoginResource(private val handler: LoginHandler, private val cookie: SessionCookie) : LoginApi {

    override fun requestLoginChallenge(loginChallengeRequest: LoginChallengeRequest): Response {
        val challenge = handler.challenge(loginChallengeRequest.email)
        return Response.ok(
            LoginChallenge(nonce = challenge.nonce, instanceSalt = challenge.instanceSalt),
        ).build()
    }

    override fun verifyLogin(loginVerifyRequest: LoginVerifyRequest): Response {
        val session = handler.verify(loginVerifyRequest.email, loginVerifyRequest.nonce, loginVerifyRequest.signature)
            ?: return httpError(httpFailure("authentication_failed", UNAUTHORIZED))
        val family = SessionFamily(id = UUID.fromString(session.familyId))
        // Pick the refresh transport (ADR 0004 addendum / ADR 0005 §22). Web sends `sessionScope`; native omits
        // it. Compare by wire value so the exact generated enum constant doesn't matter. `remember` is the legacy
        // web flag, honoured (→ persistent) only when `sessionScope` is absent. Operator-blind in every case.
        val noBodyToken = SessionResponse(accessToken = session.accessToken, family = family, refreshToken = null)
        // The web rides a refresh cookie; `cookie.issue` sets the chosen lifetime AND clears the other (exactly
        // one refresh cookie per device). `remember` is the legacy fallback (→ persistent). Native omits both
        // and keeps the body token.
        return when (loginVerifyRequest.sessionScope?.value) {
            "PERSISTENT" -> Response.ok(noBodyToken).cookie(*cookie.issue(session.refreshToken, persistent = true)).build()
            "SESSION" -> Response.ok(noBodyToken).cookie(*cookie.issue(session.refreshToken, persistent = false)).build()
            else ->
                if (loginVerifyRequest.remember == true) {
                    Response.ok(noBodyToken).cookie(*cookie.issue(session.refreshToken, persistent = true)).build()
                } else {
                    Response.ok(
                        SessionResponse(accessToken = session.accessToken, family = family, refreshToken = session.refreshToken),
                    ).build()
                }
        }
    }
}
