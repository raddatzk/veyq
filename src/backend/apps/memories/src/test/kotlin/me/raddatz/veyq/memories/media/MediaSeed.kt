package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.memories.testing.MediaBlobRows
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import java.time.Instant
import java.util.UUID

/**
 * Media rows written straight into the tables, many at once — for the tests that need a gallery larger than a page
 * (cr-gallery-list-paging), where an upload per photo through the API would cost minutes. Each photo is an original
 * blob and, by default, a THUMBNAIL and a PREVIEW derivative with blobs of their own, each held by the medium through
 * core's blob service (ADR 0113): the shape the list maps. One
 * transaction, and one bump of the node's item revision for the lot, as a writer's would be.
 */
object MediaSeed {
    fun photos(scopeId: String, nodeId: String, n: Int, derivatives: Boolean = true, trashed: Boolean = false): List<String> {
        val ids = List(n) { UUID.randomUUID().toString() }
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            for (mediaId in ids) {
                val blobId = blob(scopeId)
                val medium = MediaEntity().apply {
                    id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId
                    updatedAt = now
                    if (trashed) {
                        deletedAt = now
                    }
                }.also { it.persist() }
                // Its blobs as core's blob service holds them (ADR 0113): the original and each tier, owned by the medium.
                MediaBlobRows.holdFor(medium, blobId)
                if (derivatives) {
                    for (kind in listOf("THUMBNAIL", "PREVIEW")) MediaBlobRows.holdFor(medium, blob(scopeId), kind)
                }
            }
            // One bump for the lot; its revision is every seeded row's place (ties ordered by id), as a writer stamps it.
            val position = Arc.container().instance(NodeItemsRevisions::class.java).get().bump(scopeId, nodeId)
            MediaEntity.update("position = ?1 where id in ?2", position, ids)
        }
        return ids
    }

    private fun blob(scopeId: String): String {
        val blobId = UUID.randomUUID().toString()
        BlobEntity().apply {
            id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
            chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "d3JhcA=="
        }.persist()
        return blobId
    }
}
