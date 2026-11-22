package me.raddatz.veyq.mobile.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tree rules' cases that are no vector (`contracts/platform/tree/v1-vectors.json` holds the rest, `TreeVectorsTest`):
 * the order's inputs untouched, the creation order's parts, a served cycle bounded, the kind table's defaults.
 */
class TreeRulesTest {
    private class E(val id: String, val name: String, val createdAt: Double? = null)

    private fun order(folders: List<E>, others: List<E>) = LevelOrder.order(folders, others, { it.id }, { it.name }, { it.createdAt }).map { it.id }

    @Test
    fun theOrderDoesNotChangeItsInputs() {
        val folders = listOf(E("f2", "B"), E("f1", "A"))
        val others = listOf(E("b", "B"), E("a", "A"))
        assertEquals(listOf("f1", "f2", "a", "b"), order(folders, others))
        assertEquals(listOf("f2", "f1"), folders.map { it.id })
        assertEquals(listOf("b", "a"), others.map { it.id })
    }

    @Test
    fun aNumberComparesByItsValue_whateverItsLength() {
        assertTrue(LevelOrder.key("Foto 9") < LevelOrder.key("Foto 10"))
        assertEquals(LevelOrder.key("Foto 0002"), LevelOrder.key("Foto 2"), "leading zeros aside")
        assertEquals(LevelOrder.key("0"), LevelOrder.key("000"))
        assertTrue(LevelOrder.key("Foto 99") < LevelOrder.key("Foto 12345678901234567890"))
    }

    @Test
    fun caseAccentsAndIgnoredCharactersAside() {
        assertEquals(LevelOrder.key("Äpfel"), LevelOrder.key("apfel"))
        assertEquals(LevelOrder.key("STRASSE"), LevelOrder.key("Straße"))
        assertEquals(LevelOrder.key("ab"), LevelOrder.key("a­b"), "a soft hyphen is ignored")
        assertEquals(LevelOrder.key("‘x’"), LevelOrder.key("'x'"), "the quotes the collator reads as one")
    }

    @Test
    fun theCreationOrder_theInstantFirst_thenTheIdInCodeUnits() {
        assertTrue(DisplayNames.compareByCreation(1.0, "B", 1.0, "a") < 0, "B (0x42) before a")
        assertEquals(0, DisplayNames.compareByCreation(1.0, "a", 1.0, "a"))
        assertTrue(DisplayNames.compareByCreation(1.0, "a", 2.0, "B") < 0, "the instant before the id")
        assertEquals(0, DisplayNames.compareByCreation(null, "a", 0.0, "a"), "none served reads as 0")
        assertTrue(DisplayNames.compareById("B", "a") < 0)
    }

    @Test
    fun aKindNotListedFoldsAsAContainerAndIsPlacedNowhere() {
        val kinds = KindFacts(listOf(KindFacts.Kind("FILE", null, KindFacts.FILE_NAME, root = true, parents = listOf("FOLDER"))))
        assertFalse(kinds.knows("UNKNOWN"))
        assertEquals("rom", kinds.tokenInput("UNKNOWN", "Rom"))
        assertFalse(kinds.placementAllows("UNKNOWN", null))
        assertFalse(kinds.placementAllows("UNKNOWN", "FOLDER"))
        assertEquals("foto.jpg", kinds.tokenInput("FILE", "Foto.JPG"))
        assertTrue(kinds.placementAllows("FILE", null))
        assertFalse(kinds.placementAllows("FILE", "MEMORY"))
    }

    @Test
    fun aServedParentCycleIsBounded() {
        val cycle = TreeFacts(
            listOf(
                TreeFacts.Node("a", "b", "FOLDER", "a", null, false),
                TreeFacts.Node("b", "a", "FOLDER", "b", null, false),
            ),
        )
        assertFalse(cycle.inIdentityFolder("a"))
        assertEquals(listOf(null, "b", "a"), MovePlan.trail(cycle, null, "a"))
        val allows = { _: String, _: String? -> true }
        assertEquals(MovePlan.INSIDE, MovePlan.hereBlock(allows, cycle, listOf(TreeFacts.Node("a", "b", "FOLDER", "", null, false)), false, "b"))
    }

    @Test
    fun aPlaceTheTreeDoesNotReadTakesNothing() {
        val tree = TreeFacts(listOf(TreeFacts.Node("f", null, "FOLDER", "f", null, false)))
        val moved = listOf(TreeFacts.Node("f", null, "FOLDER", "", null, false))
        val allows = { _: String, _: String? -> true }
        assertEquals(MovePlan.KIND, MovePlan.hereBlock(allows, tree, moved, false, "elsewhere"))
        assertNull(MovePlan.hereBlock(allows, tree, listOf(TreeFacts.Node("g", "f", "FOLDER", "", null, false)), false, null), "control: the scope root")
    }

    @Test
    fun movingAnIdentityFolderEndsIt() {
        val tree = TreeFacts(listOf(TreeFacts.Node("g", "box", "FOLDER", "Anna", null, true), TreeFacts.Node("f", null, "FOLDER", "f", null, false)))
        assertTrue(MovePlan.endsIdentity(tree, "g"))
        assertFalse(MovePlan.endsIdentity(tree, "f"))
        assertFalse(MovePlan.endsIdentity(tree, "unknown"))
    }

    @Test
    fun aSelectionNamedLikeItselfThereIsNotTakenByItself() {
        // the plan folds the destination's children once; a node listed there but stale in the selection meets only others
        val kinds = KindFacts(listOf(KindFacts.Kind("FOLDER", null, KindFacts.NAME, root = true, parents = listOf("FOLDER"))))
        val tree = TreeFacts(listOf(TreeFacts.Node("d", null, "FOLDER", "d", null, false), TreeFacts.Node("x", "d", "FOLDER", "X", null, false)))
        val plan = MovePlan.plan(kinds, tree, listOf(TreeFacts.Node("x", "elsewhere", "FOLDER", "x", null, false)), "d")
        assertEquals(listOf("x"), plan.send.map { it.id })
        assertNull(SiblingNames.colliding(kinds, tree, "d", "FOLDER", "X", "x"))
    }
}
