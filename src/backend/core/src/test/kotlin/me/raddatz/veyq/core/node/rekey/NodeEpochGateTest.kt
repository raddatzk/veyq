package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.OK
import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.PENDING
import me.raddatz.veyq.core.node.rekey.NodeEpochGate.Verdict.STALE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The gate's rule without its lock (ADR 0109 point 6): a key from before is stale before a node is pending. The lock
 *  and the HTTP answers are `RekeyCoreSliceTest`'s. */
class NodeEpochGateTest {

    @Test
    fun theStatedEpochMustBeTheStoredOneAndTheStoredOneCurrent() {
        assertEquals(OK, NodeEpochGate.verdict(stated = 2, stored = 2, current = 2))
        assertEquals(PENDING, NodeEpochGate.verdict(stated = 1, stored = 1, current = 2), "the re-key has not reached the node")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 1, stored = 2, current = 2), "the writer holds the key from before")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 2, stored = 1, current = 2), "a key the node does not have yet")
        assertEquals(STALE, NodeEpochGate.verdict(stated = 0, stored = 1, current = 2), "stale before pending")
        assertEquals(OK, NodeEpochGate.verdict(stated = 0, stored = 0, current = null), "a scope without an epoch row")
    }
}
