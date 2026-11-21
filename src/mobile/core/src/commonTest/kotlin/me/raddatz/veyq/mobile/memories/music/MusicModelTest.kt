package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.audio.PageMusic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.audio.MusicPlayer
import me.raddatz.veyq.mobile.audio.PlayedAudio
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's music on its page (ADR 0142 decisions 4–6, 8–13) over a gallery and a player the test plays: the tracks apart
 * from the photos, the header's play and stop, the Music view's marks and rights, adding (the refusals said), renaming
 * (the conflict said), removing with the gallery's Undo, the ITEM report's sheet, offline read-only — and a lightbox video
 * interrupting the music, its end resuming it.
 */
class MusicModelTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val memory = NodeAt("s1", "n1", "MEMORY", "Lisbon")

    private fun photo(id: String, at: Long) = MemoryMedia.Item(
        "s1", "n1", id, MemoryMedia.Blob("o-$id", 1, 29L + V1.FRAME_OVERHEAD, "", null), null, null,
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpg", "user:u1", false, ByteArray(0),
    )
    private fun clip(id: String, at: Long) = MemoryMedia.Item(
        "s1", "n1", id, MemoryMedia.Blob("o-$id", 1, 29L + V1.FRAME_OVERHEAD, "", null), null, null,
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "video/mp4", "$id.mp4", "user:u1", false, ByteArray(0),
    )

    private class Door(val audio: PlayedAudio, var items: List<MemoryMedia.Item>) : GalleryModel.Door, MusicModel.Door {
        val lists = MutableStateFlow<Any?>(Any())
        override val revisions = lists
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val reach = MutableStateFlow(true)
        override val online get() = reach
        var listings = 0
        val trashed = mutableListOf<String>()
        val left = mutableListOf<String>()
        val queued = mutableListOf<String>()
        var renamed: TrackLabel.Outcome = TrackLabel.Outcome.Done("x")
        val reports = mutableListOf<String>()

        override suspend fun gallery(): MemoryMedia.Gallery {
            listings++
            return MemoryMedia.Gallery(MemoryMedia.order(items), listings, 0)
        }
        override suspend fun scopeNodes(): List<NodeRef> = emptyList()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = blob.blobId.encodeToByteArray()
        override fun reader(item: MemoryMedia.Item): RangeReader = RangeReader(300, 1) { _, _, _ -> }.also { audio.register(it, item.mediaId) }
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = true
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
        override suspend fun trash(mediaId: String): GalleryModel.Removal {
            trashed += mediaId
            items = items.filter { it.mediaId != mediaId }
            return GalleryModel.Removal.DONE
        }

        // the Music view's
        override suspend fun check(source: UploadSource): MusicUpload.Check =
            MusicUpload.check(source) { if (source.name.startsWith("broken")) null else 3000 }
        override fun queue(tracks: List<MusicUpload.Check.Taken>): Int {
            queued += tracks.map { it.source.name }
            return tracks.size
        }
        override fun left(source: UploadSource) {
            left += source.name
        }
        override suspend fun rename(item: MemoryMedia.Item, name: String?) = renamed
        override suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String): NodeReport.Sent {
            reports += item.mediaId
            return NodeReport.Sent(NodeReport.Outcome.SENT, "abcd1234", "2026-11-15T10:00:00Z")
        }
        override suspend fun reportOffered() = true
        override val origin = "https://veyq.example.org"
    }

    private class Page(val audio: PlayedAudio, val door: Door, val player: MusicPlayer, val gallery: GalleryModel, val music: MusicModel)

    private fun TestScope.page(items: List<MemoryMedia.Item>): Page {
        val audio = PlayedAudio(items.associate { it.mediaId to 62_000L })
        val door = Door(audio, items)
        val player = MusicPlayer(audio::newDeck, audio.controls, random = { 0 }, scope = backgroundScope)
        val gallery = GalleryModel(memory, door, backgroundScope, backgroundScope, music = PageMusic(player) { "Track $it" })
        val music = MusicModel(gallery, door, backgroundScope)
        runCurrent()
        return Page(audio, door, player, gallery, music)
    }

    private val lisbonMusic = listOf(photo("p1", 10), track("b", "Bossa.mp3"), clip("v1", 20), track("a", "Alfama.mp3", facts = AudioFacts(62_000, 1000, 61_000)), track("u", null))

    @Test
    fun theTracksAreTheMusicsNeverTheGridsAndTheHeaderPlaysAndStopsThem() = runTest {
        val p = page(lisbonMusic)
        assertEquals(listOf("v1", "p1"), p.gallery.current.rows!!.map { it.mediaId }, "a track is no row of the grid")
        assertEquals(listOf("Alfama", "Bossa", null), p.music.current.tracks!!.map { it.title })
        assertEquals(listOf("1:02", null, null), p.music.current.tracks!!.map { it.length })
        assertTrue(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC in p.gallery.headerActions)
        assertEquals(Msg.MUSIC_PLAY, p.gallery.label(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC))
        p.gallery.toggleMusic()
        runCurrent()
        assertNotNull(p.audio.playing(), "the header's play started the node's music")
        assertEquals("memories:s1:n1", p.player.current.nowPlaying?.key)
        assertEquals(Msg.MUSIC_STOP, p.gallery.label(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC))
        assertTrue(p.music.current.tracks!!.single { it.playing }.mediaId == p.player.current.nowPlaying?.trackId, "the one playing is marked")
        p.gallery.toggleMusic()
        runCurrent()
        assertNull(p.player.current.nowPlaying)
        assertEquals(Msg.MUSIC_PLAY, p.gallery.label(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC))
    }

    @Test
    fun aNodeWithoutMusicOffersNoMusicActionAndATrackPressedPlaysFirst() = runTest {
        val none = page(listOf(photo("p1", 10)))
        assertFalse(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC in none.gallery.headerActions)
        val p = page(lisbonMusic)
        p.music.play("u")
        runCurrent()
        assertEquals("u" to "Track 3", p.player.current.nowPlaying?.let { it.trackId to it.title }, "pressed first, named as the view numbers it")
    }

    @Test
    fun aTrackThatDidNotPlayOnThisDeviceIsSkippedAndMarkedSoForTheSession() = runTest {
        // the played deck refuses to load a track whose bytes are named "broken…" (the medium's id, here)
        val p = page(listOf(track("a", "Alfama.mp3"), track("broken", "Fado.ogg", type = "audio/ogg")))
        p.music.play("broken")
        runCurrent()
        assertEquals("a", p.player.current.nowPlaying?.trackId, "skipped: the next one plays")
        assertEquals(listOf(false, true), p.music.current.tracks!!.map { it.unplayable }, "Alfama plays; Fado is marked")
        p.player.stop()
        runCurrent()
        assertTrue(p.music.current.tracks!!.single { it.mediaId == "broken" }.unplayable, "the mark stays after the queue stopped")
    }

    @Test
    fun addingChecksEachFileSaysTheRefusalsAndQueuesTheRest() = runTest {
        val p = page(lisbonMusic)
        val wav = AudioFiles.wavBytes(AudioFiles.Spec(16, 1, 8000, listOf(1.0 to 0.5)))
        val said = p.music.add(listOf(
            AudioFiles.picked(wav, "tone.wav"), AudioFiles.picked(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0), "photo.jpg"),
            AudioFiles.picked(wav, "broken.wav"),
        ))
        assertEquals(listOf(Notice(Msg.MUSIC_NOT_AUDIO, "photo.jpg"), Notice(Msg.MUSIC_UNPLAYABLE, "broken.wav")), said)
        assertEquals(listOf("tone.wav"), p.door.queued)
        assertEquals(listOf("photo.jpg", "broken.wav"), p.door.left, "a refused file is handed back at once")
    }

    @Test
    fun aRenameLostToAPeerSaysSoAndTheGalleryReadsAnew() = runTest {
        val p = page(lisbonMusic)
        val before = p.door.listings
        p.door.renamed = TrackLabel.Outcome.Conflict("Morning")
        assertEquals(Notice(Msg.MUSIC_RENAME_CONFLICT), p.music.rename("a", "Sunset"))
        runCurrent()
        assertTrue(p.door.listings > before, "read anew: the peer's name shows")
        p.door.renamed = TrackLabel.Outcome.Done("Sunset")
        assertNull(p.music.rename("a", "Sunset"))
    }

    @Test
    fun aTrackIsRemovedTheGallerysWayWithItsUndo() = runTest {
        val p = page(lisbonMusic)
        assertTrue(p.music.removes("b"))
        assertEquals(Notice(Msg.MEMORY_MOVED_TO_TRASH), p.music.remove("b"))
        runCurrent()
        assertEquals(listOf("b"), p.door.trashed)
        assertFalse(p.music.current.tracks!!.any { it.mediaId == "b" })
        assertNotNull(p.gallery.current.undo, "its Undo offered")
    }

    @Test
    fun aTrackIsReportedAsAnItemOnTheOneSheetWholeOnly() = runTest {
        val p = page(lisbonMusic)
        val sheet = assertNotNull(p.music.reportSheet("a"))
        assertFalse(sheet.offersName)
        sheet.whole(false)
        assertTrue(sheet.current.whole, "an item has no name to report apart")
        sheet.category(NodeReport.CATEGORIES.last().token)
        sheet.reason("Not ours")
        sheet.goodFaith(true)
        assertEquals(NodeReport.Outcome.SENT, sheet.send().sent?.outcome)
        assertEquals(listOf("a"), p.door.reports)
    }

    @Test
    fun offlineTheViewIsReadOnlyAndPlayingWorks() = runTest {
        val p = page(lisbonMusic)
        p.door.reach.value = false
        runCurrent()
        assertEquals(MusicModel.Rights(add = false, rename = false, download = true, report = false), p.music.current.rights)
        assertEquals(listOf(Notice(Msg.OFFLINE_READ_ONLY)), p.music.add(listOf(AudioFiles.picked(ByteArray(4), "x.mp3"))))
        assertEquals(Notice(Msg.OFFLINE_READ_ONLY), p.music.rename("a", "x"))
        assertTrue(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC in p.gallery.headerActions, "a pinned node's music plays offline")
        p.music.play("a")
        runCurrent()
        assertNotNull(p.audio.playing())
    }

    // --- the lightbox (ADR 0142 decisions 5 and 13) -----------------------------------------------------------------------

    @Test
    fun aLightboxVideoInterruptsTheMusicAndItsPauseResumesIt() = runTest {
        val p = page(lisbonMusic)
        p.music.play("a")
        runCurrent()
        val deck = assertNotNull(p.audio.playing())
        val box = p.gallery.lightbox(0)
        runCurrent()
        assertEquals("a", box.current.music?.trackId, "the compact controls show what plays")
        assertTrue(box.musicControls === p.player)
        box.videoPlaying(true)
        runCurrent()
        assertFalse(deck.playing, "the music pauses while the video plays")
        assertEquals(false, box.current.music?.playing)
        box.videoPlaying(false)
        runCurrent()
        assertTrue(deck.playing, "the video paused: the music plays again")
    }

    @Test
    fun theNextItemAndTheLightboxsCloseEachEndTheInterruption() = runTest {
        val p = page(lisbonMusic)
        p.music.play("a")
        runCurrent()
        val deck = assertNotNull(p.audio.playing())
        val box = p.gallery.lightbox(0)
        box.videoPlaying(true)
        runCurrent()
        assertFalse(deck.playing)
        box.go(1)
        runCurrent()
        assertTrue(deck.playing, "another item: the music plays again")
        box.videoPlaying(true)
        runCurrent()
        assertFalse(deck.playing)
        box.close()
        runCurrent()
        assertTrue(deck.playing, "the lightbox closed: the music plays again")
        assertNotNull(p.player.current.nowPlaying, "closing the lightbox stops nothing")
    }

    @Test
    fun aListenersPauseDuringTheVideoKeepsTheMusicPausedAfterIt() = runTest {
        val p = page(lisbonMusic)
        p.music.play("a")
        runCurrent()
        val deck = assertNotNull(p.audio.playing())
        val box = p.gallery.lightbox(0)
        box.videoPlaying(true)
        runCurrent()
        box.musicControls!!.pause()
        box.videoPlaying(false)
        runCurrent()
        assertFalse(deck.playing)
    }

    @Test
    fun aTracksOriginalIsKeptOfflineAsAVideosIs() {
        val t = track("a")
        assertTrue(MemoryMedia.keeps(t, t.original), "a music track's original is what plays: kept as played")
        val ph = photo("p", 1)
        assertFalse(MemoryMedia.keeps(ph, ph.original))
    }

    @Test
    fun theHeaderJoinsTheKindsOrderAfterShare() {
        val order = me.raddatz.veyq.mobile.AppKinds.headerOrder("MEMORY")
        assertEquals(order.indexOf(me.raddatz.veyq.mobile.navigation.HeaderAction.SHARE) + 1, order.indexOf(me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC))
    }
}
