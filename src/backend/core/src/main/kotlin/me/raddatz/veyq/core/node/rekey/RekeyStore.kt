package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/** One node as the re-key reads it: live or trashed, with the identity key its entry opens through, if any, and whether
 *  only an owner re-keys it ([OwnerOnlyRekey]). */
data class RekeyNodeRow(val node: NodeEntity, val trashed: Boolean, val wrappedIdentityKey: String?, val ownerOnly: Boolean)

/** A node's core-owned re-key parts ([RekeyStore.parts]). */
data class RekeyParts(
    val blobs: List<Blob>,
    val shares: List<Share>,
    val identities: List<Identity>,
) {
    data class Blob(val blobId: String, val role: String, val wrappedBlobKey: String)
    data class Share(
        val shareId: String, val role: String, val sealedShareKey: String, val sealedShareKeyEpoch: Int?,
        val hasWrap: Boolean, val hasDropPublicKey: Boolean,
    )
    data class Identity(val identityId: String, val shareId: String, val wrappedIdentityKey: String?, val sealedDisplayName: String?)
}

/** What a node's commit carries besides its staged parts ([RekeyStore.commit]). */
data class RekeyCommit(
    val nodeEpoch: Int,
    /** The scope's current epoch as the re-keyer read it: the node's `scopeEpoch` after the commit, which its new seals
     *  bind (ADR 0114 decision 4). */
    val scopeEpoch: Int,
    val parentId: String?,
    val parentEpoch: Int?,
    val placementRevision: Long,
    val metaRevision: Long,
    val encryptedName: String,
    val slugToken: String,
    val wrappedContentKey: String,
    val wrappedMeta: String?,
    val sealedDropPrivateKey: String?,
)

/**
 * The scope re-key's persistence (ADR 0109, uc.spaces.rekey-after-rotation; ADR 0114 decision 4): every node with its
 * own epoch and its due mark, a node's core parts, and the commit of one due node in one transaction. Operator-blind: it
 * rewrites ciphertext in rows it already holds, raises the node's own epoch and clears its mark; no key, no name, no
 * store object.
 */
@ApplicationScoped
class RekeyStore(
    private val nodes: NodesStore,
    private val stage: NodeRekeyStage,
    private val ownerOnly: OwnerOnlyRekey,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All participants: MutableList<NodeRekeyParticipant>,
) {
    /**
     * Every participant in the order its rows are locked and committed: the applications' by part, then core's own (the
     * application blob service, ADR 0112) — an application's writer takes its own rows before the service's, so the
     * service's rows are locked after every application's and before the node's.
     */
    private val participants: List<NodeRekeyParticipant> =
        participants.sortedWith(compareBy({ it.part == NodeRekeyStage.CORE }, { it.part }))

    /** Every node of the scope, live and trashed (ADR 0109 point 1: a trashed node is re-keyed too). A node is trashed
     *  when it or an ancestor was soft-deleted: every node of a trashed subtree carries the stamp ([NodesStore.deleteSubtree]). */
    fun listNodes(scopeId: String): List<RekeyNodeRow> {
        val all = NodeEntity.find("scopeId", scopeId).list()
        val wrapped = nodes.identityKeys(all.mapNotNull { it.shareIdentityId })
        val byId = all.associateBy { it.id }
        return all.map { RekeyNodeRow(it, it.deletedAt != null, it.shareIdentityId?.let(wrapped::get), ownerOnly.of(it, byId::get)) }
    }

    /** The node in [scopeId], live or trashed; null for none. */
    fun node(scopeId: String, nodeId: String): NodeEntity? = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    /** The core fields sealed under [nodeId]'s key or as a wrap of it that are not on its row ([RekeyParts]): the
     *  blobs are the node's attachments', each in its slot — the binding its wrap is sealed to. The blobs its application
     *  stores through core's service are paged by `listApplicationBlobRekey` (ADR 0113: a Memory of many photos holds
     *  thousands) and staged under core's part beside these. */
    fun parts(scopeId: String, nodeId: String): RekeyParts {
        val attachments = NodeAttachmentEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list().map { it.blobId to it.slot }
        val blobs = attachments.mapNotNull { (blobId, role) ->
            BlobEntity.findById(blobId)?.let { RekeyParts.Blob(it.id, role, it.wrappedBlobKey) }
        }
        val shares = rekeyedShares(nodeId)
        val identities = identitiesOf(shares.map { it.id })
        return RekeyParts(
            blobs,
            shares.map {
                RekeyParts.Share(it.id, it.role, it.sealedShareKey!!, it.sealedShareKeyEpoch, it.wrap != null, it.sealedDropPublicKey != null)
            },
            identities.map {
                RekeyParts.Identity(it.id, it.shareId, it.wrappedIdentityKey, it.sealedDisplayName.takeIf { _ -> it.wrappedIdentityKey == null })
            },
        )
    }

    /** The shares rooted on [nodeId] that carry the node's key (`wrap`, READ and READ_WRITE) or its drop public key
     *  (WRITE_ONLY): every link not deleted — a revoked one may be re-activated, so it is re-keyed too. A deleted or
     *  tombstoned link serves nothing. */
    private fun rekeyedShares(nodeId: String): List<ShareEntity> =
        ShareEntity.find("nodeId = ?1 and deleted = false and (wrap is not null or sealedDropPublicKey is not null)", nodeId).list()
            .filter { it.sealedShareKey != null }

    /** `SELECT … FOR UPDATE` on every link rooted on [nodeId], in id order (the order two lockers of several share rows
     *  agree on). */
    private fun lockShares(nodeId: String) {
        ShareEntity.getEntityManager()
            .createQuery("select s from ShareEntity s where s.nodeId = :node order by s.id", ShareEntity::class.java)
            .setParameter("node", nodeId).setLockMode(LockModeType.PESSIMISTIC_WRITE).resultList
    }

    private fun identitiesOf(shareIds: List<String>): List<ShareIdentityEntity> =
        if (shareIds.isEmpty()) emptyList() else ShareIdentityEntity.find("shareId in ?1", shareIds).list()

    /**
     * Commit one node's re-key (ADR 0109 point 2) in ONE transaction: the node gets the key the client minted — its
     * entry under its parent's current key, its meta and drop key re-sealed — and every value staged under [rekeyId]
     * is swapped in: each child's entry, each attachment blob's wrap, each share's wrap and drop public key, each guest
     * identity's key and name, and every participant's rows. The node's own epoch goes up by one, its `scopeEpoch`
     * becomes the scope's current one, and its due mark is cleared (ADR 0114 decision 4) — the one commit both a
     * rotation's and a link close's re-key run.
     *
     * **Lock order: the scope row, the links rooted on the node, each participant's own rows
     * ([NodeRekeyParticipant.lockRows], the applications' before the blob service's), the node row, then the rows it
     * rewrites** (children, blobs, identities) — every
     * writer's order ([NodeEpochGate]: a guest's write takes its share row, an application's writer its own rows, before
     * the node's). Everything is decided before anything is written;
     * a participant's refusal rolls the transaction back.
     *
     * A trashed root's freed slug token (its own id, [NodesStore.deleteSubtree]) is kept, not re-computed: it holds no
     * name and the restore supplies a fresh one; so is a trashed descendant's that an earlier delete freed. The name
     * and meta editors are kept too: a re-seal writes the same plaintext, it is no edit (ADR 0093 § 8).
     */
    @Transactional
    fun commit(scopeId: String, nodeId: String, rekeyId: String, c: RekeyCommit): RekeyOutcome {
        val scope = ScopeEntity.lockRow(scopeId) ?: return RekeyOutcome.NotFound
        // The links rooted on the node before the node itself: a guest's write takes its share row, then the node's
        // (`SharingHandler.createIdentity`, a re-issue), so locking the node first and then the shares it re-wraps would
        // invert that order.
        lockShares(nodeId)
        for (p in participants) p.lockRows(scopeId, nodeId)
        val node = NodeEpochGate.lockNode(nodeId)?.takeIf { it.scopeId == scopeId } ?: return RekeyOutcome.NotFound
        val current = scope.currentEpoch
        // A node is re-keyed only while due (ADR 0114 decision 4): one that is not has nothing left to re-key — another
        // member's commit cleared it — and is a stale read, as a node whose own epoch moved since.
        if (c.scopeEpoch != current || c.nodeEpoch != node.epoch || !node.rekeyDue) return RekeyOutcome.Stale
        // Top-down (ADR 0109 point 2): the entry goes under the parent's CURRENT key, so the parent comes first — a
        // parent still due is pending. The scope root's parent is the scope, whose key the rotation's commit replaced
        // before it marked anything: never due.
        val atRoot = node.parentId == scopeId
        if (atRoot != (c.parentId == null) || (!atRoot && c.parentId != node.parentId)) return RekeyOutcome.InvalidRequest
        if (!atRoot) {
            val parent = NodeEntity.findById(node.parentId) ?: return RekeyOutcome.NotFound
            val stated = c.parentEpoch ?: return RekeyOutcome.InvalidRequest
            when (NodeEpochGate.verdict(stated, parent.epoch, parent.rekeyDue)) {
                NodeEpochGate.Verdict.STALE -> return RekeyOutcome.Stale
                NodeEpochGate.Verdict.PENDING -> return RekeyOutcome.Pending
                else -> Unit
            }
        }
        if (node.placementRevision != c.placementRevision) return RekeyOutcome.StalePlacement
        if (node.metaRevision != c.metaRevision) return RekeyOutcome.StaleMeta(node.wrappedMeta, node.metaRevision)
        // Exactly the payloads the node has: one it lacks is not invented, one it has is not dropped.
        if ((node.wrappedMeta != null) != (c.wrappedMeta != null)) return if (c.wrappedMeta == null) RekeyOutcome.PartsMissing else RekeyOutcome.InvalidRequest
        if ((node.sealedDropPrivateKey != null) != (c.sealedDropPrivateKey != null)) {
            return if (c.sealedDropPrivateKey == null) RekeyOutcome.PartsMissing else RekeyOutcome.InvalidRequest
        }
        val freedSlug = node.slugToken == node.id
        if (!freedSlug && nodes.slugTaken(node.parentId, c.slugToken, exceptNodeId = nodeId)) return RekeyOutcome.SlugTaken

        val core = stage.read(rekeyId, nodeId, NodeRekeyStage.CORE)
        val plan = CorePlan.of(scopeId, nodeId, node, core, rekeyedShares(nodeId)) ?: return RekeyOutcome.PartsMissing
        if (plan.stale) return RekeyOutcome.PartsStale
        // Core's parts first: a participant may copy what they re-sealed (a contributor's name, `MediaNodeRekeyParticipant`).
        // A participant's refusal rolls the whole transaction back, core's writes included.
        plan.apply()
        for (p in participants) {
            when (p.commit(scopeId, nodeId, stage.read(rekeyId, nodeId, p.part))) {
                NodeRekeyParticipant.Verdict.APPLIED -> Unit
                NodeRekeyParticipant.Verdict.MISSING -> { QuarkusTransaction.setRollbackOnly(); return RekeyOutcome.PartsMissing }
                NodeRekeyParticipant.Verdict.STALE -> { QuarkusTransaction.setRollbackOnly(); return RekeyOutcome.PartsStale }
            }
        }
        // The node's own row in ONE statement, every column the commit changes; the copy loaded under the lock is not
        // written back (nothing on it is dirty).
        val sets = mutableListOf(
            "wrappedContentKey = :wrap", "encryptedName = :name", "placementRevision = :placement", "epoch = :epoch",
            "scopeEpoch = :scopeEpoch", "rekeyDue = false",
        )
        val params = mutableMapOf<String, Any>(
            "id" to nodeId, "wrap" to c.wrappedContentKey, "name" to c.encryptedName,
            "placement" to c.placementRevision + 1, "epoch" to node.epoch + 1, "scopeEpoch" to current,
        )
        if (!freedSlug) { sets += "slugToken = :slug"; params["slug"] = c.slugToken }
        c.wrappedMeta?.let { sets += listOf("wrappedMeta = :meta", "metaRevision = :metaRevision"); params["meta"] = it; params["metaRevision"] = c.metaRevision + 1 }
        c.sealedDropPrivateKey?.let { sets += "sealedDropPrivateKey = :drop"; params["drop"] = it }
        NodeEntity.update("${sets.joinToString(", ")} where id = :id", params)
        stage.clear(rekeyId)
        nodes.bumpTree(scopeId)
        return RekeyOutcome.Committed
    }

    /**
     * Core's staged parts checked against the rows they are for, before anything is written: every child's entry
     * (a guest's identity folder excepted — its entry is under the guest's `K_up`, which the re-key keeps), every
     * attachment blob's wrap, every share's wrap (and its drop public key where it carries one), every identity's key
     * or name. Null when a row has no staged value; [stale] when a staged child is no child any more or moved since.
     */
    private class CorePlan(
        private val children: List<Pair<NodeEntity, Map<String, String>>>,
        private val blobs: List<Pair<BlobEntity, String>>,
        private val shares: List<Triple<ShareEntity, String?, String?>>,
        private val identities: List<Pair<ShareIdentityEntity, String>>,
        val stale: Boolean,
    ) {
        fun apply() {
            for ((child, entry) in children) {
                child.encryptedName = entry.getValue(CHILD_NAME)
                if (child.slugToken != child.id) child.slugToken = entry.getValue(CHILD_SLUG)
                child.wrappedContentKey = entry.getValue(CHILD_WRAP)
                child.placementRevision += 1
            }
            for ((blob, wrap) in blobs) blob.wrappedBlobKey = wrap
            for ((share, wrap, dropPublic) in shares) {
                wrap?.let { share.wrap = it }
                dropPublic?.let { share.sealedDropPublicKey = it }
            }
            for ((identity, value) in identities) {
                if (identity.wrappedIdentityKey != null) identity.wrappedIdentityKey = value else identity.sealedDisplayName = value
            }
        }

        companion object {
            fun of(scopeId: String, nodeId: String, node: NodeEntity, staged: Map<Key, Value>, shareRows: List<ShareEntity>): CorePlan? {
                val childRows = NodeEntity.find("scopeId = ?1 and parentId = ?2 and shareIdentityId is null", scopeId, nodeId).list()
                val childIds = childRows.mapTo(HashSet()) { it.id }
                var stale = staged.keys.any { it.field.startsWith("child.") && it.itemId !in childIds }
                val children = childRows.map { child ->
                    val entry = listOf(CHILD_NAME, CHILD_SLUG, CHILD_WRAP).associateWith { f -> staged[Key(child.id, f)] ?: return null }
                    if (entry.values.any { it.revision != child.placementRevision }) stale = true
                    child to entry.mapValues { it.value.value }
                }
                val blobs = NodeAttachmentEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list().mapNotNull { a ->
                    val blob = BlobEntity.findById(a.blobId) ?: return@mapNotNull null
                    blob to (staged[Key(blob.id, BLOB_WRAP)]?.value ?: return null)
                }
                val dropKeyed = node.sealedDropPrivateKey != null
                val shares = shareRows.map { s ->
                    val wrap = if (s.wrap != null) staged[Key(s.id, SHARE_WRAP)]?.value ?: return null else null
                    val dropPublic = if (dropKeyed && s.sealedDropPublicKey != null) staged[Key(s.id, SHARE_DROP)]?.value ?: return null else null
                    Triple(s, wrap, dropPublic)
                }
                val identityRows = if (shareRows.isEmpty()) emptyList()
                else ShareIdentityEntity.find("shareId in ?1", shareRows.map { it.id }).list()
                val identities = identityRows.map { i ->
                    val field = if (i.wrappedIdentityKey != null) IDENTITY_KEY else IDENTITY_NAME
                    i to (staged[Key(i.id, field)]?.value ?: return null)
                }
                return CorePlan(children, blobs, shares, identities, stale)
            }
        }
    }

    companion object {
        const val CHILD_NAME = "child.encryptedName"
        const val CHILD_SLUG = "child.slugToken"
        const val CHILD_WRAP = "child.wrappedContentKey"
        const val BLOB_WRAP = "blob.wrappedBlobKey"
        const val SHARE_WRAP = "share.wrap"
        const val SHARE_DROP = "share.sealedDropPublicKey"
        const val IDENTITY_KEY = "identity.wrappedIdentityKey"
        const val IDENTITY_NAME = "identity.sealedDisplayName"
    }
}
