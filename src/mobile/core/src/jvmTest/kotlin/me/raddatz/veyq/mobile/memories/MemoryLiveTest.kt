package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A Memory the web made and filled, read on a running dev instance (`VEYQ_LIVE`): `VEYQ_LIVE_MEMORY=<memory name>` lists
 * its gallery (a guest's rows through the drop key's `K_up` too), opens every row's listed tier and original meta, and
 * reads each grid tile and preview, checking each is the JPEG the web derived (or, for a medium without tiers, its
 * original); each tile's size and SHA-256 are printed, to hold against the web's. Each row's details are read with its
 * heavy EXIF tier and printed; each original is read whole and through the range reader in uneven pieces from the end
 * back, and the two must be the same bytes (a video's playback reads it so).
 */
class MemoryLiveTest {
    @Test
    fun aMemoryGalleryIsRead() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val name = System.getenv("VEYQ_LIVE_MEMORY") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        if (name == "*") {
            for (m in tenant.nodes().filter { it.kind == "MEMORY" || it.kind == "CHAPTER" }) {
                println("[*] ${m.kind} ${m.name}: ${MemoryMedia.list(tenant, m.scopeId, m.nodeId)?.items?.size} media")
            }
            return@runTest
        }
        val memory = tenant.nodes().first { it.kind == "MEMORY" && it.name == name }
        val gallery = MemoryMedia.list(tenant, memory.scopeId, memory.nodeId)!!
        println("[$name] ${gallery.items.size} media (revision ${gallery.mediaRevision})")
        assertTrue(gallery.items.isNotEmpty(), "the Memory holds no media to prove anything with")
        for (item in gallery.items) {
            val m = item.meta
            val grid = item.gridBlob?.let { MemoryMedia.read(tenant, item, it) }
            val preview = MemoryMedia.read(tenant, item, item.lightboxBlob)
            if (item.thumbnail != null) {
                assertTrue(grid != null && grid.size > 2 && grid[0] == 0xFF.toByte() && grid[1] == 0xD8.toByte(), "${item.mediaId}'s tile is no JPEG")
                assertTrue(preview != null && preview[0] == 0xFF.toByte(), "${item.mediaId}'s preview is no JPEG")
            }
            val sha = grid?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }.take(16) }
            println(
                "    ${item.name} (${item.type}, ${item.original.size} B, ${item.ownerKey}): ${m?.width}x${m?.height}, " +
                    "at ${m?.effectiveCapturedAt} (${m?.capturedAtSource}), gps ${m?.gpsLat},${m?.gpsLng} ${m?.placeName ?: ""}; " +
                    "tile ${grid?.size} B $sha, preview ${preview?.size} B",
            )
            val exif = MediaDetails.exif(tenant, item)
            println("      details: " + MediaDetails.rows(item, exif).joinToString("; ") { "${it.field}=${it.value.ifEmpty { it.number.toString() }}" })
            val whole = MemoryMedia.read(tenant, item, item.original)!!
            val reader = MemoryMedia.reader(tenant, item, item.original)!!
            assertTrue(reader.size == whole.size.toLong(), "${item.mediaId}: the reader's size is not the original's")
            val pieced = ByteArray(whole.size)
            var end = whole.size
            var step = 70_001
            while (end > 0) {
                val from = maxOf(0, end - step)
                reader.read(from.toLong(), end - from).copyInto(pieced, from)
                end = from
                step = step * 3 / 2
            }
            assertTrue(pieced.contentEquals(whole), "${item.mediaId}: the range reader's bytes are not the original's")
            println("      download as ${MediaDetails.downloadName(item, name, gallery.items.indexOf(item))}, ranges read back to the byte")
        }
    }
}
