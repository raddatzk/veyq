package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.QueuedExpenses
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.QueuedMemories
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writes made offline and sent at the reconnect against a running dev instance (`VEYQ_LIVE`), as a throwaway account
 * (`VEYQ_LIVE_THROWAWAY_ENV`, ADR 0148 decision 12) — skipped without either (the CR's phases 3 and 4, "one live case").
 *
 * A device of the throwaway's own, remembered at LOGIN with a store, reads its personal space online (a Memory and a Pot
 * made there, so both are kept), then the network is cut and the account unlocked offline: a chapter is made in the
 * Memory and two photos added to it; an entry with a receipt added to the Pot. Back online, the reconnect sends them:
 * the chapter is in the tree, both photos listed in it and opening to the bytes picked, the entry in the Pot with its
 * receipt opening. Everything made is trashed and purged, the device taken off the account however the case ends.
 */
class LiveQueuedTest {
    private val accounts by lazy { Throwaway.accounts() }
    private val liveRun by lazy { LiveRun(envFile = accounts.file) }

    @AfterTest
    fun takeOffTheDevices() {
        if (System.getenv("VEYQ_LIVE") != null && System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null) liveRun.removeAll()
    }

    @Volatile private var reachable = true

    private val cut = createClientPlugin("CutNetwork") {
        onRequest { _, _ -> if (!reachable) throw java.io.IOException("the network is cut") }
    }

    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp) { block(); install(cut) } }

    private fun picked(name: String, bytes: ByteArray) = UploadSource.of(name, bytes.size.toLong(), "image/jpeg", 1_700_000_000_000L) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    @Test
    fun aChapterWithTwoPhotosAndAnEntryWithAReceiptMadeOfflineAreSentAtTheReconnect() = runBlocking {
        val origin = Throwaway.origin()
        val env = accounts.env
        val p = BouncyCastlePrimitives
        val root = Files.createTempDirectory("veyq-queued-live").toFile()
        val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live queued test", p, offlineRoot = root.absolutePath)
        var account = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN))
        val tenantRef = account.defaultTenant!!
        var tenant = account.open(tenantRef)
        val scopeId = tenant.spaces().first { tenant.scopeKind(it.scopeId) == "PERSONAL" }.scopeId
        val stamp = System.currentTimeMillis() % 100_000
        val memory = assertNotNull(MemoryCreate.create(tenant, scopeId, null, MemoryCreate.MEMORY, "Queued live $stamp").node)
        val potId = assertNotNull(PotCreate.create(tenant, scopeId, null, "Queued pot $stamp", Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Anna"))).nodeId)
        try {
            // seen online: the tree and the Pot are kept
            tenant.forgetTree()
            tenant.trees()
            assertNotNull(PotSession.open(tenant, scopeId, potId))
            account.retire()
            reachable = false
            account = VeyqAccount.resume(p, device.trusted().first(), device, client)
            assertIs<Reach.Offline>(account.reach.value)
            tenant = account.open(tenantRef)

            val chapter = assertNotNull(QueuedMemories.create(account, tenantRef.tenantId, scopeId, memory.nodeId, MemoryCreate.CHAPTER, "Day one", System.currentTimeMillis()))
            val photo = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            val deriver = object : Derivation.Deriver {
                // copies: the core zeroes the tiers once sealed, and [photo] is what both picks read and what is compared
                override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) =
                    done(Derivation.Derived(photo.copyOf(), photo.copyOf(), null, null))
            }
            val media = QueuedMemories.media(account, tenantRef.tenantId, scopeId, chapter.id, listOf(picked("one.jpg", photo), picked("two.jpg", photo)), deriver, { System.currentTimeMillis() }) {}
            assertEquals(2, media.size)

            val pot = assertNotNull(PotSession.open(tenant, scopeId, potId), "the Pot is not kept")
            val view = PotView(pot)
            val form = assertNotNull(view.blankForm(java.time.LocalDate.now().toString())).copy(title = "Taxi offline", payers = listOf(EntryForm.Payer(view.people.first().id, "9.50")))
            val scan = "live receipt ${System.nanoTime()}".encodeToByteArray()
            val entry = assertNotNull(QueuedExpenses.saveEntry(pot, null, form, null, listOf(PotSession.PendingReceipt(pot.newId(), scan, "image/png", "scan.png", null)), System.currentTimeMillis()))
            assertEquals(4, account.outbox!!.count())

            reachable = true
            assertTrue(account.reconnect())
            account.awaitSending()
            val left = account.outbox!!.writes()
            assertEquals(emptyList(), left.map { it.op + ":" + it.state + ":" + it.reason }, "writes not sent")

            tenant = account.open(tenantRef)
            tenant.forgetTree()
            val made = tenant.trees()[scopeId].orEmpty().first { it.id == chapter.id }
            assertEquals("Day one", made.name)
            assertEquals(memory.nodeId, made.parentId)
            val listed = assertNotNull(MemoryMedia.list(tenant, scopeId, chapter.id)).items
            assertEquals(media.map { it.id }.toSet(), listed.map { it.mediaId }.toSet())
            for (item in listed) assertContentEquals(photo, MemoryMedia.read(tenant, item, item.original), "a photo sent does not open to what was picked")

            val sent = assertNotNull(PotSession.open(tenant, scopeId, potId))
            val entryId = QueuedExpenses.entry(entry)!!.entryId
            assertEquals("Taxi offline", PotView(sent).entries.first { it.id == entryId }.title)
            val receipt = sent.receipts.single { it.entryId == entryId }
            assertContentEquals(scan, sent.openReceipt(receipt.receiptId))
            println("veyq-live: a chapter, two photos and an entry with a receipt made offline were sent at the reconnect")
        } finally {
            reachable = true
            runCatching { account.retire() }
            // the cleanup never masks the case's own failure: a trust the case lost leaves nothing to resume with
            runCatching {
                val again = VeyqAccount.resume(p, device.trusted().first(), device, client)
                val t = again.open(tenantRef)
                for (id in listOf(memory.nodeId, potId)) {
                    runCatching { NodeTrash.delete(t, scopeId, id) }
                    runCatching { NodeTrash.purge(t, scopeId, id) }
                }
                runCatching { again.signOut() }
            }.onFailure { println("veyq-live: the cleanup did not run: $it") }
            root.deleteRecursively()
        }
    }
}
