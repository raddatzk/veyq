package me.raddatz.veyq.mobile.export

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The web's download imported by the core (ADR 0152), on a running dev instance as a throwaway (`VEYQ_LIVE`,
 * `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): `contracts/export/import-cases/web-download.zip` — a folder with a Depot file, a
 * drop box with a guest's folder, a Pot with an expense and its receipt, a Memory with a photo and a chapter — imported into
 * a folder of the tenant's space through `SubtreeImport.run` with the build's own kinds, then read back in a fresh session:
 * the file's bytes, the drop box a plain folder, the Pot's people, expense and receipt to the byte, the Memory's photo to
 * its SHA-256 and its chapter. A second import of the same download beside it is numbered `Trip (2)`. Everything stays in
 * the throwaway, erased with it.
 */
class ImportLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun theWebsDownloadIsImportedAndReadsBack() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        val zip = File(root, "contracts/export/import-cases/web-download.zip").readBytes()
        val entries = ZipFile(File(root, "contracts/export/import-cases/web-download.zip")).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).readBytes() }
        }
        runTest(timeout = 6.minutes) {
            suspend fun session(): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), liveRun.device()) { block -> HttpClient(OkHttp, block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val tenant = session()
            val scopeId = tenant.spaces().first { it.kind == "TENANT" }.scopeId
            val n = System.currentTimeMillis() % 100_000
            val folder = (FolderCreate.create(tenant, scopeId, null, "App import $n") as NodeCreate.Result.Done).created.nodeId
            val photo = entries.getValue("Trip/Lisbon/IMG_0001.jpg")
            // the platform's tiers, played: the photo stands in for its own preview and thumbnail
            val deriver = object : MediaUpload.Deriver {
                override fun derive(source: UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) = done(MediaUpload.Derived(photo, photo, 640, 480))
            }
            val kinds = AppKinds.imports("Deleted people", { NodeCreate.uuid(tenant.account) }, deriver) { System.currentTimeMillis() }
            suspend fun import(): SubtreeImport.Result {
                val input = ImportInput.Zip(UploadSource.of("Trip.zip", zip.size.toLong(), "application/zip", null) { from, length ->
                    zip.copyOfRange(from.toInt(), minOf(zip.size.toLong(), from + length).toInt())
                })
                return SubtreeImport.run(SubtreeImport.open(input), scopeId, folder, SubtreeImport.Server(tenant, scopeId), kinds, AppKinds::tokenInput)
            }
            val first = import()
            assertEquals("Trip", first.name)
            assertEquals(7, first.nodes)
            assertEquals(emptyList(), first.skipped)
            assertEquals("Trip (2)", import().name, "a second import beside it is numbered")

            // read back in a fresh session
            val again = session()
            val nodes = again.trees()[scopeId].orEmpty()
            val trip = nodes.single { it.id == first.nodeId }
            fun child(parent: String, name: String) = assertNotNull(nodes.singleOrNull { it.parentId == parent && it.name == name }, name)
            val file = child(trip.id, "notes.txt")
            assertEquals("FILE", file.kind)
            val out = ByteArrayOutputStream()
            assertTrue(DepotFiles.download(again, scopeId, file.id) { out.write(it) })
            assertContentEquals(entries.getValue("Trip/notes.txt"), out.toByteArray())
            val inbox = child(trip.id, "Inbox")
            assertEquals("FOLDER", inbox.kind, "a drop box imports as a plain folder")
            assertEquals("FOLDER", child(inbox.id, "Anna").kind)

            val potNode = child(trip.id, "Budget")
            assertEquals(PotSession.KIND, potNode.kind)
            val pot = assertNotNull(PotSession.open(again, scopeId, potNode.id))
            val ledger = assertNotNull(pot.ledger)
            assertEquals(listOf("Anna", "Ben"), ledger.participants.map { it.name })
            val dinner = ledger.entries.single { it.status == Ledger.Status.LIVE && it.content is Expense }
            assertEquals("Dinner", (dinner.content as Expense).title)
            assertEquals(4200L, (dinner.content as Expense).amount)
            pot.readReceipts()
            val receipt = pot.receipts.single()
            assertEquals(dinner.id, receipt.entryId)
            assertEquals("Dinner.jpg", receipt.name)
            assertContentEquals(entries.getValue("Trip/Budget/Dinner.jpg"), assertNotNull(pot.openReceipt(receipt.receiptId)))

            val lisbon = child(trip.id, "Lisbon")
            assertEquals("MEMORY", lisbon.kind)
            assertEquals("CHAPTER", child(lisbon.id, "Day one").kind)
            val media = assertNotNull(MemoryMedia.list(again, scopeId, lisbon.id)).items
            assertEquals(1, media.size)
            assertEquals("IMG_0001.jpg", media.single().name)
            val item = media.single()
            val reader = assertNotNull(MemoryMedia.reader(again, item, item.original))
            try {
                assertEquals(sha(photo), sha(reader.read(0, photo.size)), "the photo to its SHA-256")
            } finally {
                reader.close()
            }
        }
    }
}
