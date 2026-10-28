package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements
import me.raddatz.veyq.core.tasks.CompromiseRotationPolicy
import me.raddatz.veyq.core.tasks.CompromiseRotationState
import me.raddatz.veyq.core.tasks.RequeueResult
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.Access
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.admin.tenant.OpenInviteRow
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** Outcome of birthing a personal scope. */
sealed interface CreatePersonalOutcome {
    data class Created(val scopeId: String) : CreatePersonalOutcome
    object Suspended : CreatePersonalOutcome, HttpFailure by Failures.tenantSuspended
    object NoTenant : CreatePersonalOutcome, HttpFailure by Failures.tenantNotFound
    object AlreadyExists : CreatePersonalOutcome, HttpFailure by httpFailure("personal_scope_exists", CONFLICT)
    object IdTaken : CreatePersonalOutcome, HttpFailure by Failures.idTaken
}

/** Outcome of creating a group scope. Group names are not unique, so there is no "already exists" case. */
sealed interface CreateGroupOutcome {
    data class Created(val scopeId: String) : CreateGroupOutcome
    object Suspended : CreateGroupOutcome, HttpFailure by Failures.tenantSuspended
    object NoTenant : CreateGroupOutcome, HttpFailure by Failures.tenantNotFound
    /** The `core.group-space` capability is switched off instance-wide (ADR 0032) — a 422, a personal-only instance.
     *  Only creation is refused: a feature switch refuses only what is new, so an existing group stays usable (ADR 0088
     *  decision 5) and no other group operation asks the capability. */
    object CapabilityDisabled : CreateGroupOutcome, HttpFailure by Failures.capabilityDisabled
    object IdTaken : CreateGroupOutcome, HttpFailure by Failures.idTaken
}

/** Outcome of an owner re-queueing the scope's dead-lettered compromise rotation (uc.spaces.complete-queued-rotation E8). */
sealed interface RequeueScopeRotationOutcome {
    /** Queued again; the scope row's value now (null = nothing needs an owner). */
    data class Requeued(val compromiseRotation: CompromiseRotationState?) : RequeueScopeRotationOutcome
    object Suspended : RequeueScopeRotationOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RequeueScopeRotationOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : RequeueScopeRotationOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RequeueScopeRotationOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object NotFailed : RequeueScopeRotationOutcome, HttpFailure by httpFailure("rotation_not_failed", CONFLICT)
}

/** Outcome of listing a tenant's open member invites (ADR 0111 decision 2) — any member reads them, none sees an email. */
sealed interface ListOpenInvitesOutcome {
    data class Listed(val invites: List<OpenInviteRow>) : ListOpenInvitesOutcome
    object Suspended : ListOpenInvitesOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeNotFound : ListOpenInvitesOutcome, HttpFailure by Failures.scopeNotFound
}

/** Outcome of listing the caller's scopes. */
sealed interface ListScopesOutcome {
    data class Scopes(val rows: List<ScopeRow>) : ListScopesOutcome
    object NoTenant : ListScopesOutcome, HttpFailure by Failures.tenantNotFound
}

/** Outcome of adding a member to a group by username (cr-0086), with the grant the owner wrapped (ADR 0111 decision 4).
 *  `ConsentRequired` (cr-0096): the target has consent on but no `sealedNameKey` was supplied. */
sealed interface AddGroupMemberOutcome {
    data class Added(val row: ScopeMemberRow) : AddGroupMemberOutcome
    object Suspended : AddGroupMemberOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : AddGroupMemberOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : AddGroupMemberOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : AddGroupMemberOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object MemberNotFound : AddGroupMemberOutcome, HttpFailure by Failures.memberNotFound
    object AlreadyMember : AddGroupMemberOutcome, HttpFailure by Failures.alreadyMember
    object ConsentRequired : AddGroupMemberOutcome, HttpFailure by httpFailure("consent_required", BAD_REQUEST)
    /** The grant is of an epoch that is no longer the scope's current one. */
    object StaleEpoch : AddGroupMemberOutcome, HttpFailure by Failures.staleEpoch
    /** The grant names a key that is not the target's current identity. */
    object RecipientKeyNotCurrent : AddGroupMemberOutcome, HttpFailure by Failures.recipientKeyNotCurrent
}

/** Outcome of resolving a per-tenant username to a group-add candidate (cr-0094) — records nothing. */
sealed interface ResolveCandidateOutcome {
    data class Ok(val row: ResolvedCandidateRow) : ResolveCandidateOutcome
    object Suspended : ResolveCandidateOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeNotFound : ResolveCandidateOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : ResolveCandidateOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object MemberNotFound : ResolveCandidateOutcome, HttpFailure by Failures.memberNotFound
}

/** Outcome of accepting a consent proposal (cr-0094). Decline is unconditionally idempotent, so it has no outcome. */
sealed interface AcceptInvitationOutcome {
    object Ok : AcceptInvitationOutcome
    object NotFound : AcceptInvitationOutcome, HttpFailure by httpFailure("invitation_not_found", NOT_FOUND)
}

/** Outcome of listing the caller's pending group invitations (cr-0094). */
sealed interface ListInvitationsOutcome {
    data class Invitations(val rows: List<GroupInvitationRow>) : ListInvitationsOutcome
    object NoTenant : ListInvitationsOutcome, HttpFailure by Failures.tenantNotFound
}

/** Outcome of promote/demote (cr-0088). */
sealed interface SetRoleOutcome {
    data class Ok(val row: ScopeMemberRow) : SetRoleOutcome
    object Suspended : SetRoleOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : SetRoleOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : SetRoleOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : SetRoleOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object MemberNotFound : SetRoleOutcome, HttpFailure by Failures.memberNotFound
    object LastOwner : SetRoleOutcome, HttpFailure by Failures.lastOwner
}

/** Outcome of the member delete — since ADR 0024 Decision 4 only a pending proposal's rescind (cr-0094). */
sealed interface RemoveMemberOutcome {
    object Ok : RemoveMemberOutcome
    object Suspended : RemoveMemberOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RemoveMemberOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : RemoveMemberOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RemoveMemberOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object MemberNotFound : RemoveMemberOutcome, HttpFailure by Failures.memberNotFound
    /** The target is not a pending proposal: removing them is a removal-rotation, never a bare delete. */
    object RemovalRequiresRotation : RemoveMemberOutcome, HttpFailure by httpFailure("removal_requires_rotation", CONFLICT)
}

/** Outcome of a rotation-commit (cr-0088). */
sealed interface RotateOutcome {
    data class Ok(val epoch: Int) : RotateOutcome
    object Suspended : RotateOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RotateOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : RotateOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RotateOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    /** The caller's account rotation queued a still-live rotation of this scope that excludes them (ADR 0060 Decision 4). */
    object RotationExcluded : RotateOutcome, HttpFailure by httpFailure("rotation_excluded", FORBIDDEN)
    object StaleEpoch : RotateOutcome, HttpFailure by Failures.staleEpoch
    /** A TENANT rotation leaves an open member invite neither re-granted nor declared pending (ADR 0111 decision 2). */
    object Incomplete : RotateOutcome, HttpFailure by httpFailure("rotation_incomplete", CONFLICT)
    /** An invite re-grant names a key that is not the invite's — nothing stored. */
    object RecipientKeyNotCurrent : RotateOutcome, HttpFailure by Failures.recipientKeyNotCurrent
    /** A removal-rotation names no member of the scope — nothing stored. */
    object MemberNotFound : RotateOutcome, HttpFailure by Failures.memberNotFound
    /** A removal-rotation would remove the last OWNER (ADR 0007) — nothing stored. */
    object LastOwner : RotateOutcome, HttpFailure by Failures.lastOwner
    /** A removal-rotation re-grants the member it removes (ADR 0024 Decision 4) — nothing stored. */
    object RemovedMemberGranted : RotateOutcome, HttpFailure by httpFailure("removed_member_granted", UNPROCESSABLE)
    /** `revokeLinks` without `removesUserId` (ADR 0075) — nothing stored. */
    object InvalidRequest : RotateOutcome, HttpFailure by Failures.invalidRequest
}

/** Outcome of reading the epoch-chain links (cr-0088). */
sealed interface EpochsOutcome {
    data class Links(val links: List<Pair<Int, String>>) : EpochsOutcome
    object Suspended : EpochsOutcome, HttpFailure by Failures.tenantSuspended
    object NotKeyHolder : EpochsOutcome, HttpFailure by Failures.notKeyHolder
    object ScopeNotFound : EpochsOutcome, HttpFailure by Failures.scopeNotFound
}

/** Outcome of renaming a group (cr-0097). Owner-gated, GROUP-only, active-only, current-epoch-only. */
sealed interface RenameGroupOutcome {
    object Ok : RenameGroupOutcome
    object Suspended : RenameGroupOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RenameGroupOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : RenameGroupOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RenameGroupOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object StaleEpoch : RenameGroupOutcome, HttpFailure by httpFailure("stale_epoch", CONFLICT)
}

/** Outcome of setting/replacing a scope icon (uc.spaces.set-space-icon). Owner-gated, TENANT or GROUP
 *  (PERSONAL has no icon), active-only, current-epoch-only — the [RenameGroupOutcome] shape one scope-kind up. */
sealed interface SetScopeIconOutcome {
    object Ok : SetScopeIconOutcome
    object Suspended : SetScopeIconOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : SetScopeIconOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : SetScopeIconOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : SetScopeIconOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object StaleEpoch : SetScopeIconOutcome, HttpFailure by httpFailure("stale_epoch", CONFLICT)
}

/** Outcome of clearing a scope icon (uc.spaces.set-space-icon A5). Owner-gated, TENANT or GROUP; no epoch —
 *  a removal carries no ciphertext that could be stale — and idempotent. */
sealed interface ClearScopeIconOutcome {
    object Ok : ClearScopeIconOutcome
    object Suspended : ClearScopeIconOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : ClearScopeIconOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : ClearScopeIconOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : ClearScopeIconOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
}

/** Outcome of soft-deleting a group (cr-0090). */
sealed interface DeleteGroupOutcome {
    object Ok : DeleteGroupOutcome
    object Suspended : DeleteGroupOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteGroupOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : DeleteGroupOutcome, HttpFailure by Failures.scopeNotFound
    object NotGroup : DeleteGroupOutcome, HttpFailure by httpFailure("not_group", CONFLICT)
    object NotOwner : DeleteGroupOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
}

/** Outcome of a scope owner's standalone switch-off of every link (ADR 0075 point 5). */
sealed interface RevokeScopeSharesOutcome {
    data class Ok(val revoked: Int) : RevokeScopeSharesOutcome
    object Suspended : RevokeScopeSharesOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RevokeScopeSharesOutcome, HttpFailure by Failures.scopeDeleting
    object ScopeNotFound : RevokeScopeSharesOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RevokeScopeSharesOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
}

/** Outcome of restoring a soft-deleted group (cr-0090). */
sealed interface RestoreGroupOutcome {
    object Ok : RestoreGroupOutcome
    object Suspended : RestoreGroupOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeNotFound : RestoreGroupOutcome, HttpFailure by Failures.scopeNotFound
    object NotOwner : RestoreGroupOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object NotDeleted : RestoreGroupOutcome, HttpFailure by httpFailure("not_deleted", CONFLICT)
    object NotGroup : RestoreGroupOutcome, HttpFailure by httpFailure("not_group", CONFLICT)
}

/** Outcome of listing a scope's members (the directory, cr-0077). */
sealed interface ListMembersOutcome {
    data class Members(val rows: List<ScopeMemberRow>) : ListMembersOutcome
    object Suspended : ListMembersOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeNotFound : ListMembersOutcome, HttpFailure by Failures.scopeNotFound
    object NotKeyHolder : ListMembersOutcome, HttpFailure by Failures.notKeyHolder
}

/**
 * Use-case logic for a member's scopes (en.spaces.personal-scope, cr-0026). The active tenant arrives resolved
 * (`RequestTenant`, from the request Host) as an id, null when the Host names no tenant; the caller must already
 * belong to it (you get a personal scope in tenants you are a member of). Every scope- and tenant-addressed
 * operation here asks the one gate, [ScopeAccess], before anything else (uc.spaces.scope-access). Birth is
 * client-driven and operator-blind — the handler only persists the ciphertext grant.
 */
@ApplicationScoped
class ScopesHandler(
    private val store: ScopesStore,
    private val capabilities: CapabilityRegistry,
    private val access: ScopeAccess,
    private val tasks: TasksStore,
    config: VeyqConfig,
) {
    private val maxAttempts: Int = config.tasks().maxAttempts()

    fun createPersonal(userId: String, tenantId: String?, scopeId: String, grant: SignedGrant): CreatePersonalOutcome {
        tenantId ?: return CreatePersonalOutcome.NoTenant
        access.check(userId, tenantId, WRITE)
            .refusal(CreatePersonalOutcome.NoTenant, CreatePersonalOutcome.Suspended)?.let { return it }
        if (store.personalScopeExists(userId, tenantId)) return CreatePersonalOutcome.AlreadyExists
        // The client-minted scope id (ADR 0072): the self-grant is sealed bound to it. A concurrent create's loser
        // answers as its replay does — the two checks above, in their order — whether it loses at the commit or at
        // the store's own id check, which a winner that committed after the check above already trips. At the commit
        // it loses on the id or on `ux_scope_personal_owner`, which a create of this member under another id holds.
        val replay = {
            when {
                store.personalScopeExists(userId, tenantId) -> CreatePersonalOutcome.AlreadyExists
                store.scopeIdTaken(scopeId) -> CreatePersonalOutcome.IdTaken
                else -> null
            }
        }
        return createOrConflict(conflict = replay) {
            // The gate again, under the tenant root's lock, taken first: a tenant removal holds it while it plans its
            // cascade from the member's memberships (developer-styleguide §12). Checked only before it, a member being
            // removed created a space that cascade never saw, and kept it in a tenant they had left.
            store.lockScope(tenantId)
            access.check(userId, tenantId, WRITE)
                .refusal(CreatePersonalOutcome.NoTenant, CreatePersonalOutcome.Suspended)?.let { return@createOrConflict it }
            if (store.createPersonal(scopeId, userId, tenantId, grant)) {
                CreatePersonalOutcome.Created(scopeId)
            } else {
                replay() ?: CreatePersonalOutcome.IdTaken
            }
        }
    }

    /**
     * Create a named group scope in the active tenant (en.spaces.group-scope, cr-0032, ADR 0014). The caller
     * must belong to the tenant; birth is client-driven and operator-blind — the handler persists only the
     * ciphertext name + the ciphertext self-grant, never the scope key or the plaintext name.
     */
    fun createGroup(
        userId: String, tenantId: String?, scopeId: String, encryptedName: String, grant: SignedGrant,
    ): CreateGroupOutcome {
        // Policy first (ADR 0032, ADR 0088): a personal-only instance or tenant switches group scopes off → 422,
        // enforced server-side even if a stale client still offers the affordance. The instance level is asked even
        // without a tenant, so a request from no tenant's domain still reads as the operator's switch.
        if (!capabilities.isActive(CapabilityIds.GROUP_SPACE, tenantId)) return CreateGroupOutcome.CapabilityDisabled
        tenantId ?: return CreateGroupOutcome.NoTenant
        access.check(userId, tenantId, WRITE)
            .refusal(CreateGroupOutcome.NoTenant, CreateGroupOutcome.Suspended)?.let { return it }
        // The client-minted scope id (ADR 0072): the self-grant and the group name are sealed bound to it.
        return createOrConflict(conflict = { if (store.scopeIdTaken(scopeId)) CreateGroupOutcome.IdTaken else null }) {
            // The gate again, under the tenant root's lock a tenant removal holds, as [createPersonal] does.
            store.lockScope(tenantId)
            access.check(userId, tenantId, WRITE)
                .refusal(CreateGroupOutcome.NoTenant, CreateGroupOutcome.Suspended)?.let { return@createOrConflict it }
            if (store.createGroup(scopeId, userId, tenantId, encryptedName, grant)) CreateGroupOutcome.Created(scopeId) else CreateGroupOutcome.IdTaken
        }
    }

    /**
     * Every open member invite of the TENANT scope [scopeId] with its key (ADR 0111 decision 2) — what any member's
     * rotation must carry, so every member reads it; never an email. Membership first (ADR 0042). A scope that is no
     * TENANT has none: an empty list.
     */
    fun listOpenInvites(callerId: String, scopeId: String): ListOpenInvitesOutcome {
        access.check(callerId, scopeId, READ)
            .refusal(ListOpenInvitesOutcome.ScopeNotFound, ListOpenInvitesOutcome.Suspended)?.let { return it }
        return ListOpenInvitesOutcome.Listed(store.openInvites(scopeId))
    }

    fun listMine(userId: String, tenantId: String?): ListScopesOutcome {
        tenantId ?: return ListScopesOutcome.NoTenant
        return ListScopesOutcome.Scopes(store.listMine(userId, tenantId))
    }

    /**
     * Rename a GROUP (uc.spaces.rename-group, cr-0097, ADR 0027). Owner-gated ([ADR 0014](0014) §4 — the name
     * authority); GROUP-only; refused while the group is being deleted, like every write (the gate). The client
     * sealed the new name under `K_name` of the **current** epoch and sends that epoch back; the server enforces
     * `nameEpoch == currentEpoch` (`StaleEpoch` on a concurrent rotation) so the name stays forward-secret, then
     * overwrites the row's ciphertext name + name-epoch. Name-only — no `K_SC` rotation, no re-grant. Operator-blind:
     * the server never sees a key or the plaintext name.
     */
    fun renameGroup(callerId: String, scopeId: String, encryptedName: String, nameEpoch: Int): RenameGroupOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(RenameGroupOutcome.ScopeNotFound, RenameGroupOutcome.Suspended, RenameGroupOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return RenameGroupOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RenameGroupOutcome.NotOwner
        // The fast path; the store decides again in the write itself, where a concurrent rotation is seen.
        if (store.currentEpoch(scopeId) != nameEpoch) return RenameGroupOutcome.StaleEpoch
        return if (store.renameGroup(scopeId, encryptedName, nameEpoch, PrincipalKey.User(callerId))) RenameGroupOutcome.Ok else RenameGroupOutcome.StaleEpoch
    }

    /**
     * Set or replace a space icon (uc.spaces.set-space-icon). Owner-gated on the scope **root** row — for a
     * `kind=TENANT` row `isScopeOwner(caller, scopeId==tenantId)` **is** the tenant-owner check (ADR 0007 — no new
     * query, and no opening to the `tenantId`-only gate cr-0163 fixed); a GROUP root gates the same way and a GROUP
     * **co-owner** is an OWNER too. TENANT or GROUP only — a PERSONAL scope has no icon (rejected); refused while
     * the scope is being deleted, like every write (the gate). The client sealed the padded, constant-length
     * icon under `K_icon` of the **current** epoch and sends that epoch back; the server enforces `iconEpoch ==
     * currentEpoch` (`StaleEpoch` on a concurrent rotation) so a replace stays forward-secret, then overwrites the
     * row's ciphertext icon + icon-epoch. (A GROUP rotates on member-removal, ADR 0024, so its `iconEpoch` may
     * later sit below `currentEpoch` — the read peels the chain; a rotation never re-seals the icon here.) Guard
     * order is [renameGroup]'s exactly (access gate → kind → owner → epoch, ADR 0042). Operator-blind: the
     * server never sees a key or the image.
     */
    fun setScopeIcon(callerId: String, scopeId: String, sealedIcon: String, iconEpoch: Int): SetScopeIconOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(SetScopeIconOutcome.ScopeNotFound, SetScopeIconOutcome.Suspended, SetScopeIconOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) == ScopeEntity.PERSONAL) return SetScopeIconOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return SetScopeIconOutcome.NotOwner
        // The fast path; the store decides again in the write itself, where a concurrent rotation is seen.
        if (store.currentEpoch(scopeId) != iconEpoch) return SetScopeIconOutcome.StaleEpoch
        return if (store.setIcon(scopeId, sealedIcon, iconEpoch, PrincipalKey.User(callerId))) SetScopeIconOutcome.Ok else SetScopeIconOutcome.StaleEpoch
    }

    /**
     * Remove a space icon (uc.spaces.set-space-icon A5) — reset the row's icon + epoch; every member falls back to
     * the initial. Same owner gate as the set, but **no epoch check** (a removal carries no ciphertext that could
     * be sealed under a stale epoch) and idempotent. Access-gated first (uc.spaces.scope-access), then kind
     * (PERSONAL is rejected), then owner. Like every write it is refused while the scope is being deleted: the grace
     * window promises that nothing about the space changes, its icon included.
     */
    fun clearScopeIcon(callerId: String, scopeId: String): ClearScopeIconOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(ClearScopeIconOutcome.ScopeNotFound, ClearScopeIconOutcome.Suspended, ClearScopeIconOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) == ScopeEntity.PERSONAL) return ClearScopeIconOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return ClearScopeIconOutcome.NotOwner
        store.clearIcon(scopeId)
        return ClearScopeIconOutcome.Ok
    }

    /**
     * Add an existing tenant member to a **group** by username (uc.spaces.add-group-member, cr-0086). Owner-gated
     * ([ADR 0014](0014) §4 — only a group OWNER; the tenant owner has no say); the username is resolved **within the
     * scope's own tenant** (ADR 0020 — never a global search). Records a MEMBER membership **with the grant** the
     * owner's client wrapped to the target's identity key (ADR 0111 decision 4): no invite key, the target has an
     * account. The returned row has its `email` nulled (the add response never exposes it).
     */
    fun addGroupMember(
        callerId: String, scopeId: String, username: String, sealedNameKey: String?, grant: SignedGrant, epoch: Int,
        recipientIdentityDigest: String,
    ): AddGroupMemberOutcome {
        val tenantId = when (val gate = addGate(callerId, scopeId, username)) {
            is AddGate.Refused -> return gate.outcome
            is AddGate.Open -> gate.tenantId
        }
        // The gates again, under the locks of the tenant root and the group, in id order (the order a tenant removal
        // takes every lock in), taken first in the write's transaction (developer-styleguide §12). A tenant removal
        // of the target holds the root's lock and one of the caller holds the group's. Checked only before them, a
        // member being removed from the tenant was added to a group after the removal had cut their memberships.
        return QuarkusTransaction.requiringNew().call {
            listOf(tenantId, scopeId).sorted().forEach { store.lockScope(it) }
            when (val gate = addGate(callerId, scopeId, username)) {
                is AddGate.Refused -> gate.outcome
                is AddGate.Open -> addUnderLocks(gate.tenantId, gate.targetId, scopeId, sealedNameKey, callerId, grant, epoch, recipientIdentityDigest)
            }
        }
    }

    private sealed interface AddGate {
        data class Refused(val outcome: AddGroupMemberOutcome) : AddGate
        data class Open(val tenantId: String, val targetId: String) : AddGate
    }

    /** Every refusal of [addGroupMember] that reads state a concurrent write can change, in the order they answer. */
    private fun addGate(callerId: String, scopeId: String, username: String): AddGate {
        access.check(callerId, scopeId, WRITE)
            .refusal(AddGroupMemberOutcome.ScopeNotFound, AddGroupMemberOutcome.Suspended, AddGroupMemberOutcome.Deleting)
            ?.let { return AddGate.Refused(it) }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return AddGate.Refused(AddGroupMemberOutcome.ScopeNotFound)
        if (!store.isScopeOwner(callerId, scopeId)) return AddGate.Refused(AddGroupMemberOutcome.NotOwner)
        val tenantId = store.scopeTenantId(scopeId) ?: return AddGate.Refused(AddGroupMemberOutcome.ScopeNotFound)
        val targetId = store.resolveUsernameInTenant(tenantId, username)
            ?: return AddGate.Refused(AddGroupMemberOutcome.MemberNotFound)
        if (!store.belongsToTenant(targetId, tenantId)) return AddGate.Refused(AddGroupMemberOutcome.MemberNotFound)
        if (store.holdsAnyRow(targetId, scopeId)) return AddGate.Refused(AddGroupMemberOutcome.AlreadyMember)
        return AddGate.Open(tenantId, targetId)
    }

    private fun addUnderLocks(
        tenantId: String, targetId: String, scopeId: String, sealedNameKey: String?, callerId: String, grant: SignedGrant,
        epoch: Int, recipientIdentityDigest: String,
    ): AddGroupMemberOutcome {
        // The grant names the key it is wrapped to (ADR 0111): the target's current identity, or nothing is written.
        if (!InviteAcceptProof.digestMatches(store.identityPublicKeyOf(targetId), recipientIdentityDigest)) {
            return AddGroupMemberOutcome.RecipientKeyNotCurrent
        }
        // Consent-first (cr-0096, ADR 0027): if the target asked to be consulted, record a soft `pendingConsent`
        // membership carrying the wrapped name-key `K_name` beside the grant; the group is not listed to them until
        // they accept. The owner must have derived + sealed it (learned consent is required from the prior resolve);
        // no name-key → ConsentRequired. Without consent, any name-key sent is not stored.
        val proposal = if (consentRequired(targetId, tenantId)) {
            sealedNameKey ?: return AddGroupMemberOutcome.ConsentRequired
        } else {
            null
        }
        if (!store.addMember(targetId, tenantId, scopeId, grant, epoch, callerId, proposal)) {
            // Refused only when stated at an epoch a rotation already left.
            return AddGroupMemberOutcome.StaleEpoch
        }
        val row = store.scopeMemberRow(scopeId, targetId) ?: return AddGroupMemberOutcome.MemberNotFound
        return AddGroupMemberOutcome.Added(row.copy(email = null))
    }

    /** Whether adding [targetId] in [tenantId] must ask them first: their own setting, or the `addConsent` requirement in
     *  effect for the tenant (ADR 0088 decision 8), which overrides a "no". Asked by the resolve that tells the owner's
     *  client to seal a name key and by the add that refuses one without it, so the two never disagree. */
    private fun consentRequired(targetId: String, tenantId: String): Boolean =
        store.requiresGroupAddConsent(targetId) ||
            capabilities.requirements(tenantId)[CapabilityRequirements.ADD_CONSENT] != null

    /**
     * Resolve a per-tenant username to a group-add candidate (uc.spaces.consent-group-add, cr-0094) — records
     * nothing. Access-gated first, then owner-gated (the same gates as the add). The **owner** gate is
     * convenience, **not** a confidentiality barrier: any tenant member can create a group and become its owner in
     * O(1), so username → identity-public-key → consent-flag are effectively enumerable by every tenant member.
     * That is within the metadata model (per-tenant usernames + identity pubkeys are tenant-visible, ADR 0023/0014
     * §5), not a leak — don't read the gate as one. That argument covers *username* enumerability only; the
     * **membership** gate above it is load-bearing (ADR 0042) — it is what keeps a stranger from enumerating
     * *scope ids*, which is not tenant-visible metadata.
     * Returns the candidate's identity public key (to seal the derived group name-key to) + whether consent is required.
     */
    fun resolveGroupMember(callerId: String, scopeId: String, username: String): ResolveCandidateOutcome {
        access.check(callerId, scopeId, READ)
            .refusal(ResolveCandidateOutcome.ScopeNotFound, ResolveCandidateOutcome.Suspended)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return ResolveCandidateOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return ResolveCandidateOutcome.NotOwner
        val tenantId = store.scopeTenantId(scopeId) ?: return ResolveCandidateOutcome.ScopeNotFound
        val targetId = store.resolveUsernameInTenant(tenantId, username) ?: return ResolveCandidateOutcome.MemberNotFound
        if (!store.belongsToTenant(targetId, tenantId)) return ResolveCandidateOutcome.MemberNotFound
        val pubkey = store.identityPublicKeyOf(targetId) ?: return ResolveCandidateOutcome.MemberNotFound
        return ResolveCandidateOutcome.Ok(
            ResolvedCandidateRow(targetId, username.trim().lowercase(), pubkey, consentRequired(targetId, tenantId)),
        )
    }

    /**
     * Accept a consent proposal (cr-0094): clear the caller's own `pendingConsent` flag, so it becomes an ordinary
     * membership — with the grant the proposal already holds (ADR 0111 decision 4). Invitee-gated (acts only on the caller's
     * membership); idempotent — an already-accepted membership is a no-op Ok; no membership at all is NotFound.
     */
    fun acceptInvitation(callerId: String, scopeId: String): AcceptInvitationOutcome =
        when {
            store.clearPendingConsent(callerId, scopeId) > 0 -> AcceptInvitationOutcome.Ok
            store.consentState(callerId, scopeId) == ConsentState.ACTIVE -> AcceptInvitationOutcome.Ok // already accepted
            else -> AcceptInvitationOutcome.NotFound
        }

    /**
     * Decline (invitee) / rescind (owner reuses this via member-remove) a consent proposal (cr-0094): delete the
     * caller's own soft membership. The proposal held a grant of the group's key (ADR 0111 decision 4), so a decline
     * that deleted one **queues the group's rotation** in the same transaction, as a tenant removal's cut does — under
     * the scope row's lock (lock order scope → membership → job). No `declined` marker is kept. Idempotent — always
     * succeeds (no open proposal is still a silent no-op, so it is no enumeration oracle).
     */
    fun declineInvitation(callerId: String, scopeId: String) = dropProposal(callerId, scopeId)

    /** Delete [userId]'s proposal on [scopeId] and, when there was one, queue the group's rotation in the same
     *  transaction under the scope row's lock — the decline's and the owner's rescind's one path. */
    private fun dropProposal(userId: String, scopeId: String) {
        QuarkusTransaction.requiringNew().run {
            store.lockScope(scopeId)
            if (store.deletePendingMembership(userId, scopeId) > 0) tasks.originateScopeRotation(scopeId, maxAttempts)
        }
    }

    /** The caller's pending group invitations in the active tenant (cr-0094) — the invitee's Accept/Decline view. */
    fun listInvitations(userId: String, tenantId: String?): ListInvitationsOutcome {
        tenantId ?: return ListInvitationsOutcome.NoTenant
        return ListInvitationsOutcome.Invitations(store.listInvitations(userId, tenantId))
    }

    /**
     * Promote a member to co-owner or demote a co-owner (uc.spaces.manage-group-members, cr-0088). Owner-gated;
     * the **last-owner guard** rejects demoting the last OWNER (ADR 0007). A pure role change — no key moves. The
     * owner, member and last-owner checks run under the scope lock ([ScopesStore.changeRole]), so of two overlapping
     * changes the second answers what its replay would.
     */
    fun setMemberRole(callerId: String, scopeId: String, targetId: String, role: String): SetRoleOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(SetRoleOutcome.ScopeNotFound, SetRoleOutcome.Suspended, SetRoleOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return SetRoleOutcome.ScopeNotFound
        when (store.changeRole(callerId, targetId, scopeId, role)) {
            ScopesStore.RoleChange.NOT_OWNER -> return SetRoleOutcome.NotOwner
            ScopesStore.RoleChange.MEMBER_NOT_FOUND -> return SetRoleOutcome.MemberNotFound
            ScopesStore.RoleChange.LAST_OWNER -> return SetRoleOutcome.LastOwner
            ScopesStore.RoleChange.CHANGED -> Unit
        }
        // Build the response from the role we just set — the same-request read-back can see a stale persistence
        // context (the DB is committed; the guard checks below run in fresh requests against committed data).
        val row = store.scopeMemberRow(scopeId, targetId) ?: return SetRoleOutcome.MemberNotFound
        return SetRoleOutcome.Ok(row.copy(role = role, email = null))
    }

    /**
     * Rescind a pending consent proposal (cr-0094) — the one membership a delete may take without committing a rotation
     * itself: a proposal holds the group's key (ADR 0111 decision 4), so the rescind queues the group's rotation for the
     * next online owner, as a decline does. Any other member leaves only by a removal-rotation ([rotateScope] with `removes`,
     * ADR 0024 Decision 4), so this refuses them (`removal_requires_rotation`) and deletes nothing: a delete that
     * committed on its own is how a removal used to end without its rotation. Owner-gated.
     */
    fun removeMember(callerId: String, scopeId: String, targetId: String): RemoveMemberOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(RemoveMemberOutcome.ScopeNotFound, RemoveMemberOutcome.Suspended, RemoveMemberOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return RemoveMemberOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RemoveMemberOutcome.NotOwner
        return when (store.consentState(targetId, scopeId)) {
            ConsentState.NONE -> RemoveMemberOutcome.MemberNotFound
            ConsentState.ACTIVE -> RemoveMemberOutcome.RemovalRequiresRotation
            ConsentState.PENDING -> {
                // The proposal held a grant (ADR 0111 decision 4): a rescind queues the group's rotation, as a decline does.
                dropProposal(targetId, scopeId)
                RemoveMemberOutcome.Ok
            }
        }
    }

    /**
     * Commit a scope-key rotation (cr-0088, ADR 0024 §4): commit the stated [epoch] iff it is the scope's next one,
     * store the chain-link, re-grant the listed remaining members; any other epoch is `409 stale_epoch` with nothing
     * stored. Owner-gated; the client minted the new epoch on-device (the server stores only ciphertext).
     *
     * With [removes] it is a **removal-rotation** (ADR 0024 Decision 4): [ScopesStore.rotate] deletes the membership
     * and soft-revokes the public shares that member created in the group (cr-0104) in the **same transaction** as
     * the epoch commit, so a commit that fails after the delete rolls the removal back with it. [revokeLinks]
     * (removals only) turns off every other link of the group in that same transaction too (ADR 0075).
     */
    fun rotateScope(
        callerId: String, scopeId: String, epoch: Int, wrappedPrev: String,
        grants: List<RotationGrant>, inviteGrants: List<InviteGrant>, pendingInviteIds: Set<String>, removes: String? = null,
        revokeLinks: Boolean = false,
    ): RotateOutcome {
        // A request-shape refusal, like the bean validation before it: `revokeLinks` belongs to a removal (ADR 0075).
        if (revokeLinks && removes == null) return RotateOutcome.InvalidRequest
        access.check(callerId, scopeId, WRITE)
            .refusal(RotateOutcome.ScopeNotFound, RotateOutcome.Suspended, RotateOutcome.Deleting)?.let { return it }
        if (!store.isScopeOwner(callerId, scopeId)) return RotateOutcome.NotOwner
        if (store.currentEpoch(scopeId) == null) return RotateOutcome.ScopeNotFound
        return when (val d = store.rotate(scopeId, epoch, wrappedPrev, callerId, grants, inviteGrants, pendingInviteIds, removes, revokeLinks)) {
            is RotationDecision.Commit -> RotateOutcome.Ok(d.plan.newEpoch)
            RotationDecision.Excluded -> RotateOutcome.RotationExcluded
            RotationDecision.Stale -> RotateOutcome.StaleEpoch
            RotationDecision.Incomplete -> RotateOutcome.Incomplete
            RotationDecision.RecipientKeyNotCurrent -> RotateOutcome.RecipientKeyNotCurrent
            RotationDecision.MemberNotFound -> RotateOutcome.MemberNotFound
            RotationDecision.LastOwner -> RotateOutcome.LastOwner
            RotationDecision.RemovedMemberGranted -> RotateOutcome.RemovedMemberGranted
        }
    }

    /**
     * An owner queues the scope's dead-lettered compromise rotation again (uc.spaces.complete-queued-rotation E8,
     * ADR 0060 Decision 6) — the action behind the owner row's `failed`. Membership first (ADR 0042), then ownership;
     * then, under the scope row's lock, every rotated account whose rotation here is `FAILED` is excluded from a joined
     * or fresh job again, and the caller is not (unless they are one of them), so their own worker can claim it.
     * Anything but `FAILED` is `409 rotation_not_failed` with nothing written. The state gate is also its rate
     * bound: at most one live covering job, and a job needs every one of its attempts failed before it is
     * re-queueable.
     */
    fun requeueScopeRotation(callerId: String, scopeId: String): RequeueScopeRotationOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(RequeueScopeRotationOutcome.ScopeNotFound, RequeueScopeRotationOutcome.Suspended, RequeueScopeRotationOutcome.Deleting)
            ?.let { return it }
        if (!store.isScopeOwner(callerId, scopeId)) return RequeueScopeRotationOutcome.NotOwner
        return when (val r = tasks.requeueFailedRotations(scopeId, null, maxAttempts)) {
            is RequeueResult.Requeued ->
                RequeueScopeRotationOutcome.Requeued(CompromiseRotationPolicy.ownerView(r.rows.map { it.state }))
            RequeueResult.NoRotation, RequeueResult.NotFailed -> RequeueScopeRotationOutcome.NotFailed
        }
    }

    /**
     * Soft-delete a group (uc.spaces.delete-group, cr-0090, ADR 0014 §6) — set the `deletedAt` marker so the
     * group enters its grace period (read-only + restorable). Owner-gated; GROUP-only; a group already being
     * deleted answers `scope_deleting` like any other write. Memberships/grants are untouched, so a restore is trivial.
     */
    fun deleteGroup(callerId: String, scopeId: String): DeleteGroupOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(DeleteGroupOutcome.ScopeNotFound, DeleteGroupOutcome.Suspended, DeleteGroupOutcome.Deleting)?.let { return it }
        val kind = store.scopeKind(scopeId) ?: return DeleteGroupOutcome.ScopeNotFound
        if (kind != ScopeEntity.GROUP) return DeleteGroupOutcome.NotGroup
        if (!store.isScopeOwner(callerId, scopeId)) return DeleteGroupOutcome.NotOwner
        store.softDelete(scopeId, java.time.Instant.now())
        return DeleteGroupOutcome.Ok
    }

    /**
     * Turn off every link of a scope (ADR 0075 point 5): every enabled share, whoever made it, goes off and is marked,
     * so each comes back only by re-issue. Any owner of the scope — a group owner, a tenant owner, the personal scope's
     * owner: one gate, any kind. Access first (a non-member's 404 is an unknown scope's), then ownership. Idempotent:
     * a second call turns nothing off and answers 0.
     */
    fun revokeScopeShares(callerId: String, scopeId: String): RevokeScopeSharesOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(RevokeScopeSharesOutcome.ScopeNotFound, RevokeScopeSharesOutcome.Suspended, RevokeScopeSharesOutcome.Deleting)
            ?.let { return it }
        if (!store.isScopeOwner(callerId, scopeId)) return RevokeScopeSharesOutcome.NotOwner
        return RevokeScopeSharesOutcome.Ok(store.turnOffAllShares(scopeId))
    }

    /**
     * Restore a group from its deletion grace (cr-0090) — clear the marker, active again for everyone. The one write
     * a deleting group allows (uc.spaces.scope-access A1): an owner who still belongs to the tenant, on a GROUP;
     * `409 not_deleted` if the group is not being deleted; a hard-purged scope is simply gone (the gate's 404).
     */
    fun restoreGroup(callerId: String, scopeId: String): RestoreGroupOutcome {
        // The one write a deleting group allows (uc.spaces.scope-access A1): `Deleting` is its precondition, not a
        // refusal — and only that is waived, so a former tenant member still stops at the gate (A2).
        val state = access.check(callerId, scopeId, WRITE)
        if (state != Access.Deleting) {
            state.refusal(RestoreGroupOutcome.ScopeNotFound, RestoreGroupOutcome.Suspended)?.let { return it }
        }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return RestoreGroupOutcome.NotGroup
        if (!store.isScopeOwner(callerId, scopeId)) return RestoreGroupOutcome.NotOwner
        if (state != Access.Deleting) return RestoreGroupOutcome.NotDeleted
        return when (store.restore(scopeId)) {
            ScopesStore.Restore.RESTORED -> RestoreGroupOutcome.Ok
            ScopesStore.Restore.NOT_DELETED -> RestoreGroupOutcome.NotDeleted
            ScopesStore.Restore.GONE -> RestoreGroupOutcome.ScopeNotFound // erased by the purge since the gate read it
        }
    }

    /**
     * The scope's epoch-chain links — any key-holder in the scope may read them (cr-0088, ADR 0024). The access
     * gate first (ADR 0042): a non-member gets the same 404 as a bogus id. A member without a grant gets 403 — they
     * already know the scope exists, so that discloses nothing. (Until cr-0162 this probed the epoch **before**
     * the gate, which made the 404-vs-403 split an existence oracle for a stranger.)
     */
    fun listEpochs(callerId: String, scopeId: String): EpochsOutcome {
        access.check(callerId, scopeId, READ)
            .refusal(EpochsOutcome.ScopeNotFound, EpochsOutcome.Suspended)?.let { return it }
        if (!store.holdsGrant(callerId, scopeId)) return EpochsOutcome.NotKeyHolder
        return EpochsOutcome.Links(store.epochLinks(scopeId))
    }

    /** Every tenant the caller holds an accepted membership in (Host-independent) — the space switcher. */
    fun myTenants(userId: String): List<TenantRow> = store.myTenants(userId)

    /**
     * The member directory of a scope (uc.spaces.add-member, ADR 0020). Only a **key-holder** in the scope may
     * read it. The access gate first (ADR 0042): a non-member — or a bogus scope id — gets a uniform 404, so scope
     * existence is not enumerable; a member without a grant gets 403. Returns each member's per-tenant identity
     * (username + sealed name), pubkey + grant-present; **email is owner-only** — nulled for a non-owner caller
     * (ADR 0023).
     */
    fun listMembers(userId: String, scopeId: String): ListMembersOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListMembersOutcome.ScopeNotFound, ListMembersOutcome.Suspended)?.let { return it }
        if (!store.holdsGrant(userId, scopeId)) return ListMembersOutcome.NotKeyHolder
        val tenantId = store.scopeTenantId(scopeId) ?: return ListMembersOutcome.ScopeNotFound
        val tenantOwner = store.isTenantOwner(userId, tenantId)
        // Pending consent proposals (cr-0094) are visible to **group owners** only — ordinary members see a proposed
        // person only once they accept. Email stays tenant-owner-only (ADR 0023), as before.
        val scopeOwner = store.isScopeOwner(userId, scopeId)
        val rows = store.listMembers(scopeId)
            .filter { scopeOwner || !it.pendingConsent }
            .map { if (tenantOwner) it else it.copy(email = null) }
        return ListMembersOutcome.Members(rows)
    }
}
