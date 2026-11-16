package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.session.testPrimitives
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A music track's listed tier across the surfaces (ADR 0142 decisions 4 and 10): a tier as the web's `media.ts` seals it
 * opened by the core, and a rename that rewrites the RAW tier — every field but `trackName` byte for byte as it was, one
 * this build does not know included — at the listed revision, with the web's rule on a `409 stale_revision`: a peer's
 * rename is a conflict, any other peer's write merges.
 */
class TrackLabelTest {
    private val suite = V1(testPrimitives)
    private val key = ByteArray(32) { 3 }
    private val scopeId = "0b8f1c3e-5a2d-4e7f-9c10-2d3e4f5a6b7c"
    private val mediaId = "7e6d5c4b-3a29-4810-b7f6-e5d4c3b2a190"

    /**
     * A track's listed tier as the web seals it (`sealMetadata` with `RestoredMediaFacts`, `prune`d, its keys in
     * `MediaMeta`'s order), with two fields a newer client wrote that no surface here knows.
     */
    private val webTier = """{"v":1,"capturedAt":1731000000000,"capturedAtSource":"file","trackName":"Sunrise","durationMs":183456,""" +
        """"trimStartMs":1200,"trimEndMs":181500,"loudnessLufs":-14.25,"futureNested":{"a":[1,"x",null],"b":{"c":true}}}"""

    private fun seal(text: String) = b64(suite.sealField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, text.encodeToByteArray()))
    private fun open(wrapped: String) = Json.parseToJsonElement(suite.openField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, unb64(wrapped)).decodeToString()) as JsonObject
    private val openRaw: (String?) -> JsonObject = { TrackLabel.openRaw(suite, scopeId, mediaId, key, it) }
    private val sealRaw: (JsonObject) -> String? = { TrackLabel.seal(suite, scopeId, mediaId, key, it) }

    /** A server the test plays: what each send carried, answered in turn by [answers] (then taken). */
    private class Played(vararg answers: NodeMetaWrite.Answer) {
        val sent = mutableListOf<Pair<String?, Long>>()
        private val queue = ArrayDeque(answers.toList())
        suspend fun send(wrapped: String?, revision: Long): NodeMetaWrite.Answer {
            sent += wrapped to revision
            return queue.removeFirstOrNull() ?: NodeMetaWrite.Answer.Taken
        }
    }

    @Test
    fun theCoreOpensATierTheWebSealedItsFactsAndItsOwnName() {
        val meta = MemoryMedia.openMeta(suite, scopeId, mediaId, key, seal(webTier))
        assertEquals(AudioFacts(183_456, 1200, 181_500), meta?.facts)
        assertEquals("Sunrise", meta?.trackName)
        assertEquals(1_731_000_000_000L, meta?.capturedAt)
    }

    @Test
    fun aRenameRewritesOnlyTheNameEveryOtherFieldByteForByteInItsPlace() = runTest {
        val before = Json.parseToJsonElement(webTier) as JsonObject
        val server = Played()
        val out = TrackLabel.write(NodeMetaWrite.State(seal(webTier), 4), openRaw, sealRaw, "  Sunset  ", server::send)
        assertEquals(TrackLabel.Outcome.Done("Sunset"), out)
        val (wrapped, revision) = server.sent.single()
        assertEquals(4L, revision, "written at the revision it was listed at")
        val after = open(wrapped!!)
        assertEquals(before.keys.toList(), after.keys.toList(), "every field kept, in its place")
        for (k in before.keys - "trackName") assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)
        assertEquals("\"Sunset\"", after.getValue("trackName").toString())
        // the core reads its own write back as the web would
        assertEquals("Sunset", MemoryMedia.openMeta(suite, scopeId, mediaId, key, wrapped)?.trackName)
        assertEquals(AudioFacts(183_456, 1200, 181_500), MemoryMedia.openMeta(suite, scopeId, mediaId, key, wrapped)?.facts)
    }

    @Test
    fun anEmptiedNameIsTakenAwaySoTheFileNamesTheTrackAgain() = runTest {
        val server = Played()
        assertEquals(TrackLabel.Outcome.Done(null), TrackLabel.write(NodeMetaWrite.State(seal(webTier), 1), openRaw, sealRaw, "   ", server::send))
        val after = open(server.sent.single().first!!)
        assertNull(after["trackName"])
        assertEquals((Json.parseToJsonElement(webTier) as JsonObject).keys - "trackName", after.keys)
    }

    @Test
    fun aPeersRenameMeanwhileIsAConflictAndThePeersNameStays() = runTest {
        val peers = webTier.replace("\"Sunrise\"", "\"Morning\"")
        val server = Played(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(seal(peers), 5)))
        val out = TrackLabel.write(NodeMetaWrite.State(seal(webTier), 4), openRaw, sealRaw, "Sunset", server::send)
        assertEquals(TrackLabel.Outcome.Conflict("Morning"), out)
        assertEquals(1, server.sent.size, "nothing sent over the peer's name")
    }

    @Test
    fun anotherPeersWriteMeanwhileMergesTheNameOntoIt() = runTest {
        val peers = webTier.replace("\"capturedAtSource\":\"file\"", "\"capturedAtSource\":\"file\",\"capturedAtOverride\":1600000000000")
        val server = Played(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(seal(peers), 5)))
        assertEquals(TrackLabel.Outcome.Done("Sunset"), TrackLabel.write(NodeMetaWrite.State(seal(webTier), 4), openRaw, sealRaw, "Sunset", server::send))
        val (wrapped, revision) = server.sent.last()
        assertEquals(5L, revision, "sent again at the revision the server handed back")
        val after = open(wrapped!!)
        assertEquals("1600000000000", after.getValue("capturedAtOverride").toString(), "the peer's write kept")
        assertEquals("\"Sunset\"", after.getValue("trackName").toString())
    }

    @Test
    fun aTierThatDoesNotOpenIsNeverRebuiltFromEmpty() = runTest {
        val server = Played()
        val other = b64(suite.sealField(Context("media-meta", "scopeId" to scopeId, "mediaId" to "11111111-2222-4333-8444-555555555555"), key, webTier.encodeToByteArray()))
        assertIs<TrackLabel.Outcome.Failed>(TrackLabel.write(NodeMetaWrite.State(other, 1), openRaw, sealRaw, "Sunset", server::send))
        assertEquals(0, server.sent.size)
    }

    @Test
    fun aTrackWithoutATierGetsOneWithItsNameAndAnEmptiedOneSealsNone() = runTest {
        val server = Played()
        TrackLabel.write(NodeMetaWrite.State(null, 0), openRaw, sealRaw, "Sunset", server::send)
        assertEquals("""{"v":1,"trackName":"Sunset"}""", open(server.sent.single().first!!).toString())
        val clear = Played()
        TrackLabel.write(NodeMetaWrite.State(null, 0), openRaw, sealRaw, null, clear::send)
        assertNull(clear.sent.single().first, "nothing but its version: no tier, as the web's")
    }
}
