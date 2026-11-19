package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.offline.freshTestDirectory
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rotation's steps in the web's order (ADR 0148 decision 7) over an engine the test plays: the moderator sent to the
 * web, the gate's refusals, a stale account resumed without minting, and — the CR's bite (i) — no publish before the
 * kit was written where the person chose AND "I saved it" was checked.
 */
class KeyRotationTest {
    private val p = testPrimitives

    private class Played(var refusal: ColdCredential.Refusal? = null, var moderator: Boolean = false) : KeyRotation.Engine {
        val published = mutableListOf<Pair<ByteArray, String?>>()
        var resumed = 0
        override suspend fun holdsModerationKey() = moderator
        override suspend fun prove(password: String, secretKey: ByteArray) = refusal
        override suspend fun resume(password: String, secretKey: ByteArray): List<RotationScopeState> {
            resumed++
            return listOf(RotationScopeState("s1", RotationScopeState.State.secured))
        }
        override suspend fun publish(password: String, secretKey: ByteArray, newSecretKey: ByteArray, newPassword: String?): KeyRotation.Published {
            published += newSecretKey.copyOf() to newPassword
            return KeyRotation.Published(false, listOf(RotationScopeState("s1", RotationScopeState.State.waiting)), emptyList())
        }
    }

    @Test
    fun nothingIsPublishedBeforeTheKitWasSavedAndConfirmed() = runTest {
        val engine = Played()
        val model = KeyRotation(engine, p, "p@a.example", "http://a.example")
        model.open()
        assertEquals(KeyRotation.Step.GATE, model.current.step)
        val handed = p.randomBytes(32)
        model.prove("pw", handed, "a much better password", "a much better password")
        assertTrue(handed.all { it == 0.toByte() }, "the Secret Key handed in was kept")
        assertEquals(KeyRotation.Step.KIT, model.current.step)
        val kit = assertNotNull(model.kit)
        model.run()
        assertTrue(engine.published.isEmpty(), "published before the kit was saved")
        kit.confirm(true)
        assertTrue(!kit.current.confirmed, "\"I saved it\" took before a file was written")
        val dir = freshTestDirectory()
        try {
            assertTrue(kit.save("$dir/kit.pdf", "en", 20_000))
            model.run()
            assertTrue(engine.published.isEmpty(), "published before \"I saved it\"")
            kit.confirm(true)
            model.run()
        } finally {
            removeAll(dir)
        }
        assertEquals(1, engine.published.size)
        assertEquals("a much better password", engine.published.single().second)
        assertTrue(model.current.done)
        assertEquals(listOf(Msg.ROTATION_STATUS_WAITING), model.current.scopes.map { it.status })
        assertNull(model.kit?.let { runCatching { it.keyCopy() }.getOrNull() }, "the kit step kept its key after the publish")
    }

    @Test
    fun aModeratorIsSentToTheWebAndTheGateRefusesWithoutWriting() = runTest {
        val moderator = KeyRotation(Played(moderator = true), p, "p@a.example", "http://a.example").also { it.open() }
        assertEquals(KeyRotation.Step.ON_THE_WEB, moderator.current.step)
        moderator.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertNull(moderator.kit)

        val wrong = KeyRotation(Played(ColdCredential.Refusal.REJECTED), p, "p@a.example", "http://a.example").also { it.open() }
        wrong.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertEquals(Msg.ROTATION_ERROR_CREDENTIAL, wrong.current.refusal)
        assertNull(wrong.kit)
        wrong.prove("pw", p.randomBytes(32), "short", "short")
        assertEquals(Msg.PASSWORD_TOO_SHORT, wrong.current.refusal)
        wrong.setNewPassword(false)
        wrong.prove("pw", p.randomBytes(32))
        assertEquals(Msg.ROTATION_ERROR_CREDENTIAL, wrong.current.refusal)
    }

    @Test
    fun anAccountSecuredElsewhereIsResumedWithoutMinting() = runTest {
        val engine = Played(ColdCredential.Refusal.STALE)
        val model = KeyRotation(engine, p, "p@a.example", "http://a.example").also { it.open() }
        model.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertEquals(1, engine.resumed)
        assertTrue(model.current.done && model.current.resumed)
        assertNull(model.kit, "a kit was minted for an account secured elsewhere")
        assertTrue(engine.published.isEmpty())
        assertEquals(listOf(Msg.ROTATION_STATUS_SECURED), model.current.scopes.map { it.status })
    }
}
