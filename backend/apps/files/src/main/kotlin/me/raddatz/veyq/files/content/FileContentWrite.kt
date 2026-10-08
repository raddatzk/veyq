package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.content.NodesStore.ApplyResult
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The one write of a file's content (cr-files-application F2): core's [AttachmentStore.attach], joined in ONE
 * transaction with the file's sealed metadata when the caller sends it. A guest has no other way to write a node's
 * payload (core's `createShareNode` takes none, `updateNode` is a member's), so a guest's complete carries it, and it
 * goes with the bytes it describes: written only if the swap happened, never beside a conflict or a refusal.
 *
 * The meta write runs after `attach`, which took the scope lock first ([NodesStore.lockScope]); every meta writer
 * (`NodesStore.applyUpdate`) takes that lock before the node row, so the version read here cannot move before the
 * update — no `stale_meta` is possible, and one would be a broken invariant, not an answer (it throws: the swap
 * rolls back with it). The version is read by a scalar query, not through a managed entity (the stale-context trap
 * of `AttachmentStore`'s KDoc).
 */
@ApplicationScoped
class FileContentWrite(
    private val attachments: AttachmentStore,
    private val nodes: NodesStore,
) {
    @Transactional
    fun attach(
        scopeId: String, nodeId: String, session: UploadSessionEntity, manifest: List<Long>?, expectedBlobId: String?,
        wrappedMeta: String?, admitted: () -> Boolean,
    ): AttachOutcome {
        val outcome = attachments.attach(
            scopeId, nodeId, setOf(FilesNodeKinds.FILE), FilesBlobRoles.FILE_CONTENT, session, manifest, expectedBlobId, admitted,
        )
        if (outcome is AttachOutcome.Attached && wrappedMeta != null) {
            val version = NodeEntity.getEntityManager()
                .createQuery("select n.metaVersion from NodeEntity n where n.id = ?1 and n.deletedAt is null", java.lang.Long::class.java)
                .setParameter(1, nodeId).singleResult.toLong()
            val written = nodes.applyUpdate(scopeId, nodeId, null, null, wrappedMeta, version)
            check(written == ApplyResult.Applied) { "a file's meta under the scope lock was refused: $written" }
        }
        return outcome
    }
}
