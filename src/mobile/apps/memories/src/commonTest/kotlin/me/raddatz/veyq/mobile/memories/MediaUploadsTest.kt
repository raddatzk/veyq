package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memories' uploads' settle, the core's (moved out of both phones, ADR 0161's Consequences): a medium in tells its node's gallery,
 * a batch's end reads the lists, and a picked file is handed back once the queue is done with it — done, cancelled or
 * abandoned, never while a retry may still read it — and every one when the tenant closes (Apple's Photos copies).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploadsTest {
    /** A job the test steers: it fails while [fail] holds, waits for [gate] where one is set. */
    private class Job(override val name: String, var fail: Boolean = false, var gate: CompletableDeferred<Unit>? = null) : UploadQueue.Job, MediaUpload.Adds {
        override val sizeBytes = 4L
        override var mediaId: String? = null
        override suspend fun run(progress: (Double) -> Unit) {
            progress(0.0)
            gate?.await()
            if (fail) throw UploadQueue.Failed("upload_failed")
            mediaId = "id-$name"
        }
        override fun isFinal(code: String) = true // no automatic retry: the test retries
    }

    private class Door : MediaUploads.Door {
        val jobs = LinkedHashMap<String, Job>()
        val left = mutableListOf<String>()
        var changes = 0
        override fun job(scopeId: String, nodeId: String, source: UploadSource): UploadQueue.Job = Job(source.name).also { jobs[source.name] = it }
        override fun musicJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken): UploadQueue.Job =
            Job(track.source.name).also { jobs[track.source.name] = it }
        override fun changed() {
            changes++
        }
        override fun left(source: UploadSource) {
            left += source.name
        }
    }

    private class Model(val uploads: MediaUploads, val queue: UploadQueue, val door: Door)

    private fun TestScope.model(): Model {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = UploadQueue(dispatcher)
        val door = Door()
        return Model(MediaUploads(queue, door, dispatcher), queue, door)
    }

    private fun source(name: String, type: String? = "image/jpeg") = UploadSource.of(name, 4, type, null) { _, n -> ByteArray(n) }

    private fun items(m: Model) = m.queue.state.value.batches.flatMap { it.items }

    @Test
    fun aFileIsNotHandedBackBeforeTheQueueShowsIt() = runTest {
        // a state the queue published before it took the batch in reaches the uploads after the batch is held
        val m = model()
        val queue = m.queue
        val door = m.door
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        queue.acknowledge("none") // publishes a state without the batch, ahead of its enqueue
        m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg")))
        door.jobs.getValue("a.jpg").gate = gate
        runCurrent()
        assertEquals(emptyList(), door.left, "not in the queue's state yet is not gone from it")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a.jpg"), door.left)
    }

    @Test
    fun theSameTenantOpenedAgainCarriesTheFilesOnNothingHandedBackOnTheWay() = runTest {
        val m = model()
        val gate = CompletableDeferred<Unit>()
        m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg"), source("b.jpg")))
        m.door.jobs.values.forEach { it.gate = gate }
        runCurrent()
        // the same tenant opened again: its successor follows the same queue
        val door = Door()
        val next = MediaUploads(m.queue, door, StandardTestDispatcher(testScheduler))
        m.uploads.handOver(next)
        runCurrent()
        assertEquals(emptyList(), m.door.left, "nothing handed back on the way: the queue still reads them")
        assertEquals(emptyList(), door.left)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a.jpg", "b.jpg"), door.left, "the successor hands each back once it is in")
        assertEquals(emptyList(), m.door.left)
    }

    // inbox #1065: an upload into a sub-bucket lands in the uploader's primary and a move files it — each medium handed to the
    // filer by its id once it is in, before its node is said in, so the gallery's read after it lists it where it was filed.
    // Levers: emit before the filer, hand it no id, or file a failed medium.
    @Test
    fun aFiledUploadHandsEachMediumInToTheFilerBeforeItsNodeIsSaidIn() = runTest {
        val m = model()
        val events = mutableListOf<String>()
        backgroundScope.launch { m.uploads.uploaded.collect { events += "in:$it" } }
        runCurrent()
        m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg"), source("b.jpg"), source("c.txt", "text/plain"))) { id -> events += "filed:$id" }
        m.door.jobs.getValue("b.jpg").fail = true
        runCurrent()
        assertEquals(listOf("filed:id-a.jpg", "in:m"), events, "the failed one is not filed; the text was never queued")
    }

    @Test
    fun aMediumInTellsItsNodesGalleryAndTheBatchsEndTheLists() = runTest {
        val m = model()
        val told = mutableListOf<String>()
        backgroundScope.launch { m.uploads.uploaded.collect { told += it } }
        runCurrent()
        assertEquals(2, m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg"), source("b.mp4", "video/mp4"))))
        runCurrent()
        assertEquals(listOf("m", "m"), told, "each medium in, its node")
        assertEquals(1, m.door.changes, "the batch's end: the lists once")
        assertEquals(listOf("a.jpg", "b.mp4"), m.door.left)
    }

    @Test
    fun aFailedOnesFileIsKeptForItsRetryAndHandedBackOnceItIsAbandoned() = runTest {
        val m = model()
        val told = mutableListOf<String>()
        backgroundScope.launch { m.uploads.uploaded.collect { told += it } }
        m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg")))
        runCurrent()
        m.uploads.upload("s", "m", "Ostsee", listOf(source("f.jpg")))
        m.door.jobs.getValue("f.jpg").fail = true
        runCurrent()
        val failed = items(m).first { it.name == "f.jpg" }
        assertEquals(UploadQueue.Status.FAILED, failed.status)
        assertEquals(listOf("a.jpg"), m.door.left, "a failed one's file is kept: a retry reads it")
        // retried and in
        m.door.jobs.getValue("f.jpg").fail = false
        m.queue.retry(failed.id)
        runCurrent()
        assertEquals(listOf("a.jpg", "f.jpg"), m.door.left)
        assertEquals(listOf("m", "m"), told)
        // failed again and the tray closed its batch: abandoned, handed back
        m.uploads.upload("s", "c1", "Day one", listOf(source("g.jpg")))
        m.door.jobs.getValue("g.jpg").fail = true
        runCurrent()
        assertEquals(listOf("a.jpg", "f.jpg"), m.door.left)
        m.queue.acknowledge(m.queue.state.value.batches.last().id)
        runCurrent()
        assertEquals(listOf("a.jpg", "f.jpg", "g.jpg"), m.door.left, "abandoned: no retry reads it any more")
    }

    @Test
    fun aCancelledOneIsHandedBackAndTheTenantsCloseHandsBackEveryOneHeld() = runTest {
        val m = model()
        val gate = CompletableDeferred<Unit>()
        m.uploads.upload("s", "m", "Ostsee", listOf(source("a.jpg"), source("b.jpg"), source("c.jpg")))
        m.door.jobs.values.forEach { it.gate = gate }
        runCurrent()
        m.queue.cancel(items(m).first { it.name == "b.jpg" }.id)
        runCurrent()
        assertEquals(listOf("b.jpg"), m.door.left, "cancelled: handed back")
        m.uploads.close()
        runCurrent()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), m.door.left.sorted(), "the tenant closed: every one held handed back")
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, m.door.left.size, "nothing twice")
    }

    @Test
    fun aFileThatIsNoMediumIsNotQueuedAndHandedBackAtOnce() = runTest {
        val m = model()
        assertEquals(1, m.uploads.upload("s", "m", "Ostsee", listOf(source("notes.txt", "text/plain"), source("IMG_1.HEIC", null))))
        assertEquals(listOf("notes.txt"), m.door.left)
        runCurrent()
        assertEquals(listOf("IMG_1.HEIC"), m.door.jobs.keys.toList())
    }
}
