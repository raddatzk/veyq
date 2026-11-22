package me.raddatz.veyq.mobile.memories.tracks

/**
 * A minimal XML reader of the core's own (ADR 0143 D8), enough for a GPX file and nothing more: elements, attributes,
 * text, CDATA, comments and processing instructions. **A document type declaration is refused before anything is
 * read**, so no entity is ever declared, let alone expanded: only the five predefined entities and character references
 * exist. Namespaces are not resolved — an element is known by its local name (the part after a prefix), which is all
 * GPX 1.0 and 1.1 differ in. Malformed input throws [Malformed]; nothing here reaches outside the text it is handed.
 */
object Xml {
    /** An element: its local name, its attributes by local name, its children in order and its text (concatenated). */
    class Element(val name: String, val attributes: Map<String, String>, val children: List<Element>, val text: String) {
        /** The direct children named [local]. */
        fun children(local: String): List<Element> = children.filter { it.name == local }

        /** The trimmed text of the first child named [local]; null where there is none or it is blank. */
        fun textOf(local: String): String? = children.firstOrNull { it.name == local }?.text?.trim()?.ifEmpty { null }
    }

    /** The text is no well-formed XML this reader takes, or declares a document type. */
    class Malformed(message: String) : Exception(message)

    /** Whether [text] carries a document type or entity declaration anywhere (refused before reading). */
    fun declaresType(text: String): Boolean = DOCTYPE.containsMatchIn(text)

    private val DOCTYPE = Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE)

    /** The document element of [text]; throws [Malformed]. */
    fun parse(text: String): Element {
        if (declaresType(text)) throw Malformed("doctype")
        return Reader(text).document()
    }

    private class Building(val name: String, val attributes: Map<String, String>) {
        val children = ArrayList<Element>()
        val text = StringBuilder()
        fun done() = Element(name, attributes, children, text.toString())
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun document(): Element {
            var root: Element? = null
            val stack = ArrayList<Building>()
            if (s.startsWith("﻿")) i = 1
            while (i < s.length) {
                if (s[i] != '<') {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    val chunk = s.substring(i, end)
                    if (stack.isEmpty()) {
                        if (chunk.isNotBlank()) throw Malformed("text outside the document element")
                    } else stack.last().text.append(unescape(chunk))
                    i = end
                    continue
                }
                when {
                    s.startsWith("<!--", i) -> i = after("-->", i + 4)
                    s.startsWith("<?", i) -> i = after("?>", i + 2)
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9)
                        if (end < 0 || stack.isEmpty()) throw Malformed("cdata")
                        stack.last().text.append(s, i + 9, end)
                        i = end + 3
                    }
                    // any other declaration is passed over unread, its internal subset with it: the one refusal of a
                    // document type is [parse]'s, before a byte is read — this reader declares and expands nothing
                    s.startsWith("<!", i) -> i = declarationEnd(i + 2)
                    s.startsWith("</", i) -> {
                        val end = s.indexOf('>', i)
                        if (end < 0) throw Malformed("end tag")
                        val name = s.substring(i + 2, end).trim()
                        val open = stack.removeLastOrNull() ?: throw Malformed("end tag without a start")
                        if (local(name) != open.name) throw Malformed("mismatched end tag")
                        val element = open.done()
                        if (stack.isEmpty()) {
                            if (root != null) throw Malformed("two document elements")
                            root = element
                        } else stack.last().children += element
                        i = end + 1
                    }
                    else -> {
                        if (root != null) throw Malformed("content after the document element")
                        val (building, empty) = startTag()
                        if (empty) {
                            val element = building.done()
                            if (stack.isEmpty()) root = element else stack.last().children += element
                        } else stack += building
                    }
                }
            }
            if (stack.isNotEmpty()) throw Malformed("unclosed element")
            return root ?: throw Malformed("no document element")
        }

        private fun after(end: String, from: Int): Int {
            val at = s.indexOf(end, from)
            if (at < 0) throw Malformed("unterminated")
            return at + end.length
        }

        /** Past a declaration's `>` from [from], skipping a bracketed internal subset whole. */
        private fun declarationEnd(from: Int): Int {
            var depth = 0
            var j = from
            while (j < s.length) {
                when (s[j]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) return j + 1
                }
                j++
            }
            throw Malformed("unterminated declaration")
        }

        /** A start tag at `<`: the element begun, and whether it closes itself. */
        private fun startTag(): Pair<Building, Boolean> {
            i++ // '<'
            val name = name()
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                skipSpace()
                if (i >= s.length) throw Malformed("unterminated start tag")
                when {
                    s.startsWith("/>", i) -> {
                        i += 2
                        return Building(local(name), attributes) to true
                    }
                    s[i] == '>' -> {
                        i++
                        return Building(local(name), attributes) to false
                    }
                    else -> {
                        val attr = name()
                        skipSpace()
                        if (i >= s.length || s[i] != '=') throw Malformed("attribute without a value")
                        i++
                        skipSpace()
                        val quote = s.getOrNull(i)
                        if (quote != '"' && quote != '\'') throw Malformed("unquoted attribute")
                        val end = s.indexOf(quote, i + 1)
                        if (end < 0) throw Malformed("unterminated attribute")
                        val raw = s.substring(i + 1, end)
                        if ('<' in raw) throw Malformed("< in an attribute")
                        local(attr).let { k -> if (k !in attributes) attributes[k] = unescape(raw) }
                        i = end + 1
                    }
                }
            }
        }

        private fun name(): String {
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
            if (i == start) throw Malformed("name expected")
            return s.substring(start, i)
        }

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    private fun local(name: String) = name.substringAfter(':')

    /** [raw] with the five predefined entities and character references read; any other `&` is malformed. */
    internal fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i)
            if (end < 0) throw Malformed("unterminated reference")
            val ref = raw.substring(i + 1, end)
            when {
                ref == "lt" -> out.append('<')
                ref == "gt" -> out.append('>')
                ref == "amp" -> out.append('&')
                ref == "quot" -> out.append('"')
                ref == "apos" -> out.append('\'')
                ref.startsWith("#x") || ref.startsWith("#X") -> out.appendCode(ref.substring(2).toIntOrNull(16))
                ref.startsWith("#") -> out.appendCode(ref.substring(1).toIntOrNull())
                else -> throw Malformed("unknown entity")
            }
            i = end + 1
        }
        return out.toString()
    }

    private fun StringBuilder.appendCode(code: Int?) {
        if (code == null || code < 1 || code > 0x10FFFF || code in 0xD800..0xDFFF) throw Malformed("bad character reference")
        if (code < 0x10000) append(code.toChar())
        else {
            val v = code - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}
