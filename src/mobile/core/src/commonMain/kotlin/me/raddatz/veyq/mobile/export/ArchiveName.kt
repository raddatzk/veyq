package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.crypto.nfc
import me.raddatz.veyq.mobile.search.JsText

/**
 * Safe names inside a download's ZIP (ADR 0082 decision 3): the ONE rule that turns a node's or a file's name into a path
 * segment every file system accepts — the writer's for every directory and file, the readers' to judge a path ([isName],
 * [isPath]), a folder upload's (inbox #424); the web draws it through `@veyq/core-js/files` (ADR 0155 decision 7.2). In order:
 * NFC; `/`, `\`, control characters and `<>:"|?*` become `_`; no leading or trailing white space (JavaScript's set), no
 * trailing dot or space; a name of nothing becomes `_`; Windows' device names (`CON`, `NUL`, `COM1`, `LPT¹`, …, with or
 * without an extension) gain a leading `_`; at most [MAX_BYTES] of UTF-8, cut on a code point, the extension kept; and two
 * names of one directory that fold to one ([fold]: NFC, ignoring case) are numbered, `img (2).JPG`. The same tree yields the
 * same paths in every client; `contracts/platform/files/v1-vectors.json` holds them. No `Regex` on the path (a web path).
 */
object ArchiveName {
    /** The longest name before a clash suffix, in UTF-8 bytes: 255 less room for ` (99999)`. */
    const val MAX_BYTES = 200
    /** The longest segment a reader accepts: the per-name limit of APFS and ext4. */
    const val SEGMENT_MAX_BYTES = 255
    /** The top-level directory that holds the manifest; no node takes its name at the archive's root. */
    const val MANIFEST_DIR = ".veyq"
    private const val MAX_EXTENSION = 16

    /** The names a directory has handed out so far, folded ([fold]); [of] adds each it hands out. The web's `Set` is one. */
    interface Taken {
        fun has(folded: String): Boolean
        fun add(folded: String)
    }

    private fun forbidden(c: Char) = c < ' ' || c == '\u007f' || when (c) {
        '/', '\\', '<', '>', ':', '"', '|', '?', '*' -> true
        else -> false
    }

    /** [s]'s length in UTF-8 as an encoder writes it: a lone surrogate as U+FFFD, three bytes. */
    private fun utf8(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }

    /** [s] without its trailing dots and spaces. */
    private fun dropTrailingDots(s: String): String {
        var end = s.length
        while (end > 0 && (s[end - 1] == '.' || s[end - 1] == ' ')) end--
        return s.substring(0, end)
    }

    /** Windows' device names, ASCII case ignored: `con`, `prn`, `aux`, `nul`, `com` or `lpt` and one of `0-9¹²³`. */
    private fun reserved(s: String): Boolean {
        val l = s.lowercase()
        if (l.length != 3 && l.length != 4) return false
        if (l.length == 3) return l == "con" || l == "prn" || l == "aux" || l == "nul"
        val head = l.substring(0, 3)
        val d = l[3]
        return (head == "com" || head == "lpt") && (d in '0'..'9' || d == '¹' || d == '²' || d == '³')
    }

    /** The name as the rules make it, before the length bound and the clash suffix. */
    private fun clean(name: String): String {
        val normal = nfc(name)
        val safe = StringBuilder(normal.length)
        for (c in normal) safe.append(if (forbidden(c)) '_' else c)
        var n = dropTrailingDots(JsText.trim(safe.toString()))
        if (n.isEmpty()) return "_"
        // what Windows compares against its device names: the part before the first dot, trailing white space dropped
        val dot = n.indexOf('.')
        if (reserved(JsText.trimEnd(if (dot < 0) n else n.substring(0, dot)))) n = "_$n"
        return n
    }

    /** Where [name]'s extension starts — its last dot, not its first character, at most 16 characters before the end — or
     *  the name's length where it has none. */
    private fun extensionAt(name: String): Int {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || name.length - dot > MAX_EXTENSION) name.length else dot
    }

    /** [name] cut to [max] UTF-8 bytes on a code point, its extension kept; no trailing dot or space after the cut. */
    private fun bound(name: String, max: Int): String {
        if (utf8(name) <= max) return name
        val at = extensionAt(name)
        val ext = name.substring(at)
        val room = max - utf8(ext)
        var used = 0
        var end = 0
        while (end < at) {
            val two = name[end].isHighSurrogate() && end + 1 < at && name[end + 1].isLowSurrogate()
            val next = end + if (two) 2 else 1
            val size = utf8(name.substring(end, next))
            if (used + size > room) break
            used += size
            end = next
        }
        val cut = dropTrailingDots(name.substring(0, end))
        return (cut.ifEmpty { "_" }) + ext
    }

    /** Whether [segment] is one path segment these rules could have produced: what a reader accepts and nothing else. */
    fun isName(segment: String): Boolean = segment.isNotEmpty() && utf8(segment) <= SEGMENT_MAX_BYTES && clean(segment) == segment

    /** Whether [path] is a relative path of safe segments that does not reach into the manifest's directory. */
    fun isPath(path: String): Boolean {
        var start = 0
        var first = true
        while (true) {
            val slash = path.indexOf('/', start)
            val segment = if (slash < 0) path.substring(start) else path.substring(start, slash)
            if (first && fold(segment) == MANIFEST_DIR) return false
            if (!isName(segment)) return false
            first = false
            if (slash < 0) return true
            start = slash + 1
        }
    }

    /** How two names of one directory are compared: NFC, ignoring case. */
    fun fold(name: String): String = nfc(name).lowercase()

    /** The safe name of [name] in a directory whose names so far are [taken]; the result joins [taken]. A directory's names
     *  are taken in the order the writer meets them, so the same tree always yields the same paths. */
    fun of(name: String, taken: Taken): String {
        val base = bound(clean(name), MAX_BYTES)
        var candidate = base
        if (taken.has(fold(candidate))) {
            val at = extensionAt(base)
            val stem = base.substring(0, at)
            val ext = base.substring(at)
            var i = 2
            while (taken.has(fold(candidate))) candidate = "$stem (${i++})$ext"
        }
        taken.add(fold(candidate))
        return candidate
    }

    /** [of] over a set the caller keeps (the apps' writers). */
    fun of(name: String, taken: MutableSet<String>): String = of(
        name,
        object : Taken {
            override fun has(folded: String) = folded in taken
            override fun add(folded: String) {
                taken += folded
            }
        },
    )
}
