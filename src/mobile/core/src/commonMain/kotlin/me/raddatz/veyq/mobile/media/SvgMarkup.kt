package me.raddatz.veyq.mobile.media

/**
 * What every app refuses of an SVG's markup before a renderer sees it (inbox #1059, ADR 0155 § The file facts' slice D9 and
 * D15; the web's taint gate): a Depot file's view (`depot/SvgView`) and a cover drawn from an SVG ([SvgCover]) alike, so a
 * peer's SVG is held to one threat model whatever draws it.
 */
object SvgMarkup {
    /** How many rounds of character references [refused] undoes before it gives up and refuses (a nesting that deep is no drawing). */
    private const val UNESCAPE_ROUNDS = 8

    /**
     * Whether an SVG's [bytes] are refused, never handed to a renderer: they hold a `foreignObject` (the web refuses one by
     * its tainted canvas; a renderer that drew one would draw a document of HTML), spelled with any case or prefix, or
     * built by character references — an internal entity's value `&#60;foreign&#79;bject` becomes markup where it is
     * used, `&#38;#…;` one round later; or they are not UTF-8 throughout, or declare an encoding other than UTF-8, so the
     * scan could not read what the renderer reads (fail closed).
     */
    fun refused(bytes: ByteArray): Boolean {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val text = try {
            bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return true
        }
        if (!utf8Declared(text)) return true
        var s = text
        repeat(UNESCAPE_ROUNDS) {
            if (s.contains("foreignobject", ignoreCase = true)) return true
            val next = unescape(s)
            if (next == s) return false
            s = next
        }
        return true
    }

    /** Whether [text]'s XML declaration, if it has one, names UTF-8 (or US-ASCII, a subset), or none. */
    private fun utf8Declared(text: String): Boolean {
        var at = 0
        while (at < text.length && text[at].isWhitespace()) at++
        if (!text.startsWith("<?xml", at)) return true
        val end = text.indexOf("?>", at)
        if (end < 0) return false
        val declaration = text.substring(at, end)
        val name = declaration.indexOf("encoding")
        if (name < 0) return true
        var i = name + "encoding".length
        while (i < declaration.length && (declaration[i].isWhitespace() || declaration[i] == '=')) i++
        if (i >= declaration.length || (declaration[i] != '"' && declaration[i] != '\'')) return false
        val close = declaration.indexOf(declaration[i], i + 1)
        if (close < 0) return false
        return declaration.substring(i + 1, close).trim().lowercase() in setOf("utf-8", "utf8", "us-ascii", "ascii")
    }

    /** [s] with its numeric character references and the five predefined entities replaced by what they stand for. */
    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            val ref = s.substring(i + 1, semi)
            val replaced: String? = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let(::codePoint)
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePoint)
                ref == "lt" -> "<"
                ref == "gt" -> ">"
                ref == "amp" -> "&"
                ref == "quot" -> "\""
                ref == "apos" -> "'"
                else -> null
            }
            if (replaced == null) {
                out.append(c)
                i++
            } else {
                out.append(replaced)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
