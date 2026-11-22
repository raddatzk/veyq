package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.search.JsText

/**
 * What a blob's decrypted bytes are, by their magic numbers alone (inbox #412) — the one rule every client reads: the apps
 * directly, the web through `@veyq/core-js/files` (ADR 0155 decision 7.2, the file facts' slice; its TypeScript deleted).
 * A blob's sealed type and an object's name are a PEER's statement about the bytes; nothing here lets that statement decide
 * how the bytes are rendered. The functions answer from the bytes alone, and a viewer combines the answer with the claim only
 * to NARROW (a text needs a text-ish claim too, an SVG needs the claim to agree, [claimsText], [claimsSvg]), never to widen: a
 * PNG claimed as `text/plain` is a PNG, a binary claimed as `image/png` is neither. `contracts/platform/files/v1-vectors.json`
 * holds every answer on every client.
 *
 * Its path runs on JavaScript per file opened, over a first frame of up to 256 KiB (ADR 0155: no `Long`, `Regex` or
 * `HashMap` on a web path): sets are `when`s, the patterns hand-written loops, the white space JavaScript's ([JsText]).
 */
object Sniff {
    enum class Image(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), WEBP("image/webp"), AVIF("image/avif"), BMP("image/bmp"), HEIC("image/heic") }

    /** How a text is decoded: UTF-8 (a BOM dropped), or UTF-16 only where a byte-order mark says so. No 8-bit legacy
     *  encoding is guessed: it is not valid UTF-8, so it is not text. */
    const val UTF8 = "utf-8"
    const val UTF16LE = "utf-16le"
    const val UTF16BE = "utf-16be"

    private const val PDF_WINDOW = 1024
    /** How much of a text an SVG's first element is looked for in, and how many prolog items are skipped before it. */
    private const val SVG_WINDOW = 4096
    private const val SVG_SKIPS = 16
    /** How far an EBML header's `DocType` is looked for (it sits at the very top of a well-formed file). */
    private const val EBML_WINDOW = 1024

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    fun asciiAt(b: ByteArray, offset: Int, length: Int): String =
        buildString { for (i in offset until minOf(b.size, offset + length)) append(u(b, i).toChar()) }

    fun hasAscii(b: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || b.size < offset + ascii.length) return false
        for (i in ascii.indices) if (u(b, offset + i) != ascii[i].code) return false
        return true
    }

    private fun avifBrand(brand: String) = brand == "avif" || brand == "avis"
    private fun heicBrand(brand: String) = when (brand) {
        "heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "heif", "mif1", "msf1" -> true
        else -> false
    }

    /** A BMP's DIB header sizes (BITMAPCOREHEADER … BITMAPV5HEADER): `BM` alone is two letters any text may start with. */
    private fun bmpDib(size: Int) = when (size) {
        12, 40, 52, 56, 64, 108, 124 -> true
        else -> false
    }

    /** The raster image format [b] is, or null. An SVG is text and answered by [svg]. */
    fun image(b: ByteArray): Image? {
        if (b.size >= 3 && u(b, 0) == 0xff && u(b, 1) == 0xd8 && u(b, 2) == 0xff) return Image.JPEG
        if (b.size >= 8 && u(b, 0) == 0x89 && hasAscii(b, 1, "PNG\r\n") && u(b, 6) == 0x1a && u(b, 7) == 0x0a) return Image.PNG
        if (hasAscii(b, 0, "GIF87a") || hasAscii(b, 0, "GIF89a")) return Image.GIF
        if (hasAscii(b, 0, "RIFF") && hasAscii(b, 8, "WEBP")) return Image.WEBP
        if (b.size >= 26 && u(b, 0) == 0x42 && u(b, 1) == 0x4d && u(b, 15) == 0 && u(b, 16) == 0 && u(b, 17) == 0 && bmpDib(u(b, 14))) return Image.BMP
        if (hasAscii(b, 4, "ftyp")) {
            val brand = asciiAt(b, 8, 4)
            if (avifBrand(brand)) return Image.AVIF
            if (heicBrand(brand)) return Image.HEIC
        }
        return null
    }

    /** Control bytes a text may hold: tab, line feed, form feed, carriage return, escape (ANSI colours in logs). */
    private fun textControl(c: Int) = c == 0x09 || c == 0x0a || c == 0x0c || c == 0x0d || c == 0x1b

    /**
     * The encoding [b] is valid text in ([UTF8], [UTF16LE], [UTF16BE]), or null: a UTF-16 byte-order mark followed by a
     * decodable body, or valid UTF-8 with no NUL and no control byte but tab, line feed, form feed, carriage return and escape.
     * A sequence cut off by the end is not an error while [b] is a prefix, but is when it is the [complete] file. Only [b] is
     * judged; what follows it decodes with U+FFFD.
     */
    fun textEncoding(b: ByteArray, complete: Boolean = false): String? {
        if (b.size >= 2) {
            val le = u(b, 0) == 0xff && u(b, 1) == 0xfe
            if (le || (u(b, 0) == 0xfe && u(b, 1) == 0xff)) return if (utf16Decodes(b, le)) (if (le) UTF16LE else UTF16BE) else null
        }
        return if (utf8Text(b, complete)) UTF8 else null
    }

    /** Whether [b] is valid text ([textEncoding]). */
    fun isText(b: ByteArray, complete: Boolean = false): Boolean = textEncoding(b, complete) != null

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

    /** Valid UTF-8 (the WHATWG decoder's fatal reading, streaming unless [complete]) holding no forbidden control byte. */
    private fun utf8Text(b: ByteArray, complete: Boolean): Boolean {
        val size = b.size
        var i = 0
        while (i < size) {
            val c = u(b, i)
            if (c < 0x80) {
                if (c < 0x20 && !textControl(c)) return false
                i++
                continue
            }
            // the lead's length and the range its second byte must fall in (the decoder's lower and upper boundary)
            val n: Int
            var lo = 0x80
            var hi = 0xbf
            when {
                c in 0xc2..0xdf -> n = 1
                c in 0xe0..0xef -> {
                    n = 2
                    if (c == 0xe0) lo = 0xa0
                    if (c == 0xed) hi = 0x9f
                }
                c in 0xf0..0xf4 -> {
                    n = 3
                    if (c == 0xf0) lo = 0x90
                    if (c == 0xf4) hi = 0x8f
                }
                else -> return false
            }
            for (k in 1..n) {
                if (i + k >= size) return !complete // cut off by the end: valid so far is valid for a prefix only
                val cc = u(b, i + k)
                if (k == 1) {
                    if (cc < lo || cc > hi) return false
                } else if (cc and 0xc0 != 0x80) {
                    return false
                }
            }
            i += n + 1
        }
        return true
    }

    /**
     * Whether [b] is a PDF: `%PDF-` at byte 0, or within the first KiB but then only where [b] is NOT valid text (a text that
     * merely mentions the header is a text). The bytes decide; no claim is consulted.
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

    /**
     * An EBML file's `DocType` ("webm", "matroska"), read where its element id (`0x42 0x82`) first sits in the first KiB, its
     * single-byte size and that many ASCII bytes; null where it is not found or not read so.
     */
    fun ebmlDocType(b: ByteArray): String? {
        val end = minOf(b.size - 1, EBML_WINDOW)
        for (i in 4 until end) {
            if (u(b, i) != 0x42 || u(b, i + 1) != 0x82) continue
            if (i + 2 >= b.size) return null
            val sizeByte = u(b, i + 2)
            if (sizeByte and 0x80 == 0) return null // only the single-byte size is expected here
            val len = sizeByte and 0x7f
            if (len == 0 || i + 3 + len > b.size) return null
            return asciiAt(b, i + 3, len)
        }
        return null
    }

    /** The audio/video container [b] is, by its magic bytes (ADR 0047: the player, not this, decides if it can play it). */
    fun stream(b: ByteArray): Stream? {
        if (hasAscii(b, 4, "ftyp")) {
            val brand = asciiAt(b, 8, 4)
            if (brand == "qt  ") return Stream(true, "video/quicktime")
            if (brand == "M4A " || brand == "M4B " || brand == "M4P ") return Stream(false, "audio/mp4")
            if (avifBrand(brand) || heicBrand(brand)) return null // an image, answered by image()
            return Stream(true, "video/mp4")
        }
        if (b.size >= 4 && u(b, 0) == 0x1a && u(b, 1) == 0x45 && u(b, 2) == 0xdf && u(b, 3) == 0xa3) {
            return if (ebmlDocType(b) == "matroska") Stream(true, "video/x-matroska") else Stream(true, "video/webm")
        }
        if (hasAscii(b, 0, "OggS")) return if (asciiAt(b, 0, 160).contains("theora")) Stream(true, "video/ogg") else Stream(false, "audio/ogg")
        if (hasAscii(b, 0, "RIFF") && hasAscii(b, 8, "WAVE")) return Stream(false, "audio/wav")
        if (hasAscii(b, 0, "fLaC")) return Stream(false, "audio/flac")
        if (hasAscii(b, 0, "ID3")) return Stream(false, "audio/mpeg")
        if (b.size >= 2 && u(b, 0) == 0xff && (u(b, 1) and 0xe0) == 0xe0) {
            val layer = (u(b, 1) shr 1) and 0x03
            val version = (u(b, 1) shr 3) and 0x03
            if (layer == 0 && (u(b, 1) and 0xf6) == 0xf0) return Stream(false, "audio/aac") // ADTS
            // An MPEG audio frame header, Layer III only (mp3; `FF FE`, a UTF-16 text's byte-order mark, is Layer I): a valid
            // version, bitrate and sample-rate index.
            val bitrate = if (b.size > 2) u(b, 2) shr 4 else 1
            val rate = if (b.size > 2) (u(b, 2) shr 2) and 0x03 else 0
            if (layer == 1 && version != 1 && bitrate != 0x0f && rate != 0x03) return Stream(false, "audio/mpeg")
        }
        return null
    }

    /**
     * Whether [b] is an SVG document: UTF-8 text ([textEncoding]) whose first element, after a byte-order mark, white space,
     * and at most [SVG_SKIPS] XML declarations, processing instructions, comments and a doctype (its internal subset too),
     * within its first [SVG_WINDOW] bytes, is `<svg` followed by white space, `>` or `/` (ASCII case ignored). The bytes decide;
     * a viewer still requires a claim that agrees ([claimsSvg]) and draws it only where nothing in it can run or fetch
     * (`docs/apps/depot.md` § The viewer, inbox #1059).
     */
    fun svg(b: ByteArray): Boolean {
        if (textEncoding(b) != UTF8) return false
        val s = svgWindow(b)
        var at = 0
        for (guard in 0 until SVG_SKIPS) {
            while (at < s.length && JsText.isJsSpace(s[at])) at++
            val skipped = prologEnd(s, at)
            if (skipped < 0) break
            at = skipped
        }
        if (!startsWithIgnoringAsciiCase(s, at, "<svg") || at + 4 >= s.length) return false
        val next = s[at + 4]
        return next == '>' || next == '/' || JsText.isJsSpace(next)
    }

    /** The first [SVG_WINDOW] bytes of valid UTF-8 [b] as text: a leading byte-order mark dropped, a sequence the window cuts
     *  withheld (a streaming decoder's reading). */
    private fun svgWindow(b: ByteArray): String {
        var end = minOf(b.size, SVG_WINDOW)
        // back up over a sequence the window cut: its lead's length runs past the end
        var lead = end - 1
        while (lead >= 0 && lead > end - 4 && (u(b, lead) and 0xc0) == 0x80) lead--
        if (lead >= 0) {
            val c = u(b, lead)
            val len = when {
                c < 0x80 -> 1
                c < 0xe0 -> 2
                c < 0xf0 -> 3
                else -> 4
            }
            if (lead + len > end) end = lead
        }
        val start = if (end >= 3 && u(b, 0) == 0xef && u(b, 1) == 0xbb && u(b, 2) == 0xbf) 3 else 0
        return if (start >= end) "" else b.decodeToString(start, end)
    }

    /** Where the declaration, instruction, comment or doctype at [at] of [s] ends, or -1 where none starts there. */
    private fun prologEnd(s: String, at: Int): Int {
        if (s.startsWith("<?", at)) return s.indexOf("?>", at + 2).let { if (it < 0) -1 else it + 2 }
        if (s.startsWith("<!--", at)) return s.indexOf("-->", at + 4).let { if (it < 0) -1 else it + 3 }
        if (!startsWithIgnoringAsciiCase(s, at, "<!DOCTYPE")) return -1
        var i = at + 9
        while (i < s.length && s[i] != '>' && s[i] != '[') i++
        if (i >= s.length) return -1
        if (s[i] == '>') return i + 1
        // an internal subset: the first `]` after which only white space stands before a `>`
        var close = s.indexOf(']', i + 1)
        while (close >= 0) {
            var k = close + 1
            while (k < s.length && JsText.isJsSpace(s[k])) k++
            if (k < s.length && s[k] == '>') return k + 1
            close = s.indexOf(']', close + 1)
        }
        return -1
    }

    private fun startsWithIgnoringAsciiCase(s: String, at: Int, prefix: String): Boolean {
        if (at + prefix.length > s.length) return false
        for (i in prefix.indices) {
            var c = s[at + i]
            if (c in 'a'..'z') c -= 32
            var p = prefix[i]
            if (p in 'a'..'z') p -= 32
            if (c != p) return false
        }
        return true
    }

    /** The claimed type without its parameters, trimmed as JavaScript trims, lower case. */
    private fun essence(type: String?): String {
        if (type == null) return ""
        val semi = type.indexOf(';')
        return JsText.trim(if (semi < 0) type else type.substring(0, semi)).lowercase()
    }

    private fun textType(t: String) = when (t) {
        "application/json", "application/ld+json", "application/x-ndjson", "application/xml", "application/javascript",
        "application/x-javascript", "application/typescript", "application/x-yaml", "application/yaml", "application/toml",
        "application/x-sh", "application/x-httpd-php", "application/sql", "application/x-tex", "application/rtf",
        -> true
        else -> false
    }

    private fun textExtension(e: String) = when (e) {
        "txt", "text", "md", "markdown", "rst", "adoc", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini",
        "cfg", "conf", "env", "properties", "log", "sql", "html", "htm", "css", "scss", "less", "js", "mjs", "cjs", "ts", "tsx",
        "jsx", "vue", "svelte", "py", "rb", "php", "java", "kt", "kts", "scala", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "swift",
        "m", "sh", "bash", "zsh", "fish", "ps1", "bat", "lua", "pl", "r", "tex", "srt", "vtt", "gradle", "dockerfile", "gitignore",
        "lock", "diff", "patch", "svg",
        -> true
        else -> false
    }

    /** The lower-cased extension of [name] (no dot), or "": after its last dot, which follows a character that is no dot,
     *  1–16 characters that are no white space, `/` or `\`. */
    fun extensionOf(name: String?): String {
        if (name == null) return ""
        val dot = name.lastIndexOf('.')
        if (dot < 1 || name[dot - 1] == '.') return ""
        val len = name.length - dot - 1
        if (len < 1 || len > 16) return ""
        for (i in dot + 1 until name.length) {
            val c = name[i]
            if (c == '/' || c == '\\' || JsText.isJsSpace(c)) return ""
        }
        return name.substring(dot + 1).lowercase()
    }

    /** Whether the CLAIMED type or name says "text" — a claim that may only narrow what the bytes already allow. */
    fun claimsText(type: String?, name: String?): Boolean {
        val t = essence(type)
        if (t.startsWith("text/") || textType(t) || t.endsWith("+json") || t.endsWith("+xml")) return true
        return textExtension(extensionOf(name))
    }

    /** Whether the CLAIM names SVG: the sealed type or the name's extension. */
    fun claimsSvg(type: String?, name: String?): Boolean = essence(type) == "image/svg+xml" || extensionOf(name) == "svg"

    /** [b] as the text [textEncoding] said it is: UTF-16 by its byte-order mark, else UTF-8 (a leading mark dropped). */
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
