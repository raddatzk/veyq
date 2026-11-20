package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.expenses.Allocation
import me.raddatz.veyq.mobile.expenses.Contribution
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.ParticipantBody
import me.raddatz.veyq.mobile.expenses.PotExport
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.Split
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Pot's ledger and receipts in the offline store (ADR 0153 decision 6)
 * over a server the test plays ([PlayedTenant]), real seals throughout: a Pot read online opens offline with the same
 * entries, as of when, read-only, the receipt that was shown opening and the one that was not saying so; a newer ledger
 * replaces the kept one (an erased record gone); a refused ledger drops it; a download keeps no receipt's frame.
 *
 * An entry written in the open Pot is in the kept ledger offline: a write's re-read is the whole, kept one (#1038).
 *
 * Bites (each one edit, seen red, then restored): merge the kept ledger with the newer answer in `TenantPotDoor.whole`
 * instead of replacing it → the erased record comes back offline; `PotExport` reading its receipts with `keep = true` →
 * a download keeps their frames; a write re-reading by delta (`refresh()`) instead of whole → the written entry is
 * missing offline.
 */
class OfflinePotTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Pot(val t: PlayedTenant, val node: PlayedTenant.Node, val shown: PlayedTenant.Blob, val unshown: PlayedTenant.Blob) {
        val ref get() = ObjectRef(node.scope.id, node.id, PotSession.KIND, ApplicationId.EXPENSES, node.name)
    }

    /** A Pot with Anna and Ben, a dinner and a lunch, two receipts of the dinner, in a personal scope, remembered here. */
    private suspend fun pot(): Pot {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val p = t.pot(s, "p1")
        t.revise(p, ANNA, ParticipantBody("Anna", true, 1))
        t.revise(p, BEN, ParticipantBody("Ben", true, 2))
        val shown = t.receipt(p, "r1", DINNER)
        val unshown = t.receipt(p, "r2", DINNER)
        t.revise(p, DINNER, expense("Dinner", 4200, listOf(shown.id, unshown.id)))
        t.revise(p, LUNCH, expense("Lunch", 1800))
        t.remember()
        return Pot(t, p, shown, unshown)
    }

    private fun expense(title: String, amount: Long, receipts: List<String> = emptyList()) = Expense(
        title, "food", "2026-07-01", "EUR", 2, "1", amount, receipts, listOf(Contribution(ANNA, amount)),
        Allocation(emptyList(), Split.Equal(listOf(ANNA, BEN))),
    )

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private suspend fun AccountOffline.kept(kind: String): List<EntryKey> = store.entries().map { it.key }.filter { it.kind == kind }

    private fun titles(session: PotSession?): List<String> = assertNotNull(session).let { s -> me.raddatz.veyq.mobile.expenses.PotView(s).entries.map { it.title.orEmpty() } }

    @Test
    fun a_pot_read_online_opens_offline_with_its_entries_as_of_then_read_only() = runTest {
        val pot = pot()
        val t = pot.t
        val online = t.resume()
        val model = PotModel(tenantOf(online), { 0L })
        model.open(pot.ref)
        val shownOnline = assertNotNull(model.current?.view)
        assertNull(model.current?.offline)
        assertTrue(shownOnline.canWrite)
        assertContentEquals("receipt r1".encodeToByteArray(), model.openReceipt(pot.shown.id))
        val entries = shownOnline.entries.map { it.title to it.amount }
        online.retire()

        t.reachable = false
        val offline = t.resume()
        val m = PotModel(tenantOf(offline), { 0L })
        m.open(pot.ref)
        val s = assertNotNull(m.current)
        assertFalse(s.failed, "a Pot read online did not open offline")
        assertNotNull(s.offline, "a Pot from the store says as of when")
        val view = assertNotNull(s.view)
        assertEquals(entries, view.entries.map { it.title to it.amount })
        // an entry is queued offline (ADR 0153 decision 3); its settings and people are not
        assertTrue(view.canWrite, "offline a Pot takes no entry although entries are queued")
        assertFalse(view.canRecordAll)
        assertFalse(view.canChangeSettings)
        // a write that does not queue is refused with the one notice, nothing sent
        val before = t.requests.size
        assertEquals(PotSession.WriteOutcome.OFFLINE, m.written(PotModel.Op.AddPerson("Cleo"), said = true))
        assertEquals(Notice(Msg.OFFLINE_READ_ONLY), m.refusal())
        assertEquals(before, t.requests.size, "an offline write was sent")
        // the receipt shown opens from the store; the one never shown says it is not kept
        assertContentEquals("receipt r1".encodeToByteArray(), m.openReceipt(pot.shown.id))
        assertNull(m.openReceipt(pot.unshown.id))
        assertEquals(Msg.OFFLINE_NOT_KEPT, m.receiptFailure())
        offline.retire()
    }

    @Test
    fun a_pot_never_read_says_offline_that_it_is_not_kept() = runTest {
        val pot = pot()
        val t = pot.t
        // the account and its space read online, the Pot never opened
        val online = t.resume()
        tenantOf(online).trees()
        online.retire()
        t.reachable = false
        val a = t.resume()
        val m = PotModel(tenantOf(a), { 0L })
        m.open(pot.ref)
        val s = assertNotNull(m.current)
        assertTrue(s.failed)
        assertEquals(Msg.OFFLINE_NOT_KEPT, s.failure)
        a.retire()
    }

    @Test
    fun a_newer_ledger_replaces_the_kept_one_and_an_erased_record_is_gone_from_it() = runTest {
        val pot = pot()
        val t = pot.t
        val a = t.resume()
        val tenant = tenantOf(a)
        assertEquals(listOf("Dinner", "Lunch").sorted(), titles(PotSession.open(tenant, pot.node.scope.id, pot.node.id)).sorted())
        // the lunch erased on the server; the Pot's next online open reads the whole ledger anew
        t.eraseRecord(pot.node, LUNCH)
        tenant.forgetTree()
        assertEquals(listOf("Dinner"), titles(PotSession.open(tenant, pot.node.scope.id, pot.node.id)))

        t.reachable = false
        tenant.forgetTree()
        val offline = assertNotNull(PotSession.open(tenant, pot.node.scope.id, pot.node.id))
        assertNotNull(offline.offline)
        assertEquals(listOf("Dinner"), titles(offline), "the erased record came back from the store")
        a.retire()
    }

    @Test
    fun a_ledger_the_server_refuses_drops_the_kept_one() = runTest {
        val pot = pot()
        val t = pot.t
        val a = t.resume()
        val tenant = tenantOf(a)
        PotSession.open(tenant, pot.node.scope.id, pot.node.id)
        val o = assertNotNull(a.offline)
        assertEquals(1, o.kept(KeptRead.POT_REVISIONS.kind).size)
        pot.node.refuseLedger = 404
        tenant.forgetTree()
        assertFailsWith<Exception> { PotSession.open(tenant, pot.node.scope.id, pot.node.id) }
        assertEquals(emptyList(), o.kept(KeptRead.POT_REVISIONS.kind), "a refused ledger stayed kept")

        t.reachable = false
        tenant.forgetTree()
        assertFailsWith<Exception> { PotSession.open(tenant, pot.node.scope.id, pot.node.id) }
        a.retire()
    }

    @Test
    fun a_download_keeps_no_receipt_and_a_receipt_shown_is_kept() = runTest {
        val pot = pot()
        val t = pot.t
        val a = t.resume()
        val session = assertNotNull(PotSession.open(tenantOf(a), pot.node.scope.id, pot.node.id))
        val o = assertNotNull(a.offline)
        val gets = t.storeGets
        assertNotNull(PotExport.download(session, "deleted", 0L) {})
        assertEquals(gets + 2, t.storeGets, "the download read both receipts")
        assertEquals(emptySet(), o.kept(KeptRead.FRAMES).map { t.nameOf(it.id) }.toSet(), "a download kept a receipt's frames")
        assertContentEquals("receipt r1".encodeToByteArray(), session.openReceipt(pot.shown.id))
        assertEquals(setOf("r1"), o.kept(KeptRead.FRAMES).map { t.nameOf(it.id) }.toSet())
        a.retire()
    }

    @Test
    fun an_entry_written_online_after_the_open_is_in_the_kept_ledger_offline() = runTest {
        val pot = pot()
        val t = pot.t
        // the server takes an append as `appendPotRevisions` does: each revision as its writer sealed it, the ledger moved
        t.route = route@{ r ->
            if (r.method != io.ktor.http.HttpMethod.Post || !r.url.encodedPath.endsWith("/pots/${pot.node.id}/revisions")) return@route null
            val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val request = VeyqAccount.KEPT_JSON.decodeFromString(me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest.serializer(), body)
            request.revisions.forEach {
                pot.node.revisions += PlayedTenant.PotRevision(
                    it.entryId, it.revision,
                    me.raddatz.veyq.mobile.expenses.SealedRevision(me.raddatz.veyq.mobile.crypto.unb64(it.sealedBody), me.raddatz.veyq.mobile.crypto.unb64(it.wrappedRevisionKey)),
                    it.tombstone == true,
                )
            }
            pot.node.ledgerRevision++
            200 to "{}"
        }
        val a = t.resume()
        val tenant = tenantOf(a)
        val session = assertNotNull(PotSession.open(tenant, pot.node.scope.id, pot.node.id))
        // written in the open Pot, after the open's whole read; the Pot not opened again before the network goes
        assertEquals(PotSession.WriteOutcome.WRITTEN, session.write(listOf(me.raddatz.veyq.mobile.expenses.PotWrite(TAXI, expense("Taxi", 900)))))
        assertTrue("Taxi" in titles(session))

        t.reachable = false
        tenant.forgetTree()
        val offline = assertNotNull(PotSession.open(tenant, pot.node.scope.id, pot.node.id))
        assertNotNull(offline.offline)
        assertEquals(listOf("Dinner", "Lunch", "Taxi"), titles(offline).sorted(), "an entry written online is missing from the kept ledger")
        a.retire()
    }

    private companion object {
        const val ANNA = "0e000000-0000-4000-8000-000000000001"
        const val BEN = "0e000000-0000-4000-8000-000000000002"
        const val DINNER = "0e000000-0000-4000-9000-000000000001"
        const val LUNCH = "0e000000-0000-4000-9000-000000000002"
        const val TAXI = "0e000000-0000-4000-9000-000000000003"
    }
}
