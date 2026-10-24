package me.raddatz.veyq.core.account.login

import me.raddatz.veyq.core.generated.api.TokenApi
import me.raddatz.veyq.core.generated.model.RefreshRequest
import me.raddatz.veyq.core.generated.model.SessionFamily
import me.raddatz.veyq.core.generated.model.SessionResponse
import me.raddatz.veyq.core.generated.model.TokenAdoptRequest
import me.raddatz.veyq.core.generated.model.TokenPair
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.UNAUTHORIZED
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import java.util.UUID

/** HTTP adapter for refresh-token rotation — and for the one-shot **adoption** that converts a handed-over
 *  grant token into that same transport. Implements the generated [TokenApi]; delegates to
 *  [SessionService] (rotation + reuse detection, ADR 0004). Public (`@PermitAll`, cr-0012): the refresh token
 *  *is* the credential, carried outside the bearer scheme — in the **httpOnly cookie** (web "remember me",
 *  cr-0052) or the request **body** (native). The cookie wins when present; the new token then rides a fresh
 *  cookie and is omitted from the body, so the refresh token never reaches JS on the web path. */
@PermitAll
@RateLimit(key = RateLimitKey.IP)
class TokenResource(private val sessions: SessionService, private val cookie: SessionCookie) : TokenApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun refreshSession(refreshRequest: RefreshRequest): Response {
        val fromCookie = cookie.read(headers)
        // Neither a cookie nor a body token: the request holds no credential, which is a 401 — under its own code, so
        // a log tells every signed-out web boot (it asks because it cannot see its HttpOnly cookie) from a token this
        // server refused, which is what ADR 0004's clone case is read from (inbox #535).
        val token = fromCookie?.token ?: refreshRequest.refreshToken
            ?: return httpError(httpFailure("no_session", UNAUTHORIZED))
        val rotated = sessions.refresh(token)
            ?: return httpError(httpFailure("refresh_rejected", UNAUTHORIZED))
        return if (fromCookie != null) {
            // Re-issue the SAME lifetime the request carried (persistent vs session) — so rotation never
            // silently upgrades/downgrades it (ADR 0004 addendum) — and clear the other lifetime, via the one
            // `issue` API. The token stays out of the body on the web path.
            Response.ok(TokenPair(accessToken = rotated.accessToken, refreshToken = null))
                .cookie(*cookie.issue(rotated.refreshToken, fromCookie.persistent)).build()
        } else {
            Response.ok(TokenPair(accessToken = rotated.accessToken, refreshToken = rotated.refreshToken)).build()
        }
    }

    /**
     * Adopt a handed-over refresh token into this device's own cookie-borne session — the login-equivalent for
     * a device that has no password (ADR 0004 § Transport, ADR 0059 §2).
     *
     * Three things it is **not**, each deliberate:
     * - *Not a new authority.* The caller must already present a valid CURRENT refresh token, exactly what
     *   refresh demands, so this changes transport and nothing else. It is therefore not gated on how the
     *   family was born — there is no origin on a family to gate on, by decision (ADR 0004 § Session family).
     * - *Not a new single-use mechanism.* The presented token is superseded by the ordinary rotation below, so
     *   a second adoption is ADR 0004's clone case and takes the family down. Nothing here enforces that.
     * - *Not a lifetime it inherits.* A device onboarded by a grant holds no cookie to read one from, so
     *   `sessionScope` is required and the person holding the new machine answers it (uc.account.onboard-device
     *   E5) — unlike refresh, which may never raise or lower the lifetime it was handed.
     */
    override fun adoptSession(tokenAdoptRequest: TokenAdoptRequest): Response {
        // Adoption is for a device that holds NO session here, so a request already carrying a refresh cookie is
        // refused rather than honoured — otherwise a browser would silently swap its own session for another
        // one. (A browser cannot reach this today: the cookie's Path is /api/token/refresh, so it is not sent
        // here at all. The rule is the server's own, and it is what keeps that Path from becoming load-bearing.)
        if (cookie.read(headers) != null) return httpError(Failures.invalidRequest)
        val rotated = sessions.refresh(tokenAdoptRequest.refreshToken)
            ?: return httpError(httpFailure("refresh_rejected", UNAUTHORIZED))
        // The successor rides the cookie and is omitted from the body — the whole point of the verb. The family
        // id comes back because the device has no login to have learned it from, and needs it to join its own
        // register entry to this session (ADR 0004 § Session family).
        return Response.ok(
            SessionResponse(
                accessToken = rotated.accessToken,
                family = SessionFamily(id = UUID.fromString(rotated.familyId)),
                refreshToken = null,
            ),
        ).cookie(
            *cookie.issue(
                rotated.refreshToken,
                persistent = tokenAdoptRequest.sessionScope == TokenAdoptRequest.SessionScope.PERSISTENT,
            ),
        ).build()
    }
}
