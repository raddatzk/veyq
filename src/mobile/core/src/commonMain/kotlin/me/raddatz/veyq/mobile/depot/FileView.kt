package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.V1

/**
 * How a file's page shows it (the web's `features/depot/view.ts`, `docs/apps/depot.md` § The viewer): decided by its first
 * frame's decrypted BYTES (`Sniff`); the sealed type and the name are claims that only narrow (text must be claimed). A
 * raster image, a PDF and a text are shown in the app, an audio or video container played there, and everything else is
 * only downloaded — a peer's bytes are never handed to another app or rendered as markup: an SVG is shown as its text.
 * The platform names any file it hands its own viewer by [Decision.extension], the SNIFFED type's, never the peer's name.
 */
object FileView {
    /** Largest image decrypted into memory (the web's `IMAGE_CAP`); over it, the download is offered instead. */
    const val IMAGE_CAP = 64L * 1024 * 1024
    /** Most text shown; a longer one shows its first [TEXT_CAP] bytes and a note (`TEXT_CAP`). */
    const val TEXT_CAP = 1024 * 1024
    /** Largest PDF decrypted (`PDF_CAP`). */
    const val PDF_CAP = 64L * 1024 * 1024

    enum class Kind { IMAGE, MEDIA, PDF, TEXT, TOO_LARGE, EMPTY, UNSUPPORTED }

    /**
     * What to show: its [kind], the sniffed [mime] and its [extension] (what a file handed to the platform's viewer is named
     * by), how many [frames] to read for it (all, or a text's first [TEXT_CAP] bytes), and whether a text is [truncated].
     */
    data class Decision(val kind: Kind, val mime: String? = null, val extension: String? = null, val frames: Int = 0, val truncated: Boolean = false)

    /** The decision for a file of [chunkCount] frames, [total] plaintext bytes, whose first frame is [head]. */
    fun decide(head: ByteArray, chunkCount: Int, total: Long, claimedType: String?, claimedName: String?): Decision {
        if (chunkCount < 1 || head.isEmpty()) return Decision(Kind.EMPTY)
        Sniff.image(head)?.let { image ->
            if (total > IMAGE_CAP) return Decision(Kind.TOO_LARGE)
            return Decision(Kind.IMAGE, image.mime, image.mime.substringAfter('/').replace("jpeg", "jpg"), chunkCount)
        }
        Sniff.stream(head)?.let { media ->
            return Decision(Kind.MEDIA, media.mime, EXTENSIONS[media.mime], chunkCount)
        }
        if (Sniff.pdf(head)) {
            if (total > PDF_CAP) return Decision(Kind.TOO_LARGE)
            return Decision(Kind.PDF, "application/pdf", "pdf", chunkCount)
        }
        if (Sniff.claimsText(claimedType, claimedName) && Sniff.isText(head, complete = chunkCount == 1)) {
            val frames = minOf(chunkCount, ((TEXT_CAP + V1.FRAME_PLAINTEXT - 1) / V1.FRAME_PLAINTEXT))
            return Decision(Kind.TEXT, "text/plain", "txt", frames, truncated = total > TEXT_CAP)
        }
        return Decision(Kind.UNSUPPORTED)
    }

    private val EXTENSIONS = mapOf(
        "video/quicktime" to "mov", "audio/mp4" to "m4a", "video/mp4" to "mp4", "video/x-matroska" to "mkv", "video/webm" to "webm",
        "video/ogg" to "ogv", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac", "audio/mpeg" to "mp3", "audio/aac" to "aac",
    )
}
