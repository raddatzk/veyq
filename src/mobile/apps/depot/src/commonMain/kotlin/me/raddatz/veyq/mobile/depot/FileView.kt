package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.V1

/**
 * How a file's page shows it (the web's `features/depot/view.ts`, `docs/apps/depot.md` § The viewer): decided by its first
 * frame's decrypted BYTES (`Sniff`); the sealed type and the name are claims that only narrow (text must be claimed). A
 * raster image, a PDF and a text are shown in the app, an audio or video container played there, and everything else is
 * only downloaded — a peer's bytes are never handed to another app or rendered as markup. An SVG whose markup and claim agree
 * is an image only on a platform that draws it with nothing in it run or fetched ([svgAsImage], inbox #1059: Linux, through
 * glycin's sandboxed librsvg); everywhere else it is shown as its text (`docs/apps/depot.md` § The viewer).
 * Nothing decrypted is written to show it (ADR 0047): an image, a text and a PDF are held in memory (each capped), a medium
 * is read in ranges through core's `RangeReader` ([FileModel]).
 */
object FileView {
    /** Largest image decrypted into memory (the web's `IMAGE_CAP`); over it, the download is offered instead. */
    const val IMAGE_CAP = 64L * 1024 * 1024
    /** Most text shown; a longer one shows its first [TEXT_CAP] bytes and a note (`TEXT_CAP`). */
    const val TEXT_CAP = 1024 * 1024
    /** Largest PDF decrypted (`PDF_CAP`). */
    const val PDF_CAP = 64L * 1024 * 1024
    /** The longest side, in pixels, a platform decodes a shown image to (both phones' 4096 before it was the core's). */
    const val IMAGE_EDGE = 4096
    /** Most pages of a PDF a viewer draws (the web's pdf.js cap, `docs/apps/depot.md` § The viewer); past it, it says so. */
    const val PDF_PAGES = 1000

    /** The pages of a PDF of [total] a viewer draws: at most [PDF_PAGES]. */
    fun pagesShown(total: Int): Int = total.coerceIn(0, PDF_PAGES)

    /** What a viewer says under a PDF of [total] pages it did not draw whole; null where it draws them all. */
    fun pagesCut(total: Int): me.raddatz.veyq.mobile.words.Notice? =
        if (total > PDF_PAGES) me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.FILE_PAGES_CUT, PDF_PAGES, total) else null

    enum class Kind { IMAGE, MEDIA, PDF, TEXT, TOO_LARGE, EMPTY, UNSUPPORTED }

    /**
     * Whether this platform draws an SVG as an image (inbox #1059): only where its renderer runs nothing an SVG holds (no
     * script, no `foreignObject` document), fetches nothing it names (`<image href>`, `<use>`, CSS `url()`/`@import`, an
     * external entity) and expands no external entity — proven on that platform by a hostile file's test. Linux's start
     * turns it on (the bridge: GTK decodes through glycin's sandboxed librsvg, `file_page.rs`' test); every other app shows an
     * SVG as its text. Off by default, and nothing turns it off again.
     */
    var svgAsImage: Boolean = false
        private set

    /** This platform draws an SVG as an image ([svgAsImage]); called once at the start of a platform that proved it. */
    fun drawSvgAsImage() {
        svgAsImage = true
    }

    /** What the page says where it shows no content of [kind]: too large, empty, else not shown here. */
    fun message(kind: Kind): me.raddatz.veyq.mobile.words.Msg = when (kind) {
        Kind.TOO_LARGE -> me.raddatz.veyq.mobile.words.Msg.FILE_TOO_LARGE
        Kind.EMPTY -> me.raddatz.veyq.mobile.words.Msg.FILE_EMPTY
        else -> me.raddatz.veyq.mobile.words.Msg.FILE_UNSUPPORTED
    }

    /**
     * What to show: its [kind], the sniffed [mime] and its [fileExtension] (the sniffed type's, never the peer's name), how many [frames] to read for it (all, or a text's first [TEXT_CAP] bytes), and whether a text is [truncated].
     */
    data class Decision(val kind: Kind, val mime: String? = null, val fileExtension: String? = null, val frames: Int = 0, val truncated: Boolean = false)

    /** The decision for a file of [chunkCount] frames, [total] plaintext bytes, whose first frame is [head]; [svg]: an SVG
     *  may be an image here ([svgAsImage]). */
    fun decide(
        head: ByteArray, chunkCount: Int, total: Long, claimedType: String?, claimedName: String?, svg: Boolean = svgAsImage,
    ): Decision {
        if (chunkCount < 1 || head.isEmpty()) return Decision(Kind.EMPTY)
        Sniff.image(head)?.let { image ->
            if (total > IMAGE_CAP) return Decision(Kind.TOO_LARGE)
            return Decision(Kind.IMAGE, image.mime, image.mime.substringAfter('/').replace("jpeg", "jpg"), chunkCount)
        }
        // the web's order (`view.ts`): an SVG only where the claim agrees with the markup, and only where this platform draws one
        if (svg && Sniff.claimsSvg(claimedType, claimedName) && Sniff.svg(head)) {
            if (total > IMAGE_CAP) return Decision(Kind.TOO_LARGE)
            return Decision(Kind.IMAGE, "image/svg+xml", "svg", chunkCount)
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

    /** What a file's page shows, read as far as it needs: the [decision], an image's or a PDF's [bytes], a text's [text]. */
    class Opened(val decision: Decision, val bytes: ByteArray?, val text: String?)

    /**
     * [file]'s view (node [nodeName]): its first frame decided by its bytes — the node's name a claim only where it has an
     * extension, else the uploaded name —, then an image and a PDF read whole (both capped), a text's first [TEXT_CAP] bytes
     * decoded. A medium is read in ranges ([DepotFiles.reader]), never here.
     */
    @Throws(Exception::class)
    suspend fun open(tenant: me.raddatz.veyq.mobile.session.TenantSession, file: DepotFiles.File, nodeName: String): Opened =
        open(file, nodeName) { first, until, sink -> DepotFiles.read(tenant, file, first, until, sink) }

    /** [file]'s view over [read], which hands its frames `first until until` in order (a test plays it). */
    suspend fun open(file: DepotFiles.File, nodeName: String, read: suspend (first: Int, until: Int, sink: suspend (ByteArray) -> Unit) -> Unit): Opened {
        var head = ByteArray(0)
        read(0, 1) { head = it }
        val named = if (Sniff.extensionOf(nodeName).isNotEmpty()) nodeName else file.uploadedName
        val d = decide(head, file.chunkCount, file.size ?: 0, file.type, named)
        suspend fun frames(until: Int): ByteArray {
            val parts = ArrayList<ByteArray>()
            read(0, until) { parts += it }
            val all = ByteArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                p.copyInto(all, o)
                o += p.size
            }
            return all
        }
        return when (d.kind) {
            Kind.IMAGE, Kind.PDF -> Opened(d, frames(file.chunkCount), null)
            Kind.TEXT -> frames(d.frames).let { Opened(d, null, Sniff.decodeText(it.copyOf(minOf(it.size, TEXT_CAP)))) }
            else -> Opened(d, null, null)
        }
    }

    private val EXTENSIONS = mapOf(
        "video/quicktime" to "mov", "audio/mp4" to "m4a", "video/mp4" to "mp4", "video/x-matroska" to "mkv", "video/webm" to "webm",
        "video/ogg" to "ogv", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac", "audio/mpeg" to "mp3", "audio/aac" to "aac",
    )
}
