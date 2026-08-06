package com.veyq.app.features.content

import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.membership.MembershipStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node row (parentId is the parent slot — node id, or the scope id at the root). */
data class NodeRow(
    val id: String,
    val parentId: String,
    val kind: String,
    val encryptedName: String,
    val slugToken: String,
    val orderIndex: Int,
    val epoch: Int,
    val wrappedContentKey: String?,
    val wrappedMeta: String?,
)

/** What a parent slot resolves to: the scope root, an existing node's kind, or nothing (foreign/missing). */
sealed interface ParentSlot {
    object Root : ParentSlot
    data class Node(val kind: String) : ParentSlot
    object Missing : ParentSlot
}

/**
 * Persistence for the Memory tree (cr-0027). Operator-blind: stores ciphertext + the blinded slug token only.
 * Scope access = the caller holds a membership for the scope (cr-0026 scope identity).
 */
@ApplicationScoped
class NodesStore(
    // cr-0073: node-delete reclaims a node's standalone cover blob (a veyq_blob linked by `cover_blob_id`, no
    // media row). The link lives on the node, so node-delete is where covers are reclaimed — hence the blob
    // store (shared transport) + BlobEntity (media) here, mirroring media's one-way dependency on content.
    private val blobStore: BlobStore,
    private val membership: MembershipStore,
) {

    fun isMember(userId: String, scopeId: String): Boolean = membership.isScopeMember(userId, scopeId)

    fun scopeExists(scopeId: String): Boolean = ScopeEntity.findById(scopeId) != null

    /** Whether the scope is in pending-deletion (cr-0090) — content is **read-only** during the grace window. */
    fun isPendingDeletion(scopeId: String): Boolean = ScopeEntity.findById(scopeId)?.deletedAt != null

    /** Resolve a parent slot within the scope: the scope id (root), a node in the same scope, or missing. */
    fun parentSlot(scopeId: String, parentSlot: String): ParentSlot {
        if (parentSlot == scopeId) return ParentSlot.Root
        val n = NodeEntity.findById(parentSlot) ?: return ParentSlot.Missing
        return if (n.scopeId == scopeId) ParentSlot.Node(n.kind) else ParentSlot.Missing
    }

    fun slugTaken(parentSlot: String, slugToken: String, exceptNodeId: String? = null): Boolean =
        NodeEntity.count("parentId = ?1 and slugToken = ?2 and id <> ?3", parentSlot, slugToken, exceptNodeId ?: "") > 0

    fun findInScope(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    /** Would moving `nodeId` under `newParentSlot` create a cycle (the slot is the node or a descendant)? */
    fun wouldCycle(scopeId: String, nodeId: String, newParentSlot: String): Boolean {
        var cur = newParentSlot
        while (cur != scopeId) {
            if (cur == nodeId) return true
            val p = NodeEntity.findById(cur) ?: return false
            if (p.scopeId != scopeId) return false
            cur = p.parentId
        }
        return false
    }

    @Transactional
    fun create(
        scopeId: String, parentSlot: String, kind: String, encryptedName: String,
        slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String?, wrappedMeta: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        NodeEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.parentId = parentSlot
            this.kind = kind
            this.encryptedName = encryptedName
            this.slugToken = slugToken
            this.orderIndex = orderIndex
            this.epoch = epoch
            this.wrappedContentKey = wrappedContentKey
            this.wrappedMeta = wrappedMeta
            createdAt = Instant.now()
        }.persist()
        return id
    }

    fun list(scopeId: String): List<NodeRow> =
        NodeEntity.find("scopeId", scopeId).list().map {
            NodeRow(it.id, it.parentId, it.kind, it.encryptedName, it.slugToken, it.orderIndex, it.epoch, it.wrappedContentKey, it.wrappedMeta)
        }

    /** Re-load the node **inside** the transaction (the one the handler validated may be detached) and mutate
     *  it — the managed entity is flushed on commit; no `persist()` of a detached instance. */
    @Transactional
    fun applyUpdate(
        nodeId: String, encryptedName: String?, slugToken: String?, newParentSlot: String?, orderIndex: Int?,
        wrappedMeta: String?,
    ) {
        val node = NodeEntity.findById(nodeId) ?: return
        encryptedName?.let { node.encryptedName = it }
        slugToken?.let { node.slugToken = it }
        newParentSlot?.let { node.parentId = it }
        orderIndex?.let { node.orderIndex = it }
        wrappedMeta?.let { node.wrappedMeta = it } // partial: present replaces, omitted/null leaves unchanged
    }

    /** Delete the node + its whole subtree (within the scope). Idempotent. */
    @Transactional
    fun deleteSubtree(scopeId: String, nodeId: String) {
        val all = NodeEntity.find("scopeId", scopeId).list()
        val byParent = all.groupBy { it.parentId }
        val doomed = mutableListOf<String>()
        val stack = ArrayDeque(listOf(nodeId))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            doomed += cur
            byParent[cur]?.forEach { stack.addLast(it.id) }
        }
        if (doomed.isEmpty()) return
        // cr-0073: reclaim the doomed subtree's standalone cover blobs (row + store objects) before the nodes
        // go. The cover_blob_id column is the only reference, so once the node rows are gone the blob would
        // orphan; here the node-delete cascade satisfies the CR's "deleteNode reclaims the cover blob".
        val doomedSet = doomed.toHashSet()
        val coverBlobIds = all.filter { it.id in doomedSet }.mapNotNull { it.coverBlobId }
        if (coverBlobIds.isNotEmpty()) {
            val coverBlobs = BlobEntity.find("id in ?1", coverBlobIds).list()
            BlobEntity.delete("id in ?1", coverBlobIds)
            blobStore.delete(coverBlobs.flatMap { b -> (0 until b.chunkCount).map { chunkKey(b.storageKey, it) } })
        }
        // cr-0107 (ADR 0033): no media_revision/delete_revision bump here. Delete removes the node rows themselves
        // (their counters vanish, no open gallery can poll a deleted node), and it leaves each node's own media
        // rows untouched — so no *surviving* node's gallery set changes. The observable delete-reconcile is the
        // share delete-own path (cr-0102), which does bump both counters; own-node galleries are the whole scope.
        // Defense in depth (cr-0126): scope the primary-key delete so a foreign nodeId can never be removed even
        // if a caller reached here — the seed + traversal already stay in-scope, this closes the raw-PK path.
        NodeEntity.delete("scopeId = ?1 and id in ?2", scopeId, doomed)
    }
}
