package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ApplicationBlobsApi
import me.raddatz.veyq.mobile.api.apis.RekeyApi
import me.raddatz.veyq.mobile.api.models.CommitNodeRekeyRequest
import me.raddatz.veyq.mobile.api.models.NodeRekeyStage
import me.raddatz.veyq.mobile.api.models.RekeyBlobWrap
import me.raddatz.veyq.mobile.api.models.RekeyChildEntry
import me.raddatz.veyq.mobile.api.models.RekeyIdentitySeal
import me.raddatz.veyq.mobile.api.models.RekeyNode
import me.raddatz.veyq.mobile.api.models.RekeyShareWrap
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A scope's re-key, core's (the web's `core/node/rekey/rekeyScope.ts`; ADR 0109, 0111 decision 11, 0114, 0115): after a
 * rotation or a link's close the server marks nodes **due**, and every write sealing under a due node waits until a
 * member's device gives it a fresh key. This one does, top-down, one node per commit: each round lists the scope anew
 * and re-keys the **frontier** — the due nodes whose parent is not due — so an interrupted run leaves a tree that opens as
 * it stands, and any later run (this device's or another member's) continues where the rows say. Nothing is kept between
 * runs; a lost race (a writer took the node's lock, another device committed it) lists again.
 *
 * Per node everything sealed under its OLD key moves to the NEW one, `K_blob`s kept: its own entry under its parent's
 * current key (the scope's one key at the root) at the scope's current epoch and placement revision + 1; each child's
 * entry under the new key — its key re-wrapped, its name re-sealed, its slug token recomputed (its kind's fold,
 * [tokenInput]); its attachment and application blobs; core's cover in its meta; each open link rooted here (a link whose
 * recovery copy opens for no one is named by its digest, and the commit closes it); a link guest's name; the kind's own
 * content through its [ContentRekey]. A drop box's drop key is replaced (the web's `replaceDropKey`): a fresh key pair,
 * its private half under the new key, each WRITE_ONLY link's public half anew under its K_share, each guest's K_up sealed
 * to the new public half; a guest's identity folder keeps its entry under that K_up. Staged in slices of 256, then
 * committed — the server swaps it all in, or nothing.
 *
 * What this client leaves for another device, the node staying due ([Outcome.LEFT]): a node only an owner re-keys where
 * this account is no owner (a drop box, a guest's identity folder and what lies below them are owner-only); a kind it does
 * not re-key ([Kinds] answers null: Memories' media are the web's); and what lies below any of those. Its device's grant
 * behind the scope's current epoch leaves everything.
 */
object NodeRekey {
    /** A kind's own content moved to the node's new key (the web's `rekeyContent`), staged under [RekeyContext.rekeyId] before the commit. */
    fun interface ContentRekey {
        suspend fun rekey(context: RekeyContext)

        companion object {
            /** A kind with nothing of its own under the node's key (the web's `nothingOfItsOwnToRekey`). */
            val NONE = ContentRekey { }
        }
    }

    /** How this client re-keys a node of a kind: its content's hook ([ContentRekey.NONE]: nothing of its own), or null
     *  where it does not re-key that kind (the node stays due for a device that does). */
    fun interface Kinds {
        fun contentOf(kind: String): ContentRekey?
    }

    /** What a [ContentRekey] is handed: the node, both keys, the rekey id its stage goes under. */
    class RekeyContext(val tenant: TenantSession, val scopeId: String, val nodeId: String, val fromEpoch: Int, val rekeyId: String, val oldKey: ByteArray, val newKey: ByteArray)

    enum class Outcome {
        /** Nothing this run wanted lags any more. */
        DONE,
        /** What still lags is not this client's to re-key (see the class): another device will; writes there wait. */
        LEFT,
        /** A refusal or a failure the run could not get past; the marks stay for the next run. */
        FAILED,
    }

    class Result(val outcome: Outcome, val committed: Int)

    /** How this build re-keys a node of each kind and folds its name: what a tenant session pulls and resumes with
     *  ([TenantSession.rekeyRules], set by the app from its composition root of kinds). */
    class Rules(val kinds: Kinds, val tokenInput: (kind: String, name: String) -> String?)

    /**
     * A member's write meeting a lag pulls its own path forward (the web's `pullRekeyPath`, ADR 0111 decision 11): [nodeIds]
     * and their lagging ancestors re-keyed on this device; true once none of them lags. False — the write then waits — where
     * the session has no rules, the path is another's to re-key, or the run failed.
     */
    @Throws(Exception::class)
    suspend fun pull(tenant: TenantSession, scopeId: String, nodeIds: List<String>): Boolean {
        val rules = tenant.rekeyRules ?: return false
        return runCatching {
            rekeyPath(tenant, scopeId, nodeIds, subtree = false, rules.kinds, rules.tokenInput) { NodeCreate.uuid(tenant.account) }.outcome == Outcome.DONE
        }.getOrDefault(false)
    }

    /**
     * Every scope the session was served as re-keying (a rotation's or a close's marks left), re-keyed as far as this device
     * can: the background run any member's device makes (ADR 0114 decision 9 — the closing device starts it, any member
     * finishes it). What came of each scope, by id.
     */
    @Throws(Exception::class)
    suspend fun resume(tenant: TenantSession): Map<String, Outcome> {
        val rules = tenant.rekeyRules ?: return emptyMap()
        return tenant.rekeyingScopes.associateWith { scopeId ->
            runCatching { rekeyScope(tenant, scopeId, rules.kinds, rules.tokenInput) { NodeCreate.uuid(tenant.account) }.outcome }
                .getOrDefault(Outcome.FAILED)
        }
    }

    private const val STAGE_BATCH = 256
    /** Lost races a run absorbs (each lists the scope again) before it gives up; the next run resumes. */
    private const val MAX_CONFLICTS = 8

    /** Every due node of [scopeId] this client re-keys (a member's background run: the scope served as `rekeying`). */
    @Throws(Exception::class)
    suspend fun rekeyScope(
        tenant: TenantSession, scopeId: String, kinds: Kinds, tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
    ): Result = run(tenant, scopeId, kinds, tokenInput, uuid) { rows, _ -> rows.filter { it.due } }

    /**
     * [nodeIds] and their lagging ancestors, top-down — with every node of their subtrees when [subtree] is set (a link's
     * close re-keys its root's subtree, ADR 0114 decision 9) — the web's `rekeyPath`.
     */
    @Throws(Exception::class)
    suspend fun rekeyPath(
        tenant: TenantSession, scopeId: String, nodeIds: List<String>, subtree: Boolean, kinds: Kinds,
        tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
    ): Result = run(tenant, scopeId, kinds, tokenInput, uuid) { rows, byId ->
        val wanted = pathOf(nodeIds, rows, byId, subtree)
        rows.filter { it.id in wanted && it.due }
    }

    /** The ids of [nodeIds], each one's ancestors, and — with [subtree] — each one's descendants. */
    internal fun pathOf(nodeIds: List<String>, rows: List<RekeyNode>, byId: Map<String, RekeyNode>, subtree: Boolean): Set<String> {
        val wanted = HashSet<String>()
        for (id in nodeIds) {
            var cur = byId[id]
            while (cur != null && wanted.add(cur.id)) cur = cur.parentId?.let { byId[it] }
        }
        if (subtree) {
            val below = HashSet(nodeIds)
            var grew = true
            while (grew) {
                grew = false
                for (r in rows) if (r.parentId != null && r.parentId in below && below.add(r.id)) grew = true
            }
            wanted += below
        }
        return wanted
    }

    /** The frontier's rule: a node's parent is re-keyed, or it has none. A parent the listing does not hold counts as due. */
    internal fun parentNotDue(n: RekeyNode, byId: Map<String, RekeyNode>): Boolean = n.parentId == null || byId[n.parentId]?.due == false

    /** Whether this client re-keys [n] at all (see the class); its subtree waits for whoever does. */
    internal fun isMine(n: RekeyNode, owner: Boolean, kinds: Kinds): Boolean =
        (owner || !n.ownerOnly) && kinds.contentOf(n.kind) != null

    private enum class Step { COMMITTED, CONFLICT, LEFT, FAILED }

    /** The driver both runs share: round by round, list the scope and re-key the frontier of what [lagging] picks. */
    private suspend fun run(
        tenant: TenantSession, scopeId: String, kinds: Kinds, tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
        lagging: (List<RekeyNode>, Map<String, RekeyNode>) -> List<RekeyNode>,
    ): Result {
        val account = tenant.account
        val api = RekeyApi(tenant.base, account.http)
        val owner = tenant.isScopeOwner(scopeId)
        val left = HashSet<String>()
        var committed = 0
        var conflicts = 0
        try {
            while (true) {
                val (scopeKey, heldEpoch) = tenant.scopeKey(scopeId) ?: return Result(Outcome.FAILED, committed)
                val list = account.call { api.listRekeyNodes(scopeId) }
                // never seal at an epoch this device does not hold the scope key of
                if (heldEpoch < list.currentEpoch) return Result(Outcome.LEFT, committed)
                val rows = list.nodes
                val byId = rows.associateBy { it.id }
                val wanted = lagging(rows, byId)
                if (wanted.isEmpty()) return Result(Outcome.DONE, committed)
                val frontier = wanted.filter { parentNotDue(it, byId) }
                if (frontier.isEmpty()) return Result(Outcome.FAILED, committed) // a parent lags that is not listed
                val keys = openKeys(account.suite, scopeId, scopeKey, rows)
                val workable = frontier.filter { it.id !in left && isMine(it, owner, kinds) && it.id in keys.node }
                if (workable.isEmpty()) return Result(Outcome.LEFT, committed)
                try {
                    for (node in workable) {
                        when (rekeyNode(tenant, api, scopeId, scopeKey, list.currentEpoch, rows, byId, keys, node, kinds, tokenInput, uuid)) {
                            Step.COMMITTED -> committed++
                            Step.LEFT -> left += node.id
                            Step.FAILED -> return Result(Outcome.FAILED, committed)
                            Step.CONFLICT -> {
                                if (++conflicts > MAX_CONFLICTS) return Result(Outcome.FAILED, committed)
                                break
                            }
                        }
                    }
                } finally {
                    keys.node.values.forEach { it.fill(0) }
                    keys.entry.values.forEach { it.fill(0) }
                }
            }
        } finally {
            tenant.forgetTree()
        }
    }

    private fun at(scopeId: String, nodeId: String, scopeEpoch: Int, revision: Long) =
        arrayOf<Pair<String, Any>>("scopeId" to scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to revision)

    /** Every node's CURRENT key — and the key its entry lives under — opened top-down from the rows as listed: a root under
     *  the scope's one key, every other under its parent's, a guest's identity folder under the guest's `K_up` opened through
     *  its drop box's drop key (`Tree.childEntryKey`). A node whose chain does not open is absent, and so is what lies below. */
    private class Keys(val node: Map<String, ByteArray>, val entry: Map<String, ByteArray>)
    // `entry` holds only the keys opened for it — a guest's K_up —, never a parent's key or the scope's one key, which every
    // round's `fill(0)` would otherwise wipe (the scope key is the session's own: wiped, no root opens any more)

    private fun openKeys(suite: V1, scopeId: String, scopeKey: ByteArray, rows: List<RekeyNode>): Keys {
        val children = rows.groupBy { it.parentId }
        val keys = HashMap<String, ByteArray>()
        val entries = HashMap<String, ByteArray>()
        val queue = ArrayDeque(children[null].orEmpty().map { Triple(it, null as RekeyNode?, scopeKey) })
        while (queue.isNotEmpty()) {
            val (row, parent, parentKey) = queue.removeFirst()
            val pair = runCatching {
                val entryKey = entryKeyOf(suite, scopeId, parent, parentKey, row)
                entryKey to suite.open(Context("content-key", *at(scopeId, row.id, row.scopeEpoch, row.placementRevision)), entryKey, unb64(row.wrappedContentKey))
            }.getOrNull() ?: continue
            if (row.shareIdentityId != null) entries[row.id] = pair.first
            keys[row.id] = pair.second
            children[row.id].orEmpty().forEach { queue.addLast(Triple(it, row, pair.second)) }
        }
        return Keys(keys, entries)
    }

    /** The key [row]'s entry lives under: its parent's key, or a guest's `K_up` for an identity folder (`childEntryKey`). */
    private fun entryKeyOf(suite: V1, scopeId: String, parent: RekeyNode?, parentKey: ByteArray, row: RekeyNode): ByteArray {
        val identity = row.shareIdentityId ?: return parentKey
        val sealedDrop = parent?.sealedDropPrivateKey ?: throw me.raddatz.veyq.mobile.crypto.OpenRefused("content-key")
        val wrapped = row.wrappedIdentityKey ?: throw me.raddatz.veyq.mobile.crypto.OpenRefused("content-key")
        val seed = suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to parent.id), parentKey, unb64(sealedDrop)))
        return suite.openAs(Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to parent.id, "identityId" to identity), suite.xwingKey(seed), unb64(wrapped))
    }

    /** One due node whose parent is not due re-keyed (see the class). The new key lives only for the call. */
    private suspend fun rekeyNode(
        tenant: TenantSession, api: RekeyApi, scopeId: String, scopeKey: ByteArray, current: Int, rows: List<RekeyNode>,
        byId: Map<String, RekeyNode>, opened: Keys, node: RekeyNode, kinds: Kinds,
        tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
    ): Step {
        val account = tenant.account
        val suite = account.suite
        val content = kinds.contentOf(node.kind) ?: return Step.LEFT
        val keys = opened.node
        val parent = node.parentId?.let { byId[it] ?: return Step.FAILED }
        // The key the entry lives under: the parent's current key (its re-key ran in an earlier round), the scope's one key at
        // the root — a guest's identity folder's the guest's K_up, before and after (`entryNew = entryNow`, `rekeyScope.ts`).
        val entryKey = (if (parent == null) scopeKey else opened.entry[node.id] ?: keys[parent.id]) ?: return Step.LEFT
        val oldKey = keys[node.id] ?: return Step.LEFT
        val name = suite.openField(Context("node-name", *at(scopeId, node.id, node.scopeEpoch, node.placementRevision)), entryKey, unb64(node.encryptedName)).decodeToString()
        val fold = tokenInput(node.kind, name) ?: return Step.LEFT
        // children: their unchanged keys under the new one, at their own scope epoch and the next placement revision
        val children = rows.filter { it.parentId == node.id && it.shareIdentityId == null }
        val childFolds = children.map { c ->
            val childName = suite.openField(Context("node-name", *at(scopeId, c.id, c.scopeEpoch, c.placementRevision)), oldKey, unb64(c.encryptedName)).decodeToString()
            Triple(c, childName, tokenInput(c.kind, childName) ?: return Step.LEFT)
        }
        val newKey = suite.p.randomBytes(32)
        val rekeyId = uuid()
        try {
            val parts = account.call { api.getNodeRekeyParts(scopeId, node.id) }
            // A drop box's drop key is REPLACED (the web's `replaceDropKey`): a fresh key pair, its private half under the new
            // key; every WRITE_ONLY link's public half anew under its own K_share; every guest's K_up sealed to the new public
            // half (the guest keeps its K_up and its folder).
            val oldDrop = node.sealedDropPrivateKey?.let {
                suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to node.id), oldKey, unb64(it)))
            }
            if (oldDrop == null && parts.shares.any { it.hasDropPublicKey }) return Step.FAILED
            val freshDrop = oldDrop?.let { suite.generateDropKey() }
            val freshPublic = freshDrop?.let { suite.serializeHybridPublic(suite.dropKeyPublic(it)) }
            val childEntries = childFolds.map { (c, childName, childFold) ->
                val childKey = suite.open(Context("content-key", *at(scopeId, c.id, c.scopeEpoch, c.placementRevision)), oldKey, unb64(c.wrappedContentKey))
                try {
                    val next = at(scopeId, c.id, c.scopeEpoch, c.placementRevision + 1)
                    RekeyChildEntry(
                        nodeId = c.id, placementRevision = c.placementRevision,
                        encryptedName = b64(suite.sealField(Context("node-name", *next), newKey, utf8(childName))),
                        slugToken = b64(suite.slugToken(newKey, node.id, childFold)),
                        wrappedContentKey = b64(suite.seal(Context("content-key", *next), newKey, childKey)),
                    )
                } finally {
                    childKey.fill(0)
                }
            }
            val blobs = parts.blobs.map { RekeyBlobWrap(it.blobId, rewrapBlob(suite, scopeId, node.id, it.blobId, it.role, oldKey, newKey, it.wrappedBlobKey)) } +
                applicationBlobs(tenant, scopeId, node.id, oldKey, newKey)
            val joins = JoinRecords(suite)
            val shares = parts.shares.map { s ->
                val kShare = s.sealedShareKeyEpoch?.let { e ->
                    ShareNames.recoverShareKey(suite, joins, scopeId, node.id, s.shareId, e, scopeKey, current.toLong(), s.wrappedRecoveryValueKey, s.sealedShareKey)
                }
                if (kShare == null) {
                    // the copy opens for no one: named by the digest of the copy as served, and the commit closes that link
                    RekeyShareWrap(s.shareId, lostCopyDigest = b64(suite.p.sha256(utf8(s.sealedShareKey))))
                } else try {
                    RekeyShareWrap(
                        s.shareId,
                        wrap = if (s.hasWrap) b64(suite.seal(Context("share-key"), kShare, newKey)) else null,
                        sealedDropPublicKey = if (s.hasDropPublicKey) b64(suite.seal(Context("drop-public-key"), kShare, freshPublic!!)) else null,
                    )
                } finally {
                    kShare.fill(0)
                }
            }
            val identities = parts.identities.map { i ->
                i.wrappedIdentityKey?.let { wrapped ->
                    val drop = oldDrop ?: throw me.raddatz.veyq.mobile.crypto.OpenRefused("upload-identity-key")
                    val context = Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to node.id, "identityId" to i.identityId)
                    val kUp = suite.openAs(context, suite.xwingKey(drop), unb64(wrapped))
                    try {
                        return@map RekeyIdentitySeal(i.identityId, wrappedIdentityKey = b64(suite.sealTo(context, suite.dropKeyPublic(freshDrop!!), kUp)))
                    } finally {
                        kUp.fill(0)
                    }
                }
                val sealed = i.sealedDisplayName ?: return@map RekeyIdentitySeal(i.identityId)
                val context = Context("share-identity-name", "scopeId" to scopeId, "shareId" to i.shareId, "identityId" to i.identityId)
                val plain = suite.openField(context, oldKey, unb64(sealed))
                RekeyIdentitySeal(i.identityId, sealedDisplayName = b64(suite.sealField(context, newKey, plain)))
            }
            val meta = node.wrappedMeta?.let { NodeMetaWrite.openForWrite(suite, scopeId, node.id, oldKey, it) }
            val wrappedMeta = meta?.let { NodeMetaWrite.seal(suite, scopeId, node.id, newKey, rewrapCover(suite, scopeId, node.id, oldKey, newKey, it)) }
            content.rekey(RekeyContext(tenant, scopeId, node.id, node.epoch, rekeyId, oldKey, newKey))

            val batches = listOf(childEntries.size, blobs.size, shares.size, identities.size).maxOf { (it + STAGE_BATCH - 1) / STAGE_BATCH }
            for (i in 0 until batches) {
                fun <T> slice(l: List<T>) = l.drop(i * STAGE_BATCH).take(STAGE_BATCH)
                val stage = NodeRekeyStage(fromEpoch = node.epoch, children = slice(childEntries), blobs = slice(blobs), shares = slice(shares), identities = slice(identities))
                val r = account.respond { api.stageNodeRekey(scopeId, node.id, rekeyId, stage) }
                if (!r.success) return if (lostRace(r.status, r.response.bodyAsText())) Step.CONFLICT else Step.FAILED
            }
            val sealedAt = at(scopeId, node.id, current, node.placementRevision + 1)
            val commit = CommitNodeRekeyRequest(
                nodeEpoch = node.epoch, scopeEpoch = current,
                placementRevision = node.placementRevision, metaRevision = node.metaRevision,
                encryptedName = b64(suite.sealField(Context("node-name", *sealedAt), entryKey, utf8(name))),
                slugToken = b64(suite.slugToken(entryKey, parent?.id ?: scopeId, fold)),
                wrappedContentKey = b64(suite.seal(Context("content-key", *sealedAt), entryKey, newKey)),
                parentId = parent?.id, parentEpoch = parent?.epoch, wrappedMeta = wrappedMeta,
                sealedDropPrivateKey = freshDrop?.let { b64(suite.seal(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to node.id), newKey, suite.serializeDropKey(it))) },
            )
            val r = account.respond { api.commitNodeRekey(scopeId, node.id, rekeyId, commit) }
            return when {
                r.success -> Step.COMMITTED
                lostRace(r.status, r.response.bodyAsText()) -> Step.CONFLICT
                else -> Step.FAILED
            }
        } finally {
            newKey.fill(0)
        }
    }

    /** The refusals a fresh listing answers: another device moved the node on, or the stage aged. */
    private fun lostRace(status: Int, body: String): Boolean = status == 409 && listOf(
        "stale_epoch", "node_rekey_pending", "rekey_parts_stale", "rekey_parts_missing", "stale_placement", "stale_revision",
    ).any { "\"$it\"" in body }

    private fun rewrapBlob(suite: V1, scopeId: String, ownerId: String, blobId: String, role: String, oldKey: ByteArray, newKey: ByteArray, wrapped: String): String {
        val context = Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to blobId, "role" to role)
        val kBlob = suite.open(context, oldKey, unb64(wrapped))
        try {
            return b64(suite.seal(context, newKey, kBlob))
        } finally {
            kBlob.fill(0)
        }
    }

    /** Every wrap of the node's application blobs, page by page, each opened bound to the owner the server lists it with. */
    private suspend fun applicationBlobs(tenant: TenantSession, scopeId: String, nodeId: String, oldKey: ByteArray, newKey: ByteArray): List<RekeyBlobWrap> {
        val api = ApplicationBlobsApi(tenant.base, tenant.account.http)
        val out = ArrayList<RekeyBlobWrap>()
        var after: String? = null
        while (true) {
            val page = tenant.account.call { api.listApplicationBlobRekey(scopeId, nodeId, after, STAGE_BATCH) }
            page.blobs.forEach { out += RekeyBlobWrap(it.blobId, rewrapBlob(tenant.account.suite, scopeId, it.ownerId, it.blobId, it.role, oldKey, newKey, it.wrappedBlobKey)) }
            if (!page.more || page.cursor == null) return out
            after = page.cursor
        }
    }

    /** Core's cover in the meta (`cover.source.ref` of an `upload`, owner the node, role `COVER`): its wrap moved in place. */
    private fun rewrapCover(suite: V1, scopeId: String, nodeId: String, oldKey: ByteArray, newKey: ByteArray, meta: JsonObject): JsonObject {
        val cover = meta["cover"] as? JsonObject ?: return meta
        val source = cover["source"] as? JsonObject ?: return meta
        if ((source["kind"] as? JsonPrimitive)?.content != "upload") return meta
        val ref = source["ref"] as? JsonObject ?: return meta
        val blobId = (ref["blobId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return meta
        val wrapped = (ref["wrappedBlobKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return meta
        val moved = rewrapBlob(suite, scopeId, nodeId, blobId, "COVER", oldKey, newKey, wrapped)
        val newRef = JsonObject(ref + ("wrappedBlobKey" to JsonPrimitive(moved)))
        val newSource = JsonObject(source + ("ref" to newRef))
        return JsonObject(meta + ("cover" to JsonObject(cover + ("source" to newSource))))
    }
}
