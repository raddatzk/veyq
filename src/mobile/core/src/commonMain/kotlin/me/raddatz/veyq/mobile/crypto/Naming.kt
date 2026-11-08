package me.raddatz.veyq.mobile.crypto

/**
 * A node name's folding before its blinded slug token HMACs it (inbox #350, ADR 0080), a port of
 * `src/web/src/shared/naming.ts`. **Frozen into tokens:** every stored token is HMAC(slugKey, NFC(fold(name))), so a
 * changed rule stops a stored node from colliding with its own name; both foldings are pinned by `v1.json#name_folding`,
 * and no library folds here, for the same reason.
 */
object Naming {
    private val GERMAN = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    /**
     * A container's slug (folders, Memories, chapters, Pots): NFC, lowercase, ä/ö/ü/ß → ae/oe/ue/ss, NFKD, U+0300–U+036F
     * dropped, every run outside `[a-z0-9]` one `-`, one edge `-` trimmed; `n` + base36(FNV-1a-32 over the NFC name's
     * UTF-16 code units) when nothing is left.
     */
    fun slugify(name: String): String {
        val composed = nfc(name)
        val folded = buildString { for (c in composed.lowercase()) append(GERMAN[c] ?: c) }
        val stripped = nfkd(folded).filterNot { it in '\u0300'..'\u036f' }
        val slug = stripped.replace(Regex("[^a-z0-9]+"), "-").removePrefix("-").removeSuffix("-")
        return slug.ifEmpty { "n" + fnv1a(composed) }
    }

    /** A file's uniqueness slug: NFC, then the locale-free default Unicode lowercasing, nothing else (no trim). */
    fun fileNameSlug(name: String): String = nfc(name).lowercase()

    /** FNV-1a-32 over UTF-16 code units, base36 of the unsigned value (`naming.ts`' `fnv1a`). */
    internal fun fnv1a(value: String): String {
        var hash = 0x811c9dc5.toInt()
        for (c in value) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return (hash.toLong() and 0xffffffffL).toString(36)
    }
}
