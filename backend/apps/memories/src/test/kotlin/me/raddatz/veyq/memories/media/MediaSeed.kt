package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.testing.TestBlob
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import java.time.Instant
import java.util.UUID

/**
 * Media rows written straight into the tables, many at once — for the tests that need a gallery larger than a page
 * (cr-gallery-list-paging), where an upload per photo through the API would cost minutes. Each photo is an original
 * blob and, by default, a THUMBNAIL and a PREVIEW derivative with blobs of their own: the shape the list maps. One
 * transaction, and one bump of the node's item revision for the lot, as a writer's would be.
 */
object MediaSeed {
    fun photos(scopeId: String, nodeId: String, n: Int, derivatives: Boolean = true, trashed: Boolean = false): List<String> {
        val ids = List(n) { UUID.randomUUID().toString() }
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            for (mediaId in ids) {
                val blobId = blob(scopeId)
                MediaEntity().apply {
                    id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                    updatedAt = now
                    if (trashed) {
                        deletedAt = now
                    }
                }.persist()
                if (derivatives) {
                    for (kind in listOf("THUMBNAIL", "PREVIEW")) {
                        MediaDerivativeEntity().apply {
                            id = UUID.randomUUID().toString(); this.mediaId = mediaId; this.kind = kind; this.blobId = blob(scopeId)
                        }.persist()
                    }
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
