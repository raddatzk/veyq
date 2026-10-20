package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.generated.api.ScopesApi
import me.raddatz.veyq.core.tasks.CompromiseRotationState
import me.raddatz.veyq.core.generated.model.AddGroupMemberRequest
import me.raddatz.veyq.core.generated.model.CompromiseRotation
import me.raddatz.veyq.core.generated.model.CreateGroupRequest
import me.raddatz.veyq.core.generated.model.CreatePersonalScopeRequest
import me.raddatz.veyq.core.generated.model.GroupInvitation
import me.raddatz.veyq.core.generated.model.MyTenant
import me.raddatz.veyq.core.generated.model.ResolveGroupMemberRequest
import me.raddatz.veyq.core.generated.model.ResolvedGroupCandidate
import me.raddatz.veyq.core.generated.model.RenameGroupRequest
import me.raddatz.veyq.core.generated.model.RotateScopeRequest
import me.raddatz.veyq.core.generated.model.ScopeCreated
import me.raddatz.veyq.core.generated.model.TenantStatus
import me.raddatz.veyq.core.generated.model.ScopeEpochLink
import me.raddatz.veyq.core.generated.model.ScopeKind
import me.raddatz.veyq.core.generated.model.ScopeMember
import me.raddatz.veyq.core.generated.model.ScopeRotated
import me.raddatz.veyq.core.generated.model.ScopeRotationRequeued
import me.raddatz.veyq.core.generated.model.ScopeSharesRevoked
import me.raddatz.veyq.core.generated.model.ScopeSummary
import me.raddatz.veyq.core.generated.model.SetMemberRoleRequest
import me.raddatz.veyq.core.generated.model.SetScopeIconRequest
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.membership.Admission
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.tenant.RequestTenant
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for a member's scopes (en.spaces.personal-scope, cr-0026). Implements the generated
 * [ScopesApi]; thin (ADR 0001) — delegates to [ScopesHandler]. Authentication is central (`@Authenticated`):
 * a missing/invalid token is a generic 401 before this runs. The active tenant is resolved from the request
 * Host by [RequestTenant]. Operator-blind: both endpoints handle only the
 * **caller's own** scopes (userId from the `SecurityIdentity` principal) and the server stores/returns only
 * ciphertext grants, never a scope key.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; every method is membership- or owner-gated on a scope the caller already belongs " +
        "to. Rotation is the costliest; concurrent commits are serialized by the epoch each one states — only the " +
        "scope's next epoch commits, under a row lock, and the rest are `409 stale_epoch` with nothing stored. " +
        "Re-queueing a compromise rotation is bounded by its own state gate, not by request count: it writes only " +
        "from `failed`, so there is at most one live covering job, and a job needs every attempt failed before it " +
        "can be re-queued.",
)
class ScopesResource(
    private val handler: ScopesHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
    config: VeyqConfig,
) : ScopesApi {
    // The grace window (cr-0090) — used to derive `purgeAfter = deletedAt + retention` for a pending-deletion group.
    private val deleteRetention: Duration = config.scope().delete().retention()

    override fun createPersonalScope(createPersonalScopeRequest: CreatePersonalScopeRequest): Response =
        when (val outcome = handler.createPersonal(
            identity.principal.name, activeTenantId(), createPersonalScopeRequest.scopeId.toString(),
            SignedGrant(createPersonalScopeRequest.wrappedGrant, createPersonalScopeRequest.grantSignature),
        )) {
            is CreatePersonalOutcome.Created ->
                Response.status(Response.Status.CREATED)
                    .entity(ScopeCreated(scopeId = UUID.fromString(outcome.scopeId), kind = ScopeKind.PERSONAL)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun createGroup(createGroupRequest: CreateGroupRequest): Response =
        when (val outcome = handler.createGroup(
            identity.principal.name, activeTenantId(), createGroupRequest.scopeId.toString(), createGroupRequest.encryptedName,
            SignedGrant(createGroupRequest.wrappedGrant, createGroupRequest.grantSignature),
            Admission(createGroupRequest.admissionId.toString(), createGroupRequest.admissionEpoch.validated(), createGroupRequest.admissionTag),
        )) {
            is CreateGroupOutcome.Created ->
                Response.status(Response.Status.CREATED)
                    .entity(ScopeCreated(scopeId = UUID.fromString(outcome.scopeId), kind = ScopeKind.GROUP)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun listMyScopes(): Response =
        when (val outcome = handler.listMine(identity.principal.name, activeTenantId())) {
            is ListScopesOutcome.Scopes ->
                Response.ok(outcome.rows.map {
                    ScopeSummary(
                        id = UUID.fromString(it.id), kind = ScopeKind.decode(it.kind),
                        tenantId = UUID.fromString(it.tenantId), role = ScopeSummary.Role.valueOf(it.role),
                        currentEpoch = it.currentEpoch, grantedEpoch = it.grantedEpoch,
                        wrappedGrant = it.wrappedGrant, encryptedName = it.encryptedName, nameEpoch = it.nameEpoch,
                        sealedIcon = it.sealedIcon, iconEpoch = it.iconEpoch,
                        deletedAt = it.deletedAt?.let { d -> OffsetDateTime.ofInstant(d, ZoneOffset.UTC) },
                        purgeAfter = it.deletedAt?.let { d -> OffsetDateTime.ofInstant(d.plus(deleteRetention), ZoneOffset.UTC) },
                        granterUserId = it.granter?.let { g -> UUID.fromString(g.userId) },
                        grantSignature = it.granter?.signature,
                        granterIdentityPublicKey = it.granter?.identityPublicKey,
                        compromiseRotation = it.compromiseRotation?.toWire(),
                    )
                }).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun addGroupMember(scopeId: UUID, addGroupMemberRequest: AddGroupMemberRequest): Response =
        when (val outcome = handler.addGroupMember(
            identity.principal.name, scopeId.toString(), addGroupMemberRequest.username, addGroupMemberRequest.sealedNameKey,
            Admission(addGroupMemberRequest.admissionId.toString(), addGroupMemberRequest.admissionEpoch.validated(), addGroupMemberRequest.admissionTag),
        )) {
            is AddGroupMemberOutcome.Added -> Response.status(Response.Status.CREATED).entity(scopeMember(outcome.row)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun resolveGroupMember(scopeId: UUID, resolveGroupMemberRequest: ResolveGroupMemberRequest): Response =
        when (val outcome = handler.resolveGroupMember(identity.principal.name, scopeId.toString(), resolveGroupMemberRequest.username)) {
            is ResolveCandidateOutcome.Ok -> Response.ok(
                ResolvedGroupCandidate(
                    userId = UUID.fromString(outcome.row.userId), username = outcome.row.username,
                    identityPublicKey = outcome.row.identityPublicKey, requiresConsent = outcome.row.requiresConsent,
                ),
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun acceptGroupInvitation(scopeId: UUID): Response =
        when (val o = handler.acceptInvitation(identity.principal.name, scopeId.toString())) {
            AcceptInvitationOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun declineGroupInvitation(scopeId: UUID): Response {
        handler.declineInvitation(identity.principal.name, scopeId.toString()) // idempotent silent drop
        return Response.noContent().build()
    }

    override fun listMyGroupInvitations(): Response =
        when (val outcome = handler.listInvitations(identity.principal.name, activeTenantId())) {
            is ListInvitationsOutcome.Invitations -> Response.ok(
                outcome.rows.map {
                    GroupInvitation(
                        scopeId = UUID.fromString(it.scopeId), addedBy = it.addedByUsername,
                        sealedNameKey = it.sealedNameKey, encryptedName = it.encryptedName, nameEpoch = it.nameEpoch,
                    )
                },
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun setMemberRole(scopeId: UUID, userId: UUID, setMemberRoleRequest: SetMemberRoleRequest): Response =
        when (val outcome = handler.setMemberRole(identity.principal.name, scopeId.toString(), userId.toString(), setMemberRoleRequest.role.value)) {
            is SetRoleOutcome.Ok -> Response.ok(scopeMember(outcome.row)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun removeMember(scopeId: UUID, userId: UUID): Response =
        when (val o = handler.removeMember(identity.principal.name, scopeId.toString(), userId.toString())) {
            RemoveMemberOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun rotateScope(scopeId: UUID, rotateScopeRequest: RotateScopeRequest): Response =
        when (val outcome = handler.rotateScope(
            identity.principal.name, scopeId.toString(), rotateScopeRequest.epoch, rotateScopeRequest.wrappedPrev,
            rotateScopeRequest.grants.map { RotationGrant(it.userId.toString(), SignedGrant(it.wrappedGrant, it.grantSignature)) },
            rotateScopeRequest.admissions.map { it.admissionId.toString() to it.admissionTag },
            rotateScopeRequest.droppedAdmissions.orEmpty().map { it.toString() }.toSet(),
            rotateScopeRequest.removesUserId?.toString(),
            rotateScopeRequest.revokeLinks ?: false,
        )) {
            is RotateOutcome.Ok -> Response.status(Response.Status.CREATED).entity(ScopeRotated(epoch = outcome.epoch)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun listScopeEpochs(scopeId: UUID): Response =
        when (val outcome = handler.listEpochs(identity.principal.name, scopeId.toString())) {
            is EpochsOutcome.Links -> Response.ok(outcome.links.map { ScopeEpochLink(epoch = it.first, wrappedPrev = it.second) }).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun deleteGroup(scopeId: UUID): Response =
        when (val o = handler.deleteGroup(identity.principal.name, scopeId.toString())) {
            DeleteGroupOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun renameGroup(scopeId: UUID, renameGroupRequest: RenameGroupRequest): Response =
        when (val o = handler.renameGroup(
            identity.principal.name, scopeId.toString(), renameGroupRequest.encryptedName, renameGroupRequest.nameEpoch.validated(),
        )) {
            RenameGroupOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun setScopeIcon(scopeId: UUID, setScopeIconRequest: SetScopeIconRequest): Response =
        when (val o = handler.setScopeIcon(
            identity.principal.name, scopeId.toString(), setScopeIconRequest.sealedIcon, setScopeIconRequest.iconEpoch.validated(),
        )) {
            SetScopeIconOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun clearScopeIcon(scopeId: UUID): Response =
        when (val o = handler.clearScopeIcon(identity.principal.name, scopeId.toString())) {
            ClearScopeIconOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun restoreGroup(scopeId: UUID): Response =
        when (val o = handler.restoreGroup(identity.principal.name, scopeId.toString())) {
            RestoreGroupOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun listScopeMembers(scopeId: UUID): Response =
        when (val outcome = handler.listMembers(identity.principal.name, scopeId.toString())) {
            is ListMembersOutcome.Members -> Response.ok(outcome.rows.map { scopeMember(it) }).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun listMyTenants(): Response =
        Response.ok(
            handler.myTenants(identity.principal.name).map {
                MyTenant(
                    tenantId = UUID.fromString(it.tenantId), name = it.name, domain = it.domain, role = MyTenant.Role.valueOf(it.role),
                    status = TenantStatus.valueOf(it.status),
                    wrappedGrant = it.wrappedGrant, sealedIcon = it.sealedIcon, iconEpoch = it.iconEpoch, grantedEpoch = it.grantedEpoch,
                    granterUserId = it.granter?.let { g -> UUID.fromString(g.userId) },
                    grantSignature = it.granter?.signature,
                    granterIdentityPublicKey = it.granter?.identityPublicKey,
                    compromiseRotation = it.compromiseRotation?.toWire(),
                )
            },
        ).build()

    /** Map a directory row to the wire DTO — carries `pendingConsent` (cr-0094); the handler already gated its visibility. */
    private fun scopeMember(row: ScopeMemberRow) = ScopeMember(
        userId = UUID.fromString(row.userId), username = row.username, sealedName = row.sealedName, avatarRevision = row.avatarRevision,
        email = row.email, role = ScopeMember.Role.valueOf(row.role), identityPublicKey = row.identityPublicKey,
        hasGrant = row.hasGrant, pendingConsent = row.pendingConsent,
        admissionId = row.admission?.let { UUID.fromString(it.id) },
        admissionEpoch = row.admission?.epoch,
        admissionTag = row.admission?.tag,
    )

    /** The active tenant's id — resolved by [RequestTenant] through the one canonical resolver, which
     *  `TenantSuspensionFilter` also uses: a gate that normalized the Host differently from the handlers it guards
     *  would guard a different tenant than it thinks (ADR 0066). */
    private fun activeTenantId(): String? = tenant.resolve()?.id
    // ADR 0075 point 5: a scope owner turns off every link of the scope; each comes back only by re-issue.
    override fun requeueScopeRotation(scopeId: UUID): Response =
        when (val o = handler.requeueScopeRotation(identity.principal.name, scopeId.toString())) {
            is RequeueScopeRotationOutcome.Requeued ->
                Response.ok(ScopeRotationRequeued(compromiseRotation = o.compromiseRotation?.toWire())).build()
            is HttpFailure -> httpError(o)
        }

    /** The owner-only row value on the wire: only the two states that need an owner ever reach it. */
    private fun CompromiseRotationState.toWire(): CompromiseRotation = when (this) {
        CompromiseRotationState.STALLED -> CompromiseRotation.stalled
        CompromiseRotationState.FAILED -> CompromiseRotation.failed
        CompromiseRotationState.WAITING, CompromiseRotationState.SECURED -> error("$this needs no owner and is never served")
    }

    override fun revokeScopeShares(scopeId: UUID): Response =
        when (val o = handler.revokeScopeShares(identity.principal.name, scopeId.toString())) {
            is RevokeScopeSharesOutcome.Ok -> Response.ok(ScopeSharesRevoked(revoked = o.revoked)).build()
            is HttpFailure -> httpError(o)
        }
}
