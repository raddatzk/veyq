package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins and their sync (ADR 0140 decision 9) over a server the test plays ([PlayedTenant]), real seals throughout: a pin's
 * size told before its first byte, its fetch (every tile, a video's original, never a photo's), its sync after a medium was
 * added, a fetch interrupted and continued, a re-key that refetches nothing, a pin two pins share, a pin of a node trashed,
 * blocked or in a suspended tenant, and one store per account in the process.
 *
 * Bites (each one edit, seen red, then restored): (a) unpin by dropping every entry the node's pin reached
 * (`AccountOffline.removePin`) → the chapter's frames, which the chapter's own pin still reaches, go; (b) keep pins outside
 * the sweep (no `settlePins`, a pinned entry never swept) → the trashed Memory's pin and frames stay; (c) clear a blob's
 * frames when its listing's revision moved (`AccountOffline.keep`) → the re-key's sync fetches every frame again.
 */
class OfflinePinsTest {
    private val played = mutableListOf<PlayedTenant>()

    private fun play() = PlayedTenant().also { played += it }

    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        played.forEach { removeAll(it.root) }
        dirs.forEach { removeAll(it) }
    }

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private fun pinsOf(a: VeyqAccount): OfflinePins = assertNotNull(a.offlinePins).also { it.use(AppKinds.pins) }

    /** The blobs whose frames the store holds, by the test's names, each with the frame indexes held. */
    private suspend fun AccountOffline.frames(t: PlayedTenant): Map<String, Set<Int>> =
        store.entries().filter { it.key.kind == KeptRead.FRAMES }.associate { t.nameOf(it.key.id) to store.frames(it.key) }

    @Test
    fun a_pinned_memory_is_fetched_whole_after_its_size_and_a_new_medium_is_kept_at_the_next_sync() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "photo", frames = 2)
        t.medium(m, "video", frames = 3, video = true)
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        // the size first: two tiles and the video's three frames, the photo's original never
        val plan = assertNotNull(pins.plan(tenant, s.id, m.id))
        assertEquals(3, plan.blobs)
        assertTrue(plan.bytes > 0)
        val gets = t.storeGets
        assertTrue(pins.pin(tenant, s.id, m.id))
        pins.sync()
        val o = assertNotNull(a.offline)
        assertEquals(mapOf("photo-t" to setOf(0), "video-t" to setOf(0), "video-o" to setOf(0, 1, 2)), o.frames(t))
        assertEquals(gets + 5, t.storeGets, "not exactly the pin's five frames were fetched")
        assertEquals(Pin.State.KEPT, pins.pins.value.single().state)
        assertTrue(o.store.entries().filter { it.key.kind == KeptRead.FRAMES }.all { it.pins == setOf(m.id) }, "a pinned blob does not carry its pin")
        assertEquals(0L, assertNotNull(pins.plan(tenant, s.id, m.id)).bytes, "a kept pin still has bytes to fetch")

        // another device adds a medium; the next sync (the next online open) keeps it
        t.medium(m, "later")
        pins.sync()
        assertEquals(setOf(0), o.frames(t)["later-t"], "the medium added after the pin was not kept")
        a.retire()

        // offline, after the account was closed: every pinned tile and the video open from the store
        t.reachable = false
        val off = t.resume()
        assertIs<Reach.Offline>(off.reach.value)
        val g = assertNotNull(MemoryMedia.list(tenantOf(off), s.id, m.id))
        val video = g.items.first { t.nameOf(it.mediaId) == "video" }
        val played = ArrayList<String>()
        MemoryMedia.stream(tenantOf(off), video, video.original) { played += it.decodeToString() }
        assertEquals((0 until 3).map { "original video frame $it" }, played)
        assertEquals(setOf("photo", "video", "later"), g.items.map { t.nameOf(it.mediaId) }.toSet())
        off.retire()
    }

    @Test
    fun an_interrupted_fetch_continues_where_it_stopped() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "video", frames = 40, video = true, thumbnail = false)
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        pins.pin(tenant, s.id, m.id)
        // the network goes after 20 frames: those are kept (a batch of 16 and what was fetched after it), the pass ends, the pin waits
        val start = t.storeGets
        t.cutAfterStoreGets = start + 20
        runCatching { pins.sync() }
        val o = assertNotNull(a.offline)
        val kept = o.frames(t)["video-o"].orEmpty()
        assertEquals((0 until 20).toSet(), kept, "not the 20 frames fetched before the cut were kept")
        assertEquals(Pin.State.WAITING, pins.pins.value.single().state)
        t.cutAfterStoreGets = null
        t.reachable = true
        a.retire()
        val again = t.resume()
        val more = pinsOf(again)
        val before = t.storeGets
        more.sync()
        assertEquals((0 until 40).toSet(), assertNotNull(again.offline).frames(t)["video-o"])
        assertEquals(before + 40 - kept.size, t.storeGets, "a kept frame was fetched again")
        assertEquals(Pin.State.KEPT, more.pins.value.single().state)
        again.retire()
    }

    @Test
    fun a_re_key_keeps_every_pinned_frame_and_the_memory_opens_offline_after_it() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "photo")
        t.medium(m, "video", frames = 3, video = true)
        t.remember()
        val a = t.resume()
        val pins = pinsOf(a)
        pins.pin(tenantOf(a), s.id, m.id)
        pins.sync()
        val o = assertNotNull(a.offline)
        val before = o.frames(t)
        assertEquals(3, before.size)
        val gets = t.storeGets
        // the Memory re-keyed (a fresh key, every K_blob re-wrapped, the tree's and the media's revisions moved)
        t.rekey(m)
        pins.sync()
        assertEquals(before, o.frames(t), "a re-key changed the pinned frames")
        assertEquals(gets, t.storeGets, "a re-key refetched frames")
        a.retire()

        // offline after it: the kept listing's new wraps open the kept frames
        t.reachable = false
        val off = t.resume()
        val g = assertNotNull(MemoryMedia.list(tenantOf(off), s.id, m.id))
        val video = g.items.first { t.nameOf(it.mediaId) == "video" }
        assertEquals("original video frame 2", MemoryMedia.read(tenantOf(off), video, video.original)!!.decodeToString().takeLast(22))
        assertEquals("thumbnail photo", MemoryMedia.read(tenantOf(off), g.items.first { t.nameOf(it.mediaId) == "photo" }, g.items.first { t.nameOf(it.mediaId) == "photo" }.thumbnail!!)!!.decodeToString())
        off.retire()
    }

    @Test
    fun unpinning_a_node_another_pin_still_reaches_keeps_the_shared_blobs() = runTest {
        val t = play()
        val s = t.scope("s1")
        val memory = t.node(s, "memory")
        val chapter = t.node(s, "chapter", kind = "CHAPTER", parent = memory)
        t.medium(memory, "m1")
        t.medium(chapter, "c1")
        t.remember()
        val a = t.resume()
        val o = assertNotNull(a.offline)
        // the automatic tier off: what no pin reaches is not kept
        o.setLimit(0)
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        pins.pin(tenant, s.id, memory.id)
        pins.pin(tenant, s.id, chapter.id)
        pins.sync()
        assertEquals(setOf("m1-t", "c1-t"), o.frames(t).keys)
        assertEquals(setOf(memory.id, chapter.id), o.store.entries().first { t.nameOf(it.key.id) == "c1-t" }.pins)
        pins.unpin(memory.id)
        assertEquals(setOf("c1-t"), o.frames(t).keys, "unpinning the Memory dropped what the chapter's pin still reaches (or kept its own)")
        assertEquals(setOf(chapter.id), o.store.entries().first { t.nameOf(it.key.id) == "c1-t" }.pins)
        assertEquals(listOf(chapter.id), pins.pins.value.map { it.node })
        a.retire()
    }

    @Test
    fun a_pin_of_a_node_trashed_on_the_server_is_dropped_with_its_notice() = runTest {
        val t = play()
        val s = t.scope("s1")
        val gone = t.node(s, "gone")
        val stays = t.node(s, "stays")
        t.medium(gone, "g1")
        t.medium(stays, "s1m")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        pins.pin(tenant, s.id, gone.id)
        pins.pin(tenant, s.id, stays.id)
        pins.sync()
        val o = assertNotNull(a.offline)
        assertEquals(setOf("g1-t", "s1m-t"), o.frames(t).keys)
        t.trashNode(gone)
        pins.sync()
        assertEquals(listOf(stays.id), pins.pins.value.map { it.node }, "the trashed node's pin stayed")
        assertEquals(listOf(PinGone(t.tenants.first().id, gone.id, "MEMORY")), pins.gone.value, "no notice of the pin dropped")
        assertEquals(setOf("s1m-t"), o.frames(t).keys, "the trashed node's frames stayed")
        o.seenGone()
        assertEquals(emptyList(), pins.gone.value)
        a.retire()
    }

    @Test
    fun a_pin_of_a_blocked_node_or_a_suspended_tenant_keeps_its_place_without_its_content() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "m1")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        pins.pin(tenant, s.id, m.id)
        pins.sync()
        val o = assertNotNull(a.offline)
        assertEquals(setOf("m1-t"), o.frames(t).keys)

        // the operator blocks the Memory: its content goes, its pin stays as not available
        t.block(m)
        pins.sync()
        assertEquals(Pin.State.UNAVAILABLE, pins.pins.value.single().state)
        assertEquals(emptySet(), o.frames(t).keys, "a blocked node's frames were kept")
        assertEquals(emptyList(), pins.gone.value)
        // unblocked: the next sync fetches it again
        t.unblock(m)
        pins.sync()
        assertEquals(Pin.State.KEPT, pins.pins.value.single().state)
        assertEquals(setOf("m1-t"), o.frames(t).keys)

        // the tenant answered as suspended: the same
        t.suspended += t.tenants.first().id
        runCatching { tenant.forgetTree(); a.open(a.tenants.first()) }
        assertEquals(Pin.State.UNAVAILABLE, pins.pins.value.single().state)
        assertEquals(emptySet(), o.frames(t).keys, "a suspended tenant's frames were kept")
        t.suspended.clear()
        val back = tenantOf(a)
        assertEquals(Pin.State.WAITING, pins.pins.value.single().state, "the tenant's scopes answered again, its pin still not available")
        val again = pinsOf(a)
        back.forgetTree()
        again.sync()
        assertEquals(Pin.State.KEPT, again.pins.value.single().state)
        assertEquals(setOf("m1-t"), o.frames(t).keys)
        a.retire()
    }

    @Test
    fun a_second_connection_of_the_account_shares_its_store() = runTest {
        val stores = OfflineStores(freshTestDirectory().also { dirs += it }, MemoryDeviceStore(), testPrimitives)
        val key = testPrimitives.randomBytes(32)
        val first = stores.openShared("a.example#u1", key, "http://a.example", 1_000_000)
        val second = stores.openShared("a.example#u1", key, "http://a.example", 1_000_000)
        assertSame(first, second, "a second connection opened a second store over the same directory")
        second.close()
        // the first holder still reads and writes
        first.keepFrames(first.blobKey("t1", "s1", "b"), mapOf(0 to ByteArray(10)), 10)
        assertEquals(1, first.store.entries().size)
        first.close()
        assertEquals(emptyList(), first.store.entries(), "the last holder's close left the store open")
        val third = stores.openShared("a.example#u1", key, "http://a.example", 1_000_000)
        assertTrue(third !== first)
        assertEquals(1, third.store.entries().size)
        third.close()
    }
}
