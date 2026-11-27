package me.raddatz.veyq.mobile.session.tasks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.ClaimedJob
import me.raddatz.veyq.mobile.api.models.CompleteRequest
import me.raddatz.veyq.mobile.api.models.GrantDeliveryTarget
import me.raddatz.veyq.mobile.session.Anchor
import me.raddatz.veyq.mobile.session.TrustStateRefused
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.RotationRefused
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The task queue's pass (inbox #966) is the web's `grantDeliveryWorker` and `scopeRotationWorker`, case for case, over a
 * queue and a tenant the test plays: what is claimed where, what is delivered, declined, failed or left leased, and the
 * order the rotation reads its directory in. Bitten: each load-bearing rule removed in turn goes red (the CR's log).
 */
class TaskPassTest {
    private val target = GrantDeliveryTarget("u-target", "m-1", "pk", "head")

    private fun delivery(id: String, scope: String = "g1") = ClaimedJob(id, scope, CapabilityTags.GRANT_DELIVERY, 1, grantDelivery = target)
    private fun rotation(id: String, scope: String = "g1") = ClaimedJob(id, scope, CapabilityTags.SCOPE_ROTATION, 1)

    private class Wire(jobs: Map<String, List<ClaimedJob>>) : TaskWire {
        val queued = jobs.mapValues { ArrayDeque(it.value) }.toMutableMap()
        val log = ArrayList<String>()
        val claims = ArrayList<Pair<List<String>, String>>()
        var refuseComplete: (String) -> TaskRefused? = { null }

        override suspend fun claim(tags: List<String>, scopeId: String): ClaimedJob? {
            claims += tags to scopeId
            return queued[scopeId]?.removeFirstOrNull()?.also { log += "claim ${it.jobId}" }
        }
        override suspend fun heartbeat(jobId: String) { log += "heartbeat $jobId" }
        override suspend fun complete(jobId: String, request: CompleteRequest) {
            refuseComplete(jobId)?.let { log += "refused $jobId ${it.code}"; throw it }
            log += "complete $jobId epoch=${request.epoch} sealed=${request.sealedResult}"
        }
        override suspend fun fail(jobId: String, reason: String) { log += "fail $jobId $reason" }
        override suspend fun decline(jobId: String, reason: String) { log += "decline $jobId $reason" }
    }

    private class Work(private val wire: Wire, val scopes: List<Pair<String, Boolean>> = listOf("g1" to true)) : TaskWork {
        var anchor: () -> Anchor = { Anchor.MATCH }
        var rekeying = false
        var rekeyDone = true
        var rotateFails: Exception? = null
        val keys = ArrayList<ByteArray>()

        override fun sharedScopes() = scopes
        override fun keyOf(scopeId: String) = HeldKey(ByteArray(32) { 7 }, 3, "GROUP").also { keys += it.key }
        override suspend fun anchor(target: GrantDeliveryTarget): Anchor = anchor.invoke()
        override fun seal(key: HeldKey, scopeId: String, target: GrantDeliveryTarget) =
            VeyqAccount.SelfGrant("wrapped-${key.key[0]}", "sig", "digest")
        override suspend fun rekeying(scopeId: String) = rekeying
        override suspend fun rekey(scopeId: String): Boolean { wire.log += "rekey $scopeId"; return rekeyDone }
        override suspend fun rotate(scopeId: String) {
            wire.log += "members+commit $scopeId"
            rotateFails?.let { throw it }
        }
    }

    @Test
    fun aDeliveryIsSealedToTheTargetAtTheHeldEpochAndTheKeyCopyZeroed() = runTest {
        val wire = Wire(mapOf("g1" to listOf(delivery("j1"), delivery("j2"))))
        val work = Work(wire)
        assertEquals(TaskOutcome(2, 0), TaskPass(wire, work).run())
        assertEquals(listOf("claim j1", "complete j1 epoch=3 sealed=wrapped-7", "claim j2", "complete j2 epoch=3 sealed=wrapped-7"), wire.log)
        assertTrue(work.keys.all { k -> k.all { it == 0.toByte() } }, "every key copy is zeroed after its seal")
    }

    @Test
    fun anOwnedScopeIsClaimedForBothTagsAMembersForDeliveryAloneAndAPersonalNever() = runTest {
        val wire = Wire(emptyMap())
        TaskPass(wire, Work(wire, listOf("t" to false, "g1" to true))).run()
        assertEquals(
            listOf(listOf(CapabilityTags.GRANT_DELIVERY) to "t", listOf(CapabilityTags.GRANT_DELIVERY, CapabilityTags.SCOPE_ROTATION) to "g1"),
            wire.claims,
        )
    }

    @Test
    fun anAnomalyIsDeclinedAndARefusedPinStorePausesTheScopeDecliningNothing() = runTest {
        val wire = Wire(mapOf("g1" to listOf(delivery("j1"), delivery("j2"))))
        val work = Work(wire).apply { anchor = { Anchor.ANOMALY } }
        TaskPass(wire, work).run()
        assertEquals(listOf("claim j1", "decline j1 identity_unanchored", "claim j2", "decline j2 identity_unanchored"), wire.log)

        val paused = Wire(mapOf("g1" to listOf(delivery("j1"), delivery("j2"))))
        val refused = Work(paused).apply { anchor = { throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE) } }
        assertEquals(TaskOutcome(0, 0), TaskPass(paused, refused).run())
        assertEquals(listOf("claim j1"), paused.log, "nothing declined, sealed or claimed after the refusal")
    }

    @Test
    fun aStaleEpochOrARetiredRecipientKeyStopsTheScope() = runTest {
        for (code in listOf("stale_epoch", "recipient_key_not_current")) {
            val wire = Wire(mapOf("g1" to listOf(delivery("j1"), delivery("j2"))))
            wire.refuseComplete = { TaskRefused(409, code) }
            assertEquals(TaskOutcome(0, 0), TaskPass(wire, Work(wire)).run())
            assertEquals(listOf("claim j1", "refused j1 $code"), wire.log, code)
        }
    }

    @Test
    fun aJobOfAnotherTagIsFailedBack() = runTest {
        val wire = Wire(mapOf("t" to listOf(rotation("j1", "t"), ClaimedJob("j2", "t", "ffffffff", 1))))
        TaskPass(wire, Work(wire, listOf("t" to false))).run()
        assertEquals(listOf("claim j1", "fail j1 not-scope-rotation", "claim j2", "fail j2 not-grant-delivery"), wire.log)
    }

    @Test
    fun aQueuedRotationReadsItsDirectoryAfterTheClaimCommitsReKeysAndCompletes() = runTest {
        val wire = Wire(mapOf("g1" to listOf(rotation("r1"))))
        assertEquals(TaskOutcome(0, 1), TaskPass(wire, Work(wire)).run())
        assertEquals(listOf("claim r1", "members+commit g1", "rekey g1", "complete r1 epoch=null sealed=null"), wire.log)
    }

    @Test
    fun aRotationAnEarlierLeaseCommittedIsReKeyedAndCompletedWithoutASecondCommit() = runTest {
        val wire = Wire(mapOf("g1" to listOf(rotation("r1"))))
        TaskPass(wire, Work(wire).apply { rekeying = true }).run()
        assertEquals(listOf("claim r1", "rekey g1", "complete r1 epoch=null sealed=null"), wire.log)

        // that lease committed nothing after all: this one commits its own
        val uncommitted = Wire(mapOf("g1" to listOf(rotation("r1"))))
        var first = true
        uncommitted.refuseComplete = { if (first) TaskRefused(409, "rotation_not_committed").also { first = false } else null }
        TaskPass(uncommitted, Work(uncommitted).apply { rekeying = true }).run()
        assertEquals(
            listOf("claim r1", "rekey g1", "refused r1 rotation_not_committed", "members+commit g1", "rekey g1", "complete r1 epoch=null sealed=null"),
            uncommitted.log,
        )
    }

    @Test
    fun aReKeyThatDoesNotFinishKeepsTheLease() = runTest {
        val wire = Wire(mapOf("g1" to listOf(rotation("r1"))))
        assertEquals(TaskOutcome(0, 0), TaskPass(wire, Work(wire).apply { rekeyDone = false }).run())
        assertEquals(listOf("claim r1", "members+commit g1", "rekey g1"), wire.log, "neither completed, failed nor declined")
    }

    @Test
    fun aRefusalAboutThisWorkerIsDeclinedAnyOtherFailsTheJob() = runTest {
        val cases = listOf(
            RotationRefused(409, "stale_epoch") to "decline r1 stale_epoch",
            RotationRefused(403, "not_scope_owner") to "decline r1 not_scope_owner",
            IllegalStateException("cannot open scope to rotate") to "fail r1 rotate-failed",
            RotationRefused(500, null) to "fail r1 rotate-failed",
        )
        for ((e, said) in cases) {
            val wire = Wire(mapOf("g1" to listOf(rotation("r1"))))
            TaskPass(wire, Work(wire).apply { rotateFails = e }).run()
            assertEquals(listOf("claim r1", "members+commit g1", said), wire.log, e.toString())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLongRotationRenewsItsLease() = runTest {
        val wire = Wire(mapOf("g1" to listOf(rotation("r1"))))
        val slow = object : TaskWork by Work(wire) {
            override suspend fun rotate(scopeId: String) {
                wire.log += "members+commit $scopeId"
                kotlinx.coroutines.delay(65.seconds)
            }
        }
        TaskPass(wire, slow).run()
        assertEquals(listOf("claim r1", "members+commit g1", "heartbeat r1", "heartbeat r1", "rekey g1", "complete r1 epoch=null sealed=null"), wire.log)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theQueueRunsAPassNowAndEveryPeriodOnlyWhileItWorksAndNoneOnceStopped() = runTest {
        var works = true
        var passes = 0
        val queue = TaskQueue({ works }, 45.seconds, backgroundScope.coroutineContext) { passes++; TaskOutcome(0, 0) }
        // the queue's scope a child of the test's background scope: a stop that does not stop fails, never hangs, the test
        try {
        queue.start()
        queue.start()
        runCurrent()
        assertEquals(1, passes, "a pass at once, one loop however often started")
        advanceTimeBy(45.seconds)
        runCurrent()
        assertEquals(2, passes)
        works = false
        advanceTimeBy(90.seconds)
        runCurrent()
        assertEquals(2, passes, "offline, locked or closed: no pass")
        works = true
        queue.stop()
        assertFalse(queue.looping)
        advanceTimeBy(90.seconds)
        runCurrent()
        assertEquals(2, passes, "the tenant closed: no pass")
        } finally {
            queue.stop()
        }
    }
}
