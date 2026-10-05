package me.raddatz.veyq.core.account.pairing

import me.raddatz.veyq.core.generated.api.PairingApi
import me.raddatz.veyq.core.generated.model.PairingAnswerRequest
import me.raddatz.veyq.core.generated.model.PairingChannel
import me.raddatz.veyq.core.generated.model.PairingCollectRequest
import me.raddatz.veyq.core.generated.model.PairingDelegateRequest
import me.raddatz.veyq.core.generated.model.PairingDelegateResult
import me.raddatz.veyq.core.generated.model.PairingOpenRequest
import me.raddatz.veyq.core.generated.model.PairingSealRequest
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for device linking. Implements the generated [PairingApi]; thin (ADR 0001) — delegates to
 * [PairingHandler].
 *
 * **Two auth postures live here, and every method states its own** rather than leaning on the class — the same
 * arrangement, and the same reason, as `SessionResource`: the class default is the lenient `@PermitAll` that
 * three of the five endpoints need, so an endpoint added here without its own `@Authenticated` inherits the
 * lenient one and ships open.
 *
 * - `open`, `answer` and `collect` are public **by necessity**: in one direction or the other the caller is the
 *   device being onboarded, which has no session and must not have to name an account before it has one
 *   (ADR 0059 §3). They are keyed on IP, because there is no principal to key on — and unlike
 *   `AccountWrapsResource`'s exemption, whose reasoning is *"bounded by the caller's own session"*, these have
 *   no caller at all, so that reasoning does not transfer.
 * - `delegate` and `seal` are `@Authenticated` and keyed on USER: they act on the caller's own account.
 *
 * **Every dead channel answers `404 pairing_channel_invalid`** — unknown, expired, spent and wrong-state alike,
 * mirroring `RecoveryResource.releaseRecoveryMaterial`'s single `recovery_token_invalid`. That is
 * uc.account.onboard-device E1, and it is why nothing here reads a row back to explain itself.
 */
@PermitAll
class PairingResource(
    private val handler: PairingHandler,
    private val identity: SecurityIdentity,
) : PairingApi {

    @RateLimit(key = RateLimitKey.IP)
    override fun openPairingChannel(pairingOpenRequest: PairingOpenRequest): Response {
        // 204 whatever happened, including for a handle that is already live: refusing a duplicate would confirm
        // that a channel exists, which is the oracle E1 closes.
        handler.open(pairingOpenRequest.handle)
        return Response.noContent().build()
    }

    @RateLimit(key = RateLimitKey.IP)
    override fun answerPairingChannel(pairingAnswerRequest: PairingAnswerRequest): Response =
        if (handler.answer(pairingAnswerRequest.handle, pairingAnswerRequest.answer)) {
            Response.noContent().build()
        } else {
            channelInvalid()
        }

    @Authenticated
    @RateLimit(key = RateLimitKey.USER)
    override fun delegatePairingSession(pairingDelegateRequest: PairingDelegateRequest): Response =
        when (
            val outcome = handler.delegate(
                pairingDelegateRequest.handle,
                identity.principal.name,
                identity.getCredential(TokenCredential::class.java)?.token,
            )
        ) {
            is DelegateOutcome.Delegated -> Response.ok(PairingDelegateResult(refreshToken = outcome.refreshToken)).build()
            DelegateOutcome.ChannelInvalid -> channelInvalid()
            is HttpFailure -> httpError(outcome)
        }

    @Authenticated
    @RateLimit(key = RateLimitKey.USER)
    override fun sealPairingChannel(pairingSealRequest: PairingSealRequest): Response =
        if (handler.seal(pairingSealRequest.handle, identity.principal.name, pairingSealRequest.envelope)) {
            Response.noContent().build()
        } else {
            channelInvalid()
        }

    @RateLimit(key = RateLimitKey.IP)
    override fun collectPairingChannel(pairingCollectRequest: PairingCollectRequest): Response {
        val channel = handler.collect(pairingCollectRequest.handle) ?: return channelInvalid()
        return Response.ok(PairingChannel(answer = channel.answer, envelope = channel.envelope)).build()
    }

    /** The one answer every dead channel gets, whichever way it died (E1). */
    private fun channelInvalid(): Response = httpError(httpFailure("pairing_channel_invalid", NOT_FOUND))
}
