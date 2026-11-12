package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeader
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file's page both phones drew (ADR 0129 decision 2, the Depot CR's D1, D3, D4) — over a file the test plays. */
class FileModelTest {
    private val node = NodeAt("s", "f", "FILE", "notes.txt")

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13)
    private val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj<<>>endobj\n%%EOF\n".encodeToByteArray()
    private val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray() + ByteArray(600_000) { (it % 251).toByte() }
    private val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0)

    /** A played file: its [content] in frames of the suite's size, under blob [blobId]. */
    private class Played(val content: ByteArray, val blobId: String?, val type: String? = null, val uploaded: String? = null, val uploader: String? = "user:u1") {
        val frames: List<ByteArray> = content.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
        fun file(size: Long = content.size.toLong(), chunks: Int = frames.size) = DepotFiles.File(
            "s", "f", blobId, if (blobId == null) 0 else chunks, 0, null, if (blobId == null) null else size, type, uploaded, 1_700_000_000_000, uploader,
        )
    }

    private class Door(var played: Played, var identity: Boolean = false) : FileModel.Door {
        var file: DepotFiles.File = played.file()
        val reads = mutableListOf<Pair<Int, Int>>()
        var listings = 0
        var uploaders = 0
        fun replace(p: Played) {
            played = p
            file = p.file()
        }
        override suspend fun file(node: NodeAt): DepotFiles.File {
            listings++
            return file
        }
        override fun inIdentityFolder(node: NodeAt) = identity
        override suspend fun read(file: DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
            reads += first to until
            for (i in first until minOf(until, played.frames.size)) {
                sink(played.frames[i])
                kotlinx.coroutines.yield() // a frame fetched: a watcher sees each step
            }
        }
        override fun reader(file: DepotFiles.File) = RangeReader(file.size ?: 0, file.chunkCount) { first, until, sink -> read(file, first, until, sink) }
        override suspend fun uploader(file: DepotFiles.File): Uploaders.Uploader {
            uploaders++
            return Uploaders.Uploader(Uploaders.Kind.MEMBER, "u1", "Anna")
        }
        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?): DepotFiles.ReplaceJob = error("not played")
    }

    private fun TestScope.open(door: Door, at: NodeAt = node, shares: Boolean = true): FileModel {
        val m = FileModel(at, door, shares, scope = backgroundScope, saves = backgroundScope)
        runCurrent()
        return m
    }

    @Test
    fun theBytesDecideNotTheClaim() = runTest {
        // a PNG named and typed as a text is shown as the image it is
        val m = open(Door(Played(png, "b1", type = "text/plain")))
        assertIs<FileModel.Shown.Image>(m.current.shown)
    }

    @Test
    fun anImageIsHeldWholeInMemoryAndDecodedToTheCoresEdge() = runTest {
        val m = open(Door(Played(png, "b1", type = "image/png")), NodeAt("s", "f", "FILE", "a.png"))
        val shown = assertIs<FileModel.Shown.Image>(m.current.shown)
        assertContentEquals(png, shown.bytes)
        assertEquals("image/png", shown.mediaType)
        assertEquals(4096, shown.edge)
        assertEquals(FileView.IMAGE_EDGE, shown.edge)
    }

    @Test
    fun aLongTextIsCutAtTheCapAndSaysSo() = runTest {
        val long = ByteArray(FileView.TEXT_CAP + 3 * V1.FRAME_PLAINTEXT) { ('a' + it % 26).code.toByte() }
        val door = Door(Played(long, "b1", type = "text/plain"))
        val m = open(door)
        val shown = assertIs<FileModel.Shown.Text>(m.current.shown)
        assertTrue(shown.truncated)
        assertEquals(FileView.TEXT_CAP, shown.text.length)
        // only the frames the first MiB needs were read, never the whole file
        assertTrue(door.reads.all { it.second <= FileView.TEXT_CAP / V1.FRAME_PLAINTEXT }, "${door.reads}")
        // a short one is whole and not cut
        val short = open(Door(Played("hello\n".encodeToByteArray(), "b2", type = "text/plain")))
        assertEquals(false, assertIs<FileModel.Shown.Text>(short.current.shown).truncated)
    }

    @Test
    fun aPdfIsHeldWholeAndItsPagesBound() = runTest {
        val m = open(Door(Played(pdf, "b1", type = "application/pdf")), NodeAt("s", "f", "FILE", "a.pdf"))
        val shown = assertIs<FileModel.Shown.Pdf>(m.current.shown)
        assertContentEquals(pdf, shown.bytes)
        assertEquals(1000, shown.pages)
        assertEquals(1000, FileView.pagesShown(1500))
        assertEquals(12, FileView.pagesShown(12))
        assertNull(FileView.pagesCut(1000))
        assertEquals(Notice(Msg.FILE_PAGES_CUT, 1000, 1001), FileView.pagesCut(1001))
    }

    @Test
    fun aMediumIsAReaderOfTheFilesSize() = runTest {
        val door = Door(Played(mp4, "b1", type = "video/mp4"))
        val m = open(door, NodeAt("s", "f", "FILE", "clip.mp4"))
        val shown = assertIs<FileModel.Shown.Media>(m.current.shown)
        assertEquals(mp4.size.toLong(), shown.size)
        assertEquals("video/mp4", shown.mediaType)
        // nothing but the first frame read to decide; a range near the end read through the reader
        assertEquals(listOf(0 to 1), door.reads)
        val at = mp4.size - 1000L
        assertContentEquals(mp4.copyOfRange(at.toInt(), mp4.size), shown.reader.read(at, 4000))
    }

    @Test
    fun tooLargeEmptyAndUnsupportedSayWhy() = runTest {
        val big = Played(png, "b1", type = "image/png")
        val tooLarge = open(Door(big).also { it.file = big.file(size = FileView.IMAGE_CAP + 1) })
        assertEquals(Msg.FILE_TOO_LARGE, assertIs<FileModel.Shown.Message>(tooLarge.current.shown).words)
        val empty = open(Door(Played(ByteArray(0), "b2", type = "text/plain")))
        assertEquals(Msg.FILE_EMPTY, assertIs<FileModel.Shown.Message>(empty.current.shown).words)
        val other = open(Door(Played(zip, "b3", type = "application/zip")), NodeAt("s", "f", "FILE", "a.zip"))
        assertEquals(Msg.FILE_UNSUPPORTED, assertIs<FileModel.Shown.Message>(other.current.shown).words)
        // a file whose upload never finished has no content to show
        assertIs<FileModel.Shown.NotUploaded>(open(Door(Played(ByteArray(0), null))).current.shown)
    }

    @Test
    fun theFactsAndTheUploadersLine() = runTest {
        val m = open(Door(Played("hello\n".encodeToByteArray(), "b1", type = "text/plain")))
        assertEquals(Notice(Msg.DEPOT_UPLOADED_BY, "Anna"), m.current.uploader)
        assertEquals(DepotFiles.facts(Played("hello\n".encodeToByteArray(), "b1", type = "text/plain").file()), m.current.facts)
    }

    @Test
    fun aReloadShowsAReplacedBlobAndReadsNothingForTheSameOne() = runTest {
        val door = Door(Played("first\n".encodeToByteArray(), "b1", type = "text/plain"))
        val m = open(door)
        assertEquals("first\n", assertIs<FileModel.Shown.Text>(m.current.shown).text)
        val reads = door.reads.size
        m.reload()
        runCurrent()
        assertEquals(reads, door.reads.size, "the same content is not read again")
        door.replace(Played(png, "b2", type = "image/png"))
        m.reload()
        runCurrent()
        assertIs<FileModel.Shown.Image>(m.current.shown)
    }

    @Test
    fun aFilesHeaderOffersReplaceSendAndItsOwnDownload() = runTest {
        val m = open(Door(Played("hello\n".encodeToByteArray(), "b1", type = "text/plain", uploaded = "notes")), NodeAt("s", "f", "FILE", "Notes"))
        val h = m.current.header!!
        assertEquals(listOf(NodeHeader.Action(FileModel.REPLACE), NodeHeader.Action(FileModel.SEND, NodeHeader.Slot.MENU)), h.own)
        assertEquals(Msg.DEPOT_REPLACE, h.label(FileModel.REPLACE))
        assertEquals(Msg.FILE_SEND, h.label(FileModel.SEND))
        assertEquals(DepotFiles.downloadName(Played("x".encodeToByteArray(), "b", type = "text/plain", uploaded = "notes").file(), "Notes"), h.downloadName)
        assertEquals(AppKinds.Facts(canWrite = true, hasContent = true), h.facts)
        val doors = AppKinds.doors("FILE", h.facts)
        val rights = NodeHeader.rights("FILE", true, true, true, true, false, doors.coverOffered, doors.copies, doors.downloads, true, false)
        val plan = NodeHeader.plan(rights, AppKinds.headerOrder("FILE"), 2, h.own)
        assertEquals(listOf(NodeHeader.FAVORITE, NodeHeader.DOWNLOAD), plan.buttons)
        assertTrue(FileModel.REPLACE in plan.menu && FileModel.SEND in plan.menu, "$plan")
        // a platform without a share sheet sends nothing; an empty file is uploaded into, and sends nothing
        assertEquals(listOf(FileModel.REPLACE), open(Door(Played("x".encodeToByteArray(), "b1", type = "text/plain")), shares = false).current.header!!.own.map { it.id })
        val empty = open(Door(Played(ByteArray(0), null))).current.header!!
        assertEquals(listOf(FileModel.REPLACE), empty.own.map { it.id })
        assertEquals(Msg.DEPOT_UPLOAD_INTO, empty.replaceWord)
        assertEquals(AppKinds.Facts(canWrite = true, hasContent = false), empty.facts)
        // a member replaces nothing in a guest's identity folder
        assertEquals(listOf(FileModel.SEND), open(Door(Played("x".encodeToByteArray(), "b1", type = "text/plain"), identity = true)).current.header!!.own.map { it.id })
    }

    @Test
    fun theOpenedFilesRightsAreTheMatrixs() {
        val member = FileRights.of(FileRights.Viewer.MEMBER, inIdentityFolder = false, hasContent = true, shares = true)
        assertEquals(FileRights.Rights(download = true, replace = true, copy = true, send = true), member)
        assertEquals(FileRights.Rights(download = true, replace = false, copy = true, send = true), FileRights.of(FileRights.Viewer.MEMBER, true, true, true))
        assertEquals(FileRights.Rights(download = true, replace = false, copy = false, send = false), FileRights.of(FileRights.Viewer.READ, false, true, false))
        assertEquals(FileRights.Rights(download = true, replace = true, copy = false, send = false), FileRights.of(FileRights.Viewer.READ_WRITE, false, true, false))
        assertEquals(false, FileRights.of(FileRights.Viewer.READ_WRITE, true, true, true).replace)
        assertEquals(false, FileRights.of(FileRights.Viewer.MEMBER, false, false, true).send, "nothing to send")
    }

    @Test
    fun aFilesDownloadIsTheFileAFoldersTheZip() = runTest {
        val content = "the file itself".encodeToByteArray()
        val zipBytes = "PK zip".encodeToByteArray()
        suspend fun download(kind: String): Pair<List<NodeExport.Skipped>?, ByteArray> {
            val out = mutableListOf<Byte>()
            val left = ShellModel.downloadAs(kind, own = { sink -> sink(content); true }, zip = { sink -> sink(zipBytes); emptyList() }) { out += it.toList() }
            return left to out.toByteArray()
        }
        val (fileLeft, file) = download("FILE")
        assertEquals(emptyList(), fileLeft)
        assertContentEquals(content, file)
        assertContentEquals(zipBytes, download("FOLDER").second)
        assertNull(ShellModel.downloadAs("FILE", own = { false }, zip = { emptyList() }) {}, "a file that did not finish is no download")
    }

    /** A place a copy is written, as core's `FileSink` keeps it: whole only when its producer answered true, else gone. */
    private class Kept {
        var bytes: ByteArray? = null
        var opened = 0
        val into: suspend (suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean = { produce ->
            opened++
            val out = mutableListOf<Byte>()
            var keep = false
            try {
                keep = produce { out += it.toList() }
                keep
            } finally {
                bytes = if (keep) out.toByteArray() else null
            }
        }
    }

    @Test
    fun aCopyIsSavedFrameByFrameWithItsProgressAndSaysSo() = runTest {
        val content = ByteArray(3 * V1.FRAME_PLAINTEXT + 17) { (it % 253).toByte() }
        val door = Door(Played(content, "b1", type = "application/zip", uploaded = "a.zip"))
        val m = open(door, NodeAt("s", "f", "FILE", "a.zip"))
        val kept = Kept()
        val seen = mutableListOf<FileSave.State>()
        val save = m.save(kept.into)!!
        val watch = backgroundScope.launch { save.state.collect { seen += it } }
        runCurrent()
        assertEquals(FileSave.End.SAVED, save.current.end)
        assertContentEquals(content, kept.bytes, "the copy is not the file's content")
        assertEquals(Notice(Msg.DOWNLOAD_DONE, "a.zip"), save.current.notice)
        assertEquals(1.0, save.current.fraction)
        // the file's frames read in one pass (never whole in memory), each step said as it went
        assertEquals(0 to Int.MAX_VALUE, door.reads.last())
        assertEquals(content.size.toLong(), save.total)
        assertTrue(seen.any { it.end == null && it.written == V1.FRAME_PLAINTEXT.toLong() }, "${seen.map { it.written }}")
        assertEquals(
            Notice(Msg.DOWNLOAD_SAVING, "a.zip", me.raddatz.veyq.mobile.words.Bytes(V1.FRAME_PLAINTEXT.toLong()), me.raddatz.veyq.mobile.words.Bytes(content.size.toLong())),
            seen.first { it.written == V1.FRAME_PLAINTEXT.toLong() }.notice,
        )
        watch.cancel()
        // nothing to save where the file has no content
        assertNull(open(Door(Played(ByteArray(0), null))).save(kept.into))
    }

    @Test
    fun aCancelledCopyLeavesNothingAndAFailedOneSaysSo() = runTest {
        val content = ByteArray(4 * V1.FRAME_PLAINTEXT) { 7 }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val door = object : FileModel.Door by Door(Played(content, "b1", type = "application/zip")) {
            override suspend fun read(file: DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
                val frames = content.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
                for (i in first until minOf(until, frames.size)) {
                    sink(frames[i])
                    if (i == 1) gate.await() // the save stands midway until the test lets it go on
                }
            }
        }
        val m = FileModel(NodeAt("s", "f", "FILE", "a.zip"), door, shares = false, scope = backgroundScope, saves = backgroundScope)
        runCurrent()
        val kept = Kept()
        val save = m.save(kept.into)!!
        runCurrent()
        assertNull(save.current.end, "it ended before it was let go on")
        assertEquals(2L * V1.FRAME_PLAINTEXT, save.current.written)
        assertTrue(save.cancel())
        assertEquals(FileSave.End.CANCELLED, save.current.end)
        assertEquals(Notice(Msg.DOWNLOAD_CANCELLED, "a.zip"), save.current.notice)
        assertNull(kept.bytes, "a cancelled copy left something at its path")
        assertEquals(false, save.cancel(), "a save that ended is not cancelled again")
        // a place that does not open: failed, said
        val failed = m.save { _ -> throw IllegalStateException("cannot write") }!!
        runCurrent()
        assertEquals(FileSave.End.FAILED, failed.current.end)
        assertEquals(Notice(Msg.DOWNLOAD_FAILED, "a.zip"), failed.current.notice)
    }

    @Test
    fun theBytesAHostShowsAreTheContentsAndTheRevisionTurnsWithIt() = runTest {
        val door = Door(Played(png, "b1", type = "image/png"))
        val m = open(door, NodeAt("s", "f", "FILE", "a.png"))
        assertContentEquals(png, m.bytes())
        assertEquals("image/png", m.mediaType())
        val first = m.current.revision
        m.reload()
        runCurrent()
        assertEquals(first, m.current.revision, "the same content turned the revision")
        door.replace(Played(pdf, "b2", type = "application/pdf"))
        m.reload()
        runCurrent()
        assertTrue(m.current.revision > first, "a replaced blob did not turn the revision")
        assertContentEquals(pdf, m.bytes())
        assertEquals("application/pdf", m.mediaType())
        assertNull(open(Door(Played("hi\n".encodeToByteArray(), "b3", type = "text/plain"))).bytes(), "a text's bytes are its state's")
    }

    @Test
    fun whatThePageSaysWhereItShowsNoContent() = runTest {
        assertEquals(Msg.FILE_OPENING, FileModel.words(FileModel.Shown.Loading))
        assertEquals(Msg.FILE_OPEN_FAILED, FileModel.words(FileModel.Shown.Failed))
        assertEquals(Msg.DEPOT_NOT_UPLOADED, FileModel.words(FileModel.Shown.NotUploaded))
        assertEquals(Msg.FILE_EMPTY, FileModel.words(FileModel.Shown.Message(FileView.Kind.EMPTY)))
        // a medium, for a platform that plays none: not shown here, Save a copy offered
        val medium = open(Door(Played(mp4, "b1", type = "video/mp4")), NodeAt("s", "f", "FILE", "clip.mp4")).current.shown
        assertEquals(Msg.FILE_UNSUPPORTED, FileModel.words(medium))
        assertNull(FileModel.words(FileModel.Shown.Pdf(pdf)), "content shows: nothing said")
        // a cut text says so above it, a whole one says nothing
        assertEquals(Notice(Msg.FILE_TEXT_TRUNCATED), FileModel.cut(FileModel.Shown.Text("x", truncated = true)))
        assertNull(FileModel.cut(FileModel.Shown.Text("x", truncated = false)))
    }
}
