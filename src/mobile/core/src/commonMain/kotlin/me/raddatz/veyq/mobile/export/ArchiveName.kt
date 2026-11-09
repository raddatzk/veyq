package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.crypto.nfc

/**
 * Safe names inside a download's ZIP, a port of the web's `core/export/archiveName.ts` (ADR 0082 decision 3), rule for rule:
 * NFC; `/`, `\`, control characters and `<>:"|?*` become `_`; no leading or trailing white space, no trailing dot; a name of
 * dots or nothing becomes `_`; Windows' device names gain a leading `_`; at most [MAX_BYTES] of UTF-8, cut on a character, the
 * extension kept; and two names of one directory that fold to one ignoring case are numbered, `img (2).JPG`. The same tree
 * yields the same paths in either client.
 */
object ArchiveName {
    /** The longest name before a clash suffix, in UTF-8 bytes: 255 less room for ` (99999)`. */
    const val MAX_BYTES = 200
    /** The top-level directory that holds the manifest; no node takes its name at the archive's root. */
    const val MANIFEST_DIR = ".veyq"
    private const val MAX_EXTENSION = 16

    private val FORBIDDEN = Regex("[\\u0000-\\u001f\\u007f/\\\\<>:\"|?*]")
    private val RESERVED = Regex("^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])$", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("[. ]+$")

    private fun utf8(s: String) = s.encodeToByteArray().size

    private fun clean(name: String): String {
        var n = nfc(name).replace(FORBIDDEN, "_").trim().replace(TRAILING, "")
        if (n.isEmpty()) return "_"
        if (RESERVED.matches(n.substringBefore('.').trimEnd())) n = "_$n"
        return n
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.length - dot > MAX_EXTENSION) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    /** [s] as code points, each a one- or two-char string. */
    private fun codePoints(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val two = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            out += s.substring(i, i + if (two) 2 else 1)
            i += if (two) 2 else 1
        }
        return out
    }

    private fun bound(name: String, max: Int): String {
        if (utf8(name) <= max) return name
        val (stem, ext) = splitExtension(name)
        val room = max - utf8(ext)
        val out = StringBuilder()
        for (ch in codePoints(stem)) {
            if (utf8(out.toString() + ch) > room) break
            out.append(ch)
        }
        val cut = out.toString().replace(TRAILING, "")
        return (cut.ifEmpty { "_" }) + ext
    }

    /** How two names of one directory are compared: NFC, ignoring case. */
    fun fold(name: String): String = nfc(name).lowercase()

    /** The safe name of [name] in a directory whose names so far are [taken] (folded); the result joins [taken]. */
    fun of(name: String, taken: MutableSet<String>): String {
        val base = bound(clean(name), MAX_BYTES)
        var candidate = base
        if (fold(candidate) in taken) {
            val (stem, ext) = splitExtension(base)
            var i = 2
            while (fold(candidate) in taken) candidate = "$stem (${i++})$ext"
        }
        taken += fold(candidate)
        return candidate
    }
}
