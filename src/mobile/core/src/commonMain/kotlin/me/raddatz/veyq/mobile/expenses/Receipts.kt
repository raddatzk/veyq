package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.Sniff

/**
 * An entry's receipts (ADR 0108 decision 7, docs/apps/expenses.md § Receipts), a port of
 * `src/web/src/features/expenses/receipts.ts` and `domain/receiptRules.ts`: each receipt is one application blob of the
 * Pot in role `EXPENSES_RECEIPT` (its key wrapped under the Pot's key, bound to the Pot), completed for the entry (its
 * `ownerId`); the entry's revision lists the receipts it keeps and names the ones it drops beside it; a dropped receipt
 * is never held again.
 */
object Receipts {
    const val ROLE = "EXPENSES_RECEIPT"
    /** The largest receipt a person adds (`RECEIPT_MAX_BYTES`): the server's sealed cap is this plus the frames' overhead. */
    const val MAX_BYTES = 10_485_760L
    /** Receipts an entry holds at most (the format's `receipts`, the server's `too_many_blobs`). */
    const val MAX_PER_ENTRY = 100

    private val EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif")

    /** Whether a picked file claims to be a receipt (`claimsReceipt`): an image or a PDF by its type, else by its name's
     *  extension. Only an offer: what a receipt IS, the bytes decide ([kindOf]). */
    fun claims(type: String?, name: String?): Boolean {
        val t = type.orEmpty().lowercase()
        if (t.isNotEmpty()) return t.startsWith("image/") || t == "application/pdf"
        val ext = Regex("\\.([a-z0-9]+)$", RegexOption.IGNORE_CASE).find(name.orEmpty())?.groupValues?.get(1)?.lowercase() ?: return false
        return ext in EXTENSIONS
    }

    /** One receipt of an entry: what the listing says, and its sealed name and type where they opened. */
    class Receipt(
        val receiptId: String, val entryId: String, internal val listed: ApplicationBlobs.Listed,
        val name: String?, val type: String?, val size: Long, val createdAt: Long, val dropped: Boolean, val blocked: Boolean,
    )

    /** How a receipt is shown, by its decrypted bytes alone (`receiptView`): an image, a PDF, or a download only. */
    enum class Kind { IMAGE, PDF, OTHER }

    fun kindOf(bytes: ByteArray): Kind {
        val head = if (bytes.size > 4096) bytes.copyOfRange(0, 4096) else bytes
        return when {
            Sniff.image(head) != null -> Kind.IMAGE
            Sniff.pdf(head) -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    /**
     * The receipts a revision of [entryId] drops (`receiptsToDrop`): every one held for it and not [kept] that the revision
     * may answer for — one the revision it was made [on] listed, one this writer added itself ([own]), or one completed
     * before that revision was stored. A receipt another writer completed after [on] is left alone: it may be on its way
     * into that writer's revision.
     */
    fun toDrop(listing: List<Receipt>, entryId: String, kept: List<String>, on: Pair<List<String>, Long>?, own: Set<String>): List<String> {
        val keep = kept.toSet()
        val before = on?.first.orEmpty().toSet()
        return listing
            .filter { it.entryId == entryId && !it.dropped && it.receiptId !in keep }
            .filter { it.receiptId in before || it.receiptId in own || (on != null && it.createdAt < on.second) }
            .map { it.receiptId }
    }

    /** A download's name (core's `downloadName`): the sealed name (trimmed), else "receipt", gaining the extension its sealed
     *  type names only where it has none of its own. */
    fun downloadName(name: String?, type: String?): String {
        val base = name?.trim()?.takeIf { it.isNotEmpty() } ?: "receipt"
        val ext = MIME_TO_EXT[type.orEmpty()] ?: return base
        return if (Regex("[^.]\\.[^.\\s]{1,16}$").containsMatchIn(base)) base else "$base.$ext"
    }

    /** Core's `MIME_TO_EXT`, exactly: a type it does not name adds nothing. */
    private val MIME_TO_EXT = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic",
        "application/pdf" to "pdf", "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/x-matroska" to "mkv",
    )
}
