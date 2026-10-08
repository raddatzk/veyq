package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.blob.ChunkFrames
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import io.quarkus.narayana.jta.QuarkusTransaction
import java.util.UUID

/**
 * A blob declaration whose `(chunkCount, byteSize)` follow from the frame size (ADR 0013 §3). Every slice test that
 * declares or seeds a blob takes its shape from here rather than inventing a pair: `start` refuses any `chunkCount`
 * [ChunkFrames] does not derive and any `byteSize` no frame sequence has, and `complete` wants each chunk at exactly
 * its derived length — so a hand-written pair is a test that exercises the refusal instead of what it meant to.
 */
data class TestBlob(val byteSize: Long) {
    val chunkCount: Int = ChunkFrames.chunkCount(byteSize)

    /** The exact sealed length of chunk [index]. */
    fun chunkLength(index: Int): Long = ChunkFrames.chunkLength(byteSize, index)

    /**
     * The item a `…blob-uploads-batch` request carries: the client-minted [blobId], the [ownerId] its wrap is sealed
     * bound to — the media id the completion will carry, or the node for a COVER — and its [role] (ADR 0072), plus
     * the optional sealed [meta] (`wrappedBlobMeta`, left out of the body when null).
     */
    fun declare(
        wrap: String,
        ownerId: String = UUID.randomUUID().toString(),
        role: String = "ORIGINAL",
        blobId: String = UUID.randomUUID().toString(),
        meta: String? = null,
    ): String {
        val metaField = meta?.let { ""","wrappedBlobMeta":"$it"""" } ?: ""
        return """{"blobId":"$blobId","ownerId":"$ownerId","role":"$role","chunkCount":$chunkCount,"byteSize":$byteSize,"wrappedBlobKey":"$wrap"$metaField}"""
    }

    /** The same declaration as the handler sees it — for a test that opens a session through the store directly. */
    fun upload(
        wrap: String,
        ownerId: String = UUID.randomUUID().toString(),
        role: String = "ORIGINAL",
        blobId: String = UUID.randomUUID().toString(),
        meta: String? = null,
    ): UploadDeclare = UploadDeclare(chunkCount, byteSize, wrap, blobId, ownerId, role, meta)

    /** Stage every chunk under [storageKey] at exactly its derived length — the objects an honest upload leaves. */
    fun stage(store: FakePresigningBlobStore, storageKey: String) =
        chunkKeys(storageKey, chunkCount).forEachIndexed { i, key -> store.statOf(key, chunkLength(i)) }

    companion object {
        /** A blob of [plaintextBytes] plaintext, sealed as the web client seals it (at least one frame). */
        fun ofPlaintext(plaintextBytes: Long): TestBlob {
            val frames = maxOf(1L, (plaintextBytes + ChunkFrames.PLAINTEXT_BYTES - 1) / ChunkFrames.PLAINTEXT_BYTES)
            return TestBlob(plaintextBytes + frames * ChunkFrames.OVERHEAD)
        }

        /** A blob of [chunks] frames: every one full, the last carrying a single byte. */
        fun ofChunks(chunks: Int): TestBlob = TestBlob((chunks - 1) * ChunkFrames.SEALED_BYTES + ChunkFrames.OVERHEAD + 1)

        /**
         * The media id an upload was started for (its `ownerId`) — what a completion must name as its `mediaId`
         * (ADR 0072). A test that declared with the default owner reads it back here instead of threading it
         * through; an unknown upload id yields a fresh id, so a completion of a bogus upload still fails on the
         * upload, not on the media id.
         */
        fun ownerOf(uploadId: String): String =
            QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)?.ownerId }
                ?: UUID.randomUUID().toString()

        /** One small frame — the shape of a test that needs *a* blob and does not care which. */
        val SMALL: TestBlob = ofPlaintext(16)
    }
}
