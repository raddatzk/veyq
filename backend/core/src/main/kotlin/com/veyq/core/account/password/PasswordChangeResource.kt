package com.veyq.core.account.password

import com.veyq.core.generated.api.AccountPasswordApi
import com.veyq.core.generated.model.ChangePasswordRequest
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for changing a password from a live session (uc.account.change-password). Implements the
 * generated [AccountPasswordApi]; thin (ADR 0001) — delegates to [PasswordChangeHandler]. Central auth
 * (`@Authenticated`, cr-0012): a missing or invalid token is a generic 401 before this runs.
 *
 * Mirrors `AccountRotationResource`'s shape: acts only on the caller's own account, resolved from the
 * `SecurityIdentity` principal — no id in the body, so there is no account to name and none to enumerate.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, and it acts only on the caller's own account, exactly as account rotation does. A " +
        "password change's cost is bounded by what it rewrites — the caller's own floor and device wraps — " +
        "not by request volume; repeating it only re-changes the password of whoever already holds a valid " +
        "session for it.",
)
class PasswordChangeResource(
    private val handler: PasswordChangeHandler,
    private val identity: SecurityIdentity,
) : AccountPasswordApi {

    override fun changePassword(changePasswordRequest: ChangePasswordRequest): Response =
        when (
            val outcome = handler.change(
                userId = identity.principal.name,
                // The family to spare is resolved by the SERVER from the presented token, never named by the
                // request — SessionService.revokeOtherFamilies owns that rule, as it does for rotation.
                presentedAccessToken = identity.getCredential(TokenCredential::class.java)?.token,
                newAuthPublicKey = changePasswordRequest.authPublicKey,
                wrappedKey = changePasswordRequest.passwordWrap.wrappedKey,
                kdfParams = changePasswordRequest.passwordWrap.kdfParams,
                attestationSignature = changePasswordRequest.attestationSignature,
                secretKeyProofSignature = changePasswordRequest.secretKeyProofSignature,
            )
        ) {
            is PasswordChangeOutcome.Applied -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
}
