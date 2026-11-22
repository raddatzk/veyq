package me.raddatz.veyq.mobile.crypto

/**
 * A node name's folding before its blinded slug token HMACs it (inbox #350, ADR 0080), a port of
 * `src/web/src/shared/naming.ts`. **Frozen into tokens:** every stored token is HMAC(slugKey, NFC(fold(name))), so a
 * changed rule stops a stored node from colliding with its own name; both foldings are pinned by `v1.json#name_folding`,
 * and no library folds here, for the same reason.
 */
object Naming {
    /**
     * A container's slug (folders, Memories, chapters, Pots): NFC, lowercase, ä/ö/ü/ß → ae/oe/ue/ss, NFKD, U+0300–U+036F
     * dropped, every run outside `[a-z0-9]` one `-`, one edge `-` trimmed; `n` + base36(FNV-1a-32 over the NFC name's
     * UTF-16 code units) when nothing is left. Spelled as loops, no map and no regular expression: the tree's sibling check
     * folds every sibling's name with it, on the web too.
     */
    fun slugify(name: String): String {
        val composed = nfc(name)
        val lower = composed.lowercase()
        val folded = StringBuilder(lower.length + 4)
        for (c in lower) {
            when (c) {
                'ä' -> folded.append("ae")
                'ö' -> folded.append("oe")
                'ü' -> folded.append("ue")
                'ß' -> folded.append("ss")
                else -> folded.append(c)
            }
        }
        val decomposed = nfkd(folded.toString())
        // every run outside [a-z0-9] one `-` (a combining mark U+0300–U+036F is dropped, never a run), one edge `-` trimmed
        val slug = StringBuilder(decomposed.length)
        var dash = false
        for (c in decomposed) {
            if (c in '\u0300'..'\u036f') continue
            if (c in 'a'..'z' || c in '0'..'9') {
                if (dash && slug.isNotEmpty()) slug.append('-')
                dash = false
                slug.append(c)
            } else {
                dash = true
            }
        }
        return if (slug.isEmpty()) "n" + fnv1a(composed) else slug.toString()
    }

    /** A file's uniqueness slug: NFC, then the locale-free default Unicode lowercasing, nothing else (no trim). */
    fun fileNameSlug(name: String): String = nfc(name).lowercase()

    /** FNV-1a-32 over UTF-16 code units, base36 of the unsigned value (`naming.ts`' `fnv1a`). No `Long`: the tree's rules
     *  reach it from the web's façade, where a `Long` is emulated (ADR 0155 § Consequences). */
    internal fun fnv1a(value: String): String {
        var hash = 0x811c9dc5.toInt()
        for (c in value) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        // the unsigned value, exact in a Double (below 2^53), in base 36 (`UInt.toString(radix)` goes through `Long`)
        var u = hash.toDouble().let { if (it < 0) it + 4294967296.0 else it }
        if (u == 0.0) return "0"
        val digits = StringBuilder()
        while (u > 0) {
            val d = (u % 36).toInt()
            digits.append(if (d < 10) '0' + d else 'a' + (d - 10))
            u = (u - d) / 36
        }
        return digits.reverse().toString()
    }
}
