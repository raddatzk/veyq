package com.veyq.app.features.account.rotation

import com.veyq.app.generated.api.AccountRotationApi
import com.veyq.app.generated.model.RotateAccountKeysRequest
import com.veyq.app.generated.model.RotateAccountKeysResult
import com.veyq.app.generated.model.RotatedWrap
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.ratelimit.RateLimitExempt
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for key rotation after a compromise (uc.account.rotate-keys, ADR 0060). Implements the generated
 * [AccountRotationApi]; thin (ADR 0001) — delegates to [RotationHandler]. Central auth (`@Authenticated`,
 * cr-0012): a missing or invalid token is a generic 401 before this runs.
 *
 * It acts only on the **caller's own** account, resolved from the `SecurityIdentity` principal — no id in the
 * body, so there is no account to name and none to enumerate. Operator-blind: the request carries a public key
 * and ciphertext, nothing else, and the response carries scope ids the server already routes on.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, and it acts only on the caller's own account. A rotation is a once-in-an-incident " +
        "operation whose cost is bounded by what it rewrites — the caller's own wraps and a job per scope they " +
        "belong to — not by request volume; repeating it only re-rotates the account of whoever is already " +
        "holding a valid session for it.",
)
class AccountRotationResource(
    private val handler: RotationHandler,
    private val identity: SecurityIdentity,
) : AccountRotationApi {

    override fun rotateAccountKeys(rotateAccountKeysRequest: RotateAccountKeysRequest): Response =
        when (
            val outcome = handler.rotate(
                userId = identity.principal.name,
                // The family to spare is resolved by the SERVER from the presented token, never named by the
                // request — SessionService.revokeOtherFamilies owns that rule, as it does for "sign out
                // everywhere else".
                presentedAccessToken = identity.getCredential(TokenCredential::class.java)?.token,
                identityPublicKey = rotateAccountKeysRequest.identityPublicKey,
                wrappedIdentityPrivateKey = rotateAccountKeysRequest.wrappedIdentityPrivateKey,
                passwordWrap = rotateAccountKeysRequest.passwordWrap.toRewritten(),
                recoveryWrap = rotateAccountKeysRequest.recoveryWrap.toRewritten(),
                secretKeyProofSignature = rotateAccountKeysRequest.secretKeyProofSignature,
            )
        ) {
            is RotationOutcome.Applied -> Response.ok(
                RotateAccountKeysResult(
                    soleMemberScopeIds = outcome.soleMemberScopeIds,
                    queuedScopeIds = outcome.queuedScopeIds,
                ),
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    private fun RotatedWrap.toRewritten() = RewrittenWrap(wrappedKey, kdfParams)
}
