package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tray signed out (inbox #1250, ADR 0145 decision 6): over the raw queue, with no application's model and no tenant,
 * a link guest's batch left running after a sign-out's [UploadQueue.clear] shows with its progress, a batch all in leaves
 * by itself after the moment, one with a failure stays until the tray closes it, and the tray's actions reach the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadsTrayTest {
    /** A job that runs until [go] completes, then fails with [fails] where one is given. */
    private class Steered(override val name: String, private val fails: String? = null) : UploadQueue.Job {
        override val sizeBytes = 10L
        val go = CompletableDeferred<Unit>()
        override suspend fun run(progress: (Double) -> Unit) {
            progress(0.5)
            go.await()
            fails?.let { throw UploadQueue.Failed(it) }
        }
    }

    private fun TestScope.queue() = UploadQueue(StandardTestDispatcher(testScheduler))

    @Test
    fun a_guests_batch_left_running_signed_out_shows_and_leaves_by_itself_once_all_in() = runTest {
        val q = queue()
        val tray = UploadsTray(q, StandardTestDispatcher(testScheduler))
        val account = Steered("mine.jpg")
        val guest = Steered("theirs.jpg")
        q.enqueue(listOf(account), "Home", null, null, null)
        q.enqueueSpared(listOf(guest), "Holiday", null, null, null)
        runCurrent()
        q.clear() // the sign-out: the account's batch goes, the guest's runs on
        advanceUntilIdle()
        assertEquals(listOf("Holiday"), tray.current.batches.map { it.label }, "the guest's batch, the account's gone")
        assertEquals(Status.UPLOADING, tray.current.batches.single().items.single().status)
        assertEquals(1, tray.current.active)

        guest.go.complete(Unit)
        runCurrent()
        assertTrue(tray.current.batches.single().finished)
        advanceTimeBy(UploadQueue.ACKNOWLEDGE_AFTER_MS - 10)
        runCurrent()
        assertEquals(1, tray.current.batches.size, "still shown just before the moment")
        advanceTimeBy(20)
        runCurrent()
        assertTrue(tray.current.batches.isEmpty(), "acknowledged by the tray itself, no tenant's model, no view")
        tray.close()
    }

    @Test
    fun a_failed_batch_stays_until_the_tray_closes_it_and_its_actions_reach_the_queue() = runTest {
        val q = queue()
        val tray = UploadsTray(q, StandardTestDispatcher(testScheduler))
        val failing = Steered("x.jpg", fails = UploadQueue.QUOTA_EXCEEDED)
        val waiting = Steered("y.jpg")
        q.enqueueSpared(listOf(failing, waiting), "Holiday", null, null, null)
        runCurrent()
        tray.cancel(tray.current.batches.single().items.single { it.name == "y.jpg" }.id)
        failing.go.complete(Unit)
        advanceUntilIdle()
        val batch = tray.current.batches.single()
        assertEquals(listOf(Status.FAILED, Status.CANCELLED), batch.items.map { it.status })
        advanceTimeBy(UploadQueue.ACKNOWLEDGE_AFTER_MS * 3)
        runCurrent()
        assertEquals(1, tray.current.batches.size, "a failure waits for the person")
        tray.acknowledge(batch.id)
        runCurrent()
        assertTrue(tray.current.batches.isEmpty())
        tray.close()
    }

    @Test
    fun a_closed_tray_acknowledges_nothing() = runTest {
        val q = queue()
        val tray = UploadsTray(q, StandardTestDispatcher(testScheduler))
        val job = Steered("a.jpg").apply { go.complete(Unit) }
        tray.close()
        q.enqueueSpared(listOf(job), "Holiday", null, null, null)
        advanceUntilIdle()
        advanceTimeBy(UploadQueue.ACKNOWLEDGE_AFTER_MS * 2)
        runCurrent()
        assertEquals(1, q.state.value.batches.size, "the queue runs on; only a tray acknowledges")
    }
}
