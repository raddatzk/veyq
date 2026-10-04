package com.veyq.core.account.login

import com.veyq.app.generated.api.SessionApi
import com.veyq.app.generated.model.SessionFamily
import com.veyq.core.http.bearerToken
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for session lifecycle. Implements the generated [SessionApi]; thin (ADR 0001) — delegates to
 * [SessionService]. Two auth postures live here, and **every method states its own** rather than leaning on the
 * class:
 *
 * - `logout` is `@PermitAll` (cr-0012) and reads the bearer token **manually**: it must stay a 204 no-op for
 *   any token — a present-but-invalid token must not 401 — so it cannot go through `@Authenticated`. It is the
 *   one endpoint that still parses the header itself.
 * - The session manager (uc.account.sessions) — list, revoke one, revoke every other — is `@Authenticated` per
 *   method and resolves its caller centrally (cr-0012), never from an id in the request. It gates on a
 *   **session, not an unlock** (auth ≠ unlock, ADR 0003), which is what lets a locked device cut off a lost one.
 *
 * **The class default is `@PermitAll` because logout needs it, and the direction is not interchangeable:** a
 * class-level `@Authenticated` with `@PermitAll` on `logout` compiles and deploys, and logout then answers
 * **401** to an invalid token — Quarkus resolves the identity before the method-level permit applies. That is
 * measured, not assumed (`SessionManagerSliceTest.logoutStillAnswers204ForATokenThatIsNotOne` fails on exactly
 * that arrangement), and it is why a new endpoint added here must carry its own `@Authenticated`: the default it
 * would otherwise inherit is the lenient one.
 *
 * Operator-blind by construction: nothing here accepts or returns a device name, user-agent or client label —
 * a family has none to give (ADR 0004), and display names live only in the client's sealed device register.
 */
@PermitAll
class SessionResource(
    private val sessions: SessionService,
    private val cookie: SessionCookie,
    private val identity: SecurityIdentity,
) : SessionApi {

    @Context
    lateinit var headers: HttpHeaders

    @RateLimitExempt(
        reason = "Logout is a lenient, idempotent 204 no-op for ANY token — valid, expired or absent — so it " +
            "is no oracle and reveals nothing to spend requests on. The only work it does is revoking a " +
            "session family the caller already holds, which strictly REDUCES what an attacker can reach.",
    )
    override fun logout(): Response {
        bearerToken(headers)?.let { sessions.revokeByAccessToken(it) }
        // Drop both web refresh cookies too (persistent + session, cr-0055) — a no-op for native callers that
        // never set one.
        return Response.noContent().cookie(*cookie.cleared()).build()
    }

    @Authenticated
    @RateLimitExempt(
        reason = "Authenticated; returns only the caller's own session families, bounded by how many times " +
            "the account has logged in rather than by request volume. It reveals nothing the caller does not " +
            "already hold a token for, and there is no unauthenticated budget to bound.",
    )
    override fun listSessionFamilies(): Response =
        Response.ok(
            sessions.listActiveFamilies(identity.principal.name, presentedToken()).map {
                SessionFamily(
                    id = UUID.fromString(it.id),
                    createdAt = it.createdAt.atOffset(ZoneOffset.UTC),
                    lastUsedAt = it.lastUsedAt.atOffset(ZoneOffset.UTC),
                    current = it.current,
                )
            },
        ).build()

    @Authenticated
    @RateLimitExempt(
        reason = "Authenticated, id-scoped and idempotent: it can only revoke a family of the caller's own " +
            "account, so every call strictly REDUCES what a token holder can reach. Answering 204 for every " +
            "id makes it no enumeration oracle either, so there is nothing a request budget would protect.",
    )
    override fun revokeSessionFamily(id: String): Response {
        // Always 204, whatever the id was: the service scopes the update to the caller's own account, so an
        // unknown id, a foreign one and an already-revoked one are indistinguishable from here. That is the
        // non-enumerability posture, and it is why nothing is read back before answering.
        sessions.revokeFamilyForUser(identity.principal.name, id)
        return Response.noContent().build()
    }

    @Authenticated
    @RateLimitExempt(
        reason = "Authenticated and idempotent; revokes only families of the caller's own account and only " +
            "ever REDUCES reachable sessions. Repeating it is a no-op, so volume buys an attacker nothing.",
    )
    override fun revokeOtherSessionFamilies(): Response {
        // The spared family is resolved by the SERVER from the presented token, never named by the request —
        // see SessionService.revokeOtherFamilies, which also owns the "resolves to nothing → revoke nothing"
        // rule so this adapter cannot get it wrong.
        presentedToken()?.let { sessions.revokeOtherFamilies(identity.principal.name, it) }
        return Response.noContent().build()
    }

    /** The access token this request arrived on, as the identity provider recorded it (cr-0012). */
    private fun presentedToken(): String? = identity.getCredential(TokenCredential::class.java)?.token
}
