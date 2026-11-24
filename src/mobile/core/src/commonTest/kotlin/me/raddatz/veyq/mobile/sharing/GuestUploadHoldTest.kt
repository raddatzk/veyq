package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadsUnderway
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guest's uploads go on with the link left (inbox #1016, ADR 0145 § A guest's uploads): the link's keys and client are
 * held in memory past [GuestView.close] exactly as long as one of the view's jobs waits or runs — then zeroed and closed,
 * whether the files got in, failed or were cancelled —, and a sign-out's [UploadQueue.clear] spares a guest's batch.
 */
class GuestUploadHoldTest {
    private val p = testPrimitives

    /** A job that runs until [go] is completed, recording whether the link's key was still whole while it ran. */
    private class Held(private val key: ByteArray, private val fails: String? = null) : UploadQueue.Job {
        override val name = "photo.jpg"
        override val sizeBytes = 10L
        val started = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        var keyWholeAtEnd: Boolean? = null
        override suspend fun run(progress: (Double) -> Unit) {
            progress(0.1)
            started.complete(Unit)
            go.await()
            keyWholeAtEnd = key.any { it != 0.toByte() }
            fails?.let { throw UploadQueue.Failed(it) }
            progress(1.0)
        }
    }

    private suspend fun opened(queue: UploadQueue): GuestView {
        val share = PlayedShare(p, role = "READ_WRITE")
        val v = GuestView.over(
            (ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), MemoryDeviceStore(), MemoryGuestSessions(),
            queue = queue, doors = { _, _ -> GuestLevel.door },
        )
        v.load()
        assertNotNull(v.access, "the link did not open")
        return v
    }

    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private suspend fun until(what: String, check: () -> Boolean) = real {
        while (!check()) delay(5)
        assertTrue(check(), what)
    }

    private fun zeroed(b: ByteArray) = b.all { it == 0.toByte() }

    @Test
    fun aLeftLinksKeysAreHeldWhileItsUploadRunsAndZeroedOnceItIsIn() = runTest {
        val queue = UploadQueue()
        val v = opened(queue)
        val kShare = v.gates.head!!.kShare
        val treeKeys = v.access!!.tree!!.nodes.map { it.key }
        val job = Held(kShare)
        assertTrue(v.access!!.enqueue(listOf(job), "Summer"))
        real { job.started.await() }
        v.close()
        assertNull(v.page.value, "the left link's page stayed")
        assertFalse(zeroed(kShare), "the link's key was zeroed under its running upload")
        assertTrue(v.holding, "the left link holds nothing for its running upload")
        assertNotNull(UploadsUnderway.uploads.value[queue.underwayKey], "the guest's upload holds no process")
        job.go.complete(Unit)
        real { queue.state.first { s -> s.batches.single().items.single().status == UploadQueue.Status.DONE } }
        assertEquals(true, job.keyWholeAtEnd, "the upload ran on without the link's key")
        until("the link's key outlived its last upload") { zeroed(kShare) }
        until("the tree's keys outlived the last upload") { treeKeys.all(::zeroed) }
        assertFalse(v.holding)
        assertNull(v.gates.guest.value, "the guest's session stayed held after the release")
    }

    @Test
    fun aCutOffUploadDropsTheKeysAndOffersNoRetryAfter() = runTest {
        // cancelled from the tray
        val queue = UploadQueue()
        val v = opened(queue)
        val kShare = v.gates.head!!.kShare
        val job = Held(kShare)
        v.access!!.enqueue(listOf(job), "Summer")
        real { job.started.await() }
        v.close()
        assertFalse(zeroed(kShare))
        queue.cancel(queue.state.value.batches.single().items.single().id)
        until("a cancelled upload left the link's key held") { zeroed(kShare) }

        // failed: a transient failure is retried once by the queue (the keys still held), then fails; released, it offers no retry
        val q2 = UploadQueue()
        val w = opened(q2)
        val k2 = w.gates.head!!.kShare
        val failing = object : UploadQueue.Job {
            override val name = "video.mp4"
            override val sizeBytes = 10L
            var runs = 0
            override suspend fun run(progress: (Double) -> Unit) {
                runs++
                throw IllegalStateException("the network went")
            }
        }
        val blocker = Held(k2)
        w.access!!.enqueue(listOf(blocker, failing), "Summer")
        real { blocker.started.await() }
        w.close()
        blocker.go.complete(Unit)
        real { q2.state.first { s -> s.batches.single().items.last().status == UploadQueue.Status.FAILED } }
        assertEquals(2, failing.runs, "the automatic retry did not run on the held keys")
        until("a failed upload left the link's key held") { zeroed(k2) }
        until("a failed upload of a released link still offers a retry") { !q2.state.value.batches.single().items.last().retryable }
    }

    @Test
    fun aLinkWithNothingUnderWayLetsGoAtOnce() = runTest {
        val v = opened(UploadQueue())
        val kShare = v.gates.head!!.kShare
        v.close()
        assertTrue(zeroed(kShare), "a link with no upload kept its key past its close")
        assertFalse(v.holding)
    }

    @Test
    fun aSignOutsClearSparesAGuestsUploads() = runTest {
        val queue = UploadQueue()
        val v = opened(queue)
        val kShare = v.gates.head!!.kShare
        val guest = Held(kShare)
        val account = Held(ByteArray(1) { 1 })
        v.access!!.enqueue(listOf(guest), "Summer")
        real { guest.started.await() }
        queue.enqueue(listOf(account), "Home", null, null, null)
        real { queue.state.first { s -> s.batches.size == 2 } }
        v.close()
        queue.clear()
        real { queue.state.first { s -> s.batches.size == 1 } }
        assertEquals("Summer", queue.state.value.batches.single().label, "the sign-out cancelled the guest's upload or kept the account's")
        assertFalse(zeroed(kShare))
        guest.go.complete(Unit)
        until("the guest's key outlived its upload after the clear") { zeroed(kShare) }
        assertEquals(true, guest.keyWholeAtEnd)
    }
}
