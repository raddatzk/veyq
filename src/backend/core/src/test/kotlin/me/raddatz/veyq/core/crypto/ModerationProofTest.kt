package me.raddatz.veyq.core.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.HexFormat

/**
 * The backend's `moderation-proof` bytes are the contract's (`moderation_proof` in the crypto vectors), which
 * `packages/crypto` asserts too. The server REBUILDS the statement from the operation it matched, so a drift between
 * the two stacks is every key-proved moderation call refused in production — this reddens first.
 */
class ModerationProofTest {

    private val vectors: JsonNode = ObjectMapper().readTree(File("../../contracts/crypto/vectors/v1.json"))
    private val v = vectors["moderation_proof"]
    private val signPublic = hex(vectors["moderation_key"]["sign_public"])
    private val challenge = hex(v["challenge"])

    @Test
    fun theStatementWithoutACaseIsTheVectorsAndItsSignatureVerifies() = assertCase(v["without_case"])

    @Test
    fun theStatementWithACaseIsTheVectorsAndItsSignatureVerifies() = assertCase(v["with_case"])

    @Test
    fun theProofAnswersForNoOtherChallengeOperationCaseOrKey() {
        val c = v["with_case"]
        val sig = hex(c["signature"])
        val op = c["operationId"].asText()
        val case = c["caseId"].asText()
        val otherChallenge = challenge.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertFalse(ModerationProof.verify(signPublic, ModerationProof.statement(otherChallenge, op, case), sig))
        assertFalse(ModerationProof.verify(signPublic, ModerationProof.statement(challenge, op + "X", case), sig))
        assertFalse(ModerationProof.verify(signPublic, ModerationProof.statement(challenge, op, null), sig))
        assertFalse(ModerationProof.verify(signPublic, ModerationProof.statement(challenge, op, "c0ffee00-0000-4000-8000-0000000000cb"), sig))
        assertFalse(ModerationProof.verify(hex(vectors["grant_signature"]["sign_public"]), ModerationProof.statement(challenge, op, case), sig))
        assertFalse(ModerationProof.verify(null, ModerationProof.statement(challenge, op, case), sig))
        assertFalse(ModerationProof.verify(signPublic, ModerationProof.statement(challenge, op, case), sig.copyOf(63)))
    }

    @Test
    fun anOperationOrCaseThatCannotBeInAStatementBuildsNone() {
        assertNull(ModerationProof.statement(challenge, "ListDeliveries", null))
        assertNull(ModerationProof.statement(challenge, "list-deliveries", null))
        assertNull(ModerationProof.statement(challenge, "", null))
        assertNull(ModerationProof.statement(challenge, "a" + "b".repeat(64), null))
        assertNull(ModerationProof.statement(challenge, "openModerationCase", "C0FFEE00-0000-4000-8000-0000000000CA"))
        assertFalse(ModerationProof.verify(signPublic, null, hex(v["without_case"]["signature"])))
    }

    private fun assertCase(c: JsonNode) {
        val caseId = c["caseId"].takeUnless { it.isNull }?.asText()
        val stmt = ModerationProof.statement(challenge, c["operationId"].asText(), caseId)
        assertArrayEquals(hex(c["statement"]), stmt)
        assertTrue(ModerationProof.verify(signPublic, stmt, hex(c["signature"])))
    }

    private fun hex(n: JsonNode): ByteArray = HexFormat.of().parseHex(n.asText())
}
