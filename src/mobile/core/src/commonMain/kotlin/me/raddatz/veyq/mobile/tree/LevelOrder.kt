package me.raddatz.veyq.mobile.tree

import me.raddatz.veyq.mobile.crypto.nfkd

/**
 * The order a level lists its nodes in (inbox #407), one rule for every client: folders first, then every other node mixed
 * by name — a Memory, a file and a drop box among each other, not by kind. Names compare as a person reads them, in no
 * one's locale: case and accents aside, digits as numbers ("Foto 2" before "Foto 10", leading zeros aside), white space,
 * then punctuation, then symbols, then currency signs before digits and letters, in the order of the web's collator
 * (`Intl.Collator(locale, { numeric: true, sensitivity: 'base' })`, ICU 78, which the web used before and which English and
 * German agree on). Names equal so fall to the creation order ([DisplayNames.compareByCreation]: the server's instant,
 * then the id), so the order never depends on how the tree happened to be listed. Held by
 * `contracts/platform/tree/v1-vectors.json`'s `order`.
 *
 * The residual: a character outside the collator's table below sorts by its code unit within its group (a letter of a
 * script the vectors do not reach, a digit outside ASCII, a dotless `ı`), where a browser's collator may place it apart.
 */
object LevelOrder {
    /**
     * The punctuation, symbols and currency signs in the collator's primary order (ICU 78's root order), each its own rank;
     * a character of one of those groups not listed sorts after the group's listed ones, by its code unit — so a group's last
     * characters, which the collator orders by code unit too (`′‵‸※‿⁀⁁⁂`, `~⁄`), and the currency signs between `¥` and `₧`
     * are left out.
     * The quotes the collator reads as one are folded first ([same]).
     */
    private val TABLE =
        "_-‐‒–—―,;⁏:!¡?¿‽.·'‹›\"«»()[]{}⁅⁆‖§¶⁋@*⁎/\\&⁊#%‰‱†‡•‣‧⁃⁌⁍`^°©®+±÷×<=>¬|¦¤¢$£¥₧"
    private val PUNCTUATION_END = rank('`')
    private val SYMBOLS_END = rank('¤')
    private val CURRENCY_END = rank('₧')

    /** [c]'s place in [TABLE], or -1 (a loop: the web's module carries no library search for it). */
    private fun rank(c: Char): Int {
        for (i in TABLE.indices) if (TABLE[i] == c) return i
        return -1
    }

    private const val SPACE = 0x80
    private const val RANKED = 0x100
    private const val DIGITS = 0x1000
    private const val LETTER = 0x2000

    private fun same(c: Char): Char = when (c) {
        '‘', '’', '‚', '‛' -> '\''
        '“', '”', '„', '‟' -> '"'
        else -> c
    }

    /** A letter the collator reads as other base letters (`ß` as `ss`, `æ` as `ae`, `ø` as `o`, …). */
    private fun letters(c: Char): String? = when (c) {
        'ß', 'ẞ' -> "ss"
        'æ' -> "ae"
        'œ' -> "oe"
        'ø' -> "o"
        'ł' -> "l"
        'đ', 'ð' -> "d"
        else -> null
    }

    /** A character's Unicode category by its two-letter code (`Mn`, `Zs`, `Po`, `Sc`, …): compared as text, so the web's
     *  module names no category's enum entry. */
    private fun category(c: Char): String = c.category.code

    /**
     * [name]'s sort key: a string whose UTF-16 order is the rule's order of names. Each character becomes a class and a value
     * (white space by its code unit, a listed character by its rank, an unlisted one after its group by its code unit, a
     * letter by its code unit after folding); a run of ASCII digits becomes the digits' class, the run's length without
     * leading zeros, then those digits — so two numbers compare by value. Case, accents (NFKD, combining marks dropped) and
     * the format characters the collator ignores (a soft hyphen, a direction mark) are gone.
     */
    fun key(name: String): String {
        val decomposed = nfkd(name)
        val folded = StringBuilder(decomposed.length)
        for (c in decomposed) if (c.code < 0x300 || category(c)[0] != 'M') folded.append(c)
        val text = folded.toString().lowercase()
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9') {
                var j = i
                while (j < text.length && text[j] in '0'..'9') j++
                var from = i
                while (from < j - 1 && text[from] == '0') from++
                out.append(DIGITS.toChar()).append((0x30 + j - from).toChar()).append(text, from, j)
                i = j
                continue
            }
            i++
            if ((c in 'a'..'z')) {
                out.append(LETTER.toChar()).append(c)
                continue
            }
            if (c == ' ' || c in '\t'..'\r' || c == ' ' || c == ' ') {
                out.append(SPACE.toChar()).append(c)
                continue
            }
            val rank = rank(same(c))
            if (rank >= 0) {
                out.append((RANKED + 2 * rank).toChar()).append('\u0001')
                continue
            }
            val code = category(c)
            val klass = when {
                code == "Cf" -> continue
                code[0] == 'Z' -> SPACE
                code[0] == 'P' -> RANKED + 2 * PUNCTUATION_END - 1
                // the currency signs the table leaves out sort between `¥` and `₧`, by code unit, as the collator's do
                code == "Sc" -> RANKED + 2 * CURRENCY_END - 1
                // a character outside the basic plane (an emoji) is read per UTF-16 unit, as a symbol
                code[0] == 'S' || code == "Cs" -> RANKED + 2 * SYMBOLS_END - 1
                else -> LETTER
            }
            val l = if (klass == LETTER) letters(c) else null
            if (l != null) for (x in l) out.append(LETTER.toChar()).append(x) else out.append(klass.toChar()).append(c)
        }
        return out.toString()
    }

    /**
     * The level in the order it is drawn: [folders] by name, then [others] by name, every kind mixed; equal names by
     * [DisplayNames.compareByCreation]. Does not change its inputs.
     */
    fun <T> order(folders: List<T>, others: List<T>, id: (T) -> String, name: (T) -> String, createdAt: (T) -> Double?): List<T> {
        val out = ArrayList<T>(folders.size + others.size)
        out.addAll(sorted(folders, id, name, createdAt))
        out.addAll(sorted(others, id, name, createdAt))
        return out
    }

    /** [items] by name, equal names by creation then id. */
    fun <T> sorted(items: List<T>, id: (T) -> String, name: (T) -> String, createdAt: (T) -> Double?): List<T> =
        sortedByKey(items, { key(name(it)) }, id, createdAt)

    /** [items] by [key] (UTF-16 order), equal keys by creation then id ([DisplayNames.compareByCreation]): the one sort of the
     *  tree's rules. */
    internal fun <T> sortedByKey(items: List<T>, key: (T) -> String, id: (T) -> String, createdAt: (T) -> Double?): List<T> {
        val keys = ArrayList<String>(items.size)
        for (item in items) keys.add(key(item))
        val at = ArrayList<Int>(items.size)
        for (i in items.indices) at.add(i)
        val out = ArrayList<T>(items.size)
        for (i in at.sortedWith { a, b ->
            val ka = keys[a]
            val kb = keys[b]
            if (ka != kb) ka.compareTo(kb)
            else DisplayNames.compareByCreation(createdAt(items[a]), id(items[a]), createdAt(items[b]), id(items[b]))
        }) out.add(items[i])
        return out
    }
}
