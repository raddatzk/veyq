package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.AccountErasurePreview
import me.raddatz.veyq.mobile.api.models.ErasureTenant
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credential flows the core ports (ADR 0148 decisions 6 and 8) over an account the test plays ([PlayedCredentials]):
 * what each sends is checked as the server checks it — the attestation under the current auth key, the Secret-Key proof
 * under the proof key, the new floor opened with the new password —, each refusal sends nothing, and the secrets the
 * flows were handed are zeroed when they return (the recording suite).
 */
class CredentialFlowsTest {
    private val p = testPrimitives
    private val proofs = AccountProofs(p)

    @Test
    fun aPasswordChangeSendsWhatTheServerChecksAndForgetsTheSavedKey() = runTest {
        val a = PlayedCredentials(p)
        val change = PasswordChange(a, p)
        change.run(a.password, "a much better password", "a much better password", a.secretKeyCopy())
        assertTrue(change.current.done, "refused: ${change.current.refusal}")
        val sent = a.changes.single()
        val oldAuth = a.authPublic(a.password)
        val newAuth = a.authPublic("a much better password")
        assertContentEquals(newAuth, unb64(sent.authPublicKey))
        assertTrue(p.ed25519Verify(oldAuth, newAuth, unb64(sent.attestationSignature)), "the attestation is not the old auth key's over the new key")
        val statement = proofs.passwordChangeProofStatement(
            oldAuth, newAuth, AccountProofs.ProofWrap(unb64(sent.passwordWrap.wrappedKey), unb64(sent.passwordWrap.kdfParams)),
        )
        assertTrue(p.ed25519Verify(proofs.secretKeyProofPublic(a.secretKey), statement, unb64(sent.secretKeyProofSignature)))
        // the new floor opens to the same Master Key under the new password and the same Secret Key
        val (params, salt) = a.suite.decodeKdfParams(unb64(sent.passwordWrap.kdfParams))
        val kek = a.suite.kekPasswordSecret(a.secretKey, "a much better password", salt, params)
        assertContentEquals(a.mk, a.suite.open(Context("master-key"), kek, unb64(sent.passwordWrap.wrappedKey)))
        assertEquals(1, a.passwordChangedCalls, "the saved key's record was not forgotten")
    }

    @Test
    fun aMismatchAShortPasswordOrAWrongSecretKeySendNothing() = runTest {
        val a = PlayedCredentials(p)
        val change = PasswordChange(a, p)
        change.run(a.password, "a much better password", "a much better passwort", a.secretKeyCopy())
        assertEquals(Msg.PASSWORD_CHANGE_MISMATCH, change.current.refusal)
        change.run(a.password, "short", "short", a.secretKeyCopy())
        assertEquals(Msg.PASSWORD_TOO_SHORT, change.current.refusal)
        change.run(a.password, "a much better password", "a much better password", p.randomBytes(32))
        assertEquals(Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL, change.current.refusal)
        change.run("not the password", "a much better password", "a much better password", a.secretKeyCopy())
        assertEquals(Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL, change.current.refusal)
        assertTrue(a.changes.isEmpty(), "a refused change sent a request")
        assertEquals(0, a.passwordChangedCalls)
    }

    @Test
    fun aFloorOpeningToAnotherKeyThatOpensTheIdentityIsStaleAndReadAsRejected() = runTest {
        val a = PlayedCredentials(p)
        val newer = p.randomBytes(32)
        val identity = a.suite.seal(Context("identity-priv"), newer, me.raddatz.veyq.mobile.crypto.concat(
            me.raddatz.veyq.mobile.crypto.lp(p.randomBytes(32)), me.raddatz.veyq.mobile.crypto.lp(p.randomBytes(32)),
        ))
        a.material = a.materialFor(newer, a.password, a.secretKey, identity)
        val stale = runCatching { ColdCredential.prove(a.suite, a.masterKey(), a.material, a.password, a.secretKeyCopy()) }.exceptionOrNull()
        assertEquals(ColdCredential.Refusal.STALE, (stale as ColdCredential.Refused).reason)
        val change = PasswordChange(a, p)
        change.run(a.password, "a much better password", "a much better password", a.secretKeyCopy())
        assertEquals(Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL, change.current.refusal)
    }

    /**
     * The recording suite (CR bite f): every secret the flow was handed or derived — the Secret Key it signs the proof
     * with (HKDF's input), every Argon2id input (the Secret Key with the password, each password), every Ed25519 seed —
     * is zero once the flow returned.
     */
    @Test
    fun thePasswordChangeLeavesNoSecretItWasHandedOrDerived() = runTest {
        val recorded = mutableListOf<Pair<String, ByteArray>>()
        val recording = object : Primitives by p {
            override fun argon2id(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, length: Int): ByteArray =
                this@CredentialFlowsTest.p.argon2id(password, salt, t, mKiB, p, length).also {
                    recorded += "argon input" to password
                    recorded += "argon output" to it
                }

            override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
                p.hkdfSha256(ikm, salt, info, length).also { if (info.contentEquals(utf8("veyq/v1/secret-key-proof"))) recorded += "proof input" to ikm }

            override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray =
                p.ed25519Sign(seed, message).also { recorded += "signing seed" to seed }
        }
        val a = PlayedCredentials(p)
        val handed = a.secretKeyCopy()
        PasswordChange(a, recording).run(a.password, "a much better password", "a much better password", handed)
        assertEquals(1, a.changes.size)
        assertTrue(handed.all { it == 0.toByte() }, "the Secret Key handed in was not zeroed")
        assertTrue(recorded.any { it.first == "proof input" } && recorded.count { it.first == "argon input" } >= 3, "the recording missed the flow")
        for ((what, bytes) in recorded) assertTrue(bytes.all { it == 0.toByte() }, "a $what was left in memory")
    }

    @Test
    fun anAddressChangeSignsUnderTheNewAddressAndTheKitAfterItIsChecked() = runTest {
        val a = PlayedCredentials(p)
        val change = AddressChange(a, p)
        change.request("  New@B.example ", a.password, a.secretKeyCopy())
        assertEquals("New@B.example", change.current.sentTo, "refused: ${change.current.refusal}")
        val sent = a.emailChanges.single()
        val currentAuth = a.authPublic(a.password)
        val newAuth = a.authPublic(a.password, "new@b.example")
        assertContentEquals(newAuth, unb64(sent.authPublicKey))
        assertTrue(p.ed25519Verify(currentAuth, proofs.emailChangeAttestationStatement(a.userId, "New@B.example", newAuth), unb64(sent.attestationSignature)))
        assertTrue(
            p.ed25519Verify(
                proofs.secretKeyProofPublic(a.secretKey), proofs.emailChangeProofStatement(currentAuth, newAuth, "New@B.example"),
                unb64(sent.secretKeyProofSignature),
            ),
        )
        // the same address, and a wrong Secret Key, send nothing
        change.request("P@A.example", a.password, a.secretKeyCopy())
        assertEquals(Msg.EMAIL_CHANGE_UNCHANGED, change.current.refusal)
        change.request("other@b.example", a.password, p.randomBytes(32))
        assertEquals(Msg.EMAIL_CHANGE_ERROR_CREDENTIAL, change.current.refusal)
        assertEquals(1, a.emailChanges.size)
        // the kit after it: refused for a wrong key, a step for the right one, its key the account's
        assertNull(change.kit(a.password, p.randomBytes(32)))
        assertEquals(Msg.EMAIL_CHANGE_KIT_REFUSED, change.current.refusal)
        val step = change.kit(a.password, a.secretKeyCopy())!!
        assertContentEquals(a.secretKey, step.keyCopy())
        assertEquals("veyq-emergency-kit-p@a.example.pdf", step.current.fileName)
        step.close()
    }

    @Test
    fun theErasureSignsTheNonceWithThePasswordAndAsksForEveryChoice() = runTest {
        val a = PlayedCredentials(p)
        a.answer = FlowReply(200)
        a.names = mapOf("u2" to "sam")
        a.preview = FlowReply(200) to AccountErasurePreview(
            listOf(ErasureTenant("t1", "Family", listOf("u2")), ErasureTenant("t2", "Mine", emptyList())),
        )
        val erasure = AccountErasure(a, p)
        erasure.load()
        assertEquals(listOf("sam"), erasure.current.tenants.first().members.map { it.name })
        erasure.erase(a.password)
        assertEquals(Msg.ERASURE_ERROR_CHOICE_REQUIRED, erasure.current.refusal)
        assertTrue(a.erasures.isEmpty())
        erasure.choose("t1", "u2")
        erasure.erase(a.password)
        assertTrue(erasure.current.erased)
        val sent = a.erasures.single()
        assertEquals(listOf("t1" to "u2"), sent.lastOwnerChoices.map { it.tenantId to it.newOwnerId })
        assertTrue(p.ed25519Verify(a.authPublic(a.password), proofs.eraseAccountAttestationStatement(a.userId, a.nonce), unb64(sent.attestationSignature)))
        assertEquals(1, a.erasedCalls, "the device did not forget the erased account")
    }

    @Test
    fun theServerOwnerAndAModeratorAreToldAndSendNothing() = runTest {
        val owner = PlayedCredentials(p).apply { preview = FlowReply(409, "server_owner_account") to null }
        val e1 = AccountErasure(owner, p).also { it.load() }
        assertTrue(e1.current.serverOwner)
        e1.erase(owner.password)
        assertTrue(owner.erasures.isEmpty())
        val moderator = PlayedCredentials(p).apply { moderator = true }
        val e2 = AccountErasure(moderator, p).also { it.load() }
        assertTrue(e2.current.onTheWeb)
        assertEquals("http://a.example", e2.current.server)
        e2.erase(moderator.password)
        assertTrue(moderator.erasures.isEmpty())
        // a wrong password: the server's 403, nothing forgotten
        val a = PlayedCredentials(p).apply { answer = FlowReply(403) }
        val e3 = AccountErasure(a, p).also { it.load() }
        e3.erase("nope")
        assertEquals(Msg.ERASURE_ERROR_PASSWORD, e3.current.refusal)
        assertEquals(0, a.erasedCalls)
    }
}
