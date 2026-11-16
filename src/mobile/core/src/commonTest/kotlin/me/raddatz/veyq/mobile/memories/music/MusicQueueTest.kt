package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A track as listed and played: the music's own medium in a test's listing. */
internal fun track(
    id: String, name: String? = "$id Song.mp3", trackName: String? = null, facts: AudioFacts? = null, type: String = "audio/mpeg",
    blocked: Boolean = false, key: ByteArray = ByteArray(32) { 7 },
) = MemoryMedia.Item(
    "s1", "n1", id, MemoryMedia.Blob("o-$id", 1, 300L + V1.FRAME_OVERHEAD, "", null), null, null,
    MemoryMedia.Meta(null, null, null, null, null, null, null, null, null, trackName, facts), type, name, "user:u1", blocked, key,
)

/**
 * The web's `musicQueue.test.ts` and the naming half of `audio.test.ts`, with ADR 0142 decision 4's one numbering: the
 * titles, the spans, a blocked one left out, the node's key and route, and a track's `open()` handing a reader, never a key.
 */
class MusicQueueTest {
    private val lisbon = NodeAt("s1", "n1", "MEMORY", "Lisbon")

    @Test
    fun musicTitleIsTheFileNameWithoutItsExtension() {
        assertEquals("Summer Song", MusicQueue.musicTitle("Summer Song.mp3"))
        assertEquals("01. Intro.final", MusicQueue.musicTitle("01. Intro.final.m4a"))
        assertEquals(".hidden", MusicQueue.musicTitle(".hidden"))
        assertEquals("noext", MusicQueue.musicTitle("noext"))
        assertEquals(listOf(null, null, ".mp3"), listOf(MusicQueue.musicTitle("  "), MusicQueue.musicTitle(null), MusicQueue.musicTitle(".mp3 ")))
    }

    @Test
    fun aLengthIsMinutesAndSecondsAndHoursFromAnHourOn() {
        assertEquals(listOf("0:00", "0:01", "1:01", "9:59", "1:00:00", "1:02:05"), listOf(0L, 999, 61_000, 599_499, 3_600_000, 3_725_000).map(MusicQueue::formatLength))
    }

    @Test
    fun theViewSortsByTitleNumbersAwareAndCaseBlindTheUntitledLast() {
        val tracks = MusicQueue.tracks(listOf(track("a", "track 10.mp3"), track("b", null), track("c", "Track 2.mp3"), track("d", "apple.wav"), track("photo", "x.jpg", type = "image/jpeg")))
        assertEquals(listOf("apple", "Track 2", "track 10", null), tracks.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), tracks.map { it.number })
        assertFalse(tracks.any { it.mediaId == "photo" }, "a photo is no track")
    }

    @Test
    fun anUntitledTrackHasOneNumberInTheViewAndInTheQueue() {
        // the listing's order puts the untitled first; the view's sorted order puts it last, and its number is that place
        val listing = listOf(track("x", null), track("b", "B song.mp3"), track("y", "  "), track("a", "A song.mp3"))
        val tracks = MusicQueue.tracks(listing)
        assertEquals(listOf("a" to 1, "b" to 2, "x" to 3, "y" to 4), tracks.map { it.mediaId to it.number })
        val q = MusicQueue.queue(lisbon, tracks, { "Track $it" }, { null })
        val titles = q.tracks.associate { it.id to it.title }
        assertEquals("Track 3", titles["x"], "the queue names it as the view numbers it")
        assertEquals("Track 4", titles["y"])
    }

    @Test
    fun aQueueNamesSpansAndKeysTheNodesTracksAndLeavesABlockedOneOut() {
        val tracks = MusicQueue.tracks(listOf(
            track("a", facts = AudioFacts(9000, 300, 8000)), track("n", null), track("w", "w Song.wav", type = "audio/wav"),
            track("x", blocked = true), track("r", "file.mp3", trackName = "Sunrise"),
        ))
        val q = MusicQueue.queue(lisbon, tracks, { "Track $it" }, { null }, startId = "w")
        assertEquals("memories:s1:n1", q.key)
        assertEquals("Lisbon" to "w", q.album to q.startId)
        assertEquals(ObjectRef("s1", "n1", "MEMORY", ApplicationId.MEMORIES, "Lisbon"), q.route, "the bar's node name opens the node")
        assertEquals(
            listOf(Triple("a", "a Song", 300L to 8000L), Triple("n", "Track 5", null to null), Triple("w", "w Song", null to null), Triple("r", "Sunrise", null to null)),
            q.tracks.map { Triple(it.id, it.title, it.startMs to it.endMs) },
            "in listing order; a renamed track plays under its own name; one without facts plays whole; a blocked one is no track to play",
        )
    }

    @Test
    fun aTrackOpensAsAReaderOfItsOriginalTypedByItsSealedTypeAndReleaseClosesIt() = runTest {
        val readers = mutableListOf<RangeReader>()
        val items = listOf(track("w", "w.wav", type = "audio/wav"), track("u", "u.mp3"))
        val q = MusicQueue.queue(lisbon, MusicQueue.tracks(items), { "Track $it" }, { item ->
            RangeReader(300, 1) { _, _, _ -> }.also { readers += it }.let { MusicQueue.source(item, it) }
        })
        val wav = q.tracks.first { it.id == "w" }.open()
        assertEquals("audio/wav", wav.mime)
        assertEquals("audio/mpeg", q.tracks.first { it.id == "u" }.open().mime)
        assertFalse(readers[0].isClosed)
        wav.release()
        assertTrue(readers[0].isClosed)
    }

    @Test
    fun aTrackWhoseReaderDoesNotOpenFailsItsOpenSoThePlayerSkipsIt() = runTest {
        val q = MusicQueue.queue(lisbon, MusicQueue.tracks(listOf(track("a"))), { "Track $it" }, { null })
        assertNull(runCatching { q.tracks.single().open() }.getOrNull())
    }
}
