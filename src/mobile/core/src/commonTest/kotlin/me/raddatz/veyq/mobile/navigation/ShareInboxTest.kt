package me.raddatz.veyq.mobile.navigation

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
        assertNull(AppKinds.share("FOLDER"), "a folder is Depot's answer, not built (#987)")
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
        assertFalse(inbox.offers(AppKinds.share("FOLDER"), place("FOLDER")), "a kind with no answer")
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
