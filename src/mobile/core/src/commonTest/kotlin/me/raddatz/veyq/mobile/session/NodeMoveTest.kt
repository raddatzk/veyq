package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.navigation.NodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The move browser's rules (the web's `moveHereBlock`, `movePlaces`, `moveStart`, `moveTrail` in `core/node/move.ts`). */
class NodeMoveTest {
    private fun n(id: String, parent: String?, kind: String = "FOLDER") = NodeRef("s", id, parent, kind, id)

    // root: trips/ (2026/ (pot-a), memory-x (chapter-y)), pot-b
    private val tree = listOf(
        n("trips", null), n("2026", "trips"), n("pot-a", "2026", "EXPENSES_POT"),
        n("memory-x", "trips", "MEMORY"), n("chapter-y", "memory-x", "CHAPTER"), n("pot-b", null, "EXPENSES_POT"),
    )

    /** Each kind's placement as the web registers it: a folder and a Pot at the root or in a folder, a chapter in a Memory. */
    private val allows = NodeMove.PlacementAllows { kind, parent ->
        when (kind) {
            "FOLDER", "EXPENSES_POT", "MEMORY" -> parent == null || parent == "FOLDER"
            "CHAPTER" -> parent == "MEMORY"
            else -> false
        }
    }

    private fun ref(id: String) = tree.single { it.nodeId == id }

    @Test
    fun aPotGoesToTheRootOrAFolderButNotWhereItIsNorIntoAMemory() {
        val pot = ref("pot-a")
        assertNull(NodeMove.hereBlock(tree, pot, null, allows))
        assertNull(NodeMove.hereBlock(tree, pot, "trips", allows))
        assertEquals(NodeMove.Block.CURRENT, NodeMove.hereBlock(tree, pot, "2026", allows))
        assertEquals(NodeMove.Block.KIND, NodeMove.hereBlock(tree, pot, "memory-x", allows))
        assertEquals(NodeMove.Block.KIND, NodeMove.hereBlock(tree, pot, "pot-b", allows))
        // a place this tree does not hold takes nothing
        assertEquals(NodeMove.Block.KIND, NodeMove.hereBlock(tree, pot, "elsewhere", allows))
    }

    @Test
    fun aFolderGoesNeitherIntoItselfNorBelowItself() {
        val trips = ref("trips")
        assertEquals(NodeMove.Block.ITSELF, NodeMove.hereBlock(tree, trips, "trips", allows))
        assertEquals(NodeMove.Block.INSIDE, NodeMove.hereBlock(tree, trips, "2026", allows))
        assertEquals(NodeMove.Block.CURRENT, NodeMove.hereBlock(tree, trips, null, allows))
        assertNull(NodeMove.hereBlock(tree, ref("2026"), null, allows))
    }

    @Test
    fun theListedPlacesAreFoldersAndWhatTakesTheNodeWithItselfBlocked() {
        val trips = ref("trips")
        val atRoot = NodeMove.places(tree, trips, null, allows)
        // the folder itself is listed but cannot be opened; a Pot takes no folder, so it is not listed
        assertEquals(listOf("trips" to NodeMove.Block.ITSELF), atRoot.map { it.node.nodeId to it.blocked })
        val inTrips = NodeMove.places(tree, ref("pot-b"), "trips", allows)
        // the folder is the way down; a Memory does not take a Pot
        assertEquals(listOf("2026" to null), inTrips.map { it.node.nodeId to it.blocked })
        // a chapter's place is a Memory: listed for it, and nothing else in trips but the folder
        assertEquals(listOf("2026", "memory-x"), NodeMove.places(tree, ref("chapter-y"), "trips", allows).map { it.node.nodeId })
        assertEquals(NodeMove.Block.INSIDE, NodeMove.places(tree, trips, "trips", allows).single().blocked)
    }

    @Test
    fun theBrowserOpensAtTheNodesPlaceUnderATrailFromTheRoot() {
        assertEquals("2026", NodeMove.start(tree, ref("pot-a")))
        assertNull(NodeMove.start(tree, ref("pot-b")))
        assertNull(NodeMove.start(tree, n("orphan", "missing")))
        assertEquals(listOf(null, "trips", "2026"), NodeMove.trail(tree, "2026").map { it?.nodeId })
        assertEquals(listOf<String?>(null), NodeMove.trail(tree, null).map { it?.nodeId })
    }

    @Test
    fun aServedParentCycleStops() {
        val cycle = listOf(n("a", "b"), n("b", "a"))
        assertNull(NodeMove.hereBlock(cycle, n("c", null), "a", allows))
        assertEquals(2, NodeMove.trail(cycle, "a").size - 1)
    }
}
