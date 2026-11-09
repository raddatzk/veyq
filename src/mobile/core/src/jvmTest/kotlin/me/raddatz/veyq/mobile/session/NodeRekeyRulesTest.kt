package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.models.RekeyNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The re-key's rules (the web's `rekeyScope.ts`): which nodes a path takes, the frontier, and what this client re-keys. */
class NodeRekeyRulesTest {
    private fun row(id: String, parent: String?, due: Boolean, kind: String = "FOLDER", ownerOnly: Boolean = false, drop: Boolean = false, identity: Boolean = false) =
        RekeyNode(
            id = id, kind = kind, trashed = false, ownerOnly = ownerOnly, epoch = 1, scopeEpoch = 1, due = due, placementRevision = 1, metaRevision = 0,
            encryptedName = "", slugToken = "", wrappedContentKey = "", parentId = parent,
            sealedDropPrivateKey = if (drop) "x" else null, shareIdentityId = if (identity) "i" else null,
        )

    //  r ─ f ─ p
    //      └ g ─ q
    //  s
    private val rows = listOf(
        row("r", null, true), row("f", "r", true), row("p", "f", true, "EXPENSES_POT"), row("g", "f", true), row("q", "g", true, "MEMORY"), row("s", null, true),
    )
    private val byId = rows.associateBy { it.id }

    @Test
    fun aPathIsTheNodesAndTheirAncestors_andWithItsSubtreeEverythingBelow() {
        assertEquals(setOf("r", "f", "g"), NodeRekey.pathOf(listOf("g"), rows, byId, subtree = false))
        assertEquals(setOf("r", "f", "p", "g", "q"), NodeRekey.pathOf(listOf("f"), rows, byId, subtree = true))
        assertEquals(setOf("s"), NodeRekey.pathOf(listOf("s"), rows, byId, subtree = true))
    }

    @Test
    fun theFrontierIsADueNodeWhoseParentIsNot_anUnlistedParentCountsAsDue() {
        assertTrue(NodeRekey.parentNotDue(byId.getValue("r"), byId))
        assertFalse(NodeRekey.parentNotDue(byId.getValue("f"), byId))
        val doneParent = mapOf("r" to row("r", null, false), "f" to row("f", "r", true))
        assertTrue(NodeRekey.parentNotDue(doneParent.getValue("f"), doneParent))
        assertFalse(NodeRekey.parentNotDue(row("x", "gone", true), byId))
    }

    @Test
    fun theAppReKeysWhatItFullyHolds_andLeavesTheRest() {
        val kinds = AppKinds.rekeys
        assertTrue(NodeRekey.isMine(row("a", null, true), owner = false, kinds))
        assertTrue(NodeRekey.isMine(row("a", null, true, "EXPENSES_POT"), owner = false, kinds))
        assertTrue(NodeRekey.isMine(row("a", null, true, "FILE"), owner = false, kinds))
        // Memories' media are the web's re-key
        assertFalse(NodeRekey.isMine(row("a", null, true, "MEMORY"), owner = true, kinds))
        // an owner-only node is an owner's
        assertFalse(NodeRekey.isMine(row("a", null, true, ownerOnly = true), owner = false, kinds))
        assertTrue(NodeRekey.isMine(row("a", null, true, ownerOnly = true), owner = true, kinds))
        // a drop box's drop key and a guest's identity folder are re-keyed here too, by an owner (the server marks them owner-only)
        assertTrue(NodeRekey.isMine(row("a", null, true, "DEPOT_DROPBOX", drop = true), owner = true, kinds))
        assertTrue(NodeRekey.isMine(row("a", null, true, identity = true), owner = true, kinds))
    }

    @Test
    fun aNameFoldsAsItsKindsApplicationFoldsIt() {
        assertEquals("rom", AppKinds.tokenInput("FOLDER", "Rom"))
        assertEquals("memories/rom", AppKinds.tokenInput("MEMORY", "Rom"))
        assertEquals("expenses/rom", AppKinds.tokenInput("EXPENSES_POT", "Rom"))
        assertEquals("a.b", AppKinds.tokenInput("FILE", "A.b"))
        assertEquals(null, AppKinds.tokenInput("SOMETHING_NEW", "Rom"))
    }
}
