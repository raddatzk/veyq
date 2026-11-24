package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.depot.replace

import me.raddatz.veyq.mobile.expenses.PotGuest

import me.raddatz.veyq.mobile.depot.DepotGuest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * The core's half of #1035, live on a running dev instance as a throwaway (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`): the
 * owner mints the links through the core and the core opens them as their guest, signed out.
 * - A READ_WRITE folder link's guest uploads a file, opens it and replaces its content on the link's own queue
 *   (`GuestAccess.replace`, no tenant's); the page reads the new content.
 * - A READ_WRITE Pot link's named guest is offered the Settings with its people alone: a person added and renamed, nobody's
 *   standing changed, no currency.
 * - A kept link's page renamed shows its new name; trashed, it is left (`ShellModel.afterRename`/`afterTrash`).
 */
class GuestRemainderLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    private fun guest(url: String, queue: UploadQueue? = null): GuestView =
        GuestView.open(
            BouncyCastlePrimitives, (ReceivedLink.open(url) as ReceivedLink.Parsed.Ok).link, { block -> LiveHttp.client(block) },
            MemoryDeviceStore(), MemoryGuestSessions(), { "en" }, queue = queue, now = System::currentTimeMillis,
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

    private fun text(name: String, body: String): UploadSource {
        val bytes = body.encodeToByteArray()
        return object : UploadSource(name, bytes.size.toLong(), "text/plain", null) {
            override suspend fun read(from: Long, length: Int) = bytes.copyOfRange(from.toInt(), minOf(bytes.size, (from + length).toInt()))
        }
    }

    private suspend fun settledDone(queue: UploadQueue) {
        val s = withContext(Dispatchers.Default) { withTimeout(60_000) { queue.state.first { s -> s.batches.isNotEmpty() && s.batches.all { it.finished } } } }
        val failed = s.batches.flatMap { it.items }.filter { it.status != UploadQueue.Status.DONE }
        assertTrue(failed.isEmpty(), "an upload failed: ${failed.map { it.error }}")
    }

    @Test
    fun theCoresRemainderLive() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        runTest(timeout = 6.minutes) {
            suspend fun session(who: String): TenantSession {
                val run = runs.getOrPut(who) { LiveRun(who, t.file) }
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), run.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val stamp = System.currentTimeMillis() % 100_000

            // ---- (1) a signed-out guest replaces a file on the link's own queue ----
            val folder = assertIs<NodeCreate.Result.Done>(FolderCreate.create(owner, scopeId, null, "Replace $stamp")).created.nodeId
            val rwUrl = assertNotNull(ShareLinks.mint(owner, scopeId, folder, ShareLinks.READ_WRITE, "", "", null).url)
            val queue = UploadQueue()
            val rw = GuestScreen(guest(rwUrl, queue))
            passGates(rw)
            val access = assertNotNull(rw.view.access)
            access.nameGuest("Replace guest")
            assertEquals(1, assertIs<GuestPage.Level>(rw.view.page.value).model.upload(listOf(text("notes.txt", "first"))))
            settledDone(queue)
            val row = withContext(Dispatchers.Default) {
                withTimeout(30_000) { rw.view.page.first { p -> (p as? GuestPage.Level)?.model?.rows?.any { it.name == "notes.txt" } == true } }
            }.let { (it as GuestPage.Level).model.rows.single { r -> r.name == "notes.txt" } }
            rw.view.open(row.nodeId)
            val file = assertIs<DepotGuest.File>(rw.view.page.value).model
            suspend fun shown(): String = withContext(Dispatchers.Default) {
                withTimeout(30_000) { (file.state.first { it.shown is FileModel.Shown.Text }.shown as FileModel.Shown.Text).text }
            }
            assertEquals("first", shown())
            assertTrue(file.chooseReplace(), "a READ_WRITE guest's file offers no replace")
            assertTrue(rw.view.access!!.replace(file, text("notes.txt", "second")), "the signed-out guest's replace was not queued")
            settledDone(queue)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) { file.state.first { (it.shown as? FileModel.Shown.Text)?.text == "second" } }
            }
            file.close()
            rw.leave()
            println("[live] #1035 (1): a signed-out READ_WRITE guest replaced a file on the link's own queue; its page reads the new content")

            // ---- (2) a Pot guest's Settings: its people alone ----
            val potId = assertNotNull(PotCreate.create(owner, scopeId, null, "Guest pot $stamp", Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Owner person"))).nodeId)
            val potUrl = assertNotNull(ShareLinks.mint(owner, scopeId, potId, ShareLinks.READ_WRITE, "", "", null).url)
            val pg = GuestScreen(guest(potUrl))
            passGates(pg)
            pg.view.access!!.nameGuest("Pot guest")
            val pot = assertIs<PotGuest.Pot>(pg.view.page.value).model
            pot.open(assertNotNull(pg.view.access!!.tree!!.node(potId)!!.asObject()))
            val view = assertNotNull(pot.view())
            assertTrue(view.settingsOffered, "a named READ_WRITE guest is offered no Settings")
            assertFalse(view.metaShown)
            assertTrue(view.personEntries.none { it.canToggle || it.canErase }, "a guest is offered a member's people step")
            val settings = assertNotNull(pot.settings())
            assertFalse(settings.setBaseCurrency("USD"))
            assertTrue(settings.addPerson("Guest's friend"), "the guest's person was not stored: ${settings.current.notice}")
            val added = assertNotNull(pot.view()!!.personEntries.firstOrNull { it.name == "Guest's friend" })
            assertTrue(settings.rename(added.id, "Guest's friend 2"), "the guest's rename was not stored: ${settings.current.notice}")
            assertFalse(settings.toggleActive(added.id))
            assertTrue(pot.view()!!.personEntries.any { it.name == "Guest's friend 2" })
            pg.leave()
            println("[live] #1035 (2): a Pot's named READ_WRITE guest added and renamed a person; no standing, no currency")

            // ---- (3) a kept link's page after its rename and trash ----
            val personal = owner.spaces().first { it.kind == "PERSONAL" }.scopeId
            val link = (ReceivedLink.open(rwUrl) as ReceivedLink.Parsed.Ok).link
            val kept = assertIs<NodeCreate.Result.Done>(BookmarkCreate.into(owner, personal, null).create("Kept $stamp", link, null)).created.nodeId
            val shell = ShellModel(owner, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
            shell.load(openLastUsed = false)
            val node = assertNotNull(shell.state.first { it != null }!!.lists.nodes.firstOrNull { it.nodeId == kept }?.asBookmark(), "the bookmark is not listed")
            shell.openBookmark(node)
            assertEquals("Kept $stamp", shell.shell?.bookmark?.name)
            val renamed = shell.rename(node, "Kept renamed $stamp")
            assertNull(renamed.notice, "the bookmark's rename: ${renamed.notice}")
            assertEquals("Kept renamed $stamp", shell.shell?.bookmark?.name, "the kept link's page keeps its old name")
            val trashed = shell.trash(shell.shell!!.bookmark!!)
            assertTrue(trashed.gone && trashed.undoable, "the bookmark did not go to the trash: ${trashed.outcome}")
            assertNull(shell.shell?.bookmark, "the kept link's page stays open after its trash")
            assertNull(shell.undoTrash(assertNotNull(trashed.before)), "the trash's undo failed")
            println("[live] #1035 (3): a kept link's page heads its new name after the rename, is left after the trash, the undo restored it")
        }
    }
}
