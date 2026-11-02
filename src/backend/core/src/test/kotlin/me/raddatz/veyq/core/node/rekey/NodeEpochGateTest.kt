package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.OK
import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.PENDING
import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.STALE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The gate's rule without its lock (ADR 0114 decision 3): a replaced key is stale before a due node is pending, and
 *  the node's own mark decides — no scope epoch is read. The lock and the HTTP answers are `RekeyCoreSliceTest`'s. */
class NodeEpochGateTest {

    @Test
    fun theStatedEpochMustBeTheNodesOwnAndTheNodeNotDue() {
        assertEquals(OK, NodeEpochGate.verdict(stated = 2, stored = 2, due = false))
        assertEquals(PENDING, NodeEpochGate.verdict(stated = 1, stored = 1, due = true), "the re-key has not reached the node")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 1, stored = 2, due = false), "the writer holds a replaced key")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 2, stored = 1, due = false), "a key the node does not have yet")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 0, stored = 1, due = true), "stale before pending")
        assertEquals(OK, NodeEpochGate.verdict(stated = 0, stored = 0, due = false), "a fresh node, never re-keyed")
    }
}
