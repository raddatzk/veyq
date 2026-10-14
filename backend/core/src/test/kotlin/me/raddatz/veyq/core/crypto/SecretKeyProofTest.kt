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
 * The backend's statement bytes are the contract's (`secret_key_proof` in the crypto vectors), which
 * `packages/crypto` asserts too — ADR 0017 rule 4's second asserting stack. The server REBUILDS each statement
 * rather than trusting one the client names, so a drift between the two stacks is not a local test failure: it is
 * every rotation, password change and recovery refused in production. This is what reddens first.
 */
class SecretKeyProofTest {

    private val v: JsonNode = ObjectMapper().readTree(File("../contracts/crypto/vectors/v1.json"))["secret_key_proof"]
    private val publicKey = b64(v["key"]["public_key"])

    @Test
    fun theRotationStatementIsTheVectorsAndItsSignatureVerifies() {
        val r = v["rotation"]
        val stmt = SecretKeyProof.rotationStatement(
            currentIdentityPublicKey = b64(r["current_identity_public_key"]),
            identityPublicKey = b64(r["identity_public_key"]),
            wrappedIdentityPrivateKey = b64(r["wrapped_identity_private_key"]),
            passwordWrap = wrap(r["password_wrap"]),
            recoveryWrap = wrap(r["recovery_wrap"]),
        )
        assertStatementAndSignature(r, stmt)
    }

    @Test
    fun theChangePasswordStatementIsTheVectorsAndItsSignatureVerifies() {
        val c = v["change_password"]
        val stmt = SecretKeyProof.passwordChangeStatement(
            currentAuthPublicKey = b64(c["current_auth_public_key"]),
            authPublicKey = b64(c["auth_public_key"]),
            passwordWrap = wrap(c["password_wrap"]),
        )
        assertStatementAndSignature(c, stmt)
    }

    @Test
    fun theRecoveryStatementIsTheVectorsAndItsSignatureVerifies() {
        val r = v["recovery"]
        val stmt = SecretKeyProof.recoveryStatement(
            token = r["token_utf8"].asText(),
            authPublicKey = b64(r["auth_public_key"]),
            passwordWrap = wrap(r["password_wrap"]),
        )
        assertStatementAndSignature(r, stmt)
    }

    @Test
    fun theChangeEmailStatementIsTheVectorsAndItsSignatureVerifies() {
        val c = v["change_email"]
        // email_utf8 is the raw, un-normalized input: the statement must carry the address as stored.
        val stmt = SecretKeyProof.emailChangeStatement(
            currentAuthPublicKey = b64(c["current_auth_public_key"]),
            authPublicKey = b64(c["auth_public_key"]),
            email = c["email_utf8"].asText(),
        )
        assertStatementAndSignature(c, stmt)
    }

    @Test
    fun aSignatureAnswersOnlyForItsOwnFlow() {
        // Domain separation: the rotation vector's signature, presented over the change-password statement, must
        // not verify — otherwise one flow's proof would authorise another's write.
        val c = v["change_password"]
        val changeStmt = SecretKeyProof.passwordChangeStatement(
            b64(c["current_auth_public_key"]), b64(c["auth_public_key"]), wrap(c["password_wrap"]),
        )
        assertFalse(SecretKeyProof.verify(publicKey, changeStmt, b64(v["rotation"]["signature"])))
    }

    @Test
    fun everyMissingOrMalformedInputIsARefusalNeverAnException() {
        val r = v["recovery"]
        val stmt = SecretKeyProof.recoveryStatement(r["token_utf8"].asText(), b64(r["auth_public_key"]), wrap(r["password_wrap"]))
        val sig = b64(r["signature"])
        assertFalse(SecretKeyProof.verify(null, stmt, sig), "an account born before the proof key existed")
        assertFalse(SecretKeyProof.verify(publicKey, null, sig), "a statement that could not be built")
        assertFalse(SecretKeyProof.verify(publicKey, stmt, "not base64 !"), "a signature that is not base64")
        assertNull(
            SecretKeyProof.recoveryStatement("t", "not base64 !", wrap(r["password_wrap"])),
            "a non-base64 field builds no statement rather than throwing",
        )
    }

    private fun assertStatementAndSignature(case: JsonNode, stmt: ByteArray?) {
        assertArrayEquals(hex(case["statement"]), stmt)
        assertTrue(SecretKeyProof.verify(publicKey, stmt, b64(case["signature"])))
    }

    private fun wrap(w: JsonNode) = SecretKeyProof.Wrap(b64(w["wrapped_key"]), b64(w["kdf_params"]))

    private fun hex(n: JsonNode): ByteArray = HexFormat.of().parseHex(n.asText())

    private fun b64(n: JsonNode): String = Base64.getEncoder().encodeToString(hex(n))
}
