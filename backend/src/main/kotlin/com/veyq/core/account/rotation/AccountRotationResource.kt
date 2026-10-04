package com.veyq.core.account.rotation

import com.veyq.core.tasks.CompromiseRotationState
import com.veyq.app.generated.api.AccountRotationApi
import com.veyq.app.generated.model.RotateAccountKeysRequest
import com.veyq.app.generated.model.RotateAccountKeysResult
import com.veyq.app.generated.model.RotatedWrap
import com.veyq.app.generated.model.RotationScopeState
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.ratelimit.RateLimitExempt
import com.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

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
        "holding a valid session for it. Reading the queued rotations' states touches only the caller's own rows, " +
        "and a re-queue is bounded by its own state gate, not by request count: it writes only from `failed`, so " +
        "there is at most one live covering job per scope, and a job needs every attempt failed first.",
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
                pins = rotateAccountKeysRequest.identityPins.let { ResealedDocument(it.blob, it.version.validated()) },
                register = rotateAccountKeysRequest.deviceRegister.let { ResealedDocument(it.blob, it.version.validated()) },
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

    override fun listRotationScopeStates(): Response =
        Response.ok(handler.scopeStates(identity.principal.name).map { RotationScopeState(UUID.fromString(it.scopeId), it.state.toWire()) }).build()

    override fun requeueRotationScope(scopeId: UUID): Response =
        when (val outcome = handler.requeue(identity.principal.name, scopeId.toString())) {
            is RequeueRotationOutcome.Requeued ->
                Response.ok(RotationScopeState(UUID.fromString(outcome.scopeId), outcome.state.toWire())).build()
            is HttpFailure -> httpError(outcome)
        }

    private fun CompromiseRotationState.toWire(): RotationScopeState.State = when (this) {
        CompromiseRotationState.WAITING -> RotationScopeState.State.waiting
        CompromiseRotationState.STALLED -> RotationScopeState.State.stalled
        CompromiseRotationState.SECURED -> RotationScopeState.State.secured
        CompromiseRotationState.FAILED -> RotationScopeState.State.failed
    }

    private fun RotatedWrap.toRewritten() = RewrittenWrap(wrappedKey, kdfParams)
}
