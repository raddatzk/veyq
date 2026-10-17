package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.api.InstanceModerationApi
import me.raddatz.veyq.core.generated.model.AppointModeratorRequest
import me.raddatz.veyq.core.generated.model.InstanceModeration
import me.raddatz.veyq.core.generated.model.InstanceModerationOwner
import me.raddatz.veyq.core.generated.model.InstanceModerator
import me.raddatz.veyq.core.generated.model.MintModerationEpochRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Server Owner's half of moderation on the admin channel (ADR 0099 § 3, uc.moderation.appoint-moderators): the
 * roster, turning moderation on, appointing, removing and minting. Thin over [ModerationHandler]; every write answers the
 * roster as it then stands, so admin-web needs no second read.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only roster and key writes on the admin channel: one row per appointment, one epoch per mint " +
        "(bounded by the roster), no mail and no outbound request; the role gate already bounds the caller set to one " +
        "account.",
)
class InstanceModerationResource(
    private val moderation: ModerationHandler,
    private val identity: SecurityIdentity,
) : InstanceModerationApi {

    override fun getInstanceModeration(): Response = ok()

    override fun appointModerator(appointModeratorRequest: AppointModeratorRequest): Response =
        answer(moderation.appoint(caller(), appointModeratorRequest.address))

    override fun removeModerator(accountId: UUID): Response = answer(moderation.remove(caller(), accountId.toString()))

    override fun mintModerationEpoch(mintModerationEpochRequest: MintModerationEpochRequest): Response = with(mintModerationEpochRequest) {
        val reason = MintReason.valueOf(reason.name)
        val epochGrants = grants.map { GrantIn(it.accountId.toString(), it.identityPublicKey, 0, it.sealedGrant) }
        answer(moderation.mint(caller(), epoch, kemPublicKey, signPublicKey, reason, epochGrants))
    }

    private fun caller(): String = identity.principal.name

    private fun answer(outcome: ModerationOutcome<Unit>): Response = when (outcome) {
        is ModerationOutcome.Ok -> ok()
        is HttpFailure -> httpError(outcome)
    }

    private fun ok(): Response = Response.ok(moderation.roster(caller()).toWire()).build()

    private fun RosterView.toWire() = InstanceModeration(
        enabled = currentEpoch != null,
        currentEpoch = currentEpoch,
        rotationDueSince = rotationDueSince?.atOffset(ZoneOffset.UTC),
        serverOwner = InstanceModerationOwner(accountId = UUID.fromString(ownerId), identityPublicKey = ownerIdentityPublicKey),
        moderators = moderators.map {
            InstanceModerator(
                accountId = UUID.fromString(it.accountId),
                address = it.address,
                state = if (it.accepted) InstanceModerator.State.ACCEPTED else InstanceModerator.State.PENDING,
                appointedAt = it.appointedAt.atOffset(ZoneOffset.UTC),
                acceptedAt = it.acceptedAt?.atOffset(ZoneOffset.UTC),
                identityPublicKey = it.identityPublicKey,
                holdsCurrentEpoch = it.holdsCurrentEpoch,
                addressConfirmed = it.addressConfirmed,
            )
        },
    )
}
