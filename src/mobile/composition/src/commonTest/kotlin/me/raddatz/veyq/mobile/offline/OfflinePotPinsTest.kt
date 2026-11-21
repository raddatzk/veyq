package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.memories.MemoriesKept

import me.raddatz.veyq.mobile.expenses.ExpensesKept

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.expenses.Allocation
import me.raddatz.veyq.mobile.expenses.Contribution
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.ParticipantBody
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.memories.MemoriesSearch
import me.raddatz.veyq.mobile.search.ScopeDocumentsContext
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Pot pinned and a space's media kept (ADR 0153 decisions 6 and 7) over a
 * server the test plays ([PlayedTenant]), real seals throughout: a pinned Pot fetches its ledger, its receipts and its
 * cover and keeps them through "Clear cache", opening offline; a Pot unpinned and never viewed goes with the next "Clear
 * cache"; search offline finds a medium of a Memory never opened, from the space's kept listing; the space's listing goes
 * with "Clear cache" and with the space itself.
 *
 * Bites (each one edit, seen red, then restored): (a) `AccountOffline.belowNode` without the Pot's ledger → the pinned
 * Pot's ledger is swept, it does not open offline; (b) `MemoriesKept.SCOPE_MEDIA` among `KeptRead.PROTECTED` → "Clear cache"
 * keeps the space's listing.
 */
class OfflinePotPinsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private fun play() = PlayedTenant().also { played += it }

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private fun pinsOf(a: VeyqAccount): OfflinePins = assertNotNull(a.offlinePins).also { it.use(AppKinds.pins) }

    private suspend fun AccountOffline.kept(kind: String): List<EntryKey> = store.entries().map { it.key }.filter { it.kind == kind }

    private suspend fun AccountOffline.frames(t: PlayedTenant): Set<String> = kept(KeptRead.FRAMES).map { t.nameOf(it.id) }.toSet()

    private fun expense(title: String, amount: Long, receipts: List<String> = emptyList()) = Expense(
        title, "food", "2026-07-01", "EUR", 2, "1", amount, receipts, listOf(Contribution(ANNA, amount)),
        Allocation(emptyList(), me.raddatz.veyq.mobile.expenses.Split.Equal(listOf(ANNA, BEN))),
    )

    /** A Pot with Anna, Ben, a dinner with one receipt and a cover, in [s]. */
    private fun pot(t: PlayedTenant, s: PlayedTenant.Scope, label: String): PlayedTenant.Node {
        val p = t.pot(s, label)
        t.revise(p, ANNA, ParticipantBody("Anna", true, 1))
        t.revise(p, BEN, ParticipantBody("Ben", true, 2))
        val r = t.receipt(p, "$label-receipt", DINNER)
        t.revise(p, DINNER, expense("Dinner", 4200, listOf(r.id)))
        t.cover(p, "$label-cover")
        return p
    }

    @Test
    fun a_pinned_pot_keeps_its_ledger_receipts_and_cover_through_clear_cache_and_opens_offline() = runTest {
        val t = play()
        val s = t.scope("s1")
        val p = pot(t, s, "p1")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        assertTrue(pins.pinnable(PotSession.KIND), "a Pot is not offered Available offline")
        val plan = assertNotNull(pins.plan(tenant, s.id, p.id))
        assertEquals(2, plan.blobs, "a Pot's pin keeps its receipt and its cover")
        assertTrue(pins.pin(tenant, s.id, p.id))
        pins.sync()
        val o = assertNotNull(a.offline)
        assertEquals(setOf("p1-receipt", "p1-cover"), o.frames(t))
        assertEquals(Pin.State.KEPT, pins.pins.value.single().state)
        o.clearCache()
        assertEquals(setOf("p1-receipt", "p1-cover"), o.frames(t), "Clear cache dropped the pinned Pot's blobs")
        assertEquals(1, o.kept(ExpensesKept.POT_REVISIONS.kind).size, "Clear cache dropped the pinned Pot's ledger")
        a.retire()

        t.reachable = false
        val off = t.resume()
        val session = assertNotNull(PotSession.open(tenantOf(off), s.id, p.id))
        assertNotNull(session.offline)
        assertEquals(listOf("Dinner"), PotView(session).entries.map { it.title })
        val receipt = session.receipts.single()
        assertContentEquals("receipt p1-receipt".encodeToByteArray(), session.openReceipt(receipt.receiptId))
        off.retire()
    }

    @Test
    fun a_pot_unpinned_and_never_viewed_goes_with_clear_cache() = runTest {
        val t = play()
        val s = t.scope("s1")
        val p = pot(t, s, "p1")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        val pins = pinsOf(a)
        pins.pin(tenant, s.id, p.id)
        pins.sync()
        val o = assertNotNull(a.offline)
        assertEquals(1, o.kept(ExpensesKept.POT_REVISIONS.kind).size)
        pins.unpin(p.id)
        o.clearCache()
        assertEquals(emptyList(), o.kept(ExpensesKept.POT_REVISIONS.kind), "an unpinned Pot's ledger stayed")
        assertEquals(emptySet(), o.frames(t), "an unpinned Pot's receipt stayed")
        a.retire()
    }

    /** A space with a Memory of two photos, the Memory never opened; its documents as the search reads them. */
    private suspend fun search(tenant: TenantSession, s: PlayedTenant.Scope, tag: String? = null) = MemoriesSearch.scopeMediaDocuments(
        ScopeDocumentsContext(tenant, s.id, tag, emptyMap(), tenant.trees()[s.id].orEmpty().associateBy { it.id }) { if (it) "Video" else "Photo" },
    )

    @Test
    fun search_offline_finds_a_medium_of_a_memory_never_opened_from_the_kept_space_listing() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "beach", fileName = "beach.jpg")
        t.medium(m, "dunes", fileName = "dunes.jpg")
        t.remember()
        val a = t.resume()
        val online = assertNotNull(search(tenantOf(a), s))
        assertEquals(setOf("beach.jpg", "dunes.jpg"), online.documents.map { it.name }.toSet())
        // unchanged: a 304, the documents before stand
        assertNull(search(tenantOf(a), s, online.tag))
        a.retire()

        t.reachable = false
        val off = t.resume()
        val tenant = tenantOf(off)
        val found = assertNotNull(search(tenant, s), "offline the space's listing was not read from the store")
        assertEquals(setOf("beach.jpg", "dunes.jpg"), found.documents.map { it.name }.toSet())
        // the Memory itself was never opened: its own listing is not kept
        assertNull(assertNotNull(off.offline).store.entries().firstOrNull { it.key.kind == MemoriesKept.NODE_MEDIA.kind })
        off.retire()
    }

    @Test
    fun a_spaces_media_listing_goes_with_clear_cache_and_with_the_space() = runTest {
        val t = play()
        val s1 = t.scope("s1")
        t.medium(t.node(s1, "m1"), "one")
        val s2 = t.scope("s2")
        t.medium(t.node(s2, "m2"), "two")
        t.remember()
        val a = t.resume()
        val tenant = tenantOf(a)
        search(tenant, s1)
        search(tenant, s2)
        val o = assertNotNull(a.offline)
        assertEquals(setOf(s1.id, s2.id), o.kept(MemoriesKept.SCOPE_MEDIA.kind).map { it.scope }.toSet())
        // the account leaves s2: the next read of the scopes sweeps its listing
        s2.member = false
        tenant.forgetTree()
        tenant.nodes()
        assertEquals(setOf(s1.id), o.kept(MemoriesKept.SCOPE_MEDIA.kind).map { it.scope }.toSet(), "a space left kept its media listing")
        o.clearCache()
        assertEquals(emptyList(), o.kept(MemoriesKept.SCOPE_MEDIA.kind), "Clear cache kept a space's media listing")
        assertFalse(o.store.entries().any { it.key.kind == MemoriesKept.SCOPE_MEDIA.kind && it.pins.isNotEmpty() })
        a.retire()
    }

    private companion object {
        const val ANNA = "0e000000-0000-4000-8000-000000000001"
        const val BEN = "0e000000-0000-4000-8000-000000000002"
        const val DINNER = "0e000000-0000-4000-9000-000000000001"
    }
}
