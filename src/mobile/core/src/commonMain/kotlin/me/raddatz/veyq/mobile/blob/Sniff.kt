package me.raddatz.veyq.mobile.blob

/**
 * What a blob's decrypted bytes are, by their magic numbers alone — a port of `src/web/src/core/blob/sniff.ts` (images,
 * audio and video containers, text, PDF). The bytes decide; a claimed type or name only ever narrows ([claimsText]), so a
 * peer's file labelled "image" that is no image is never handed to an image decoder as one.
 */
object Sniff {
    enum class Image(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), WEBP("image/webp"), AVIF("image/avif"), BMP("image/bmp"), HEIC("image/heic") }

    private val AVIF = setOf("avif", "avis")
    private val HEIC = setOf("heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "heif", "mif1", "msf1")
    private val BMP_DIB = setOf(12, 40, 52, 56, 64, 108, 124)
    private val TEXT_CONTROLS = setOf(0x09, 0x0a, 0x0c, 0x0d, 0x1b)
    private const val PDF_WINDOW = 1024

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    fun asciiAt(b: ByteArray, offset: Int, length: Int): String =
        buildString { for (i in offset until minOf(b.size, offset + length)) append(u(b, i).toChar()) }

    fun hasAscii(b: ByteArray, offset: Int, ascii: String) = b.size >= offset + ascii.length && asciiAt(b, offset, ascii.length) == ascii

    /** The raster image format [b] is, or null. */
    fun image(b: ByteArray): Image? {
        if (b.size >= 3 && u(b, 0) == 0xff && u(b, 1) == 0xd8 && u(b, 2) == 0xff) return Image.JPEG
        if (b.size >= 8 && u(b, 0) == 0x89 && hasAscii(b, 1, "PNG\r\n") && u(b, 6) == 0x1a && u(b, 7) == 0x0a) return Image.PNG
        if (hasAscii(b, 0, "GIF87a") || hasAscii(b, 0, "GIF89a")) return Image.GIF
        if (hasAscii(b, 0, "RIFF") && hasAscii(b, 8, "WEBP")) return Image.WEBP
        if (b.size >= 26 && u(b, 0) == 0x42 && u(b, 1) == 0x4d && u(b, 15) == 0 && u(b, 16) == 0 && u(b, 17) == 0 && u(b, 14) in BMP_DIB) return Image.BMP
        if (hasAscii(b, 4, "ftyp")) {
            val brand = asciiAt(b, 8, 4)
            if (brand in AVIF) return Image.AVIF
            if (brand in HEIC) return Image.HEIC
        }
        return null
    }

    /**
     * Whether [b] is valid text: a UTF-16 byte-order mark followed by a decodable body, or valid UTF-8 with no NUL and no
     * control byte but tab, line feed, form feed, carriage return and escape. A sequence cut off by the end is not an error
     * while [b] is a prefix, but is when it is the [complete] file.
     */
    fun isText(b: ByteArray, complete: Boolean = false): Boolean {
        if (b.size >= 2 && ((u(b, 0) == 0xff && u(b, 1) == 0xfe) || (u(b, 0) == 0xfe && u(b, 1) == 0xff))) {
            return utf16Decodes(b, littleEndian = u(b, 0) == 0xff)
        }
        for (i in b.indices) if (u(b, i) < 0x20 && u(b, i) !in TEXT_CONTROLS) return false
        return utf8Valid(b, complete)
    }

    private fun utf16Decodes(b: ByteArray, littleEndian: Boolean): Boolean {
        val n = b.size - (b.size % 2)
        var i = 2
        var pendingHigh = false
        while (i < n) {
            val unit = if (littleEndian) u(b, i) or (u(b, i + 1) shl 8) else (u(b, i) shl 8) or u(b, i + 1)
            if (unit in 0xD800..0xDBFF) {
                if (pendingHigh) return false
                pendingHigh = true
            } else if (unit in 0xDC00..0xDFFF) {
                if (!pendingHigh) return false
                pendingHigh = false
            } else if (pendingHigh) {
                return false
            }
            i += 2
        }
        // A fatal decoder refuses a high surrogate left open at the end.
        return !pendingHigh
    }

    private fun utf8Valid(b: ByteArray, complete: Boolean): Boolean {
        var i = 0
        while (i < b.size) {
            val c = u(b, i)
            val (n, min) = when {
                c < 0x80 -> 0 to 0
                c in 0xc2..0xdf -> 1 to 0x80
                c in 0xe0..0xef -> 2 to 0x800
                c in 0xf0..0xf4 -> 3 to 0x10000
                else -> return false
            }
            if (i + n >= b.size && n > 0) {
                // Cut off by the end: valid so far is valid for a prefix, never for the complete file.
                if (complete) return false
                for (k in i + 1 until b.size) if (u(b, k) and 0xc0 != 0x80) return false
                return prefixOk(b, i)
            }
            var cp = if (n == 0) c else c and (0x3f shr n)
            for (k in 1..n) {
                val cc = u(b, i + k)
                if (cc and 0xc0 != 0x80) return false
                cp = (cp shl 6) or (cc and 0x3f)
            }
            if (n > 0 && (cp < min || cp > 0x10ffff || cp in 0xd800..0xdfff)) return false
            i += n + 1
        }
        return true
    }

    /** A truncated last sequence at [i] that could still complete validly (its second byte within the lead's range). */
    private fun prefixOk(b: ByteArray, i: Int): Boolean {
        if (i + 1 >= b.size) return true
        val c = u(b, i)
        val c1 = u(b, i + 1)
        return when (c) {
            0xe0 -> c1 >= 0xa0
            0xed -> c1 <= 0x9f
            0xf0 -> c1 >= 0x90
            0xf4 -> c1 <= 0x8f
            else -> true
        }
    }

    /**
     * Whether [b] is a PDF: `%PDF-` at byte 0, or within the first KiB but then only where [b] is NOT valid text (a text
     * that merely mentions the header is a text).
     */
    fun pdf(b: ByteArray): Boolean {
        if (hasAscii(b, 0, "%PDF-")) return true
        for (i in 1..PDF_WINDOW) {
            if (i >= b.size) break
            if (u(b, i) == 0x25 && hasAscii(b, i, "%PDF-")) return !isText(b)
        }
        return false
    }

    /** An audio or video container: whether it is video, and the MIME it is played under. */
    data class Stream(val video: Boolean, val mime: String)

    /** The audio/video container [b] is, by its magic bytes (the web's `sniffStream`; the player decides if it can play it). */
    fun stream(b: ByteArray): Stream? {
        if (hasAscii(b, 4, "ftyp")) {
            val brand = asciiAt(b, 8, 4)
            if (brand == "qt  ") return Stream(true, "video/quicktime")
            if (brand == "M4A " || brand == "M4B " || brand == "M4P ") return Stream(false, "audio/mp4")
            if (brand in AVIF || brand in HEIC) return null
            return Stream(true, "video/mp4")
        }
        if (b.size >= 4 && u(b, 0) == 0x1a && u(b, 1) == 0x45 && u(b, 2) == 0xdf && u(b, 3) == 0xa3) {
            return if (asciiAt(b, 0, 64).contains("matroska")) Stream(true, "video/x-matroska") else Stream(true, "video/webm")
        }
        if (hasAscii(b, 0, "OggS")) return if (asciiAt(b, 0, 160).contains("theora")) Stream(true, "video/ogg") else Stream(false, "audio/ogg")
        if (hasAscii(b, 0, "RIFF") && hasAscii(b, 8, "WAVE")) return Stream(false, "audio/wav")
        if (hasAscii(b, 0, "fLaC")) return Stream(false, "audio/flac")
        if (hasAscii(b, 0, "ID3")) return Stream(false, "audio/mpeg")
        if (b.size >= 2 && u(b, 0) == 0xff && (u(b, 1) and 0xe0) == 0xe0) {
            val layer = (u(b, 1) shr 1) and 0x03
            val version = (u(b, 1) shr 3) and 0x03
            if (layer == 0 && (u(b, 1) and 0xf6) == 0xf0) return Stream(false, "audio/aac")
            val bitrate = if (b.size > 2) u(b, 2) shr 4 else 1
            val rate = if (b.size > 2) (u(b, 2) shr 2) and 0x03 else 0
            if (layer == 1 && version != 1 && bitrate != 0x0f && rate != 0x03) return Stream(false, "audio/mpeg")
        }
        return null
    }

    private val TEXT_TYPES = setOf(
        "application/json", "application/ld+json", "application/x-ndjson", "application/xml", "application/javascript",
        "application/x-javascript", "application/typescript", "application/x-yaml", "application/yaml", "application/toml",
        "application/x-sh", "application/x-httpd-php", "application/sql", "application/x-tex", "application/rtf",
    )
    private val TEXT_EXTENSIONS = setOf(
        "txt", "text", "md", "markdown", "rst", "adoc", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini",
        "cfg", "conf", "env", "properties", "log", "sql", "html", "htm", "css", "scss", "less", "js", "mjs", "cjs", "ts", "tsx",
        "jsx", "vue", "svelte", "py", "rb", "php", "java", "kt", "kts", "scala", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "swift",
        "m", "sh", "bash", "zsh", "fish", "ps1", "bat", "lua", "pl", "r", "tex", "srt", "vtt", "gradle", "dockerfile", "gitignore",
        "lock", "diff", "patch", "svg",
    )
    private val EXTENSION = Regex("[^.]\\.([^.\\s/\\\\]{1,16})$")

    /** The lower-cased extension of [name] (no dot), or "" (the web's `extensionOf`). */
    fun extensionOf(name: String?): String = EXTENSION.find(name ?: "")?.groupValues?.get(1)?.lowercase() ?: ""

    /** Whether the CLAIMED type or name says "text" — a claim that may only narrow what the bytes already allow. */
    fun claimsText(type: String?, name: String?): Boolean {
        val t = (type ?: "").substringBefore(';').trim().lowercase()
        if (t.startsWith("text/") || t in TEXT_TYPES || t.endsWith("+json") || t.endsWith("+xml")) return true
        return extensionOf(name) in TEXT_EXTENSIONS
    }

    /** [b] as the text [isText] said it is: UTF-16 by its byte-order mark, else UTF-8 (a leading mark dropped). */
    fun decodeText(b: ByteArray): String {
        if (b.size >= 2 && ((u(b, 0) == 0xff && u(b, 1) == 0xfe) || (u(b, 0) == 0xfe && u(b, 1) == 0xff))) {
            val le = u(b, 0) == 0xff
            val sb = StringBuilder()
            var i = 2
            while (i + 1 < b.size) {
                sb.append((if (le) u(b, i) or (u(b, i + 1) shl 8) else (u(b, i) shl 8) or u(b, i + 1)).toChar())
                i += 2
            }
            return sb.toString()
        }
        val start = if (b.size >= 3 && u(b, 0) == 0xef && u(b, 1) == 0xbb && u(b, 2) == 0xbf) 3 else 0
        return b.decodeToString(start, b.size)
    }
}
