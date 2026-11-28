package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.RecoveryCompleteRequest
import me.raddatz.veyq.mobile.api.models.RecoveryMaterial
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.sharing.OpenLink
import me.raddatz.veyq.mobile.sharing.ReceivedLink
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finishing a recovery (uc.account.recover-access steps 3–11, the web's `recovery.ts`) over a server the test plays: the
 * RECOVERY wrap opened by the Secret Key alone, what the complete sends checked as the server checks it (the recovery
 * proof under the Secret Key's proof key over the token's hash, the new auth key and the new wrap; the new wrap opening to
 * the same Master Key under the new password), each refusal sending nothing, and every key the flow held zeroed.
 */
class RecoveryFinishTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val proofs = AccountProofs(p)

    private inner class Played(
        val email: String = "p@a.example",
        var material: (() -> RecoveryMaterial?)? = null,
        var status: Int = 204,
    ) : RecoveryFinish.Door {
        val secretKey = p.randomBytes(32)
        val mk = p.randomBytes(32)
        val instanceSalt = p.randomBytes(16)
        val completes = mutableListOf<RecoveryCompleteRequest>()
        val released = mutableListOf<String>()
        var closed = false

        fun materialOf(params: ByteArray? = null): RecoveryMaterial {
            val salt = p.randomBytes(16)
            val kek = suite.kekRecovery(secretKey, salt)
            return RecoveryMaterial(
                email, RecoveryMaterial.SuiteId.v1, b64(suite.seal(Context("master-key"), kek, mk)),
                b64(params ?: suite.encodeKdfParams(V1.ARGON, salt)), b64(p.randomBytes(48)),
            )
        }

        override suspend fun release(token: String): RecoveryMaterial? {
            released += token
            return (material ?: { materialOf() }).invoke()
        }

        override suspend fun instanceSalt(email: String): String = b64(instanceSalt)
        override suspend fun complete(body: RecoveryCompleteRequest): Int = status.also { completes += body }
        override fun close() { closed = true }
    }

    private val token = "tok3n_tok3n_tok3n_tok3n_tok3n_tok3n_tok3n00"

    private fun finish(door: Played) = RecoveryFinish(p, "https://a.example", token, door)

    @Test
    fun aRecoveryOpensWithTheSecretKeyAloneAndSendsWhatTheServerChecks() = runTest {
        val d = Played()
        val f = finish(d)
        f.load()
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.KEY, f.current.phase)
        assertEquals("p@a.example", f.current.email)
        assertEquals(listOf(token), d.released)

        f.unlock(d.secretKey.copyOf())
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.PASSWORD, f.current.phase, "refused: ${f.current.refusal}")
        f.finish("a much better password", "a much better password")
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.DONE, f.current.phase, "refused: ${f.current.refusal}")

        val sent = d.completes.single()
        assertEquals(token, sent.token)
        val authSalt = suite.authSalt(d.instanceSalt, d.email)
        val auth = p.ed25519Public(suite.authSeed("a much better password", authSalt))
        assertContentEquals(auth, unb64(sent.authPublicKey))
        val statement = proofs.recoveryProofStatement(token, auth, AccountProofs.ProofWrap(unb64(sent.wrappedKey), unb64(sent.kdfParams)))
        assertTrue(p.ed25519Verify(proofs.secretKeyProofPublic(d.secretKey), statement, unb64(sent.secretKeyProofSignature)), "the recovery proof does not verify")
        val (params, salt) = suite.decodeKdfParams(unb64(sent.kdfParams))
        val kek = suite.kekPasswordSecret(d.secretKey, "a much better password", salt, params)
        assertContentEquals(d.mk, suite.open(Context("master-key"), kek, unb64(sent.wrappedKey)), "the new wrap does not open to the Master Key")
        assertEquals("https://a.example" to "p@a.example", f.signIn)
        assertTrue(f.heldForTest.isEmpty(), "the done step kept a key")
    }

    @Test
    fun aWrongKeyAMismatchAndAShortPasswordSendNothingAndKeepTheLink() = runTest {
        val d = Played()
        val f = finish(d)
        f.load()
        val wrong = p.randomBytes(32)
        f.unlock(wrong)
        assertEquals(Msg.RECOVERY_KEY_FAILED, f.current.refusal)
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.KEY, f.current.phase)
        assertTrue(wrong.all { it == 0.toByte() }, "a refused key was not zeroed")
        f.unlock(d.secretKey.copyOf())
        f.finish("a much better password", "a much better passwort")
        assertEquals(Msg.RECOVERY_MISMATCH, f.current.refusal)
        f.finish("short", "short")
        assertEquals(Msg.PASSWORD_TOO_SHORT, f.current.refusal)
        assertTrue(d.completes.isEmpty())
        assertNull(f.signIn)
    }

    @Test
    fun aSpentLinkAForeignProfileAndARefusedCompleteSayWhy() = runTest {
        val spent = finish(Played(material = { null }))
        spent.load()
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.SPENT, spent.current.phase)
        assertEquals(Msg.RECOVERY_LINK_SPENT, spent.current.refusal)

        val unreachable = finish(Played(material = { error("not reached") }))
        unreachable.load()
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.SPENT, unreachable.current.phase)

        val foreign = Played()
        foreign.material = { foreign.materialOf(suite.encodeKdfParams(me.raddatz.veyq.mobile.crypto.ArgonParams(2, 19456, 1, 32), p.randomBytes(16))) }
        val f = finish(foreign)
        f.load()
        f.unlock(foreign.secretKey.copyOf())
        assertEquals(Msg.SIGNIN_ERROR_UNREACHABLE, f.current.refusal)
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.KEY, f.current.phase)

        val refused = Played(status = 403)
        val r = finish(refused)
        r.load()
        r.unlock(refused.secretKey.copyOf())
        r.finish("a much better password", "a much better password")
        assertEquals(Msg.RECOVERY_PROOF_FAILED, r.current.refusal)
        assertEquals(me.raddatz.veyq.mobile.session.RecoveryPhase.PASSWORD, r.current.phase)
        assertEquals(1, refused.completes.size)
    }

    @Test
    fun theCloseZeroesEveryKeyTheFlowHeld() = runTest {
        val d = Played()
        val f = finish(d)
        f.load()
        f.unlock(d.secretKey.copyOf())
        val held = f.heldForTest
        assertEquals(2, held.size)
        f.close()
        for (k in held) assertTrue(k.all { it == 0.toByte() }, "a held key was left in memory")
        assertTrue(d.closed, "the door's clients were not closed")
        f.finish("a much better password", "a much better password")
        assertTrue(d.completes.isEmpty(), "a closed flow sent")
    }

    @Test
    fun aRecoveryLinkIsReadOnlyAsTheMailedOneAndHandedOverOnce() {
        val link = assertNotNull(ReceivedLink.recoveryOf(" https://a.example/recover#$token "))
        assertEquals("https://a.example", link.origin)
        assertEquals(token, link.token)
        assertFalse(link.toString().contains(token), "the link's text names the token")
        assertNotNull(ReceivedLink.recoveryOf("http://veyq.localhost:8081/recover#$token"), "a development host")
        for (refused in listOf(
            "http://a.example/recover#$token", // http off a development host
            "https://a.example/recover", // no token
            "https://a.example/recover#", // an empty one
            "https://u:p@a.example/recover#$token", // credentials
            "https://a.example/recovery#$token", // another path
            "veyq://open#$token", // the custom scheme carries no token
        )) assertNull(ReceivedLink.recoveryOf(refused), refused)

        val field = OpenLink { _, _ -> error("no guest view") }
        field.text("Your link: https://a.example/recover#$token — it stops working after an hour.")
        assertTrue(field.state.value.recovery && field.state.value.canOpen)
        assertEquals("https://a.example", field.recovery()?.origin)
        assertEquals(OpenLink.State(), field.state.value, "the field kept the token")
        assertNull(field.recovery(), "handed twice")
    }
}
