package me.raddatz.veyq.core.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.HexFormat

/**
 * The backend's change-email attestation bytes are the contract's (`auth_attestation` in the crypto vectors), which
 * `packages/crypto` asserts too. The server rebuilds the statement from the request and the caller, so a drift
 * between the stacks is every address change refused in production — this reddens first.
 */
class AuthAttestationTest {

    private val a: JsonNode =
        ObjectMapper().readTree(File("../../contracts/crypto/vectors/v1.json"))["auth_attestation"]["change_email"]

    private fun build(userId: String = a["user_id"].asText(), email: String = a["email_utf8"].asText()) =
        AuthAttestation.emailChangeStatement(userId, email, b64(a["auth_public_key"]))

    @Test
    fun theStatementIsTheVectorsAndTheCurrentKeysSignatureVerifies() {
        assertArrayEquals(hex(a["statement"]), build())
        assertTrue(AuthAttestation.verify(b64(a["current_auth_public_key"]), build(), b64(a["signature"])))
    }

    @Test
    fun itAnswersForNoOtherAccountAddressOrKey() {
        val sig = b64(a["signature"])
        val pub = b64(a["current_auth_public_key"])
        assertFalse(AuthAttestation.verify(pub, build(userId = "c0ffee00-0000-4000-8000-0000000000ab"), sig))
        assertFalse(AuthAttestation.verify(pub, build(email = "other@example.com"), sig))
        val otherKey = AuthAttestation.emailChangeStatement(a["user_id"].asText(), a["email_utf8"].asText(), "QUFB")
        assertFalse(AuthAttestation.verify(pub, otherKey, sig))
        // Not the password change's attestation (the new key's raw bytes alone).
        assertFalse(AuthAttestation.verify(pub, hex(a["auth_public_key"]), sig))
    }

    @Test
    fun everyMalformedInputIsARefusalNeverAnException() {
        val sig = b64(a["signature"])
        assertNull(AuthAttestation.emailChangeStatement("u", "e@example.com", "not base64 !"))
        assertFalse(AuthAttestation.verify(null, build(), sig))
        assertFalse(AuthAttestation.verify(b64(a["current_auth_public_key"]), null, sig))
        assertFalse(AuthAttestation.verify(b64(a["current_auth_public_key"]), build(), "not base64 !"))
    }

    private val e: JsonNode =
        ObjectMapper().readTree(File("../../contracts/crypto/vectors/v1.json"))["auth_attestation"]["erase_account"]

    @Test
    fun theErasureStatementIsTheVectorsAndBindsTheAccountAndTheChallenge() {
        val statement = AuthAttestation.eraseAccountStatement(e["user_id"].asText(), e["nonce_b64"].asText())
        assertArrayEquals(hex(e["statement"]), statement)
        val sig = b64(e["signature"])
        val pub = b64(e["auth_public_key"])
        assertTrue(AuthAttestation.verify(pub, statement, sig))
        // Control: another account, or another challenge, is no proof.
        assertFalse(AuthAttestation.verify(pub, AuthAttestation.eraseAccountStatement("c0ffee00-0000-4000-8000-0000000000ab", e["nonce_b64"].asText()), sig))
        assertFalse(AuthAttestation.verify(pub, AuthAttestation.eraseAccountStatement(e["user_id"].asText(), "AAAA"), sig))
        assertNull(AuthAttestation.eraseAccountStatement(e["user_id"].asText(), "not base64 !"))
    }

    private fun hex(n: JsonNode): ByteArray = HexFormat.of().parseHex(n.asText())

    private fun b64(n: JsonNode): String = Base64.getEncoder().encodeToString(hex(n))
}
