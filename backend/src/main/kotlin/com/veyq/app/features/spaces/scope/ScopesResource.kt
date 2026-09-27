package com.veyq.app.features.spaces.scope

import com.veyq.app.generated.api.ScopesApi
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.CreatePersonalScopeRequest
import com.veyq.app.generated.model.GroupInvitation
import com.veyq.app.generated.model.MyTenant
import com.veyq.app.generated.model.ResolveGroupMemberRequest
import com.veyq.app.generated.model.ResolvedGroupCandidate
import com.veyq.app.generated.model.RenameGroupRequest
import com.veyq.app.generated.model.RotateScopeRequest
import com.veyq.app.generated.model.ScopeCreated
import com.veyq.app.generated.model.TenantStatus
import com.veyq.app.generated.model.ScopeEpochLink
import com.veyq.app.generated.model.ScopeKind
import com.veyq.app.generated.model.ScopeMember
import com.veyq.app.generated.model.ScopeRotated
import com.veyq.app.generated.model.ScopeSummary
import com.veyq.app.generated.model.SetMemberRoleRequest
import com.veyq.app.generated.model.SetScopeIconRequest
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.membership.Admission
import com.veyq.app.shared.membership.SignedGrant
import com.veyq.app.shared.tenant.RequestTenant
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

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
        "scope's next epoch commits, under a row lock, and the rest are `409 stale_epoch` with nothing stored.",
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
            Admission(createGroupRequest.admissionId.toString(), createGroupRequest.admissionEpoch, createGroupRequest.admissionTag),
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
                    )
                }).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun addGroupMember(scopeId: UUID, addGroupMemberRequest: AddGroupMemberRequest): Response =
        when (val outcome = handler.addGroupMember(
            identity.principal.name, scopeId.toString(), addGroupMemberRequest.username, addGroupMemberRequest.sealedNameKey,
            Admission(addGroupMemberRequest.admissionId.toString(), addGroupMemberRequest.admissionEpoch, addGroupMemberRequest.admissionTag),
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
            identity.principal.name, scopeId.toString(), renameGroupRequest.encryptedName, renameGroupRequest.nameEpoch,
        )) {
            RenameGroupOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun setScopeIcon(scopeId: UUID, setScopeIconRequest: SetScopeIconRequest): Response =
        when (val o = handler.setScopeIcon(
            identity.principal.name, scopeId.toString(), setScopeIconRequest.sealedIcon, setScopeIconRequest.iconEpoch,
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
                )
            },
        ).build()

    /** Map a directory row to the wire DTO — carries `pendingConsent` (cr-0094); the handler already gated its visibility. */
    private fun scopeMember(row: ScopeMemberRow) = ScopeMember(
        userId = UUID.fromString(row.userId), username = row.username, sealedName = row.sealedName,
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
}
