package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.generated.api.AccountEmailChangeApi
import me.raddatz.veyq.core.generated.model.EmailChangeConfirmRequest
import me.raddatz.veyq.core.generated.model.EmailChangeRequest
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.tenant.RequestTenant
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for changing the sign-in address (uc.account.change-email). Implements the generated
 * [AccountEmailChangeApi]; thin (ADR 0001). Acts only on the caller's own account, resolved from the principal — no
 * id in the body. The `USER` bucket bounds runaway automation; the mail is bounded far tighter by the per-account
 * limiter the handler shares with the address confirmation.
 */
@Authenticated
@RateLimit(key = RateLimitKey.USER)
class EmailChangeResource(
    private val handler: EmailChangeHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
) : AccountEmailChangeApi {

    override fun requestEmailChange(emailChangeRequest: EmailChangeRequest): Response =
        when (
            val outcome = handler.request(
                userId = identity.principal.name,
                presentedAccessToken = identity.getCredential(TokenCredential::class.java)?.token,
                tenant = tenant.resolve(),
                newEmail = emailChangeRequest.newEmail,
                authPublicKey = emailChangeRequest.authPublicKey,
                attestationSignature = emailChangeRequest.attestationSignature,
                secretKeyProofSignature = emailChangeRequest.secretKeyProofSignature,
            )
        ) {
            EmailChangeRequestOutcome.Mailed -> Response.accepted().build()
            is EmailChangeRequestOutcome.RateLimited ->
                Response.fromResponse(httpError(Failures.rateLimited)).header("Retry-After", outcome.retryAfterSeconds).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun confirmEmailChange(emailChangeConfirmRequest: EmailChangeConfirmRequest): Response =
        when (
            val outcome = handler.confirm(
                userId = identity.principal.name,
                // The family to spare is resolved by the server from the presented token, never named by the request.
                presentedAccessToken = identity.getCredential(TokenCredential::class.java)?.token,
                tenant = tenant.resolve(),
                token = emailChangeConfirmRequest.token,
            )
        ) {
            is EmailChangeConfirmOutcome.Changed -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
}
