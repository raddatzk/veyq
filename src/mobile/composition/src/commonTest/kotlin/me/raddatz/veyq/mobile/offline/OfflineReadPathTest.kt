package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Unreachable
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read path through the offline store (ADR 0140 decisions 4–7) over a server the test plays ([PlayedTenant]), real
 * seals throughout: a gallery read online is opened from the store after the account was closed and unlocked again with
 * the server unreachable; an online read replaces what was kept and the sweep drops what no current answer reaches (a
 * trashed medium, a scope the account left, a node the operator blocked, a suspended tenant); a wrap the server no longer
 * serves and a refused refresh wipe the store; offline every write is refused.
 *
 * Bites (each one edit, seen red, then restored): merge a newer listing into the kept one instead of replacing it
 * (`VeyqAccount.kept`) → the trashed medium comes back; skip the sweep after a `403` → the left scope's frames stay; keep
 * the store when the wrap is not served (`notServed`) → its directory stays; treat a blocked node as reached
 * (`AccountOffline.walk`) or hide a suspended tenant without dropping it (`AccountOffline.suspend`) → the bytes stay.
 */
class OfflineReadPathTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    private fun play(files: DeviceFiles = SizedVolume(), clock: () -> Long = { 0L }) = PlayedTenant(files, clock).also { played += it }

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    /** A Memory with two photos (each a thumbnail) in a personal scope, remembered on this device. */
    private suspend fun gallery(t: PlayedTenant = play()): Triple<PlayedTenant, PlayedTenant.Node, List<PlayedTenant.Medium>> {
        val s = t.scope("s1")
        val m = t.node(s, "n1")
        val media = listOf(t.medium(m, "m1"), t.medium(m, "m2"))
        t.remember()
        return Triple(t, m, media)
    }

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    /** Each medium's tile (its thumbnail) read, as the grid reads them. */
    private suspend fun tiles(tenant: TenantSession, node: PlayedTenant.Node): Map<String, String?> {
        val g = assertNotNull(MemoryMedia.list(tenant, node.scope.id, node.id))
        return g.items.associate { node.scope.played().nameOf(it.mediaId) to MemoryMedia.read(tenant, it, it.gridBlob!!)?.decodeToString() }
    }

    private fun PlayedTenant.Scope.played(): PlayedTenant = played.first { t -> t.scopes.contains(this) }

    private suspend fun AccountOffline.blobKeys(t: PlayedTenant): Set<String> =
        store.entries().filter { it.key.kind == KeptRead.FRAMES }.map { t.nameOf(it.key.id) }.toSet()

    @Test
    fun a_gallery_viewed_online_opens_from_the_store_after_the_account_was_closed_and_unlocked_offline() = runTest {
        val (t, node, _) = gallery()
        val online = t.resume()
        assertEquals(Reach.Online, online.reach.value)
        assertEquals(mapOf("m1" to "thumbnail m1", "m2" to "thumbnail m2"), tiles(tenantOf(online), node))
        // the app goes: the account closes as a process end leaves it, nothing wiped
        online.retire()

        t.reachable = false
        val gets = t.storeGets
        val offline = t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        val tenant = tenantOf(offline)
        val g = assertNotNull(MemoryMedia.list(tenant, node.scope.id, node.id))
        assertNotNull(g.offline, "a listing from the store says as of when")
        assertEquals(listOf("m1", "m2"), g.items.map { t.nameOf(it.mediaId) }.sorted())
        assertEquals(mapOf("m1" to "thumbnail m1", "m2" to "thumbnail m2"), tiles(tenant, node))
        assertEquals(gets, t.storeGets, "a kept frame was fetched again")
        // a photo's original is never kept (decision 8): offline it is not there
        val item = g.items.first()
        assertFailsWith<Unreachable> { MemoryMedia.read(tenant, item, item.original) }
        offline.retire()
    }

    @Test
    fun a_trashed_medium_is_not_shown_from_the_store_after_the_next_online_read() = runTest {
        val (t, node, media) = gallery()
        val a = t.resume()
        val tenant = tenantOf(a)
        tiles(tenant, node)
        t.trash(media[0])
        // the next online read replaces the kept listing (a 200: the media revision moved)
        assertEquals(listOf("m2"), MemoryMedia.list(tenant, node.scope.id, node.id)!!.items.map { t.nameOf(it.mediaId) })
        t.reachable = false
        tenant.forgetTree()
        val g = assertNotNull(MemoryMedia.list(tenant, node.scope.id, node.id))
        assertNotNull(g.offline)
        assertEquals(listOf("m2"), g.items.map { t.nameOf(it.mediaId) }, "the trashed medium came back from the store")
        val o = assertNotNull(a.offline)
        assertEquals(setOf("m2-t"), o.blobKeys(t), "the trashed medium's frames outlived the sweep")
        a.retire()
    }

    @Test
    fun a_scope_the_account_was_removed_from_is_swept_with_its_blobs() = runTest {
        val t = play()
        val s1 = t.scope("s1")
        val n1 = t.node(s1, "n1")
        t.medium(n1, "m1")
        val s2 = t.scope("s2")
        val n2 = t.node(s2, "n2")
        t.medium(n2, "m2")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        tiles(tenant, n1)
        tiles(tenant, n2)
        val o = assertNotNull(a.offline)
        assertEquals(setOf("m1-t", "m2-t"), o.blobKeys(t))
        val before = o.store.used()
        // the membership ends: the tree answers 403 while the scope list still names it
        s2.member = false
        s2.stillListed = true
        tenant.forgetTree()
        assertEquals(emptyList(), tenant.nodes().filter { it.scopeId == s2.id })
        assertEquals(setOf("m1-t"), o.blobKeys(t), "the left scope's frames outlived its 403")
        assertTrue(o.store.entries().none { it.key.scope == s2.id }, "something of the left scope is still kept")
        assertTrue(o.store.used() < before)
        a.retire()
    }

    @Test
    fun a_wrap_the_server_no_longer_serves_wipes_the_store_at_resume_and_a_refused_refresh_does_too() = runTest {
        val (t, node, _) = gallery()
        val a = t.resume()
        tiles(tenantOf(a), node)
        val dir = assertNotNull(a.offline).store.directory
        a.retire()
        assertTrue(t.device.offline!!.has(t.record.key))
        assertNotNull(t.device.keptWrap(t.record.key))

        // another device's "Sign out and remove saved key": the wrap is gone from the server
        t.serveWrap = false
        val e = assertFailsWith<SignInFailure> { t.resume() }
        assertEquals(SignInFailure.Reason.DEVICE_NOT_TRUSTED, e.reason)
        assertFalse(t.device.offline!!.has(t.record.key), "the store outlived a wrap the server no longer serves")
        assertNull(deviceFiles.list(dir), "the store's directory outlived it")
        assertNull(t.device.keptWrap(t.record.key), "the local copy of the wrap outlived it")
        assertNotNull(t.device.trusted(t.record.key), "one answer of a server never makes the device forget its trust")
        // and with no wrap kept, an unreachable server opens nothing
        t.reachable = false
        assertEquals(SignInFailure.Reason.UNREACHABLE, assertFailsWith<SignInFailure> { t.resume() }.reason)

        // a refused refresh ends the remembered session, and the store with it
        t.reachable = true
        t.serveWrap = true
        val b = t.resume()
        tiles(tenantOf(b), node)
        b.retire()
        assertTrue(t.device.offline!!.has(t.record.key))
        t.refreshStatus = 401
        assertEquals(SignInFailure.Reason.SESSION_ENDED, assertFailsWith<SignInFailure> { t.resume() }.reason)
        assertFalse(t.device.offline!!.has(t.record.key), "the store outlived a refused refresh")
    }

    @Test
    fun at_reconnect_the_resume_runs_and_a_wrap_no_longer_served_ends_the_account_and_wipes_its_store() = runTest {
        val (t, node, _) = gallery()
        val a = t.resume()
        tiles(tenantOf(a), node)
        a.retire()
        t.reachable = false
        val offline = t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        assertFalse(offline.reconnect(), "an unreachable server reconnects")
        // the server is back: the resume's steps run and the account is online, its reads the server's again
        t.reachable = true
        assertTrue(offline.reconnect())
        assertEquals(Reach.Online, offline.reach.value)
        val g = assertNotNull(MemoryMedia.list(tenantOf(offline), node.scope.id, node.id))
        assertNull(g.offline)
        offline.retire()

        // offline again, and meanwhile the wrap was removed from another device
        t.reachable = false
        val again = t.resume()
        val dir = assertNotNull(again.offline).store.directory
        t.reachable = true
        t.serveWrap = false
        assertFalse(again.reconnect())
        assertEquals(Reach.Ended(SignInFailure.Reason.DEVICE_NOT_TRUSTED), again.reach.value)
        assertNull(deviceFiles.list(dir), "the store outlived the escalation once the device was reachable")
        assertNull(again.offline)
        again.retire()
    }

    @Test
    fun a_node_the_operator_blocked_and_a_suspended_tenant_are_not_shown_from_the_store_and_their_entries_are_gone() = runTest {
        val t = play()
        t.tenants += PlayedTenant.Tenant("t2", "b.example")
        val s1 = t.scope("s1")
        val kept = t.node(s1, "n1")
        t.medium(kept, "m1")
        val blocked = t.node(s1, "n2")
        t.medium(blocked, "m2")
        val other = t.scope("s3", t.tenants[1])
        val n3 = t.node(other, "n3")
        t.medium(n3, "m3")
        t.remember()
        val a = t.resume()
        val one = a.open(a.tenants.first { it.tenantId == "t1" })
        tiles(one, kept)
        tiles(one, blocked)
        val two = a.open(a.tenants.first { it.tenantId == "t2" })
        tiles(two, n3)
        val o = assertNotNull(a.offline)
        assertEquals(setOf("m1-t", "m2-t", "m3-t"), o.blobKeys(t))

        // the operator blocks n2: the tree read next leaves it out, and what was kept below it goes
        val before = o.store.used()
        t.block(blocked)
        one.forgetTree()
        assertEquals(listOf("n1"), one.nodes().map { t.nameOf(it.nodeId) })
        assertEquals(setOf("m1-t", "m3-t"), o.blobKeys(t), "a blocked node's frames stayed in the store")
        assertTrue(o.store.used() < before, "hidden without dropping: the bytes stayed")

        // tenant t2 is suspended: opening it is refused, and nothing of it stays kept (t1, the control, does)
        t.suspended += "t2"
        val refused = assertFailsWith<VeyqAccount.Refused> { a.open(a.tenants.first { it.tenantId == "t2" }) }
        assertEquals("tenant_suspended", refused.code)
        assertTrue(o.store.entries().none { it.key.tenant == "t2" }, "a suspended tenant's entries stayed in the store")
        assertEquals(setOf("m1-t"), o.blobKeys(t))
        // and offline it opens nothing from the store
        t.reachable = false
        a.retire()
        val offline = t.resume()
        assertFailsWith<Unreachable> { offline.open(offline.tenants.first { it.tenantId == "t2" }) }
        val t1 = offline.open(offline.tenants.first { it.tenantId == "t1" })
        assertEquals(mapOf("m1" to "thumbnail m1"), tiles(t1, kept))
        offline.retire()
    }

    @Test
    fun offline_the_shell_says_as_of_when_queues_what_queues_and_refuses_the_rest() = runTest {
        val (t, node, _) = gallery()
        val a = t.resume()
        val tenant = tenantOf(a)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shell.load(openLastUsed = false)
        assertNull(shell.current!!.offline)
        a.retire()
        t.reachable = false
        val offline = t.resume()
        val shellOffline = ShellModel(tenantOf(offline), Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shellOffline.load(openLastUsed = false)
        val state = assertNotNull(shellOffline.current)
        assertNotNull(state.offline, "the shell offline does not say so")
        assertEquals(listOf("n1"), state.lists.nodes.map { t.nameOf(it.nodeId) })
        val requests = t.requests.size
        // what ADR 0153 decision 3 queues waits to be sent (the device keeps the store); what it does not is refused
        assertTrue(shellOffline.createFolder(node.scope.id, null, "New").queued, "a folder made offline was not queued")
        val made = shellOffline.createMemory(node.scope.id, null, "New")
        assertEquals(Msg.OFFLINE_WAITING, made.notice?.msg)
        // typed, so a host closes its dialog on it instead of asking again with the notice as a problem (ADR 0154 decision 1)
        assertTrue(made.queued, "a Memory made offline was not answered as queued")
        assertEquals(Msg.OFFLINE_WAITING, shellOffline.rename(me.raddatz.veyq.mobile.navigation.NodeAt(node.scope.id, node.id, "MEMORY", node.name), "Other").notice?.msg)
        assertEquals(Msg.OFFLINE_READ_ONLY, shellOffline.move(me.raddatz.veyq.mobile.navigation.NodeAt(node.scope.id, node.id, "MEMORY", node.name), null).notice?.msg)
        assertEquals(3, assertNotNull(offline.outbox).count())
        assertEquals(requests, t.requests.size, "a write offline was sent")
        offline.retire()
    }

    @Test
    fun a_store_a_session_holds_is_closed_before_its_wipe() = runTest {
        val (t, node, _) = gallery()
        val a = t.resume()
        tiles(tenantOf(a), node)
        val store = assertNotNull(a.offline).store
        val dir = store.directory
        a.signOut()
        assertNull(deviceFiles.list(dir))
        // a read still under way when the account went writes nothing back into the wiped directory
        store.put(EntryKey(t.server, "t1", node.scope.id, KeptRead.NODES.kind, "late"), ByteArray(8))
        assertNull(deviceFiles.list(dir), "a store left open wrote into its wiped directory")
        assertEquals(emptyList(), store.entries())
    }

    /**
     * ADR 0144 decision 7: a scope's member list is a kept read — read online, it names a Memory's participants offline (the line, never
     * the picker); a scope the account left takes its list with it at the sweep.
     */
    @Test
    fun a_scopes_members_read_online_are_named_offline_and_go_with_a_left_scope() = runTest {
        val t = play()
        val s1 = t.scope("s1")
        s1.members[t.id("anna")] = "anna"
        t.node(s1, "n1")
        val s2 = t.scope("s2")
        t.node(s2, "n2")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        assertEquals(listOf("me", "anna"), tenant.scopeMembers(s1.id)?.map { it.username })
        assertEquals(listOf("me"), tenant.scopeMembers(s2.id)?.map { it.username })
        val o = assertNotNull(a.offline)
        assertTrue(o.store.entries().any { it.key.kind == KeptRead.MEMBERS.kind && it.key.scope == s1.id }, "the member list was not kept")
        a.retire()

        t.reachable = false
        val offline = t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        assertEquals(listOf("me", "anna"), tenantOf(offline).scopeMembers(s1.id)?.map { it.username }, "offline the kept list names the members")
        offline.retire()

        // the membership ends: the scope leaves the account's scopes, and the next open's read of them sweeps its list
        t.reachable = true
        s2.member = false
        val back = t.resume()
        tenantOf(back)
        assertTrue(assertNotNull(back.offline).store.entries().none { it.key.scope == s2.id }, "the left scope's member list outlived the sweep")
        back.retire()
    }
}
