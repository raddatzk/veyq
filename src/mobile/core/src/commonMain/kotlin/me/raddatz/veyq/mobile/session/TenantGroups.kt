package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.AddGroupMemberRequest
import me.raddatz.veyq.mobile.api.models.CreateGroupRequest
import me.raddatz.veyq.mobile.api.models.InviteeNameKey
import me.raddatz.veyq.mobile.api.models.RenameGroupRequest
import me.raddatz.veyq.mobile.api.models.ResolveGroupMemberRequest
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.api.models.SetMemberRoleRequest
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.rotation.OpenedScope
import me.raddatz.veyq.mobile.session.rotation.ScopeRotation
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * The tenant's groups (en.spaces.group-scope; uc.spaces.add-group-member, uc.spaces.consent-group-add,
 * uc.spaces.manage-group-members, uc.spaces.rename-group, uc.spaces.delete-group), the port of the web's `scopes.ts`
 * (`createGroup`, `renameGroup`, `deleteGroup`, `restoreGroup`, `wrapGroupNameKeyForInvitee`, `rotateGroupForRemoval`), the
 * group half of `members.ts`, `GroupMembersPanel.vue`, `CreateGroupDialog.vue` and `GroupInvitationsView.vue`.
 *
 * Operator-blind: a group's key is minted here and reaches the server only sealed to an identity; its name is sealed under
 * a value key of its own ([GroupNames]). A member is added only once its key is anchored in the tenant
 * ([Anchoring.anchorIdentity]); an anomaly sends nothing. A member's removal is the group's removal-rotation
 * ([ScopeRotation.commitRotation] with `removes`), never a plain delete — the server refuses one
 * (`409 removal_requires_rotation`); only a pending consent proposal is rescinded by a delete.
 */
class TenantGroups internal constructor(private val tenant: TenantSession) {
    private val account get() = tenant.account
    private val suite get() = account.suite
    private val tenantId get() = tenant.tenant.tenantId
    private fun api() = ScopesApi(tenant.base, account.http)

    /** One group as Settings' *Groups* lists it: the groups the caller is in, an owner's first. */
    @Serializable
    data class GroupRow(
        val scopeId: String,
        /** The name as it opened here; empty where it does not (the row then says *Untitled group*). */
        val name: String,
        val owner: Boolean,
        /** Deleted, in its grace period: an owner may restore it until then. */
        val deleted: Boolean,
        /** The grant is behind the group's epoch: nothing of it opens until a member online re-grants it. */
        val behind: Boolean,
    ) {
        val titleWord: Msg? = if (name.isEmpty()) Msg.GROUPS_UNTITLED else null
    }

    /** The caller's groups, and whether they may create one (`core.group-space`, ADR 0032). */
    @Serializable
    data class Groups(val rows: List<GroupRow>, val canCreate: Boolean, val said: Msg?)

    /** One member of a group as its page shows them. */
    @Serializable
    data class GroupMember(
        val userId: String,
        val username: String,
        val realName: String?,
        val owner: Boolean,
        /** Behind the group's epoch (ADR 0111 decision 9). */
        val pending: Boolean,
        /** A consent proposal the member has not answered (uc.spaces.consent-group-add). */
        val pendingConsent: Boolean,
        val promote: Boolean,
        val demote: Boolean,
        /** *Remove* — by the group's removal-rotation. */
        val remove: Boolean,
        /** *Withdraw*: a pending proposal, removed by a delete. */
        val rescind: Boolean,
    ) {
        val title: String = realName ?: "@$username"
        val usernameLine: String? = realName?.let { "@$username" }
        val roleWord: Msg = if (owner) Msg.MEMBERS_ROLE_OWNER else Msg.MEMBERS_ROLE_MEMBER
        val statusWord: Msg = if (pendingConsent) Msg.MEMBERS_STATUS_PENDING_CONSENT else Msg.MEMBERS_STATUS_ACTIVE
        val pendingWord: Msg? = if (pending) Msg.MEMBERS_PENDING else null
    }

    /** A group's page: its name, the caller's role, its members, and the owner's acts; [said] in their place. */
    @Serializable
    data class GroupPage(
        val scopeId: String,
        val name: String,
        val owner: Boolean,
        val deleted: Boolean,
        val members: List<GroupMember>,
        val said: Msg?,
    ) {
        /** A member's page is read-only (the web's `groups.readonlyNote`). */
        val readonlyNote: Msg? = if (!owner && said == null) Msg.GROUPS_READONLY_NOTE else null
    }

    /** A pending consent proposal to the caller, its name opened with the caller's own identity. */
    @Serializable
    data class Invitation(val scopeId: String, val name: String?, val addedBy: String) {
        val titleWord: Msg? = if (name == null) Msg.INVITATIONS_UNNAMED_GROUP else null
        val addedByLine: Notice = Notice(Msg.INVITATIONS_ADDED_BY, addedBy)
    }

    @Serializable
    data class Invitations(val rows: List<Invitation>, val said: Msg?)

    /** How an act ended: [done], and what is said ([notice]; null where the page shows it). [scopeId] a created group's. */
    @Serializable
    data class Outcome(val done: Boolean, val notice: Notice? = null, val scopeId: String? = null)

    // ---- the list ----

    /** The caller's groups in this tenant, each name opened here; deleted ones an owner may restore. */
    @Throws(Exception::class)
    suspend fun list(): Groups {
        val canCreate = tenant.capabilityOn(GROUP_SPACE) == true
        val scopes = try {
            val r = account.respond { api().listMyScopes() }
            if (!r.success) return Groups(emptyList(), canCreate, Msg.MEMBERS_LOAD_FAILED)
            r.body().filter { it.kind.value == "GROUP" && it.tenantId == tenantId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Groups(emptyList(), canCreate, Msg.MEMBERS_LOAD_FAILED)
        }
        val rows = scopes.map { s ->
            val behind = s.grantedEpoch < s.currentEpoch
            val name = if (behind) tenant.scopeName(s.id).orEmpty() else nameOf(s)
            GroupRow(s.id, name, s.role == ScopeSummary.Role.OWNER, s.deletedAt != null, behind)
        }.sortedByDescending { it.owner }
        return Groups(rows, canCreate, null)
    }

    /** [s]'s name: the open scope's, else opened from its own grant (a deleted group is not open); empty where none opens. */
    private suspend fun nameOf(s: ScopeSummary): String {
        tenant.scopeKey(s.id)?.let { (key, _) -> return GroupNames.open(suite, s, key).orEmpty() }
        val opened = runCatching { account.openOwnGrant(s, null, false) }.getOrNull() as? GrantOutcome.Opened ?: return ""
        return GroupNames.open(suite, s, opened.key).orEmpty()
    }

    // ---- create, rename, delete, restore ----

    /** *Create group* named [name] (where `core.group-space` is on): a fresh key, the name sealed at name epoch 0, the self-grant. */
    @Throws(Exception::class)
    suspend fun create(name: String): Outcome = attempt(Msg.GROUPS_CREATE_FAILED) {
        val text = name.trim()
        if (text.isEmpty() || tenant.capabilityOn(GROUP_SPACE) != true) return@attempt Outcome(false, Notice(Msg.GROUPS_CREATE_FAILED))
        val key = account.primitives.randomBytes(32)
        try {
            val scopeId = NodeCreate.uuid(account)
            val sealed = GroupNames.seal(suite, scopeId, 0, 0, key, text)
            sealed.valueKey.fill(0)
            val grant = account.sealSelfGrant(key, scopeId, "GROUP", 0)
            val r = account.respond {
                api().createGroup(CreateGroupRequest(scopeId, sealed.encryptedName, sealed.wrappedNameValueKey, grant.wrappedGrant, grant.grantSignature, grant.recipientIdentityDigest))
            }
            if (!r.success) return@attempt Outcome(false, Notice(Msg.GROUPS_CREATE_FAILED))
            // the new group opens with the session's next read of the scopes, as any group another device made
            runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
            Outcome(true, null, r.body().scopeId)
        } finally {
            key.fill(0)
        }
    }

    /**
     * *Rename* [scopeId] to [name] (an owner's): the name sealed under a FRESH value key at the epoch held (forward-secret:
     * a member removed earlier never held it), that key sealed to every pending consent invitee the tenant anchors (an
     * anomaly's cleared, ADR 0111 decision 9). A stale epoch or an outdated invitee list is rebuilt once from a fresh read.
     */
    @Throws(Exception::class)
    suspend fun rename(scopeId: String, name: String): Outcome = attempt(Msg.GROUPS_RENAME_FAILED) {
        val text = name.trim()
        if (text.isEmpty()) return@attempt Outcome(false, Notice(Msg.GROUPS_RENAME_FAILED))
        for (round in 0..1) {
            val (key, epoch) = tenant.scopeKey(scopeId) ?: return@attempt Outcome(false, Notice(Msg.GROUPS_RENAME_FAILED))
            val sealed = GroupNames.seal(suite, scopeId, epoch.toLong(), epoch.toLong(), key, text)
            val r = try {
                val keys = inviteeNameKeys(scopeId, epoch.toLong(), sealed.valueKey) ?: return@attempt Outcome(false, Notice(Msg.GROUPS_RENAME_FAILED))
                account.respond { api().renameGroup(scopeId, RenameGroupRequest(sealed.encryptedName, epoch, sealed.wrappedNameValueKey, keys)) }
            } finally {
                sealed.valueKey.fill(0)
            }
            if (r.success) {
                runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
                return@attempt Outcome(true)
            }
            val code = account.errorCode(r)
            if (round == 0 && r.status == 409 && code in setOf("stale_epoch", "rename_incomplete", "recipient_key_not_current")) {
                runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
                continue
            }
            return@attempt Outcome(false, Notice(if (code == "stale_epoch") Msg.GROUPS_RENAME_STALE else Msg.GROUPS_RENAME_FAILED))
        }
        Outcome(false, Notice(Msg.GROUPS_RENAME_STALE))
    }

    /** The new name's key sealed to each pending consent invitee the tenant anchors; an anomaly's cleared (both null). */
    private suspend fun inviteeNameKeys(scopeId: String, nameEpoch: Long, valueKey: ByteArray): List<InviteeNameKey>? {
        val members = account.members(tenant.base, tenantId, scopeId) ?: return null
        val pending = members.filter { it.pendingConsent == true }
        if (pending.isEmpty()) return emptyList()
        val view = view() ?: return null
        return pending.map { m ->
            if (account.anchoringOfAccount.anchorIdentity(m.userId, ServedIdentity(m.identityPublicKey, m.keyChainHead), view) == Anchor.ANOMALY) {
                InviteeNameKey(m.userId, null, null)
            } else {
                InviteeNameKey(m.userId, GroupNames.wrapToInvitee(suite, scopeId, nameEpoch, valueKey, m.identityPublicKey), b64(account.primitives.sha256(unb64(m.identityPublicKey))))
            }
        }
    }

    /** *Delete group* after its question: the grace period starts; an owner restores it until then. */
    @Throws(Exception::class)
    suspend fun delete(scopeId: String): Outcome = plain(Msg.GROUPS_DELETE_FAILED) { api().deleteGroup(scopeId) }

    /** *Restore* a deleted group in its grace period. */
    @Throws(Exception::class)
    suspend fun restore(scopeId: String): Outcome = plain(Msg.GROUPS_RESTORE_FAILED) { api().restoreGroup(scopeId) }

    // ---- a group's page ----

    /** [scopeId]'s page: its name, the caller's role, every member with the acts an owner has on them. */
    @Throws(Exception::class)
    suspend fun page(scopeId: String): GroupPage {
        val failed = GroupPage(scopeId, "", false, false, emptyList(), Msg.MEMBERS_LOAD_FAILED)
        val listed = runCatching { account.respond { api().listMyScopes() } }.getOrNull()?.takeIf { it.success }?.body()?.firstOrNull { it.id == scopeId } ?: return failed
        val owner = listed.role == ScopeSummary.Role.OWNER
        val name = nameOf(listed)
        val members = runCatching { account.members(tenant.base, tenantId, scopeId) }.getOrNull() ?: return failed.copy(name = name, owner = owner)
        val (tenantKey, tenantEpoch) = tenant.scopeKey(tenantId) ?: (null to 0)
        val joins = JoinRecords(suite)
        val rows = assemble(members, owner, account.userId) { m -> tenantKey?.let { People.realName(joins, tenantId, it, tenantEpoch.toLong(), m) } }
        return GroupPage(scopeId, name, owner, listed.deletedAt != null, rows, if (rows.isEmpty()) Msg.MEMBERS_EMPTY else null)
    }

    /**
     * *Add member* by [username] (an owner's): resolved in the tenant, its key anchored there first (an anomaly sends
     * nothing), the group's current key granted to it; a member who asks before being added is sent a consent proposal
     * carrying the name's key sealed to them (`groups.invitationSent`).
     */
    @Throws(Exception::class)
    suspend fun add(scopeId: String, username: String): Outcome = attempt(Msg.GROUPS_ADD_MEMBER_FAILED) {
        val handle = username.trim().removePrefix("@").lowercase()
        if (handle.isEmpty()) return@attempt Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
        val resolved = account.respond { api().resolveGroupMember(scopeId, ResolveGroupMemberRequest(handle)) }
        if (!resolved.success) return@attempt Outcome(false, Notice(refusal(resolved, Msg.GROUPS_ADD_MEMBER_FAILED)))
        val c = resolved.body()
        val view = view() ?: return@attempt Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
        if (account.anchoringOfAccount.anchorIdentity(c.userId, ServedIdentity(c.identityPublicKey, c.keyChainHead), view) == Anchor.ANOMALY) {
            return@attempt Outcome(false, Notice(Msg.TRUST_ANOMALY_NOTE))
        }
        for (round in 0..1) {
            val (key, epoch) = tenant.scopeKey(scopeId) ?: return@attempt Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
            val summary = tenant.summaryOf(scopeId)
            val sealedNameKey = if (!c.requiresConsent) null else {
                val wrapped = summary?.wrappedNameValueKey ?: return@attempt Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
                val k = GroupNames.valueKey(suite, scopeId, epoch.toLong(), summary.nameEpoch.toLong(), key, wrapped)
                    ?: return@attempt Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
                try { GroupNames.wrapToInvitee(suite, scopeId, summary.nameEpoch.toLong(), k, c.identityPublicKey) } finally { k.fill(0) }
            }
            val g = account.sealGrant(key, scopeId, "GROUP", epoch.toLong(), c.userId, c.identityPublicKey)
            val r = account.respond { api().addGroupMember(scopeId, AddGroupMemberRequest(handle, epoch, g.wrappedGrant, g.grantSignature, g.recipientIdentityDigest, sealedNameKey)) }
            if (r.success) return@attempt Outcome(true, if (c.requiresConsent) Notice(Msg.GROUPS_INVITATION_SENT) else null)
            if (round == 0 && r.status == 409 && account.errorCode(r) == "stale_epoch") {
                runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
                continue
            }
            return@attempt Outcome(false, Notice(refusal(r, Msg.GROUPS_ADD_MEMBER_FAILED)))
        }
        Outcome(false, Notice(Msg.GROUPS_ADD_MEMBER_FAILED))
    }

    /** *Make co-owner* ([owner]) or *Remove co-owner*: no key moves; the last owner's demotion is said as such. */
    @Throws(Exception::class)
    suspend fun setRole(scopeId: String, userId: String, owner: Boolean): Outcome = attempt(Msg.MEMBERS_ROLE_CHANGE_FAILED) {
        val r = account.respond { api().setMemberRole(scopeId, userId, SetMemberRoleRequest(if (owner) SetMemberRoleRequest.Role.OWNER else SetMemberRoleRequest.Role.MEMBER)) }
        when {
            r.success -> Outcome(true)
            r.status == 409 && account.errorCode(r) == "last_owner" -> Outcome(false, Notice(Msg.GROUPS_LAST_OWNER))
            else -> Outcome(false, Notice(Msg.MEMBERS_ROLE_CHANGE_FAILED))
        }
    }

    /**
     * *Remove* [userId] after its question: the group's removal-rotation — a new key granted to everyone but them, the
     * membership deleted with the commit, every link of the group off too where [revokeLinks] (ADR 0075). The session then
     * reopens what moved and finishes the re-keys now due.
     */
    @Throws(Exception::class)
    suspend fun remove(scopeId: String, userId: String, revokeLinks: Boolean): Outcome = attempt(Msg.GROUPS_REMOVE_MEMBER_FAILED) {
        val members = account.members(tenant.base, tenantId, scopeId) ?: return@attempt Outcome(false, Notice(Msg.GROUPS_REMOVE_MEMBER_FAILED))
        if (members.none { it.userId == userId }) return@attempt Outcome(false, Notice(Msg.GROUPS_REMOVE_MEMBER_FAILED))
        val opener: suspend (String) -> OpenedScope? = { id ->
            tenant.scopeKey(id)?.let { (k, e) -> tenant.scopeKind(id)?.let { kind -> OpenedScope(id, tenantId, kind, e.toLong(), k.copyOf()) } }
        }
        ScopeRotation(account, AppKinds.scopeRekeyParticipants, AppKinds::tokenInput).commitRotation(scopeId, members, opener, opener, removes = userId, revokeLinks = revokeLinks)
        runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
        tenant.resumeRekeys()
        Outcome(true)
    }

    /** *Withdraw* a pending consent proposal (a delete: the proposal held the key, so nothing else needs it). */
    @Throws(Exception::class)
    suspend fun rescind(scopeId: String, userId: String): Outcome = plain(Msg.GROUPS_RESCIND_FAILED) { api().removeMember(scopeId, userId) }

    // ---- the caller's invitations ----

    /** The caller's pending consent proposals in this tenant, each name opened with this account's own identity. */
    @Throws(Exception::class)
    suspend fun invitations(): Invitations {
        val r = try {
            account.respond { api().listMyGroupInvitations() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Invitations(emptyList(), Msg.INVITATIONS_LOAD_FAILED)
        }
        if (!r.success) return Invitations(emptyList(), Msg.INVITATIONS_LOAD_FAILED)
        val identity = account.identityCopy()
        val rows = try {
            r.body().map { i ->
                Invitation(i.scopeId, GroupNames.openAsInvitee(suite, identity, i.scopeId, i.nameEpoch.toLong(), i.sealedNameKey, i.encryptedName), addedByName(i.addedBy))
            }
        } finally {
            identity.kemSeed.fill(0)
            identity.signSeed.fill(0)
        }
        return Invitations(rows, if (rows.isEmpty()) Msg.INVITATIONS_EMPTY else null)
    }

    private fun addedByName(userId: String): String = tenant.people[userId]?.username ?: userId

    /** *Accept*: the caller joins the group (the proposal holds its key); the session opens it with its next read. */
    @Throws(Exception::class)
    suspend fun accept(scopeId: String): Outcome = plain(Msg.MEMBERS_ACTION_FAILED) { api().acceptGroupInvitation(scopeId) }
        .also { if (it.done) runCatching { tenant.reopenMoved() }.onFailure { e -> if (e is CancellationException) throw e } }

    /** *Decline*: a silent drop; the group rotates. */
    @Throws(Exception::class)
    suspend fun decline(scopeId: String): Outcome = plain(Msg.MEMBERS_ACTION_FAILED) { api().declineGroupInvitation(scopeId) }

    // ---- helpers ----

    private suspend fun view(): TenantView? {
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return null
        val members = account.members(tenant.base, tenantId, tenantId) ?: return null
        return TenantView.of(tenantId, members, key, epoch.toLong(), suite, account.anchoringOfAccount)
    }

    private suspend fun refusal(r: HttpResponse<*>, fallback: Msg): Msg = when (account.errorCode(r)) {
        "member_not_found" -> Msg.GROUPS_MEMBER_NOT_FOUND
        "already_member" -> Msg.MEMBERS_ALREADY_MEMBER
        "not_scope_owner" -> Msg.MEMBERS_NOT_OWNER
        else -> if (r.status == 404) Msg.GROUPS_MEMBER_NOT_FOUND else fallback
    }

    private suspend fun plain(failed: Msg, call: suspend () -> HttpResponse<*>): Outcome = attempt(failed) {
        val r = account.respond { call() }
        if (r.success) Outcome(true) else Outcome(false, Notice(failed))
    }

    private inline fun attempt(failed: Msg, block: () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, Notice(failed))
    }

    companion object {
        /**
         * A group's rows from its [members] for a caller who is an [owner] there (or not), [me] the caller's id.
         * **Load-bearing:** only an owner's page offers an act; *Remove* (the rotation) never on an owner, on the caller,
         * or on a pending proposal, which is *Withdraw*n instead; a proposal is neither promoted nor demoted.
         */
        fun assemble(members: List<ScopeMember>, owner: Boolean, me: String, name: (ScopeMember) -> String?): List<GroupMember> = members.map { m ->
            val isOwner = m.role == ScopeMember.Role.OWNER
            val consent = m.pendingConsent == true
            GroupMember(
                m.userId, m.username, name(m), isOwner, m.pending, consent,
                promote = owner && !consent && !isOwner, demote = owner && isOwner && !consent,
                remove = owner && !consent && !isOwner && m.userId != me, rescind = owner && consent,
            )
        }

        /** The capability that lets a member create groups (ADR 0032, ADR 0088). */
        const val GROUP_SPACE = "core.group-space"
    }
}
