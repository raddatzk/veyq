package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * A Memory downloaded and copied by the app (ADR 0149 decision 3), on a running dev instance as a throwaway (`VEYQ_LIVE`,
 * `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): in a folder of the tenant's space, a Memory with a photo, a video and a video
 * banner. The folder's download carries the Memory (nothing left out), every original at its SHA-256 and the banner's video
 * and poster; the Memory's copy into the folder reads back in a fresh session — every original and tier to the byte, the
 * listed tiers, the banner's video and poster. The web's import of an app's download is held by the fixture
 * (`MemoryDownloadFixtureTest`, the web's `exportImport.test.ts`). Everything stays in the throwaway, erased with it.
 */
class MemoryExportLiveTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(MemoriesKinds) }

    /** Each account's devices taken off that account of the throwaway (never the seeded principal's `.run/.env`). */
    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun aMemoryIsDownloadedAndCopied_theCopyReadsBackToTheByte() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 6.minutes) {
            suspend fun session(): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), runs.getOrPut("VEYQ") { LiveRun("VEYQ", t.file) }.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val tenant = session()
            val scopeId = tenant.spaces().first { it.kind == "TENANT" }.scopeId
            val n = System.currentTimeMillis() % 100_000
            val folder = (FolderCreate.create(tenant, scopeId, null, "App export $n") as NodeCreate.Result.Done).created.nodeId
            val memory = assertNotNull(MemoryCreate.create(tenant, scopeId, folder, MemoryCreate.MEMORY, "Lisbon").node).nodeId
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            val video = File(root, "src/web/src/shared/image/__fixtures__/dated.mp4").readBytes()
            fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
            for ((bytes, name, type) in listOf(Triple(photo, "Photo.jpg", "image/jpeg"), Triple(video, "Clip.mp4", "video/mp4"))) {
                val up = MediaUpload.upload(tenant, scopeId, memory, MediaUpload.Original(source(bytes), type, name, 1_000_000), Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis())
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
            }
            val picked = BannerWrite.Picked(UploadSource.of("banner.mp4", video.size.toLong(), "video/mp4", null) { from, length ->
                video.copyOfRange(from.toInt(), minOf(video.size.toLong(), from + length).toInt())
            }, Banner.Medium.VIDEO, "video/mp4", photo)
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(tenant, scopeId, memory, Banner.Slots.NONE, picked, emptyList()))

            // the folder's download: the Memory in it, nothing left out, every payload at its digest
            val zipFile = File.createTempFile("veyq-memory-download", ".zip")
            try {
                val walked = assertNotNull(NodeExport.walk(session(), scopeId, folder, AppKinds.exports("deleted")))
                assertEquals(emptyList(), walked.skipped)
                zipFile.outputStream().use { out -> NodeExport.write(walked.root, "live", System.currentTimeMillis()) { out.write(it) } }
                ZipFile(zipFile).use { zip ->
                    val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(NodeExport.MANIFEST_PATH)).readBytes().decodeToString()) as JsonObject
                    val content = ((manifest["root"] as JsonObject)["children"] as JsonArray).single().let { (it as JsonObject)["content"] as JsonObject }
                    val files = (content["media"] as JsonArray).associate { m ->
                        val f = (m as JsonObject)["file"] as JsonObject
                        m["name"]!!.jsonPrimitive.content to (f["path"]!!.jsonPrimitive.content to f["sha256"]!!.jsonPrimitive.content)
                    }
                    for ((name, bytes) in listOf("Photo.jpg" to photo, "Clip.mp4" to video)) {
                        val (path, digest) = files.getValue(name)
                        assertEquals(sha(bytes), digest)
                        assertEquals(sha(bytes), sha(zip.getInputStream(zip.getEntry(path)).readBytes()), "$name in the archive to the byte")
                    }
                    val banner = content["banner"] as JsonObject
                    assertEquals("video", banner["medium"]!!.jsonPrimitive.content)
                    val bannerPath = (banner["file"] as JsonObject)["path"]!!.jsonPrimitive.content
                    assertEquals(sha(video), sha(zip.getInputStream(zip.getEntry(bannerPath)).readBytes()), "the banner's video")
                    assertTrue(bannerPath.endsWith("banner.mp4") && banner["poster"] != null)
                }
            } finally {
                zipFile.delete()
            }

            // the Memory's copy beside it, read back in a fresh session
            val copied = SubtreeCopy.duplicate(tenant, scopeId, memory, folder, AppKinds.copies("deleted") { NodeCreate.uuid(tenant.account) })
            assertEquals(SubtreeCopy.Outcome.COPIED, copied.outcome)
            assertEquals("Lisbon (2)", copied.name)
            val fresh = session()
            val copy = assertNotNull(fresh.node(scopeId, assertNotNull(copied.nodeId)))
            val source = assertNotNull(MemoryMedia.list(fresh, scopeId, memory)).items.associateBy { it.name }
            val items = assertNotNull(MemoryMedia.list(fresh, scopeId, copy.id)).items
            assertEquals(setOf("Photo.jpg", "Clip.mp4"), items.map { it.name }.toSet())
            for (item in items) {
                val from = source.getValue(item.name)
                assertTrue(MemoryMedia.read(fresh, item, item.original)!!.contentEquals(MemoryMedia.read(fresh, from, from.original)!!), "${item.name}'s original")
                assertTrue(MemoryMedia.read(fresh, item, item.preview!!)!!.contentEquals(MemoryMedia.read(fresh, from, from.preview!!)!!), "${item.name}'s preview, not derived again")
                assertEquals(from.meta?.capturedAt, item.meta?.capturedAt, "${item.name}'s listed tier")
                assertEquals(from.type, item.type)
            }
            assertTrue(Banner.picture(fresh, copy)!!.contentEquals(photo), "the copy's banner poster")
            val reader = assertNotNull(Banner.reader(fresh, copy))
            assertTrue(reader.read(0, video.size).contentEquals(video), "the copy's banner video")
            reader.close()
            println("[live] \"App export $n\": downloaded with its Memory, the Memory copied as \"${copied.name}\" and read back to the byte")
        }
    }
}
