package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.api.ModerationApi
import me.raddatz.veyq.core.generated.model.ModerationChallenge
import me.raddatz.veyq.core.generated.model.ModerationDeliveries
import me.raddatz.veyq.core.generated.model.ModerationDelivery
import me.raddatz.veyq.core.generated.model.ModerationEpochGrant
import me.raddatz.veyq.core.generated.model.ModerationEpochKey
import me.raddatz.veyq.core.generated.model.ModerationGrants
import me.raddatz.veyq.core.generated.model.ModerationPendingDelivery
import me.raddatz.veyq.core.generated.model.ModeratorAddressConfirmation
import me.raddatz.veyq.core.generated.model.ModeratorAppointment
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The moderator's operations on the public listener (ADR 0099 § 6, uc.moderation.serve-as-moderator). Every one passes
 * [ModerationProofGate] first, which requires the `MODERATOR` role and — for the operations that declare the proof
 * headers — the key proof; the resource sees only callers the gate let through, so the headers are not read here.
 * `@Authenticated` rather than `@RolesAllowed`: the role is the gate's (it answers `not_moderator`, where a role
 * annotation would answer `not_server_owner`).
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated; each operation reads or writes the caller's own appointment, grants or a delivery the key " +
        "proof gates, one row per epoch. The operations with something to guess or to mail carry their own USER bound.",
)
class ModerationResource(
    private val moderation: ModerationHandler,
    private val address: ModeratorAddressHandler,
    private val rotationDue: RotationDueNotice,
    private val identity: SecurityIdentity,
) : ModerationApi {

    @PermitAll
    @RateLimit(key = RateLimitKey.IP) // unauthenticated; the one read every reporter makes
    override fun getModerationKey(): Response {
        val current = moderation.currentKey() ?: return httpError(ModerationFailures.moderationOffKey)
        return Response.ok(ModerationEpochKey(current.epoch, current.kemPublicKey, current.signPublicKey)).build()
    }

    override fun getMyModeratorAppointment(): Response = Response.ok(appointment()).build()

    @RateLimit(key = RateLimitKey.USER) // each accept mails a code
    override fun acceptModeratorAppointment(): Response = when (val outcome = moderation.accept(caller())) {
        is ModerationOutcome.Ok -> {
            address.sendCode(caller())
            Response.ok(appointment()).build()
        }
        is HttpFailure -> httpError(outcome)
    }

    @RateLimit(key = RateLimitKey.USER) // each request mails a code
    override fun sendModeratorAddressCode(): Response {
        address.sendCode(caller())
        return Response.ok(appointment()).build()
    }

    @RateLimit(key = RateLimitKey.USER) // a code to guess, on top of the per-code attempt limit
    override fun confirmModeratorAddress(moderatorAddressConfirmation: ModeratorAddressConfirmation): Response =
        if (address.confirm(caller(), moderatorAddressConfirmation.code)) {
            Response.ok(appointment()).build()
        } else {
            httpError(ModeratorAddressHandler.codeInvalid)
        }

    override fun stepDownAsModerator(xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        when (val outcome = moderation.stepDown(caller())) {
            is ModerationOutcome.Ok -> {
                rotationDue.mailIfDue()
                Response.noContent().build()
            }
            is HttpFailure -> httpError(outcome)
        }

    override fun listMyModerationGrants(): Response {
        val (current, epochs) = moderation.grantsOf(caller())
        return Response.ok(
            ModerationGrants(
                currentEpoch = current,
                epochs = epochs.map { ModerationEpochGrant(it.epoch, it.kemPublicKey, it.signPublicKey, it.sealedGrant) },
            ),
        ).build()
    }

    @RateLimit(key = RateLimitKey.USER) // every key-proved call spends one; bound how many rows a session can mint
    override fun createModerationChallenge(): Response =
        Response.ok(ModerationChallenge(moderation.challenge(caller()))).build()

    override fun listPendingModerationDeliveries(xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        Response.ok(
            ModerationDeliveries(
                moderation.pendingDeliveries(caller()).map {
                    ModerationPendingDelivery(UUID.fromString(it.accountId), it.identityPublicKey, it.epochs)
                },
            ),
        ).build()

    override fun putModerationDelivery(
        accountId: UUID,
        moderationDelivery: ModerationDelivery,
        xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response {
        val grants = moderationDelivery.grants.map {
            GrantIn(accountId.toString(), moderationDelivery.identityPublicKey, it.epoch, it.sealedGrant)
        }
        return when (val outcome = moderation.deliver(caller(), accountId.toString(), moderationDelivery.identityPublicKey, grants)) {
            is ModerationOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
    }

    private fun caller(): String = identity.principal.name

    private fun appointment(): ModeratorAppointment {
        val id = caller()
        val now = Instant.now()
        val row = moderation.appointmentOf(id)
        val email = QuarkusTransaction.joiningExisting().call { VeyqUser.findById(id)?.email } ?: ""
        val state = when {
            row == null -> ModeratorAppointment.State.NONE
            row.acceptedAt == null -> ModeratorAppointment.State.PENDING
            else -> ModeratorAppointment.State.ACCEPTED
        }
        return ModeratorAppointment(
            state = state,
            appointedAt = row?.appointedAt?.atOffset(ZoneOffset.UTC),
            acceptedAt = row?.acceptedAt?.atOffset(ZoneOffset.UTC),
            address = email,
            confirmation = ModeratorAppointment.Confirmation.valueOf(ModeratorAddress.state(row, email, now).name),
            awaitingKey = state == ModeratorAppointment.State.ACCEPTED && moderation.awaitingKey(id),
        )
    }
}
