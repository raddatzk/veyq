package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.composition.AddHere

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.expenses.PotShare
import me.raddatz.veyq.mobile.memories.MemoryShare
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shared files from arrival to where they went (ADR 0138), every app's one state machine, and each kind's answer. */
class ShareInboxTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private fun file(name: String, type: String?, size: Long = 3): UploadSource =
        UploadSource.of(name, size, type, null) { from, length -> ByteArray(minOf(length.toLong(), size - from).toInt()) { (from + it).toByte() } }

    private val photo = file("a.jpg", "image/jpeg")
    private val video = file("clip.mp4", "video/mp4", 300L * 1024 * 1024)
    private val pdf = file("bill.pdf", "application/pdf")

    private fun place(kind: String, off: Set<String> = emptySet(), online: Boolean = true, identity: Boolean = false) =
        ShareAnswer.Place(kind, off, online, identity)

    @Test
    fun each_kind_answers_in_its_own_application() {
        assertSame(PotShare.answer, AppKinds.share("EXPENSES_POT"))
        assertSame(MemoryShare.answer, AppKinds.share("MEMORY"))
        assertSame(MemoryShare.answer, AppKinds.share("CHAPTER"))
        assertNotNull(AppKinds.share("FOLDER"), "a folder is Depot's answer (#987)")
        assertEquals(ShareAnswer.How.QUEUE, AppKinds.share("FOLDER")!!.how)
        assertNull(AppKinds.share("FILE"))
        assertEquals(ShareAnswer.How.EDITOR, PotShare.answer.how)
        assertEquals(ShareAnswer.How.QUEUE, MemoryShare.answer.how)
    }

    @Test
    fun a_memory_takes_a_video_and_refuses_a_pdf_a_chapter_takes_too() {
        val memory = AppKinds.share("MEMORY")!!
        val (fit, out) = memory.split(listOf(photo, pdf, video), { it.type }, { it.name }, { it.size })
        assertEquals(listOf(photo, video), fit, "no bound beyond the upload's own: a 300 MB video is taken")
        assertEquals(listOf(pdf), out)
        val sorted = memory.sort(listOf(photo, pdf, video))
        assertEquals(listOf(photo, video), sorted.taken, "the platforms' sort is the same split")
        assertEquals(listOf(pdf), sorted.left)
        val chapter = AppKinds.share("CHAPTER")!!
        assertEquals(listOf(video), chapter.split(listOf(video), { it.type }, { it.name }, { it.size }).first)
        // the Pot's answer refuses what a Memory takes: a video, and anything beyond its receipts' 10 MiB
        assertEquals(listOf(photo, pdf), PotShare.answer.split(listOf(photo, pdf, video), { it.type }, { it.name }, { it.size }).first)
    }

    @Test
    fun the_pick_mode_dims_a_memory_the_member_may_not_upload_into() {
        val inbox = ShareInbox()
        inbox.receive(ShareInbox.Incoming(listOf(photo, video), refused = 0))
        assertTrue(inbox.offers(AppKinds.share("MEMORY"), place("MEMORY")))
        assertTrue(inbox.offers(AppKinds.share("CHAPTER"), place("CHAPTER")))
        assertFalse(inbox.offers(AppKinds.share("MEMORY"), place("MEMORY", off = setOf("memories"))), "Memories switched off: read-only")
        assertFalse(inbox.offers(AppKinds.share("MEMORY"), place("MEMORY", online = false)), "offline nothing is written")
        assertFalse(inbox.offers(AppKinds.share("FILE"), place("FILE")), "a kind with no answer")
        assertTrue(inbox.offers(AppKinds.share("EXPENSES_POT"), place("EXPENSES_POT")), "a photo is a receipt")
        inbox.receive(ShareInbox.Incoming(listOf(video), refused = 0))
        assertFalse(inbox.offers(AppKinds.share("EXPENSES_POT"), place("EXPENSES_POT")), "a Pot takes no video")
    }

    @Test
    fun add_here_queues_the_media_once_and_lets_the_rest_go() {
        val released = mutableListOf<UploadSource>()
        val inbox = ShareInbox { released += it }
        inbox.receive(ShareInbox.Incoming(listOf(photo, pdf, video), refused = 1))
        val queued = mutableListOf<List<UploadSource>>()
        val taken = inbox.queue(MemoryShare.answer) { queued += it; it.size }
        assertNotNull(taken)
        assertEquals(1, taken.refused)
        assertEquals(listOf(listOf(photo, video)), queued)
        assertEquals(listOf(pdf), released, "the queue hands back what it took; the inbox only what was left out")
        assertNull(inbox.waiting.value, "Add here ends the share")
        assertNull(inbox.queue(MemoryShare.answer) { queued += it; it.size }, "a second Add here finds nothing")
        assertEquals(1, queued.size, "queued once")
    }

    @Test
    fun add_here_with_no_medium_keeps_the_files_waiting() {
        val inbox = ShareInbox()
        val incoming = ShareInbox.Incoming(listOf(pdf), refused = 0)
        inbox.receive(incoming)
        assertNull(inbox.queue(MemoryShare.answer) { error("nothing to queue") })
        assertSame(incoming, inbox.waiting.value)
    }

    // ---- a folder and a space's top: Depot's answer (#987) ----

    private val docx = file("plan.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    private val zip = file("export.zip", "application/zip", 2L * 1024 * 1024 * 1024)
    private val unknown = file("notes", null)

    @Test
    fun a_folder_takes_any_file() {
        val folder = AppKinds.share("FOLDER")!!
        val all = listOf(docx, zip, photo, video, pdf, unknown)
        assertEquals(all, folder.split(all, { it.type }, { it.name }, { it.size }).first, "no type and no bound but the upload's own")
    }

    @Test
    fun the_pick_mode_dims_a_folder_in_an_identity_folder_offline_or_with_depot_off() {
        val inbox = ShareInbox()
        inbox.receive(ShareInbox.Incoming(listOf(docx), refused = 0))
        val folder = AppKinds.share("FOLDER")
        assertTrue(inbox.offers(folder, place("FOLDER")))
        assertTrue(inbox.offers(folder, place("FOLDER", off = setOf("memories"))), "Memories off is not Depot's")
        assertFalse(inbox.offers(folder, place("FOLDER", identity = true)), "nothing is placed in a guest's identity folder")
        assertFalse(inbox.offers(folder, place("FOLDER", online = false)), "offline nothing is written")
        assertFalse(inbox.offers(folder, place("FOLDER", off = setOf("depot"))), "Depot switched off: read-only")
    }

    /** A Depot queue whose door records which files it was handed, under which level. */
    private class RecordingDoor : me.raddatz.veyq.mobile.depot.DepotUploads.Door {
        val handed = mutableListOf<String>()
        override fun uploadJob(scopeId: String, parentId: String?, source: UploadSource, name: String) =
            me.raddatz.veyq.mobile.depot.DepotFiles.UploadJob(scopeId, parentId, source, name, { _, _, _, _, _ ->
                me.raddatz.veyq.mobile.depot.DepotFiles.Uploaded(me.raddatz.veyq.mobile.depot.DepotFiles.Outcome.DONE, "n-$name")
            }).also { handed += "$scopeId/${parentId ?: "top"}/$name" }
        override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<me.raddatz.veyq.mobile.depot.FolderUpload.Entry>) =
            error("no folder here")
        override suspend fun file(scopeId: String, nodeId: String): me.raddatz.veyq.mobile.depot.DepotFiles.File? = null
        override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: UploadSource, expectedBlobId: String?) = error("no replace here")
        override fun changed() = Unit
    }

    private fun level(folder: String?) = Shell(application = null, space = "s", path = listOfNotNull(folder?.let { FolderRef(it, "Docs") }))

    @Test
    fun add_here_on_a_folder_level_queues_into_depots_queue_once_and_ends_the_share() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val door = RecordingDoor()
        val depot = me.raddatz.veyq.mobile.depot.DepotUploads(me.raddatz.veyq.mobile.blob.UploadQueue(dispatcher), door, dispatcher)
        val inbox = ShareInbox()
        inbox.receive(ShareInbox.Incoming(listOf(docx, photo), refused = 0))
        val places = mutableListOf<NodeAt?>()
        val target = assertNotNull(AddHere.at(inbox, level("f1"), null) { places += it; place(it?.kind ?: "FOLDER") })
        assertEquals(listOf<NodeAt?>(NodeAt("s", "f1", "FOLDER", "Docs")), places, "the folder shown is the place asked")
        val lvl = target as AddHere.Level
        assertEquals("f1", lvl.parentId)
        val left = mutableListOf<UploadSource>()
        val taken = assertNotNull(target.take(inbox, "Docs", depot) { left += it })
        assertEquals(listOf(docx, photo), taken.files)
        assertEquals(listOf("s/f1/plan.docx", "s/f1/a.jpg"), door.handed, "Depot's queue was handed both, under the folder")
        assertNull(inbox.waiting.value, "Add here ends the share")
        assertNull(target.take(inbox, "Docs", depot) { left += it }, "a second Add here finds nothing")
        assertEquals(2, door.handed.size, "queued once")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(docx, photo), left, "each handed back once its upload is done")
        depot.close()
    }

    @Test
    fun a_spaces_top_takes_like_a_folder_and_a_level_that_may_not_offers_nothing() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val door = RecordingDoor()
        val depot = me.raddatz.veyq.mobile.depot.DepotUploads(me.raddatz.veyq.mobile.blob.UploadQueue(dispatcher), door, dispatcher)
        val inbox = ShareInbox()
        assertNull(AddHere.at(inbox, level(null), null) { place("FOLDER") }, "nothing waits: no Add here")
        inbox.receive(ShareInbox.Incoming(listOf(zip), refused = 0))
        var asked: NodeAt? = NodeAt("x", "x", "x", "x")
        val top = assertNotNull(AddHere.at(inbox, level(null), null) { asked = it; place("FOLDER") }) as AddHere.Level
        assertNull(asked, "a space's top asks with no folder")
        assertEquals("FOLDER", top.place.kind, "and as a folder")
        assertNull(top.parentId)
        assertNull(AddHere.at(inbox, level(null), null) { place("FOLDER", identity = true) }, "in an identity folder: no Add here")
        assertNull(AddHere.at(inbox, level(null), null) { place("FOLDER", off = setOf("depot")) }, "Depot off: no Add here")
        assertNull(AddHere.at(inbox, level(null).search(true), null) { place("FOLDER") }, "Search open: no level shown")
        assertNull(AddHere.at(inbox, level(null).openPerson("u"), null) { place("FOLDER") }, "a person's page: no level shown")
        assertNull(AddHere.at(inbox, level("f1").open(ObjectRef("s", "m1", "MEMORY", ApplicationId.MEMORIES, "Summer")), null) { place("FOLDER") },
            "an open Memory without its page: nothing")
        top.take(inbox, "Home", depot) {}
        assertEquals(listOf("s/top/export.zip"), door.handed)
        assertNull(inbox.waiting.value)
        testScheduler.advanceUntilIdle()
        depot.close()
    }

    @Test
    fun a_drop_onto_a_level_uploads_any_file_there_at_once_where_depot_may() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val door = RecordingDoor()
        val depot = me.raddatz.veyq.mobile.depot.DepotUploads(me.raddatz.veyq.mobile.blob.UploadQueue(dispatcher), door, dispatcher)
        val folder = assertNotNull(AddHere.shown(level("f1")) { place("FOLDER") }, "a folder shown takes a drop")
        val left = mutableListOf<UploadSource>()
        assertEquals(3, folder.drop(listOf(docx, video, unknown), "Docs", depot) { left += it })
        assertEquals(listOf("s/f1/plan.docx", "s/f1/clip.mp4", "s/f1/notes"), door.handed, "any file, into Depot's queue under the folder")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(docx, video, unknown), left, "each handed back once its upload is done")
        assertNotNull(AddHere.shown(level(null)) { place("FOLDER") }, "a space's top takes a drop as a folder")
        assertNull(AddHere.shown(level("f1")) { place("FOLDER", identity = true) }, "in an identity folder a drop waits in the pick mode")
        assertNull(AddHere.shown(level("f1")) { place("FOLDER", online = false) }, "offline likewise")
        assertNull(AddHere.shown(level("f1").open(ObjectRef("s", "m1", "MEMORY", ApplicationId.MEMORIES, "Summer"))) { place("FOLDER") }, "an object open: no level")
        val offline = AddHere.Level("s", "f1", place("FOLDER", online = false))
        val gone = mutableListOf<UploadSource>()
        assertEquals(0, offline.drop(listOf(docx), "Docs", depot) { gone += it })
        assertEquals(listOf(docx), gone, "a level that may take nothing now hands the files back at once")
        depot.close()
    }

    @Test
    fun a_pots_files_are_taken_given_back_and_let_go_at_the_save() = runTest {
        val released = mutableListOf<UploadSource>()
        val inbox = ShareInbox { released += it }
        val incoming = ShareInbox.Incoming(listOf(photo, video), refused = 2)
        inbox.receive(incoming)
        assertSame(incoming, inbox.waiting.value)
        var ids = 0
        val taken = PotShare.take(inbox) { "r${++ids}" }
        assertNotNull(taken)
        assertEquals(1, taken.refused, "the video is no receipt")
        assertEquals(listOf("r1"), taken.pending.map { it.id })
        assertEquals(3L, taken.pending.single().size)
        assertNull(inbox.waiting.value, "taken: the pick mode ends")
        assertSame(incoming, inbox.giveBack(), "the editor closed unsaved: they wait again")
        assertTrue(released.isEmpty(), "nothing let go while they may wait again")
        PotShare.take(inbox) { "again" }
        inbox.saved()
        assertEquals(listOf(photo, video), released, "the save lets every file of the share go")
        assertNull(inbox.giveBack(), "once saved there is nothing to give back")
    }

    @Test
    fun a_cancel_and_a_new_share_let_the_old_files_go() {
        val released = mutableListOf<UploadSource>()
        val inbox = ShareInbox { released += it }
        inbox.receive(ShareInbox.Incoming(listOf(photo), refused = 0))
        inbox.receive(ShareInbox.Incoming(listOf(video), refused = 0))
        assertEquals(listOf(photo), released, "a share still waiting is replaced")
        inbox.cancel()
        assertEquals(listOf(photo, video), released)
        assertNull(inbox.waiting.value)
    }

    @Test
    fun a_pot_takes_nothing_when_no_file_fits() = runTest {
        val inbox = ShareInbox()
        inbox.receive(ShareInbox.Incoming(listOf(video), refused = 3))
        assertNull(PotShare.take(inbox) { "x" }, "only a video: nothing goes into an expense")
        assertNotNull(inbox.waiting.value, "it keeps waiting for a place that takes it")
        assertFalse(PotShare.goesInto(null))
    }

    @Test
    fun a_pot_reads_each_receipt_whole() = runTest {
        val inbox = ShareInbox()
        val bytes = file("r.png", "image/png", 5)
        inbox.receive(ShareInbox.Incoming(listOf(bytes), refused = 0))
        val pending = PotShare.take(inbox) { "r" }!!.pending.single()
        assertEquals("image/png", pending.type)
        assertEquals("r.png", pending.name)
        assertEquals(5L, pending.size)
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), pending.bytes)
    }
}
