package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outbox (ADR 0153 decisions 1 and 2): a write made offline kept as its intent, sealed under `K_off`
 * in the account's store, a queued blob sealed under its own `K_blob` at once; never evicted, cleared or swept; refused
 * below the free-space floor; wiped by a refused refresh; carried across a Master Key rotation this device makes.
 *
 * Bites (each one edit, seen red, then restored): the outbox among the evictable kinds (`KeptRead.QUEUED` left out of
 * `PROTECTED`) → [clear_cache_eviction_and_the_sweep_leave_the_outbox] red; the rotation's wipe left in place
 * (`VeyqAccount.swapKeys` calling `wipeOffline` again) → [a_rotation_this_device_makes_carries_the_outbox_and_nothing_else]
 * red; a queued blob's frames not sealed under `K_blob` (`Outbox.sealBlob` keeping `plain`) →
 * [nothing_queued_is_on_disk_or_on_the_wire_as_plaintext] red — not by the directory's grep, which `K_off` alone already
 * passes, but by the frames as they will be sent.
 */
class OutboxTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val root = freshTestDirectory()
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() {
        removeAll(root)
        played.forEach { removeAll(it.root) }
    }

    private val prefs = MemoryDeviceStore()
    private val masterKey = p.randomBytes(32)

    private fun stores(files: DeviceFiles = SizedVolume()) = OfflineStores(root, prefs, p, files) { 0L }

    private fun open(files: DeviceFiles = SizedVolume(), limit: Long = 1_000_000): AccountOffline =
        stores(files).openFor("a.example#u1", masterKey, "http://a.example", limit)

    private fun note(id: String, text: String, blobs: List<QueuedBlob> = emptyList(), at: Long = 0) = QueuedWrite(
        id, "FOLDER_CREATE", "t1", "s1", node = id, intent = JsonObject(mapOf("name" to JsonPrimitive(text))), blobs = blobs, at = at,
    )

    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), from.toInt() + length) }

    private fun ByteArray.has(marker: String): Boolean {
        val m = marker.encodeToByteArray()
        return (0..size - m.size).any { i -> m.indices.all { this[i + it] == m[it] } }
    }

    @Test
    fun an_intent_round_trips_sealed_and_survives_the_store_closing() = runTest {
        val o = open()
        val box = o.outbox(suite)
        val blob = assertNotNull(box.sealBlob(source("a photo".encodeToByteArray())))
        val write = note("w1", "Holiday", listOf(blob), at = 5)
        assertTrue(box.put(write))
        assertTrue(box.put(note("w2", "Later", at = 6)))
        o.close()

        val again = open().outbox(suite)
        assertEquals(listOf(write, note("w2", "Later", at = 6)), again.writes())
        // the blob's frames as they will be sent open under the K_blob the intent carries, to the bytes picked
        val frame = assertNotNull(again.frame(blob, 0))
        assertEquals("a photo", suite.openFrame(unb64(blob.key), 0, 1, frame).decodeToString())
    }

    @Test
    fun a_requeue_replaces_the_write_in_place_and_a_removal_takes_its_blob() = runTest {
        val o = open()
        val box = o.outbox(suite)
        box.put(note("w1", "First", at = 1))
        box.put(note("w2", "Second", at = 2))
        // the same write queued again (the person renamed what they made): its turn kept, its intent replaced
        box.put(note("w1", "First, renamed", at = 3))
        assertEquals(listOf("First, renamed", "Second"), box.writes().map { (it.intent["name"] as JsonPrimitive).content })
        val blob = assertNotNull(box.sealBlob(source(ByteArray(V1.FRAME_PLAINTEXT * 2 + 1))))
        assertEquals(3, blob.chunkCount)
        box.put(note("w3", "With a blob", listOf(blob), at = 4))
        assertEquals(3, box.count())
        box.remove("w3")
        assertNull(box.frame(blob, 0), "a removed write's blob stayed")
        assertEquals(listOf("w1", "w2"), box.writes().map { it.id })
        assertTrue(o.store.entries().none { it.key.kind == KeptRead.OUTBOX_FRAMES })
    }

    @Test
    fun a_write_the_order_does_not_name_is_put_back_and_a_blob_no_write_carries_goes() = runTest {
        val o = open()
        val box = o.outbox(suite)
        box.put(note("w1", "Kept in order", at = 1))
        // a crash between a write and the order's rewrite: the write is there, the order does not name it
        o.putQueued(EntryKey("http://a.example", "", "", KeptRead.OUTBOX, "w0"), Outbox.JSON.encodeToString(QueuedWrite.serializer(), note("w0", "Stray", at = 0)).encodeToByteArray(), floor = false)
        // and a blob sealed for a write that was never queued
        val loose = assertNotNull(box.sealBlob(source("loose".encodeToByteArray())))
        o.close()
        val again = open()
        assertEquals(listOf("w1", "w0"), again.outbox(suite).writes().map { it.id })
        assertNull(again.outbox(suite).frame(loose, 0))
    }

    @Test
    fun nothing_queued_is_on_disk_or_on_the_wire_as_plaintext() = runTest {
        val o = open()
        val box = o.outbox(suite)
        val marker = "QUEUED-BLOB-PLAINTEXT-MARKER"
        val picked = ByteArray(V1.FRAME_PLAINTEXT + 64).also { b -> marker.encodeToByteArray().copyInto(b, 10); marker.encodeToByteArray().copyInto(b, V1.FRAME_PLAINTEXT + 5) }
        val blob = assertNotNull(box.sealBlob(source(picked)))
        assertTrue(box.put(note("w1", "QUEUED-NOTE-CANARY", listOf(blob))))
        o.store.flush()
        val files = everyFile(o.store.directory)
        assertTrue(files.isNotEmpty())
        for ((path, bytes) in files) {
            assertFalse(bytes.has("QUEUED-NOTE-CANARY"), "the queued note is plaintext in $path")
            assertFalse(bytes.has(marker), "the queued blob is plaintext in $path")
        }
        // what the sender will put: each frame sealed under K_blob — no marker, and it opens only under that key
        for (i in 0 until blob.chunkCount) {
            val frame = assertNotNull(box.frame(blob, i))
            assertFalse(frame.has(marker), "frame $i would go to the store as plaintext")
            val opened = suite.openFrame(unb64(blob.key), i, blob.chunkCount, frame)
            assertTrue(opened.has(marker))
        }
    }

    @Test
    fun clear_cache_eviction_and_the_sweep_leave_the_outbox() = runTest {
        val o = open(limit = 10_000)
        val box = o.outbox(suite)
        val blob = assertNotNull(box.sealBlob(source(ByteArray(3_000))))
        assertTrue(box.put(note("w1", "Waiting", listOf(blob))))
        o.keepFrames(o.blobKey("t1", "s1", "viewed"), mapOf(0 to ByteArray(1_000)), 1_000)
        val usage = o.usage()
        assertTrue(usage.queued > 3_000, "the outbox is not counted apart")
        assertTrue(usage.automatic in 1..2_000, "the outbox is counted as recently viewed")

        // the limit lowered under what the outbox alone takes: the automatic tier goes, the outbox stays
        o.setLimit(100)
        assertEquals(listOf("w1"), box.writes().map { it.id }, "the eviction dropped a waiting write")
        assertNotNull(box.frame(blob, 0), "the eviction dropped a waiting blob")
        o.setLimit(1_000_000)
        o.keepFrames(o.blobKey("t1", "s1", "viewed"), mapOf(0 to ByteArray(1_000)), 1_000)
        o.clearCache()
        assertEquals(1, box.count(), "Clear cache dropped a waiting write")
        assertNotNull(box.frame(blob, 0))
        // no listing reaches a waiting write: the sweep leaves it all the same
        o.sweep()
        assertEquals(1, box.count(), "the sweep dropped a waiting write")
        assertNotNull(box.frame(blob, 0), "the sweep dropped a waiting blob")
    }

    @Test
    fun below_the_free_space_floor_a_new_write_is_refused_and_a_change_still_lands() = runTest {
        val volume = SizedVolume(total = 1_000_000, free = 500_000)
        val o = open(volume)
        val box = o.outbox(suite)
        assertTrue(box.put(note("w1", "Before")))
        volume.free = 49_000
        assertFalse(box.put(note("w2", "Under the floor")), "a write was queued below the floor")
        assertNull(box.sealBlob(source(ByteArray(10))), "a blob was sealed below the floor")
        assertTrue(o.store.entries().none { it.key.kind == KeptRead.OUTBOX_FRAMES }, "a refused blob left frames")
        // a waiting write's state and its removal land whatever is free
        box.update(note("w1", "Before").copy(state = QueuedWrite.State.HELD, reason = "stale_placement"))
        assertEquals(QueuedWrite.State.HELD, box.write("w1")?.state)
        box.remove("w1")
        assertEquals(0, box.count())
    }

    // ---- the wipes and the rotation, over an account the test plays

    private suspend fun account(): Pair<PlayedTenant, me.raddatz.veyq.mobile.session.VeyqAccount> {
        val t = PlayedTenant().also { played += it }
        t.scope("s1")
        t.remember()
        return t to t.resume()
    }

    @Test
    fun a_refused_refresh_wipes_the_waiting_writes_unasked() = runTest {
        val (t, a) = account()
        assertTrue(assertNotNull(a.outbox).put(note("w1", "Made offline")))
        a.retire()
        t.reachable = false
        val offline = t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        assertEquals(1, assertNotNull(offline.outbox).count(), "a write made offline did not survive the app's restart")
        val dir = assertNotNull(offline.offline).store.directory
        t.reachable = true
        t.refreshStatus = 401
        assertFalse(offline.reconnect())
        assertNull(offline.outbox, "the outbox outlived a refused refresh")
        assertNull(deviceFiles.list(dir), "the store's directory outlived a refused refresh")
        assertFalse(t.device.offline!!.has(t.record.key))
        offline.retire()
    }

    @Test
    fun a_rotation_this_device_makes_carries_the_outbox_and_nothing_else() = runTest {
        val (_, a) = account()
        val o = assertNotNull(a.offline)
        val kept = o.key(KeptRead.NODES, "t1", "s1", "s1")
        o.keep(kept, listOf("[]".encodeToByteArray()), "\"rev-1\"")
        val box = assertNotNull(a.outbox)
        val blob = assertNotNull(box.sealBlob(source("QUEUED-BLOB-PLAINTEXT-MARKER".encodeToByteArray())))
        assertTrue(box.put(note("w1", "Made before the rotation", listOf(blob))))
        o.store.flush()
        val dir = o.store.directory
        val before = deviceFiles.list(dir).orEmpty().toSet()

        val newMk = p.randomBytes(32)
        val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val material = me.raddatz.veyq.mobile.api.models.AccountKeyMaterial(
            a.userId, me.raddatz.veyq.mobile.api.models.AccountKeyMaterial.SuiteId.v1, emptyList(),
            b64(suite.seal(Context("identity-priv"), newMk, concat(lp(identity.kemSeed), lp(identity.signSeed)))),
        )
        // the account takes the key as it is (and zeroes it at its close): a copy, so the test can open the store after
        a.swapKeys(newMk.copyOf(), identity, material)

        val reopened = assertNotNull(a.offline, "the store was not opened anew under the new Master Key")
        assertEquals(dir, reopened.store.directory)
        val after = assertNotNull(a.outbox)
        assertEquals(listOf("w1"), after.writes().map { it.id }, "the rotation lost the waiting write")
        assertEquals("QUEUED-BLOB-PLAINTEXT-MARKER", suite.openFrame(unb64(blob.key), 0, 1, assertNotNull(after.frame(blob, 0))).decodeToString())
        assertNull(reopened.answer(kept), "an answer kept under the old Master Key outlived the rotation")
        // no file sealed under the old K_off is left: every name is an HMAC under the new one, but the header's
        assertEquals(setOf(OfflineStore.HEADER), before intersect deviceFiles.list(dir).orEmpty().toSet())
        // and after the account closed, the store opens under the new key, the write in it
        a.retire()
        val t = played.first()
        val store = OfflineStores(t.root, t.prefs, p).open(a.key, newMk)
        assertEquals(1, store.entries().count { it.key.kind == KeptRead.OUTBOX && it.key.id == "w1" })
        store.close()
    }
}
