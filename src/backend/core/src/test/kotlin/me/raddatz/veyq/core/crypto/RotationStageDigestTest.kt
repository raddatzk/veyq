package me.raddatz.veyq.core.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.HexFormat

/**
 * The backend's stage digest is the contract's (`rotation_stage` in the crypto vectors, entries listed out of order so
 * the canonical sort is load-bearing). The server recomputes it over the staged rows and refuses a publish naming
 * another, so a drift is every rotation refused `409 rotation_plan_changed` — this reddens first.
 */
class RotationStageDigestTest {

    private val v: JsonNode = ObjectMapper().readTree(File("../../contracts/crypto/vectors/v1.json"))["rotation_stage"]
    private val entries = v["entries"].map {
        RotationStageDigest.Entry(it["scopeId"].asText(), it["epoch"].asLong(), b64(hex(it["wrapped_grant"])))
    }

    @Test
    fun theDigestIsTheVectorsInAnyInputOrder() {
        assertArrayEquals(hex(v["digest"]), RotationStageDigest.of(entries))
        assertArrayEquals(hex(v["digest"]), RotationStageDigest.of(entries.reversed()))
        assertArrayEquals(hex(v["empty"]), RotationStageDigest.of(emptyList()))
    }

    @Test
    fun eachBoundValueChangesTheDigest() {
        val digest = hex(v["digest"])
        val first = entries[0]
        assertFalse(digest.contentEquals(RotationStageDigest.of(listOf(first.copy(epoch = first.epoch + 1), entries[1]))), "another epoch")
        assertFalse(digest.contentEquals(RotationStageDigest.of(listOf(first.copy(wrappedGrant = "AQ=="), entries[1]))), "another ciphertext")
        assertFalse(digest.contentEquals(RotationStageDigest.of(listOf(first))), "a scope left out")
    }

    @Test
    fun aMalformedOrDoubledEntryBuildsNoDigest() {
        assertNull(RotationStageDigest.of(listOf(entries[0], entries[0])), "one scope twice")
        assertNull(RotationStageDigest.of(listOf(entries[0].copy(scopeId = entries[0].scopeId.uppercase()))), "a non-canonical id")
        assertNull(RotationStageDigest.of(listOf(entries[0].copy(epoch = -1))), "a negative epoch")
        assertNull(RotationStageDigest.of(listOf(entries[0].copy(wrappedGrant = "not base64 !"))), "a ciphertext that is no base64")
    }

    private fun hex(n: JsonNode): ByteArray = HexFormat.of().parseHex(n.asText())
    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
