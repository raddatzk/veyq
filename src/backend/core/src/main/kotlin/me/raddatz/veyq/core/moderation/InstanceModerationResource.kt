package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.statement.toStepInput
import me.raddatz.veyq.core.generated.api.InstanceModerationApi
import me.raddatz.veyq.core.generated.model.AppointModeratorRequest
import me.raddatz.veyq.core.generated.model.BlockAccountSignInRequest
import me.raddatz.veyq.core.generated.model.InstanceModeration
import me.raddatz.veyq.core.generated.model.InstanceModerationOwner
import me.raddatz.veyq.core.generated.model.InstanceModerator
import me.raddatz.veyq.core.generated.model.MintModerationEpochRequest
import me.raddatz.veyq.core.generated.model.SignInBlockResult
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.generated.model.AuthorityOrderRequest
import me.raddatz.veyq.core.moderation.cases.BlockOutcome
import me.raddatz.veyq.core.moderation.cases.SignInBlocks
import me.raddatz.veyq.core.moderation.order.AuthorityOrderHandler
import me.raddatz.veyq.core.moderation.order.ordered
import me.raddatz.veyq.core.moderation.order.toOrderIn
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
    reason = "Server-Owner-only roster, key and moderation-step writes on the admin channel: one row per appointment, " +
        "one epoch per mint, one per sign-in block or settled proposal " +
        "(bounded by the roster), and an authority's order, whose few mails (its statements, the new-case notice) go " +
        "to accounts and the instance contact, never to an address in the request; no outbound request; the role gate " +
        "already bounds the caller set to one account.",
)
class InstanceModerationResource(
    private val moderation: ModerationHandler,
    private val steps: SignInBlocks,
    private val orders: AuthorityOrderHandler,
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

    override fun getModerationSteps(): Response = Response.ok(steps.steps()).build()

    override fun declineModerationProposal(proposalId: UUID): Response = stepped(steps.decline(caller(), proposalId.toString()))

    override fun blockAccountSignIn(blockAccountSignInRequest: BlockAccountSignInRequest): Response = with(blockAccountSignInRequest) {
        when (val o = steps.block(caller(), address, caseId?.toString(), overContent = overContent ?: false, statement = statement?.toStepInput())) {
            // The steps as they now stand, and over content whom the statement reached — the block stands either way.
            is BlockOutcome.Blocked -> Response.ok(SignInBlockResult(steps.steps(), o.statement?.delivery())).build()
            is BlockOutcome.Refused -> httpError(o)
        }
    }

    override fun unblockAccountSignIn(blockId: UUID): Response = stepped(steps.unblock(caller(), blockId.toString()))

    /** The Server Owner's door to an authority's order (ADR 0093 § 12): the moderator's handler, without a key. */
    override fun enterInstanceModerationOrder(authorityOrderRequest: AuthorityOrderRequest): Response =
        ordered(orders.enter(caller(), authorityOrderRequest.toOrderIn()))

    /** A Server Owner's step: the steps as they now stand, or its refusal. */
    private fun stepped(refusal: HttpFailure?): Response = refusal?.let { httpError(it) } ?: Response.ok(steps.steps()).build()

    private fun caller(): String = identity.principal.name

    private fun answer(outcome: ModerationOutcome<Unit>): Response = when (outcome) {
        is ModerationOutcome.Ok -> ok()
        is HttpFailure -> httpError(outcome)
    }

    private fun ok(): Response = Response.ok(moderation.roster(caller()).toWire()).build()

    private fun RosterView.toWire() = InstanceModeration(
        enabled = currentEpoch != null,
        currentEpoch = currentEpoch,
        // What the Server Owner's clients seal an authority's reference to (ADR 0093 § 12): the epoch's public half only.
        currentKemPublicKey = moderation.currentKey()?.takeIf { it.epoch == currentEpoch }?.kemPublicKey,
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
