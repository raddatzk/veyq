package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.session.NodeEpochWrite.Answer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core's write at a node's epoch (the web's `rekeyRetry.test.ts` for a writer without a pull): what is retried, after
 * how long, and that every attempt after a refusal re-reads first.
 */
class NodeEpochWriteTest {
    private class Run(answers: List<Answer>, var floorBelow: List<Boolean> = emptyList(), val rereadOk: Boolean = true) {
        private val queue = ArrayDeque(answers)
        val log = mutableListOf<String>()

        suspend fun go(): NodeEpochWrite.Result<Int> = NodeEpochWrite.run(
            waitsMs = listOf(1, 2, 3),
            belowFloor = { (floorBelow.firstOrNull() ?: false).also { floorBelow = floorBelow.drop(1); if (it) log += "floor" } },
            reread = { log += "reread"; rereadOk },
            attempt = { log += "send"; queue.removeFirst() to log.count { it == "send" } },
            sleep = { log += "wait $it" },
        )
    }

    @Test
    fun aWriteThatIsTakenIsSentOnceWithoutARead() = runBlocking {
        val run = Run(listOf(Answer.DONE))
        assertEquals(NodeEpochWrite.Result.Answered(1), run.go())
        assertEquals(listOf("send"), run.log)
    }

    @Test
    fun anotherRefusalIsHandedBackAsItCame() = runBlocking {
        val run = Run(listOf(Answer.OTHER))
        assertEquals(NodeEpochWrite.Result.Answered(1), run.go())
        assertEquals(listOf("send"), run.log)
    }

    @Test
    fun aStaleEpochReReadsOnceAndTriesAgain() = runBlocking {
        val run = Run(listOf(Answer.STALE_EPOCH, Answer.DONE))
        assertEquals(NodeEpochWrite.Result.Answered(2), run.go())
        assertEquals(listOf("send", "reread", "send"), run.log)
        assertEquals(NodeEpochWrite.Result.StaleAgain, Run(listOf(Answer.STALE_EPOCH, Answer.STALE_EPOCH)).go())
    }

    @Test
    fun aPendingReKeyWaitsReReadsAndGivesUpAfterTheLastWait() = runBlocking {
        val run = Run(listOf(Answer.REKEY_PENDING, Answer.DONE))
        assertEquals(NodeEpochWrite.Result.Answered(2), run.go())
        assertEquals(listOf("send", "wait 1", "reread", "send"), run.log)
        val busy = Run(List(4) { Answer.REKEY_PENDING })
        assertEquals(NodeEpochWrite.Result.Busy, busy.go())
        assertEquals(listOf("wait 1", "wait 2", "wait 3"), busy.log.filter { it.startsWith("wait") })
        assertEquals(4, busy.log.count { it == "send" })
    }

    @Test
    fun belowTheFloorNothingIsSentUntilTheReReadIsAtIt() = runBlocking {
        val run = Run(listOf(Answer.DONE), floorBelow = listOf(true, true, false))
        assertEquals(NodeEpochWrite.Result.Answered(1), run.go())
        assertEquals(listOf("floor", "wait 1", "reread", "floor", "wait 2", "reread", "send"), run.log)
        val stuck = Run(listOf(Answer.DONE), floorBelow = List(5) { true })
        assertEquals(NodeEpochWrite.Result.Busy, stuck.go())
        assertEquals(0, stuck.log.count { it == "send" })
    }

    @Test
    fun theFloorsWaitsAndThePendingOnesAreOneBudget() = runBlocking {
        val run = Run(List(3) { Answer.REKEY_PENDING }, floorBelow = listOf(true))
        assertEquals(NodeEpochWrite.Result.Busy, run.go())
        assertEquals(listOf("wait 1", "wait 2", "wait 3"), run.log.filter { it.startsWith("wait") })
    }

    @Test
    fun aNodeTheReReadNoLongerFindsIsGone() = runBlocking {
        assertEquals(NodeEpochWrite.Result.Gone, Run(listOf(Answer.STALE_EPOCH), rereadOk = false).go())
    }
}
