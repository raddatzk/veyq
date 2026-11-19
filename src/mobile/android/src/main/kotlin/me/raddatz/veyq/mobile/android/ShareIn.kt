package me.raddatz.veyq.mobile.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.navigation.ShareInbox

/**
 * What another app shared to veyq (ADR 0138 decision 8) — `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of any type — or
 * dropped onto it (decision 9), as sources read a frame at a time where the system put them (`ContentSource`):
 * the share's read grant is the receiving activity's and outlives its leaving for the upload's worker (measured, ADR 0138's
 * Consequences). Nothing is read whole here; the kind chosen judges the files (core's `ShareAnswer`). A share that carries
 * no file (a text, a link: `EXTRA_TEXT` only) waits with none, so the pick mode says nothing could be taken (decision 1,
 * until a shared link has a meaning, inbox #1015).
 *
 * A HEIC photo is taken as a JPEG (decision 7), so a receipt and a photo read in every browser: the one file the app
 * writes, its copy under `no_backup/share-in/` ([dir]), removed once nothing reads it again ([release]) and swept at
 * start ([sweep]).
 */
object ShareIn {
    fun from(context: Context, intent: Intent?): ShareInbox.Incoming? {
        if (intent == null) return null
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        // a text or a link shared, no stream: nothing to take, said so
        if (uris.isEmpty()) return ShareInbox.Incoming(emptyList(), 0)
        return of(context, uris)
    }

    /** The files of a drop's clip ([clip]): each item's URI, as a share's. */
    fun fromClip(context: Context, clip: ClipData?): ShareInbox.Incoming? =
        clip?.let { c -> of(context, (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }) }

    /** [uris] as the files that wait, in order; one that does not open counts as not read. */
    fun of(context: Context, uris: List<Uri>): ShareInbox.Incoming? {
        if (uris.isEmpty()) return null
        var refused = 0
        val files = uris.mapNotNull { uri -> source(context, uri).also { if (it == null) refused++ } }
        return ShareInbox.Incoming(files, refused)
    }

    private fun source(context: Context, uri: Uri): UploadSource? = runCatching {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        // a provider that says no size: its descriptor's (a stream with neither is not taken)
        if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        if (size < 0) return@runCatching null
        val type = resolver.getType(uri).orEmpty()
        val shown = name ?: uri.lastPathSegment ?: "shared"
        if (type == "image/heic" || type == "image/heif") return@runCatching jpeg(context, uri, shown)
        AppModel.ContentSource(resolver, uri, shown, size, type, null)
    }.onFailure { android.util.Log.w("veyq-share", "a shared stream did not read: ${it.javaClass.simpleName}") }.getOrNull()

    /** A HEIC photo re-encoded as a JPEG into the app's one copy of it; null when it does not decode. */
    private fun jpeg(context: Context, uri: Uri, name: String): UploadSource? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val file = java.io.File(dir(context).apply { mkdirs() }, java.util.UUID.randomUUID().toString())
        try {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: java.io.IOException) {
            file.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        return Copy(file, name.substringBeforeLast('.') + ".jpg")
    }

    /** The app's copy of a shared file: read a frame at a time, deleted when [release]d. */
    class Copy(val file: java.io.File, name: String) : UploadSource(name, file.length(), "image/jpeg", null) {
        private var open: java.io.RandomAccessFile? = null

        override suspend fun read(from: Long, length: Int): ByteArray = synchronized(this) {
            val f = open ?: java.io.RandomAccessFile(file, "r").also { open = it }
            val buf = ByteArray(minOf(length.toLong(), (size - from).coerceAtLeast(0)).toInt())
            f.seek(from)
            f.readFully(buf)
            buf
        }

        @Synchronized
        override fun close() {
            runCatching { open?.close() }
            open = null
        }
    }

    /** Where the app's copies of shared files live (`no_backup/share-in/`). */
    fun dir(context: Context) = java.io.File(context.noBackupFilesDir, "share-in")

    /** A shared file nothing reads again: closed, and the app's copy of it removed. */
    fun release(source: UploadSource) {
        source.close()
        if (source is Copy) source.file.delete()
    }

    /** At start, before any share is read: what an earlier process left (a share it never placed) is removed. */
    fun sweep(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
