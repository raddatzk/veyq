package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.media.Derivation
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertFailsWith
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
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

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
            val deriver = object : Derivation.Deriver {
                override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) = done(Derivation.Derived(photo, photo, 640, 480))
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

    /**
     * A Memory at an import's top that fails after its create (its photo's on-device stage throws) is trashed whole: core
     * learns no id from a hook that throws, so the hook trashes it itself (inbox #1150 — on the web it stayed in the level
     * with the photos before the failure while the dialog said everything went to the trash). The download is the web's,
     * its manifest re-rooted at its Memory, as a Memory's own download is.
     */
    @Test
    fun aMemoryThatFailsAfterItsCreateIsTrashed() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        val zip = memoryRooted(File(root, "contracts/export/import-cases/web-download.zip"), "Lisbon")
        runTest(timeout = 6.minutes) {
            val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), liveRun.device()) { block -> HttpClient(OkHttp, block) }
            val tenant = a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            val scopeId = tenant.spaces().first { it.kind == "TENANT" }.scopeId
            val folder = (FolderCreate.create(tenant, scopeId, null, "App import fail ${System.currentTimeMillis() % 100_000}") as NodeCreate.Result.Done).created.nodeId
            val failing = object : Derivation.Deriver {
                override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) = throw IllegalStateException("decode_failed")
            }
            val kinds = AppKinds.imports("Deleted people", { NodeCreate.uuid(tenant.account) }, failing) { System.currentTimeMillis() }
            val input = ImportInput.Zip(UploadSource.of("Lisbon.zip", zip.size.toLong(), "application/zip", null) { from, length ->
                zip.copyOfRange(from.toInt(), minOf(zip.size.toLong(), from + length).toInt())
            })
            val e = assertFailsWith<SubtreeImport.Failed> {
                SubtreeImport.run(SubtreeImport.open(input), scopeId, folder, SubtreeImport.Server(tenant, scopeId), kinds, AppKinds::tokenInput)
            }
            assertTrue(e.rolledBack)
            tenant.forgetTree()
            val left = tenant.trees()[scopeId].orEmpty().filter { it.parentId == folder }
            assertEquals(emptyList(), left.map { it.name }, "nothing of the Memory stays in the level")
        }
    }

    /** [zip], its entries stored, with its manifest's root replaced by the node named [name] and the paths one level up, as that node's own download. */
    private fun memoryRooted(zip: File, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipFile(zip).use { z ->
            ZipOutputStream(out).use { w ->
                for (entry in z.entries()) {
                    if (entry.name == "Trip/") continue
                    val bytes = z.getInputStream(entry).readBytes()
                    val written = if (entry.name != ".veyq/manifest.json") bytes else {
                        val m = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                        fun find(n: JsonObject): JsonObject? =
                            if ((n["name"] as JsonPrimitive).content == name) n
                            else (n["children"] as? JsonArray).orEmpty().firstNotNullOfOrNull { find(it.jsonObject) }
                        JsonObject(m + ("root" to find(m.getValue("root").jsonObject)!!)).toString().replace("\"Trip/", "\"").encodeToByteArray()
                    }
                    // stored, as a download is: the import reads no deflated entry
                    w.putNextEntry(ZipEntry(entry.name.removePrefix("Trip/")).apply {
                        method = ZipEntry.STORED
                        size = written.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(written) }.value
                    })
                    w.write(written)
                    w.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
