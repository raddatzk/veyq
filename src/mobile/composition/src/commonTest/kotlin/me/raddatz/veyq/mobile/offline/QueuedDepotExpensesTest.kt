package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.offline.QueuedOpen
import me.raddatz.veyq.mobile.offline.QueuedTray

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.depot.QueuedDepot
import me.raddatz.veyq.mobile.expenses.Allocation
import me.raddatz.veyq.mobile.expenses.Contribution
import me.raddatz.veyq.mobile.expenses.Deleted
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.ParticipantBody
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.QueuedExpenses
import me.raddatz.veyq.mobile.expenses.Receipts
import me.raddatz.veyq.mobile.expenses.RevisionAddress
import me.raddatz.veyq.mobile.expenses.RevisionCodec
import me.raddatz.veyq.mobile.expenses.Revisions
import me.raddatz.veyq.mobile.expenses.SealedRevision
import me.raddatz.veyq.mobile.expenses.Split
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Depot's and Expenses' writes queued offline and sent (ADR 0153 decisions 1, 3 and 4), over a played
 * server with real seals: a new Pot entry with a receipt sent, the receipt first and its `K_blob` wrapped under the Pot's
 * key at the send; the revision naming the heads the person read, not the ones the send found; an edit and a delete
 * meeting a newer revision held, the person's values kept beside the newer version; a receipt the storage has no room for
 * refused with its copy still savable; a Depot file sent into a node of the id minted offline, a name a file holds held.
 *
 * Bite (one edit, seen red, then restored): `PotWrites.seal` naming the heads held at the send instead of the ones seen
 * ([seen] ignored) → [the_revision_names_the_heads_the_person_read_not_the_ones_found_at_the_send] red.
 */
class QueuedDepotExpensesTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Setup(val t: PlayedTenant, val up: PlayedUploads, val scope: PlayedTenant.Scope, val pot: PlayedTenant.Node, val folder: PlayedTenant.Node)

    /** A Pot with Anna and Ben and a lunch, and a folder, in a personal space, read online once (so kept), remembered here. */
    private suspend fun setup(): Setup {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val p = t.pot(s, "p1")
        t.revise(p, ANNA, ParticipantBody("Anna", true, 1))
        t.revise(p, BEN, ParticipantBody("Ben", true, 2))
        t.revise(p, LUNCH, expense("Lunch", 1800))
        val f = t.node(s, "f1", kind = "FOLDER", name = "Papers")
        t.remember()
        val x = Setup(t, PlayedUploads(t), s, p, f)
        val a = t.resume()
        val tenant = a.open(a.tenants.first())
        tenant.trees()
        assertNotNull(PotSession.open(tenant, s.id, p.id))
        tenant.close()
        a.retire()
        return x
    }

    private fun expense(title: String, amount: Long, receipts: List<String> = emptyList()) = Expense(
        title, "food", "2026-07-01", "EUR", 2, "1", amount, receipts, listOf(Contribution(ANNA, amount)),
        Allocation(emptyList(), Split.Equal(listOf(ANNA, BEN))),
    )

    /** The account opened offline and the Pot as it was kept. */
    private suspend fun Setup.offline(): Pair<VeyqAccount, PotSession> {
        t.reachable = false
        val a = t.resume()
        assertIs<Reach.Offline>(a.reach.value)
        val s = assertNotNull(PotSession.open(a.open(a.tenants.first()), scope.id, pot.id), "the Pot is not kept")
        assertNotNull(s.offline)
        return a to s
    }

    private suspend fun Setup.back(a: VeyqAccount) {
        t.reachable = true
        t.route = up::route
        assertTrue(a.reconnect())
        a.awaitSending()
    }

    private fun HttpRequestData.json() = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    @Test
    fun a_new_entry_with_a_receipt_queued_offline_is_sent_its_receipt_first() = runTest {
        val x = setup()
        val (a, s) = x.offline()
        assertTrue(a.queues(Queued.POT_ENTRY))
        val form = assertNotNull(PotView(s).blankForm("2026-07-02")).copy(title = "Taxi", payers = listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Payer(ANNA, "12.00")))
        val scan = "RECEIPT-SCAN-MARKER".encodeToByteArray()
        val w = assertNotNull(QueuedExpenses.saveEntry(s, null, form, null, listOf(PotSession.PendingReceipt(RECEIPT, scan, "image/png", "scan.png", 7L)), 1))
        val queued = assertNotNull(QueuedExpenses.entry(w))
        assertEquals(null, queued.basedOn)
        x.back(a)
        assertEquals(0, a.outbox!!.count(), "the entry was not sent: ${a.outbox!!.writes().map { it.reason }}")
        assertEquals(listOf("startBlobUploads", "completeApplicationBlob", "appendPotRevisions"), x.up.operations)
        val receipt = x.up.reserved.single()
        // the wrap is bound to the Pot, the completion names the entry it belongs to
        assertEquals(x.pot.id, receipt.ownerId)
        assertEquals(queued.entryId, x.up.completions.single().second["ownerId"]!!.jsonPrimitive.content)
        val ctx = Context("blob-key", "scopeId" to x.scope.id, "ownerId" to x.pot.id, "blobId" to receipt.blobId, "role" to Receipts.ROLE)
        assertContentEquals(scan, x.up.plaintext(receipt.blobId, x.t.suite.open(ctx, x.pot.key, unb64(receipt.wrappedBlobKey))))
        val appended = x.up.appended.single()
        assertEquals(1L, appended["revision"]!!.jsonPrimitive.content.toLong())
        val body = open(x, queued.entryId, 1)
        assertEquals(listOf(RECEIPT), (body.content as Expense).receipts)
        assertEquals("Taxi", (body.content as Expense).title)
        a.retire()
    }

    @Test
    fun the_revision_names_the_heads_the_person_read_not_the_ones_found_at_the_send() = runTest {
        val x = setup()
        val (a, s) = x.offline()
        val seen = Revisions.headsToName(s.heldRevisions())
        val form = assertNotNull(PotView(s).blankForm("2026-07-02")).copy(title = "Taxi", payers = listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Payer(ANNA, "12.00")))
        val w = assertNotNull(QueuedExpenses.saveEntry(s, null, form, null, emptyList(), 1))
        // meanwhile someone else adds an entry: the heads at the send are other ones
        x.t.revise(x.pot, DINNER, expense("Dinner", 4200))
        x.back(a)
        assertEquals(0, a.outbox!!.count())
        val body = open(x, QueuedExpenses.entry(w)!!.entryId, 1)
        assertEquals(seen, body.heads, "the revision does not name the heads the person read")
        a.retire()
    }

    @Test
    fun an_edit_meeting_a_newer_revision_is_held_with_both_versions() = runTest {
        val x = setup()
        val (a, s) = x.offline()
        val editing = assertNotNull(PotView(s).editing(LUNCH))
        val edit = assertNotNull(QueuedExpenses.saveEntry(s, LUNCH, editing.form.copy(title = "Lunch (mine)"), editing.basedOn, emptyList(), 1))
        // a second change of the waiting entry keeps what the first saw
        assertNotNull(QueuedExpenses.saveEntry(s, LUNCH, editing.form.copy(title = "Lunch (mine, again)"), 7, emptyList(), 2))
        assertEquals(1L, QueuedExpenses.entry(a.outbox!!.write(edit.id)!!)!!.basedOn)
        // meanwhile someone else edits the lunch
        x.t.revise(x.pot, LUNCH, expense("Lunch (theirs)", 1900))
        x.back(a)
        val held = a.outbox!!.writes().single()
        assertEquals(QueuedWrite.State.HELD, held.state)
        assertEquals("stale_revision", held.reason)
        assertEquals("Lunch (mine, again)", (QueuedExpenses.entry(held)!!.content as Expense).title, "the person's values are gone from the held write")
        assertTrue(x.up.appended.isEmpty(), "the edit was written over the newer revision")
        // the newer version is the Pot's now
        val tenant = a.open(a.tenants.first())
        val pot = assertNotNull(PotSession.open(tenant, x.scope.id, x.pot.id))
        assertEquals("Lunch (theirs)", PotView(pot).entries.first { it.id == LUNCH }.title)
        a.retire()
    }

    /** An edit with a receipt made offline, held at the reconnect: someone else's lunch is the Pot's now. */
    private suspend fun Setup.heldEdit(): Pair<VeyqAccount, QueuedWrite> {
        val (a, s) = offline()
        val editing = assertNotNull(PotView(s).editing(LUNCH))
        val scan = "HELD-RECEIPT-MARKER".encodeToByteArray()
        assertNotNull(QueuedExpenses.saveEntry(s, LUNCH, editing.form.copy(title = "Lunch (mine)"), editing.basedOn, listOf(PotSession.PendingReceipt(RECEIPT, scan, "image/png", "scan.png", null)), 1))
        t.revise(pot, LUNCH, expense("Lunch (theirs)", 1900))
        back(a)
        val held = a.outbox!!.writes().single()
        assertEquals(QueuedWrite.State.HELD, held.state)
        return a to held
    }

    private suspend fun Setup.potModel(a: VeyqAccount) = PotModel(a.open(a.tenants.first()), { 0L }).also {
        it.open(ObjectRef(scope.id, pot.id, "EXPENSES_POT", ApplicationId.EXPENSES, "Trip"))
        assertNotNull(it.current?.view, "the Pot did not open")
    }

    /**
     * A held edit's *Open* (ADR 0153 decision 4, owner 2026-11-19 Q3): the tray's typed target, then the entry's editor with
     * the person's values and their receipt over the newer version, named; the save puts theirs on top of the newer
     * revision and the held write is gone. Bitten by the held door that never drops the write (red: it stays).
     */
    @Test
    fun a_held_edit_opens_the_persons_values_over_the_newer_version_and_its_save_lands_on_top() = runTest {
        val x = setup()
        val (a, held) = x.heldEdit()
        assertEquals(QueuedOpen.Entry(held.tenant, x.scope.id, x.pot.id, LUNCH, held.id), QueuedTray(a).opens(held.id))
        val model = x.potModel(a)
        val editor = assertNotNull(model.heldEditor(held.id, '.'), "no editor for the held entry")
        val shown = editor.current
        assertEquals("Lunch (mine)", shown.form.title, "the person's values are not in the form")
        assertEquals(me.raddatz.veyq.mobile.words.Msg.OFFLINE_NEWER_VERSION, shown.newer?.msg, "the newer version is not named")
        assertTrue(shown.discardsMine)
        assertEquals(listOf("scan.png"), shown.pending.map { it.name }, "the receipt the change carried is not in the editor")
        assertTrue(editor.save().done)
        assertEquals(0, a.outbox!!.count(), "the held write stayed after its save landed")
        val body = open(x, LUNCH, 3)
        assertEquals("Lunch (mine)", (body.content as Expense).title)
        assertEquals(listOf(RECEIPT), (body.content as Expense).receipts)
        editor.close()
        a.retire()
    }

    /** *Discard mine*: the held write gone unsent, nothing written over the newer version. */
    @Test
    fun a_held_edits_discard_mine_drops_it_unsent() = runTest {
        val x = setup()
        val (a, held) = x.heldEdit()
        val editor = assertNotNull(x.potModel(a).heldEditor(held.id, '.'))
        val before = x.up.appended.size
        assertTrue(editor.discardMine())
        assertTrue(editor.current.done)
        assertEquals(0, a.outbox!!.count())
        assertEquals(before, x.up.appended.size, "Discard mine wrote")
        // a write that is not held opens no held editor
        assertNull(x.potModel(a).heldEditor(held.id, '.'))
        a.retire()
    }

    @Test
    fun a_delete_meeting_a_newer_revision_is_held() = runTest {
        val x = setup()
        val (a, s) = x.offline()
        assertTrue(QueuedExpenses.deleteEntry(s, LUNCH, 1, 1))
        x.t.revise(x.pot, LUNCH, expense("Lunch (theirs)", 1900))
        x.back(a)
        val held = a.outbox!!.writes().single()
        assertEquals(QueuedWrite.State.HELD, held.state)
        assertEquals(Deleted, QueuedExpenses.entry(held)!!.content)
        a.retire()
    }

    @Test
    fun a_receipt_the_storage_has_no_room_for_refuses_the_entry_and_its_copy_stays() = runTest {
        val x = setup()
        val (a, s) = x.offline()
        val form = assertNotNull(PotView(s).blankForm("2026-07-02")).copy(title = "Taxi", payers = listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Payer(ANNA, "12.00")))
        assertNotNull(QueuedExpenses.saveEntry(s, null, form, null, listOf(PotSession.PendingReceipt(RECEIPT, "scan".encodeToByteArray(), "image/png", "scan.png", null)), 1))
        x.up.refuseStart = "quota_exceeded"
        x.back(a)
        val refused = a.outbox!!.writes().single()
        assertEquals(QueuedWrite.State.REFUSED, refused.state)
        assertEquals("quota_exceeded", refused.reason)
        assertEquals(1, refused.blobs.size, "the receipt's sealed copy is gone: nothing to save")
        assertTrue("appendPotRevisions" !in x.up.operations)
        a.retire()
    }

    // ---- Depot

    private fun file(name: String, text: String) = UploadSource.of(name, text.length.toLong(), "text/plain", 5L) { from, length -> text.encodeToByteArray().copyOfRange(from.toInt(), from.toInt() + length) }

    @Test
    fun a_file_queued_offline_is_sent_into_a_node_of_its_id_and_a_name_a_file_holds_is_held() = runTest {
        val x = setup()
        x.t.node(x.scope, "taken", kind = "FILE", parent = x.folder, name = "taken.txt")
        val (a, _) = x.offline()
        assertTrue(a.queues(Queued.DEPOT_FILE_ADD))
        val left = mutableListOf<UploadSource>()
        val w = assertNotNull(QueuedDepot.file(a, "t1", x.scope.id, x.folder.id, file("notes.txt", "DEPOT-FILE-MARKER"), 1, left::add))
        assertNotNull(QueuedDepot.file(a, "t1", x.scope.id, x.folder.id, file("taken.txt", "other"), 2, left::add))
        assertEquals(2, left.size, "a picked file was not handed back once sealed")
        val made = mutableListOf<String>()
        val inner = x.up::route
        x.t.reachable = true
        x.t.route = { r ->
            if (r.method == HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) {
                val id = r.json()["nodeId"]!!.jsonPrimitive.content
                made += id
                x.t.adopt(x.scope, id, "FILE", x.folder, "notes.txt")
                201 to """{"nodeId":"$id"}"""
            } else inner(r)
        }
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(listOf(w.id), made, "the file's node was not made with the id minted offline")
        val content = x.up.reserved.singleOrNull() ?: error("reserved ${x.up.reserved.map { it.ownerId to it.role }}, made $made, writes ${a.outbox!!.writes().map { it.op + ":" + it.state + ":" + it.reason }}, ops ${x.up.operations}")
        val ctx = Context("blob-key", "scopeId" to x.scope.id, "ownerId" to w.id, "blobId" to content.blobId, "role" to content.role)
        val node = x.scope.nodes.first { it.id == w.id }
        assertContentEquals("DEPOT-FILE-MARKER".encodeToByteArray(), x.up.plaintext(content.blobId, x.t.suite.open(ctx, node.key, unb64(content.wrappedBlobKey))))
        val held = a.outbox!!.writes().singleOrNull() ?: error("writes ${a.outbox!!.writes().map { it.op + ":" + it.state + ":" + it.reason }}, ops ${x.up.operations}")
        assertEquals(QueuedWrite.State.HELD, held.state)
        assertEquals("file_exists", held.reason)
        a.retire()
    }

    /** Revision [revision] of [entryId] as appended, opened under the Pot's key. */
    private fun open(x: Setup, entryId: String, revision: Long): me.raddatz.veyq.mobile.expenses.RevisionBody {
        val item = x.up.appended.first { it["entryId"]!!.jsonPrimitive.content == entryId && it["revision"]!!.jsonPrimitive.content.toLong() == revision }
        return RevisionCodec(x.t.suite).open(
            RevisionAddress(x.scope.id, x.pot.id, entryId, revision), x.pot.key,
            SealedRevision(unb64(item["sealedBody"]!!.jsonPrimitive.content), unb64(item["wrappedRevisionKey"]!!.jsonPrimitive.content)),
        )
    }

    private companion object {
        const val ANNA = "0e000000-0000-4000-8000-000000000001"
        const val BEN = "0e000000-0000-4000-8000-000000000002"
        const val LUNCH = "0e000000-0000-4000-9000-000000000002"
        const val DINNER = "0e000000-0000-4000-9000-000000000003"
        const val RECEIPT = "0e000000-0000-4000-a000-000000000001"
    }
}
