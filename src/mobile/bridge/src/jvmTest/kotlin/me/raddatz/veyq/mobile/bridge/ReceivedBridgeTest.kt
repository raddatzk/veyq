package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.sharing.GuestView
import me.raddatz.veyq.mobile.sharing.MemoryGuestSessions
import me.raddatz.veyq.mobile.sharing.PlayedGuestContent
import me.raddatz.veyq.mobile.sharing.PlayedShare
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A received link through the bridge (ADR 0150 decision 7): *Open a link* signed out, the gates, the guest's pages and its
 * name, *Back* that asks and lets go — and **no update of the whole visit carries the link or its key**: every update's
 * JSON is scanned for the key's canaries (base64url, base64, hex) and for the link itself, as the host would receive it.
 */
class ReceivedBridgeTest {
    private val p = BouncyCastlePrimitives

    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key
    }

    /** A sign-in door that signs nothing in: the link is opened signed out. */
    private class Door : SessionModel.Door<Acc, String> {
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc = throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("none")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("none")
        override suspend fun resume(record: TrustedDevice) = error("none")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = null
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = ""
        override suspend fun spaces(session: String) = emptyList<me.raddatz.veyq.mobile.navigation.SpaceRef>()
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    private fun bridge(share: PlayedShare) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(
            SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, files = JvmFiles,
            received = { link, queue, password, music ->
                GuestView.open(
                    p, link, { block -> HttpClient(MockEngine(share.handler), block) }, MemoryDeviceStore(), MemoryGuestSessions(), { "en" },
                    handedPassword = password, queue = queue, music = music,
                )
            },
        ) { null }
    }

    @Test
    fun aWholeGuestVisitThroughTheBridgeSendsNoUpdateWithTheLinkOrItsKey() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE", password = "pw", termsRevision = 1)
        val content = PlayedGuestContent(share)
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "bytes".encodeToByteArray())
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen("  ${share.link}  ")))
        val w = b.watching(Watched.Received(opened.received))
        val all = mutableListOf<String>()
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            all += j.toString()
            val u = wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received
            u.shown?.takeIf(pick)
        }
        assertEquals(ReceivedGate.LOCKED, until { it.gate == ReceivedGate.LOCKED }.gate)
        b.ask(Call.ReceivedPassword(opened.received, "wrong"))
        assertTrue(until { it.askedAgain }.askedAgain)
        b.ask(Call.ReceivedPassword(opened.received, "pw"))
        val terms = until { it.gate == ReceivedGate.TERMS }
        assertEquals("Space terms", terms.terms)
        b.ask(Call.ReceivedAcceptTerms(opened.received))
        val ready = until { it.gate == ReceivedGate.READY && it.page != null }
        assertEquals(share.rootName, ready.rootName)
        assertEquals(ReceivedPageKind.LEVEL, ready.page!!.kind)
        assertEquals(listOf("Trip"), ready.page!!.rows.map { it.name })
        assertFalse(ready.keepOffered, "Keep offered signed out")
        // named at a write, by the core's naming flow (ADR 0154 decision 1): there is no naming apart from a write
        val made = async { b.ask(Call.ReceivedNewFolder(opened.received, "Neu")) }
        until { it.naming }
        b.ask(Call.ReceivedNamingSave(opened.received, "Ben"))
        made.await()
        assertEquals("Ben", until { it.guestName != null }.guestName)
        b.ask(Call.ReceivedOpenNode(opened.received, memory))
        val gallery = until { it.page?.kind == ReceivedPageKind.GALLERY }
        assertNotNull(gallery.page!!.gallery, "the gallery was not registered for the Gallery.* calls")
        b.ask(Call.ReceivedBack(opened.received))
        until { it.page?.kind == ReceivedPageKind.LEVEL }
        b.ask(Call.ReceivedBack(opened.received))
        assertTrue(until { it.leaving }.leaving, "Back at the root left without asking")
        b.ask(Call.ReceivedStay(opened.received))
        assertEquals(Answer.Done, b.ask(Call.ReceivedLeave(opened.received)))
        w.untilJson { j -> all += j.toString(); (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).takeIf { it.shown == null } }
        assertIs<Answer.Refused>(b.ask(Call.ReceivedBack(opened.received)))

        val key = share.kShare
        val canaries = listOf(b64url(key), b64(key), b64(key).trimEnd('='), hex(key), share.link, share.shareId)
        assertTrue(all.size >= 6, "too few updates: ${all.size}")
        for (json in all) for (c in canaries) if (json.contains(c)) fail("an update carried the link or its key: $json")
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * A received link's live poll through the bridge (inbox #1017): while `Watched.ReceivedLive` stands, a folder added to
     * the link reaches the host on the link's own watch, the poll's watch delivering nothing of its own; an unmoved link is
     * a `304`.
     */
    @Test
    fun aReceivedLinksLivePollShowsAMovedLinkOnItsOwnWatch() = runBlocking {
        val share = PlayedShare(p)
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf(pick)
        }
        assertEquals(emptyList(), until { it.gate == ReceivedGate.READY && it.page != null }.page!!.rows.map { it.name })
        val live = b.watching(Watched.ReceivedLive(opened.received))
        share.add(share.uuid(), share.rootId, share.rootKey, "FOLDER", "Anna")
        assertEquals(listOf("Anna"), until { it.page?.rows?.isNotEmpty() == true }.page!!.rows.map { it.name }, "the moved link did not reach the host")
        assertNull(live.pending(), "the poll's watch delivered an update of its own")
        b.unwatch(live.id)
        b.shutdown()
    }

    /**
     * The guest-write record's notice (ADR 0093 § 10, inbox #1018): a writing link's update carries the days its instance
     * keeps a writer's address, port, time and `Origin`, and where its privacy notice says more — the host shows them
     * before any write. A link that does not write carries none.
     */
    @Test
    fun aWritingLinkCarriesItsRecordDaysAndItsInstancesPrivacyNoticeAReadingOneNone() = runBlocking {
        suspend fun ready(role: String, days: Int?): ReceivedShown {
            val share = PlayedShare(p, role = role)
            days?.let { share.extraResolve["guestWriteRecordDays"] = kotlinx.serialization.json.JsonPrimitive(it) }
            val b = bridge(share)
            val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
            val shown = b.watching(Watched.Received(opened.received)).untilJson { j ->
                (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf { it.gate == ReceivedGate.READY }
            }
            for (c in listOf(b64url(share.kShare), share.shareId)) assertFalse(wire.encodeToString(ReceivedShown.serializer(), shown).contains(c))
            b.shutdown()
            return shown
        }
        val writing = ready("READ_WRITE", 30)
        assertEquals(30, writing.recordDays)
        assertEquals("https://photos.example.org/privacy#guest-write-record", writing.privacy)
        assertNull(ready("READ", null).recordDays)
    }

    /**
     * A drop box's guest adds a file by path and is asked its name at that write by the core (`GuestAccess.naming`, #1042:
     * an uploader's, no role read by the bridge) — declined, nothing is sent; named, the upload waiting on it goes on —; the
     * page's update names the file once it was sent (#1018's write).
     */
    @Test
    fun aDropBoxsGuestAddsAFileAndThePageSaysItWasSent() = runBlocking {
        val share = PlayedShare(p, role = "WRITE_ONLY", rootKind = "DEPOT_DROPBOX")
        PlayedGuestContent(share).dropKey()
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        fun shown(j: kotlinx.serialization.json.JsonElement) = (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown
        w.untilJson { j -> shown(j)?.takeIf { it.page?.kind == ReceivedPageKind.DROP_BOX } }
        val file = kotlin.io.path.createTempFile("dropped", ".txt").toFile().apply { writeText("dropped"); deleteOnExit() }
        val declined = async { b.ask(Call.ReceivedUpload(opened.received, listOf(file.path))) }
        w.untilJson { j -> shown(j)?.takeIf { it.naming } }
        assertEquals(Answer.Done, b.ask(Call.ReceivedNamingDecline(opened.received)))
        // declined, `NOT_CHOSEN` as Received.Replace answers it: the hosts say nothing, never "failed" (#1091)
        assertEquals(Answer.Refused(Answer.Why.NOT_CHOSEN), declined.await(), "a declined naming answered as a failed upload")
        val sending = async { b.ask(Call.ReceivedUpload(opened.received, listOf(file.path))) }
        w.untilJson { j -> shown(j)?.takeIf { it.naming } }
        assertEquals(Answer.Named(null, null, false, false), b.ask(Call.ReceivedNamingSave(opened.received, "Ben")))
        assertEquals(Answer.Done, sending.await())
        assertEquals("Ben", w.untilJson { j -> shown(j)?.takeIf { it.guestName != null } }.guestName)
        val sent = w.untilJson { j -> shown(j)?.takeIf { it.page?.sent?.isNotEmpty() == true } }
        assertEquals(listOf(file.name), sent.page!!.sent)
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * #1091: a guest's upload the core refuses (a READ link's level, never asked a name) is `NOT_TAKEN` — the hosts' "failed" —,
     * told apart from a declined naming's `NOT_CHOSEN`.
     */
    @Test
    fun aReadGuestsUploadIsRefusedNotDeclined() = runBlocking {
        val share = PlayedShare(p, role = "READ")
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        fun shown(j: kotlinx.serialization.json.JsonElement) = (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown
        w.untilJson { j -> shown(j)?.takeIf { it.page?.kind == ReceivedPageKind.LEVEL } }
        val file = kotlin.io.path.createTempFile("read", ".txt").toFile().apply { writeText("x"); deleteOnExit() }
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.ReceivedUpload(opened.received, listOf(file.path))))
        b.ask(Call.ReceivedClose(opened.received))
        b.shutdown()
    }

    /**
     * ADR 0154 decision 1 on a guest's gallery: a signed-out READ_WRITE guest's drop onto the Memory on screen goes through
     * the one `Shell.Drop` with no tenant open — judged by the gallery's rights alone, a folder said by name, never answered
     * "no tenant".
     */
    @Test
    fun aSignedOutGuestsDropOntoAGalleryIsTheCoresDropWithNoTenant() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE")
        val content = PlayedGuestContent(share)
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "bytes".encodeToByteArray())
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        fun shown(j: kotlinx.serialization.json.JsonElement) = (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown
        w.untilJson { j -> shown(j)?.takeIf { it.page?.kind == ReceivedPageKind.LEVEL } }
        b.ask(Call.ReceivedOpenNode(opened.received, memory))
        val gallery = w.untilJson { j -> shown(j)?.page?.gallery }
        // #1044: a guest not named yet is offered Add photos — its upload asks the name at the write; declined, none queued
        b.watching(Watched.Gallery(gallery)).untilJson { j ->
            (wire.decodeFromJsonElement(Update.serializer(), j) as? Update.Gallery)?.shown?.takeIf { it.canUpload }
        }
        val photo = kotlin.io.path.createTempFile("photo", ".jpg").toFile().apply { writeBytes(byteArrayOf(-1, -40, -1)); deleteOnExit() }
        val adding = async { b.ask(Call.MemoryUpload(gallery, listOf(photo.path))) }
        w.untilJson { j -> shown(j)?.takeIf { it.naming } }
        assertEquals(Answer.Done, b.ask(Call.ReceivedNamingDecline(opened.received)))
        // declined: nothing queued and nothing said — never "1 file was not added: only photos and videos" (#1091)
        assertEquals(Answer.Refused(Answer.Why.NOT_CHOSEN), adding.await(), "a declined naming's photo was queued or said as no photo")
        val folder = kotlin.io.path.createTempDirectory("Reise").toFile().apply { deleteOnExit() }
        val dropped = assertIs<Answer.Dropped>(
            b.ask(Call.Drop(DropOn.Gallery(gallery), listOf(DropItem.Folder(folder.path), DropItem.NotLocal("away.jpg")), "")),
        )
        assertFalse(dropped.refused, "the guest's gallery refused a drop its rights take")
        assertEquals(
            listOf(Msg.MEMORY_DROP_FOLDER, Msg.DEPOT_DROP_NOT_LOCAL), dropped.notices.map { it.msg },
            "the folder and the item with no path were not said",
        )
        b.ask(Call.ReceivedClose(opened.received))
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * #1034 and #1033 over the bridge: a READ_WRITE folder's guest is offered the level's writes, adds a folder (the page
     * read anew by itself), and reports a row and the page to the link's operator — every update scanned for the key.
     */
    @Test
    fun aReadWriteGuestAddsAFolderAndReportsThroughTheBridge() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE")
        val content = PlayedGuestContent(share)
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "bytes".encodeToByteArray())
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        val all = mutableListOf<String>()
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            all += j.toString()
            (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf(pick)
        }
        val level = until { it.page?.kind == ReceivedPageKind.LEVEL }
        assertTrue(level.page!!.writes, "a READ_WRITE guest's level offers no write")
        // not named yet: the new folder asks the guest's name at the write (ADR 0154 decision 1); declined, nothing is made
        val declined = async { b.ask(Call.ReceivedNewFolder(opened.received, "Neu")) }
        until { it.naming }
        assertEquals(Answer.Done, b.ask(Call.ReceivedNamingDecline(opened.received)))
        assertNotNull(assertIs<Answer.Named>(declined.await()).notice, "a folder was made before the guest named itself")
        // named at the write: the folder the write asked for is made
        val made = async { b.ask(Call.ReceivedNewFolder(opened.received, "Neu")) }
        until { it.naming }
        assertEquals(Answer.Named(null, null, false, false), b.ask(Call.ReceivedNamingSave(opened.received, "Ben")))
        assertEquals(Answer.Named(null, null, false, false), made.await())
        assertTrue(until { s -> s.page?.rows?.any { it.name == "Neu" } == true }.page!!.writes, "the new folder was not drawn after the reload")
        // the reports: a row's (the Memory) and the page's (the root), never the link's
        assertIs<Answer.ReportOpened>(b.ask(Call.ReceivedReportNode(opened.received, memory)))
        assertIs<Answer.ReportOpened>(b.ask(Call.ReceivedReport(opened.received)))
        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), b.ask(Call.ReceivedReportNode(opened.received, "no-such-node")))
        b.ask(Call.ReceivedClose(opened.received))
        val key = share.kShare
        for (json in all) for (c in listOf(b64url(key), b64(key), b64(key).trimEnd('='), hex(key), share.link, share.shareId)) {
            if (json.contains(c)) fail("an update carried the link or its key: $json")
        }
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * #1063 over the bridge: a READ_WRITE guest's level carries each row's menu (Move where the guest moves its node) and
     * Select; the selection is shown in the page, and its *Move to…* and a row's are the member's sheets (`Move.*`), the
     * guest's name asked at the write — every update scanned for the key.
     */
    @Test
    fun aReadWriteGuestMovesThroughTheBridgeWithTheMembersSheet() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE")
        val content = PlayedGuestContent(share)
        val folder = share.uuid().also { share.add(it, share.rootId, share.rootKey, "FOLDER", "Archive") }
        val (file, _) = content.file(share.rootId, share.rootKey, "notes.txt", "hello".encodeToByteArray())
        val pot = content.pot(share.rootId, share.rootKey, "Dinner")
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        val all = mutableListOf<String>()
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            all += j.toString()
            (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf(pick)
        }
        val level = until { it.page?.kind == ReceivedPageKind.LEVEL }.page!!
        val menus = level.rows.associate { it.id to it.menu.map { a -> a.id } }
        assertEquals(listOf(me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE), menus[file])
        assertEquals(listOf(me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE), menus[folder])
        assertEquals(emptyList(), menus[pot], "a guest's Pot offers a move")
        assertEquals(listOf(me.raddatz.veyq.mobile.navigation.LevelTool.SELECT), level.tools)
        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), b.ask(Call.ReceivedMove(opened.received, pot)))
        // Select: the selection shown in the page, its Move over the rows the guest moves
        assertEquals(Answer.Done, b.ask(Call.ReceivedSelectStart(opened.received)))
        assertEquals(Answer.Done, b.ask(Call.ReceivedSelectToggle(opened.received, file)))
        assertEquals(listOf(file), until { it.page?.selection?.selected == listOf(file) }.page!!.selection!!.selected)
        val sheet = assertIs<Answer.MoveOpened>(b.ask(Call.ReceivedSelectMove(opened.received))).move
        val m = b.watching(Watched.Move(sheet))
        m.untilJson { j -> (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Move).shown?.takeIf { it.loaded } }
        assertEquals(Answer.Done, b.ask(Call.MoveGo(sheet, folder)))
        val sent = async { b.ask(Call.MoveHere(sheet)) }
        until { it.naming }
        assertEquals(Answer.Named(null, null, false, false), b.ask(Call.ReceivedNamingSave(opened.received, "Ben")))
        assertNotNull(assertIs<Answer.MoveSent>(sent.await()).done, "the guest's move was not said")
        assertEquals(listOf(file to folder), content.placed.map { (id, body) -> id to body["parentId"].toString().trim('"') })
        // the link read anew: the file no longer lists at the root
        until { s -> s.page?.kind == ReceivedPageKind.LEVEL && s.page!!.rows.none { it.id == file } }
        assertIs<Answer.MoveOpened>(b.ask(Call.ReceivedMove(opened.received, folder)))
        b.ask(Call.ReceivedClose(opened.received))
        val key = share.kShare
        for (json in all) for (c in listOf(b64url(key), b64(key), b64(key).trimEnd('='), hex(key), share.link, share.shareId)) {
            if (json.contains(c)) fail("an update carried the link or its key: $json")
        }
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * #1035: a signed-out READ_WRITE guest replaces the file on screen by path (`Received.Replace`, the link's own queue —
     * no tenant is open); ADR 0154 decision 1: a guest not named yet is asked its name at the write by the core's naming
     * flow ([ReceivedShown.naming]) — declined, nothing queued; an empty name keeps the dialog open; named, the replace
     * waiting on it goes up. Refused before Replace was chosen; the content completed.
     */
    @Test
    fun aSignedOutGuestReplacesTheFileOnScreen() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE")
        val content = PlayedGuestContent(share)
        val (file, _) = content.file(share.rootId, share.rootKey, "notes.txt", "hello guest".encodeToByteArray())
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
        val w = b.watching(Watched.Received(opened.received))
        val all = mutableListOf<String>()
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            all += j.toString()
            (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf(pick)
        }
        until { it.page?.kind == ReceivedPageKind.LEVEL }
        val path = kotlin.io.path.createTempFile("replaced", ".txt").toFile().apply { writeText("new content"); deleteOnExit() }.path
        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), b.ask(Call.ReceivedReplace(opened.received, path)), "a level took a replace")
        b.ask(Call.ReceivedOpenNode(opened.received, file))
        val page = assertNotNull(until { it.page?.kind == ReceivedPageKind.FILE }.page!!.file)
        assertIs<Answer.Said>(b.ask(Call.ReceivedReplace(opened.received, "$path.missing")), "a path that does not read was not said")
        // not named yet: the replace asks the name and waits; declined, nothing is queued
        kotlinx.coroutines.withTimeout(10_000) { while (b.ask(Call.FileChooseReplace(page)) != Answer.Done) kotlinx.coroutines.delay(20) }
        val declined = async { b.ask(Call.ReceivedReplace(opened.received, path)) }
        until { it.naming }
        assertEquals(Answer.Done, b.ask(Call.ReceivedNamingDecline(opened.received)))
        assertEquals(Answer.Refused(Answer.Why.NOT_CHOSEN), declined.await(), "a declined naming's replace was taken")
        assertFalse(until { !it.naming }.naming)
        assertTrue(content.completed.isEmpty(), "a replace went up unnamed")
        // asked again: an empty name keeps the dialog open, a name lets the waiting replace go up
        kotlinx.coroutines.withTimeout(10_000) { while (b.ask(Call.FileChooseReplace(page)) != Answer.Done) kotlinx.coroutines.delay(20) }
        val replaced = async { b.ask(Call.ReceivedReplace(opened.received, path)) }
        until { it.naming }
        val empty = assertIs<Answer.Named>(b.ask(Call.ReceivedNamingSave(opened.received, "  ")))
        assertTrue(empty.keepsOpen && empty.problem != null, "an empty name closed the dialog")
        assertEquals(Answer.Named(null, null, false, false), b.ask(Call.ReceivedNamingSave(opened.received, "Ben")))
        assertEquals(Answer.Queued(1), replaced.await())
        assertEquals("Ben", until { it.guestName != null && !it.naming }.guestName)
        // named: a replace not chosen is refused at once, nothing asked
        assertEquals(Answer.Refused(Answer.Why.NOT_CHOSEN), b.ask(Call.ReceivedReplace(opened.received, path)), "a replace not chosen was taken")
        kotlinx.coroutines.withTimeout(10_000) { while (content.completed.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(1, content.completed.size)
        b.ask(Call.ReceivedClose(opened.received))
        kotlinx.coroutines.delay(300)
        for (json in all) for (c in listOf(b64url(share.kShare), b64(share.kShare), hex(share.kShare), share.link, share.shareId)) {
            if (json.contains(c)) fail("an update carried the link or its key: $json")
        }
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    @Test
    fun aTextThatIsNoLinkIsAnsweredWithWhyAndAReviewWithTheBrowser() = runBlocking {
        val share = PlayedShare(p)
        val b = bridge(share)
        assertEquals(Answer.ReceivedProblem(Msg.LINK_PROBLEM_NOT_A_URL, false), b.ask(Call.ReceivedOpen("hello")))
        assertEquals(Answer.ReceivedProblem(Msg.LINK_PROBLEM_MODERATION, true), b.ask(Call.ReceivedOpen("${share.origin}/s/${share.shareId}#moderation")))
        assertNull(share.requests.firstOrNull(), "a refused text reached a server")
        b.shutdown()
    }
}
