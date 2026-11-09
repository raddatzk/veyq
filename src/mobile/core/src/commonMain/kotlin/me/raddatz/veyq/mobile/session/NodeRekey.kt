package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ApplicationBlobsApi
import me.raddatz.veyq.mobile.api.apis.RekeyApi
import me.raddatz.veyq.mobile.api.models.CommitNodeRekeyRequest
import me.raddatz.veyq.mobile.api.models.NodeRekeyStage
import me.raddatz.veyq.mobile.api.models.RekeyBlobWrap
import me.raddatz.veyq.mobile.api.models.RekeyIdentitySeal
import me.raddatz.veyq.mobile.api.models.RekeyShareWrap
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * One node re-keyed after a link to it closed, core's (the web's `rekeyNode`, `core/node/rekey/rekeyScope.ts`; ADR 0111, 0115):
 * the server marked the link's subtree due, and every write sealing under a due node waits until a member's device gives
 * it a new key. A fresh key and rekey id per attempt; the node's own attachment blobs and its application's blobs moved
 * under it with `K_blob` kept, core's cover in its meta likewise, each open link rooted here wrapped anew (a link whose
 * recovery copy opens for no one is named by its digest, and the commit closes it), a link guest's name sealed anew, the
 * kind's own content through its [ContentRekey]; staged in slices of 256, then committed with the node's name, slug token
 * and key under the key its entry lives under at the scope's current epoch and placement revision + 1.
 *
 * Only what this client fully holds is re-keyed here: a node with no child, no drop key and no drop-box guest, whose
 * parent is not due. Anything else stays due and is said ([Outcome.LEFT]): a member's web device re-keys it — its worker
 * resumes every due scope it holds — and writes there wait until then.
 */
object NodeRekey {
    /** A kind's own content moved to the node's new key (the web's `rekeyContent`), staged under [RekeyContext.rekeyId] before the commit. */
    fun interface ContentRekey {
        suspend fun rekey(context: RekeyContext)
    }

    /** What a [ContentRekey] is handed: the node, both keys, the rekey id its stage goes under. */
    class RekeyContext(val tenant: TenantSession, val scopeId: String, val nodeId: String, val fromEpoch: Int, val rekeyId: String, val oldKey: ByteArray, val newKey: ByteArray)

    enum class Outcome {
        /** The node has a new key; the closed link opens nothing below it any more. */
        REKEYED,
        /** It was not due (nothing to do), or is gone. */
        NOT_DUE,
        /** It is due, but this client does not re-key it (see the class): a member's web device will. */
        LEFT,
        FAILED,
    }

    /** How often a lost race (`stale_epoch`, `rekey_parts_stale`, …) lists the scope again and starts anew. */
    private const val ATTEMPTS = 3
    private const val STAGE_BATCH = 256

    /**
     * Re-keys node [nodeId] of [scopeId] where it is due, its kind's content through [content] (null: a kind with nothing
     * of its own to re-key), its name folded by [tokenInput] as its application folds it, [uuid] the source of rekey ids.
     * The tree is read anew after it.
     */
    @Throws(Exception::class)
    suspend fun rekey(
        tenant: TenantSession, scopeId: String, nodeId: String, content: ContentRekey?, tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
    ): Outcome {
        repeat(ATTEMPTS) {
            val ended = attempt(tenant, scopeId, nodeId, content, tokenInput, uuid)
            if (ended != null) {
                tenant.forgetTree()
                return ended
            }
        }
        tenant.forgetTree()
        return Outcome.FAILED
    }

    /** One attempt: its outcome, or null after a lost race (the next lists the scope anew). */
    private suspend fun attempt(
        tenant: TenantSession, scopeId: String, nodeId: String, content: ContentRekey?, tokenInput: (kind: String, name: String) -> String?, uuid: () -> String,
    ): Outcome? {
        val account = tenant.account
        val suite = account.suite
        val (scopeKey, heldEpoch) = tenant.scopeKey(scopeId) ?: return Outcome.FAILED
        val api = RekeyApi(tenant.base, account.http)
        val list = account.call { api.listRekeyNodes(scopeId) }
        // never seal at an epoch this device does not hold the scope key of
        if (heldEpoch < list.currentEpoch) return Outcome.LEFT
        val node = list.nodes.firstOrNull { it.id == nodeId } ?: return Outcome.NOT_DUE
        if (!node.due) return Outcome.NOT_DUE
        val parent = node.parentId?.let { p -> list.nodes.firstOrNull { it.id == p } ?: return Outcome.LEFT }
        if (parent?.due == true || node.sealedDropPrivateKey != null || list.nodes.any { it.parentId == nodeId }) return Outcome.LEFT
        // The key the entry lives under: the parent's current key (not due, so the tree's), or the scope key at the root.
        val entryKey = if (parent == null) scopeKey else tenant.trees()[scopeId]?.firstOrNull { it.id == parent.id }?.key ?: return Outcome.LEFT
        val at = { scopeEpoch: Int, revision: Long ->
            arrayOf<Pair<String, Any>>("scopeId" to scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to revision)
        }
        val name = suite.openField(Context("node-name", *at(node.scopeEpoch, node.placementRevision)), entryKey, unb64(node.encryptedName)).decodeToString()
        val oldKey = suite.open(Context("content-key", *at(node.scopeEpoch, node.placementRevision)), entryKey, unb64(node.wrappedContentKey))
        val newKey = suite.p.randomBytes(32)
        val rekeyId = uuid()
        try {
            val parts = account.call { api.getNodeRekeyParts(scopeId, nodeId) }
            if (parts.identities.any { it.wrappedIdentityKey != null } || parts.shares.any { it.hasDropPublicKey }) return Outcome.LEFT
            val blobs = parts.blobs.map { RekeyBlobWrap(it.blobId, rewrapBlob(suite, scopeId, nodeId, it.blobId, it.role, oldKey, newKey, it.wrappedBlobKey)) } +
                applicationBlobs(tenant, scopeId, nodeId, oldKey, newKey)
            val joins = JoinRecords(suite)
            val shares = parts.shares.map { s ->
                val kShare = s.sealedShareKeyEpoch?.let { e ->
                    ShareNames.recoverShareKey(suite, joins, scopeId, nodeId, s.shareId, e, scopeKey, heldEpoch.toLong(), s.wrappedRecoveryValueKey, s.sealedShareKey)
                }
                if (kShare == null) {
                    // the copy opens for no one: named by the digest of the copy as served, and the commit closes that link
                    RekeyShareWrap(s.shareId, lostCopyDigest = b64(suite.p.sha256(utf8(s.sealedShareKey))))
                } else try {
                    RekeyShareWrap(s.shareId, wrap = if (s.hasWrap) b64(suite.seal(Context("share-key"), kShare, newKey)) else null)
                } finally {
                    kShare.fill(0)
                }
            }
            val identities = parts.identities.map { i ->
                val sealed = i.sealedDisplayName ?: return@map RekeyIdentitySeal(i.identityId)
                val context = Context("share-identity-name", "scopeId" to scopeId, "shareId" to i.shareId, "identityId" to i.identityId)
                val plain = suite.openField(context, oldKey, unb64(sealed))
                RekeyIdentitySeal(i.identityId, sealedDisplayName = b64(suite.sealField(context, newKey, plain)))
            }
            val meta = node.wrappedMeta?.let { NodeMetaWrite.openForWrite(suite, scopeId, nodeId, oldKey, it) }
            val wrappedMeta = meta?.let { NodeMetaWrite.seal(suite, scopeId, nodeId, newKey, rewrapCover(suite, scopeId, nodeId, oldKey, newKey, it)) }
            content?.rekey(RekeyContext(tenant, scopeId, nodeId, node.epoch, rekeyId, oldKey, newKey))

            val batches = maxOf(1, (blobs.size + STAGE_BATCH - 1) / STAGE_BATCH, (shares.size + STAGE_BATCH - 1) / STAGE_BATCH, (identities.size + STAGE_BATCH - 1) / STAGE_BATCH)
            for (i in 0 until batches) {
                fun <T> slice(l: List<T>) = l.drop(i * STAGE_BATCH).take(STAGE_BATCH)
                val stage = NodeRekeyStage(fromEpoch = node.epoch, children = emptyList(), blobs = slice(blobs), shares = slice(shares), identities = slice(identities))
                if (stage.blobs!!.isEmpty() && stage.shares!!.isEmpty() && stage.identities!!.isEmpty()) continue
                val r = account.respond { api.stageNodeRekey(scopeId, nodeId, rekeyId, stage) }
                if (!r.success) return if (lostRace(r.status, r.response.bodyAsText())) null else Outcome.FAILED
            }
            val sealedAt = at(list.currentEpoch, node.placementRevision + 1)
            val slot = parent?.id ?: scopeId
            val fold = tokenInput(node.kind, name) ?: return Outcome.LEFT
            val commit = CommitNodeRekeyRequest(
                nodeEpoch = node.epoch, scopeEpoch = list.currentEpoch,
                placementRevision = node.placementRevision, metaRevision = node.metaRevision,
                encryptedName = b64(suite.sealField(Context("node-name", *sealedAt), entryKey, utf8(name))),
                slugToken = b64(suite.slugToken(entryKey, slot, fold)),
                wrappedContentKey = b64(suite.seal(Context("content-key", *sealedAt), entryKey, newKey)),
                parentId = parent?.id, parentEpoch = parent?.epoch, wrappedMeta = wrappedMeta,
            )
            val r = account.respond { api.commitNodeRekey(scopeId, nodeId, rekeyId, commit) }
            return when {
                r.success -> Outcome.REKEYED
                lostRace(r.status, r.response.bodyAsText()) -> null
                else -> Outcome.FAILED
            }
        } finally {
            newKey.fill(0)
            oldKey.fill(0)
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
