package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.memories.MemoriesGuest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * The three gaps the Android pass found (#1032–#1034), live on a running dev instance as a throwaway (`VEYQ_LIVE`,
 * `VEYQ_LIVE_THROWAWAY_ENV`): the owner mints the links through the core and the core opens them as their guest, signed out.
 * - #1033: a READ link of a Memory reported from a bookmark's *Report shared content* (its root, whole) and a medium as an
 *   ITEM — both received by the instance (before: `404 report_item_not_found`).
 * - #1034: a READ_WRITE folder link's guest names itself, adds a folder and uploads a file at the level; the owner lists both.
 * - #1032: a bookmark in the owner's personal space is listed on its level, a search's hit opens its page, and its page
 *   opens the link on its own page.
 */
class GuestGapsLiveTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    private fun guest(url: String, queue: UploadQueue? = null, password: String? = null): GuestView =
        GuestView.open(
            BouncyCastlePrimitives, (ReceivedLink.open(url) as ReceivedLink.Parsed.Ok).link, { block -> LiveHttp.client(block) },
            MemoryDeviceStore(), MemoryGuestSessions(), { "en" }, handedPassword = password, queue = queue, now = System::currentTimeMillis,
        )

    private suspend fun passGates(screen: GuestScreen) {
        screen.load()
        while (true) when (screen.view.gates.state.value) {
            is ShareGates.State.Terms -> { screen.view.gates.acceptTerms(); screen.enter() }
            is ShareGates.State.Note -> { screen.view.gates.acceptNote(); screen.enter() }
            else -> break
        }
        assertIs<ShareGates.State.Ready>(screen.view.gates.state.value, "the gates did not open: ${screen.view.gates.state.value}")
    }

    private suspend fun send(sheet: me.raddatz.veyq.mobile.navigation.ReportSheet): NodeReport.Sent? {
        sheet.category("TERMS_BREACH")
        sheet.reason("live check of a guest's report (throwaway)")
        sheet.goodFaith(true)
        return sheet.send().sent
    }

    @Test
    fun theThreeGapsLive() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 6.minutes) {
            suspend fun session(who: String): TenantSession {
                val run = runs.getOrPut(who) { LiveRun(who, t.file) }
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), run.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val stamp = System.currentTimeMillis() % 100_000
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            fun bytes(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }

            // ---- #1033: a viewing link reported from the views ----
            val memory = assertNotNull(MemoryCreate.create(owner, scopeId, null, MemoryCreate.MEMORY, "Gaps $stamp").node).nodeId
            MediaUpload.upload(owner, scopeId, memory, MediaUpload.Original(bytes(photo), "image/jpeg", "Owner.jpg", 1), Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis())
            val readUrl = assertNotNull(ShareLinks.mint(owner, scopeId, memory, ShareLinks.READ, "", "", null).url)
            val reported = GuestScreen(guest(readUrl), reportAsked = true)
            passGates(reported)
            assertTrue(reported.state.value.reporting)
            val rootReport = send(assertNotNull(reported.reportSheet()))
            assertEquals(NodeReport.Outcome.SENT, rootReport?.outcome, "a READ link's root report was not received")
            val gallery = assertIs<MemoriesGuest.Gallery>(reported.view.page.value).model
            val rows = withContext(Dispatchers.Default) { withTimeout(30_000) { gallery.state.first { it.rows != null }.rows!! } }
            val itemReport = send(assertNotNull(gallery.reportSheet(rows.first().mediaId), "a viewing guest is offered no medium's report"))
            assertEquals(NodeReport.Outcome.SENT, itemReport?.outcome, "a medium's ITEM report was not received")
            gallery.close()
            reported.leave()
            println("[live] #1033: READ link's root reported (ref ${rootReport?.reference}), a medium as an ITEM (ref ${itemReport?.reference})")

            // ---- #1034: a READ_WRITE folder's guest adds a folder and a file ----
            val folder = assertIs<NodeCreate.Result.Done>(FolderCreate.create(owner, scopeId, null, "Gaps folder $stamp")).created.nodeId
            val rwUrl = assertNotNull(ShareLinks.mint(owner, scopeId, folder, ShareLinks.READ_WRITE, "", "", null).url)
            val queue = UploadQueue()
            val rw = GuestScreen(guest(rwUrl, queue))
            passGates(rw)
            val level = assertIs<GuestPage.Level>(rw.view.page.value).model
            assertTrue(level.writes)
            assertNotNull(rw.view.access).nameGuest("Gaps guest")
            assertEquals(me.raddatz.veyq.mobile.navigation.NameAnswer.DONE, level.createFolder("Guest folder"), "the guest's folder was refused")
            val after = assertIs<GuestPage.Level>(rw.view.page.value).model
            assertTrue(after.rows.any { it.name == "Guest folder" }, "the guest's folder is not listed after the write")
            val text = "a guest's file".encodeToByteArray()
            val source = object : UploadSource("guest.txt", text.size.toLong(), "text/plain", null) {
                override suspend fun read(from: Long, length: Int) = text.copyOfRange(from.toInt(), minOf(text.size, (from + length).toInt()))
            }
            assertEquals(1, after.upload(listOf(source)))
            val settled = withContext(Dispatchers.Default) { withTimeout(60_000) { queue.state.first { s -> s.batches.isNotEmpty() && s.batches.all { it.finished } } } }
            assertEquals(UploadQueue.Status.DONE, settled.batches.single().items.single().status, "the guest's upload: ${settled.batches.single().items.single().error}")
            rw.leave()
            val ownerNodes = session("VEYQ").nodes()
            assertTrue(ownerNodes.any { it.parentId == folder && it.name == "Guest folder" && it.isFolder }, "the owner does not list the guest's folder")
            assertTrue(ownerNodes.any { it.parentId == folder && it.name == "guest.txt" && it.kind == "FILE" }, "the owner does not list the guest's file")
            println("[live] #1034: READ_WRITE folder's guest named, made a folder and uploaded a file; the owner lists both")

            // ---- #1032: a bookmark listed, found and opened ----
            val personal = owner.spaces().first { it.kind == "PERSONAL" }.scopeId
            val link = (ReceivedLink.open(readUrl) as ReceivedLink.Parsed.Ok).link
            val made = assertIs<NodeCreate.Result.Done>(BookmarkCreate.into(owner, personal, null).create("Kept $stamp", link, null)).created.nodeId
            val nodes = session("VEYQ").nodes()
            val row = assertNotNull(Home.level(nodes, personal, null, null).firstOrNull { it.nodeId == made }, "the bookmark is not listed on its level")
            val bookmark = assertNotNull(row.asBookmark())
            assertEquals(bookmark, ShellModel.found(Shell(null, space = scopeId).search(true), nodes, personal, made).first.bookmark, "a search's hit did not open the bookmark's page")
            val page = BookmarkPage.of(owner, bookmark) { l, pw -> guest(l.link, password = pw) }
            page.load()
            val state = page.state.value
            assertTrue(state is BookmarkPage.State.InPlace || state is BookmarkPage.State.OnItsOwn, "the bookmark's page did not read: $state")
            val own = assertNotNull(page.openOwnPage(owner))
            passGates(own)
            assertIs<MemoriesGuest.Gallery>(own.view.page.value, "the bookmark's own page did not open the kept Memory")
            own.leave()
            page.close()
            println("[live] #1032: bookmark listed on the personal space's level, found by search as its page, opened (${state::class.simpleName}) and on its own page")
        }
    }
}
