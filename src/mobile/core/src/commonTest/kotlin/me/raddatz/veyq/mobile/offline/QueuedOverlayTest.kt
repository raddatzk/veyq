package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.composition.QueuedLoss
import me.raddatz.veyq.mobile.composition.QueuedShown

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.expenses.Allocation
import me.raddatz.veyq.mobile.expenses.Contribution
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.ParticipantBody
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.Split
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the person sees of their writes made offline (ADR 0153 decision 5): each model's
 * state with a queued write offline — a gallery's medium as a placeholder tile *Waiting to send*, a trashed medium gone
 * from it, a mark shown set; a Pot's entry counted, marked *Not sent*, its balances saying so; a level's folder made
 * offline in place —, the banner's count, the tray's items and actions (*Discard*, *Save a copy* of a refused file), the
 * reconnect's notice, and the ask before a wipe.
 *
 * Bite (one edit, seen red, then restored): the gallery's own offline check left beside `queues` (`takesUploads` back to
 * `current.offline == null`) → [a_gallery_offline_shows_a_queued_medium_as_a_waiting_tile_a_trash_and_a_mark_in_place] red.
 */
class QueuedOverlayTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private fun shellOf(tenant: TenantSession) = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }

    /** [read]'s first non-null answer, the models' watches running on their own thread: at most two seconds. */
    private suspend fun <R : Any> waitFor(read: suspend () -> R?): R? {
        repeat(200) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return null
    }

    private val deriver = object : Derivation.Deriver {
        override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) =
            done(Derivation.Derived("p".encodeToByteArray(), "t".encodeToByteArray(), 4, 3))
    }

    private fun photo(name: String, text: String) = UploadSource.of(name, text.length.toLong(), "image/png", null) { from, length -> text.encodeToByteArray().copyOfRange(from.toInt(), from.toInt() + length) }

    /** A Memory with two media, seen online (its listing and the lists kept), then the account opened offline. */
    private suspend fun offlineMemory(): Triple<PlayedTenant, VeyqAccount, Pair<PlayedTenant.Scope, PlayedTenant.Node>> {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "m1", name = "Summer")
        t.medium(m, "x1")
        t.medium(m, "x2")
        t.remember()
        val first = t.resume()
        val tenant = tenantOf(first)
        assertNotNull(MemoryMedia.list(tenant, s.id, m.id))
        // the marks read online too (nobody's yet): kept, so offline the own set is known
        t.route = { r -> if (r.url.encodedPath.endsWith("/item-favorites")) 200 to "[]" else null }
        assertTrue(me.raddatz.veyq.mobile.memories.ItemMarks.of(tenant, s.id, m.id).load())
        t.route = null
        shellOf(tenant).load(openLastUsed = false)
        first.retire()
        t.reachable = false
        val a = t.resume()
        assertIs<Reach.Offline>(a.reach.value)
        return Triple(t, a, s to m)
    }

    @Test
    fun a_gallery_offline_shows_a_queued_medium_as_a_waiting_tile_a_trash_and_a_mark_in_place() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, m) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        val uploads = shell.mediaUploads(UploadQueue(), deriver, {}, {})
        val gallery = shell.galleryModel(assertNotNull(shell.galleryAt(s.id, m.id)), uploads)
        assertNotNull(waitFor { gallery.current.offline }, "the Memory did not open from the store")
        assertTrue(gallery.takesUploads, "offline the gallery takes no upload although writes are queued")
        assertEquals(1, gallery.upload(listOf(photo("beach.png", "PNG-beach"))))
        val tile = assertNotNull(waitFor { gallery.current.uploading.firstOrNull() }, "no placeholder tile for the queued medium")
        assertTrue(tile.waiting)
        assertEquals(Notice(Msg.OFFLINE_WAITING), tile.said)
        assertEquals("beach.png", tile.name)
        // a trash: the medium leaves the grid at once, and stays gone when the page is read again from the store
        val x1 = t.id("x1")
        gallery.trash(x1)
        assertTrue(gallery.current.rows.orEmpty().none { it.mediaId == x1 })
        gallery.reload()
        assertTrue(waitFor { gallery.current.rows?.takeIf { r -> r.isNotEmpty() && r.none { it.mediaId == x1 } } } != null, "the queued trash came back")
        // a mark: shown as set
        assertTrue(gallery.current.marking, "offline no mark is offered although marks are queued")
        val x2 = t.id("x2")
        waitFor { gallery.current.marksLoaded.takeIf { it } }
        // the tile's plan offers the mark once the own set is read (ADR 0154 decision 2: the web's tile, nothing else)
        val x2row = assertNotNull(gallery.current.rows?.firstOrNull { it.mediaId == x2 })
        assertEquals(listOf(me.raddatz.veyq.mobile.memories.TileAction.MARK), gallery.tileActions(x2row))
        assertNull(gallery.toggleMark(x2))
        assertEquals(true, gallery.current.marks[x2]?.mine)
        assertEquals(listOf(Queued.MEDIA_ADD, Queued.MEDIA_TRASH, Queued.MEDIA_MARK).map { it.name }, a.outbox!!.writes().map { it.op })
        gallery.close()
        a.retire()
    }

    @Test
    fun a_pot_entry_made_offline_is_counted_marked_not_sent_and_its_balances_say_so() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val p = t.pot(s, "p1")
        t.revise(p, ANNA, ParticipantBody("Anna", true, 1))
        t.revise(p, BEN, ParticipantBody("Ben", true, 2))
        t.revise(p, LUNCH, Expense("Lunch", "food", "2026-07-01", "EUR", 2, "1", 1800, emptyList(), listOf(Contribution(ANNA, 1800)), Allocation(emptyList(), Split.Equal(listOf(ANNA, BEN)))))
        t.remember()
        val first = t.resume()
        assertNotNull(PotSession.open(tenantOf(first), s.id, p.id))
        first.retire()
        t.reachable = false
        val a = t.resume()
        val pot = assertNotNull(PotSession.open(tenantOf(a), s.id, p.id))
        assertTrue(PotView(pot).canWrite, "offline the Pot takes no entry although entries are queued")
        // what crosses to the desktops (PotView.Shown, the bridge's Pot update) offers Add entry offline too
        assertTrue(PotView(pot).shown().offersEntries, "the shown view does not offer Add entry offline")
        val before = PotView(pot).balances.first { it.id == BEN }.balance
        val form = assertNotNull(PotView(pot).blankForm("2026-07-02")).copy(title = "Taxi", payers = listOf(EntryForm.Payer(BEN, "10.00")))
        assertEquals(PotSession.WriteOutcome.QUEUED, pot.saveEntry(null, form, null))
        val view = PotView(pot)
        val row = view.entries.first { it.title == "Taxi" }
        assertTrue(row.unsent)
        assertEquals(Notice(Msg.OFFLINE_NOT_SENT), view.unsentMark(row))
        assertTrue(view.entries.first { it.title == "Lunch" }.unsent.not())
        assertEquals(before + 500, view.balances.first { it.id == BEN }.balance, "the entry not sent is not counted")
        assertEquals(Notice(Msg.OFFLINE_BALANCES_UNSENT), view.balancesUnsent)
        // a payment recorded and every other write still refuse offline
        assertEquals(false, view.canRecordAll)
        a.retire()
    }

    @Test
    fun the_tray_lists_and_discards_saves_a_refused_copy_and_the_banner_counts() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, m) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        assertNull(shell.queuedBanner.first())
        assertNull(shell.queuedLost())
        // a folder made offline shows where it was made
        assertTrue(shell.createFolder(s.id, null, "Trip").queued)
        val here = shell.queuedAt(s.id, null).first()
        assertEquals(listOf(Notice(Msg.QUEUED_FOLDER, "Trip")), here.map { it.title })
        assertEquals(Notice(Msg.OFFLINE_WAITING), here.single().said)
        shell.switchSpace(s.id)
        assertEquals(here, shell.queuedHere.first(), "the level shown does not list what was made offline there")
        val uploads = shell.mediaUploads(UploadQueue(), deriver, {}, {})
        assertEquals(1, uploads.queue(s.id, m.id, listOf(photo("dune.png", "PNG-DUNE-PLAINTEXT"))))
        assertEquals(Notice(Msg.OFFLINE_BANNER_WAITING, 2), shell.queuedBanner.first())
        assertEquals(Notice(Msg.OFFLINE_WRITES_LOST, 2), shell.queuedLost())
        // New offline offers only what is queued (never a Pot, a folder uploaded whole or a kept link)
        assertEquals(
            listOf(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER, me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES, me.raddatz.veyq.mobile.AppKinds.Create.MEMORY),
            shell.createsHere(isHome = true, bookmarks = true),
        )
        // the one upload gate (ADR 0154 decision 1: a drop, a pick, the bridge's upload calls) takes what New offers offline
        assertTrue(shell.takesUpload(s.id, null), "New offers files offline, but the upload gate refuses them")
        val tray = shell.queuedTray()
        // the ask before a loss names how many go, with the loss's own confirm
        assertEquals(Notice(Msg.OFFLINE_WRITES_LOST, 2) to Msg.OFFLINE_STOP_TRUSTING_ANYWAY, tray.ask(QueuedLoss.STOP_TRUSTING)?.let { it.question to it.confirm })
        val items = tray.list()
        // nothing held, nothing to open
        assertNull(tray.opens(items[0].id))
        assertEquals(listOf(Queued.FOLDER_CREATE, Queued.MEDIA_ADD), items.map { it.op })
        assertEquals("Summer", items[1].where)
        assertTrue(items.all { it.state == QueuedWrite.State.WAITING && !it.opens && it.copies == 0 })
        // refused: its file saved as plaintext only into the sink the host's save dialog gave
        val box = a.outbox!!
        box.update(box.writes()[1].copy(state = QueuedWrite.State.REFUSED, reason = "quota_exceeded"))
        val refused = tray.list()[1]
        assertEquals(Notice(Msg.OFFLINE_REFUSED_NO_ROOM), refused.said)
        assertEquals(1, refused.copies)
        assertEquals("dune.png" to "image/png", tray.copyName(refused.id))
        val saved = mutableListOf<ByteArray>()
        assertTrue(tray.saveCopy(refused.id) { saved += it })
        assertContentEquals("PNG-DUNE-PLAINTEXT".encodeToByteArray(), saved.fold(ByteArray(0)) { x, y -> x + y })
        assertEquals(Notice(Msg.DOWNLOAD_DONE, "dune.png"), QueuedShown.copySaved("dune.png", true))
        assertEquals(Notice(Msg.DOWNLOAD_FAILED, "dune.png"), QueuedShown.copySaved("dune.png", false))
        assertTrue(tray.discard(refused.id))
        assertTrue(tray.discard(items[0].id))
        assertEquals(0, tray.count())
        assertNull(shell.queuedBanner.first())
        a.retire()
    }

    @Test
    fun the_reconnect_says_how_many_were_sent_and_how_many_need_the_person() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        assertTrue(shell.createFolder(s.id, null, "Sent").queued)
        assertTrue(shell.createFolder(s.id, null, "Taken").queued)
        var creates = 0
        t.route = { r ->
            if (r.method == io.ktor.http.HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) {
                val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val id = Regex("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                // the first is taken, the second meets a sibling of its name made meanwhile (held for the person)
                if (creates++ == 0) 201 to """{"nodeId":"$id"}""" else 409 to """{"code":"slug_taken"}"""
            } else null
        }
        val notices = kotlinx.coroutines.CompletableDeferred<List<Notice>>()
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            .launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shell.queuedSent.collect { notices.complete(it) } }
        t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        val said = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(2000) { notices.await() } }
        assertEquals(listOf(Notice(Msg.OFFLINE_SENT, 1), Notice(Msg.OFFLINE_NEED_YOU, 1)), said)
        watch.cancel()
        a.retire()
    }

    /** What was made offline and sent at the reconnect shows as itself in the shell's lists, unasked (no pull to refresh). */
    @Test
    fun the_lists_are_read_anew_once_writes_made_offline_were_sent() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        assertTrue(shell.createFolder(s.id, null, "Late").queued)
        // the server takes the create and lists the folder from then on
        t.route = { r ->
            if (r.method == io.ktor.http.HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) {
                val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val id = Regex("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                t.adopt(s, id, "FOLDER", null, "Late")
                201 to """{"nodeId":"$id"}"""
            } else null
        }
        t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(0, a.outbox?.count(), "the folder was not sent")
        assertNotNull(waitFor { shell.current?.lists?.nodes?.firstOrNull { it.name == "Late" } }, "the folder sent is not listed")
        a.retire()
    }

    private companion object {
        const val ANNA = "0e000000-0000-4000-8000-000000000001"
        const val BEN = "0e000000-0000-4000-8000-000000000002"
        const val LUNCH = "0e000000-0000-4000-9000-000000000002"
    }
}
