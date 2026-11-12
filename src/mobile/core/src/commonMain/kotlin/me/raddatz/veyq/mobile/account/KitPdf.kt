package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.blob.inflate

/**
 * The little of a PDF an Emergency Kit's read needs (en.account.key-model § The Emergency Kit, steps (b) and (d)): the
 * document information's `Keywords` and the value of the form field `address1`. Read the way a tolerant reader recovers
 * a broken file — every `N G obj … endobj` in the bytes and every object of every Flate object stream, a later definition
 * of a number winning (an incremental update appends) — and never through the xref table or stream, whose offsets a
 * re-save may have broken. Nothing else of the file is read; nothing here throws: a file it cannot follow answers null.
 */
class KitPdf private constructor(
    private val defs: Map<Int, Any?>,
    private val trailers: List<Map<String, Any?>>,
) {
    /** An `/Encrypt` in a trailer: the file's strings are ciphertext, so neither value is read from it. */
    val encrypted: Boolean = trailers.any { "Encrypt" in it }

    /** The `Keywords` of the document information the last trailer naming one points at. */
    fun keywords(): String? = guard {
        if (encrypted) return@guard null
        val info = resolve(trailers.lastOrNull { "Info" in it }?.get("Info")) as? Map<*, *> ?: return@guard null
        (resolve(info["Keywords"]) as? PdfString)?.text()
    }

    /**
     * The value of the form field whose fully qualified name is [name] (the kit's first address field is `address1`,
     * the name both writers give it), walked from the catalog's `/AcroForm` `/Fields` through `/Kids`.
     */
    fun field(name: String): String? = guard {
        if (encrypted) return@guard null
        val catalog = resolve(trailers.lastOrNull { "Root" in it }?.get("Root")) as? Map<*, *>
            ?: defs.values.lastOrNull { (it as? Map<*, *>)?.get("Type") == PdfName("Catalog") } as? Map<*, *>
            ?: return@guard null
        val form = resolve(catalog["AcroForm"]) as? Map<*, *> ?: return@guard null
        val fields = resolve(form["Fields"]) as? List<*> ?: return@guard null
        val seen = HashSet<Int>()
        var visited = 0
        fun walk(node: Any?, parent: String?): String? {
            if (node is PdfRef && !seen.add(node.num)) return null
            if (++visited > MAX_FIELDS) return null
            val f = resolve(node) as? Map<*, *> ?: return null
            val t = (resolve(f["T"]) as? PdfString)?.text()
            val qualified = if (t == null) parent else if (parent == null) t else "$parent.$t"
            if (qualified == name) (resolve(f["V"]) as? PdfString)?.let { return it.text() }
            for (kid in resolve(f["Kids"]) as? List<*> ?: emptyList<Any?>()) walk(kid, qualified)?.let { return it }
            return null
        }
        fields.firstNotNullOfOrNull { walk(it, null) }
    }

    private fun resolve(x: Any?): Any? {
        var v = x
        var hops = 0
        while (v is PdfRef && hops++ < 32) v = defs[v.num]
        return if (v is PdfRef) null else v
    }

    private inline fun <T> guard(f: () -> T?): T? = try {
        f()
    } catch (_: Exception) {
        null
    }

    companion object {
        /** An object stream that inflates past this is skipped unread. */
        const val INFLATE_CAP = 8 * 1024 * 1024
        private const val MAX_FIELDS = 10_000

        /** [bytes] scanned for its objects; a file that is no PDF answers a reader that finds nothing. */
        fun parse(bytes: ByteArray, inflateCap: Int = INFLATE_CAP): KitPdf {
            val scan = try {
                Scan(bytes).also { it.run(inflateCap) }
            } catch (_: Exception) {
                return KitPdf(emptyMap(), emptyList())
            }
            return KitPdf(scan.defs.mapValues { it.value.value }, scan.trailers.sortedBy { it.first }.map { it.second })
        }
    }

    /** One pass over the file's top level, objects and trailers in their order; then each object stream's objects. */
    private class Scan(private val b: ByteArray) {
        class Def(val at: Int, val value: Any?, val stream: IntRange?)

        val defs = HashMap<Int, Def>()
        val trailers = ArrayList<Pair<Int, Map<String, Any?>>>()

        fun run(inflateCap: Int) {
            val lex = Lexer(b, 0, b.size)
            // The last two integers read at the top level and where each began: `N G obj` names an object.
            var n1 = -1L
            var n2 = -1L
            var n1At = 0
            var n2At = 0
            while (true) {
                lex.skipSpace()
                if (lex.pos >= b.size) break
                val start = lex.pos
                val c = b[start].toInt() and 0xff
                if (c in DIGIT) {
                    val num = lex.number()
                    if (num is Long && num >= 0) {
                        when {
                            n1 < 0 -> { n1 = num; n1At = start }
                            n2 < 0 -> { n2 = num; n2At = start }
                            else -> { n1 = n2; n1At = n2At; n2 = num; n2At = start }
                        }
                    } else {
                        n1 = -1; n2 = -1
                    }
                    if (lex.pos == start) lex.pos++
                    continue
                }
                if (isRegular(c)) {
                    val word = lex.word()
                    when {
                        word == "obj" && n1 >= 0 && n2 >= 0 && n1 <= Int.MAX_VALUE -> obj(lex, n1.toInt(), n1At)
                        word == "trailer" -> (lex.objectOrNull() as? Map<*, *>)?.let { trailers += start to it.strings() }
                    }
                    n1 = -1; n2 = -1
                    continue
                }
                lex.pos++
                n1 = -1; n2 = -1
            }
            // Object streams in file order: an object inside one is that stream's definition, so it loses to a later one.
            for ((_, def) in defs.entries.sortedBy { it.value.at }.toList()) {
                val dict = def.value as? Map<*, *> ?: continue
                if (dict["Type"] != PdfName("ObjStm") || def.stream == null) continue
                objectStream(dict, def, inflateCap)
            }
            for (def in defs.values) {
                val dict = def.value as? Map<*, *> ?: continue
                if (dict["Type"] == PdfName("XRef")) trailers += def.at to dict.strings()
            }
        }

        private fun obj(lex: Lexer, num: Int, at: Int) {
            val value = lex.objectOrNull()
            lex.skipSpace()
            val mark = lex.pos
            var stream: IntRange? = null
            if (lex.pos < b.size && isRegular(b[lex.pos].toInt() and 0xff) && lex.word() == "stream") {
                var from = lex.pos
                if (from < b.size && b[from] == '\r'.code.toByte()) from++
                if (from < b.size && b[from] == '\n'.code.toByte()) from++
                val length = ((value as? Map<*, *>)?.get("Length") as? Long)?.takeIf { it in 0..(b.size - from).toLong() }?.toInt()
                val end = if (length != null && startsWithAfterSpace(from + length, "endstream")) from + length else indexOf("endstream", from)
                if (end < 0) {
                    lex.pos = b.size
                } else {
                    stream = from until end
                    lex.pos = end + "endstream".length
                }
            } else {
                lex.pos = mark
            }
            defs[num] = Def(at, value, stream)
        }

        private fun objectStream(dict: Map<*, *>, def: Def, inflateCap: Int) {
            val filter = dict["Filter"].let { if (it is List<*> && it.size == 1) it[0] else it }
            if (filter != PdfName("FlateDecode")) return
            val count = (dict["N"] as? Long)?.toInt() ?: return
            val first = (dict["First"] as? Long)?.toInt() ?: return
            val range = def.stream ?: return
            val data = inflate(b.copyOfRange(range.first, range.last + 1), inflateCap) ?: return
            if (first !in 0..data.size) return
            val head = Lexer(data, 0, first)
            repeat(count.coerceAtMost(first / 4 + 1)) {
                head.skipSpace()
                val num = head.number() as? Long ?: return
                head.skipSpace()
                val offset = head.number() as? Long ?: return
                if (num !in 0..Int.MAX_VALUE.toLong() || offset !in 0..(data.size - first).toLong()) return
                val held = defs[num.toInt()]
                if (held != null && held.at > def.at) return@repeat
                val value = Lexer(data, first + offset.toInt(), data.size).objectOrNull()
                defs[num.toInt()] = Def(def.at, value, null)
            }
        }

        private fun startsWithAfterSpace(at: Int, word: String): Boolean {
            var i = at
            while (i < b.size && (b[i].toInt() and 0xff) in SPACE) i++
            return matches(i, word)
        }

        private fun matches(at: Int, word: String): Boolean =
            at + word.length <= b.size && word.indices.all { b[at + it] == word[it].code.toByte() }

        private fun indexOf(word: String, from: Int): Int {
            var i = from
            while (i + word.length <= b.size) {
                if (matches(i, word)) return i
                i++
            }
            return -1
        }

        private fun Map<*, *>.strings(): Map<String, Any?> = entries.associate { (k, v) -> k.toString() to v }
    }
}

/** A name, without its slash, `#xx` decoded. */
internal data class PdfName(val name: String)

/** An indirect reference; the generation is not compared (a later definition of the number wins). */
internal data class PdfRef(val num: Int, val gen: Int)

/** A string's bytes, a literal's escapes or a hex string's digits read. */
internal class PdfString(val bytes: ByteArray) {
    /** UTF-16BE with its BOM, UTF-8 with its BOM (PDF 2.0), else PDFDocEncoding read as Latin-1 (the ASCII a token is). */
    fun text(): String = when {
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
            CharArray((bytes.size - 2) / 2) { i -> (((bytes[2 + 2 * i].toInt() and 0xff) shl 8) or (bytes[3 + 2 * i].toInt() and 0xff)).toChar() }.concatToString()
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() ->
            bytes.decodeToString(3, bytes.size)
        else -> CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
    }
}

internal object PdfNull

private val SPACE = setOf(0, 9, 10, 12, 13, 32)
private val DIGIT = ('0'.code..'9'.code).toSet() + setOf('+'.code, '-'.code, '.'.code)
private val DELIMITER = "()<>[]{}/%".map { it.code }.toSet()
private fun isRegular(c: Int) = c !in SPACE && c !in DELIMITER

/** PDF's object syntax over [b] from [start] to [end]: numbers, names, both string forms, arrays, dictionaries, refs. */
private class Lexer(private val b: ByteArray, start: Int, private val end: Int) {
    var pos = start

    private fun at(i: Int) = if (i < end) b[i].toInt() and 0xff else -1

    fun skipSpace() {
        while (pos < end) {
            val c = at(pos)
            if (c == '%'.code) {
                while (pos < end && at(pos) != 10 && at(pos) != 13) pos++
            } else if (c in SPACE) {
                pos++
            } else {
                return
            }
        }
    }

    fun word(): String {
        val s = pos
        while (pos < end && isRegular(at(pos))) pos++
        return latin1(s, pos)
    }

    /** A Long for an integer, a Double for a real, null for a word that is no number (the position moved past it). */
    fun number(): Any? {
        val w = word()
        return w.toLongOrNull() ?: w.toDoubleOrNull()
    }

    /** The object at the position, or null where the syntax is broken (the position then somewhere past it). */
    fun objectOrNull(): Any? = try {
        value(0)
    } catch (_: Broken) {
        null
    }

    private class Broken : Exception()

    private fun value(depth: Int): Any? {
        if (depth > 64) throw Broken()
        skipSpace()
        val c = at(pos)
        return when {
            c < 0 -> throw Broken()
            c == '<'.code && at(pos + 1) == '<'.code -> dictionary(depth)
            c == '<'.code -> hex()
            c == '('.code -> literal()
            c == '['.code -> array(depth)
            c == '/'.code -> name()
            c in DIGIT -> numberOrRef()
            isRegular(c) -> when (word()) {
                "true" -> true
                "false" -> false
                "null" -> PdfNull
                else -> throw Broken()
            }
            else -> throw Broken()
        }
    }

    private fun dictionary(depth: Int): Map<String, Any?> {
        pos += 2
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            skipSpace()
            if (at(pos) == '>'.code && at(pos + 1) == '>'.code) {
                pos += 2
                return map
            }
            if (at(pos) != '/'.code) throw Broken()
            val key = name().name
            map[key] = value(depth + 1)
        }
    }

    private fun array(depth: Int): List<Any?> {
        pos++
        val list = ArrayList<Any?>()
        while (true) {
            skipSpace()
            if (at(pos) == ']'.code) {
                pos++
                return list
            }
            list += value(depth + 1)
        }
    }

    private fun name(): PdfName {
        pos++
        val out = StringBuilder()
        while (pos < end && isRegular(at(pos))) {
            val c = at(pos)
            val h1 = hexDigit(at(pos + 1))
            val h2 = hexDigit(at(pos + 2))
            if (c == '#'.code && h1 >= 0 && h2 >= 0) {
                out.append(((h1 shl 4) or h2).toChar())
                pos += 3
            } else {
                out.append(c.toChar())
                pos++
            }
        }
        return PdfName(out.toString())
    }

    private fun numberOrRef(): Any? {
        val first = number() ?: throw Broken()
        if (first !is Long) return first
        // `N G R`: look ahead without consuming unless all three are there.
        val mark = pos
        skipSpace()
        if (at(pos) in '0'.code..'9'.code) {
            val second = number()
            skipSpace()
            if (second is Long && at(pos) == 'R'.code && !isRegular(at(pos + 1)) && first in 0..Int.MAX_VALUE.toLong()) {
                pos++
                return PdfRef(first.toInt(), second.toInt())
            }
        }
        pos = mark
        return first
    }

    private fun hex(): PdfString {
        pos++
        val out = ArrayList<Byte>()
        var high = -1
        while (true) {
            val c = at(pos++)
            if (c < 0) throw Broken()
            if (c == '>'.code) break
            if (c in SPACE) continue
            val d = hexDigit(c)
            if (d < 0) throw Broken()
            if (high < 0) {
                high = d
            } else {
                out += ((high shl 4) or d).toByte()
                high = -1
            }
        }
        if (high >= 0) out += (high shl 4).toByte() // an odd count: the last digit padded with 0
        return PdfString(out.toByteArray())
    }

    private fun literal(): PdfString {
        pos++
        val out = ArrayList<Byte>()
        var depth = 1
        while (true) {
            val c = at(pos++)
            when (c) {
                -1 -> throw Broken()
                '('.code -> depth++
                ')'.code -> if (--depth == 0) return PdfString(out.toByteArray())
                13 -> { // an end of line in a literal reads as one \n, whatever it was written as
                    if (at(pos) == 10) pos++
                    out += 10
                    continue
                }
                '\\'.code -> {
                    val e = at(pos++)
                    when (e) {
                        'n'.code -> out += 10
                        'r'.code -> out += 13
                        't'.code -> out += 9
                        'b'.code -> out += 8
                        'f'.code -> out += 12
                        '('.code, ')'.code, '\\'.code -> out += e.toByte()
                        13 -> if (at(pos) == 10) pos++ // a continuation: the backslash and its end of line vanish
                        10 -> Unit
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val o = at(pos)
                                if (o in '0'.code..'7'.code) {
                                    v = v * 8 + (o - '0'.code)
                                    pos++
                                }
                            }
                            out += v.toByte()
                        }
                        -1 -> throw Broken()
                        else -> out += e.toByte() // an unknown escape: the backslash is dropped
                    }
                    continue
                }
            }
            out += c.toByte()
        }
    }

    private fun hexDigit(c: Int) = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    private fun latin1(from: Int, to: Int) = CharArray(to - from) { (b[from + it].toInt() and 0xff).toChar() }.concatToString()
}
