package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeader
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What phase 5's screens show of the offline store (ADR 0140 decision 10), as core screen-model state the platforms only
 * draw: a node's "Available offline" toggle (asked first, its status line), the header read-only offline, the shell's
 * banner and its notice of a pin dropped — named from the tree the shell holds, else nameless —, and Settings' "Storage
 * on this device" (its limit as a free size or Off, its items named from the held tree, Clear cache, Remove all asked first).
 *
 * Bites (each one edit, seen red, then restored): (a) the toggle pins at once (`toggleOffline` without the ask) → the
 * node is pinned before the person confirmed; (b) the shell names no dropped pin (`heldNames` not read) → the notice is
 * nameless where the shell held the name; (c) "Remove all offline content" without its question (`removeAllOffline` not
 * checking `askingRemoveAll`) → the pins go on the first tap; (d) `NodeHeader.rights` offering "Available offline"
 * offline whatever the toggle's state → a node not kept offers its pin offline.
 */
class OfflineScreensTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private fun play() = PlayedTenant().also { played += it }

    // ---- the words and the size field

    @Test
    fun the_limit_field_reads_and_writes_any_size_or_off() {
        assertEquals(LimitField("2", LimitUnit.GB, false), OfflineShown.field(AccountOffline.PHONE_LIMIT))
        assertEquals(LimitField("10", LimitUnit.GB, false), OfflineShown.field(AccountOffline.DESKTOP_LIMIT))
        assertEquals(LimitField("2.5", LimitUnit.GB, false), OfflineShown.field(2_500_000_000))
        assertEquals(LimitField("500", LimitUnit.MB, false), OfflineShown.field(500_000_000))
        assertEquals(LimitField("", LimitUnit.GB, true), OfflineShown.field(0))
        // the reader's decimal sign: a German UI writes "2,5", and what it writes reads back
        assertEquals(LimitField("2,5", LimitUnit.GB, false), OfflineShown.field(2_500_000_000, ','))
        assertEquals(LimitField("1,25", LimitUnit.GB, false), OfflineShown.field(1_250_000_000, ','))
        assertEquals(LimitField("2", LimitUnit.GB, false), OfflineShown.field(AccountOffline.PHONE_LIMIT, ','))
        assertEquals(2_500_000_000, OfflineShown.parse("2,5", LimitUnit.GB))
        assertEquals(2_500_000_000, OfflineShown.parse(" 2.5 ", LimitUnit.GB))
        assertEquals(750_000_000, OfflineShown.parse("750", LimitUnit.MB))
        assertEquals(1_250_000, OfflineShown.parse("1.25", LimitUnit.MB))
        for (bad in listOf("", "0", "-1", "abc", "1.234", "1e9", "99999999")) assertNull(OfflineShown.parse(bad, LimitUnit.GB), bad)
        // what the field shows reads back to the same limit
        for (b in listOf(AccountOffline.PHONE_LIMIT, 2_500_000_000, 500_000_000, 1_250_000_000)) for (sign in listOf('.', ',')) {
            val f = OfflineShown.field(b, sign)
            assertEquals(b, OfflineShown.parse(f.amount, f.unit))
        }
    }

    @Test
    fun the_banner_and_the_notice_of_a_pin_gone() {
        assertNull(OfflineShown.banner(null))
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(42)), OfflineShown.banner(OfflineAsOf(42)))
        assertEquals(Notice(Msg.OFFLINE_BANNER_UNKNOWN), OfflineShown.banner(OfflineAsOf(null)))
        assertEquals(Notice(Msg.OFFLINE_GONE_NAMED, "Summer"), OfflineShown.gone("Summer"))
        assertEquals(Notice(Msg.OFFLINE_GONE), OfflineShown.gone(null))
    }

    // ---- a node's toggle

    private class Nodes : OfflineNodes {
        override val pins = MutableStateFlow<List<Pin>>(emptyList())
        override val progress = MutableStateFlow<Map<String, OfflineFetching.Progress>>(emptyMap())
        val did = mutableListOf<String>()
        override fun pinnable(kind: String) = kind == "MEMORY"
        override suspend fun plan(ref: NodeAt): Long? = 12_000_000L.also { did += "plan" }
        override suspend fun pin(ref: NodeAt): Boolean {
            did += "pin"
            pins.value = pins.value + Pin("t", ref.scopeId, ref.nodeId, ref.kind)
            return true
        }
        override suspend fun unpin(ref: NodeAt) {
            did += "unpin"
            pins.value = pins.value.filter { it.node != ref.nodeId }
        }
    }

    private class Door(val nodes: OfflineNodes?, val reach: StateFlow<Boolean> = MutableStateFlow(true)) : NodeHeaderModel.Door {
        override fun isFavorite(ref: NodeAt) = false
        override fun inIdentityFolder(ref: NodeAt) = false
        override fun linksOn() = true
        override suspend fun reportOffered() = false
        override suspend fun toggleFavorite(ref: NodeAt) = true
        override suspend fun trash(ref: NodeAt): ShellModel.Trashed = throw UnsupportedOperationException()
        override suspend fun undoTrash(node: OpenNode): Notice? = null
        override suspend fun rename(ref: NodeAt, name: String): ShellModel.Renamed = throw UnsupportedOperationException()
        override fun closeOpen(ref: NodeAt) {}
        override fun changed() {}
        override fun offline() = nodes
        override val online get() = reach
    }

    private val memory = NodeAt("s", "m", "MEMORY", "Summer")

    private fun TestScope.header(node: NodeAt, door: Door) = NodeHeaderModel(node, AppKinds.Facts(), door, backgroundScope).also { runCurrent() }

    @Test
    fun available_offline_asks_the_size_first_and_shows_where_its_fetch_is() = runTest {
        val nodes = Nodes()
        val h = header(memory, Door(nodes))
        assertEquals(OfflineToggle(on = false, status = OfflineStatus.OFF, said = null), h.current.offline)
        assertTrue(me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE in assertNotNull(h.plan(2)).menu, "the toggle is not in the header's menu")
        assertEquals(Msg.OFFLINE_AVAILABLE, h.label(me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE))

        // on: the size asked, nothing kept until confirmed
        assertNull(h.toggleOffline())
        runCurrent()
        assertEquals(listOf("plan"), nodes.did, "the node was pinned before the person confirmed its size")
        assertEquals(12_000_000L, h.current.offline?.asking)
        assertEquals(Notice(Msg.OFFLINE_PIN_ASK, Bytes(12_000_000)), h.current.offline?.question)
        h.cancelOffline()
        runCurrent()
        assertNull(h.current.offline?.asking)
        assertNull(h.confirmOffline(), "a confirm with nothing asked")
        assertEquals(listOf("plan"), nodes.did)
        h.toggleOffline()
        assertNull(h.confirmOffline())
        runCurrent()
        assertEquals(listOf("plan", "plan", "pin"), nodes.did)
        assertEquals(OfflineStatus.WAITING, h.current.offline?.status)
        assertEquals(true, h.current.offline?.on)
        assertNull(h.current.offline?.asking)

        // its fetch, then kept
        nodes.progress.value = mapOf("m" to OfflineFetching.Progress(3_000_000, 12_000_000))
        runCurrent()
        assertEquals(OfflineStatus.FETCHING, h.current.offline?.status)
        assertEquals(Notice(Msg.OFFLINE_STATUS_FETCHING, Bytes(3_000_000), Bytes(12_000_000)), h.current.offline?.said)
        nodes.progress.value = emptyMap()
        nodes.pins.value = nodes.pins.value.map { it.copy(state = Pin.State.KEPT) }
        runCurrent()
        assertEquals(Notice(Msg.OFFLINE_STATUS_KEPT), h.current.offline?.said)
        nodes.pins.value = nodes.pins.value.map { it.copy(state = Pin.State.NO_SPACE) }
        runCurrent()
        assertEquals(Notice(Msg.OFFLINE_STATUS_NO_SPACE), h.current.offline?.said)

        // off: at once, nothing asked
        h.toggleOffline()
        runCurrent()
        assertEquals(listOf("plan", "plan", "pin", "unpin"), nodes.did)
        assertEquals(false, h.current.offline?.on)
    }

    @Test
    fun a_kind_not_pinned_or_no_store_offers_no_toggle_and_offline_the_header_writes_nothing() = runTest {
        assertNull(header(memory, Door(null)).current.offline)
        assertNull(header(NodeAt("s", "p", "EXPENSES_POT", "Rome"), Door(Nodes())).current.offline)
        assertFalse(me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE in assertNotNull(header(memory, Door(null)).plan(9)).menu)

        val reach = MutableStateFlow(true)
        val h = header(NodeAt("s", "f", "MEMORY", "Trip"), Door(Nodes(), reach))
        assertEquals(true, h.current.rights?.rename)
        reach.value = false
        runCurrent()
        val r = assertNotNull(h.current.rights)
        assertEquals(listOf(false, false, false, false, false, false, false), listOf(r.favorite, r.share, r.rename, r.move, r.cover, r.report, r.delete), "offline the header still offers a write")
        assertFalse(r.offline, "offline a node not kept offered its pin")
        reach.value = true
        runCurrent()
        assertEquals(true, h.current.rights?.rename)
    }

    @Test
    fun offline_a_pin_is_not_offered_nor_made_and_one_kept_can_still_be_turned_off() = runTest {
        val nodes = Nodes()
        val reach = MutableStateFlow(true)
        val h = header(memory, Door(nodes, reach))
        // a size asked online is no question once the network went: it could not fetch
        assertNull(h.toggleOffline())
        runCurrent()
        assertEquals(12_000_000L, h.current.offline?.asking)
        reach.value = false
        runCurrent()
        assertNull(h.current.offline?.asking, "offline a pin's size is still asked")
        assertNull(h.confirmOffline(), "a confirm with nothing asked any more")
        // not kept: not offered, and a call that comes anyway is refused before anything is asked of the server
        assertEquals(false, h.current.rights?.offline, "offline the header offers a pin it cannot fetch")
        assertFalse(me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE in assertNotNull(h.plan(9)).menu)
        assertEquals(Notice(Msg.OFFLINE_READ_ONLY), h.toggleOffline())
        runCurrent()
        assertEquals(listOf("plan"), nodes.did, "offline a pin was planned or made")
        assertEquals(false, h.current.offline?.on)

        // kept already: offered offline, and turned off at once (this device's own, no server asked)
        reach.value = true
        runCurrent()
        h.toggleOffline()
        h.confirmOffline()
        runCurrent()
        assertEquals(true, h.current.offline?.on)
        reach.value = false
        runCurrent()
        assertEquals(true, h.current.rights?.offline, "offline a kept node cannot be turned off")
        assertNull(h.toggleOffline())
        runCurrent()
        assertEquals(listOf("plan", "plan", "pin", "unpin"), nodes.did)
        assertEquals(false, h.current.rights?.offline, "unpinned offline, its toggle stays offered")
    }

    // ---- the shell's notice of a pin gone

    private suspend fun tenantOf(a: VeyqAccount) = a.open(a.tenants.first())

    @Test
    fun a_pin_gone_is_named_from_the_tree_the_shell_held_else_nameless() = runTest {
        val t = play()
        val s = t.scope("s1")
        val held = t.node(s, "held", name = "Summer")
        t.medium(held, "h1")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = assertNotNull(a.offlinePins).also { it.use(AppKinds.pins) }
        pins.pin(tenant, s.id, held.id)
        pins.sync()
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shell.load(openLastUsed = false)
        assertEquals(listOf("Summer"), shell.current!!.lists.nodes.map(NodeRef::name))

        // the node trashed on the server: the pin's sync drops it, the shell names it from what it held
        t.trashNode(held)
        pins.sync()
        val said = waitFor { shell.current?.gone?.takeIf { it.isNotEmpty() } }
        assertEquals(listOf(Notice(Msg.OFFLINE_GONE_NAMED, "Summer")), said)
        shell.goneSeen()
        assertEquals(emptyList(), shell.current!!.gone)
        assertEquals(emptyList(), pins.gone.value, "said, the notice stays in the store")

        // a pin dropped before this shell held its node: nameless
        val unseen = t.node(s, "unseen", name = "Winter")
        t.medium(unseen, "u1")
        tenant.forgetTree()
        pins.pin(tenant, s.id, unseen.id)
        pins.sync()
        t.trashNode(unseen)
        pins.sync()
        assertEquals(listOf(Notice(Msg.OFFLINE_GONE)), waitFor { shell.current?.gone?.takeIf { it.isNotEmpty() } })
        a.retire()
    }

    /** [read]'s first non-null answer, the shell's watches running on their own thread: at most two seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(200) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return read()
    }

    // ---- Settings' "Storage on this device"

    private class Here : AccountSettings.Here<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession> {
        override fun trust() = AccountSettings.Trust(null, null, emptyList())
        override suspend fun stay(level: StaySignedIn) = TrustChange.DONE
        override suspend fun trustWithPassword(password: String) = TrustChange.DONE
        override suspend fun stopTrusting() {}
        override suspend fun signOut(): SessionModel.SignedOut<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession> = SessionModel.SignedOut(true, null)
    }

    @Test
    fun the_storage_section_sets_the_limit_names_its_items_and_asks_before_removing_all() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory", name = "Summer")
        t.medium(m, "m1")
        val viewed = t.node(s, "viewed", name = "Viewed")
        t.medium(viewed, "v1")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = assertNotNull(a.offlinePins).also { it.use(AppKinds.pins) }
        pins.pin(tenant, s.id, m.id)
        pins.sync()
        // a medium viewed (the automatic tier)
        val g = assertNotNull(me.raddatz.veyq.mobile.memories.MemoryMedia.list(tenant, s.id, viewed.id))
        me.raddatz.veyq.mobile.memories.MemoryMedia.read(tenant, g.items.single(), g.items.single().thumbnail!!)

        val page = AccountSettings(AccountSettings.Over(a), Here(), a.email) { 0L }
        page.refresh()
        val st = assertNotNull(page.current.storage, "a device keeping a store shows no section")
        assertNull(page.current.storageUnavailable)
        assertEquals(LimitField("10", LimitUnit.GB, false), st.field)
        assertTrue(st.automatic > 0 && st.pinned > 0, "the sizes say nothing kept: $st")
        val item = st.items.single()
        assertEquals(Notice(Msg.OFFLINE_ITEM_NAMED, "Summer"), item.title)
        assertEquals(OfflineStatus.KEPT, item.status)
        assertTrue(item.bytes > 0)

        // the limit: a free size, refused when it is none, Off
        page.setOfflineLimit("0,5", LimitUnit.GB)
        assertEquals(500_000_000, page.current.storage?.limit)
        // the field in the reader's decimal sign (the page opened with the platform's): a German UI reads "1,5"
        page.setOfflineLimit("1.5", LimitUnit.GB)
        val german = AccountSettings(AccountSettings.Over(a), Here(), a.email, ',') { 0L }
        german.refresh()
        assertEquals(LimitField("1,5", LimitUnit.GB, false), german.current.storage?.field)
        assertEquals(LimitField("1.5", LimitUnit.GB, false), page.current.storage?.field)
        page.setOfflineLimit("0,5", LimitUnit.GB)
        page.setOfflineLimit("lots", LimitUnit.GB)
        assertEquals(Msg.ACCOUNT_SETTINGS_STORAGE_LIMIT_INVALID, page.current.refusal)
        assertEquals(500_000_000, page.current.storage?.limit)
        page.turnOfflineLimitOff()
        assertEquals(true, page.current.storage?.field?.off)
        // what is left outside the pins is what an offline unlock opens with (never evicted, ADR 0140 decision 8)
        assertEquals(emptyList(), assertNotNull(a.offline).store.entries().filter { it.pins.isEmpty() && it.key.kind !in KeptRead.PROTECTED }.map { it.key.kind }, "the automatic tier off kept what it held")
        page.setOfflineLimit("10", LimitUnit.GB)

        // clear cache keeps the pin; remove all only once asked
        me.raddatz.veyq.mobile.memories.MemoryMedia.read(tenant, g.items.single(), g.items.single().thumbnail!!)
        page.refresh()
        assertTrue(assertNotNull(page.current.storage).automatic > 0, "the medium viewed again is not kept")
        page.clearOfflineCache()
        assertEquals(1, page.current.storage?.items?.size)
        // "Recently viewed" shows nothing after it: what an account opens with is kept (an offline unlock needs it), but is
        // no content viewed, so it is not counted there (phase 6: Clear cache left about 19 kB showing)
        assertEquals(0L, page.current.storage?.automatic, "Recently viewed after Clear cache")
        assertTrue(assertNotNull(a.offline).store.entries().any { it.key.kind in KeptRead.OPENING }, "the opening answers went with the cache")
        page.removeAllOffline()
        assertEquals(1, page.current.storage?.items?.size, "removed all without asking")
        page.askRemoveAllOffline()
        assertTrue(page.current.askingRemoveAll)
        page.cancelRemoveAllOffline()
        page.removeAllOffline()
        assertEquals(1, page.current.storage?.items?.size, "removed all after the question was cancelled")
        page.askRemoveAllOffline()
        page.removeAllOffline()
        assertFalse(page.current.askingRemoveAll)
        assertEquals(emptyList(), page.current.storage?.items)
        assertEquals(0L, page.current.storage?.pinned)

        // an item removed by its row
        pins.pin(tenant, s.id, m.id)
        pins.sync()
        page.refresh()
        assertEquals(1, page.current.storage?.items?.size)
        page.unpinOffline(m.id)
        assertEquals(emptyList(), page.current.storage?.items)
        a.retire()
    }
}
