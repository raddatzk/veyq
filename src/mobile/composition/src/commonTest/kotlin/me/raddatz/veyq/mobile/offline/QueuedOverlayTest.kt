package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.offline.QueuedLoss
import me.raddatz.veyq.mobile.offline.QueuedShown

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import me.raddatz.veyq.mobile.navigation.NameAnswer
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
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
 * `QueuedShown.forgetAsk` back to the loss's ask (`ask(QueuedLoss.STOP_TRUSTING, waiting)`, nothing asked while none
 * waits) → [the_unlocks_forget_is_always_asked_and_says_whether_writes_wait] red.
 * #1040's three (each seen red with its overlay taken out, then restored): `GalleryModel.read` without the queued times →
 * [a_capture_time_set_offline_reorders_the_gallery_in_place] red ("the grid kept its order"); `ShellModel.rebuild` over the
 * raw nodes → [a_rename_made_offline_shows_in_its_level_and_on_its_open_page] red; the gallery door's `madeOffline` answer
 * dropped → [a_memory_made_offline_opens_and_takes_media_and_a_chapter] red ("did not read").
 * #1095's three (each mutation seen red, then restored): `QueuedShown.item` without `folder` → all three red ("enters
 * nothing"); `relocate` over the tree alone → [a_folder_made_offline_is_entered_and_takes_a_folder_a_memory_and_a_file]
 * red (the path left the folder); the outbox watch not calling `leaveUnsent` → that one and
 * [a_held_folder_made_offline_offers_nothing_online_and_a_refused_one_is_left] red ("Home stayed"); `leaveUnsent` over the
 * listed nodes without reading the tree anew → [a_folder_made_offline_is_sent_before_what_waits_in_it_and_home_stays_in_it]
 * red ("Home left a folder that was sent"); `canCreateHere`'s or `takesUpload`'s online gate dropped → the held one red;
 * `placeName` without the folders made offline → the first red (the tray names no place).
 */
class QueuedOverlayTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    /**
     * The unlock's *Stop trusting this device* (inbox #1052, owner 2026-11-22): asked every time, in one question — the
     * Secret Key it will take to sign in here again, and, where writes made offline wait, how many go with the account.
     */
    @Test
    fun the_unlocks_forget_is_always_asked_and_says_whether_writes_wait() {
        val plain = assertNotNull(QueuedShown.forgetAsk(0), "nothing waits: still asked")
        assertEquals(Notice(Msg.UNLOCK_FORGET_CONFIRM), plain.question)
        assertEquals(Msg.MANAGE_FORGET_DEVICE, plain.confirm)
        val waiting = assertNotNull(QueuedShown.forgetAsk(2))
        assertEquals(Notice(Msg.UNLOCK_FORGET_CONFIRM_WRITES_LOST, 2), waiting.question, "one question names the writes, not a second ask")
        assertEquals(QueuedLoss.STOP_TRUSTING.confirm, waiting.confirm)
    }

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

    /** #1040 (1): a capture time set offline moves its medium to where its new time puts it, and the details show the time. */
    @Test
    fun a_capture_time_set_offline_reorders_the_gallery_in_place() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, m) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        val gallery = shell.galleryModel(assertNotNull(shell.galleryAt(s.id, m.id)), shell.mediaUploads(UploadQueue(), deriver, {}, {}))
        val (x1, x2) = t.id("x1") to t.id("x2")
        // neither has a time: by id, so the later id is last
        val (first, last) = listOf(x1, x2).sorted().let { it[0] to it[1] }
        assertEquals(listOf(first, last), waitFor { gallery.current.rows?.takeIf { it.size == 2 } }?.map { it.mediaId })
        val at = 1_780_000_000_000L
        val editor = assertNotNull(gallery.captureTimeEditor(last), "offline no capture time is offered although they are queued")
        assertIs<me.raddatz.veyq.mobile.memories.CaptureTime.Outcome.Done>(editor.set(at))
        // a medium with a time comes before one without: the one set offline leads, its time the one set
        val moved = assertNotNull(waitFor { gallery.current.rows?.takeIf { r -> r.firstOrNull()?.mediaId == last } }, "the grid kept its order")
        assertEquals(me.raddatz.veyq.mobile.memories.CaptureTime.minute(at), moved.first().capturedAt)
        // discarded from the tray, the medium goes back to its place
        shell.queuedTray().discard(a.outbox!!.writes().single().id)
        gallery.reload()
        assertNotNull(waitFor { gallery.current.rows?.takeIf { r -> r.map { it.mediaId } == listOf(first, last) && r.all { it.capturedAt == null } } }, "the discarded time still shows")
        gallery.close()
        a.retire()
    }

    /** #1040 (2): a rename made offline shows in its level, on the page open on it, and goes with a *Discard*. */
    @Test
    fun a_rename_made_offline_shows_in_its_level_and_on_its_open_page() = runTest {
        val (_, a, sm) = offlineMemory()
        val (s, m) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        val ref = assertNotNull(shell.current?.lists?.objects?.firstOrNull { it.nodeId == m.id })
        shell.open(ref)
        val r = shell.rename(ref.at, "Winter")
        assertEquals(Notice(Msg.OFFLINE_WAITING), r.notice)
        assertEquals("Winter", shell.current?.lists?.nodes?.first { it.nodeId == m.id }?.name, "the level shows the old name")
        assertEquals("Winter", shell.current?.lists?.objects?.first { it.nodeId == m.id }?.name)
        assertEquals("Winter", shell.shell?.openObject?.name, "the open page heads the old name")
        // a Discard in the tray: the name the server holds again
        shell.queuedTray().discard(a.outbox!!.writes().single().id)
        assertNotNull(waitFor { shell.current?.lists?.nodes?.firstOrNull { it.nodeId == m.id && it.name == "Summer" } }, "the discarded name still shows")
        assertNotNull(waitFor { shell.shell?.openObject?.takeIf { it.name == "Summer" } }, "the open page heads the discarded name")
        a.retire()
    }

    /**
     * #1040 (3): a Memory made offline opens from its level's row as its page — empty but for what waits —, takes media and
     * a chapter made in it, the chapter listed marked and opening in turn.
     */
    @Test
    fun a_memory_made_offline_opens_and_takes_media_and_a_chapter() = runTest {
        val (_, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        assertTrue(shell.createMemory(s.id, null, "Autumn").queued)
        val row = shell.queuedHere.first().single()
        val page = assertNotNull(row.page, "the Memory made offline opens nothing")
        assertEquals("Autumn", page.name)
        assertEquals("MEMORY", page.kind)
        shell.open(page)
        assertEquals(page, shell.shell?.openObject)
        val uploads = shell.mediaUploads(UploadQueue(), deriver, {}, {})
        val gallery = shell.galleryModel(page.at, uploads)
        val read = assertNotNull(waitFor { gallery.current.rows }, "the page of the Memory made offline did not read")
        assertTrue(read.isEmpty())
        assertNotNull(gallery.current.offline)
        assertNull(gallery.current.failure, "the page says it failed")
        assertTrue(gallery.takesUploads)
        assertEquals(1, gallery.upload(listOf(photo("leaf.png", "PNG-leaf"))))
        assertTrue(assertNotNull(waitFor { gallery.current.uploading.firstOrNull() }, "no waiting tile in the Memory made offline").waiting)
        // a chapter made on its page: listed, marked, and opening as a page of its own
        val made = gallery.createChapter("Day one")
        assertTrue(made.queued)
        val chapter = assertNotNull(waitFor { gallery.current.chapters.firstOrNull() }, "the chapter made offline is not listed")
        assertEquals("Day one", chapter.name)
        assertEquals(Notice(Msg.OFFLINE_WAITING), gallery.current.chapterSaid[chapter.nodeId])
        val chapterPage = shell.galleryModel(assertNotNull(me.raddatz.veyq.mobile.memories.GalleryModel.rowPage(chapter)).at, uploads)
        assertNotNull(waitFor { chapterPage.current.rows }, "the chapter made offline does not open")
        // all three wait, the medium and the chapter for the Memory
        val writes = a.outbox!!.writes()
        assertEquals(listOf(Queued.MEMORY_CREATE, Queued.MEDIA_ADD, Queued.MEMORY_CREATE).map { it.name }, writes.map { it.op })
        assertEquals(listOf(null, page.nodeId, page.nodeId), writes.map { it.after })
        // renamed on its page: the row and the open page follow
        shell.rename(page.at, "Fall")
        assertEquals("Fall", shell.shell?.openObject?.name)
        assertEquals(Notice(Msg.QUEUED_MEMORY, "Fall"), shell.queuedAt(s.id, null).first().single().title)
        chapterPage.close()
        gallery.close()
        a.retire()
    }

    /**
     * #1095 (1): a folder made offline is entered from its level's row — a level empty but for what waits in it —, takes a
     * folder, a Memory and a file made in it (each waiting for it), names them its place in the tray, is renamed on Home's
     * path, and Home leaves it once its create is discarded with what waited in it.
     */
    @Test
    fun a_folder_made_offline_is_entered_and_takes_a_folder_a_memory_and_a_file() = runTest {
        val (_, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        assertEquals(NameAnswer.QUEUED, shell.createFolder(s.id, null, "Trip"))
        val row = shell.queuedHere.first().single()
        assertNull(row.page)
        val folder = assertNotNull(row.folder, "the folder made offline enters nothing")
        assertEquals("Trip", folder.name)
        shell.enter(folder)
        assertEquals(listOf(folder.nodeId), shell.shell?.path?.map { it.nodeId })
        assertTrue(shell.current!!.lists.level(shell.shell!!).isEmpty())
        assertEquals(emptyList(), shell.queuedHere.first(), "the folder made offline shows what is not in it")
        // its New offers what queues offline, as any level's does
        assertTrue(shell.canCreateHere, "the folder made offline offers no New")
        assertTrue(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER in assertNotNull(shell.levelPlan()).creates)
        assertTrue(shell.takesUpload(s.id, folder.nodeId), "the folder made offline takes no upload")
        // a folder, a Memory and a file made in it: shown in it, each waiting for it, the tray naming it their place
        assertEquals(NameAnswer.QUEUED, shell.createFolderHere("Day one"))
        assertTrue(shell.createMemory(s.id, folder.nodeId, "Beach").queued)
        val depot = shell.depotUploads(UploadQueue()) {}
        assertEquals(1, depot.upload(s.id, folder.nodeId, "Trip", listOf(photo("ticket.pdf", "PDF-ticket"))))
        val inside = assertNotNull(waitFor { shell.queuedHere.first().takeIf { it.size == 3 } }, "what was made in the folder made offline is not shown in it")
        assertEquals(listOf(Queued.FOLDER_CREATE, Queued.MEMORY_CREATE, Queued.DEPOT_FILE_ADD), inside.map { it.op })
        assertTrue(inside.all { it.where == "Trip" }, "the tray does not name the folder made offline as the place: ${inside.map { it.where }}")
        val writes = a.outbox!!.writes()
        assertEquals(listOf(null, folder.nodeId, folder.nodeId, folder.nodeId), writes.map { it.after })
        // the folder made in it enters in turn
        shell.enter(assertNotNull(inside.first().folder))
        assertEquals(listOf("Trip", "Day one"), shell.shell?.path?.map { it.name })
        // renamed on Home's path: the path follows the queued create
        val renamed = shell.rename(NodeAt(s.id, folder.nodeId, NodeRef.FOLDER, "Trip"), "Holiday")
        assertEquals(Notice(Msg.OFFLINE_WAITING), renamed.notice)
        assertEquals(listOf("Holiday", "Day one"), shell.shell?.path?.map { it.name }, "Home's path left the folder made offline or kept its old name")
        // discarded from the tray with what waited in it: Home shows the space's top again
        shell.queuedTray().discard(writes.first().id)
        assertNotNull(waitFor { shell.shell?.takeIf { it.path.isEmpty() } }, "Home stayed in a folder whose create was discarded")
        assertEquals(0, a.outbox!!.count())
        a.retire()
    }

    /** A create of a node the test's [answer] plays: the id the client minted, its parent, the status and body answered. */
    private fun PlayedTenant.creates(answer: (id: String, parent: String?) -> Pair<Int, String>) {
        route = { r ->
            if (r.method == io.ktor.http.HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) {
                val b = Json.parseToJsonElement((r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                answer(b["nodeId"]!!.jsonPrimitive.content, (b["parentId"] as? JsonPrimitive)?.contentOrNull)
            } else null
        }
    }

    /**
     * #1095 (2): back online, a folder made offline is sent before the folder waiting in it, that one under it, and Home
     * stays in both as they become the server's.
     */
    @Test
    fun a_folder_made_offline_is_sent_before_what_waits_in_it_and_home_stays_in_it() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        shell.createFolder(s.id, null, "Trip")
        val outer = assertNotNull(shell.queuedHere.first().single().folder)
        shell.enter(outer)
        shell.createFolderHere("Day one")
        val inner = assertNotNull(waitFor { shell.queuedHere.first().singleOrNull()?.folder })
        shell.enter(inner)
        val created = mutableListOf<Pair<String, String?>>()
        val adopted = HashMap<String, PlayedTenant.Node>()
        t.creates { id, parent ->
            created += id to parent
            // the played server takes it: the tree lists the folder at the id the client minted
            adopted[id] = t.adopt(s, id, "FOLDER", parent?.let { adopted[it] }, if (id == outer.nodeId) "Trip" else "Day one")
            201 to """{"nodeId":"$id"}"""
        }
        t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(listOf(outer.nodeId to null, inner.nodeId to outer.nodeId), created, "not sent in order, each under its parent")
        assertEquals(0, a.outbox!!.count())
        assertNotNull(waitFor { shell.current?.lists?.nodes?.takeIf { n -> n.any { it.nodeId == inner.nodeId } } }, "the lists never showed what was sent")
        assertEquals(listOf(outer.nodeId, inner.nodeId), shell.shell?.path?.map { it.nodeId }, "Home left a folder that was sent")
        a.retire()
    }

    /**
     * #1095 (3): a folder made offline whose send is held (its name taken) stays a level that is entered, but online it
     * offers no New and takes no upload — the server holds no such parent yet; one refused leaves Home for the level above.
     */
    @Test
    fun a_held_folder_made_offline_offers_nothing_online_and_a_refused_one_is_left() = runTest {
        val (t, a, sm) = offlineMemory()
        val (s, _) = sm
        val shell = shellOf(tenantOf(a))
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        shell.createFolder(s.id, null, "Taken")
        shell.createFolder(s.id, null, "Refused")
        val made = shell.queuedHere.first().map { assertNotNull(it.folder) }
        val taken = made.first { it.name == "Taken" }
        val refused = made.first { it.name == "Refused" }
        t.creates { id, _ -> if (id == taken.nodeId) 409 to """{"code":"slug_taken"}""" else 422 to """{"code":"nope"}""" }
        shell.enter(refused)
        t.reachable = true
        assertTrue(a.reconnect())
        a.awaitSending()
        assertEquals(listOf(QueuedWrite.State.HELD, QueuedWrite.State.REFUSED), a.outbox!!.writes().map { it.state })
        assertNotNull(waitFor { shell.shell?.takeIf { it.path.isEmpty() } }, "Home stayed in a folder whose create was refused")
        val row = shell.queuedHere.first().first { it.id == taken.nodeId }
        assertNull(shell.queuedHere.first().first { it.id == refused.nodeId }.folder, "a refused folder still enters")
        shell.enter(assertNotNull(row.folder, "a held folder made offline enters nothing"))
        assertTrue(shell.online)
        assertTrue(!shell.canCreateHere, "online a folder not sent yet offers New")
        assertTrue(!shell.takesUpload(s.id, taken.nodeId), "online a folder not sent yet takes an upload")
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
            listOf(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER, me.raddatz.veyq.mobile.AppKinds.Create.MEMORY, me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES),
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
