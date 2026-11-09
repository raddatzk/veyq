package me.raddatz.veyq.core.account.profile

import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.generated.api.AccountProfileApi
import me.raddatz.veyq.core.generated.model.MemberProfile
import me.raddatz.veyq.core.generated.model.ProfileUpdateRequest
import me.raddatz.veyq.core.generated.model.SetAvatarRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.tenant.RequestTenant
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

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
            profileUpdateRequest.wrappedNameValueKey, profileUpdateRequest.epoch, profileUpdateRequest.requireGroupAddConsent,
            profileUpdateRequest.donationBannerDismissed,
        )) {
            is ProfileOutcome.Found -> Response.ok(memberProfile(outcome)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun setMyAvatar(setAvatarRequest: SetAvatarRequest): Response =
        when (val outcome = handler.setAvatar(
            identity.principal.name, tenant.resolve()?.id,
            SealedValue(setAvatarRequest.sealedAvatar, setAvatarRequest.wrappedAvatarValueKey), setAvatarRequest.epoch.validated(),
        )) {
            AvatarWriteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }

    override fun clearMyAvatar(): Response =
        when (val outcome = handler.clearAvatar(identity.principal.name, tenant.resolve()?.id)) {
            AvatarWriteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }

    private fun memberProfile(o: ProfileOutcome.Found) = MemberProfile(
        username = o.username, avatarRevision = o.avatarRevision, requireGroupAddConsent = o.requireGroupAddConsent,
        donationBannerDismissed = o.donationBannerDismissed, sealedName = o.name?.value, wrappedNameValueKey = o.name?.wrappedValueKey,
    )
}
