package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.GregorianCalendar
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.test.AfterTest
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * A member's upload into a Memory on a running dev instance (`VEYQ_LIVE`): `VEYQ_LIVE_MEMORY_UPLOAD=<memory name>` (made in the
 * default space where none of that name exists, a throwaway's pass) uploads
 * the web's EXIF fixture photo (`exif-sample.jpg`) with a preview and a thumbnail drawn here, and the web's dated video
 * fixture (`dated.mp4`) with a drawn poster as both tiers, then reads the gallery in a fresh session: each medium listed,
 * its capture time and its source, the photo's position and camera, each original back to the byte, each tier a JPEG.
 * Both stay, for the web's gallery and lightbox to show.
 */
class MemoryUploadLiveTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(MemoriesKinds) }

    /** Every device this case signs in with, taken off the account again after it, whatever it came to. */
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun aPhotoAndAVideoAreUploadedAsTheWebUploadsThem() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val name = System.getenv("VEYQ_LIVE_MEMORY_UPLOAD") ?: return@runTest
        val root = File(System.getProperty("veyq.repoRoot"))
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(root, ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        suspend fun signIn() = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            liveRun.device(),
        ) { block -> LiveHttp.client(block) }.let { it.open(it.defaultTenant!!) }
        val tenant = signIn()
        // a throwaway's pass has none yet: made in the account's default space where no Memory of that name exists
        val memory = tenant.nodes().firstOrNull { it.kind == "MEMORY" && it.name == name } ?: run {
            val shell = me.raddatz.veyq.mobile.navigation.ShellModel(
                tenant, me.raddatz.veyq.mobile.navigation.Hidden({}, emptySet()),
            ) { System.currentTimeMillis() }
            shell.load(openLastUsed = false)
            val scopeId = assertNotNull(me.raddatz.veyq.mobile.navigation.Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
            assertNotNull(MemoryCreate.create(tenant, scopeId, null, MemoryCreate.MEMORY, name).node, "the Memory was not made")
                .let { made -> tenant.forgetTree(); tenant.nodes().first { it.nodeId == made.nodeId } }
        }
        val now = System.currentTimeMillis()

        val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
        val photoUp = MediaUpload.upload(
            tenant, memory.scopeId, memory.nodeId,
            MediaUpload.Original(source(photo), "image/jpeg", "App upload.jpg", 1_000_000),
            Derivation.Derived(jpeg(2048, 1536, Color(0x2E7D32), 0.85f), jpeg(512, 384, Color(0x2E7D32), 0.8f), 4032, 3024),
            now,
        )
        assertEquals(MediaUpload.Outcome.DONE, photoUp.outcome, photoUp.code)
        val video = File(root, "src/web/src/shared/image/__fixtures__/dated.mp4").readBytes()
        val poster = jpeg(64, 36, Color(0x1565C0), 0.85f)
        val videoUp = MediaUpload.upload(
            tenant, memory.scopeId, memory.nodeId,
            MediaUpload.Original(source(video), null, "App clip.mp4", 2_000_000),
            Derivation.Derived(poster, poster, null, null),
            now,
        )
        assertEquals(MediaUpload.Outcome.DONE, videoUp.outcome, videoUp.code)

        val fresh = signIn()
        val gallery = MemoryMedia.list(fresh, memory.scopeId, memory.nodeId)!!
        val p = assertNotNull(gallery.items.firstOrNull { it.mediaId == photoUp.mediaId }, "the photo is listed")
        assertEquals(GregorianCalendar(2021, 6, 15, 18, 4, 5).timeInMillis, p.meta?.capturedAt)
        assertEquals("exif", p.meta?.capturedAtSource)
        assertEquals(48.136944444444445, p.meta?.gpsLat)
        assertEquals(4032 to 3024, p.meta?.width to p.meta?.height)
        assertEquals("App upload.jpg" to "image/jpeg", p.name to p.type)
        assertTrue(MemoryMedia.read(fresh, p, p.original)!!.contentEquals(photo), "the photo's original to the byte")
        assertEquals("Canon EOS R", MediaDetails.rows(p, MediaDetails.exif(fresh, p)).single { it.field == MediaDetails.Field.CAMERA }.value)
        for (tier in listOf(p.thumbnail!!, p.preview!!)) assertEquals(0xD8.toByte(), MemoryMedia.read(fresh, p, tier)!![1])

        val v = assertNotNull(gallery.items.firstOrNull { it.mediaId == videoUp.mediaId }, "the video is listed")
        assertEquals(java.time.Instant.parse("2024-05-12T12:03:21Z").toEpochMilli(), v.meta?.capturedAt)
        assertEquals("container", v.meta?.capturedAtSource)
        assertEquals(64 to 36, v.meta?.width to v.meta?.height, "the size its header names")
        assertEquals("video/mp4", v.type, "sniffed: no type was given")
        assertTrue(v.isVideo)
        assertTrue(MemoryMedia.read(fresh, v, v.original)!!.contentEquals(video), "the video's original to the byte")
        println("[$name] uploaded photo ${p.mediaId} and video ${v.mediaId}")
    }

    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    /** A JPEG of one colour with a white frame, as a platform's derivation hands one over. */
    private fun jpeg(w: Int, h: Int, colour: Color, quality: Float): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = colour
            fillRect(0, 0, w, h)
            color = Color.WHITE
            fillRect(w / 4, h / 4, w / 2, h / 2)
            dispose()
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(image, null, null), writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        })
        writer.dispose()
        return out.toByteArray()
    }
}
