package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.generated.api.AccountRotationApi
import me.raddatz.veyq.core.generated.model.OpenRotationStageRequest
import me.raddatz.veyq.core.generated.model.RotateAccountKeysRequest
import me.raddatz.veyq.core.generated.model.RotateAccountKeysResult
import me.raddatz.veyq.core.generated.model.RotatedWrap
import me.raddatz.veyq.core.generated.model.RotationBlockedReason
import me.raddatz.veyq.core.generated.model.RotationPlanScope
import me.raddatz.veyq.core.generated.model.RotationScopeState
import me.raddatz.veyq.core.generated.model.RotationStage
import me.raddatz.veyq.core.generated.model.RotationPlanInvite
import me.raddatz.veyq.core.generated.model.RotationPlanJoinRecord
import me.raddatz.veyq.core.generated.model.RotationStageGrants
import me.raddatz.veyq.core.generated.model.RotationStageInvites
import me.raddatz.veyq.core.generated.model.RotationStageJoinEndorsements
import me.raddatz.veyq.core.account.identitypins.PinWrite
import me.raddatz.veyq.core.account.identitypins.toPinWrite
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for securing the account (uc.account.rotate-keys, ADR 0060, ADR 0111 decisions 6-10). Implements the
 * generated [AccountRotationApi]; thin (ADR 0001) — delegates to [RotationHandler]. Central auth (`@Authenticated`,
 * cr-0012): a missing or invalid token is a generic 401 before this runs.
 *
 * It acts only on the **caller's own** account, resolved from the `SecurityIdentity` principal — no account id in any
 * body, so there is none to name and none to enumerate. Operator-blind: public keys, signatures and ciphertext in, scope
 * ids, epochs and the caller's own grant rows out.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, and it acts only on the caller's own account. A publish is a once-in-an-incident operation whose " +
            "cost is bounded by what it rewrites — the caller's own wraps and one grant per scope they hold — not by " +
            "request volume, and it needs the Secret Key. The stage holds at most one row per scope the caller holds a " +
            "grant on, per open invite they signed and per join record they rooted (upserts, 256 a chunk) and is " +
            "replaced by the next open and purged at its expiry; the plans and the states read only the caller's own rows.",
)
class AccountRotationResource(
    private val handler: RotationHandler,
    private val identity: SecurityIdentity,
) : AccountRotationApi {

    override fun rotateAccountKeys(rotateAccountKeysRequest: RotateAccountKeysRequest): Response {
        val r = rotateAccountKeysRequest
        // The pins ride as the whole re-keyed set (ADR 0104): a write that keeps any entry would leave it under the
        // retired Master Key's id and seal, which every device then refuses.
        val pins = r.identityPins.toPinWrite()
        if (!pins.replaceAll || pins.deletesBesideReplaceAll()) return httpError(Failures.invalidRequest)
        if (pins.contradictory()) return httpError(PinWrite.contradiction)
        val request = RotationRequest(
            identityPublicKey = r.identityPublicKey,
            wrappedIdentityPrivateKey = r.wrappedIdentityPrivateKey,
            passwordWrap = r.passwordWrap.toRewritten(),
            recoveryWrap = r.recoveryWrap.toRewritten(),
            secretKeyProofSignature = r.secretKeyProofSignature,
            pins = pins,
            register = r.deviceRegister.let { ResealedDocument(it.blob, it.revision.validated()) },
            stepIdentitySignature = r.keyChainStep.identitySignature,
            stepProofSignature = r.keyChainStep.proofSignature,
            stageId = r.stageId.toString(),
            stagedGrantsDigest = r.stagedGrantsDigest,
            unreadableScopeIds = r.unreadableScopeIds.map { it.toString() }.toSet(),
            authPublicKey = r.authPublicKey,
            attestationSignature = r.attestationSignature,
            secretKeyProofPublicKey = r.secretKeyProofPublicKey,
            secretKeyProofPossession = r.secretKeyProofPossession,
        )
        // The family to spare is resolved by the SERVER from the presented token, never named by the request —
        // SessionService.revokeOtherFamilies owns that rule, as it does for "sign out everywhere else".
        val token = identity.getCredential(TokenCredential::class.java)?.token
        return when (val outcome = handler.rotate(identity.principal.name, token, request)) {
            is RotationOutcome.Applied -> Response.ok(RotateAccountKeysResult(regrantedScopeIds = outcome.regrantedScopeIds)).build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun getAccountRotationPlan(): Response =
        Response.ok(
            handler.plan(identity.principal.name).map {
                RotationPlanScope(
                    scopeId = UUID.fromString(it.scopeId), kind = RotationPlanScope.Kind.valueOf(it.kind),
                    tenantId = UUID.fromString(it.tenantId), currentEpoch = it.currentEpoch, wrappedGrant = it.wrappedGrant,
                    grantedEpoch = it.grantedEpoch, blocked = it.blocked?.toWire(),
                    granterUserId = it.granterUserId?.let(UUID::fromString), grantSignature = it.grantSignature,
                    granterIdentityPublicKey = it.granterIdentityPublicKey,
                )
            },
        ).build()

    override fun openRotationStage(openRotationStageRequest: OpenRotationStageRequest): Response {
        val stage = handler.openStage(identity.principal.name, openRotationStageRequest.identityPublicKey)
        return Response.status(Response.Status.CREATED)
            .entity(RotationStage(stageId = UUID.fromString(stage.id), expiresAt = stage.expiresAt.atOffset(ZoneOffset.UTC)))
            .build()
    }

    override fun putRotationStageGrants(stageId: UUID, rotationStageGrants: RotationStageGrants): Response {
        val grants = rotationStageGrants.grants.map {
            StagedSelfGrantInput(it.scopeId.toString(), it.epoch.validated(), it.wrappedGrant, it.grantSignature, it.recipientIdentityDigest)
        }
        return when (val outcome = handler.stageGrants(identity.principal.name, stageId.toString(), grants)) {
            StageGrantsOutcome.Staged -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun getRotationPlanInvites(): Response =
        Response.ok(
            handler.planInvites(identity.principal.name).map {
                RotationPlanInvite(
                    tenantId = UUID.fromString(it.tenantId), inviteId = UUID.fromString(it.inviteId), invitePublicKey = it.invitePublicKey,
                    expiresAt = it.expiresAt.atOffset(ZoneOffset.UTC), inviteKeySignature = it.inviteKeySignature,
                    anchorWrappedGrant = it.anchorWrappedGrant, anchorEpoch = it.anchorEpoch, anchorGrantSignature = it.anchorGrantSignature,
                )
            },
        ).build()

    override fun getRotationPlanJoinRecords(): Response =
        Response.ok(
            handler.planJoinRecords(identity.principal.name).map {
                RotationPlanJoinRecord(
                    tenantId = UUID.fromString(it.tenantId), userId = UUID.fromString(it.userId),
                    sealedJoinRecord = it.sealedJoinRecord, joinEndorsement = it.joinEndorsement,
                )
            },
        ).build()

    override fun putRotationStageInvites(stageId: UUID, rotationStageInvites: RotationStageInvites): Response {
        // A declared invite carries neither signature (ADR 0111 decision 20): one
        // without the other is neither a re-signature nor a declaration.
        if (rotationStageInvites.invites.any { (it.inviteKeySignature == null) != (it.anchorGrantSignature == null) }) {
            return httpError(Failures.invalidRequest)
        }
        val entries = rotationStageInvites.invites.map {
            StagedInviteInput(it.inviteId.toString(), it.anchorEpoch.validated(), it.inviteKeySignature, it.anchorGrantSignature)
        }
        return when (val outcome = handler.stageInvites(identity.principal.name, stageId.toString(), entries)) {
            StageInvitesOutcome.Staged -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun putRotationStageJoinEndorsements(stageId: UUID, rotationStageJoinEndorsements: RotationStageJoinEndorsements): Response {
        val entries = rotationStageJoinEndorsements.endorsements.map {
            StagedEndorsementInput(it.tenantId.toString(), it.userId.toString(), it.endorsement)
        }
        return when (val outcome = handler.stageEndorsements(identity.principal.name, stageId.toString(), entries)) {
            StageEndorsementsOutcome.Staged -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun listRotationScopeStates(): Response =
        Response.ok(
            handler.scopeStates(identity.principal.name).map {
                RotationScopeState(UUID.fromString(it.scopeId), it.state.toWire(), it.blocked?.toWire())
            },
        ).build()

    private fun RegrantState.toWire(): RotationScopeState.State = when (this) {
        RegrantState.WAITING -> RotationScopeState.State.waiting
        RegrantState.REKEYING -> RotationScopeState.State.rekeying
        RegrantState.SECURED -> RotationScopeState.State.secured
        RegrantState.BLOCKED -> RotationScopeState.State.blocked
    }

    private fun RotationBlock.toWire(): RotationBlockedReason = when (this) {
        RotationBlock.TENANT_SUSPENDED -> RotationBlockedReason.tenant_suspended
        RotationBlock.SCOPE_DELETING -> RotationBlockedReason.scope_deleting
    }

    private fun RotatedWrap.toRewritten() = RewrittenWrap(wrappedKey, kdfParams)
}
