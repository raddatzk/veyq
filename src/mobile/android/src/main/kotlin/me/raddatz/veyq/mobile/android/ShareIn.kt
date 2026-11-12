package me.raddatz.veyq.mobile.android

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotShare
import java.io.ByteArrayOutputStream

/**
 * What another app shared to veyq (ADR 0131 decision 8): `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of images and PDFs, read
 * at once — the permission to read a shared stream is the receiving activity's and does not outlast it — into the files a
 * Pot takes and those it refuses. A HEIC photo is taken as a JPEG (decision 7), so the receipt reads in every browser.
 */
object ShareIn {
    fun from(context: Context, intent: Intent?): me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming? {
        if (intent == null) return null
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        if (uris.isEmpty()) return null
        // one rule on every app (core's `PotShare.Intake`): each a receipt, at most its count, all within one budget
        val intake = PotShare.Intake()
        var refused = 0
        val fit = uris.mapNotNull { uri ->
            read(context, uri, intake.room)?.takeIf { intake.take(it.type, it.name, it.size) }.also { if (it == null) refused++ }
        }
        return me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming(fit, refused)
    }

    /**
     * One shared stream read whole, or null when it does not open or is larger than [room] (what is left of the share's
     * budget: a HEIC larger than its JPEG included, so nothing beyond it is read at all).
     */
    private fun read(context: Context, uri: Uri, room: Long): PotSession.PendingReceipt? = runCatching {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val type = resolver.getType(uri).orEmpty()
        if (size > room) return@runCatching null
        val bytes = resolver.openInputStream(uri)?.use { bounded(it, room) } ?: return@runCatching null
        if (type == "image/heic" || type == "image/heif") {
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            } ?: return@runCatching null
            return@runCatching PotSession.PendingReceipt(java.util.UUID.randomUUID().toString(), jpeg, "image/jpeg", name?.substringBeforeLast('.')?.plus(".jpg"), null)
        }
        PotSession.PendingReceipt(java.util.UUID.randomUUID().toString(), bytes, type, name, null)
    }.onFailure { android.util.Log.w("veyq-share", "a shared stream did not read: ${it.javaClass.simpleName}") }.getOrNull()

    /** At most [limit] bytes of [input]; null when it holds more (a size the provider did not say). */
    private fun bounded(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
    }
}
