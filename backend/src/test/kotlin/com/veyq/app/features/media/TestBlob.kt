package com.veyq.app.features.media

import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.blobstore.FakePresigningBlobStore

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

    /** The item a `…blob-uploads-batch` request carries. */
    fun declare(wrap: String): String = """{"chunkCount":$chunkCount,"byteSize":$byteSize,"wrappedBlobKey":"$wrap"}"""

    /** Stage every chunk under [storageKey] at exactly its derived length — the objects an honest upload leaves. */
    fun stage(store: FakePresigningBlobStore, storageKey: String) =
        (0 until chunkCount).forEach { store.statOf(chunkKey(storageKey, it), chunkLength(it)) }

    companion object {
        /** A blob of [plaintextBytes] plaintext, sealed as the web client seals it (at least one frame). */
        fun ofPlaintext(plaintextBytes: Long): TestBlob {
            val frames = maxOf(1L, (plaintextBytes + ChunkFrames.PLAINTEXT_BYTES - 1) / ChunkFrames.PLAINTEXT_BYTES)
            return TestBlob(plaintextBytes + frames * ChunkFrames.OVERHEAD)
        }

        /** A blob of [chunks] frames: every one full, the last carrying a single byte. */
        fun ofChunks(chunks: Int): TestBlob = TestBlob((chunks - 1) * ChunkFrames.SEALED_BYTES + ChunkFrames.OVERHEAD + 1)

        /** One small frame — the shape of a test that needs *a* blob and does not care which. */
        val SMALL: TestBlob = ofPlaintext(16)
    }
}
