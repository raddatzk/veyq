package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The automatic tier under its limit (ADR 0140 decision 8): the least recently viewed entry goes first, a pinned one
 * never; lowering the limit evicts at once; `0` keeps nothing automatically; and a medium read is kept in batches — one
 * frame index and one manifest shard written per batch, never per frame.
 *
 * The free-space floor: a write is taken only where the volume keeps 5 % free after it — at 5 % it keeps, a byte under
 * it does not. The volume is a [SizedVolume], so neither this nor any other test reads how full the host is.
 *
 * Bites: evict by size (the largest first) instead of by last view → the most recently viewed entry goes and the oldest
 * stays; append each frame on its own (`ApplicationBlobs.KEEP_BATCH = 1`) → the writes grow with the frames; the floor
 * strict (`>` for `>=` in `AccountOffline.mayWrite`) → the write leaving exactly 5 % is refused; no floor
 * (`FLOOR_PERCENT = 0`) → the writes under it are taken, and `OfflinePinsTest`'s pin is kept instead of `NO_SPACE`;
 * [SizedVolume] answering the host's volume → on a host under 5 % free every test that keeps anything goes red.
 */
class OfflineLimitTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    private val p = testPrimitives
    private val root = freshTestDirectory()

    @AfterTest
    fun cleanUp() = removeAll(root)

    private var now = 0L

    private fun open(files: DeviceFiles = SizedVolume()): AccountOffline =
        OfflineStores.of(root, MemoryDeviceStore(), p, files) { now }.openFor("a.example#u1", p.randomBytes(32), "http://a.example", 1_000_000)

    private fun key(o: AccountOffline, id: String) = o.blobKey("t1", "s1", id)

    private suspend fun AccountOffline.kept(): Set<String> = store.entries().map { it.key.id }.toSet()

    @Test
    fun eviction_keeps_a_pinned_entry_and_drops_the_least_recently_viewed() = runTest {
        val o = open()
        now = 1
        o.keepFrames(key(o, "oldest"), mapOf(0 to ByteArray(1_000)), 1_000)
        now = 2
        o.keepFrames(key(o, "pinned"), mapOf(0 to ByteArray(1_000)), 1_000)
        o.store.setPins(key(o, "pinned"), setOf("pin"))
        now = 3
        o.keepFrames(key(o, "newest"), mapOf(0 to ByteArray(3_000)), 3_000)
        assertEquals(setOf("oldest", "pinned", "newest"), o.kept())
        val used = o.store.used()
        // lowered below what is kept: it evicts at once, down to 90 % of the new limit — the oldest view alone is enough
        o.setLimit(used - 400)
        assertEquals(setOf("pinned", "newest"), o.kept(), "not the least recently viewed went")
        // a view moves an entry back: now the older one of the two goes first
        now = 4
        o.keepFrames(key(o, "fresh"), mapOf(0 to ByteArray(500)), 500)
        now = 5
        o.touch(key(o, "newest"))
        now = 6
        o.setLimit(o.store.used() - 64)
        assertEquals(setOf("pinned", "newest"), o.kept())
        // 0: the automatic tier keeps nothing, the pin stays, and nothing new is kept
        o.setLimit(0)
        assertEquals(setOf("pinned"), o.kept())
        o.keepFrames(key(o, "after"), mapOf(0 to ByteArray(10)), 10)
        assertEquals(setOf("pinned"), o.kept())
        val usage = o.usage()
        assertEquals(0, usage.automatic)
        assertTrue(usage.pinned > 0)
        o.close()
    }

    @Test
    fun a_blob_larger_than_a_quarter_of_the_limit_is_not_kept_by_the_automatic_tier() = runTest {
        val o = open()
        o.setLimit(4_000)
        o.keepFrames(key(o, "big"), mapOf(0 to ByteArray(100)), 1_001)
        o.keepFrames(key(o, "small"), mapOf(0 to ByteArray(100)), 1_000)
        assertEquals(setOf("small"), o.kept())
        o.close()
    }

    @Test
    fun the_free_space_floor_keeps_five_percent_of_the_volume_free() = runTest {
        val volume = SizedVolume(total = 100_000, free = 5_100)
        val o = open(volume)
        // 100 bytes more leave exactly 5 % free: kept
        assertTrue(o.keepFrames(key(o, "at"), mapOf(0 to ByteArray(100)), 100), "a write leaving 5 % free was refused")
        // one byte less free: the same write would leave under 5 %, refused and nothing kept
        volume.free = 5_099
        assertFalse(o.keepFrames(key(o, "under"), mapOf(0 to ByteArray(100)), 100), "a write leaving under 5 % free was taken")
        assertEquals(setOf("at"), o.kept())
        // a volume already under 5 % free keeps nothing, however small
        volume.free = 4_999
        assertFalse(o.keepFrames(key(o, "tiny"), mapOf(0 to ByteArray(1)), 1))
        assertEquals(setOf("at"), o.kept())
        o.close()
    }

    /** Counts renames: each whole-file replace of the store (a frame index, a manifest shard) ends in one. */
    private class Counting(val inner: DeviceFiles) : DeviceFiles by inner {
        var renames = 0
        override fun rename(from: String, to: String) { renames++; inner.rename(from, to) }
    }

    @Test
    fun a_medium_read_is_kept_in_batches_not_frame_by_frame() = runTest {
        val files = Counting(SizedVolume())
        val t = PlayedTenant(files)
        try {
            val s = t.scope("s1")
            val n = t.node(s, "n1")
            t.medium(n, "v1", frames = 20, video = true)
            t.remember()
            val a = t.resume()
            val tenant = a.open(a.tenants.first())
            val item = assertNotNull(MemoryMedia.list(tenant, s.id, n.id)).items.single()
            val before = files.renames
            var frames = 0
            assertTrue(MemoryMedia.stream(tenant, item, item.original) { frames++ })
            assertEquals(20, frames)
            val writes = files.renames - before
            // two batches (16 + 4), each one frame index and one shard
            assertTrue(writes <= 4, "the frames were kept one write at a time: $writes renames for 20 frames")
            assertEquals((0 until 20).toSet(), a.offline!!.frames(a.offline!!.blobKey("t1", s.id, t.id("v1-o"))))
            a.retire()
        } finally {
            removeAll(t.root)
        }
    }
}
