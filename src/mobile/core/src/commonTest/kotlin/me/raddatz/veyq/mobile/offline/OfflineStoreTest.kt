package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline store and its seal (ADR 0140 decisions 2 and 11) over a real directory of the platform's: on the JVM, on
 * macOS, and through each `run.py test` on Linux and Windows. Each check names the edit that turns it red.
 */
class OfflineStoreTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val root = freshTestDirectory()
    private val server = "https://veyq.example.org"
    private var now = 1_763_000_000_000L
    private val clock = { now }

    @AfterTest
    fun cleanUp() = removeAll(root)

    private fun stores(device: MemoryDeviceStore = MemoryDeviceStore(), files: DeviceFiles = deviceFiles) =
        OfflineStores(root, device, p, files, clock)

    private fun key(kind: String, id: String, scope: String = "s-1") = EntryKey(server, "t-1", scope, kind, id)

    // (a) — bite: an injected line that writes the opened frame into the store's directory turns it red; so does an
    // entry written without its seal (`seal` returning `content`).
    @Test
    fun no_plaintext_and_no_id_reaches_the_disk() = runTest {
        val blobMarker = p.randomBytes(64)
        val nameMarker = hex(p.randomBytes(32))
        val blobId = "b-" + hex(p.randomBytes(16))
        val nodeId = "n-" + hex(p.randomBytes(16))
        val masterKey = p.randomBytes(32)
        val store = stores().open("veyq.example.org#u-1", masterKey)

        // a blob of two frames as the store served them, its plaintext carrying the marker
        val blobKey = p.randomBytes(32)
        val plain = ByteArray(V1.FRAME_PLAINTEXT + 100).also { p.randomBytes(it.size).copyInto(it) }
        blobMarker.copyInto(plain, 1000)
        blobMarker.copyInto(plain, V1.FRAME_PLAINTEXT + 10)
        val frames = (0 until 2).associateWith { i ->
            val part = plain.copyOfRange(i * V1.FRAME_PLAINTEXT, minOf(plain.size, (i + 1) * V1.FRAME_PLAINTEXT))
            suite.sealFrame(blobKey, i, 2, part)
        }
        store.putFrames(key("blob", blobId), frames)

        // a listing as the server answers it: the node's id in clear, its name sealed under the node's key
        val nodeKey = p.randomBytes(32)
        val sealedName = b64(suite.seal(Context("master-key"), nodeKey, nameMarker.encodeToByteArray()))
        val listing = """{"nodes":[{"id":"$nodeId","encryptedMeta":"$sealedName","blobId":"$blobId"}]}""".encodeToByteArray()
        store.put(key("listNodes", "s-1"), listing, revision = "\"r1\"", asOf = now)

        // what a view does: the frame read back and opened in memory, the listing read back
        val opened = suite.openFrame(blobKey, 0, 2, store.frame(key("blob", blobId), 0)!!)
        assertContentEquals(plain.copyOf(V1.FRAME_PLAINTEXT), opened)
        assertContentEquals(listing, store.get(key("listNodes", "s-1")))
        store.touch(key("blob", blobId))
        store.flush()

        val disk = everyFile(root)
        assertTrue(disk.isNotEmpty(), "the store wrote nothing: the scan would prove nothing")
        for ((path, bytes) in disk) {
            assertFalse(contains(bytes, blobMarker), "the blob's plaintext is on disk at $path")
            assertFalse(contains(bytes, nameMarker.encodeToByteArray()), "the listing's decrypted name is on disk at $path")
            assertFalse(contains(bytes, blobId.encodeToByteArray()), "the blob id is on disk at $path")
            assertFalse(contains(bytes, nodeId.encodeToByteArray()), "the node id is on disk at $path")
            assertFalse(blobId in path || nodeId in path || "s-1" in path.substringAfter(root), "an id names a file: $path")
        }
        store.close()
    }

    // (b) — bite: drop the logical key from the aad (`aad(tag, key)` without `key`) and the copy under another name opens.
    @Test
    fun an_entry_moved_under_another_name_or_into_another_account_does_not_open() = runTest {
        val device = MemoryDeviceStore()
        val stores = stores(device)
        val mine = stores.open("veyq.example.org#u-1", p.randomBytes(32))
        val a = key("listNodes", "s-1")
        val b = key("listNodes", "s-2")
        mine.put(a, "A's answer".encodeToByteArray())
        mine.put(b, "B's answer".encodeToByteArray())
        val aFile = nameFor(mine, a)
        val bFile = nameFor(mine, b)
        val d = mine.directory + deviceFiles.separator
        deviceFiles.write(d + bFile, deviceFiles.read(d + aFile)!!)
        assertNull(mine.get(b), "A's entry copied under B's name opened as B")
        assertNull(mine.entry(b), "the refused entry stayed in the manifest")
        assertContentEquals("A's answer".encodeToByteArray(), mine.get(a), "the original was lost")

        // the same key in a second account's store: its file replaced by the first account's
        val theirs = stores.open("veyq.example.org#u-2", p.randomBytes(32))
        theirs.put(a, "their answer".encodeToByteArray())
        val t = theirs.directory + deviceFiles.separator
        deviceFiles.write(t + nameFor(theirs, a), deviceFiles.read(d + aFile)!!)
        assertNull(theirs.get(a), "the first account's entry opened in the second account's store")
        mine.close(); theirs.close()
    }

    // (c) — bite: the header's wrap key derived from anything but the Master Key (e.g. `hkdfSha256(ByteArray(32), …)`)
    // and the other Master Key opens the store as it was.
    @Test
    fun a_header_under_another_master_key_starts_the_store_empty() = runTest {
        val device = MemoryDeviceStore()
        val account = "veyq.example.org#u-1"
        val first = stores(device).open(account, p.randomBytes(32))
        first.put(key("listNodes", "s-1"), "kept".encodeToByteArray())
        first.putFrames(key("blob", "b-1"), mapOf(0 to ByteArray(300) { 7 }))
        val dir = first.directory
        first.close()

        val rotated = stores(device).open(account, p.randomBytes(32))
        assertEquals(dir, rotated.directory, "the store moved instead of starting empty in place")
        assertEquals(emptyList(), rotated.entries(), "a store opened under another Master Key")
        assertNull(rotated.get(key("listNodes", "s-1")))
        assertEquals(listOf(OfflineStore.HEADER), deviceFiles.list(dir), "the old entries' files stayed")
        rotated.close()
    }

    @Test
    fun a_store_reopens_under_its_master_key_with_what_it_held() = runTest {
        val device = MemoryDeviceStore()
        val account = "veyq.example.org#u-1"
        val masterKey = p.randomBytes(32)
        val s = stores(device).open(account, masterKey)
        s.put(key("listNodes", "s-1"), "kept".encodeToByteArray(), revision = "\"r7\"", asOf = 42)
        s.putFrames(key("blob", "b-1"), mapOf(0 to ByteArray(300) { 1 }))
        s.putFrames(key("blob", "b-1"), mapOf(2 to ByteArray(200) { 3 }, 0 to ByteArray(5)))
        now += 1000
        s.touch(key("listNodes", "s-1"))
        s.flush()
        s.close()

        val again = stores(device).open(account, masterKey)
        assertContentEquals("kept".encodeToByteArray(), again.get(key("listNodes", "s-1")))
        assertEquals("\"r7\"", again.entry(key("listNodes", "s-1"))!!.revision)
        assertEquals(42L, again.entry(key("listNodes", "s-1"))!!.asOf)
        assertEquals(now, again.entry(key("listNodes", "s-1"))!!.lastView, "the flushed last view was lost")
        assertEquals(setOf(0, 2), again.frames(key("blob", "b-1")))
        assertContentEquals(ByteArray(300) { 1 }, again.frame(key("blob", "b-1"), 0), "a frame kept was replaced by a later append")
        assertContentEquals(ByteArray(200) { 3 }, again.frame(key("blob", "b-1"), 2))
        assertNull(again.frame(key("blob", "b-1"), 1))
        again.close()
    }

    @Test
    fun a_last_view_is_written_at_most_once_per_shard_per_30_seconds() = runTest {
        val writes = Counting(deviceFiles)
        val s = stores(files = writes).open("veyq.example.org#u-1", p.randomBytes(32))
        s.put(key("listNodes", "s-1"), ByteArray(3))
        val before = writes.renames
        repeat(5) { now += 1000; s.touch(key("listNodes", "s-1")) }
        assertEquals(before, writes.renames, "a view within 30 s of the shard's write wrote it")
        now += 30_000
        s.touch(key("listNodes", "s-1"))
        assertEquals(before + 1, writes.renames, "a view 30 s after the shard's write did not write it")
        now += 1000
        s.touch(key("listNodes", "s-1"))
        s.flush()
        assertEquals(before + 2, writes.renames, "flush left a dirty shard")
        s.close()
    }

    // (d) — bite: remove the id first (`write(records() - account)` before the files) and the directory outlives the wipe.
    @Test
    fun a_wipe_leaves_nothing_and_one_a_crash_interrupted_finishes_at_the_next_start() = runTest {
        val device = MemoryDeviceStore()
        val stores = stores(device)
        val account = "veyq.example.org#u-1"
        val other = "veyq.example.org#u-2"
        stores.open(account, p.randomBytes(32)).apply { put(key("listNodes", "s-1"), ByteArray(10)); close() }
        val kept = stores.open(other, p.randomBytes(32)).apply { put(key("listNodes", "s-1"), ByteArray(10)); close() }

        stores.wipe(account)
        assertEquals(listOf(kept.directory.substringAfterLast(deviceFiles.separator)), deviceFiles.list(root), "the wipe left a file or touched another account's store")
        assertFalse(stores.has(account))
        assertTrue(stores.has(other))

        // a crash after the files went, before the directory and the id did
        val again = stores.open(account, p.randomBytes(32)).apply { put(key("listNodes", "s-1"), ByteArray(10)); close() }
        assertFailsWith<Crash> { stores.wipe(account) { if (it == OfflineStores.WipeStep.FILES) throw Crash() } }
        assertTrue(deviceFiles.list(again.directory) != null, "the crash came too late to prove anything")
        stores(device).finishWipes()
        assertEquals(null, deviceFiles.list(again.directory), "the interrupted wipe was not finished at the next start")
        assertFalse(stores.has(account))
        assertEquals(null, device.read(OfflineStores.KEY)?.takeIf { account in it }, "the wiped account's id stayed")
        assertTrue(stores.has(other))
    }

    // (e) — bite: write a shard in place (`replace` writing `path` itself) and a torn write loses the shard's entries.
    @Test
    fun a_torn_shard_write_leaves_the_previous_shard_readable() = runTest {
        val device = MemoryDeviceStore()
        val account = "veyq.example.org#u-1"
        val masterKey = p.randomBytes(32)
        val torn = Torn(deviceFiles)
        val s = stores(device, torn).open(account, masterKey)
        val keys = List(20) { key("listNodes", "s-$it") }
        for (k in keys) s.put(k, k.scope.encodeToByteArray())
        val victim = keys[0]

        // the process dies half-way through writing the shard of an entry it replaces: the shard holds that entry at least
        torn.tearNext = true
        assertFailsWith<Crash> { s.put(victim, ByteArray(4)) }
        assertTrue(torn.tore, "no write was torn")

        val reopened = stores(device).open(account, masterKey)
        for (k in keys - victim) assertContentEquals(k.scope.encodeToByteArray(), reopened.get(k), "an entry written before the torn write was lost: $k")
        // the entry's own file was renamed in before the crash: its new answer or its old one, never none
        assertTrue(reopened.get(victim) != null, "the entry whose shard write tore was lost")
        reopened.sweep()
        assertFalse(deviceFiles.list(reopened.directory)!!.any { it.endsWith(OfflineStore.BESIDE) }, "the torn write's file stayed after the sweep")
        reopened.close()
    }

    @Test
    fun a_sweep_removes_what_the_manifest_does_not_name() = runTest {
        val s = stores().open("veyq.example.org#u-1", p.randomBytes(32))
        s.put(key("listNodes", "s-1"), ByteArray(3))
        val stray = s.directory + deviceFiles.separator + "0123456789abcdef0123456789abcdef"
        deviceFiles.write(stray, ByteArray(9))
        s.sweep()
        assertNull(deviceFiles.size(stray))
        assertContentEquals(ByteArray(3), s.get(key("listNodes", "s-1")))
        s.close()
    }

    /** The file [key]'s entry is kept in: the one name that appears when it is put into an empty copy of [store]. */
    private suspend fun nameFor(store: OfflineStore, key: EntryKey): String {
        val before = deviceFiles.list(store.directory)!!.toSet()
        val content = store.get(key)!!
        store.remove(key)
        val without = deviceFiles.list(store.directory)!!.toSet()
        store.put(key, content)
        return (before - without).single().also { assertEquals(before, deviceFiles.list(store.directory)!!.toSet()) }
    }

    private class Crash : Exception("crash")

    /** Counts the renames (each one a whole file replaced). */
    private class Counting(val inner: DeviceFiles) : DeviceFiles by inner {
        var renames = 0
        override fun rename(from: String, to: String) { renames++; inner.rename(from, to) }
    }

    /** Writes only the first half of the next write that is no header, then dies. */
    private class Torn(val inner: DeviceFiles) : DeviceFiles by inner {
        var tearNext = false
        var tore = false
        var writes = 0
        override fun write(path: String, bytes: ByteArray) {
            if (tearNext && writes++ == 1) {
                // the entry's own file went first; the shard is the second write
                tearNext = false
                tore = true
                inner.write(path, bytes.copyOf(bytes.size / 2))
                throw Crash()
            }
            inner.write(path, bytes)
        }
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
