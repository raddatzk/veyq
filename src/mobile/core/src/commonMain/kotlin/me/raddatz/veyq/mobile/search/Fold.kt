package me.raddatz.veyq.mobile.search

import me.raddatz.veyq.mobile.crypto.nfd

/**
 * Text compared as a person reads it, case and accents aside (ADR 0125 decision 4; ported from the web for ADR 0146
 * decision 3, and since ADR 0155 the web's own through `@veyq/core-js/search`): "Zoë", "ZOE" and "zoe" are the same letters, and so are "Straße" and "STRASSE". One rule for
 * the search and the participants picker, as on the web, held to `contracts/platform/search/v1-vectors.json`'s `fold`.
 *
 * **Not a slug** (`crypto/Naming.slugify` is frozen into stored tokens); this one is for comparing in memory only.
 *
 * Strings are Kotlin's, which are UTF-16 as JavaScript's are, so an index here is an index there. A combining mark outside
 * the basic plane is kept (the Kotlin common library reads a category per UTF-16 unit): a residual the vectors do not reach.
 */
object Fold {
    /** [s] decomposed (NFD), its combining marks (Mn, Mc, Me) dropped, lower-cased, `ß` as `ss` (the web's `fold`). */
    fun fold(s: String): String {
        val decomposed = nfd(s)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) if (!isMark(c)) out.append(c)
        return out.toString().lowercase().replace("ß", "ss")
    }

    /** Where each character of a fold came from in the original (the web's `foldMap`): see [foldMap]. */
    class FoldMap(val folded: String, val start: IntArray, val end: IntArray)

    /**
     * Where each character of `fold(s)` came from in [s], for drawing a match found in the folded text over the original (the
     * bold part): `start[i]` and `end[i]` bound, in [s], the character that folded to the i-th folded one; a mark that folds
     * to nothing belongs to the character before it. Null where folding character by character does not give `fold(s)` (a
     * letter whose lower case depends on its neighbours): the caller then draws no match.
     */
    fun foldMap(s: String): FoldMap? {
        val folded = StringBuilder()
        val start = ArrayList<Int>()
        val end = ArrayList<Int>()
        var at = 0
        while (at < s.length) {
            val width = if (s[at].isHighSurrogate() && at + 1 < s.length && s[at + 1].isLowSurrogate()) 2 else 1
            val f = fold(s.substring(at, at + width))
            repeat(f.length) {
                start += at
                end += at + width
            }
            if (f.isEmpty() && end.isNotEmpty()) end[end.size - 1] = at + width
            folded.append(f)
            at += width
        }
        val text = folded.toString()
        return if (text == fold(s)) FoldMap(text, start.toIntArray(), end.toIntArray()) else null
    }

    private fun isMark(c: Char): Boolean = when (c.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }
}

/**
 * JavaScript's character classes as the web's search reads them (ADR 0146 decision 2: no regular expression in the text
 * rules — the JVM's `\s` is ASCII, Kotlin's `trim` and `isWhitespace` read other sets). Each is named after the class it
 * spells; the vectors' `tails` and `wordPrefix` hold the cases where the sets differ.
 */
internal object JsText {
    /** JavaScript's `\s`: the space separators (Zs), tab, line feed, vertical tab, form feed, carriage return, U+FEFF and the
     *  line and paragraph separators — and nothing else (not U+0085). */
    fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\uFEFF', '\u2028', '\u2029' -> true
        else -> c.category == CharCategory.SPACE_SEPARATOR
    }

    /** JavaScript's `\d`: an ASCII digit only. */
    fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /** JavaScript's `[\p{L}\p{N}]` over one UTF-16 unit (the web tests `s[i - 1]`, a unit; a surrogate is neither). */
    fun isLetterOrNumber(c: Char): Boolean = when (c.category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER, CharCategory.MODIFIER_LETTER,
        CharCategory.OTHER_LETTER, CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
        -> true
        else -> false
    }

    /** JavaScript's `trim()`. */
    fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isJsSpace(s[a])) a++
        while (b > a && isJsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** JavaScript's `trimEnd()`. */
    fun trimEnd(s: String): String {
        var b = s.length
        while (b > 0 && isJsSpace(s[b - 1])) b--
        return s.substring(0, b)
    }

    /** `s.split(/\s+/).filter(Boolean)`: the runs of non-space. */
    fun words(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && isJsSpace(s[i])) i++
            val from = i
            while (i < s.length && !isJsSpace(s[i])) i++
            if (i > from) out += s.substring(from, i)
        }
        return out
    }

    /** `s.replace(/\s+/g, ' ')`: every run of space as one space. */
    fun collapseSpaces(s: String): String {
        val out = StringBuilder(s.length)
        var inSpace = false
        for (c in s) {
            if (isJsSpace(c)) {
                if (!inSpace) out.append(' ')
                inSpace = true
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString()
    }
}
