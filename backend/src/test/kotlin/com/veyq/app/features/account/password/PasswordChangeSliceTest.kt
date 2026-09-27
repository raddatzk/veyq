package com.veyq.app.features.account.password

import com.veyq.app.features.account.login.SessionFamilyEntity
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.crypto.SecretKeyProof
import com.veyq.app.shared.crypto.TestProofKey
import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for changing a password from a live session (uc.account.change-password).
 *
 * Four cases carry the correctness this operation rests on:
 *  - the **deliberate** path (a valid attestation) rewrites the floor + auth key, drops every device wrap, and
 *    revokes every session **except the caller's own** — the one behaviour that differs from `RecoveryStore`,
 *    asserted by name (not by the absence of an error), per `changes/README.md` § *a bite test must tell "the
 *    guard held" apart from "nothing ran"*;
 *  - a **wrong** attestation (one flipped byte of an otherwise-valid signature) is refused with nothing
 *    written — the floor and auth key stay byte-identical to before;
 *  - a change carrying **no** `attestationSignature` is refused with nothing written — the field is required
 *    by the contract, so this one is bitten at the boundary rather than in the handler, and it is the live
 *    defect this flow shipped with: it used to be a second entry point that applied the identical rewrite
 *    while proving nothing (ADR 0060 Decision 2's bypass);
 *  - a valid attestation with a Secret-Key proof from **another** Secret Key is refused with nothing written — a
 *    phished or cracked password buys the attestation and nothing else (ADR 0060 Decision 2), with the same
 *    request under the account's own proof key as its control;
 *  - an account with no `PASSWORD` floor refuses with nothing written.
 *
 * Seeds use UUID-unique ids: the H2 mem DB lives for the whole JVM and is shared across test classes.
 */
@QuarkusTest
class PasswordChangeSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun deliberateChangeWithValidAttestationRewritesFloorDropsDeviceWrapsAndSparesTheCallersOwnSession() {
        val user = seedAccount()
        seedWrap(user, "DEVICE", "ZGV2aWNlLXdyYXA=")
        seedWrap(user, "DEVICE_KEY", "ZGV2aWNlLWtleS13cmFw")
        seedWrap(user, "PASSKEY", "cGFzc2tleS13cmFw")
        seedWrap(user, "RECOVERY", REC_WRAP)
        val otherToken = sessions.issue(user).accessToken
        val otherFamily = familyIdOf(user, otherToken)
        val ownToken = sessions.issue(user).accessToken
        val ownFamily = familyIdOf(user, ownToken)

        change(user, ownToken, attestation = sign(KEYPAIR, NEW_AUTH_PUB))
            .then().statusCode(204)

        assertEquals(NEW_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor holds the new Master Key")
        assertEquals(NEW_AUTH_PUB, field(user) { it.authPublicKey }, "login and unlock must never disagree")
        assertEquals(null, wrapOf(user, "DEVICE"), "a device-held KEK cannot be rewritten from here, so it goes")
        assertEquals(null, wrapOf(user, "DEVICE_KEY"), "the password-less one especially needs no password at all")
        // The control on the same predicate: a whitelist that over-reaches and takes the recovery path with it
        // would lock the person out of the one route that works without the password.
        assertEquals(REC_WRAP to KDF, wrapRowOf(user, "RECOVERY"), "the Emergency Kit's wrap survives byte-for-byte")
        assertEquals(null, wrapOf(user, "PASSKEY"), "every method not declared a survivor goes — a whitelist, not a blacklist")

        assertEquals(SessionFamilyEntity.REVOKED, familyStatus(otherFamily), "every other session is cut")
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(ownFamily), "changing a password must not strand the session doing it")
    }

    @Test
    fun aWrongAttestationIsRefusedAndWritesNothing() {
        val user = seedAccount()
        val validSignature = Base64.getDecoder().decode(sign(KEYPAIR, NEW_AUTH_PUB))
        val flipped = validSignature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        val wrongSignature = Base64.getEncoder().encodeToString(flipped)

        change(user, sessions.issue(user).accessToken, attestation = wrongSignature)
            .then().statusCode(403).body("code", equalTo("attestation_failed"))

        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "a failed attestation writes nothing")
        assertEquals(OLD_AUTH_PUB, field(user) { it.authPublicKey })
    }

    @Test
    fun aValidPasswordWithoutTheSecretKeyIsRefusedAndWritesNothing() {
        // The attestation is genuine — this caller knows the current password — but the proof is signed with a
        // key the account never registered, because the caller does not hold its Secret Key. That is someone
        // who phished the password, and the half this server can check for them is this one.
        val user = seedAccount()
        val attestation = sign(KEYPAIR, NEW_AUTH_PUB)

        change(user, sessions.issue(user).accessToken, attestation, proof = OTHER_KEY.sign(changeStatement()))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))

        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "a failed proof writes nothing")
        assertEquals(OLD_AUTH_PUB, field(user) { it.authPublicKey })

        change(user, sessions.issue(user).accessToken, attestation).then().statusCode(204)
        assertEquals(NEW_AUTH_PUB, field(user) { it.authPublicKey }, "control: the account's own proof key applies")
    }

    @Test
    fun anAccountWithNoProofKeyCannotChangeItsPassword() {
        val user = seedAccount()
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(user)!!.secretKeyProofPublicKey = null }

        change(user, sessions.issue(user).accessToken, attestation = sign(KEYPAIR, NEW_AUTH_PUB))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertEquals(OLD_AUTH_PUB, field(user) { it.authPublicKey })
    }

    @Test
    fun aChangeWithoutAnAttestationWritesNothing() {
        val user = seedAccount()

        change(user, sessions.issue(user).accessToken, attestation = null)
            .then().statusCode(400)

        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "a change proving neither factor must not touch the floor")
        assertEquals(OLD_AUTH_PUB, field(user) { it.authPublicKey }, "nor the directory value login reads")
    }

    @Test
    fun withoutAPasswordFloorNothingIsWritten() {
        val user = seedAccount()
        QuarkusTransaction.requiringNew().run { MasterKeyWrapEntity.delete("userId = ?1 and method = ?2", user, "PASSWORD") }

        change(user, sessions.issue(user).accessToken, attestation = sign(KEYPAIR, NEW_AUTH_PUB))
            .then().statusCode(409).body("code", equalTo("password_change_unavailable"))

        assertEquals(OLD_AUTH_PUB, field(user) { it.authPublicKey }, "the directory value is untouched")
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun change(
        userId: String,
        accessToken: String,
        attestation: String?,
        proof: String = PROOF_KEY.sign(changeStatement()),
    ) = given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
        .body(changeBody(attestation, proof)).`when`().post("/api/account/password")

    private fun changeBody(attestation: String?, proof: String): String {
        val attestationField = attestation?.let { ""","attestationSignature":"$it"""" } ?: ""
        return """
            {"passwordWrap":{"wrappedKey":"$NEW_PW_WRAP","kdfParams":"$KDF"},
             "authPublicKey":"$NEW_AUTH_PUB"$attestationField,
             "secretKeyProofSignature":"$proof"}
        """.trimIndent()
    }

    /** What a legitimate client signs for this fixture's change: from the seeded auth key to the new one. */
    private fun changeStatement() =
        SecretKeyProof.passwordChangeStatement(OLD_AUTH_PUB, NEW_AUTH_PUB, SecretKeyProof.Wrap(NEW_PW_WRAP, KDF))

    private fun seedAccount(): String {
        val userId = seedUser(authPublicKey = OLD_AUTH_PUB, secretKeyProofPublicKey = PROOF_KEY.publicKey)
        seedWrap(userId, "PASSWORD", OLD_PW_WRAP)
        return userId
    }

    private fun seedWrap(userId: String, method: String, wrapped: String) = QuarkusTransaction.requiringNew().run {
        MasterKeyWrapEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.method = method
            wrappedKey = wrapped; kdfParams = KDF
        }.persist()
    }

    private fun field(userId: String, read: (VeyqUser) -> String): String? =
        QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(userId)?.let(read) }

    private fun wrapOf(userId: String, method: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method).firstResult()?.wrappedKey
    }

    private fun wrapRowOf(userId: String, method: String): Pair<String, String?>? =
        QuarkusTransaction.requiringNew().call<Pair<String, String?>?> {
            MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method).firstResult()
                ?.let { it.wrappedKey to it.kdfParams }
        }

    private fun familyStatus(familyId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { SessionFamilyEntity.findById(familyId)?.status }

    /** The family an issued access token belongs to — read the way the account's own session list reads it. */
    private fun familyIdOf(userId: String, accessToken: String): String =
        sessions.listActiveFamilies(userId, accessToken).first { it.current }.id

    private companion object {
        const val KDF = "a2RmLXBhcmFtcw=="
        const val OLD_PW_WRAP = "b2xkLXBhc3N3b3JkLXdyYXA="
        const val NEW_PW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val REC_WRAP = "cmVjb3Zlcnktd3JhcA=="
        const val NEW_AUTH_PUB = "bmV3LWF1dGgtcHVibGljLWtleQ=="

        private val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        /** The Secret-Key proof key every seeded account registers; [OTHER_KEY] is a Secret Key it does not hold. */
        val PROOF_KEY = TestProofKey()
        val OTHER_KEY = TestProofKey()

        // The account's stored auth key must be the REAL public key of KEYPAIR — the attestation is verified
        // against it, so a placeholder string here would make every valid-attestation case fail closed.
        val OLD_AUTH_PUB: String = Base64.getEncoder().encodeToString(rawPublicKey(KEYPAIR))

        private fun rawPublicKey(kp: KeyPair): ByteArray =
            kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

        /** Sign the raw bytes of [messageB64] (the new auth public key) with the OLD private key — the
         *  attestation's own shape (not a server nonce). Return standard-base64 signature. */
        private fun sign(kp: KeyPair, messageB64: String): String {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(kp.private)
            sig.update(Base64.getDecoder().decode(messageB64))
            return Base64.getEncoder().encodeToString(sig.sign())
        }
    }
}
