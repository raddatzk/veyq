package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's copy (ADR 0149 decision 3), the web's `exportImport.test.ts` copy cases twinned: the source read over a server
 * the test plays with real seals ([MemoryExportTest.fixture]), the writes into a target the test plays. Per medium its
 * original and each tier handed on as they are — read a frame at a time, their declared meta carried —, its listed tier
 * verbatim and its EXIF; the new node born with the date and the place; the cover's and the banner's files with their
 * framing; and a failure part-way trashing what was made. Nothing is decoded: the copy has no deriver to ask.
 */
class MemoryCopyTest {
    private inner class Played : MemoryCopy.Target {
        val created = mutableListOf<Triple<String, String, JsonObject?>>()
        val media = mutableListOf<MemoryCopy.Medium>()
        /** Each medium's blobs as the upload read them, while the copy held its readers open. */
        val read = mutableListOf<List<String>>()
        var cover: Pair<ByteArray, NodeCover.Crop>? = null
        var banner: Triple<BannerWrite.Prepared, Banner.Frame, Banner.Focal>? = null
        val trashed = mutableListOf<String>()
        var failAt: Int? = null
        val made = MemoryCopy.Created.Made("33333333-3333-4333-8333-333333333333", ByteArray(32), 0)

        override suspend fun create(kind: String, name: String, meta: JsonObject?): MemoryCopy.Created {
            created += Triple(kind, name, meta)
            return made
        }

        override suspend fun medium(made: MemoryCopy.Created.Made, medium: MemoryCopy.Medium) {
            if (failAt == media.size) throw IllegalStateException("quota_exceeded")
            media += medium
            read += medium.blobs.map { it.bytes() }
        }

        override suspend fun cover(made: MemoryCopy.Created.Made, jpeg: ByteArray, crop: NodeCover.Crop) { cover = jpeg to crop }

        override suspend fun banner(made: MemoryCopy.Created.Made, prepared: BannerWrite.Prepared, frame: Banner.Frame, focal: Banner.Focal) {
            banner = Triple(prepared, frame, focal)
        }

        override suspend fun trash(made: MemoryCopy.Created.Made) { trashed += made.nodeId }
    }

    /** An outgoing blob read as the upload reads it: a frame at a time, each read no longer than a frame. */
    private suspend fun ApplicationBlobs.Outgoing.bytes(): String {
        val out = StringBuilder()
        var at = 0L
        while (at < size) {
            val n = minOf(size - at, V1.FRAME_PLAINTEXT.toLong()).toInt()
            out.append(read(at, n).decodeToString())
            at += n
        }
        return out.toString()
    }

    private suspend fun open(f: MemoryExportTest.Fixture): TenantSession {
        f.t.remember()
        val account = f.t.resume()
        return account.open(account.tenants.first())
    }

    @Test
    fun aCopyHandsOnEveryOriginalAndTierByFrame_theListedTierVerbatim_theCoverAndTheBanner() = runTest {
        val f = MemoryExportTest.fixture()
        val tenant = open(f)
        val target = Played()
        val copied = MemoryCopy.duplicate(tenant, assertNotNull(tenant.node(f.s.id, f.memory.id)), "Lisbon (2)", target)
        assertEquals(target.made.nodeId, copied.nodeId)

        val (kind, name, birth) = target.created.single()
        assertEquals("MEMORY" to "Lisbon (2)", kind to name)
        assertEquals(
            """{"v":1,"date":{"mode":"range","from":"2025-09-12","to":"2025-09-14"},"location":{"name":"Lisboa","nameSource":"manual","derivedMode":"fixed"}}""",
            birth.toString(), "born with the date and the place; who was there is not carried",
        )

        assertEquals(2, target.media.size)
        val ai = target.media.indexOfFirst { it.blobs.first().name == "IMG_0001.jpg" }
        val a = target.media[ai]
        assertEquals(listOf(MemoryMedia.ORIGINAL, MemoryMedia.THUMBNAIL, MemoryMedia.PREVIEW), a.blobs.map { it.role }, "the original and every tier it has: three uploads")
        val original = a.blobs[0]
        assertEquals(Triple("image/jpeg", 1_600_000_000_000L, "original a frame 0"), Triple(original.type, original.modifiedAt, target.read[ai][0]))
        assertEquals("thumbnail a", target.read[ai][1], "a tier as it is stored, never derived again")
        assertEquals("preview a frame 0", target.read[ai][2])
        assertEquals(
            """{"v":1,"w":4032,"h":3024,"capturedAt":1757660000000,"capturedAtOverride":1757670000000,"gpsLat":38.7,"gpsLng":-9.1,"placeName":"Alfama"}""",
            a.tier.toString(), "the listed tier verbatim: the correction, the coordinates, the place name",
        )
        assertEquals("""{"make":"Fujifilm","model":"X100V"}""", a.exif.toString())
        val b = target.media.single { it.blobs.first().name == "IMG_0002.jpg" }
        assertEquals(listOf(MemoryMedia.ORIGINAL, MemoryMedia.THUMBNAIL), b.blobs.map { it.role })
        assertNull(b.tier)
        assertNull(b.exif)

        val (jpeg, crop) = assertNotNull(target.cover)
        assertEquals("cover cover" to NodeCover.Crop.FULL, jpeg.decodeToString() to crop)
        val (prepared, frame, focal) = assertNotNull(target.banner)
        assertEquals(Banner.Medium.IMAGE, prepared.medium)
        assertEquals("banner banner frame 0", (prepared.main as BannerWrite.Part.Upload).let { p -> p.read(0, p.size.toInt()).decodeToString() })
        assertEquals(Banner.Frame.FIT to Banner.CENTRE, frame to focal)
        assertTrue(target.trashed.isEmpty())
    }

    @Test
    fun aChapterCopiesItsOwnMedia() = runTest {
        val f = MemoryExportTest.fixture()
        val tenant = open(f)
        val target = Played()
        MemoryCopy.duplicate(tenant, assertNotNull(tenant.node(f.s.id, f.chapter.id)), "Day one", target)
        assertEquals("CHAPTER", target.created.single().first)
        assertEquals(listOf("IMG_0003.jpg"), target.media.map { it.blobs.first().name })
        assertEquals(null, target.created.single().third, "a chapter without facts is born without a meta")
    }

    @Test
    fun aFailurePartWayTrashesWhatWasMade_nothingHalfCopiedStays() = runTest {
        val f = MemoryExportTest.fixture()
        val tenant = open(f)
        val target = Played().also { it.failAt = 1 }
        val copied = MemoryCopy.duplicate(tenant, assertNotNull(tenant.node(f.s.id, f.memory.id)), "Lisbon (2)", target)
        assertNull(copied.nodeId)
        assertEquals(listOf(target.made.nodeId), target.trashed)
        assertNull(target.cover, "nothing written after the failure")
    }
}
