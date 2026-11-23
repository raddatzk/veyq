package me.raddatz.veyq.mobile.media

/**
 * An XMP packet's properties [ExifTags] names, scanned by hand (no XML parser, no `Regex`): as the web read them through the
 * ExifReader library over the browser's `DOMParser` until ADR 0155's media headers' slice. A property is found by its
 * **local name**, whatever its prefix or namespace (the library's reading), as an attribute (`xmp:Rating="4"`) or an
 * element; the last occurrence in the packet wins. An element's value is its text, or — holding an `rdf:Alt`, `rdf:Bag` or
 * `rdf:Seq` — the `x-default` item's, else the first item's; an item or element holding markup (a structure, a CDATA
 * section) is no value. A packet that is not well-formed — unbalanced tags, an attribute out of shape, an unknown entity —
 * answers nothing, as the parser refused it.
 */
internal object Xmp {
    private val NAMES = arrayOf("DateTimeOriginal", "DateCreated", "CreateDate", "CreatorTool", "Rating", "title", "description")

    /** [packet]'s properties into [tags]. */
    fun read(packet: String, tags: ExifTags) {
        val values = scan(packet) ?: return
        tags.xmpCapturedAtMs = ExifReader.xmpClock(values[0]) ?: ExifReader.xmpClock(values[1]) ?: ExifReader.xmpClock(values[2])
        tags.xmpCreatorTool = values[3]
        tags.xmpRating = ExifReader.jsNumber(values[4])
        tags.xmpTitle = values[5]
        tags.xmpDescription = values[6]
    }

    /** The value of the property whose local name is [name] in [packet], or null. */
    fun value(packet: String, name: String): String? = scan(packet)?.get(NAMES.indexOf(name))

    /** Each of [NAMES]' last value in [packet], or null where the packet is not well-formed. */
    private fun scan(packet: String): Array<String?>? {
        val values = arrayOfNulls<String>(NAMES.size)
        val open = ArrayList<String>()
        var i = 0
        val n = packet.length
        while (true) {
            val lt = packet.indexOf('<', i)
            if (lt < 0) break
            if (unescape(packet, i, lt) == null) return null // an unknown entity in text
            when {
                packet.startsWith("<?", lt) -> i = end(packet, lt, "?>") ?: return null
                packet.startsWith("<!--", lt) -> i = end(packet, lt, "-->") ?: return null
                packet.startsWith("<![CDATA[", lt) -> i = end(packet, lt, "]]>") ?: return null
                packet.startsWith("<!", lt) -> i = end(packet, lt, ">") ?: return null
                packet.startsWith("</", lt) -> {
                    var at = lt + 2
                    val nameEnd = nameEnd(packet, at)
                    val name = packet.substring(at, nameEnd)
                    at = spaces(packet, nameEnd)
                    if (at >= n || packet[at] != '>' || open.isEmpty() || open.removeAt(open.size - 1) != name) return null
                    i = at + 1
                }
                else -> {
                    var at = lt + 1
                    val nameEnd = nameEnd(packet, at)
                    if (nameEnd == at) return null
                    val name = packet.substring(at, nameEnd)
                    at = nameEnd
                    // attributes: name = "value" or 'value', separated by white space
                    while (true) {
                        val before = at
                        at = spaces(packet, at)
                        if (at >= n) return null
                        if (packet[at] == '>' || packet.startsWith("/>", at)) break
                        if (at == before) return null
                        val attrEnd = nameEnd(packet, at)
                        if (attrEnd == at) return null
                        val attr = packet.substring(at, attrEnd)
                        at = spaces(packet, attrEnd)
                        if (at >= n || packet[at] != '=') return null
                        at = spaces(packet, at + 1)
                        if (at >= n || (packet[at] != '"' && packet[at] != '\'')) return null
                        val close = packet.indexOf(packet[at], at + 1)
                        if (close < 0) return null
                        val raw = unescape(packet, at + 1, close) ?: return null
                        if (packet.substring(at + 1, close).indexOf('<') >= 0) return null
                        if (attr != "xmlns" && !attr.startsWith("xmlns:")) {
                            val k = NAMES.indexOf(local(attr))
                            if (k >= 0) values[k] = raw
                        }
                        at = close + 1
                    }
                    val selfClosing = packet[at] == '/'
                    i = at + if (selfClosing) 2 else 1
                    val k = NAMES.indexOf(local(name))
                    if (selfClosing) {
                        if (k >= 0) values[k] = null
                    } else {
                        open.add(name)
                        if (k >= 0) values[k] = elementValue(packet, i, name)
                    }
                }
            }
        }
        if (unescape(packet, i, n) == null || open.isNotEmpty()) return null
        return values
    }

    /** The value of the element [name] whose content starts at [from]: its text, or its list's chosen item's. */
    private fun elementValue(packet: String, from: Int, name: String): String? {
        val to = closing(packet, from, name) ?: return null
        val content = packet.substring(from, to)
        if (content.indexOf('<') < 0) return unescape(content, 0, content.length)
        // the items of an rdf:Alt, Bag or Seq: the x-default one, else the first
        var first: String? = null
        var firstSeen = false
        var at = 0
        while (true) {
            val lt = content.indexOf('<', at)
            if (lt < 0) break
            val nameEnd = nameEnd(content, lt + 1)
            val tag = content.substring(lt + 1, nameEnd)
            if (tag.isEmpty() || local(tag) != "li") {
                at = lt + 1
                continue
            }
            val gt = content.indexOf('>', nameEnd)
            if (gt < 0) return null
            if (content[gt - 1] == '/') {
                at = gt + 1
                continue
            }
            val attrs = content.substring(nameEnd, gt)
            val itemEnd = closing(content, gt + 1, tag) ?: return null
            val item = content.substring(gt + 1, itemEnd)
            val text = if (item.indexOf('<') >= 0) null else unescape(item, 0, item.length)
            if (attrs.contains("\"x-default\"") || attrs.contains("'x-default'")) return text
            if (!firstSeen) {
                first = text
                firstSeen = true
            }
            at = itemEnd
        }
        return first
    }

    /** Where the first `</name>` (white space allowed before its `>`) at or after [from] starts, or null. */
    private fun closing(s: String, from: Int, name: String): Int? {
        var at = from
        while (true) {
            val lt = s.indexOf("</$name", at)
            if (lt < 0) return null
            val after = spaces(s, lt + 2 + name.length)
            if (after < s.length && s[after] == '>') return lt
            at = lt + 1
        }
    }

    private fun local(qname: String): String = qname.substring(qname.lastIndexOf(':') + 1)

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    private fun spaces(s: String, from: Int): Int {
        var at = from
        while (at < s.length && isSpace(s[at])) at++
        return at
    }

    /** Where an XML name starting at [from] ends. */
    private fun nameEnd(s: String, from: Int): Int {
        var at = from
        while (at < s.length) {
            val c = s[at]
            if (isSpace(c) || c == '>' || c == '/' || c == '=' || c == '<' || c == '"' || c == '\'') break
            at++
        }
        return at
    }

    /** After the [close] that ends the markup starting at [from], or null where it never does. */
    private fun end(s: String, from: Int, close: String): Int? = s.indexOf(close, from).let { if (it < 0) null else it + close.length }

    /** `[from, to)` of [s] with its entities and character references undone; null for an unknown or broken one. */
    private fun unescape(s: String, from: Int, to: Int): String? {
        val amp = s.indexOf('&', from)
        if (amp < 0 || amp >= to) return s.substring(from, to)
        val out = StringBuilder()
        var at = from
        while (at < to) {
            val c = s[at]
            if (c != '&') {
                out.append(c)
                at++
                continue
            }
            val semi = s.indexOf(';', at)
            if (semi < 0 || semi >= to) return null
            val e = s.substring(at + 1, semi)
            when (e) {
                "amp" -> out.append('&')
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> {
                    val code = when {
                        e.startsWith("#x") -> e.substring(2).toIntOrNull(16)
                        e.startsWith("#") -> e.substring(1).toIntOrNull()
                        else -> null
                    } ?: return null
                    if (code < 0 || code > 0x10FFFF) return null
                    if (code < 0x10000) out.append(code.toChar())
                    else {
                        val v = code - 0x10000
                        out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
                    }
                }
            }
            at = semi + 1
        }
        return out.toString()
    }
}
