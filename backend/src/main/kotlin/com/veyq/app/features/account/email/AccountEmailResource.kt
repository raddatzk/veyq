package com.veyq.app.features.account.email

import com.veyq.app.generated.api.AccountEmailApi
import com.veyq.app.generated.model.AccountEmail
import com.veyq.app.generated.model.EmailConfirmationRequest
import com.veyq.app.shared.http.FailureStatus
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.ratelimit.RateLimit
import com.veyq.app.shared.ratelimit.RateLimitKey
import com.veyq.app.shared.tenant.RequestTenant
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the caller's own address and its confirmation (uc.account.confirm-email-address, ADR 0074).
 * Implements the generated [AccountEmailApi]; thin (ADR 0001).
 *
 * `@Authenticated` on all three: confirming needs the account's own session (Decision 5), and unlock is not
 * required, because this is operational state, not content. The `USER` bucket bounds runaway automation; the mail
 * itself is bounded far tighter by the handler's per-account limiter.
 */
@Authenticated
@RateLimit(key = RateLimitKey.USER)
class AccountEmailResource(
    private val handler: EmailConfirmationHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
) : AccountEmailApi {

    override fun getAccountEmail(): Response {
        // An authenticated principal always names an account (no path deletes one); a missing row answers as the
        // auth layer's own generic 401.
        val view = handler.get(identity.principal.name)
            ?: return httpError(httpFailure("authentication_failed", FailureStatus.UNAUTHORIZED))
        return Response.ok(AccountEmail(email = view.email, confirmed = view.confirmed)).build()
    }

    override fun requestEmailConfirmation(): Response =
        when (val outcome = handler.request(identity.principal.name, tenant.resolve())) {
            ConfirmationRequestOutcome.Mailed -> Response.accepted().build()
            // 429 + Retry-After, the shape every limiter here emits.
            is ConfirmationRequestOutcome.RateLimited ->
                Response.fromResponse(httpError(Failures.rateLimited)).header("Retry-After", outcome.retryAfterSeconds).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun confirmEmail(emailConfirmationRequest: EmailConfirmationRequest): Response =
        when (val outcome = handler.confirm(identity.principal.name, emailConfirmationRequest.token)) {
            ConfirmOutcome.Confirmed -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
}
