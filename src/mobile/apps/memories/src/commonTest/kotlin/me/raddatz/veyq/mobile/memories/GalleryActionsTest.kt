package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.MemoryRights.Access
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a Memory's page and its lightbox do beyond what the phones drew first (ADR 0136's Consequences), over a gallery the test plays and on virtual time: the
 * rights deciding what is offered (one page for a member and a guest), a medium trashed with its Undo in its window and not
 * after, a guest's own purge asked first and without Undo, the live poll reading only when the media moved and ending with
 * the page, the slideshow's clock, a chapter made only on a Memory.
 */
class GalleryActionsTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")
    private val chapter = NodeAt("s", "c1", "CHAPTER", "Day one")

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

    private fun item(id: String, at: Long, type: String = "image/jpeg", owner: String = "user:u1", name: String? = "$id.jpg") = MemoryMedia.Item(
        "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), type, name, owner, false, ByteArray(0),
    )

    private class Door(var items: List<MemoryMedia.Item>) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        var listings = 0
        var asked = 0
        var moves = false
        val sent = mutableListOf<String>()
        var trashAnswer = GalleryModel.Removal.DONE
        var restoreAnswer = GalleryModel.Removal.DONE
        var purgeAnswer = true
        val own = mutableSetOf<String>()
        val trashed = mutableSetOf<String>()

        override suspend fun gallery(): MemoryMedia.Gallery {
            listings++
            return MemoryMedia.Gallery(MemoryMedia.order(items.filter { it.mediaId !in trashed }), listings, 0)
        }
        override suspend fun scopeNodes() = listOf(NodeRef("s", "c1", "m", "CHAPTER", "Day one"))
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
        override fun reader(item: MemoryMedia.Item) = RangeReader(10, 1) { _, _, sink -> sink(ByteArray(10)) }
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
            sink(ByteArray(3))
            return true
        }
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>): Int {
            sent += "upload:${sources.size}"
            return sources.size
        }
        override suspend fun moved(since: MemoryMedia.Gallery): Boolean {
            asked++
            return moves
        }
        /** Where set, a trash is on its way until it completes (a slow server). */
        var trashOnItsWay: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun trash(mediaId: String): GalleryModel.Removal {
            trashOnItsWay?.await()
            sent += "trash:$mediaId"
            if (trashAnswer == GalleryModel.Removal.DONE) trashed += mediaId
            return trashAnswer
        }
        override suspend fun restore(mediaId: String): GalleryModel.Removal {
            sent += "restore:$mediaId"
            if (restoreAnswer == GalleryModel.Removal.DONE) trashed -= mediaId
            return restoreAnswer
        }
        override fun isOwn(mediaId: String) = mediaId in own
        var settles = 0
        override suspend fun settle(items: List<MemoryMedia.Item>): Boolean {
            settles++
            return false
        }
        override suspend fun purge(mediaId: String): Boolean {
            sent += "purge:$mediaId"
            if (purgeAnswer) items = items.filter { it.mediaId != mediaId }
            return purgeAnswer
        }
        override suspend fun createChapter(name: String): MemoryCreate.Made {
            sent += "chapter:$name"
            return MemoryCreate.Made(NodeAt("s", "new", "CHAPTER", name), null)
        }
        val covers = mutableListOf<String>()
        override suspend fun cover(nodeId: String): ByteArray? {
            covers += nodeId
            return "cover-$nodeId".encodeToByteArray()
        }
    }

    /** The page in a scope of its own under the test's (its close cancels only it), on the test's clock. */
    private fun TestScope.open(door: Door, at: NodeAt = memory, access: Access = Access.Member()): GalleryModel {
        val own = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        return GalleryModel(at, door, own, own, access) { testScheduler.currentTime }.also { runCurrent() }
    }

    private fun source() = object : UploadSource("x.jpg", 1, "image/jpeg", null) {
        override suspend fun read(from: Long, length: Int) = ByteArray(1)
    }

    private fun three() = listOf(item("a", 30), item("b", 20, type = "video/mp4"), item("c", 10))

    private fun GalleryModel.ids() = current.rows?.map { it.mediaId }

    @Test
    fun theRightsDecideWhatThePageOffersOneViewForAMemberAndAGuest() = runTest {
        val member = open(Door(three()))
        assertEquals(
            listOf(me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD, me.raddatz.veyq.mobile.navigation.HeaderAction.BANNER), member.headerActions,
            "a member adds photos and sets the banner from the header",
        )
        assertEquals(Msg.MEMORY_BANNER_ADD, member.label(me.raddatz.veyq.mobile.navigation.HeaderAction.BANNER))
        assertEquals(listOf(MemoryCreate.CHAPTER), member.creates)
        assertEquals(1, member.upload(listOf(source())))
        val reader = open(Door(three()), access = Access.Guest(ShareLinks.READ))
        assertEquals(emptyList(), reader.headerActions)
        assertEquals(emptyList(), reader.creates)
        val door = Door(three())
        val viewer = open(door, access = Access.Guest(ShareLinks.READ))
        assertEquals(0, viewer.upload(listOf(source())), "a viewer uploads nothing")
        assertEquals(emptyList(), door.sent)
        assertNotNull(viewer.lightbox(0).save { true }, "a viewer downloads")
        val drop = open(Door(three()), access = Access.Guest(ShareLinks.WRITE_ONLY))
        val box = drop.lightbox(1)
        assertNull(box.play(), "an uploader opens no original")
        assertNull(box.save { true }, "nor saves one")
        assertEquals(listOf(me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD), drop.headerActions, "an uploader sets no banner")
        assertEquals(emptyList(), open(Door(three()), at = chapter).creates, "no chapter in a chapter")
    }

    @Test
    fun aMembersTrashTakesTheMediumOutAtOnceAndItsUndoRestoresItWithinItsWindow() = runTest {
        val door = Door(three())
        val g = open(door)
        val said = g.trash("b")
        assertEquals(Notice(Msg.MEMORY_MOVED_TO_TRASH), said)
        assertEquals(listOf("a", "c"), g.ids(), "out of the rows at once")
        assertEquals(said, g.current.undo)
        assertEquals(listOf("trash:b"), door.sent)
        advanceTimeBy(ShellModel.TRASH_UNDO_MS - 1)
        assertNotNull(g.current.undo, "still offered within its window")
        assertNull(g.undo(), "restored: nothing to say")
        runCurrent()
        assertEquals(listOf("trash:b", "restore:b"), door.sent)
        assertEquals(listOf("a", "b", "c"), g.ids(), "back at its place once read anew")
        assertNull(g.current.undo)
    }

    @Test
    fun anUndoPressedWhileTheTrashIsOnItsWayRestoresOnlyOnceTheTrashLanded() = runTest {
        val door = Door(three())
        val g = open(door)
        door.trashOnItsWay = kotlinx.coroutines.CompletableDeferred()
        backgroundScope.launch { g.trash("b") }
        runCurrent()
        assertNotNull(g.current.undo, "the Undo is offered at once")
        var said: Notice? = Notice(Msg.MEMORY_UNNAMED)
        backgroundScope.launch { said = g.undo() }
        runCurrent()
        assertEquals(emptyList(), door.sent, "no restore before the trash landed")
        door.trashOnItsWay!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("trash:b", "restore:b"), door.sent)
        assertNull(said, "restored: nothing to say")
        assertEquals(listOf("a", "b", "c"), g.ids(), "back at its place")
    }

    @Test
    fun anUndoOfATrashThatDidNotGoRestoresNothing() = runTest {
        val door = Door(three())
        val g = open(door)
        door.trashOnItsWay = kotlinx.coroutines.CompletableDeferred()
        door.trashAnswer = GalleryModel.Removal.FAILED
        backgroundScope.launch { g.trash("b") }
        runCurrent()
        var said: Notice? = Notice(Msg.MEMORY_UNNAMED)
        backgroundScope.launch { said = g.undo() }
        runCurrent()
        door.trashOnItsWay!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("trash:b"), door.sent, "nothing to restore")
        assertNull(said)
        assertEquals(listOf("a", "b", "c"), g.ids())
    }

    @Test
    fun theUndoIsGoneOnceItsWindowPassed() = runTest {
        val door = Door(three())
        val g = open(door)
        g.trash("a")
        advanceTimeBy(ShellModel.TRASH_UNDO_MS + 1)
        assertNull(g.current.undo, "the window passed")
        assertNull(g.undo())
        assertEquals(listOf("trash:a"), door.sent, "nothing restored after the window")
        assertEquals(listOf("b", "c"), g.ids())
    }

    @Test
    fun aTrashThatDidNotGoBringsTheMediumBackAndARefusedRestoreIsNamedWithItsReason() = runTest {
        val door = Door(three())
        val g = open(door)
        door.trashAnswer = GalleryModel.Removal.FAILED
        g.trash("a")
        runCurrent()
        assertNull(g.current.undo, "no Undo for a trash that did not go")
        assertEquals(listOf("a", "b", "c"), g.ids(), "read anew: it is still there")
        door.trashAnswer = GalleryModel.Removal.DONE
        door.restoreAnswer = GalleryModel.Removal.BLOCKED
        g.trash("c")
        val refused = g.undo()
        assertEquals(Notice(Msg.MEMORY_RESTORE_REFUSED, "c.jpg", Notice(Msg.MEMORY_REFUSED_BLOCKED)), refused)
        assertEquals(refused, g.current.said)
        g.seen()
        assertNull(g.current.said)
    }

    @Test
    fun theLightboxTrashesItsMediumAndShowsTheNeighbour() = runTest {
        val door = Door(three())
        val g = open(door)
        val box = g.lightbox(1)
        assertEquals(Notice(Msg.MEMORY_MOVED_TO_TRASH), box.trash())
        runCurrent()
        assertEquals("c", box.current.row?.mediaId, "the one now at its place")
        assertEquals(listOf("trash:b"), door.sent)
    }

    @Test
    fun aGuestsOwnMediumIsPurgedOnlyAfterAskingAndWithoutUndo() = runTest {
        val door = Door(three())
        door.own += "c"
        val g = open(door, access = Access.Guest(ShareLinks.READ_WRITE))
        assertNull(g.trash("c"), "a guest's delete is asked first")
        assertEquals("c", g.current.purging)
        assertEquals(emptyList(), door.sent, "nothing sent before the answer")
        g.cancelAsk()
        assertNull(g.purge(), "declined: nothing purged")
        assertEquals(emptyList(), door.sent)
        g.trash("a")
        assertNull(g.current.purging, "another's medium is not the guest's to delete")
        g.askPurge("c")
        assertNull(g.purge())
        assertEquals(listOf("purge:c"), door.sent)
        assertEquals(listOf("a", "b"), g.ids())
        assertNull(g.current.undo, "a purge has no Undo")
        door.own += "a"
        door.purgeAnswer = false
        g.askPurge("a")
        assertEquals(Notice(Msg.MEMORY_DELETE_OWN_FAILED), g.purge())
        assertEquals(listOf("a", "b"), g.ids(), "the tile stays to try again")
    }

    @Test
    fun aMemberWhileTheSpacesDeletionIsPendingDeletesNothing() = runTest {
        val door = Door(three())
        val g = open(door, access = Access.Member(readonly = true))
        assertNull(g.trash("a"))
        assertEquals(emptyList(), door.sent)
        assertEquals(listOf("a", "b", "c"), g.ids())
    }

    @Test
    fun theLivePollReadsOnlyWhenTheMediaMovedAndEndsWithThePage() = runTest {
        val door = Door(three())
        val g = open(door)
        val read = door.listings
        val live = backgroundScope.launch { g.live() }
        val tick = (LivePoll.ITEMS.intervalMs * (1 + LivePoll.ITEMS.jitter)).toLong() + 1
        advanceTimeBy(tick)
        assertTrue(door.asked >= 1, "the poll asked")
        assertEquals(read, door.listings, "nothing moved: nothing read")
        val settled = door.settles
        door.items = door.items + item("d", 40)
        door.moves = true
        advanceTimeBy(tick)
        assertTrue(door.listings > read, "moved: read anew")
        assertTrue(door.settles > settled, "media that moved are a settle point (ADR 0155 § The gallery model's move, D8)")
        assertEquals("d", g.ids()?.first())
        g.close()
        runCurrent()
        assertTrue(live.isCompleted, "the poll ended with the page")
        val asked = door.asked
        advanceTimeBy(tick * 3)
        assertEquals(asked, door.asked)
    }

    // ADR 0155 § The gallery model's move, D4: the page's one poll across its runs — a page shown again (the web's tab visible
    // again, a phone's screen back) asks at once where no tick started within the gap, as the web's poller did; a run that
    // starts right after one ticked waits the interval. A poll made anew per run waited the whole interval every time.
    @Test
    fun aPageShownAgainCatchesUpAtOnceButNotRightAfterATick() = runTest {
        val door = Door(three())
        val g = open(door)
        val tick = (LivePoll.ITEMS.intervalMs * (1 + LivePoll.ITEMS.jitter)).toLong() + 1
        val first = backgroundScope.launch { g.live() }
        runCurrent()
        assertEquals(0, door.asked, "a page just opened has just read: no tick at once")
        var waited = 0L
        while (door.asked == 0 && waited < tick) {
            advanceTimeBy(100)
            waited += 100
        }
        assertEquals(1, door.asked, "the first tick, within a jittered interval")
        first.cancel() // hidden
        val again = backgroundScope.launch { g.live() } // shown again within the gap
        runCurrent()
        assertEquals(1, door.asked, "a tick started within the gap: no catch-up")
        again.cancel()
        advanceTimeBy(LivePoll.ITEMS.minGapMs + 1)
        door.items = door.items + item("d", 40)
        door.moves = true
        val shown = backgroundScope.launch { g.live() } // shown again after the gap
        runCurrent()
        assertEquals(2, door.asked, "shown again: it asks at once")
        assertEquals("d", g.ids()?.first(), "and reads what moved")
        shown.cancel()
        g.close()
    }

    // The web's sort toggle (cr-0066), the gallery model's move D3: the grid oldest first or newest first, at once and for every
    // read after; an undated medium last either way; a tie keeps the listing's order in both directions.
    @Test
    fun theGridTurnsOldestFirstAtOnceAnUndatedMediumLastEitherWay() = runTest {
        val undated = MemoryMedia.Item(
            "s", "m", "u", blob("o-u"), blob("t-u"), blob("p-u"), null, "image/jpeg", "u.jpg", "user:u1", false, ByteArray(0),
        )
        val door = Door(listOf(item("a", 10), item("b", 30), undated, item("c", 30)))
        val g = open(door)
        assertEquals(listOf("b", "c", "a", "u"), g.ids())
        g.sort(oldestFirst = true)
        assertEquals(listOf("a", "b", "c", "u"), g.ids(), "turned at once, no read")
        assertTrue(g.current.oldestFirst)
        door.items = door.items + item("d", 5)
        g.readAnew()
        assertEquals(listOf("d", "a", "b", "c", "u"), g.ids(), "a read after orders in the page's direction")
        g.sort(oldestFirst = false)
        assertEquals(listOf("b", "c", "a", "d", "u"), g.ids())
        g.close()
    }

    // The gallery model's move D5: a medium trashed here and restored from the node's trash elsewhere (the web's Trash tab)
    // before any read showed it gone was kept out of the rows for good; [GalleryModel.restored] lets the next read show it.
    @Test
    fun aMediumRestoredFromTheTrashElsewhereShowsWithTheNextRead() = runTest {
        val door = Door(three())
        val g = open(door)
        g.trash("a")
        runCurrent()
        assertEquals(listOf("b", "c"), g.ids())
        door.trashed -= "a" // restored from the Trash tab
        g.readAnew()
        assertEquals(listOf("b", "c"), g.ids(), "still kept out as trashed here")
        g.restored(listOf("a"))
        g.readAnew()
        assertEquals(listOf("a", "b", "c"), g.ids())
        g.close()
    }

    @Test
    fun theSlideshowStepsEveryIntervalNeverPlaysAVideoAndStopsOnTheLast() = runTest {
        val g = open(Door(three()))
        val box = g.lightbox(0)
        box.slideshow(true)
        assertEquals(Lightbox.Slideshow.PLAYING, box.current.slideshow)
        advanceTimeBy(Lightbox.SLIDESHOW_MS - 1)
        assertEquals(0, box.current.index, "not before the interval")
        advanceTimeBy(2)
        assertEquals(1, box.current.index)
        assertTrue(box.current.row!!.isVideo)
        assertNull(box.current.reader, "a video shows its poster: never played by the slideshow")
        assertEquals(Lightbox.Slideshow.PLAYING, box.current.slideshow, "a video holds one interval")
        advanceTimeBy(Lightbox.SLIDESHOW_MS)
        assertEquals(2, box.current.index)
        assertEquals(Lightbox.Slideshow.ENDED, box.current.slideshow, "it stops on the last medium")
        advanceTimeBy(Lightbox.SLIDESHOW_MS * 3)
        assertEquals(2, box.current.index)
        box.slideshow(true)
        assertEquals(Lightbox.Slideshow.ENDED, box.current.slideshow, "nothing after the last to show")
    }

    @Test
    fun anyGoOrPlayPausesTheSlideshow() = runTest {
        val g = open(Door(three() + item("e", 5)))
        val box = g.lightbox(0)
        box.slideshow(true)
        advanceTimeBy(1_000)
        box.go(2)
        assertEquals(Lightbox.Slideshow.PAUSED, box.current.slideshow)
        advanceTimeBy(Lightbox.SLIDESHOW_MS * 2)
        assertEquals(2, box.current.index, "paused: no step")
        box.go(1)
        box.slideshow(true)
        advanceTimeBy(1_000)
        assertNotNull(box.play(), "the person plays the video")
        assertEquals(Lightbox.Slideshow.PAUSED, box.current.slideshow)
        advanceTimeBy(Lightbox.SLIDESHOW_MS * 2)
        assertEquals(1, box.current.index)
        box.slideshow(true)
        assertNull(box.current.reader, "starting the slideshow stops the video")
        box.slideshow(false)
        assertEquals(Lightbox.Slideshow.PAUSED, box.current.slideshow)
    }

    @Test
    fun aChapterIsMadeOnlyOnAMemoryWhereTheRightsAllowIt() = runTest {
        val door = Door(three())
        val made = open(door).createChapter("  Day two ")
        assertEquals("new", made.node?.nodeId)
        assertEquals(listOf("chapter:Day two"), door.sent)
        val onChapter = Door(three())
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), open(onChapter, at = chapter).createChapter("Inner").notice)
        val asGuest = Door(three())
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), open(asGuest, access = Access.Guest(ShareLinks.READ_WRITE)).createChapter("X").notice)
        assertEquals(Notice(Msg.FOLDER_NAME_EMPTY), open(Door(three())).createChapter("  ").notice)
        assertEquals(emptyList(), onChapter.sent + asGuest.sent)
    }

    @Test
    fun eachMediumSaysWhetherItsDeleteIsOfferedAChaptersCoverIsReadOnlyForItsChaptersAndTheUploadIsWorded() = runTest {
        val member = open(Door(three()))
        assertTrue(listOf("a", "b", "c").all(member::deletes), "a member deletes every medium")
        assertFalse(member.deletes("gone"), "nor one not shown")
        val door = Door(three())
        door.own += "c"
        val guest = open(door, access = Access.Guest(ShareLinks.READ_WRITE))
        assertEquals(listOf(false, false, true), listOf("a", "b", "c").map(guest::deletes), "a guest only their own")
        assertFalse(open(Door(three()), access = Access.Guest(ShareLinks.READ)).deletes("a"), "a viewer none")
        val covers = Door(three())
        val g = open(covers)
        assertContentEquals("cover-c1".encodeToByteArray(), g.chapterCover("c1"))
        assertNull(g.chapterCover("elsewhere"), "a node that is no chapter of this Memory")
        assertEquals(listOf("c1"), covers.covers)
        assertEquals(Msg.MEMORY_ADD, g.label(me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD))
        assertNull(g.label(me.raddatz.veyq.mobile.navigation.HeaderAction.SHARE))
        assertEquals(null, MediaUploads.notTaken(0))
        assertEquals(Notice(Msg.MEMORY_NOT_MEDIA, 2), MediaUploads.notTaken(2))
    }
}
