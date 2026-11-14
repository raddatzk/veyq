package me.raddatz.veyq.mobile.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Debug builds only — a Memory's page (`MemoryGallery`, the CR's phase 1: core's `GalleryModel` and `Lightbox`) drawn over a
 * played gallery, reached without a server or a sign-in: launched with the extra `veyq.devMemory=1`, the app shows a Memory
 * "Dev memory" with two chapters, three photos drawn here (one with a camera and a place), a blocked medium, and — where
 * `files/veyq-memory.mp4` was pushed into the app's external files directory — that video, played through core's range
 * reader. Every read the page makes through the model is logged to logcat (`veyq-memory`: each tile and picture, each
 * reader opened, each EXIF and uploaders read), so a pass reads what the core asked for before it looks. A release build has
 * no such door.
 */
object DevMemory {
    fun requested(intent: Intent?): Boolean = intent?.extras?.getString("veyq.devMemory") == "1"

    private fun log(line: String) {
        Log.i("veyq-memory", line)
    }

    private fun jpeg(colour: Int, label: String, w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colour)
        canvas.drawText(label, w * 0.1f, h * 0.55f, Paint().apply { color = Color.WHITE; textSize = h / 6f; isAntiAlias = true })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun blob(id: String, size: Long) =
        MemoryMedia.Blob(id, ((size + V1.FRAME_PLAINTEXT - 1) / V1.FRAME_PLAINTEXT).toInt().coerceAtLeast(1), size + V1.FRAME_OVERHEAD * ((size + V1.FRAME_PLAINTEXT - 1) / V1.FRAME_PLAINTEXT).coerceAtLeast(1), "", null)

    private class Played(val items: List<MemoryMedia.Item>, val bytes: Map<String, ByteArray>, val video: File?)

    private fun played(video: File?): Played {
        val bytes = HashMap<String, ByteArray>()
        val items = ArrayList<MemoryMedia.Item>()
        // each its own shape, so the justified grid shows aspects kept: landscape, portrait, square
        fun photo(id: String, colour: Int, at: Long, meta: MemoryMedia.Meta? = null, name: String, owner: String, w: Int = 1600, h: Int = 1200) {
            val original = jpeg(colour, name, w, h)
            bytes["o-$id"] = original
            bytes["p-$id"] = jpeg(colour, "$name (preview)", w, h)
            bytes["t-$id"] = jpeg(colour, name, 512, 512 * h / w)
            items += MemoryMedia.Item(
                "s", "m", id, blob("o-$id", original.size.toLong()), blob("t-$id", bytes.getValue("t-$id").size.toLong()),
                blob("p-$id", bytes.getValue("p-$id").size.toLong()),
                meta ?: MemoryMedia.Meta(w, h, at, null, "exif", null, null, null, null), "image/jpeg", name, owner, false, ByteArray(0),
            )
        }
        photo(
            "beach", Color.rgb(0x1e, 0x88, 0xe5), 1_784_016_900_000,
            MemoryMedia.Meta(1600, 1200, 1_784_016_900_000, null, "exif", 54.079, 10.889, "Kühlungsborn", "city"), "beach.jpg", "user:u1",
        )
        photo("dunes", Color.rgb(0xf9, 0xa8, 0x25), 1_784_000_000_000, name = "dunes.jpg", owner = "share:g1")
        photo("pier", Color.rgb(0x43, 0xa0, 0x47), 1_783_900_000_000, name = "pier.jpg", owner = "user:u2", w = 1200, h = 1600)
        photo("shell", Color.rgb(0x8e, 0x24, 0xaa), 1_783_850_000_000, name = "shell.jpg", owner = "user:u1", w = 1200, h = 1200)
        items += MemoryMedia.Item(
            "s", "m", "blocked", blob("o-blocked", 100), null, null, MemoryMedia.Meta(1600, 1200, 1_783_800_000_000, null, "exif", null, null, null, null),
            "image/jpeg", "blocked.jpg", "user:u1", true, ByteArray(0),
        )
        if (video != null) {
            val poster = jpeg(Color.DKGRAY, "video", 1280, 720)
            bytes["p-clip"] = poster
            bytes["t-clip"] = poster
            items += MemoryMedia.Item(
                "s", "m", "clip", blob("o-clip", video.length()), blob("t-clip", poster.size.toLong()), blob("p-clip", poster.size.toLong()),
                MemoryMedia.Meta(1280, 720, 1_784_100_000_000, null, "container", null, null, null, null), "video/mp4", "clip.mp4", "user:u1",
                false, ByteArray(0),
            )
        }
        return Played(MemoryMedia.order(items), bytes, video)
    }

    private fun frames(file: File, first: Int, until: Int, sink: (ByteArray) -> Unit) {
        RandomAccessFile(file, "r").use { f ->
            for (i in first until until) {
                val at = i.toLong() * V1.FRAME_PLAINTEXT
                if (at >= f.length()) break
                val n = minOf(V1.FRAME_PLAINTEXT.toLong(), f.length() - at).toInt()
                val b = ByteArray(n)
                f.seek(at)
                f.readFully(b)
                sink(b)
            }
        }
    }

    private fun gallery(played: Played): GalleryModel = GalleryModel(NodeAt("s", "m", "MEMORY", "Dev memory"), object : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>()
        override suspend fun gallery(): MemoryMedia.Gallery {
            log("gallery read: ${played.items.size} media")
            return MemoryMedia.Gallery(played.items, 1, 0)
        }
        override suspend fun scopeNodes() = listOf(NodeRef("s", "c1", "m", "CHAPTER", "Day one"), NodeRef("s", "c2", "m", "CHAPTER", "Day two"))
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? {
            log("read ${blob.blobId}")
            return played.bytes[blob.blobId]
        }
        override fun reader(item: MemoryMedia.Item): RangeReader? {
            val file = played.video ?: return null
            log("reader opened for ${item.mediaId}")
            return RangeReader(file.length(), item.original.chunkCount) { first, until, sink ->
                val got = ArrayList<ByteArray>()
                frames(file, first, until) { got += it }
                got.forEach { sink(it) }
            }
        }
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
            log("save of ${item.mediaId}")
            val file = played.video
            if (item.mediaId == "clip" && file != null) {
                val got = ArrayList<ByteArray>()
                frames(file, 0, item.original.chunkCount) { got += it }
                got.forEach { sink(it) }
            } else {
                sink(played.bytes["o-${item.mediaId}"] ?: return false)
            }
            return true
        }
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? {
            log("exif read for ${item.mediaId}")
            if (item.mediaId != "beach") return null
            return MediaDetails.Exif(
                "Google", "Pixel 9", null, 6.9, 24.0, 1.7, 0.004, null, 100.0, 0.0, null, null, null, null, null, null, 3.0, null, null,
                null, 1600.0, 1200.0,
            )
        }
        override suspend fun uploaders(items: List<MemoryMedia.Item>): Map<String, MediaDetails.Uploader> {
            val keys = items.mapNotNull { it.ownerKey }.toSet()
            log("uploaders read for ${keys.sorted()}")
            return keys.associateWith {
                when (it) {
                    "user:u1" -> MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Ada")
                    "user:u2" -> MediaDetails.Uploader(MediaDetails.UploaderKind.FORMER)
                    else -> MediaDetails.Uploader(MediaDetails.UploaderKind.GUEST)
                }
            }
        }
        override fun upload(sources: List<UploadSource>): Int {
            log("upload of ${sources.map { "${it.name} (${it.size} B, ${it.type})" }}")
            return sources.size
        }
    })

    @Composable
    fun Screen(model: AppModel) {
        val context = LocalContext.current
        val gallery = remember {
            val video = context.getExternalFilesDir(null)?.let { File(it, "veyq-memory.mp4") }?.takeIf { it.isFile }
            log("video: ${video?.length() ?: "none"}")
            gallery(played(video))
        }
        Box(Modifier.fillMaxSize().systemBarsPadding()) { MemoryGallery(gallery, model) }
    }
}
