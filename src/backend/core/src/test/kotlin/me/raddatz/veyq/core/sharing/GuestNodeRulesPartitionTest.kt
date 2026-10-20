package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [GuestNodeRules] partitions container kinds as `NodeKindRules` and [ShareRoots] do (ADR 0080 decision 6, bite test
 * 18): the one implementation that handles a kind decides; none or several allow a guest nothing — never the first
 * by CDI bean order, never open by default.
 */
class GuestNodeRulesPartitionTest {

    private val ops = listOf(
        GuestNodeOp.CreateChild("X"), GuestNodeOp.MoveChildOut("X"), GuestNodeOp.MoveChildIn("X"), GuestNodeOp.RenameChild("X"),
    )

    private fun claiming(kind: String, allows: Boolean) = object : GuestNodeRules {
        override fun handles(containerKind: String) = containerKind == kind
        override fun readWriteAllows(containerKind: String, op: GuestNodeOp) = allows
    }

    private fun declaring(vararg kinds: String) = object : GuestCreatableKinds {
        override fun kinds() = kinds.toSet()
    }

    private fun folder(vararg declared: GuestCreatableKinds) = FolderGuestNodeRules(declared.toMutableList())

    @Test
    fun aKindNobodyHandlesAllowsNothing() {
        for (op in ops) {
            assertFalse(GuestNodeRules.readWriteAllows(emptyList(), NodeEntity.FOLDER, op), "no rule at all: $op")
            assertFalse(GuestNodeRules.readWriteAllows(listOf(folder(declaring("X"))), "MEMORY", op), "an unclaimed kind: $op")
        }
    }

    @Test
    fun aKindTwoImplementationsClaimAllowsNothing() {
        val rules = listOf(folder(declaring("X")), claiming(NodeEntity.FOLDER, allows = true))
        for (op in ops) assertFalse(GuestNodeRules.readWriteAllows(rules, NodeEntity.FOLDER, op), "two claim FOLDER: $op")
    }

    /**
     * Owner decision 2026-10-14: in a folder a `READ_WRITE` guest creates a `FOLDER` or a kind its application declares
     * ([GuestCreatableKinds]), nothing else — and moves and renames any kind, declared or not.
     */
    @Test
    fun inAFolderAGuestCreatesAFolderOrADeclaredKindAndMovesAnyKind() {
        val rules = listOf(folder(declaring("X")))
        for (op in ops) assertTrue(GuestNodeRules.readWriteAllows(rules, NodeEntity.FOLDER, op), "a declared kind: $op")
        assertTrue(GuestNodeRules.readWriteAllows(rules, NodeEntity.FOLDER, GuestNodeOp.CreateChild(NodeEntity.FOLDER)), "a folder")

        val undeclared = listOf(folder())
        assertTrue(GuestNodeRules.readWriteAllows(undeclared, NodeEntity.FOLDER, GuestNodeOp.CreateChild(NodeEntity.FOLDER)), "a folder, nothing declared")
        assertFalse(GuestNodeRules.readWriteAllows(undeclared, NodeEntity.FOLDER, GuestNodeOp.CreateChild("X")), "an undeclared kind is created")
        for (op in listOf(GuestNodeOp.MoveChildOut("X"), GuestNodeOp.MoveChildIn("X"), GuestNodeOp.RenameChild("X"))) {
            assertTrue(GuestNodeRules.readWriteAllows(undeclared, NodeEntity.FOLDER, op), "an undeclared kind, already there: $op")
        }
        // a kind two declarations name is created by nobody (and fails startup: GuestCreatableKindsTest)
        val twice = listOf(folder(declaring("X"), declaring("X")))
        assertFalse(GuestNodeRules.readWriteAllows(twice, NodeEntity.FOLDER, GuestNodeOp.CreateChild("X")), "a kind declared twice")
        // the one handler decides, a refusing one included
        assertFalse(GuestNodeRules.readWriteAllows(listOf(claiming("BOX", allows = false)), "BOX", ops[0]))
    }
}
