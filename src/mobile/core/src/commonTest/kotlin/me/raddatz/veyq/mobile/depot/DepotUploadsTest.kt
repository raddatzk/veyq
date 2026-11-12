package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Depot's uploads both phones drew (ADR 0129 decision 2, the Depot CR's D2 and D9) — over a level of files the test plays:
 * the settle's replace offer and its action, a replace expected over the content shown when it was chosen, a folder's
 * preparing and outcome in Depot's words, and the tray's acknowledge on virtual time. Also `DepotFiles.offerFor` and
 * `collision` (R2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepotUploadsTest {
    private fun source(name: String, size: Long = 4) = UploadSource.of(name, size, "text/plain", null) { _, n -> ByteArray(n) }

    private fun file(nodeId: String, blobId: String?) = DepotFiles.File("s", nodeId, blobId, 1, 40, null, 4, "text/plain", nodeId, null, "user:u1")

    /** A level of files the test plays: each name's node and its content's blob, and what reached it. */
    private class Level {
        val names = linkedMapOf<String, String>() // name -> node id
        val blobs = HashMap<String, String?>() // node id -> blob
        val sent = mutableListOf<String>()
        var changes = 0
        private var next = 0

        fun put(name: String, blob: String?): String = "n${++next}".also { names[name] = it; blobs[it] = blob }

        fun upload(name: String): DepotFiles.Uploaded {
            names[name]?.let { return DepotFiles.Uploaded(DepotFiles.Outcome.EXISTS, existing = it) }
            sent += "upload:$name"
            return DepotFiles.Uploaded(DepotFiles.Outcome.DONE, put(name, "b-$name"))
        }

        fun replace(nodeId: String, expected: String?, name: String): DepotFiles.Uploaded {
            val now = blobs[nodeId]
            if (now != expected) return DepotFiles.Uploaded(DepotFiles.Outcome.STALE, nodeId, currentBlobId = now)
            sent += "replace:$nodeId over $expected"
            blobs[nodeId] = "r-$name-${++next}"
            return DepotFiles.Uploaded(DepotFiles.Outcome.DONE, nodeId)
        }

        fun listed(nodeId: String) = blobs[nodeId]?.let { DepotFiles.File("s", nodeId, it, 1, 40, null, 4, "text/plain", null, null, "user:u1") }

        fun replaceJob(file: DepotFiles.File, source: UploadSource, expected: String?) =
            DepotFiles.ReplaceJob(file, source, expected, source.name) { s, e, _ -> replace(file.nodeId, e, s.name) }
    }

    private class Door(val level: Level) : DepotUploads.Door {
        var prepareGate: CompletableDeferred<Unit>? = null
        var report: (String, List<FolderUpload.Entry>) -> FolderUpload.Report = { name, _ -> FolderUpload.Report(FolderUpload.Outcome.NOTHING, name) }
        var prepared: List<FolderUpload.Entry>? = null
        override fun uploadJob(scopeId: String, parentId: String?, source: UploadSource, name: String) =
            DepotFiles.UploadJob(scopeId, parentId, source, name, { n, _, _, _ -> level.upload(n) })
        override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<FolderUpload.Entry>): FolderUpload.Report {
            prepareGate?.await()
            prepared = entries
            return report(rootName, entries)
        }
        override suspend fun file(scopeId: String, nodeId: String) = level.listed(nodeId)
        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?) = level.replaceJob(file, source, expectedBlobId)
        override fun changed() {
            level.changes++
        }
    }

    private class Model(val uploads: DepotUploads, val queue: UploadQueue, val door: Door)

    private fun TestScope.model(level: Level = Level()): Model {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = UploadQueue(dispatcher)
        val door = Door(level)
        return Model(DepotUploads(queue, door, dispatcher), queue, door)
    }

    private fun items(m: Model) = m.uploads.current.queue.batches.flatMap { it.items }

    // ---- DepotFiles.offerFor and collision (R2) ----

    private fun item(job: UploadQueue.Job, status: UploadQueue.Status, error: String?) =
        UploadQueue.Item("i", job.name, 4, status, 0.0, error, error in DepotFiles.Codes.FINAL, 1, job)

    @Test
    fun offerForOffersAFilesReplaceOnItsNameAndReplaceAnywayOnAStaleOneNothingElse() = runTest {
        val level = Level()
        val taken = level.put("a.txt", "b1")
        val up = DepotFiles.UploadJob("s", null, source("a.txt"), "a.txt", { n, _, _, _ -> level.upload(n) })
        kotlin.runCatching { up.run {} }
        val offer = assertNotNull(DepotFiles.offerFor(item(up, UploadQueue.Status.FAILED, DepotFiles.Codes.EXISTS)) { if (it == taken) "b1" else null })
        assertEquals(Notices.replaceOffered("a.txt"), offer.notice)
        assertEquals(Msg.DEPOT_REPLACE_TITLE, offer.action)
        assertEquals(taken, offer.nodeId)
        assertEquals("b1", offer.expectedBlobId, "the colliding file's content as listed")
        assertSame(up.picked, offer.source)

        val stale = DepotFiles.ReplaceJob(file(taken, "b1"), source("a.txt"), "b0", "a.txt") { _, _, _ -> DepotFiles.Uploaded(DepotFiles.Outcome.STALE, taken, currentBlobId = "b1") }
        kotlin.runCatching { stale.run {} }
        val anyway = assertNotNull(DepotFiles.offerFor(item(stale, UploadQueue.Status.FAILED, DepotFiles.Codes.STALE)) { null })
        assertEquals(Notices.replaceStale("a.txt"), anyway.notice)
        assertEquals(Msg.DEPOT_REPLACE_ANYWAY, anyway.action)
        assertEquals("b1", anyway.expectedBlobId, "anyway is over theirs")

        // nothing else offers: a job done, another refusal, a replace refused for another reason
        assertNull(DepotFiles.offerFor(item(up, UploadQueue.Status.DONE, null)) { "b1" })
        assertNull(DepotFiles.offerFor(item(up, UploadQueue.Status.FAILED, DepotFiles.Codes.NAME_TAKEN)) { "b1" })
        assertNull(DepotFiles.offerFor(item(stale, UploadQueue.Status.FAILED, DepotFiles.Codes.EXISTS)) { "b1" })
        assertNull(DepotFiles.offerFor(item(up, UploadQueue.Status.FAILED, DepotFiles.Codes.STALE)) { "b1" })
    }

    private fun node(id: String, parent: String?, kind: String, name: String) = OpenNode("s", id, parent, kind, name, ByteArray(0))

    @Test
    fun aCollisionIsASiblingWhoseNameFoldsAsTheFilesDoes() {
        val nodes = listOf(
            node("f1", "p", DepotFiles.FILE, "Notes.txt"),
            node("d1", "p", "FOLDER", "Report"),
            node("f2", "q", DepotFiles.FILE, "elsewhere.txt"),
        )
        assertEquals("f1", DepotFiles.collision(nodes, "p", "notes.TXT", AppKinds::tokenInput)?.id, "a case apart is the same file")
        assertEquals("d1", DepotFiles.collision(nodes, "p", "report", AppKinds::tokenInput)?.id, "a folder of the name holds it too")
        assertNull(DepotFiles.collision(nodes, "p", "elsewhere.txt", AppKinds::tokenInput), "a file of another level is no sibling")
        assertNull(DepotFiles.collision(nodes, null, "Notes.txt", AppKinds::tokenInput), "the space's top is its own level")
        assertNull(DepotFiles.collision(nodes, "p", "new.txt", AppKinds::tokenInput))
    }

    // ---- the settle ----

    @Test
    fun aNameAFileHoldsIsOfferedForReplaceOnceAndTakenOverTheFileAsListedNow() = runTest {
        val level = Level()
        val existing = level.put("a.txt", "b1")
        val m = model(level)
        assertEquals(2, m.uploads.upload("s", null, "Home", listOf(source("a.txt"), source("b.txt"))))
        runCurrent() // not past the tray's moment: the batch is still shown
        assertEquals(listOf("upload:b.txt"), level.sent)
        val said = assertNotNull(m.uploads.current.said)
        assertEquals(listOf(Notices.replaceOffered("a.txt")), said.notices)
        assertEquals(Msg.DEPOT_REPLACE_TITLE, said.action)
        assertEquals(listOf("b.txt"), items(m).map { it.name }, "the offered item left the tray, its notice says it")
        assertTrue(level.changes >= 2, "the lists were read anew after each settle")

        // meanwhile nothing changed: the replace goes over the content listed when it was offered
        m.uploads.take(said)
        advanceUntilIdle()
        assertNull(m.uploads.current.said)
        assertEquals("replace:$existing over b1", level.sent.last())
    }

    @Test
    fun anOfferNotTakenIsSeenAndTheNextWaitsItsTurn() = runTest {
        val level = Level()
        level.put("a.txt", "b1")
        level.put("b.txt", "b2")
        val m = model(level)
        m.uploads.upload("s", null, "Home", listOf(source("a.txt"), source("b.txt")))
        advanceUntilIdle()
        val first = assertNotNull(m.uploads.current.said)
        assertEquals(Notices.replaceOffered("a.txt"), first.notices.single())
        m.uploads.seen(first.id)
        val second = assertNotNull(m.uploads.current.said)
        assertEquals(Notices.replaceOffered("b.txt"), second.notices.single())
        m.uploads.seen(second.id)
        assertNull(m.uploads.current.said)
        advanceUntilIdle()
        assertTrue(level.sent.isEmpty(), "nothing replaced without its action")
    }

    // ---- a replace, expected over the content shown when it was chosen (D9) ----

    private class FileDoor(val level: Level, val nodeId: String) : FileModel.Door {
        override suspend fun file(node: NodeAt) = level.listed(nodeId)
        override fun inIdentityFolder(node: NodeAt) = false
        override suspend fun read(file: DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) = sink("hello".encodeToByteArray())
        override fun reader(file: DepotFiles.File) = RangeReader(5, 1) { _, _, sink -> sink("hello".encodeToByteArray()) }
        override suspend fun uploader(file: DepotFiles.File): Uploaders.Uploader? = null
        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?) = level.replaceJob(file, source, expectedBlobId)
    }

    @Test
    fun aReplaceIsExpectedOverTheContentShownWhenChosenSoAPeersReplaceInBetweenIsAnsweredStale() = runTest {
        val level = Level()
        val id = level.put("a.txt", "b1")
        val m = model(level)
        val page = FileModel(NodeAt("s", id, DepotFiles.FILE, "a.txt"), FileDoor(level, id), shares = false, scope = backgroundScope)
        runCurrent()
        assertFalse(m.uploads.replace(page, source("mine.txt")), "nothing chosen: nothing replaced")
        assertTrue(page.chooseReplace())
        // a peer replaces it while the picker is open, and the page reads it anew
        level.blobs[id] = "peer"
        page.reload()
        runCurrent()
        assertTrue(m.uploads.replace(page, source("mine.txt")))
        advanceUntilIdle()
        assertEquals("peer", level.blobs[id], "nothing overwritten")
        assertTrue(level.sent.isEmpty())
        val said = assertNotNull(m.uploads.current.said)
        assertEquals(listOf(Notices.replaceStale("mine.txt")), said.notices)
        assertEquals(Msg.DEPOT_REPLACE_ANYWAY, said.action)
        // "Replace anyway": over theirs
        m.uploads.take(said)
        advanceUntilIdle()
        assertEquals("replace:$id over peer", level.sent.single())
        assertNull(m.uploads.current.said)
    }

    // ---- a folder ----

    private fun found(path: String, kind: FolderUpload.Kind = FolderUpload.Kind.FILE) =
        FolderUpload.Found(path, kind, if (kind == FolderUpload.Kind.FILE) source(path.substringAfterLast('/')) else null)

    @Test
    fun aFolderIsPreparedUnderItsOwnNameAndItsOutcomeSaidInDepotsWords() = runTest {
        val m = model()
        m.door.prepareGate = CompletableDeferred()
        m.door.report = { name, entries ->
            FolderUpload.Report(FolderUpload.Outcome.READY, name, 8, "root", failedFolders = listOf("$name/sub"),
                jobs = entries.map { e -> DepotFiles.UploadJob("s", "root", e.source, e.path, { n, _, _, _ -> m.door.level.upload(n) }) })
        }
        val walk = listOf(found("a.txt"), found(".hidden"), found("sub", FolderUpload.Kind.DIRECTORY), found("ln", FolderUpload.Kind.LINK), found("locked", FolderUpload.Kind.UNREADABLE))
        launch { m.uploads.uploadFolder("s", null, null, "/Users/me/Trip", walk) }
        runCurrent()
        assertEquals("Trip", m.uploads.current.preparing, "the directory's own name, never a word of the app's")
        m.door.prepareGate!!.complete(Unit)
        runCurrent()
        assertNull(m.uploads.current.preparing)
        assertEquals(listOf("a.txt", ".hidden"), m.door.prepared?.map { it.path })
        val said = assertNotNull(m.uploads.current.said)
        assertEquals(
            listOf(Notice(Msg.DEPOT_FOLDER_LINK_SKIPPED, "ln"), Notice(Msg.DEPOT_FOLDER_UNREADABLE, "locked"), Notice(Msg.DEPOT_FOLDER_PART_FAILED, "Trip/sub")),
            said.notices,
        )
        assertNull(said.offer)
        assertEquals(listOf("upload:a.txt", "upload:.hidden"), m.door.level.sent)
        assertEquals(listOf("Trip"), m.queue.state.value.batches.map { it.label }, "one batch under the folder's name")
    }

    @Test
    fun aFolderThatIsNotMadeOrHasNoRoomSaysSoAndOneThatFailsSaysItsOwnWordsNeverAShareFailure() = runTest {
        val m = model()
        m.door.report = { name, _ -> FolderUpload.Report(FolderUpload.Outcome.NO_ROOM, name, 1234) }
        m.uploads.uploadFolder("s", null, "Trip", "content://tree/primary%3ATrip", listOf(found("a")))
        assertEquals(listOf(Notice(Msg.DEPOT_FOLDER_NO_ROOM, "Trip", Bytes(1234))), m.uploads.current.said?.notices)
        m.uploads.seen(m.uploads.current.said!!.id)

        m.door.report = { name, _ -> FolderUpload.Report(FolderUpload.Outcome.ROOT_FAILED, name) }
        m.uploads.uploadFolder("s", null, null, "primary:Pictures/Trip", listOf(found("a")))
        assertEquals(listOf(Notice(Msg.DEPOT_FOLDER_FAILED, "Trip")), m.uploads.current.said?.notices)
        m.uploads.seen(m.uploads.current.said!!.id)

        m.door.report = { _, _ -> error("the walk's folder did not answer") }
        m.uploads.uploadFolder("s", null, "Trip", "/x/Trip", listOf(found("a")))
        assertEquals(listOf(Notice(Msg.DEPOT_FOLDER_FAILED, "Trip")), m.uploads.current.said?.notices)
        assertNull(m.uploads.current.preparing)
        m.uploads.seen(m.uploads.current.said!!.id)

        // an empty one says nothing
        m.door.report = { name, _ -> FolderUpload.Report(FolderUpload.Outcome.NOTHING, name) }
        m.uploads.uploadFolder("s", null, "Trip", "/x/Trip", emptyList())
        assertNull(m.uploads.current.said)
        advanceUntilIdle()
    }

    // ---- the tray's acknowledge ----

    @Test
    fun aBatchAllInLeavesByItselfAfterTheMomentOneWithAFailureStays() = runTest {
        val level = Level()
        val m = model(level)
        m.uploads.upload("s", null, "Home", listOf(source("a.txt")))
        runCurrent()
        advanceTimeBy(10)
        assertEquals(1, m.uploads.current.queue.batches.size)
        assertTrue(m.uploads.current.queue.batches.single().finished)
        advanceTimeBy(UploadQueue.ACKNOWLEDGE_AFTER_MS - 20)
        runCurrent()
        assertEquals(1, m.uploads.current.queue.batches.size, "still there just before the moment")
        advanceTimeBy(20)
        runCurrent()
        assertTrue(m.uploads.current.queue.batches.isEmpty(), "acknowledged by the model, no view")

        // one that failed (a sibling of another kind holds the name) stays until it is closed
        val failing = DepotFiles.UploadJob("s", null, source("x"), "x", { _, _, _, _ -> DepotFiles.Uploaded(DepotFiles.Outcome.TAKEN_BY_OTHER) })
        m.queue.enqueue(listOf(failing), "Home", "s", null, null)
        advanceUntilIdle()
        advanceTimeBy(UploadQueue.ACKNOWLEDGE_AFTER_MS * 3)
        runCurrent()
        val batch = m.uploads.current.queue.batches.single()
        m.uploads.acknowledge(batch.id)
        runCurrent()
        assertTrue(m.uploads.current.queue.batches.isEmpty())
        m.uploads.close()
    }

    @Test
    fun anUploadCancelledAfterItsNodeWasMadeLeavesNoEmptyFile() = runTest {
        val m = model()
        val given = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val job = DepotFiles.UploadJob("s", null, source("big.bin"), "big.bin", { _, _, p, made ->
            made("n-big")
            p(0.1)
            gate.await()
            DepotFiles.Uploaded(DepotFiles.Outcome.DONE, "n-big")
        }) { given += it }
        m.queue.enqueue(listOf(job), "Home", "s", null, null)
        runCurrent()
        m.uploads.cancel(m.uploads.current.queue.batches.single().items.single().id)
        runCurrent()
        assertEquals(listOf("n-big"), given, "the node it made is given up")
        // one that ended (done or refused) gives nothing up: the upload itself saw its outcome
        val done = DepotFiles.UploadJob("s", null, source("ok.txt"), "ok.txt", { _, _, _, made -> made("n-ok"); DepotFiles.Uploaded(DepotFiles.Outcome.DONE, "n-ok") }) { given += it }
        kotlin.runCatching { done.run {} }
        done.abandon()
        assertEquals(listOf("n-big"), given)
        m.uploads.close()
    }
}
