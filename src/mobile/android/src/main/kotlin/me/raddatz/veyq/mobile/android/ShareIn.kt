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
 * What another app shared to veyq (ADR 0129 decision 8): `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of images and PDFs, read
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
        val read = uris.map { read(context, it) }
        val (fit, refused) = PotShare.split(read.filterNotNull(), { it.type }, { it.name }, { it.size })
        return me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming(fit, refused.size + read.count { it == null })
    }

    /** One shared stream read whole (one larger than a receipt may be is not read), or null when it does not open. */
    private fun read(context: Context, uri: Uri): PotSession.PendingReceipt? = runCatching {
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
        if (size > HEIC_READ_LIMIT) return@runCatching null
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        if (type == "image/heic" || type == "image/heif") {
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            } ?: return@runCatching null
            return@runCatching PotSession.PendingReceipt(java.util.UUID.randomUUID().toString(), jpeg, "image/jpeg", name?.substringBeforeLast('.')?.plus(".jpg"), null)
        }
        PotSession.PendingReceipt(java.util.UUID.randomUUID().toString(), bytes, type, name, null)
    }.onFailure { android.util.Log.w("veyq-share", "a shared stream did not read: ${it.javaClass.simpleName}") }.getOrNull()

    /** A HEIC may be larger than the JPEG it becomes; anything past this is not read at all. */
    private const val HEIC_READ_LIMIT = 4 * 10_485_760L
}
