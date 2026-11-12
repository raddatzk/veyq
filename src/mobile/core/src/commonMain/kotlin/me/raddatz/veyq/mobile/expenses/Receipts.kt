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
    /** How much of a picked file a platform reads at most: one byte past [MAX_BYTES], so the editor refuses a larger one
     *  ([EntryEditor.addReceipts]) without the device reading it whole. */
    const val READ_LIMIT = MAX_BYTES + 1
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
        /** The modified time its sealed meta carries (ms), which a download keeps. */
        val modifiedAt: Long? = null,
    )

    /** How a receipt is shown, by its decrypted bytes alone (`receiptView`): an image, a PDF, or a download only. */
    enum class Kind { IMAGE, PDF, OTHER }

    /**
     * How many of a PDF's pages a receipt's viewer shows at least (Android's cap made the core's, D8): a viewer that pages
     * lazily (Apple's PDFKit) may show them all; one that renders each page up front stops here and says so ([pagesCut]).
     */
    const val PDF_PAGES_SHOWN = 20

    /** The pages of a PDF of [total] a viewer renders up front: at most [PDF_PAGES_SHOWN]. */
    fun pagesShown(total: Int): Int = total.coerceIn(0, PDF_PAGES_SHOWN)

    /** What a viewer that stopped at [PDF_PAGES_SHOWN] says under a PDF of [total] pages; null when it showed them all. */
    fun pagesCut(total: Int): me.raddatz.veyq.mobile.words.Notice? =
        if (total > PDF_PAGES_SHOWN) me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.RECEIPTS_PAGES_CUT, PDF_PAGES_SHOWN, total) else null

    /**
     * What a receipt's bytes are as a media type (`image/jpeg`, `application/pdf`), read from the bytes alone — never from
     * its sealed type, a peer's statement — so a share sheet offers it as what it is; null for any other bytes.
     */
    fun mediaTypeOf(bytes: ByteArray): String? {
        val head = if (bytes.size > 4096) bytes.copyOfRange(0, 4096) else bytes
        return Sniff.image(head)?.mime ?: if (Sniff.pdf(head)) "application/pdf" else null
    }

    /**
     * A file picked by its [path] (a desktop's open dialog, ADR 0128 decision 3) as the editor takes it: [bytes] what the
     * device read of it (null: it did not open, or held more than [MAX_BYTES]), named by the path's last part (`/` or `\`),
     * its type what the bytes are ([mediaTypeOf]) — no platform names one there, so bytes that are no image and no PDF are
     * refused ([claims] by type).
     */
    fun picked(path: String, bytes: ByteArray?): EntryEditor.Picked {
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { null }
        return EntryEditor.Picked(bytes, bytes?.let(::mediaTypeOf) ?: "application/octet-stream", name)
    }

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
    fun downloadName(name: String?, type: String?): String = me.raddatz.veyq.mobile.blob.DownloadName.of(type, "receipt", name)
}
