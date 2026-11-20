package me.raddatz.veyq.mobile.offline

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.QueuedMemories
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Memories' writes queued offline and sent (ADR 0153 decisions 1, 3 and 4; the CR's phase 3), each over a played server
 * with real seals: a chapter made offline and two photos added into it, the photos waiting for it and sent after it; a
 * photo's `K_blob` wrapped at the send under the key its node has then (a re-key while offline); the picked file let go
 * once sealed, before anything is sent; a capture time held where someone else set that field, merged where they changed
 * another; a medium trashed and a mark set.
 *
 * Bite (one edit, seen red, then restored): `QueuedMemories.medium` wrapping each `K_blob` under the node's key at queue
 * time and the send reusing that wrap → [a_medium_is_wrapped_at_the_send_under_the_key_its_node_has_then] red.
 */
class QueuedMemoriesTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Setup(val t: PlayedTenant, val up: PlayedUploads, val scope: PlayedTenant.Scope, val memory: PlayedTenant.Node)

    /** A personal space with one Memory ("Trip"), remembered on this device. */
    private suspend fun setup(): Setup {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "m1", kind = "MEMORY", name = "Trip")
        t.remember()
        return Setup(t, PlayedUploads(t), s, m)
    }

    /** The account opened offline (once opened online, so the device keeps what an unlock offline opens). */
    private suspend fun Setup.offline(): VeyqAccount {
        // the person saw the space online first: its tree is kept
        t.resume().also { a -> a.open(a.tenants.first()).also { it.trees() }.close() }.retire()
        t.reachable = false
        return t.resume().also { assertIs<Reach.Offline>(it.reach.value) }
    }

    private fun HttpRequestData.body(): JsonObject = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun isCreate(r: HttpRequestData) = r.method == HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))

    /** A picked photo whose bytes carry [marker]; [closed] counts its closes. */
    private class Picked(name: String, val bytes: ByteArray) : UploadSource(name, bytes.size.toLong(), "image/png", 1_000L) {
        var closed = 0
        var reads = 0
        override suspend fun read(from: Long, length: Int): ByteArray { reads++; return bytes.copyOfRange(from.toInt(), minOf(bytes.size, from.toInt() + length)) }
        override fun close() { closed++ }
    }

    private fun picked(marker: String) = Picked("$marker.png", ("PNG-$marker-" + "x".repeat(200)).encodeToByteArray())

    /** The platform's tiers: a preview and a thumbnail naming the photo they came from. */
    private val deriver = object : MediaUpload.Deriver {
        override fun derive(source: UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) =
            done(MediaUpload.Derived("preview of ${source.name}".encodeToByteArray(), "thumb of ${source.name}".encodeToByteArray(), 40, 30))
    }

    @Test
    fun a_chapter_and_two_photos_made_offline_are_sent_in_order_at_the_reconnect() = runTest {
        val x = setup()
        val a = x.offline()
        assertTrue(a.queues(Queued.MEMORY_CREATE))
        assertTrue(a.queues(Queued.MEDIA_ADD))
        val chapter = assertNotNull(QueuedMemories.create(a, "t1", x.scope.id, x.memory.id, MemoryCreate.CHAPTER, " Day one ", 1))
        val left = mutableListOf<UploadSource>()
        val photos = listOf(picked("first"), picked("second"))
        val media = QueuedMemories.media(a, "t1", x.scope.id, chapter.id, photos, deriver, { 2L }, left::add)
        assertEquals(2, media.size)
        assertTrue(media.all { it.after == chapter.id }, "a medium into a chapter made offline does not wait for it")
        val order = mutableListOf<String>()
        x.t.route = { r ->
            when {
                isCreate(r) -> {
                    val b = r.body()
                    order += "create:" + b["kind"]!!.jsonPrimitive.content
                    // the played server takes it: the tree lists the chapter at the id the client minted
                    x.t.adopt(x.scope, b["nodeId"]!!.jsonPrimitive.content, "CHAPTER", x.memory, "Day one")
                    201 to """{"nodeId":"${b["nodeId"]!!.jsonPrimitive.content}"}"""
                }
                r.url.encodedPath.endsWith("media-batch") -> {
                    order += "medium:" + r.body()["items"].toString().substringAfter("\"nodeId\":\"").substringBefore('"')
                    x.up.route(r)
                }
                else -> x.up.route(r)
            }
        }
        x.t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(listOf("create:CHAPTER", "medium:${chapter.id}", "medium:${chapter.id}"), order)
        assertEquals(0, assertNotNull(a.outbox).count())
        a.retire()
    }

    @Test
    fun a_medium_is_wrapped_at_the_send_under_the_key_its_node_has_then() = runTest {
        val x = setup()
        val a = x.offline()
        val photo = picked("rekeyed")
        val w = QueuedMemories.media(a, "t1", x.scope.id, x.memory.id, listOf(photo), deriver, { 1L }, {}).single()
        val oldKey = x.memory.key.copyOf()
        // meanwhile another device re-keys the Memory (a link closed below it)
        x.t.rekey(x.memory)
        x.t.reachable = true
        x.t.route = x.up::route
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(0, assertNotNull(a.outbox).count(), "the medium was not sent")
        val original = x.up.reserved.first { it.role == MemoryMedia.ORIGINAL }
        assertEquals(w.id, original.ownerId)
        val ctx = Context("blob-key", "scopeId" to x.scope.id, "ownerId" to w.id, "blobId" to original.blobId, "role" to MemoryMedia.ORIGINAL)
        val blobKey = runCatching { x.t.suite.open(ctx, x.memory.key, unb64(original.wrappedBlobKey)) }.getOrNull()
        assertNotNull(blobKey, "the K_blob sent does not open under the node's key now")
        assertTrue(runCatching { x.t.suite.open(ctx, oldKey, unb64(original.wrappedBlobKey)) }.isFailure, "the K_blob sent opens under the node's old key")
        assertContentEquals(photo.bytes, x.up.plaintext(original.blobId, blobKey), "the frames sent do not open to the photo")
        val item = x.up.completions.single { it.first == "completeMedia" }.second
        assertEquals(w.id, item["mediaId"]!!.jsonPrimitive.content)
        assertEquals(listOf(MemoryMedia.ORIGINAL, MemoryMedia.THUMBNAIL, MemoryMedia.PREVIEW), x.up.reserved.map { it.role }, "the tiers were not sent beside the original")
        a.retire()
    }

    @Test
    fun the_picked_file_is_let_go_once_sealed_and_nothing_of_it_lies_in_the_store_as_plaintext() = runTest {
        val x = setup()
        val a = x.offline()
        val photo = picked("plainmarker")
        val left = mutableListOf<UploadSource>()
        val w = QueuedMemories.media(a, "t1", x.scope.id, x.memory.id, listOf(photo), deriver, { 1L }, left::add).single()
        assertEquals(listOf<UploadSource>(photo), left, "the picked file was not handed back once sealed")
        assertTrue(photo.closed > 0, "the picked file was not closed once sealed")
        val reads = photo.reads
        assertEquals(3, w.blobs.size, "the original and both tiers are not sealed")
        // the send reads nothing of the picked file again: the frames go as they were sealed
        x.t.reachable = true
        x.t.route = x.up::route
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(reads, photo.reads, "the send read the picked file again")
        val marker = "plainmarker".encodeToByteArray()
        a.offline!!.store.flush()
        for ((f, bytes) in everyFile(x.t.root)) {
            assertTrue((0..bytes.size - marker.size).none { i -> marker.indices.all { bytes[i + it] == marker[it] } }, "$f holds the photo as plaintext")
        }
        a.retire()
    }

    @Test
    fun a_capture_time_is_held_where_someone_set_it_meanwhile_and_merged_where_they_changed_another_field() = runTest {
        val x = setup()
        val m1 = x.t.medium(x.memory, "p1").also { it.tier = """{"v":1,"capturedAt":1000}""" }
        val m2 = x.t.medium(x.memory, "p2").also { it.tier = """{"v":1,"capturedAt":1000}""" }
        // seen online first, so the listing is kept
        val online = x.t.resume()
        val tenant = online.open(online.tenants.first())
        val items = assertNotNull(MemoryMedia.list(tenant, x.scope.id, x.memory.id)).items
        tenant.close()
        online.retire()
        x.t.reachable = false
        val a = x.t.resume()
        assertTrue(QueuedMemories.captureTime(a, "t1", items.first { it.mediaId == m1.id }, 120_000L, 1))
        assertTrue(QueuedMemories.captureTime(a, "t1", items.first { it.mediaId == m2.id }, 180_000L, 2))
        // meanwhile someone else corrects m1's time and names m2's place
        m1.tier = """{"v":1,"capturedAt":1000,"capturedAtOverride":60000}"""
        m2.tier = """{"v":1,"capturedAt":1000,"placeName":"Berlin"}"""
        x.memory.mediaRevision++
        val puts = mutableListOf<Pair<String, String?>>()
        x.t.route = { r ->
            if (r.method == HttpMethod.Put && r.url.encodedPath.endsWith("/meta")) {
                val mediaId = r.url.encodedPath.split('/')[5]
                puts += mediaId to r.body()["wrappedMeta"]?.jsonPrimitive?.content
                204 to ""
            } else null
        }
        x.t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        val waiting = assertNotNull(a.outbox).writes()
        assertEquals(listOf(QueuedWrite.State.HELD), waiting.map { it.state })
        assertEquals("stale_revision", waiting.single().reason)
        assertEquals(listOf(m2.id), puts.map { it.first }, "the held time was written, or the merged one was not")
        val sealed = unb64(assertNotNull(puts.single().second))
        val tier = Json.parseToJsonElement(x.t.suite.openField(Context("media-meta", "scopeId" to x.scope.id, "mediaId" to m2.id), x.memory.key, sealed).decodeToString()).jsonObject
        assertEquals("Berlin", tier["placeName"]!!.jsonPrimitive.content, "another field's change was not merged")
        assertEquals(180_000L, tier["capturedAtOverride"]!!.jsonPrimitive.content.toLong())
        a.retire()
    }

    @Test
    fun a_trash_and_a_mark_are_sent_and_a_medium_trashed_before_it_was_sent_leaves_the_queue() = runTest {
        val x = setup()
        val m1 = x.t.medium(x.memory, "p1")
        val a = x.offline()
        assertTrue(QueuedMemories.trash(a, "t1", x.scope.id, x.memory.id, m1.id, 1))
        val unsent = QueuedMemories.media(a, "t1", x.scope.id, x.memory.id, listOf(picked("never")), deriver, { 2L }, {}).single()
        assertTrue(QueuedMemories.mark(a, "t1", x.scope.id, x.memory.id, unsent.id, true, 3))
        assertEquals(unsent.id, a.outbox!!.writes().last().after, "a mark of a medium added offline does not wait for it")
        assertTrue(QueuedMemories.trash(a, "t1", x.scope.id, x.memory.id, unsent.id, 4))
        assertEquals(listOf(Queued.MEDIA_TRASH.name), a.outbox!!.writes().map { it.op }, "the medium trashed before it was sent, or its mark, still waits")
        assertTrue(QueuedMemories.mark(a, "t1", x.scope.id, x.memory.id, m1.id, true, 5))
        val ops = mutableListOf<String>()
        x.t.route = { r ->
            when {
                r.method == HttpMethod.Delete && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/media/[^/]+")) -> { ops += "trash"; 204 to "" }
                r.url.encodedPath.endsWith("/item-favorites") && r.method == HttpMethod.Get -> 200 to "[]"
                r.url.encodedPath.endsWith("/item-favorites") && r.method == HttpMethod.Put -> { ops += "mark"; 204 to "" }
                else -> null
            }
        }
        x.t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(listOf("trash", "mark"), ops)
        assertEquals(0, a.outbox!!.count())
        a.retire()
    }

    @Test
    fun a_medium_answered_id_taken_is_sent_where_it_is_listed_and_held_where_not() = runTest {
        val x = setup()
        val a = x.offline()
        val w = QueuedMemories.media(a, "t1", x.scope.id, x.memory.id, listOf(picked("lost")), deriver, { 1L }, {}).single()
        x.up.refuseMedia = "id_taken"
        x.t.route = x.up::route
        x.t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals("id_taken", a.outbox!!.writes().single().reason)
        a.retire()
        assertNotNull(w)
    }
}
