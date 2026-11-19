package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A Memory's and a chapter's download (ADR 0149 decision 3), the web's `exportImport.test.ts` download cases twinned over a
 * server the test plays with real seals ([PlayedTenant]): the content a member downloads — every medium's original to the
 * byte with its facts and its contributor, the date, the place, the participants by name, the cover and the banner as
 * files —, a guest's without any name or EXIF copy, and a folder's download that carries its Memory and the chapter in it.
 */
class MemoryExportTest {
    /** The fixture's Memory: two photos (one corrected, placed and EXIF'd), a chapter with one, the facts, a cover, an image banner. */
    internal class Fixture(val t: PlayedTenant, val s: PlayedTenant.Scope, val folder: PlayedTenant.Node, val memory: PlayedTenant.Node, val chapter: PlayedTenant.Node, val exifReads: () -> Int)

    companion object {
        internal fun fixture(t: PlayedTenant = PlayedTenant()): Fixture {
            // the tenant's own scope (its id the tenant's): the open reads the tenant's directory from its members
            t.tenants[0] = PlayedTenant.Tenant(t.id("s1"), "a.example")
            val s = t.scope("s1", kind = "TENANT")
            val folder = t.node(s, "trips", kind = "FOLDER", name = "Trips")
            val m = t.node(s, "memory", parent = folder, name = "Lisbon")
            m.meta = mapOf(
                "date" to buildJsonObject { put("mode", "range"); put("from", "2025-09-12"); put("to", "2025-09-14") },
                "location" to buildJsonObject { put("name", "Lisboa"); put("nameSource", "manual"); put("derivedMode", "fixed") },
            )
            t.participants(m, t.userId, "00000000-0000-4000-8000-0000000000ff")
            val a = t.medium(m, "a", preview = 1, fileName = "IMG_0001.jpg", modifiedAt = 1_600_000_000_000)
            a.tier = """{"v":1,"w":4032,"h":3024,"capturedAt":1757660000000,"capturedAtOverride":1757670000000,"gpsLat":38.7,"gpsLng":-9.1,"placeName":"Alfama"}"""
            a.owner = "user:${t.userId}"
            val b = t.medium(m, "b", fileName = "IMG_0002.jpg", modifiedAt = 1_600_000_100_000)
            b.owner = "user:${t.userId}"
            t.cover(m, "cover")
            t.banner(m, "banner", video = false)
            val chapter = t.node(s, "chapter", kind = "CHAPTER", parent = m, name = "Day one")
            t.medium(chapter, "c", fileName = "IMG_0003.jpg", modifiedAt = 1_600_000_200_000).also { it.tier = """{"v":1,"capturedAt":1757600000000}""" }
            var exif = 0
            val exifSealed = mapOf(a.id to """{"make":"Fujifilm","model":"X100V"}""")
            t.route = { r ->
                val p = r.url.encodedPath
                if (p.endsWith("/exif")) {
                    exif++
                    val mediaId = p.split('/')[5]
                    val wrapped = exifSealed[mediaId]?.let { b64(t.suite.sealField(Context("media-exif", "scopeId" to s.id, "mediaId" to mediaId), m.key, it.encodeToByteArray())) }
                    200 to (if (wrapped != null) """{"wrappedExif":"$wrapped"}""" else "{}")
                } else null
            }
            return Fixture(t, s, folder, m, chapter) { exif }
        }

        /** The content built over refs naming each payload's index, and each payload's bytes as it streamed. */
        internal suspend fun built(c: NodeExport.Content): Pair<JsonObject, List<ByteArray>> {
            val bytes = c.payloads.map { p ->
                val out = ArrayList<Byte>()
                p.stream { chunk -> out += chunk.toList() }
                assertEquals(p.size, out.size.toLong(), "${p.fileName} streams the size it declared")
                out.toByteArray()
            }
            return c.build(c.payloads.indices.map { i -> buildJsonObject { put("path", "p$i"); put("name", c.payloads[i].fileName) } }) to bytes
        }
    }

    private suspend fun open(f: Fixture): TenantSession {
        f.t.remember()
        val account = f.t.resume()
        return account.open(account.tenants.first())
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    @Test
    fun aMembersDownloadCarriesEveryOriginalWithItsFacts_theNamesTheCoverAndTheBanner() = runTest {
        val f = fixture()
        val tenant = open(f)
        val node = assertNotNull(tenant.node(f.s.id, f.memory.id))
        val export = assertNotNull(MemoryExport.content(tenant, node))
        val (content, bytes) = built(export)
        assertEquals("memories.memory", content.str("type"))
        assertEquals("1", content.str("schema"))
        val media = (content["media"] as JsonArray).map { it as JsonObject }
        val a = media.single { it.str("name") == "IMG_0001.jpg" }
        assertEquals("2025-09-12T09:40:00.000Z", a.str("capturedAt"), "the effective time: the correction")
        assertEquals("true", a.str("capturedAtCorrected"))
        assertEquals("""{"lat":38.7,"lon":-9.1,"place":"Alfama"}""", a["location"].toString())
        assertEquals("""{"make":"Fujifilm","model":"X100V"}""", a["exif"].toString())
        assertEquals("me", a.str("contributor"), "named by the tenant's directory")
        val aFile = (a["file"] as JsonObject).str("path")!!.removePrefix("p").toInt()
        assertEquals("original a frame 0", bytes[aFile].decodeToString(), "the original to the byte")
        assertEquals(1_757_670_000_000, export.payloads[aFile].modifiedAt, "the payload's time: the effective capture time")
        val b = media.single { it.str("name") == "IMG_0002.jpg" }
        assertNull(b["capturedAt"])
        assertEquals(setOf("file", "name", "contributor"), b.keys, "no facts it does not have")
        assertEquals(1_600_000_100_000, export.payloads.first { it.fileName == "IMG_0002.jpg" }.modifiedAt, "no capture time: the file's own")

        assertEquals("""{"mode":"range","from":"2025-09-12","to":"2025-09-14"}""", content["date"].toString())
        assertEquals("Lisboa", (content["location"] as JsonObject).str("name"))
        assertEquals(listOf("me"), (content["participants"] as JsonArray).map { it.jsonPrimitive.content }, "a name for each current member, none for an unknown id")
        val cover = content["cover"] as JsonObject
        assertEquals("cover cover", bytes[(cover["file"] as JsonObject).str("path")!!.removePrefix("p").toInt()].decodeToString())
        val banner = content["banner"] as JsonObject
        assertEquals("image", banner.str("medium"))
        assertEquals("banner.jpg", (banner["file"] as JsonObject).str("name"))
        assertEquals("banner banner frame 0", bytes[(banner["file"] as JsonObject).str("path")!!.removePrefix("p").toInt()].decodeToString())
        assertEquals(listOf("fit", """{"x":0.5,"y":0.5}"""), listOf(banner.str("frame"), banner["focal"].toString()))

        val chapter = assertNotNull(MemoryExport.content(tenant, assertNotNull(tenant.node(f.s.id, f.chapter.id))))
        assertEquals("memories.chapter", chapter.type)
        assertEquals(listOf("IMG_0003.jpg"), chapter.payloads.map { it.fileName }, "a chapter's media are its own")
    }

    @Test
    fun aGuestsDownloadNamesNoOne_andCarriesNoExifCopy() = runTest {
        val f = fixture()
        val tenant = open(f)
        val (content, _) = built(assertNotNull(MemoryExport.content(tenant, assertNotNull(tenant.node(f.s.id, f.memory.id)), names = null)))
        assertEquals(setOf(f.t.userId), tenant.people.keys, "control: the directory is there for a member")
        assertNull(content["participants"], "a guest never reads the member directory")
        val media = (content["media"] as JsonArray).map { it as JsonObject }
        assertEquals(2, media.size, "control: the media are there")
        assertEquals(listOf(null, null), media.map { it["contributor"] })
        assertEquals(listOf(null, null), media.map { it["exif"] })
        assertEquals(0, f.exifReads(), "the EXIF tier is never asked for")
        assertEquals("Lisboa", (content["location"] as JsonObject).str("name"), "control: the facts are there")
    }

    @Test
    fun aFoldersDownloadCarriesItsMemoryAndTheChapterInIt_nothingLeftOut() = runTest {
        val f = fixture()
        val tenant = open(f)
        val walked = assertNotNull(NodeExport.walk(tenant, f.s.id, f.folder.id, AppKinds.exports("deleted")))
        assertEquals(emptyList(), walked.skipped, "a Memory is no longer left out")
        val memory = walked.root.children.single()
        assertEquals("MEMORY" to "Lisbon", memory.kind to memory.name)
        assertEquals(listOf("Day one"), memory.children.map { it.name })
        assertEquals("memories.chapter", memory.children.single().content?.type)
    }
}
