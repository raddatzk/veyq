package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.admin.tenant.VerificationEntity
import me.raddatz.veyq.core.admin.tenant.VerificationKey
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.identity.TenantProfileEntity
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

/** One page of what a rotation moves under the scope key ([ScopeRekeyCommit.parts]): value keys' wraps and root entries. */
data class ScopeRekeyPage(
    val currentEpoch: Int,
    /** A GROUP's name's value key wrap and the name's epoch, on the first page only. */
    val groupName: Pair<String, Int>?,
    /** A TENANT's or GROUP's icon's value key wrap and the icon's epoch, on the first page only. */
    val icon: Pair<String, Int>?,
    val rootNodes: List<NodeEntity>,
    val shares: List<ShareEntity>,
    val members: List<TenantProfileEntity>,
    val verifications: List<VerificationEntity>,
    val more: Boolean,
    val cursor: String?,
)

/** One core value a rotator stages ([ScopeRekeyCommit.stage]): the row it is for, the column, the new wrap (or a root
 *  entry's re-sealed part), a scope-root node's placement revision as read, and the digest of the wrap the rotator opened
 *  ([ScopeRekeyStage.digest]; null for a root's name and slug, held by [revision] instead). */
data class ScopeRekeyField(
    val itemId: String, val field: String, val value: String, val revision: Long? = null, val replacedDigest: String? = null,
)

/**
 * A rotation's scope-level move (ADR 0115 decisions 1–2, owner 2026-11-02): **a rotation re-wraps keys and never
 * rewrites a value.** Every scope-level value that has an author is sealed under a value key `K_v` of its own and only
 * `K_v` is under the scope key, so the commit swaps in each value key's re-wrapped wrap; the one thing under the scope
 * key it re-seals is each scope-root node's entry — any member may write that anyway (ADR 0111 decision 11), and its
 * slug token is a keyed MAC that must move with the key. This class is the listing in pages, the stage, and the swap
 * that `ScopesStore.rotate` runs in the rotation's own transaction. The rows, in the fixed order the listing serves them:
 *
 * | Kind | Row | Column(s) the commit writes | Staged by |
 * |---|---|---|---|
 * | `nodes` | each scope-root node, live and trashed | `wrappedContentKey`, `encryptedName`, `slugToken` (a live one's; a trashed root keeps its freed slug) — re-sealed | core |
 * | (first page) | the GROUP's name, the TENANT's or GROUP's icon | `wrappedNameValueKey`, `wrappedIconValueKey` | core |
 * | `shares` | each link not deleted that holds a recovery copy | `wrappedRecoveryValueKey` | core |
 * | `members` | TENANT only: each profile holding a sealed field | `wrappedNameValueKey`, `wrappedAvatarValueKey`, `wrappedJoinRecordValueKey` | core |
 * | `verifs` | TENANT only: each verification | `wrappedValueKey` | core |
 * | — | each application's rows | its own wraps | its [ScopeRekeyParticipant] |
 *
 * Never a value's ciphertext — a group's `encryptedName`, an icon, a recovery copy, a member's name, picture or join
 * record, a verification — nor its epoch: a stage that would write one is refused ([Verdict.WRITES_VALUE], `422
 * rekey_writes_value`), so a rotator, owner and member alike, can make a value unreadable (a wrap under a key it drops,
 * as any member could junk it) and never rewrite another member's. A pending group invitee's name key is sealed to the
 * invitee, no wrap under the scope key: a rotation leaves it. `scripts/validate_scope_rekey_arity.py` holds both halves:
 * every wrap column is assigned here or by a participant, and no value column is.
 *
 * Operator-blind: it serves wraps it serves members anyway and swaps in ciphertext; it opens nothing and learns which
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
    enum class Verdict { APPLIED, MISSING, STALE, WRITES_VALUE }

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
     * One page of what [scope]'s rotation moves, at most [limit] rows of every kind together, after [since] (null: the
     * first page, which carries the group name's and the icon's wraps). Keyset paging per kind on its id columns, in the
     * database's own order of them, which the cursor's `>` compares by, so pages tile the set.
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
            groupName = scope.wrappedNameValueKey?.takeIf { first && scope.kind == ScopeEntity.GROUP }?.let { it to scope.nameEpoch },
            icon = scope.wrappedIconValueKey?.takeIf { first }?.let { it to scope.iconEpoch },
            rootNodes = out.nodes, shares = out.shares, members = out.members,
            verifications = out.verifications, more = more, cursor = last?.toString(),
        )
    }

    private class Rows {
        val nodes = mutableListOf<NodeEntity>()
        val shares = mutableListOf<ShareEntity>()
        val members = mutableListOf<TenantProfileEntity>()
        val verifications = mutableListOf<VerificationEntity>()

        fun lastId(kind: String): String = when (kind) {
            NODES -> nodes.last().id
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
            SHARES -> page(keyset(ShareEntity, "scopeId = :s and $SHARE_WRAPPED", "id", after, sid, n), into.shares)
            MEMBERS -> if (!tenant) 0 else page(
                keyset(TenantProfileEntity, "tenantId = :s and $PROFILE_WRAPPED", "userId", after, sid, n), into.members,
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
     * was written between the rotator's read and this stage — a write mints a fresh value key and so a fresh wrap — and
     * a re-wrap of the key read before it would replace that write. The commit compares the same digest again
     * ([ScopeRekeyStage.current]).
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
        GROUP_NAME_WRAP -> scope.wrappedNameValueKey.takeIf { itemId == GROUP_ITEM }
        ICON_WRAP -> scope.wrappedIconValueKey.takeIf { itemId == ICON_ITEM }
        SHARE_WRAP -> ShareEntity.findById(itemId)?.takeIf { it.scopeId == scope.id }?.wrappedRecoveryValueKey
        MEMBER_NAME_WRAP, MEMBER_AVATAR_WRAP, MEMBER_JOIN_WRAP ->
            TenantProfileEntity.find("tenantId = ?1 and userId = ?2", scope.id, itemId).firstResult()?.let {
                when (field) {
                    MEMBER_NAME_WRAP -> it.wrappedNameValueKey
                    MEMBER_AVATAR_WRAP -> it.wrappedAvatarValueKey
                    else -> it.wrappedJoinRecordValueKey
                }
            }
        VERIFICATION_WRAP -> itemId.split('|').takeIf { it.size == 2 }
            ?.let { (v, s) -> VerificationEntity.findById(VerificationKey(scope.id, v, s))?.wrappedValueKey }
        else -> null
    }

    // --- the commit ----------------------------------------------------------------------------------------------

    /**
     * Swap every wrap staged under [rekeyId] into [scope]'s rows, re-seal its root entries, and mark every node of the
     * scope due for its own re-key (ADR 0114 decision 4) — or, refusing, write nothing of its own and mark the caller's
     * transaction rollback-only, so the rotation around it is undone too (a removal's deletes included). Joins
     * `ScopesStore.rotate`'s transaction, which holds [scope]'s row lock (locked first, as every writer of these rows
     * takes it) and has applied a removal's deletes, so a removed member's rows are neither required nor written.
     *
     * **Wraps only.** A staged value for any column but a value key's wrap or a root entry's part — a value's own
     * ciphertext, as a stage written before ADR 0115's value keys carried, or a column nobody names — is
     * [Verdict.WRITES_VALUE], decided before anything else: a rotation never rewrites a value.
     *
     * **Fail closed** (decision 2): a row holding a wrap (or a root entry) with no staged replacement is
     * [Verdict.MISSING]; a row whose wrap changed since its replacement was staged ([ScopeRekeyStage.current]), a
     * scope-root node moved since its stated placement revision, or a wrap staged for a column the row does not hold is
     * [Verdict.STALE]. Core decides its own rows first, then each participant decides and writes its own, then core writes.
     *
     * **Lock order:** the scope row (the caller's), the shares it rewrites (the rotation's own rows since the removal),
     * each participant's rows ([ScopeRekeyParticipant.lockRows]), the profile and verification rows, then the node rows
     * — the root entries, then every node's due mark — and last the tree revision, a leaf.
     */
    fun commit(scope: ScopeEntity, rekeyId: String, newEpoch: Int): Verdict {
        check(QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) { "ScopeRekeyCommit.commit joins the rotation's transaction" }
        val scopeId = scope.id
        val staged = stage.read(rekeyId, scopeId, ScopeRekeyStage.CORE)
        if (staged.keys.any { it.field !in STAGED_FIELDS }) return refuse(Verdict.WRITES_VALUE)
        val plan = Plan(staged)
        plan.scope(scope)
        plan.shares(ShareEntity.find("scopeId = ?1 and $SHARE_WRAPPED", scopeId).list())
        for (p in participants) p.lockRows(scopeId)
        if (scope.kind == ScopeEntity.TENANT) {
            plan.members(TenantProfileEntity.find("tenantId = ?1 and $PROFILE_WRAPPED", scopeId).list())
            plan.verifications(VerificationEntity.find("tenantId", scopeId).list())
        }
        plan.roots(NodeEntity.find("scopeId = ?1 and parentId = ?1", scopeId).list())
        plan.verdict?.let { return refuse(it) }
        for (p in participants) {
            when (p.commit(scopeId, rekeyId, newEpoch, stage.read(rekeyId, scopeId, p.part))) {
                ScopeRekeyParticipant.Verdict.APPLIED -> Unit
                ScopeRekeyParticipant.Verdict.MISSING -> return refuse(Verdict.MISSING)
                ScopeRekeyParticipant.Verdict.STALE -> return refuse(Verdict.STALE)
                ScopeRekeyParticipant.Verdict.WRITES_VALUE -> return refuse(Verdict.WRITES_VALUE)
            }
        }
        plan.apply(scope)
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
     * Core's staged wraps checked against its rows, before anything is written: each `require*` records the first
     * refusal in [verdict] and the write it would make in [writes]. Every write is a wrap's or a root entry's.
     */
    private class Plan(private val staged: Map<Key, Value>) {
        var verdict: Verdict? = null
            private set
        private val writes = mutableListOf<() -> Unit>()

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
            val name = take(GROUP_ITEM, GROUP_NAME_WRAP, scope.wrappedNameValueKey.takeIf { scope.kind == ScopeEntity.GROUP })
            val icon = take(ICON_ITEM, ICON_WRAP, scope.wrappedIconValueKey)
            // The same key re-wrapped: the name and the icon, and the editors who wrote them, stay (ADR 0093 § 8).
            writes += {
                name?.let { scope.wrappedNameValueKey = it }
                icon?.let { scope.wrappedIconValueKey = it }
            }
        }

        fun shares(rows: List<ShareEntity>) = rows.forEach { s ->
            val wrap = take(s.id, SHARE_WRAP, s.wrappedRecoveryValueKey) ?: return@forEach
            // The copy and the epochs beside it stay: the one it was written at, the one the link was minted at.
            writes += { s.wrappedRecoveryValueKey = wrap }
        }

        fun members(rows: List<TenantProfileEntity>) = rows.forEach { p ->
            val name = take(p.userId, MEMBER_NAME_WRAP, p.wrappedNameValueKey)
            val avatar = take(p.userId, MEMBER_AVATAR_WRAP, p.wrappedAvatarValueKey)
            val join = take(p.userId, MEMBER_JOIN_WRAP, p.wrappedJoinRecordValueKey)
            writes += {
                name?.let { p.wrappedNameValueKey = it }
                // A new wrap beside the same picture: the revision moves, so a reader's cached pair — its wrap under the
                // key it no longer holds — is fetched again (ADR 0084's ETag).
                avatar?.let { p.wrappedAvatarValueKey = it; p.avatarRevision += 1 }
                join?.let { p.wrappedJoinRecordValueKey = it }
            }
        }

        fun verifications(rows: List<VerificationEntity>) = rows.forEach { v ->
            val wrap = take("${v.verifierUserId}|${v.subjectUserId}", VERIFICATION_WRAP, v.wrappedValueKey) ?: return@forEach
            // The statement and the epoch it was written at stay: what it binds does not move.
            writes += { v.wrappedValueKey = wrap }
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
            // The one entry a rotation re-seals (any member may write it, ADR 0111 decision 11), in its own file.
            writes += { ScopeRootEntries.reseal(n, wrap, name, slug) }
        }

        fun apply(scope: ScopeEntity) {
            check(verdict == null) { "a refused plan writes nothing" }
            writes.forEach { it() }
            scope.persist()
        }
    }

    companion object {
        const val NODES = "nodes"
        const val SHARES = "shares"
        const val MEMBERS = "members"
        const val VERIFS = "verifs"
        /** The listing's kinds, in the order it serves them. */
        val KINDS = listOf(NODES, SHARES, MEMBERS, VERIFS)

        /** The scope row's two wraps are rows of their own in the stage: a row staged again replaces all its fields,
         *  and the name's and the icon's are staged apart (`stageScopeRekey`'s `groupName`, `icon`). */
        const val GROUP_ITEM = "group-name"
        const val ICON_ITEM = "icon"

        // The exempt root entry: re-sealed under the new key (ADR 0111 decision 11, the slug token a keyed MAC).
        const val ROOT_WRAP = "root.wrappedContentKey"
        const val ROOT_NAME = "root.encryptedName"
        const val ROOT_SLUG = "root.slugToken"
        // Value keys' wraps: re-wrapped, the values they open never touched (ADR 0115).
        const val GROUP_NAME_WRAP = "group.wrappedNameValueKey"
        const val ICON_WRAP = "icon.wrappedIconValueKey"
        const val SHARE_WRAP = "share.wrappedRecoveryValueKey"
        const val MEMBER_NAME_WRAP = "member.wrappedNameValueKey"
        const val MEMBER_AVATAR_WRAP = "member.wrappedAvatarValueKey"
        const val MEMBER_JOIN_WRAP = "member.wrappedJoinRecordValueKey"
        const val VERIFICATION_WRAP = "verification.wrappedValueKey"

        /** Every field core's stage may hold — anything else is a write of a value, refused at the commit. */
        val STAGED_FIELDS = setOf(
            ROOT_WRAP, ROOT_NAME, ROOT_SLUG,
            GROUP_NAME_WRAP, ICON_WRAP, SHARE_WRAP, MEMBER_NAME_WRAP, MEMBER_AVATAR_WRAP, MEMBER_JOIN_WRAP, VERIFICATION_WRAP,
        )

        /** A link holding a recovery copy, and so its value key's wrap — the listing's and the commit's one filter. */
        private const val SHARE_WRAPPED = "deleted = false and wrappedRecoveryValueKey is not null"

        /** A profile with a value key under the tenant key — the listing's and the commit's one filter. */
        private const val PROFILE_WRAPPED =
            "(wrappedNameValueKey is not null or wrappedAvatarValueKey is not null or wrappedJoinRecordValueKey is not null)"
    }
}
