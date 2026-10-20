package me.raddatz.veyq.core.blob

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.blob.ChunkFrames.OVERHEAD
import me.raddatz.veyq.core.blob.ChunkFrames.PLAINTEXT_BYTES
import me.raddatz.veyq.core.blob.ChunkFrames.SEALED_BYTES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The backend's frame size is the contract's (`chunk_frame_size` in the crypto vectors), and the three answers
 * [ChunkFrames] gives follow from it. Every boundary below is one byte either side of a frame edge, because that is
 * where an off-by-one in a ceiling division lives.
 */
class ChunkFramesTest {

    @Test
    fun theFrameSizeIsTheContractConstant() {
        // The same drift-proofing CapabilityTagRegistryTest gives the tag registry: re-deriving the size here from
        // anything but the vector file is what this reddens.
        val size = ObjectMapper().readTree(File("../../contracts/crypto/vectors/v1.json"))["chunk_frame_size"]
        assertEquals(size["plaintextBytes"].asLong(), ChunkFrames.PLAINTEXT_BYTES)
        assertEquals(size["nonceBytes"].asLong(), ChunkFrames.NONCE_BYTES)
        assertEquals(size["tagBytes"].asLong(), ChunkFrames.TAG_BYTES)
        assertEquals(262_172L, SEALED_BYTES)
    }

    @Test
    fun theChunkCountIsTheCeilingOverTheSealedFrame() {
        assertEquals(1, ChunkFrames.chunkCount(OVERHEAD)) // the empty blob
        assertEquals(1, ChunkFrames.chunkCount(OVERHEAD + 1))
        assertEquals(1, ChunkFrames.chunkCount(SEALED_BYTES))
        assertEquals(2, ChunkFrames.chunkCount(SEALED_BYTES + OVERHEAD + 1))
        assertEquals(2, ChunkFrames.chunkCount(2 * SEALED_BYTES))
        assertEquals(3, ChunkFrames.chunkCount(2 * SEALED_BYTES + OVERHEAD + 1))
    }

    @Test
    fun everyChunkButTheLastIsFullAndTheLastCarriesTheRemainder() {
        val size = 2 * SEALED_BYTES + OVERHEAD + 5
        assertEquals(SEALED_BYTES, ChunkFrames.chunkLength(size, 0))
        assertEquals(SEALED_BYTES, ChunkFrames.chunkLength(size, 1))
        assertEquals(OVERHEAD + 5, ChunkFrames.chunkLength(size, 2))
        assertEquals(size, (0 until 3).sumOf { ChunkFrames.chunkLength(size, it) })
        assertEquals(OVERHEAD, ChunkFrames.chunkLength(OVERHEAD, 0))
        assertThrows<IllegalArgumentException> { ChunkFrames.chunkLength(size, 3) }
        assertThrows<IllegalArgumentException> { ChunkFrames.chunkLength(size, -1) }
    }

    @Test
    fun aSizeNoFrameSequenceHasIsImpossible() {
        // Under one empty frame: nothing seals to fewer than its overhead.
        assertFalse(ChunkFrames.isPossible(0))
        assertFalse(ChunkFrames.isPossible(-1))
        assertFalse(ChunkFrames.isPossible(OVERHEAD - 1))
        // A last frame of several carrying no plaintext: only an empty blob has an empty frame.
        assertFalse(ChunkFrames.isPossible(SEALED_BYTES + OVERHEAD))
        assertFalse(ChunkFrames.isPossible(SEALED_BYTES + 1))
        // …and the controls one byte past each edge.
        assertTrue(ChunkFrames.isPossible(OVERHEAD))
        assertTrue(ChunkFrames.isPossible(SEALED_BYTES))
        assertTrue(ChunkFrames.isPossible(SEALED_BYTES + OVERHEAD + 1))
        assertThrows<IllegalArgumentException> { ChunkFrames.chunkCount(SEALED_BYTES + OVERHEAD) }
    }

    @Test
    fun aPlaintextOfNBytesSealsToTheSizeThatDerivesItsFrames() {
        // The client side of the same arithmetic (web `chunkify`): n bytes of plaintext in ⌈n / 256 KiB⌉ frames,
        // at least one. Whatever it produces must be a size this unit calls possible, with that frame count.
        for (n in listOf(0L, 1L, PLAINTEXT_BYTES - 1, PLAINTEXT_BYTES, PLAINTEXT_BYTES + 1, 3 * PLAINTEXT_BYTES)) {
            val frames = maxOf(1L, (n + PLAINTEXT_BYTES - 1) / PLAINTEXT_BYTES)
            val sealed = n + frames * OVERHEAD
            assertTrue(ChunkFrames.isPossible(sealed), "n=$n")
            assertEquals(frames.toInt(), ChunkFrames.chunkCount(sealed), "n=$n")
        }
    }
}
