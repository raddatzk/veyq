package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.Kit
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.qr.QrDecoder
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.session.SessionModel

/**
 * What a platform draws for a kit's read (ADR 0136 decision 1: the platform draws, the core decides): a PDF's pages
 * through its own renderer, a picture through its own decoder. Each answers null where it cannot.
 */
interface KitPictures {
    /** The PDF's page count as the platform's renderer opens it; null where it does not open. */
    suspend fun pdfPages(): Int?

    /** Page [page] (1-based) drawn [width] px wide, as luminance (0 black, 255 white). */
    suspend fun pdfPage(page: Int, width: Int): QrFrame?

    /** The file decoded as an image, scaled so its longer side is [longSide] px, as luminance. */
    suspend fun picture(longSide: Int): QrFrame?
}

/**
 * An Emergency Kit read from a file the person picked (en.account.key-model § The Emergency Kit, the apps' order): (a)
 * the token in the file's bytes; (b) the `Keywords` of a PDF's document information ([KitPdf]); (c) the QR on its first
 * [PDF_PAGES] pages drawn [PICTURE_PIXELS] wide, a page that reads nothing again [FALLBACK_PIXELS] wide — an image file
 * straight to its QR, at the same two sizes; (d) only where the token found has no
 * server, a PDF's first address field read as a typed server. One function for every app: the phones play [KitPictures]
 * in their process, the desktops through the bridge's host requests.
 */
object KitImport {
    /** A larger file is refused unread (a photographed kit is ~230 KB, a scan app's multi-page PDF a few MB). */
    const val FILE_CAP = 32 * 1024 * 1024

    /** The web's `KIT_PICTURE_PIXELS`: a page drawn this wide, an image this long on its longer side. */
    const val PICTURE_PIXELS = 2400

    /**
     * Where [PICTURE_PIXELS] reads nothing, the page or picture is drawn once more this wide. Measured on the
     * photographed fixture (`kit-photo.pdf`, its photo 1512 px wide), page 1 decoded by this decoder on macosArm64:
     * PDFKit's drawing (8-bit grey, or sRGB taken to luminance) reads at every width tried from 1600 to 3600 but
     * exactly 2400, and not at 1000-1400; PDFBox's reads at 2400 and not at 1200 (`KitFixtureTest`). The resampling
     * at one width decides, so the order tries a second width rather than one renderer's.
     */
    const val FALLBACK_PIXELS = 2000

    /** The web's `KIT_PDF_PAGES`: a photographed or scanned kit may have a cover sheet. */
    const val PDF_PAGES = 3

    /** The address field a kit's writers fill with the server's host; fields 2 and 3 are the person's, never read. */
    private const val ADDRESS_FIELD = "address1"

    private val PDF = "%PDF-".encodeToByteArray()
    private val PREFIX = "veyqkit:".encodeToByteArray()

    /** Serializable: the sign-in's state says it (`SessionModel.KitShown`), which the bridge sends as it is. */
    @kotlinx.serialization.Serializable
    enum class ServerFrom { TOKEN, FIELD, NONE }

    /** The kit read, and where its server came from: the token, the first address field, or nowhere. */
    class Found(val kit: Kit, val serverFrom: ServerFrom)

    /**
     * The kit in [bytes], or null. A drawn picture's QR is read by the core's [QrDecoder] (a still image: trying harder,
     * at any size; the platform never decodes) and its text goes straight to [EmergencyKit.read] — only the read [Kit]
     * leaves, as only `QrScan`'s `Found` leaves a scan. Never throws for a file it cannot read; each drawn picture's
     * pixels are zeroed once decoded (the decoder's own working copies are let go, not zeroed).
     */
    suspend fun read(p: Primitives, bytes: ByteArray, pictures: KitPictures): Found? {
        if (bytes.size > FILE_CAP) return null
        val isPdf = bytes.size >= PDF.size && PDF.indices.all { bytes[it] == PDF[it] }
        val pdf by lazy { KitPdf.parse(bytes) }
        val kit = tokenInBytes(bytes)?.let { EmergencyKit.read(p, it) }
            ?: (if (isPdf) pdf.keywords()?.let { EmergencyKit.read(p, it) } else null)
            ?: fromPictures(p, isPdf, pictures)
            ?: return null
        if (kit.server != null) return Found(kit, ServerFrom.TOKEN)
        val typed = if (isPdf) pdf.field(ADDRESS_FIELD)?.trim()?.takeIf { it.isNotEmpty() } else null
        val server = typed?.let { SessionModel.origin(it.lowercase()) }?.takeIf { EmergencyKit.isServer(it) }
            ?: return Found(kit, ServerFrom.NONE)
        return Found(Kit(kit.email, kit.secretKey, server), ServerFrom.FIELD)
    }

    private suspend fun fromPictures(p: Primitives, isPdf: Boolean, pictures: KitPictures): Kit? {
        if (!isPdf) {
            for (size in WIDTHS) pictures.picture(size)?.let { decoded(p, it) }?.let { return it }
            return null
        }
        val pages = pictures.pdfPages() ?: return null
        for (page in 1..minOf(pages, PDF_PAGES)) {
            for (width in WIDTHS) pictures.pdfPage(page, width)?.let { decoded(p, it) }?.let { return it }
        }
        return null
    }

    private val WIDTHS = intArrayOf(PICTURE_PIXELS, FALLBACK_PIXELS)

    private fun decoded(p: Primitives, frame: QrFrame): Kit? = try {
        QrDecoder.decode(frame, tryHarder = true)?.let { EmergencyKit.read(p, it) }
    } finally {
        frame.luminance.fill(0)
    }

    /**
     * The first `veyqkit:` with at least one base64url symbol after it, and those symbols — what the web's `parseKit`
     * finds in the file read as text, without making the whole file a string.
     */
    private fun tokenInBytes(b: ByteArray): String? {
        var i = 0
        while (i + PREFIX.size < b.size) {
            if (PREFIX.indices.all { b[i + it] == PREFIX[it] }) {
                var end = i + PREFIX.size
                while (end < b.size && isSymbol(b[end].toInt())) end++
                if (end > i + PREFIX.size) return CharArray(end - i) { (b[i + it].toInt() and 0xff).toChar() }.concatToString()
            }
            i++
        }
        return null
    }

    private fun isSymbol(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code
}
