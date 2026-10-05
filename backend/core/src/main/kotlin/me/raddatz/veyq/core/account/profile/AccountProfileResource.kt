package com.veyq.core.account.profile

import com.veyq.core.generated.api.AccountProfileApi
import com.veyq.core.generated.model.MemberProfile
import com.veyq.core.generated.model.ProfileUpdateRequest
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.tenant.RequestTenant
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import com.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the caller's per-tenant identity profile (username + sealed real name, ADR 0023). Implements
 * the generated [AccountProfileApi]; thin (ADR 0001) — delegates to [AccountProfileHandler]. Authentication is
 * central (`@Authenticated`); the tenant is resolved from the request Host by [RequestTenant] (like the scopes endpoints). Only the
 * caller's own profile (userId from the principal) — no id/email in the request.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; reads and updates the caller's own profile row. No fan-out, no external cost.",
)
class AccountProfileResource(
    private val handler: AccountProfileHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
) : AccountProfileApi {

    override fun getMyProfile(): Response =
        when (val outcome = handler.get(identity.principal.name, tenant.resolve()?.id)) {
            is ProfileOutcome.Found -> Response.ok(memberProfile(outcome)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun updateMyProfile(profileUpdateRequest: ProfileUpdateRequest): Response =
        when (val outcome = handler.update(
            identity.principal.name, tenant.resolve()?.id, profileUpdateRequest.username, profileUpdateRequest.sealedName,
            profileUpdateRequest.requireGroupAddConsent,
        )) {
            is ProfileOutcome.Found -> Response.ok(memberProfile(outcome)).build()
            is HttpFailure -> httpError(outcome)
        }

    private fun memberProfile(o: ProfileOutcome.Found) =
        MemberProfile(username = o.username, requireGroupAddConsent = o.requireGroupAddConsent, sealedName = o.sealedName)
}
