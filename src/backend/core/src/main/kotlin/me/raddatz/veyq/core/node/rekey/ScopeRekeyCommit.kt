package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.admin.tenant.VerificationEntity
import me.raddatz.veyq.core.admin.tenant.VerificationKey
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage.Value
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import io.quarkus.arc.All
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Status

/** One page of the scope-level rows sealed under the scope key ([ScopeRekeyCommit.parts]). */
data class ScopeRekeyPage(
    val currentEpoch: Int,
    /** A GROUP's sealed name and its epoch, on the first page only. */
    val groupName: Pair<String, Int>?,
    /** A TENANT's or GROUP's sealed icon and its epoch, on the first page only. */
    val icon: Pair<String, Int>?,
    val rootNodes: List<NodeEntity>,
    val invitees: List<MembershipEntity>,
    val shares: List<ShareEntity>,
    val members: List<TenantProfileEntity>,
    val verifications: List<VerificationEntity>,
    val more: Boolean,
    val cursor: String?,
)

/** One core value a rotator stages ([ScopeRekeyCommit.stage]): the row it is for, the column, the ciphertext, a
 *  scope-root node's placement revision as read, and the digest of the ciphertext the rotator opened it from
 *  ([ScopeRekeyStage.digest]; null for a value held by [revision] instead — a root's name and slug — or one that is no
 *  stored ciphertext of its own, an invitee's recipient digest). */
data class ScopeRekeyField(
    val itemId: String, val field: String, val value: String, val revision: Long? = null, val replacedDigest: String? = null,
)

/**
 * A rotation's scope-level re-seal (ADR 0115 decisions 1–2): every row core stores sealed under the scope key — its
 * listing in pages, its stage, and the swap that `ScopesStore.rotate` runs in the rotation's own transaction. The rows,
 * in the fixed order the listing serves them:
 *
 * | Kind | Row | Column(s) | Staged by |
 * |---|---|---|---|
 * | `nodes` | each scope-root node, live and trashed | `wrappedContentKey`, `encryptedName`, `slugToken` (a live one's; a trashed root keeps its freed slug) | core |
 * | (first page) | the GROUP's name, the TENANT's or GROUP's icon | `encryptedName` + `nameEpoch`, `sealedIcon` + `iconEpoch` | core |
 * | `invitees` | each pending consent proposal holding a name key | `sealedNameKey` (sealed to the invitee's identity, or cleared) | core |
 * | `shares` | each link not deleted that holds a recovery copy | `sealedShareKey` + `sealedShareKeyEpoch` | core |
 * | `members` | TENANT only: each profile holding a sealed field | `sealedName`, `sealedAvatar`, `sealedJoinRecord` | core |
 * | `verifs` | TENANT only: each verification | `sealedStatement` + `epoch` | core |
 * | — | each application's rows | its own | its [ScopeRekeyParticipant] |
 *
 * Operator-blind: it serves rows it serves members anyway and swaps in ciphertext; it opens nothing and learns which
 * rows a rotation rewrites — all of them.
 */
@ApplicationScoped
class ScopeRekeyCommit(
    private val stage: ScopeRekeyStage,
    private val nodes: NodesStore,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All participants: MutableList<ScopeRekeyParticipant>,
) {
    /** Every participant, in the order its rows are locked and committed — by part, so two rotations agree. */
    private val participants: List<ScopeRekeyParticipant> = participants.sortedBy { it.part }

    /** The rotation's verdict on the staged values. */
    enum class Verdict { APPLIED, MISSING, STALE, RECIPIENT_KEY_NOT_CURRENT }

    // --- the listing ---------------------------------------------------------------------------------------------

    /** A position in the listing: the kind and the last row's id ("verifier|subject" for a verification). */
    data class Cursor(val kind: String, val id: String) {
        override fun toString() = "$kind,$id"

        companion object {
            /** The cursor [raw] spells, or null when it is none the listing answered (`400 invalid_request`). */
            fun parse(raw: String): Cursor? {
                val kind = raw.substringBefore(',', "")
                val id = raw.substringAfter(',', "")
                return Cursor(kind, id).takeIf { kind in KINDS && id.isNotEmpty() }
            }
        }
    }

    /**
     * One page of [scope]'s rows sealed under its key, at most [limit] rows of every kind together, after [since]
     * (null: the first page, which carries the group name and the icon). Keyset paging per kind on its id columns, in
     * the database's own order of them, which the cursor's `>` compares by, so pages tile the set.
     */
    fun parts(scope: ScopeEntity, since: Cursor?, limit: Int): ScopeRekeyPage {
        val out = Rows()
        var remaining = limit
        var more = false
        var last: Cursor? = null
        val start = since?.let { KINDS.indexOf(it.kind) } ?: 0
        for (i in start until KINDS.size) {
            val kind = KINDS[i]
            val after = since?.takeIf { i == start }?.id
            if (remaining == 0) {
                if (fetch(scope, kind, after, 1, Rows()) > 0) { more = true; break }
                continue
            }
            val got = fetch(scope, kind, after, remaining + 1, out, keep = remaining)
            if (got > remaining) more = true
            val taken = minOf(got, remaining)
            if (taken > 0) last = Cursor(kind, out.lastId(kind))
            remaining -= taken
            if (more) break
        }
        val first = since == null
        return ScopeRekeyPage(
            currentEpoch = scope.currentEpoch,
            groupName = scope.encryptedName?.takeIf { first && scope.kind == ScopeEntity.GROUP }?.let { it to scope.nameEpoch },
            icon = scope.sealedIcon?.takeIf { first }?.let { it to scope.iconEpoch },
            rootNodes = out.nodes, invitees = out.invitees, shares = out.shares, members = out.members,
            verifications = out.verifications, more = more, cursor = last?.toString(),
        )
    }

    private class Rows {
        val nodes = mutableListOf<NodeEntity>()
        val invitees = mutableListOf<MembershipEntity>()
        val shares = mutableListOf<ShareEntity>()
        val members = mutableListOf<TenantProfileEntity>()
        val verifications = mutableListOf<VerificationEntity>()

        fun lastId(kind: String): String = when (kind) {
            NODES -> nodes.last().id
            INVITEES -> invitees.last().userId
            SHARES -> shares.last().id
            MEMBERS -> members.last().userId
            else -> verifications.last().let { "${it.verifierUserId}|${it.subjectUserId}" }
        }
    }

    /** Up to [n] rows of [kind] after [after]; the first [keep] of them go into [into]. Answers how many it read. */
    private fun fetch(scope: ScopeEntity, kind: String, after: String?, n: Int, into: Rows, keep: Int = n): Int {
        val sid = scope.id
        val tenant = scope.kind == ScopeEntity.TENANT
        fun <T> page(rows: List<T>, sink: MutableList<T>): Int { sink += rows.take(keep); return rows.size }
        return when (kind) {
            NODES -> page(keyset(NodeEntity, "scopeId = :s and parentId = :s", "id", after, sid, n), into.nodes)
            INVITEES -> page(
                keyset(MembershipEntity, "scopeId = :s and pendingConsent = true and sealedNameKey is not null", "userId", after, sid, n),
                into.invitees,
            )
            SHARES -> page(keyset(ShareEntity, "scopeId = :s and deleted = false and sealedShareKey is not null", "id", after, sid, n), into.shares)
            MEMBERS -> if (!tenant) 0 else page(
                keyset(TenantProfileEntity, "tenantId = :s and $PROFILE_SEALED", "userId", after, sid, n), into.members,
            )
            else -> if (!tenant) 0 else {
                val params = mutableMapOf<String, Any>("s" to sid)
                var where = "tenantId = :s"
                if (after != null) {
                    val (v, s) = after.split('|').takeIf { it.size == 2 } ?: return 0
                    where += " and (verifierUserId > :v or (verifierUserId = :v and subjectUserId > :sub))"
                    params["v"] = v; params["sub"] = s
                }
                page(VerificationEntity.find(where, Sort.by("verifierUserId").and("subjectUserId"), params).range(0, n - 1).list(), into.verifications)
            }
        }
    }

    private fun <E : PanacheEntityBase> keyset(
        companion: PanacheCompanionBase<E, *>, where: String, idColumn: String,
        after: String?, scopeId: String, n: Int,
    ): List<E> {
        val params = mutableMapOf<String, Any>("s" to scopeId)
        val clause = if (after == null) where else { params["after"] = after; "$where and $idColumn > :after" }
        return companion.find(clause, Sort.by(idColumn), params).range(0, n - 1).list()
    }

    // --- the stage -----------------------------------------------------------------------------------------------

    /**
     * Stage core's [fields] for [scope] under [rekeyId], each beside the digest of what its row holds now — or nothing,
     * answering false, when a field's [ScopeRekeyField.replacedDigest] is not that digest (ADR 0115 decision 2): the row
     * was written between the rotator's read and this stage, and a re-seal of the value read before it would replace
     * that write. The commit compares the same digest again ([ScopeRekeyStage.current]).
     */
    fun stage(rekeyId: String, scope: ScopeEntity, fields: List<ScopeRekeyField>): Boolean {
        val values = fields.associate { f ->
            val now = ScopeRekeyStage.digest(stored(scope, f.itemId, f.field))
            if (f.replacedDigest != null && f.replacedDigest != now) return false
            Key(f.itemId, f.field) to Value(f.value, now, f.revision)
        }
        stage.put(rekeyId, scope.id, ScopeRekeyStage.CORE, values)
        return true
    }

    /** What the row [itemId] of [scope] holds in [field] now, or null (no such row, or nothing in that column). */
    private fun stored(scope: ScopeEntity, itemId: String, field: String): String? = when (field) {
        ROOT_NAME, ROOT_SLUG, ROOT_WRAP -> NodeEntity.findById(itemId)?.takeIf { it.scopeId == scope.id && it.parentId == scope.id }?.let {
            when (field) { ROOT_NAME -> it.encryptedName; ROOT_SLUG -> it.slugToken; else -> it.wrappedContentKey }
        }
        GROUP_NAME -> scope.encryptedName.takeIf { itemId == GROUP_ITEM }
        ICON -> scope.sealedIcon.takeIf { itemId == ICON_ITEM }
        INVITEE_KEY, INVITEE_RECIPIENT -> MembershipEntity.find("scopeId = ?1 and userId = ?2", scope.id, itemId).firstResult()?.sealedNameKey
        SHARE_KEY -> ShareEntity.findById(itemId)?.takeIf { it.scopeId == scope.id }?.sealedShareKey
        MEMBER_NAME, MEMBER_AVATAR, MEMBER_JOIN -> TenantProfileEntity.find("tenantId = ?1 and userId = ?2", scope.id, itemId).firstResult()?.let {
            when (field) { MEMBER_NAME -> it.sealedName; MEMBER_AVATAR -> it.sealedAvatar; else -> it.sealedJoinRecord }
        }
        VERIFICATION -> itemId.split('|').takeIf { it.size == 2 }
            ?.let { (v, s) -> VerificationEntity.findById(VerificationKey(scope.id, v, s))?.sealedStatement }
        else -> null
    }

    // --- the commit ----------------------------------------------------------------------------------------------

    /**
     * Swap every value staged under [rekeyId] into [scope]'s rows sealed under its key, moving each to [newEpoch], and
     * mark every node of the scope due for its own re-key (ADR 0114 decision 4) — or, refusing, write nothing of its
     * own and mark the caller's transaction rollback-only, so the rotation around it is undone too (a removal's deletes
     * included). Joins `ScopesStore.rotate`'s transaction, which holds [scope]'s row lock (locked first, as every
     * writer of these rows takes it) and has applied a removal's deletes, so a removed member's rows are neither
     * required nor written.
     *
     * **Fail closed** (decision 2): a row sealed under the scope key with no staged value is [Verdict.MISSING]; a row
     * whose ciphertext changed since its value was staged ([ScopeRekeyStage.current]), a scope-root node moved since its
     * stated placement revision, or a value staged for a column the row does not hold is [Verdict.STALE]; a pending
     * invitee's name key staged to another identity than the invitee's current one is
     * [Verdict.RECIPIENT_KEY_NOT_CURRENT]. Core decides its own rows first, then each participant decides and writes
     * its own, then core writes.
     *
     * **Lock order:** the scope row (the caller's), the shares and the memberships it rewrites (the rotation's own rows
     * since the removal), each participant's rows ([ScopeRekeyParticipant.lockRows]), the profile and verification rows,
     * then the node rows — the root entries, then every node's due mark — and last the tree revision, a leaf.
     */
    fun commit(scope: ScopeEntity, rekeyId: String, newEpoch: Int): Verdict {
        check(QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) { "ScopeRekeyCommit.commit joins the rotation's transaction" }
        val scopeId = scope.id
        val staged = stage.read(rekeyId, scopeId, ScopeRekeyStage.CORE)
        val plan = Plan(staged)
        plan.scope(scope)
        plan.shares(ShareEntity.find("scopeId = ?1 and deleted = false and sealedShareKey is not null", scopeId).list())
        plan.invitees(MembershipEntity.find("scopeId = ?1 and pendingConsent = true and sealedNameKey is not null", scopeId).list())
        for (p in participants) p.lockRows(scopeId)
        if (scope.kind == ScopeEntity.TENANT) {
            plan.members(TenantProfileEntity.find("tenantId = ?1 and $PROFILE_SEALED", scopeId).list())
            plan.verifications(VerificationEntity.find("tenantId", scopeId).list())
        }
        plan.roots(NodeEntity.find("scopeId = ?1 and parentId = ?1", scopeId).list())
        plan.verdict?.let { return refuse(it) }
        for (p in participants) {
            when (p.commit(scopeId, rekeyId, newEpoch, stage.read(rekeyId, scopeId, p.part))) {
                ScopeRekeyParticipant.Verdict.APPLIED -> Unit
                ScopeRekeyParticipant.Verdict.MISSING -> return refuse(Verdict.MISSING)
                ScopeRekeyParticipant.Verdict.STALE -> return refuse(Verdict.STALE)
            }
        }
        plan.apply(scope, newEpoch)
        // Every node of the scope, live and trashed, is due for its own re-key from here on (ADR 0115 decision 2): a
        // node's key is opened through its parent's re-sealed entry, so any member holding the new grant re-keys it
        // (decision 7) — and until then it takes no write under its key (`NodeEpochGate`).
        NodeEntity.update("rekeyDue = true where scopeId = ?1", scopeId)
        stage.clear(rekeyId, scopeId) // every part's, the participants' included
        nodes.bumpTree(scopeId) // the root entries are new ciphertext in every level view (ADR 0081)
        return Verdict.APPLIED
    }

    private fun refuse(v: Verdict): Verdict {
        QuarkusTransaction.setRollbackOnly()
        return v
    }

    /**
     * Core's staged values checked against its rows, before anything is written: each `require*` records the first
     * refusal in [verdict] and the write it would make in [writes].
     */
    private class Plan(private val staged: Map<Key, Value>) {
        var verdict: Verdict? = null
            private set
        private val writes = mutableListOf<(Int) -> Unit>()

        private fun refuse(v: Verdict) { if (verdict == null) verdict = v }

        /** The staged value for [field] of [itemId], given what the row holds there now ([stored]): null when the row
         *  holds none and none is staged; a refusal when only one of the two is there or the value is stale. */
        private fun take(itemId: String, field: String, stored: String?): String? {
            val v = staged[Key(itemId, field)]
            return when {
                stored == null && v == null -> null
                stored == null -> null.also { refuse(Verdict.STALE) }
                v == null -> null.also { refuse(Verdict.MISSING) }
                !ScopeRekeyStage.current(v, stored) -> null.also { refuse(Verdict.STALE) }
                else -> v.value
            }
        }

        fun scope(scope: ScopeEntity) {
            val name = take(GROUP_ITEM, GROUP_NAME, scope.encryptedName.takeIf { scope.kind == ScopeEntity.GROUP })
            val icon = take(ICON_ITEM, ICON, scope.sealedIcon)
            writes += { epoch ->
                // A re-seal writes the same plaintext: no edit, so the editors stay (ADR 0093 § 8).
                name?.let { scope.encryptedName = it; scope.nameEpoch = epoch }
                icon?.let { scope.sealedIcon = it; scope.iconEpoch = epoch }
            }
        }

        fun shares(rows: List<ShareEntity>) = rows.forEach { s ->
            val copy = take(s.id, SHARE_KEY, s.sealedShareKey) ?: return@forEach
            // The epoch the link was minted at stays beside it (`mintedEpoch`, ADR 0075 point 4).
            writes += { epoch -> s.sealedShareKey = copy; s.sealedShareKeyEpoch = epoch }
        }

        fun invitees(rows: List<MembershipEntity>) = rows.forEach { m ->
            val key = take(m.userId, INVITEE_KEY, m.sealedNameKey) ?: return@forEach
            // A rotator that cannot anchor the invitee's key seals to none and clears it instead (ADR 0111 decision 9):
            // allowed and visible — the proposal stays, shown without the group's name. The digest held it as any value.
            if (key == CLEARED) {
                writes += { _ -> m.sealedNameKey = null }
                return@forEach
            }
            // The new name key goes to the invitee's CURRENT identity only, as every re-grant (ADR 0111 decision 10).
            val digest = staged[Key(m.userId, INVITEE_RECIPIENT)]?.value
            if (!InviteAcceptProof.digestMatches(VeyqUser.findById(m.userId)?.identityPublicKey, digest)) {
                refuse(Verdict.RECIPIENT_KEY_NOT_CURRENT)
                return@forEach
            }
            writes += { _ -> m.sealedNameKey = key }
        }

        fun members(rows: List<TenantProfileEntity>) = rows.forEach { p ->
            val name = take(p.userId, MEMBER_NAME, p.sealedName)
            val avatar = take(p.userId, MEMBER_AVATAR, p.sealedAvatar)
            val join = take(p.userId, MEMBER_JOIN, p.sealedJoinRecord)
            writes += { _ ->
                name?.let { p.sealedName = it }
                // A new envelope of the same picture: the revision moves, so a reader's cached ciphertext — sealed under
                // the key it no longer holds — is fetched again (ADR 0084's ETag).
                avatar?.let { p.sealedAvatar = it; p.avatarRevision += 1 }
                // The join endorsement signs the opened record, not its envelope (decision 8): it stays.
                join?.let { p.sealedJoinRecord = it }
            }
        }

        fun verifications(rows: List<VerificationEntity>) = rows.forEach { v ->
            val statement = take("${v.verifierUserId}|${v.subjectUserId}", VERIFICATION, v.sealedStatement) ?: return@forEach
            writes += { epoch -> v.sealedStatement = statement; v.epoch = epoch }
        }

        fun roots(rows: List<NodeEntity>) = rows.forEach { n ->
            // A trashed root holds its freed slug (its own id, `NodesStore.deleteSubtree`): no name in it, and the
            // restore supplies a fresh token — kept, as the node's own commit keeps it.
            val freed = n.slugToken == n.id
            val wrap = take(n.id, ROOT_WRAP, n.wrappedContentKey)
            val name = take(n.id, ROOT_NAME, n.encryptedName)
            val slug = if (freed) null else take(n.id, ROOT_SLUG, n.slugToken)
            if (listOf(ROOT_WRAP, ROOT_NAME).any { staged[Key(n.id, it)]?.revision != n.placementRevision }) refuse(Verdict.STALE)
            if (wrap == null || name == null || (!freed && slug == null)) return@forEach
            // The re-wrap binds `placementRevision + 1` (the `content-key` and `node-name` seals), as every placement.
            writes += { _ ->
                n.wrappedContentKey = wrap
                n.encryptedName = name
                slug?.let { n.slugToken = it }
                n.placementRevision += 1
            }
        }

        fun apply(scope: ScopeEntity, newEpoch: Int) {
            check(verdict == null) { "a refused plan writes nothing" }
            writes.forEach { it(newEpoch) }
            scope.persist()
        }
    }

    companion object {
        const val NODES = "nodes"
        const val INVITEES = "invitees"
        const val SHARES = "shares"
        const val MEMBERS = "members"
        const val VERIFS = "verifs"
        /** The listing's kinds, in the order it serves them. */
        val KINDS = listOf(NODES, INVITEES, SHARES, MEMBERS, VERIFS)

        /** The scope row's two values are rows of their own in the stage: a row staged again replaces all its fields,
         *  and the name and the icon are staged apart (`stageScopeRekey`'s `groupName`, `icon`). */
        const val GROUP_ITEM = "group-name"
        const val ICON_ITEM = "icon"

        const val ROOT_WRAP = "root.wrappedContentKey"
        const val ROOT_NAME = "root.encryptedName"
        const val ROOT_SLUG = "root.slugToken"
        const val GROUP_NAME = "group.encryptedName"
        const val ICON = "icon.sealedIcon"
        const val INVITEE_KEY = "invitee.sealedNameKey"
        const val INVITEE_RECIPIENT = "invitee.recipientDigest"
        /** The staged [INVITEE_KEY] that clears a pending invitee's name key (`ScopeRekeyInviteeSeal`'s nulls): no
         *  ciphertext is empty, and the stage keeps no null value. */
        const val CLEARED = ""
        const val SHARE_KEY = "share.sealedShareKey"
        const val MEMBER_NAME = "member.sealedName"
        const val MEMBER_AVATAR = "member.sealedAvatar"
        const val MEMBER_JOIN = "member.sealedJoinRecord"
        const val VERIFICATION = "verification.sealedStatement"

        /** A profile with something sealed under the tenant key — the listing's and the commit's one filter. */
        private const val PROFILE_SEALED = "(sealedName is not null or sealedAvatar is not null or sealedJoinRecord is not null)"
    }
}
