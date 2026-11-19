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

    /**
     * Inbox #979: each status line is named as the web's `scopeLabel` names it — a tenant root or an opened space by its
     * name, a personal space as such, any other as "a space this device hasn't opened" —, and a names read that fails
     * leaves every line named the last way, never refuses the result. Bitten by statuses that drop the names: red.
     */
    @Test
    fun eachStatusLineIsNamedAsTheWebNamesIt() = runTest {
        val names = KeyRotation.ScopeNames(personal = setOf("personal"), named = mapOf("tenant" to "Familie Raddatz", "group" to "Urlaub"))
        val engine = object : KeyRotation.Engine by Played(ColdCredential.Refusal.STALE) {
            override suspend fun resume(password: String, secretKey: ByteArray) = listOf("tenant", "group", "personal", "unseen")
                .map { RotationScopeState(it, RotationScopeState.State.secured) }
            override suspend fun names() = names
        }
        val model = KeyRotation(engine, p, "p@a.example", "http://a.example").also { it.open() }
        model.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        val lines = model.current.scopes.associateBy { it.scopeId }
        assertEquals("Familie Raddatz" to null, lines.getValue("tenant").let { it.name to it.nameWord })
        assertEquals("Urlaub" to null, lines.getValue("group").let { it.name to it.nameWord })
        assertEquals(null to Msg.ROTATION_PERSONAL_SPACE, lines.getValue("personal").let { it.name to it.nameWord })
        assertEquals(null to Msg.ROTATION_SCOPE_NAME_UNAVAILABLE, lines.getValue("unseen").let { it.name to it.nameWord })

        val failing = object : KeyRotation.Engine by Played(ColdCredential.Refusal.STALE) {
            override suspend fun names(): KeyRotation.ScopeNames = error("the plan did not read")
        }
        val unnamed = KeyRotation(failing, p, "p@a.example", "http://a.example").also { it.open() }
        unnamed.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertTrue(unnamed.current.done)
        assertEquals(listOf(Msg.ROTATION_SCOPE_NAME_UNAVAILABLE), unnamed.current.scopes.map { it.nameWord })
    }

    /**
     * Inbox #977: the done rotation lists the person's *Links to renew* (the web's `listLinksToRenew`), each space named as
     * a status line is, a link the open tenant holds carrying its node (its share dialog opens from there); read anew by
     * [KeyRotation.refreshLinks] once a dialog closed; none before the rotation is done; a read that fails lists none.
     */
    @Test
    fun theDoneRotationListsTheLinksToRenew() = runTest {
        val names = KeyRotation.ScopeNames(named = mapOf("group" to "Urlaub"))
        val node = me.raddatz.veyq.mobile.navigation.NodeAt("group", "n1", "memories.memory", "Lisbon")
        var listed = listOf(
            KeyRotation.RenewLink("share-1", "group", node),
            KeyRotation.RenewLink("share-2", "elsewhere", upload = true, tenant = "Familie"),
        )
        var asked = 0
        val engine = object : KeyRotation.Engine by Played(ColdCredential.Refusal.STALE) {
            override suspend fun names() = names
            override suspend fun linksToRenew(names: KeyRotation.ScopeNames): List<KeyRotation.RenewLink> {
                asked++
                return listed.map { KeyRotation.named(it, names) }
            }
        }
        val model = KeyRotation(engine, p, "p@a.example", "http://a.example").also { it.open() }
        model.refreshLinks()
        assertEquals(0, asked, "links read before the rotation was done")
        model.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertTrue(model.current.done)
        val links = model.current.links.associateBy { it.shareId }
        assertEquals(node, links.getValue("share-1").node)
        assertEquals("Urlaub" to null, links.getValue("share-1").let { it.scopeName to it.scopeWord })
        assertEquals(Triple(true, "Familie", Msg.ROTATION_SCOPE_NAME_UNAVAILABLE), links.getValue("share-2").let { Triple(it.upload, it.tenant, it.scopeWord) })
        // renewed in its dialog: read anew, it drops out
        listed = listed.drop(1)
        model.refreshLinks()
        assertEquals(listOf("share-2"), model.current.links.map { it.shareId })

        val failing = object : KeyRotation.Engine by Played(ColdCredential.Refusal.STALE) {
            override suspend fun linksToRenew(names: KeyRotation.ScopeNames): List<KeyRotation.RenewLink> = error("listShares did not answer")
        }
        val unlisted = KeyRotation(failing, p, "p@a.example", "http://a.example").also { it.open() }
        unlisted.prove("pw", p.randomBytes(32), "a much better password", "a much better password")
        assertTrue(unlisted.current.done && unlisted.current.links.isEmpty())
    }
}
