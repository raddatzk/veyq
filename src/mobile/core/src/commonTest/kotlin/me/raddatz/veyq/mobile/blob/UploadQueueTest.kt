package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's `core/blob/queue.test.ts` rules, on virtual time: order, progress, retry, final refusals, cancel, prepare ahead. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueueTest {
    /** A job the test steers: each run waits for [gate] unless it is open; it fails with the next of [failures] first. */
    private class Fake(override val name: String, val log: MutableList<String>, var failures: MutableList<String?> = mutableListOf()) : UploadQueue.Job {
        override val sizeBytes = 10L
        var gate: CompletableDeferred<Unit>? = null
        var hasPrepare = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var prepared = 0
        var abandoned = 0
        val finals = HashSet<String>()
        override val prepares get() = hasPrepare
        override suspend fun prepare() {
            log += "prepare $name"
            prepared++
            prepareGate?.await()
        }
        override suspend fun run(progress: (Double) -> Unit) {
            log += "run $name"
            if (hasPrepare) prepare()
            progress(0.0)
            gate?.await()
            failures.removeFirstOrNull()?.let { throw if (it == "boom") IllegalStateException(it) else UploadQueue.Failed(it) }
            progress(0.5)
            log += "done $name"
        }
        override fun isFinal(code: String) = code in finals
        override suspend fun abandon() {
            abandoned++
        }
    }

    private fun TestScope.queue() = UploadQueue(StandardTestDispatcher(testScheduler))

    private fun UploadQueue.item(name: String) = state.value.batches.flatMap { it.items }.single { it.name == name }

    @Test
    fun runs_jobs_one_at_a_time_in_enqueue_order_across_batches_and_settles_them_done() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val a = Fake("a", log).apply { gate = CompletableDeferred() }
        val b = Fake("b", log)
        val c = Fake("c", log)
        val settled = mutableListOf<String>()
        var finished: String? = null
        q.enqueue(listOf(a, b), "first", null, { settled += it.name }, { finished = it.label })
        q.enqueue(listOf(c), "second", null, null, null)
        advanceUntilIdle()
        assertEquals(listOf("run a"), log)
        assertEquals(Status.UPLOADING, q.item("a").status)
        assertEquals(Status.QUEUED, q.item("b").status)
        a.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("run a", "done a", "run b", "done b", "run c", "done c"), log)
        assertEquals(listOf("a", "b"), settled)
        assertEquals("first", finished)
        assertTrue(q.state.value.batches.all { b -> b.finished && b.items.all { it.status == Status.DONE && it.progress == 1.0 } })
        assertEquals(0, q.state.value.active)
    }

    @Test
    fun stays_preparing_until_the_first_progress() = runTest {
        val q = queue()
        val job = object : UploadQueue.Job {
            override val name = "x"
            override val sizeBytes = 1L
            val first = CompletableDeferred<Unit>()
            override suspend fun run(progress: (Double) -> Unit) {
                first.await()
                progress(0.25)
                awaitCancellation()
            }
        }
        q.enqueue(listOf(job), "l", null, null, null)
        advanceUntilIdle()
        assertEquals(Status.PREPARING, q.item("x").status)
        job.first.complete(Unit)
        advanceUntilIdle()
        assertEquals(Status.UPLOADING, q.item("x").status)
        assertEquals(0.25, q.item("x").progress)
        q.clear()
        advanceUntilIdle()
    }

    @Test
    fun retries_once_on_a_transient_failure_and_fails_after_the_second() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val once = Fake("once", log, mutableListOf("boom"))
        val twice = Fake("twice", log, mutableListOf("net", "net"))
        q.enqueue(listOf(once, twice), "l", null, null, null)
        advanceUntilIdle()
        assertEquals(Status.DONE, q.item("once").status)
        assertEquals(2, q.item("once").attempts)
        assertEquals(0, once.abandoned)
        val t = q.item("twice")
        assertEquals(Status.FAILED, t.status)
        assertEquals("net", t.error)
        assertTrue(t.retryable)
        assertEquals(1, twice.abandoned) // once given up, never between the retry's attempts
        q.retry(t.id)
        advanceUntilIdle()
        assertEquals(Status.DONE, q.item("twice").status)
        assertEquals(1, q.item("twice").attempts)
    }

    @Test
    fun a_refusal_no_retry_helps_fails_at_once_and_offers_none() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val quota = Fake("quota", log, mutableListOf(UploadQueue.QUOTA_EXCEEDED))
        val off = Fake("off", log, mutableListOf(UploadQueue.APPLICATION_OFF))
        val taken = Fake("taken", log, mutableListOf("exists")).apply { finals += "exists" }
        val after = Fake("after", log)
        q.enqueue(listOf(quota, off, taken, after), "l", null, null, null)
        advanceUntilIdle()
        for (n in listOf("quota", "off", "taken")) {
            val i = q.item(n)
            assertEquals(Status.FAILED, i.status, n)
            assertEquals(1, i.attempts, n)
            assertFalse(i.retryable, n)
        }
        assertTrue(q.item("taken").final)
        assertEquals(Status.DONE, q.item("after").status) // a hard failure leaves the rest of its batch running
    }

    @Test
    fun cancel_settles_a_running_job_cancelled_and_a_queued_one_without_running_it() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val a = Fake("a", log).apply { gate = CompletableDeferred() }
        val b = Fake("b", log)
        var finished = false
        q.enqueue(listOf(a, b), "l", null, null) { finished = true }
        advanceUntilIdle()
        q.cancel(q.item("b").id)
        q.cancel(q.item("a").id)
        advanceUntilIdle()
        assertEquals(Status.CANCELLED, q.item("a").status)
        assertNull(q.item("a").error)
        assertEquals(Status.CANCELLED, q.item("b").status)
        assertEquals(listOf("run a"), log)
        assertEquals(1, a.abandoned)
        assertEquals(1, b.abandoned)
        assertTrue(finished)
    }

    @Test
    fun the_next_job_is_prepared_ahead_once_the_running_one_is_on_the_wire_and_runs_on_what_it_prepared() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val a = Fake("a", log).apply { gate = CompletableDeferred() }
        val plain = Fake("plain", log)
        val b = Fake("b", log).apply { hasPrepare = true; prepareGate = CompletableDeferred() }
        val c = Fake("c", log).apply { hasPrepare = true }
        q.enqueue(listOf(a, plain, b, c), "l", null, null, null)
        advanceUntilIdle()
        // a is on the wire: b (the next with an on-device stage, past plain) prepares; c waits for b's turn
        assertEquals(listOf("run a", "prepare b"), log)
        assertEquals(Status.PREPARING, q.item("b").status)
        assertEquals(Status.QUEUED, q.item("c").status)
        b.prepareGate!!.complete(Unit)
        a.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(q.state.value.batches.single().items.all { it.status == Status.DONE })
        assertEquals(2, b.prepared) // ahead, then awaited again by its run (the job keeps what it prepared)
        assertEquals(listOf("run a", "prepare b", "done a", "run plain", "done plain", "run b", "prepare b"), log.take(7))
    }

    @Test
    fun cancelling_a_job_prepared_ahead_settles_it_at_once_and_abandons_it_once() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val a = Fake("a", log).apply { gate = CompletableDeferred() }
        val b = Fake("b", log).apply { hasPrepare = true; prepareGate = CompletableDeferred() }
        q.enqueue(listOf(a, b), "l", null, null, null)
        advanceUntilIdle()
        q.cancel(q.item("b").id)
        advanceUntilIdle()
        assertEquals(Status.CANCELLED, q.item("b").status)
        assertEquals(1, b.abandoned)
        a.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(Status.DONE, q.item("a").status)
        assertFalse("run b" in log)
    }

    @Test
    fun a_failure_of_the_running_job_runs_its_retry_before_the_prepared_one() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        val a = Fake("a", log, mutableListOf("net")).apply { gate = CompletableDeferred() }
        val b = Fake("b", log).apply { hasPrepare = true }
        q.enqueue(listOf(a, b), "l", null, null, null)
        advanceUntilIdle()
        a.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("run a", "prepare b", "run a", "done a", "run b"), log.take(5))
    }

    @Test
    fun remove_drops_a_settled_item_and_acknowledge_hides_a_finished_batch() = runTest {
        val q = queue()
        val log = mutableListOf<String>()
        q.enqueue(listOf(Fake("a", log), Fake("b", log)), "one", null, null, null)
        q.enqueue(listOf(Fake("c", log)), "two", null, null, null)
        advanceUntilIdle()
        q.remove(q.item("a").id)
        advanceUntilIdle()
        assertEquals(listOf("b"), q.state.value.batches.first().items.map { it.name })
        q.acknowledge(q.state.value.batches.first().id)
        advanceUntilIdle()
        assertEquals(listOf("two"), q.state.value.batches.map { it.label })
    }
}
