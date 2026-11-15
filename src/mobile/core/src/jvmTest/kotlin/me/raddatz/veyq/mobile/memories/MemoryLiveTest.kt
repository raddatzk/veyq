package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.test.AfterTest
import me.raddatz.veyq.mobile.session.LiveRun

/**
 * A Memory the web made and filled, read on a running dev instance (`VEYQ_LIVE`): `VEYQ_LIVE_MEMORY=<memory name>` lists
 * its gallery (a guest's rows through the drop key's `K_up` too), opens every row's listed tier and original meta, and
 * reads each grid tile and preview, checking each is the JPEG the web derived (or, for a medium without tiers, its
 * original); each tile's size and SHA-256 are printed, to hold against the web's. Each row's details are read with its
 * heavy EXIF tier and printed; each original is read whole and through the range reader in uneven pieces from the end
 * back, and the two must be the same bytes (a video's playback reads it so).
 */
class MemoryLiveTest {
    /** Every device this case signs in with, taken off the account again after it, whatever it came to. */
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    /**
     * The same Memory through the screen models both phones draw (`GalleryModel`, `Lightbox`; ADR 0136's Consequences):
     * `VEYQ_LIVE_MEMORY=<memory name>` opens its gallery over a shell, reads every tile and picture the rows name, every
     * medium's details (the uploaders asked once), a video's original through the lightbox's reader across a frame's edge,
     * and one original saved to a temporary file whose SHA-256 is the original's read whole; the reader closes when the
     * lightbox pages away. Reads only. Reported skipped without `VEYQ_LIVE`.
     */
    @Test
    fun aMemoryIsReadThroughItsScreenModels() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE")
        val name = System.getenv("VEYQ_LIVE_MEMORY")
        org.junit.Assume.assumeTrue("VEYQ_LIVE and VEYQ_LIVE_MEMORY are not set: a live case", origin != null && name != null)
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            liveRun.device(),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val shell = me.raddatz.veyq.mobile.navigation.ShellModel(
            tenant, me.raddatz.veyq.mobile.navigation.Hidden({}, emptySet()), me.raddatz.veyq.mobile.navigation.RecentUses({}, emptyList()),
        ) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val memory = tenant.nodes().first { it.kind == "MEMORY" && it.name == name }
        val queue = me.raddatz.veyq.mobile.blob.UploadQueue()
        val uploads = shell.mediaUploads(queue, object : MediaUpload.Deriver {
            override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) =
                done(MediaUpload.Derived(null, null, null, null))
        }, left = {}) {}
        val gallery = shell.galleryModel(me.raddatz.veyq.mobile.navigation.NodeAt(memory.scopeId, memory.nodeId, memory.kind, memory.name), uploads)
        val rows = gallery.state.first { it.rows != null || it.failed }.rows // real time: runTest's own timeout bounds it
        assertTrue(!rows.isNullOrEmpty(), "the Memory holds no media to prove anything with")
        println("[$name] ${rows.size} media, ${gallery.current.chapters.size} chapters through GalleryModel")
        val box = gallery.lightbox(0)
        for ((i, row) in rows.withIndex()) {
            val tile = row.tile?.let { gallery.tile(row.mediaId) }
            assertTrue(row.tile == null || tile != null, "${row.mediaId}: its tile did not read")
            val picture = row.picture?.let { box.picture(row.mediaId) }
            assertTrue(row.picture == null || picture != null, "${row.mediaId}: its picture did not read")
            val details = box.details(row.mediaId)!!
            println("    ${row.name}: tile ${tile?.size} B, picture ${picture?.size} B, ${details.rows.size} detail rows, by ${details.uploader?.kind}")
            if (row.isVideo && !row.blocked) {
                box.go(i)
                val reader = box.play()!!
                val edge = me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT.toLong()
                val across = reader.read(maxOf(0, minOf(reader.size, edge) - 100), 200)
                assertTrue(across.isNotEmpty(), "${row.mediaId}: no range across a frame's edge")
                box.go(if (i == 0) 1.coerceAtMost(rows.size - 1) else 0)
                assertTrue(rows.size == 1 || reader.isClosed, "${row.mediaId}: paged away, its reader still open")
            }
        }
        box.go(0)
        val saved = File.createTempFile("veyq-memory-live", ".bin")
        try {
            val save = box.save { produce -> saved.outputStream().use { out -> produce { out.write(it) } } }!!
            val ended = save.state.first { it.end != null }
            assertTrue(ended.end == me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, "the original was not saved: ${ended.end}")
            val whole = MemoryMedia.read(tenant, rows[0].item, rows[0].item.original)!!
            fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
            assertTrue(sha(saved.readBytes()) == sha(whole), "the saved original is not the original")
            println("    saved ${save.name}: ${saved.length()} B at the original's SHA-256 ${sha(whole).take(16)}")
        } finally {
            saved.delete()
            gallery.close()
            uploads.close()
        }
    }

    @Test
    fun aMemoryGalleryIsRead() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val name = System.getenv("VEYQ_LIVE_MEMORY") ?: return@runTest
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            liveRun.device(),
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
