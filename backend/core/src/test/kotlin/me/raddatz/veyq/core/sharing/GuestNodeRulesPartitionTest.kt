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

    @Test
    fun aKindNobodyHandlesAllowsNothing() {
        for (op in ops) {
            assertFalse(GuestNodeRules.readWriteAllows(emptyList(), NodeEntity.FOLDER, op), "no rule at all: $op")
            assertFalse(GuestNodeRules.readWriteAllows(listOf(FolderGuestNodeRules()), "MEMORY", op), "an unclaimed kind: $op")
        }
    }

    @Test
    fun aKindTwoImplementationsClaimAllowsNothing() {
        val rules = listOf(FolderGuestNodeRules(), claiming(NodeEntity.FOLDER, allows = true))
        for (op in ops) assertFalse(GuestNodeRules.readWriteAllows(rules, NodeEntity.FOLDER, op), "two claim FOLDER: $op")
    }

    @Test
    fun coreLetsAReadWriteGuestDoEveryTreeOperationInAFolder() {
        for (op in ops) assertTrue(GuestNodeRules.readWriteAllows(listOf(FolderGuestNodeRules()), NodeEntity.FOLDER, op), "$op")
        // the one handler decides, a refusing one included
        assertFalse(GuestNodeRules.readWriteAllows(listOf(claiming("BOX", allows = false)), "BOX", ops[0]))
    }
}
