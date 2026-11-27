package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.place.PlayedMeta
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OfflineAsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A medium's corrected capture time (ADR 0144 decision 3): the web's lightbox cases — set, reset to the original, a peer's
 * place-name backfill rebased, a peer's own correction a conflict the editor reopens on — over the listed tier written raw
 * through [ListedTierWrite], and the details' editor offered only where the meta may be written and online, the gallery
 * read anew (re-ordered) after each save.
 */
class CaptureTimeTest {
    private val m = PlayedMeta()
    private val mediaId = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val ctx = Context("media-meta", "scopeId" to m.scopeId, "mediaId" to mediaId)
    private fun sealTier(json: String) = b64(m.suite.sealField(ctx, m.key, json.encodeToByteArray()))
    private fun openTier(wrapped: String) = Json.parseToJsonElement(m.suite.openField(ctx, m.key, unb64(wrapped)).decodeToString()) as JsonObject
    private val open: (String?) -> JsonObject = { ListedTierWrite.openRaw(m.suite, m.scopeId, mediaId, m.key, it) }
    private val seal: (JsonObject) -> String? = { ListedTierWrite.seal(m.suite, m.scopeId, mediaId, m.key, it) }

    private val tier = """{"v":1,"w":4000,"h":3000,"capturedAt":1731000000000,"capturedAtSource":"exif","gpsLat":48.1,"gpsLng":11.5}"""

    private class Sent(vararg answers: NodeMetaWrite.Answer) {
        val tiers = mutableListOf<Pair<String?, Long>>()
        private val queue = ArrayDeque(answers.toList())
        suspend fun send(wrapped: String?, revision: Long): NodeMetaWrite.Answer {
            tiers += wrapped to revision
            return queue.removeFirstOrNull() ?: NodeMetaWrite.Answer.Taken
        }
    }

    private suspend fun write(listed: String?, overrideMs: Long?, sent: Sent, revision: Long = 4) =
        CaptureTime.write(NodeMetaWrite.State(listed?.let(::sealTier), revision), open, seal, overrideMs, send = sent::send)

    @Test
    fun aCorrectionIsSetToTheMinuteBesideTheOriginalEveryOtherFieldInItsPlace() = runTest {
        val sent = Sent()
        val at = 1_746_100_000_000L + 42_123 // a picked time with seconds: held to the minute
        val out = write(tier, at, sent)
        val minute = at - at % 60_000
        assertEquals(CaptureTime.Outcome.Done(minute), out)
        val after = openTier(sent.tiers.single().first!!)
        assertEquals(minute, CaptureTime.overrideOf(after))
        val before = Json.parseToJsonElement(tier) as JsonObject
        assertEquals(before.keys.toList() + CaptureTime.FIELD, after.keys.toList(), "the override added, nothing moved")
        for (k in before.keys) assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)
        assertEquals(4L, sent.tiers.single().second, "at the revision it was listed at")
        assertEquals(minute, MemoryMedia.metaOf(after).effectiveCapturedAt, "the effective time is the correction")
    }

    @Test
    fun theMinuteOfATimeBeforeTheEpochIsTheOneItFallsIn() = assertEquals(-60_000L, CaptureTime.minute(-1L))

    @Test
    fun aResetRemovesTheOverrideAndTheOriginalCountsAgain() = runTest {
        val sent = Sent()
        assertEquals(CaptureTime.Outcome.Done(null), write(tier.dropLast(1) + ""","capturedAtOverride":1600000000000}""", null, sent))
        val after = openTier(sent.tiers.single().first!!)
        assertFalse(CaptureTime.FIELD in after)
        assertEquals(1731000000000L, MemoryMedia.metaOf(after).effectiveCapturedAt)
        // clearing the last field drops the tier (the web's: no tier at all)
        val bare = Sent()
        write("""{"v":1,"capturedAtOverride":1600000000000}""", null, bare)
        assertNull(bare.tiers.single().first)
    }

    @Test
    fun aTimeIsSetOnAMediumWithNoTierYet() = runTest {
        val sent = Sent()
        write(null, 1_700_000_040_000L, sent)
        assertEquals("""{"v":1,"capturedAtOverride":1700000040000}""", openTier(sent.tiers.single().first!!).toString())
    }

    @Test
    fun aPeersPlaceNameBackfillMeanwhileIsRebasedNotAConflict() = runTest {
        val peers = sealTier(tier.dropLast(1) + ""","placeName":"München","placeLevel":"city"}""")
        val sent = Sent(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 5)))
        assertEquals(CaptureTime.Outcome.Done(1_700_000_040_000L), write(tier, 1_700_000_040_000L, sent))
        assertEquals(listOf(4L, 5L), sent.tiers.map { it.second })
        val after = openTier(sent.tiers.last().first!!)
        assertEquals("\"München\"", after.getValue("placeName").toString(), "the peer's backfill kept")
        assertEquals(1_700_000_040_000L, CaptureTime.overrideOf(after))
    }

    /** Bite (e): with no own-field rule the peer's correction is overwritten on the retry — red here. */
    @Test
    fun aPeersDifferentCorrectionMeanwhileIsAConflictAndNothingIsWritten() = runTest {
        val peers = sealTier(tier.dropLast(1) + ""","capturedAtOverride":1500000000000}""")
        val sent = Sent(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 5)))
        assertEquals(CaptureTime.Outcome.Conflict(1_500_000_000_000L), write(tier, 1_700_000_040_000L, sent))
        assertEquals(1, sent.tiers.size, "nothing sent over the peer's correction")
        // and a peer's reset is one too: the editor reopens on the original
        val reset = Sent(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(sealTier(tier), 5)))
        assertEquals(CaptureTime.Outcome.Conflict(null), write(tier.dropLast(1) + ""","capturedAtOverride":1600000000000}""", 1_700_000_040_000L, reset))
    }

    @Test
    fun aTierThatDoesNotOpenIsNeverRebuilt() = runTest {
        val sent = Sent()
        val out = CaptureTime.write(NodeMetaWrite.State("bm90IGEgdGllcg==", 1), open, seal, 1L, send = sent::send)
        assertEquals(CaptureTime.Outcome.Unreadable, out)
        assertTrue(sent.tiers.isEmpty())
    }

    // ---- the editor ----

    @Test
    fun theEditorOpensOnTheEffectiveTimeAndReopensOnAPeersCorrection() = runTest {
        val answers = ArrayDeque(listOf<CaptureTime.Outcome>(CaptureTime.Outcome.Conflict(1_500_000_000_000L), CaptureTime.Outcome.Done(1_700_000_040_000L)))
        val asked = mutableListOf<Long?>()
        val e = CaptureTimeEditor(1_731_000_000_000L, null) { asked += it; answers.removeFirst() }
        assertEquals(1_731_000_000_000L, e.current.atMs)
        assertFalse(e.current.corrected)
        assertNull(e.reset(), "nothing to reset without a correction")
        e.set(1_700_000_040_000L)
        assertEquals(CaptureTimeEditor.Said.CONFLICT, e.current.said)
        assertEquals(1_500_000_000_000L, e.current.atMs, "reopened on the peer's")
        assertTrue(e.current.corrected)
        e.set(1_700_000_040_000L)
        assertTrue(e.current.saved)
        assertNull(e.current.said)
        assertEquals(listOf<Long?>(1_700_000_040_000L, 1_700_000_040_000L), asked)
    }

    @Test
    fun theEditorsResetReturnsToTheOriginal() = runTest {
        val e = CaptureTimeEditor(1_731_000_000_000L, 1_600_000_000_000L) { CaptureTime.Outcome.Done(it) }
        assertEquals(1_600_000_000_000L, e.current.atMs)
        assertTrue(e.current.corrected)
        assertEquals(CaptureTime.Outcome.Done(null), e.reset())
        assertEquals(1_731_000_000_000L, e.current.atMs)
        assertFalse(e.current.corrected)
    }

    // ---- the page's gate ----

    private class Door(val items: List<MemoryMedia.Item>) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val reach = MutableStateFlow(true)
        override val online get() = reach
        var offline: OfflineAsOf? = null
        var reads = 0
        val corrections = mutableListOf<Pair<String, Long?>>()
        override suspend fun gallery() = MemoryMedia.Gallery(items, 1, 0, offline).also { reads++ }
        override suspend fun scopeNodes() = emptyList<NodeRef>()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
        override suspend fun captureTime(item: MemoryMedia.Item, overrideMs: Long?): CaptureTime.Outcome {
            corrections += item.mediaId to overrideMs
            return CaptureTime.Outcome.Done(overrideMs)
        }
    }

    private fun TestScope.open(door: Door, access: MemoryRights.Access = MemoryRights.Access.Member()) =
        GalleryModel(NodeAt(m.scopeId, m.nodeId, "MEMORY", "Ostsee"), door, backgroundScope, backgroundScope, access).also { runCurrent() }

    @Test
    fun theEditorIsOfferedWhereTheMetaMayBeWrittenAndASaveReadsTheGalleryAnew() = runTest {
        val door = Door(listOf(m.item(tier, mediaId)))
        val g = open(door)
        val editor = assertNotNull(g.lightbox(0).captureTimeEditor(mediaId))
        assertEquals(1731000000000L, editor.current.atMs)
        val before = door.reads
        editor.set(1_700_000_040_000L)
        runCurrent()
        assertEquals(listOf<Pair<String, Long?>>(mediaId to 1_700_000_040_000L), door.corrections)
        assertTrue(door.reads > before, "the gallery reads anew: it re-orders by the effective time")
        assertNull(g.captureTimeEditor("not-there"))
        assertNull(open(Door(listOf(m.item(tier, mediaId))), MemoryRights.Access.Member(readonly = true)).captureTimeEditor(mediaId), "read-only: none")
        assertNull(open(Door(listOf(m.item(tier, mediaId))), MemoryRights.Access.Guest(me.raddatz.veyq.mobile.session.ShareLinks.READ_WRITE)).captureTimeEditor(mediaId), "a guest: none")
        val offline = Door(listOf(m.item(tier, mediaId))).also { it.reach.value = false; it.offline = OfflineAsOf(1L) }
        assertNull(open(offline).captureTimeEditor(mediaId), "offline nothing is edited")
    }
}
