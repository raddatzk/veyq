package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A cover framed in the app (the web's crop), on a running dev instance (`VEYQ_LIVE`), with `VEYQ_LIVE_COVER_CROP=<pot name>`:
 * a drawn 16:9 JPEG — its left half red, its right half blue with a white disc — set framed on its left half, read back framed
 * so by a fresh session, then framed on its right half by a crop-only write that keeps the blob. With `VEYQ_LIVE_COVER_KEEP`
 * it stays, for the web's header and rows (the blue half with the disc), else it is removed again.
 */
class CoverCropLiveTest {
    @Test
    fun aCoverIsFramedAndReframedWithItsPictureKept() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val potName = System.getenv("VEYQ_LIVE_COVER_CROP") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val ref = tenant.nodes().first { it.kind == PotSession.KIND && it.name == potName }
        val image = java.awt.image.BufferedImage(640, 360, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = java.awt.Color(0xdc, 0x26, 0x26); fillRect(0, 0, 320, 360)
                color = java.awt.Color(0x25, 0x63, 0xeb); fillRect(320, 0, 320, 360)
                color = java.awt.Color.WHITE; fillOval(420, 100, 120, 120)
                dispose()
            }
        }
        val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        suspend fun stored(): NodeCover.Read {
            val fresh = account.open(tenant.tenant)
            val node = fresh.node(ref.scopeId, ref.nodeId)!!
            return NodeCover.read(NodeMetaWrite.openForWrite(fresh.account.suite, ref.scopeId, ref.nodeId, node.key, node.wrappedMeta))!!
        }
        // the left half, 16:9 (320 × 180 of 640 × 360), framed by the app's cropper math
        val frame = me.raddatz.veyq.mobile.session.CoverFrame(640.0, 360.0, 320.0, 180.0)
        val left = frame.crop(frame.pan(frame.zoom(frame.centred(), 2.0), 10_000.0, 0.0))
        assertEquals(NodeCover.Crop(0.0, 0.25, 0.5, 0.5), left)
        assertEquals(NodeCover.Outcome.DONE, NodeCover.set(tenant, ref.scopeId, ref.nodeId, jpeg, left))
        val first = stored()
        assertEquals(left, first.crop, "a fresh session reads another crop")
        // the right half: only the crop moves, the picture stays
        val right = NodeCover.Crop(0.5, 0.25, 0.5, 0.5)
        assertEquals(NodeCover.Outcome.DONE, NodeCover.recrop(tenant, ref.scopeId, ref.nodeId, right))
        val second = stored()
        assertEquals(right, second.crop, "the recrop did not land")
        assertEquals(first.blobId, second.blobId, "a recrop must keep the cover's picture")
        val opened = NodeCover.open(tenant.also { it.forgetTree() }, tenant.node(ref.scopeId, ref.nodeId)!!)
        assertTrue(opened != null && opened.contentEquals(jpeg), "the cover does not open to the bytes set")
        val keep = System.getenv("VEYQ_LIVE_COVER_KEEP") != null
        if (!keep) assertEquals(NodeCover.Outcome.DONE, NodeCover.remove(tenant, ref.scopeId, ref.nodeId))
        println("[$potName] a cover framed on its left half, read back so, re-framed on its right half with its blob kept; ${if (keep) "kept" else "removed"}")
    }
}
