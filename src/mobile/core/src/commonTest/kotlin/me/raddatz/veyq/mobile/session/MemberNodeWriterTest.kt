package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A node a member's run made and keeps writing into — a Memory's copy (inbox #988), the web's `memberNodeWriter` — over a
 * server the test plays: a rotation another member made mid-run refuses a write (`node_rekey_pending`); the node's path is
 * pulled forward, the node re-read, and that one write sent again under the re-keyed key and epoch — and the run's next
 * write starts there. A `stale_epoch` re-reads once; a node gone ends the write.
 */
class MemberNodeWriterTest {
    private val born = MemberNodeWriter.At(ByteArray(32) { 1 }, 0)

    @Test
    fun aRotationMidRunPullsTheNodeForwardAndResendsThatWrite_theNextStartsThere() = runTest {
        // the rotation landed: the server holds the node re-keyed at epoch 7, which this device learns by the pull's re-key
        // and the re-read
        val rekeyed = MemberNodeWriter.At(ByteArray(32) { 7 }, 7)
        var pulls = 0
        val writer = MemberNodeWriter(
            born,
            pull = { pulls++; true },
            reread = { MemberNodeWriter.Read(rekeyed, 1) },
            heldScopeEpoch = { 1 },
            sleep = {},
        )
        val sent = mutableListOf<MemberNodeWriter.At>()
        // the server takes a write stating the node's epoch now, and refuses one at the epoch it was born at
        suspend fun write() = writer.write { at ->
            sent += at
            if (at.epoch == rekeyed.epoch) null else "node_rekey_pending"
        }
        assertEquals(NodeEpochWrite.Result.Answered(null), write())
        assertEquals(1, pulls, "the node's path was not pulled forward")
        assertEquals(listOf(0, 7), sent.map { it.epoch }, "the refused write was not sent again under the re-read node")
        assertContentEquals(rekeyed.key, sent.last().key)
        // the run's next write starts at the key and epoch the last one landed under
        sent.clear()
        assertEquals(NodeEpochWrite.Result.Answered(null), write())
        assertEquals(listOf(7), sent.map { it.epoch }, "the next write started at the key the re-key replaced")
        assertEquals(1, pulls)
    }

    @Test
    fun aStaleEpochReReadsOnce_aGoneNodeEndsTheWrite() = runTest {
        var reads = 0
        val stale = MemberNodeWriter(born, pull = { false }, reread = { reads++; MemberNodeWriter.Read(born, 0) }, heldScopeEpoch = { 0 }, sleep = {})
        assertIs<NodeEpochWrite.Result.StaleAgain>(stale.write { "stale_epoch" })
        assertEquals(1, reads)
        val gone = MemberNodeWriter(born, pull = { false }, reread = { null }, heldScopeEpoch = { 0 }, sleep = {})
        assertIs<NodeEpochWrite.Result.Gone>(gone.write { "stale_epoch" })
    }
}
