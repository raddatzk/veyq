package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/** A file's blob as the wire lists it: ids and opaque sizes, the `K_blob` wrap — never a byte of content. */
data class FileBlobData(
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

/** One live file under a parent and its blob (null: no upload of it has completed yet). */
data class FileRow(val nodeId: String, val blob: FileBlobData?)

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
                    "and n.deletedAt is null order by n.id",
                String::class.java,
            )
            .setParameter(1, scopeId).setParameter(2, parentSlot).setParameter(3, FilesNodeKinds.FILE)
            .resultList
        return withBlobs(nodeIds)
    }

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
                    "and n.deletedAt is null order by n.id",
                String::class.java,
            )
            .setParameter(1, scopeId).setParameter(2, liveNodeIds).setParameter(3, FilesNodeKinds.FILE)
            .resultList
        return withBlobs(nodeIds)
    }

    /** [nodeIds] (files, in order) each paired with its slot's blob: two reads bounded by the ids. */
    private fun withBlobs(nodeIds: List<String>): List<FileRow> {
        if (nodeIds.isEmpty()) return emptyList()
        val blobOf = NodeAttachmentEntity.list("nodeId in ?1 and slot = ?2", nodeIds, FilesBlobRoles.FILE_CONTENT)
            .associate { it.nodeId to it.blobId }
        val blobRows = if (blobOf.isEmpty()) emptyMap() else BlobEntity.list("id in ?1", blobOf.values.toList()).associateBy { it.id }
        return nodeIds.map { id -> FileRow(id, blobOf[id]?.let(blobRows::get)?.let(::toData)) }
    }

    /** The blob [nodeId]'s file slot holds now, read from the database (null: empty). */
    fun currentBlobId(nodeId: String): String? =
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", nodeId, FilesBlobRoles.FILE_CONTENT).firstResult()?.blobId

    /** The blob [blobId] of [scopeId], as the completion's answer lists it. */
    fun blob(scopeId: String, blobId: String): FileBlobData? = blobs.findBlob(scopeId, blobId)?.let(::toData)

    private fun toData(b: BlobEntity) = FileBlobData(b.id, b.chunkCount, b.byteSize, b.wrappedBlobKey, blobs.decodeManifest(b.manifest))
}
