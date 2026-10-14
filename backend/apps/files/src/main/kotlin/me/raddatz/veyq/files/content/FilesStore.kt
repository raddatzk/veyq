package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/** A file's blob as the wire lists it: ids and opaque sizes, the `K_blob` wrap, its sealed meta — never a byte of content. */
data class FileBlobData(
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
    /** The blob's sealed metadata as its upload declared it (opaque; null when none was declared). */
    val wrappedBlobMeta: String?,
)

/** A link guest's display name as core's identity row holds it: sealed client-side, bound to its [shareId] (ADR 0072). */
data class GuestUploaderName(val sealedName: String, val shareId: String)

/**
 * One live file under a parent and its blob (null: no upload of it has completed yet), with who uploaded that blob:
 * [uploaderKey] is `user:{id}` or `share:{identityId}` (ADR 0083 Decision 8), null with no blob; for a `share:` uploader
 * whose identity row exists, [uploaderName] is that guest's sealed name (inbox #431). Only a MEMBER's listing reads and
 * serves either; the guest's item has no such field (`ShareFilesResource`, inbox #190).
 */
data class FileRow(val nodeId: String, val blob: FileBlobData?, val uploaderKey: String?, val uploaderName: GuestUploaderName? = null)

/**
 * Files' reads of core's tables (cr-files-application F1a): a file's blob is the [FilesBlobRoles.FILE_CONTENT] slot of
 * core's [NodeAttachmentEntity]. Files writes nothing here — the one write is core's `AttachmentStore.attach`, reached
 * through [FilesUploadCompletion].
 */
@ApplicationScoped
class FilesStore(private val blobs: BlobUploadStore) {

    /**
     * Every live `FILE` whose parent slot is [parentSlot] (a node id, or the scope id for the root) in [scopeId], with
     * the blob of its slot, ordered by node id. Three reads, each bounded by the one before it: the nodes by scope and
     * parent, the attachments of exactly those nodes, the blobs those attachments name.
     */
    fun listFiles(scopeId: String, parentSlot: String): List<FileRow> {
        val nodeIds = NodeEntity.getEntityManager()
            .createQuery(
                "select n.id from NodeEntity n where n.scopeId = ?1 and n.parentId = ?2 and n.kind = ?3 " +
                    "and n.deletedAt is null",
                String::class.java,
            )
            .setParameter(1, scopeId).setParameter(2, parentSlot).setParameter(3, FilesNodeKinds.FILE)
            .resultList
        // by the text, not the column: a UUID column orders by its own segments
        return withGuestNames(withBlobs(nodeIds.sorted()))
    }

    /**
     * [rows] with each guest uploader's sealed name (inbox #431): ONE read of core's identity rows for the listing, by
     * the identities its rows name. An identity of a deleted link is still there while an attachment names it
     * (`fk_attachment_share_identity`; `SharingStore.dropDeadIdentities` asks core's attachment usage first), so its guest
     * stays named. The member listing's only: the guest's ([listFilesAmong]) never reads a name (inbox #190).
     */
    private fun withGuestNames(rows: List<FileRow>): List<FileRow> {
        val identityIds = rows.mapNotNull { guestIdentityOf(it.uploaderKey) }.distinct()
        if (identityIds.isEmpty()) return rows
        val nameOf = ShareIdentityEntity.list("id in ?1", identityIds).associate { it.id to GuestUploaderName(it.sealedDisplayName, it.shareId) }
        return rows.map { r -> guestIdentityOf(r.uploaderKey)?.let { r.copy(uploaderName = nameOf[it]) } ?: r }
    }

    /** The guest identity id a `share:{identityId}` uploader key names; null for a member's key or none. */
    private fun guestIdentityOf(uploaderKey: String?): String? = uploaderKey?.takeIf { it.startsWith(SHARE_KEY) }?.removePrefix(SHARE_KEY)

    /**
     * Every live `FILE` among [liveNodeIds] — a share's live subtree, as core's guest gate read it — in [scopeId], with
     * the blob of its slot, ordered by node id (`listShareFiles`, F2). The kind and liveness are read again here, so a
     * node trashed since the gate's read is not listed.
     */
    fun listFilesAmong(scopeId: String, liveNodeIds: List<String>): List<FileRow> {
        if (liveNodeIds.isEmpty()) return emptyList()
        val nodeIds = NodeEntity.getEntityManager()
            .createQuery(
                "select n.id from NodeEntity n where n.scopeId = ?1 and n.id in ?2 and n.kind = ?3 " +
                    "and n.deletedAt is null",
                String::class.java,
            )
            .setParameter(1, scopeId).setParameter(2, liveNodeIds).setParameter(3, FilesNodeKinds.FILE)
            .resultList
        return withBlobs(nodeIds.sorted())
    }

    /** [nodeIds] (files, in order) each paired with its slot's blob: two reads bounded by the ids. */
    private fun withBlobs(nodeIds: List<String>): List<FileRow> {
        if (nodeIds.isEmpty()) return emptyList()
        val attachmentOf = NodeAttachmentEntity.list("nodeId in ?1 and slot = ?2", nodeIds, FilesBlobRoles.FILE_CONTENT)
            .associateBy { it.nodeId }
        val blobRows = if (attachmentOf.isEmpty()) emptyMap() else BlobEntity.list("id in ?1", attachmentOf.values.map { it.blobId }).associateBy { it.id }
        return nodeIds.map { id ->
            val attachment = attachmentOf[id]
            val blob = attachment?.blobId?.let(blobRows::get)?.let(::toData)
            FileRow(id, blob, attachment?.uploaderKey?.takeIf { blob != null })
        }
    }

    /** The blob [nodeId]'s file slot holds now, read from the database (null: empty). */
    fun currentBlobId(nodeId: String): String? =
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", nodeId, FilesBlobRoles.FILE_CONTENT).firstResult()?.blobId

    /** The blob [blobId] of [scopeId], as the completion's answer lists it. */
    fun blob(scopeId: String, blobId: String): FileBlobData? = blobs.findBlob(scopeId, blobId)?.let(::toData)

    private fun toData(b: BlobEntity) = FileBlobData(b.id, b.chunkCount, b.byteSize, b.wrappedBlobKey, blobs.decodeManifest(b.manifest), b.wrappedBlobMeta)

    private companion object {
        /** A guest uploader's key prefix (`NodeAttachmentEntity.uploaderKey`). */
        const val SHARE_KEY = "share:"
    }
}
