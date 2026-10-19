package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.generated.model.SharedBlob
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

/**
 * A blob row on the stored object of an existing blob, in place of an upload - the contract's `SharedBlob` (ADR 0013
 * § 5). The client found the file it would upload already stored for the node (it compared a SHA-256 sealed under that
 * blob's own key; the server compares nothing) and asks for a row of its own: [blobId] with its own [wrappedBlobKey]
 * (bound to the new row's owner and role, ADR 0072) and [wrappedBlobMeta].
 */
data class SharedBlobSpec(val sourceBlobId: String, val blobId: String, val wrappedBlobKey: String, val wrappedBlobMeta: String?)

/** The contract's `SharedBlob` (`_common`: a cover's `shared`, a banner's `shared` / `posterShared`, a medium's `originalShared`). */
fun SharedBlob.toSpec(): SharedBlobSpec = SharedBlobSpec(sourceBlobId.toString(), blobId.toString(), wrappedBlobKey, wrappedBlobMeta)

/**
 * Who may name which stored object: the one check every shared write passes before [BlobUploadStore.persistSharedRow].
 *
 * The source must be a blob of the scope that a **live** row **of the same node** holds - asked of every
 * [SharedSubtreeContributor], the arms a share's blob GET is authorized against (a live medium's original and tiers in
 * Memories, an attachment in core), so the predicate is the one `validate_blob_reference_arity.py` already holds to
 * every arm. It must not be blocked (ADR 0093 § 6: a blocked object is not handed out again under a new id), and the
 * new id must be free. A shared object therefore never leaves its node at the moment it is shared, and never its scope.
 *
 * **The source row is read under its row lock**, the lock [BlobUploadStore.deleteBlobRow] takes on every row of an
 * object before it decides whether the object goes: a delete of the source that committed first is seen (no source,
 * refused), and one that comes after waits and then sees the new row (the object stays).
 */
@ApplicationScoped
class SharedBlobs(
    private val blobs: BlobUploadStore,
    private val blocks: ModerationBlocks,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val arms: MutableList<SharedSubtreeContributor>,
) {
    /**
     * Persist [spec] as a new row of [scopeId] on its source's stored object when [nodeId] - a live node the caller
     * resolved under the scope lock - holds the source; null (nothing written) otherwise. Joins the caller's transaction.
     */
    fun persist(scopeId: String, nodeId: String, spec: SharedBlobSpec): String? {
        if (blobs.blobIdTaken(spec.blobId)) return null
        // Every row on the source's object, in the one lock order (StoredObjectLocks) - deleteBlobRow's lock.
        val source = StoredObjectLocks.lockOf(scopeId, listOf(spec.sourceBlobId)).firstOrNull { it.id == spec.sourceBlobId }
            ?: return null
        if (arms.none { it.holds(scopeId, nodeId, source.id) }) return null
        if (blocks.blobBlocked(scopeId, source.id)) return null
        return blobs.persistSharedRow(source, spec)
    }
}
