package com.veyq.core.blob

/**
 * The shape a blob's size fixes (ADR 0013 §3). A blob is a sequence of `chunk_frame`s, and the frame size is a
 * contract constant — `chunk_frame_size` in `contracts/crypto/vectors/v1.json`, pinned here by
 * `ChunkFramesTest`. Every frame but the last carries exactly [PLAINTEXT_BYTES] of plaintext and is [SEALED_BYTES]
 * on the wire; the last carries 1…[PLAINTEXT_BYTES], and an empty blob is one frame carrying nothing. So a
 * declared sealed `byteSize` is enough to know every chunk's exact length, and a `chunkCount` beside it is either
 * the derived one or a lie.
 *
 * Frame arithmetic lives here and not in `BlobStore`: storage knows keys, not frames. `start`, `complete` and the
 * test fixtures all ask this one unit.
 *
 * The size is a literal once per runtime — here, `@veyq/crypto`'s `CHUNK_FRAME_SIZE`, and the service worker's
 * `range.ts` — each pinned to the vector by its own test. No guard counts the copies: a client that chunked by any
 * other size is refused at `start` (`shape_invalid`), so a drifted copy fails the first upload, loudly.
 */
object ChunkFrames {
    const val PLAINTEXT_BYTES = 262_144L
    const val NONCE_BYTES = 12L
    const val TAG_BYTES = 16L

    /** What sealing adds to a frame: its nonce in front, its tag behind — and so the size of an empty blob. */
    const val OVERHEAD = NONCE_BYTES + TAG_BYTES
    const val SEALED_BYTES = PLAINTEXT_BYTES + OVERHEAD

    /**
     * Whether any frame sequence is [byteSize] bytes long. It is not when it is shorter than one empty frame, or
     * when its last frame of several would carry no plaintext — only an empty blob has an empty frame.
     */
    fun isPossible(byteSize: Long): Boolean {
        if (byteSize < OVERHEAD) return false
        val count = countOf(byteSize)
        return count == 1L || byteSize - (count - 1) * SEALED_BYTES > OVERHEAD
    }

    /** The number of chunks a blob of [byteSize] sealed bytes has. Only defined where [isPossible]. */
    fun chunkCount(byteSize: Long): Int {
        require(isPossible(byteSize)) { "no frame sequence is $byteSize bytes long" }
        return Math.toIntExact(countOf(byteSize))
    }

    /** The exact sealed length of chunk [index] of a blob of [byteSize] bytes: full, except the last. */
    fun chunkLength(byteSize: Long, index: Int): Long {
        val count = chunkCount(byteSize)
        require(index in 0 until count) { "chunk $index of $count" }
        return if (index < count - 1) SEALED_BYTES else byteSize - (count - 1) * SEALED_BYTES
    }

    private fun countOf(byteSize: Long): Long = (byteSize + SEALED_BYTES - 1) / SEALED_BYTES
}
