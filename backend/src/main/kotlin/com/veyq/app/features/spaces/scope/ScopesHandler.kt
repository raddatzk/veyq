package com.veyq.app.features.spaces.scope

import com.veyq.app.features.platform.capabilities.CapabilityIds
import com.veyq.app.features.platform.capabilities.CapabilityRegistry
import com.veyq.app.shared.http.FailureStatus.BAD_REQUEST
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.Access
import com.veyq.app.shared.membership.Admission
import com.veyq.app.shared.membership.SignedGrant
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import com.veyq.app.shared.scope.ScopeEntity
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
    /** The `spaces.groups` capability is disabled instance-wide (ADR 0032) — a 422, a personal-only instance. */
    object CapabilityDisabled : CreateGroupOutcome, HttpFailure by Failures.capabilityDisabled
    object IdTaken : CreateGroupOutcome, HttpFailure by Failures.idTaken
}

/** Outcome of listing the caller's scopes. */
sealed interface ListScopesOutcome {
    data class Scopes(val rows: List<ScopeRow>) : ListScopesOutcome
    object NoTenant : ListScopesOutcome, HttpFailure by Failures.tenantNotFound
}

/** Outcome of adding a member to a group by username (cr-0086). Admission only — the key is delivered later.
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
    /** The admission was tagged at an epoch that is no longer the scope's current one (ADR 0020 move 6). */
    object StaleEpoch : AddGroupMemberOutcome, HttpFailure by Failures.staleEpoch
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
    object StaleEpoch : RotateOutcome, HttpFailure by Failures.staleEpoch
    /** The rotation leaves an open admission neither re-tagged nor dropped (ADR 0020 move 6) — nothing stored. */
    object AdmissionsIncomplete : RotateOutcome, HttpFailure by httpFailure("admissions_incomplete", CONFLICT)
    /** A removal-rotation names no member of the scope — nothing stored. */
    object MemberNotFound : RotateOutcome, HttpFailure by Failures.memberNotFound
    /** A removal-rotation would remove the last OWNER (ADR 0007) — nothing stored. */
    object LastOwner : RotateOutcome, HttpFailure by Failures.lastOwner
    /** A removal-rotation re-grants the member it removes (ADR 0024 Decision 4) — nothing stored. */
    object RemovedMemberGranted : RotateOutcome, HttpFailure by httpFailure("removed_member_granted", UNPROCESSABLE)
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
) {

    fun createPersonal(userId: String, tenantId: String?, scopeId: String, grant: SignedGrant): CreatePersonalOutcome {
        tenantId ?: return CreatePersonalOutcome.NoTenant
        access.check(userId, tenantId, WRITE)
            .refusal(CreatePersonalOutcome.NoTenant, CreatePersonalOutcome.Suspended)?.let { return it }
        if (store.personalScopeExists(userId, tenantId)) return CreatePersonalOutcome.AlreadyExists
        // The client-minted scope id (ADR 0072): the self-grant is sealed bound to it.
        if (!store.createPersonal(scopeId, userId, tenantId, grant)) return CreatePersonalOutcome.IdTaken
        return CreatePersonalOutcome.Created(scopeId)
    }

    /**
     * Create a named group scope in the active tenant (en.spaces.group-scope, cr-0032, ADR 0014). The caller
     * must belong to the tenant; birth is client-driven and operator-blind — the handler persists only the
     * ciphertext name + the ciphertext self-grant, never the scope key or the plaintext name.
     */
    fun createGroup(
        userId: String, tenantId: String?, scopeId: String, encryptedName: String, grant: SignedGrant, admission: Admission,
    ): CreateGroupOutcome {
        // Instance policy first (ADR 0032): a personal-only instance disables group scopes → 422, enforced
        // server-side even if a stale client still offers the affordance.
        if (!capabilities.isActive(CapabilityIds.SPACES_GROUPS)) return CreateGroupOutcome.CapabilityDisabled
        tenantId ?: return CreateGroupOutcome.NoTenant
        access.check(userId, tenantId, WRITE)
            .refusal(CreateGroupOutcome.NoTenant, CreateGroupOutcome.Suspended)?.let { return it }
        // The client-minted scope id (ADR 0072): the self-grant and the group name are sealed bound to it.
        if (!store.createGroup(scopeId, userId, tenantId, encryptedName, grant, admission)) return CreateGroupOutcome.IdTaken
        return CreateGroupOutcome.Created(scopeId)
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
        if (store.currentEpoch(scopeId) != nameEpoch) return RenameGroupOutcome.StaleEpoch
        store.renameGroup(scopeId, encryptedName, nameEpoch)
        return RenameGroupOutcome.Ok
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
        if (store.currentEpoch(scopeId) != iconEpoch) return SetScopeIconOutcome.StaleEpoch
        store.setIcon(scopeId, sealedIcon, iconEpoch)
        return SetScopeIconOutcome.Ok
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
     * Add an existing tenant member to a **group** by username (uc.spaces.add-group-member, cr-0086). Admission
     * is owner-gated ([ADR 0014](0014) §4 — only a group OWNER; the tenant owner has no say); the username is
     * resolved **within the scope's own tenant** (ADR 0020 — never a global search). Records a **grant-less**
     * MEMBER membership — no key crosses the wire; grant-delivery wraps `K_SC` to them afterwards (cr-0076). The
     * returned row has its `email` nulled (the add response never exposes it).
     */
    fun addGroupMember(
        callerId: String, scopeId: String, username: String, sealedNameKey: String?, admission: Admission,
    ): AddGroupMemberOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(AddGroupMemberOutcome.ScopeNotFound, AddGroupMemberOutcome.Suspended, AddGroupMemberOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return AddGroupMemberOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return AddGroupMemberOutcome.NotOwner
        val tenantId = store.scopeTenantId(scopeId) ?: return AddGroupMemberOutcome.ScopeNotFound
        val targetId = store.resolveUsernameInTenant(tenantId, username) ?: return AddGroupMemberOutcome.MemberNotFound
        if (!store.belongsToTenant(targetId, tenantId)) return AddGroupMemberOutcome.MemberNotFound
        if (store.holdsAnyRow(targetId, scopeId)) return AddGroupMemberOutcome.AlreadyMember
        // Consent-first (cr-0096, ADR 0027): if the target asked to be consulted, record a soft `pendingConsent`
        // membership grant-delivery skips — so `K_SC` is gated **before** the wrap — carrying the wrapped name-key
        // `K_name`. The owner must have derived + sealed it (learned consent is required from the prior resolve);
        // no name-key → ConsentRequired.
        if (store.requiresGroupAddConsent(targetId)) {
            if (sealedNameKey == null) return AddGroupMemberOutcome.ConsentRequired
            if (!store.addSoftPendingMember(targetId, tenantId, scopeId, sealedNameKey, callerId, admission)) {
                return AddGroupMemberOutcome.StaleEpoch
            }
        } else if (!store.addGrantlessMember(targetId, tenantId, scopeId, admission)) {
            // Both paths store the admission the owner's client tagged (ADR 0020 move 6) — opaque here, verified by
            // every key-holder before it wraps; refused only when stated at an epoch a rotation already left.
            return AddGroupMemberOutcome.StaleEpoch
        }
        val row = store.scopeMemberRow(scopeId, targetId) ?: return AddGroupMemberOutcome.MemberNotFound
        return AddGroupMemberOutcome.Added(row.copy(email = null))
    }

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
            ResolvedCandidateRow(targetId, username.trim().lowercase(), pubkey, store.requiresGroupAddConsent(targetId)),
        )
    }

    /**
     * Accept a consent proposal (cr-0094): clear the caller's own `pendingConsent` flag, so it becomes an ordinary
     * grant-less membership and grant-delivery wraps `K_SC` to them. Invitee-gated (acts only on the caller's
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
     * caller's own soft membership. Nothing was wrapped, so **no rotation**; no `declined` marker is kept.
     * Idempotent — always succeeds (no open proposal is still a silent no-op, so it is no enumeration oracle).
     */
    fun declineInvitation(callerId: String, scopeId: String) {
        store.deletePendingMembership(callerId, scopeId)
    }

    /** The caller's pending group invitations in the active tenant (cr-0094) — the invitee's Accept/Decline view. */
    fun listInvitations(userId: String, tenantId: String?): ListInvitationsOutcome {
        tenantId ?: return ListInvitationsOutcome.NoTenant
        return ListInvitationsOutcome.Invitations(store.listInvitations(userId, tenantId))
    }

    /**
     * Promote a member to co-owner or demote a co-owner (uc.spaces.manage-group-members, cr-0088). Owner-gated;
     * the **last-owner guard** rejects demoting the last OWNER (ADR 0007). A pure role change — no key moves.
     */
    fun setMemberRole(callerId: String, scopeId: String, targetId: String, role: String): SetRoleOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(SetRoleOutcome.ScopeNotFound, SetRoleOutcome.Suspended, SetRoleOutcome.Deleting)?.let { return it }
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return SetRoleOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return SetRoleOutcome.NotOwner
        val current = store.memberRole(targetId, scopeId) ?: return SetRoleOutcome.MemberNotFound
        if (MembershipPolicy.isLastOwnerDemote(current, role, store.ownerCount(scopeId))) {
            return SetRoleOutcome.LastOwner
        }
        store.setRole(targetId, scopeId, role)
        // Build the response from the role we just set — the same-request read-back can see a stale persistence
        // context (the DB is committed; the guard checks below run in fresh requests against committed data).
        val row = store.scopeMemberRow(scopeId, targetId) ?: return SetRoleOutcome.MemberNotFound
        return SetRoleOutcome.Ok(row.copy(role = role, email = null))
    }

    /**
     * Rescind a pending consent proposal (cr-0094) — the one membership a delete may take without a rotation, since
     * a proposal was never keyed. Any other member leaves only by a removal-rotation ([rotateScope] with `removes`,
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
                store.deletePendingMembership(targetId, scopeId)
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
     * the epoch commit, so a commit that fails after the delete rolls the removal back with it.
     */
    fun rotateScope(
        callerId: String, scopeId: String, epoch: Int, wrappedPrev: String,
        grants: List<RotationGrant>, retags: List<Pair<String, String>>, dropped: Set<String>, removes: String? = null,
    ): RotateOutcome {
        access.check(callerId, scopeId, WRITE)
            .refusal(RotateOutcome.ScopeNotFound, RotateOutcome.Suspended, RotateOutcome.Deleting)?.let { return it }
        if (!store.isScopeOwner(callerId, scopeId)) return RotateOutcome.NotOwner
        if (store.currentEpoch(scopeId) == null) return RotateOutcome.ScopeNotFound
        return when (val d = store.rotate(scopeId, epoch, wrappedPrev, callerId, grants, retags, dropped, removes)) {
            is RotationDecision.Commit -> RotateOutcome.Ok(d.plan.newEpoch)
            RotationDecision.Stale -> RotateOutcome.StaleEpoch
            RotationDecision.AdmissionsIncomplete -> RotateOutcome.AdmissionsIncomplete
            RotationDecision.MemberNotFound -> RotateOutcome.MemberNotFound
            RotationDecision.LastOwner -> RotateOutcome.LastOwner
            RotationDecision.RemovedMemberGranted -> RotateOutcome.RemovedMemberGranted
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
        store.restore(scopeId)
        return RestoreGroupOutcome.Ok
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
