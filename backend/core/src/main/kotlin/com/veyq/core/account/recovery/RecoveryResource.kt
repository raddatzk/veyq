package com.veyq.core.account.recovery

import com.veyq.core.generated.api.RecoveryApi
import com.veyq.core.generated.model.RecoveryCompleteRequest
import com.veyq.core.generated.model.RecoveryMaterial
import com.veyq.core.generated.model.RecoveryRequest
import com.veyq.core.generated.model.RecoveryTokenRequest
import com.veyq.core.http.FailureStatus.FORBIDDEN
import com.veyq.core.http.FailureStatus.NOT_FOUND
import com.veyq.core.http.Failures
import com.veyq.core.http.httpError
import com.veyq.core.http.httpFailure
import com.veyq.core.ratelimit.RateLimit
import com.veyq.core.ratelimit.RateLimitKey
import com.veyq.core.tenant.RequestTenant
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for password recovery. Implements the generated [RecoveryApi]; thin — maps to/from the generated
 * models and delegates to [RecoveryHandler] (ADR 0001).
 *
 * **Public by necessity** (`@PermitAll`): the actor has forgotten their password, so they have no session. That
 * is also why the release is gated on a mailed token instead — handing the RECOVERY wrap to any authenticated
 * caller would let a live session plus the Secret Key open the vault without the password, demoting the password
 * from a content factor (ADR 0054 § Context 2). The IP limiter applies on top of the handler's per-address one.
 */
@PermitAll
@RateLimit(key = RateLimitKey.IP)
class RecoveryResource(private val handler: RecoveryHandler, private val tenant: RequestTenant) : RecoveryApi {

    /**
     * 202 for everything the *address* could explain — registered, unknown, or rate-limited are indistinguishable
     * on the wire, so the endpoint is never an oracle for who has an account here (ADR 0054 § Decision 6).
     *
     * The one exception is not about an address at all: a tenant with no working mail transport cannot send a
     * recovery link and says so with `503 mail_unavailable`, rather than accepting a request it will silently
     * drop (ADR 0055 §4). The handler checks that before the rate limiter precisely so this branch cannot be
     * paired with the 202 into an address-level oracle.
     */
    override fun requestPasswordRecovery(recoveryRequest: RecoveryRequest): Response =
        when (handler.request(recoveryRequest.email, tenant.resolve())) {
            RequestOutcome.NO_MAIL_TRANSPORT -> httpError(Failures.mailUnavailable)
            else -> Response.accepted().build()
        }

    override fun releaseRecoveryMaterial(recoveryTokenRequest: RecoveryTokenRequest): Response {
        val material = handler.release(recoveryTokenRequest.token)
            ?: return httpError(httpFailure("recovery_token_invalid", NOT_FOUND))
        return Response.ok(
            RecoveryMaterial(
                email = material.email,
                suiteId = RecoveryMaterial.SuiteId.entries.first { it.value == material.suiteId },
                wrappedKey = material.wrappedKey,
                kdfParams = material.kdfParams.orEmpty(),
                wrappedIdentityPrivateKey = material.wrappedIdentityPrivateKey,
            ),
        ).build()
    }

    override fun completePasswordRecovery(recoveryCompleteRequest: RecoveryCompleteRequest): Response =
        when (
            handler.complete(
                recoveryCompleteRequest.token,
                recoveryCompleteRequest.secretKeyProofSignature,
                recoveryCompleteRequest.wrappedKey,
                recoveryCompleteRequest.kdfParams,
                recoveryCompleteRequest.authPublicKey,
            )
        ) {
            ResetOutcome.Applied -> Response.noContent().build()
            ResetOutcome.TokenNotLive -> httpError(httpFailure("recovery_token_invalid", NOT_FOUND))
            ResetOutcome.NotProven -> httpError(httpFailure("secret_key_proof_failed", FORBIDDEN))
        }
}
