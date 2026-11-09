package me.raddatz.veyq.mobile.media

/** A file read at any offset: its size, and its bytes `[from, from + length)` — fewer only at its end. */
class ByteSource(val size: Long, val read: suspend (from: Long, length: Int) -> ByteArray)

/**
 * A photo's metadata tags, read on the device from its bytes (the web's `shared/image/exifTags.ts` [ExifTags], which reads
 * them through ExifReader): the tags by their standard names, each already undone — a rational is a number, an ASCII tag a
 * string (the first of several), a date epoch ms read in the device's zone ([localWallClock]), the GPS position signed
 * decimal degrees (both or neither). A tag the file does not carry, or carries unreadably, is null.
 */
class ExifTags internal constructor() {
    var make: String? = null; internal set
    var model: String? = null; internal set
    /** 1..8, raw (6 = rotate 90° clockwise). */
    var orientation: Double? = null; internal set
    var software: String? = null; internal set
    var artist: String? = null; internal set
    var copyright: String? = null; internal set
    var rating: Double? = null; internal set
    var imageDescription: String? = null; internal set
    var dateTime: Long? = null; internal set
    var imageWidth: Double? = null; internal set
    var imageLength: Double? = null; internal set
    var exposureTime: Double? = null; internal set
    var fNumber: Double? = null; internal set
    var isoSpeedRatings: Double? = null; internal set
    var dateTimeOriginal: Long? = null; internal set
    var dateTimeDigitized: Long? = null; internal set
    var exposureBiasValue: Double? = null; internal set
    /** The raw bitfield: bit 0 is "fired". */
    var flash: Double? = null; internal set
    var focalLength: Double? = null; internal set
    var focalLengthIn35mmFilm: Double? = null; internal set
    var pixelXDimension: Double? = null; internal set
    var pixelYDimension: Double? = null; internal set
    var lensMake: String? = null; internal set
    var lensModel: String? = null; internal set
    var gpsAltitude: Double? = null; internal set
    var gpsAltitudeRef: Double? = null; internal set
    var gpsImgDirection: Double? = null; internal set
    var gpsImgDirectionRef: String? = null; internal set
    var gpsHPositioningError: Double? = null; internal set
    var latitude: Double? = null; internal set
    var longitude: Double? = null; internal set
    /** XMP's capture time: the first of `exif:DateTimeOriginal`, `photoshop:DateCreated`, `xmp:CreateDate`, a wall clock. */
    var xmpCapturedAt: Long? = null; internal set
    var xmpCreatorTool: String? = null; internal set
    var xmpRating: Double? = null; internal set
    var xmpTitle: String? = null; internal set
    var xmpDescription: String? = null; internal set
    var iptcObjectName: String? = null; internal set

    internal fun isEmpty(): Boolean = listOf<Any?>(
        make, model, orientation, software, artist, copyright, rating, imageDescription, dateTime, imageWidth, imageLength,
        exposureTime, fNumber, isoSpeedRatings, dateTimeOriginal, dateTimeDigitized, exposureBiasValue, flash, focalLength,
        focalLengthIn35mmFilm, pixelXDimension, pixelYDimension, lensMake, lensModel, gpsAltitude, gpsAltitudeRef,
        gpsImgDirection, gpsImgDirectionRef, gpsHPositioningError, latitude, longitude, xmpCapturedAt, xmpCreatorTool,
        xmpRating, xmpTitle, xmpDescription, iptcObjectName,
    ).all { it == null }
}

/**
 * Reads [ExifTags] from a photo's container (what the web's ExifReader reads, `exifTags.ts`): a JPEG's `APP1` Exif and
 * XMP and its `APP13` IPTC object name; a bare TIFF (its first [TIFF_PREFIX]); a PNG's `eXIf` and uncompressed XMP `iTXt`
 * and a WebP's `EXIF` and `XMP ` (each within its first [CHUNKED_PREFIX], as the web reads them); a HEIC's or an AVIF's
 * `Exif` item through its `meta` box. Only the tags [ExifTags] names are read. Never the whole file: a JPEG's segments and
 * a HEIF's `meta` are read by their headers, at most [SLICED_MAX] in all. A container this does not know answers null, and
 * so does a damaged one past what it read.
 */
object ExifReader {
    const val CHUNKED_PREFIX = 512 * 1024
    const val TIFF_PREFIX = 1024 * 1024
    const val SLICED_MAX = 4 * 1024 * 1024

    /** [source]'s tags, or null where it carries none this reads. */
    suspend fun read(source: ByteSource): ExifTags? {
        val head = source.read(0, 12)
        if (head.size < 12) return null
        val tags = ExifTags()
        runCatching {
            when {
                isTiff(head, 0) -> tiff(source.read(0, minOf(source.size, TIFF_PREFIX.toLong()).toInt()), 0, tags)
                head[0] == 0x89.toByte() && ascii(head, 1, "PNG") -> png(source.read(0, minOf(source.size, CHUNKED_PREFIX.toLong()).toInt()), tags)
                ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP") -> webp(source.read(0, minOf(source.size, CHUNKED_PREFIX.toLong()).toInt()), tags)
                u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 -> jpeg(source, tags)
                ascii(head, 4, "ftyp") -> heif(source, tags)
                else -> return null
            }
        }
        return tags.takeUnless { it.isEmpty() }
    }

    // --- containers ------------------------------------------------------------------------------------------------

    private suspend fun jpeg(source: ByteSource, tags: ExifTags) {
        var at = 2L
        var budget = SLICED_MAX.toLong()
        while (at + 4 <= source.size) {
            val h = source.read(at, 4)
            if (h.size < 4 || u8(h, 0) != 0xFF) return
            val marker = u8(h, 1)
            when {
                marker == 0xFF -> { at += 1; continue } // fill byte
                marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> { at += 2; continue }
                marker == 0xDA || marker == 0xD9 -> return // the image data, or its end: no metadata follows
            }
            val length = (u8(h, 2) shl 8) or u8(h, 3)
            if (length < 2) return
            if (marker == 0xE1 || marker == 0xED) {
                budget -= length
                if (budget < 0) return
                val body = source.read(at + 4, length - 2)
                when {
                    marker == 0xE1 && ascii(body, 0, "Exif\u0000\u0000") -> tiff(body, 6, tags)
                    marker == 0xE1 && ascii(body, 0, XMP_JPEG) -> xmp(body.decodeToString(XMP_JPEG.length, body.size), tags)
                    marker == 0xED && ascii(body, 0, "Photoshop 3.0\u0000") -> photoshop(body, 14, tags)
                }
            }
            at += 2 + length
        }
    }

    private fun png(b: ByteArray, tags: ExifTags) {
        var at = 8
        while (at + 8 <= b.size) {
            val length = u32be(b, at)
            val type = fourcc(b, at + 4)
            val body = at + 8
            if (length < 0 || body + length > b.size) return
            when (type) {
                "eXIf" -> tiff(b.copyOfRange(body, body + length.toInt()), 0, tags)
                "iTXt" -> {
                    val keyEnd = indexOf(b, 0, body, body + length.toInt())
                    if (keyEnd > 0 && b.decodeToString(body, keyEnd) == "XML:com.adobe.xmp" && keyEnd + 2 < body + length && b[keyEnd + 1].toInt() == 0) {
                        // keyword, NUL, compression flag (0: none), method, language tag NUL, translated keyword NUL, text
                        val lang = indexOf(b, 0, keyEnd + 3, body + length.toInt())
                        val translated = if (lang < 0) -1 else indexOf(b, 0, lang + 1, body + length.toInt())
                        if (translated >= 0) xmp(b.decodeToString(translated + 1, body + length.toInt()), tags)
                    }
                }
                "IEND" -> return
            }
            at = body + length.toInt() + 4
        }
    }

    private fun webp(b: ByteArray, tags: ExifTags) {
        var at = 12
        while (at + 8 <= b.size) {
            val type = fourcc(b, at)
            val length = u32le(b, at + 4)
            val body = at + 8
            if (length < 0 || body + length > b.size) return
            when (type) {
                "EXIF" -> tiff(b, if (ascii(b, body, "Exif\u0000\u0000")) body + 6 else body, tags)
                "XMP " -> xmp(b.decodeToString(body, body + length.toInt()), tags)
            }
            at = body + length.toInt() + (length.toInt() and 1)
        }
    }

    /** A HEIC's or an AVIF's `Exif` item: the top-level `meta` box's `iinf` names it, its `iloc` places it. */
    private suspend fun heif(source: ByteSource, tags: ExifTags) {
        var at = 0L
        var visits = 0
        var meta: Pair<Long, Long>? = null
        while (at + 8 <= source.size && visits++ < 64) {
            val h = source.read(at, 16)
            if (h.size < 8) return
            var size = u32be(h, 0)
            var body = at + 8
            if (size == 1L) {
                if (h.size < 16) return
                size = (u32be(h, 8) shl 32) or u32be(h, 12)
                body = at + 16
            } else if (size == 0L) size = source.size - at
            if (size < body - at) return
            if (fourcc(h, 4) == "meta") {
                meta = body to at + size
                break
            }
            at += size
        }
        val (from, end) = meta ?: return
        if (end - from > SLICED_MAX) return
        val m = source.read(from, (end - from).toInt())
        // a full box: version and flags, then its children
        var exifId = -1L
        val locations = HashMap<Long, List<Pair<Long, Long>>>()
        eachBox(m, 4, m.size) { type, b0, b1 ->
            when (type) {
                "iinf" -> exifId = exifItem(m, b0, b1)
                "iloc" -> locations.putAll(itemLocations(m, b0, b1))
            }
        }
        val extents = locations[exifId] ?: return
        val total = extents.sumOf { it.second }
        if (exifId < 0 || total <= 4 || total > SLICED_MAX) return
        val data = ByteArray(total.toInt())
        var o = 0
        for ((offset, length) in extents) {
            source.read(offset, length.toInt()).copyInto(data, o)
            o += length.toInt()
        }
        val tiffAt = 4 + u32be(data, 0)
        if (tiffAt < data.size) tiff(data, tiffAt.toInt(), tags)
    }

    private inline fun eachBox(b: ByteArray, from: Int, end: Int, visit: (String, Int, Int) -> Unit) {
        var at = from
        var visits = 0
        while (at + 8 <= end && visits++ < 4096) {
            val size = u32be(b, at)
            if (size < 8 || at + size > end) return
            visit(fourcc(b, at + 4), at + 8, (at + size).toInt())
            at += size.toInt()
        }
    }

    /** The id of the `Exif` item an `iinf` lists, or -1. */
    private fun exifItem(b: ByteArray, body: Int, end: Int): Long {
        val version = u8(b, body)
        var found = -1L
        eachBox(b, body + 4 + (if (version == 0) 2 else 4), end) { type, b0, _ ->
            if (type != "infe") return@eachBox
            val v = u8(b, b0)
            if (v < 2) return@eachBox
            val id = if (v == 2) u16be(b, b0 + 4).toLong() else u32be(b, b0 + 4)
            val typeAt = b0 + 4 + (if (v == 2) 2 else 4) + 2
            if (found < 0 && fourcc(b, typeAt) == "Exif") found = id
        }
        return found
    }

    /** Each item's extents in the file (offset, length), for the items stored in the file itself (construction 0). */
    private fun itemLocations(b: ByteArray, body: Int, end: Int): Map<Long, List<Pair<Long, Long>>> {
        val version = u8(b, body)
        var at = body + 4
        val offsetSize = u8(b, at) shr 4
        val lengthSize = u8(b, at) and 0xF
        val baseSize = u8(b, at + 1) shr 4
        val indexSize = if (version == 1 || version == 2) u8(b, at + 1) and 0xF else 0
        at += 2
        val count = if (version < 2) u16be(b, at).toLong().also { at += 2 } else u32be(b, at).also { at += 4 }
        fun sized(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or u8(b, at + it).toLong() }
            at += n
            return v
        }
        val out = HashMap<Long, List<Pair<Long, Long>>>()
        for (i in 0 until minOf(count, 4096L)) {
            if (at >= end) break
            val id = if (version < 2) sized(2) else sized(4)
            val construction = if (version == 1 || version == 2) (sized(2) and 0xF).toInt() else 0
            sized(2) // data reference index
            val base = sized(baseSize)
            val extents = sized(2).toInt()
            val list = ArrayList<Pair<Long, Long>>()
            repeat(extents) {
                if (indexSize > 0) sized(indexSize)
                val offset = sized(offsetSize)
                val length = sized(lengthSize)
                list += (base + offset) to length
            }
            if (construction == 0) out[id] = list
        }
        return out
    }

    /** A Photoshop `APP13`'s image resources: the IPTC block (`0x0404`) carries the object name (record 2, dataset 5). */
    private fun photoshop(b: ByteArray, from: Int, tags: ExifTags) {
        var at = from
        while (at + 12 <= b.size && ascii(b, at, "8BIM")) {
            val id = u16be(b, at + 4)
            val nameLength = u8(b, at + 6)
            val sizeAt = at + 6 + ((nameLength + 2) and 1.inv()) // the Pascal name padded to an even length
            if (sizeAt + 4 > b.size) return
            val size = u32be(b, sizeAt).toInt()
            val data = sizeAt + 4
            if (size < 0 || data + size > b.size) return
            if (id == 0x0404) iptc(b, data, data + size, tags)
            at = data + size + (size and 1)
        }
    }

    private fun iptc(b: ByteArray, from: Int, end: Int, tags: ExifTags) {
        var at = from
        while (at + 5 <= end && u8(b, at) == 0x1C) {
            val record = u8(b, at + 1)
            val dataset = u8(b, at + 2)
            val length = u16be(b, at + 3)
            if (length and 0x8000 != 0 || at + 5 + length > end) return
            if (record == 2 && dataset == 5 && tags.iptcObjectName == null) tags.iptcObjectName = text(b, at + 5, at + 5 + length)
            at += 5 + length
        }
    }

    // --- TIFF ------------------------------------------------------------------------------------------------------

    private fun isTiff(b: ByteArray, at: Int) =
        (ascii(b, at, "II") && u8(b, at + 2) == 0x2A && u8(b, at + 3) == 0) || (ascii(b, at, "MM") && u8(b, at + 2) == 0 && u8(b, at + 3) == 0x2A)

    /** A TIFF header at [base] of [b]: IFD0, then the Exif IFD and the GPS IFD it points at. Offsets count from [base]. */
    private fun tiff(b: ByteArray, base: Int, tags: ExifTags) {
        if (!isTiff(b, base)) return
        val t = Tiff(b, base, u8(b, base) == 'I'.code)
        val ifd0 = t.ifd(t.u32(4)) ?: return
        tags.make = ifd0[0x010F]?.text()
        tags.model = ifd0[0x0110]?.text()
        tags.orientation = ifd0[0x0112]?.number()
        tags.software = ifd0[0x0131]?.text()
        tags.artist = ifd0[0x013B]?.text()
        tags.copyright = ifd0[0x8298]?.text()
        tags.rating = ifd0[0x4746]?.number()
        tags.imageDescription = ifd0[0x010E]?.text()
        tags.dateTime = ifd0[0x0132]?.text()?.let(::exifClock)
        tags.imageWidth = ifd0[0x0100]?.number()
        tags.imageLength = ifd0[0x0101]?.number()
        ifd0[0x8769]?.number()?.let { t.ifd(it.toLong()) }?.let { exif ->
            tags.exposureTime = exif[0x829A]?.number()
            tags.fNumber = exif[0x829D]?.number()
            tags.isoSpeedRatings = exif[0x8827]?.number()
            tags.dateTimeOriginal = exif[0x9003]?.text()?.let(::exifClock)
            tags.dateTimeDigitized = exif[0x9004]?.text()?.let(::exifClock)
            tags.exposureBiasValue = exif[0x9204]?.number()
            tags.flash = exif[0x9209]?.number()
            tags.focalLength = exif[0x920A]?.number()
            tags.focalLengthIn35mmFilm = exif[0xA405]?.number()
            tags.pixelXDimension = exif[0xA002]?.number()
            tags.pixelYDimension = exif[0xA003]?.number()
            tags.lensMake = exif[0xA433]?.text()
            tags.lensModel = exif[0xA434]?.text()
        }
        ifd0[0x8825]?.number()?.let { t.ifd(it.toLong()) }?.let { gps ->
            tags.gpsAltitudeRef = gps[0x0005]?.number()
            tags.gpsAltitude = gps[0x0006]?.number()
            tags.gpsImgDirectionRef = gps[0x0010]?.text()
            tags.gpsImgDirection = gps[0x0011]?.number()
            tags.gpsHPositioningError = gps[0x001F]?.number()
            val lat = gps[0x0002]?.degrees()?.let { if (gps[0x0001]?.text() == "S") -it else it }
            val lng = gps[0x0004]?.degrees()?.let { if (gps[0x0003]?.text() == "W") -it else it }
            if (lat != null && lng != null) {
                tags.latitude = lat
                tags.longitude = lng
            }
        }
    }

    /** One IFD entry's value as stored: text bytes, or its numbers (a rational divided out). */
    private class Value(val texts: List<String>?, val numbers: List<Double>?) {
        /** An ASCII tag; of several NUL-separated strings, the first. */
        fun text(): String? = texts?.firstOrNull()

        /** A single number; a count above one is no number. */
        fun number(): Double? = numbers?.singleOrNull()?.takeIf { it.isFinite() }

        /** Degrees, minutes, seconds as decimal degrees (ExifReader's computed GPS value). */
        fun degrees(): Double? = numbers?.takeIf { it.size == 3 }?.let { (d, m, s) -> d + m / 60 + s / 3600 }?.takeIf { it.isFinite() }
    }

    private class Tiff(val b: ByteArray, val base: Int, val le: Boolean) {
        fun u16(at: Long): Int = (base + at).toInt().let { if (it < 0 || it + 2 > b.size) -1 else if (le) u16le(b, it) else u16be(b, it) }
        fun u32(at: Long): Long = (base + at).toInt().let { if (it < 0 || it + 4 > b.size) -1 else if (le) u32le(b, it) else u32be(b, it) }

        /** The entries of the IFD at [at] by tag; null where it is not inside the block. */
        fun ifd(at: Long): Map<Int, Value>? {
            if (at <= 0) return null
            val count = u16(at)
            if (count < 0 || count > 1024) return null
            val out = HashMap<Int, Value>()
            for (i in 0 until count) {
                val e = at + 2 + 12L * i
                val tag = u16(e)
                val type = u16(e + 2)
                val n = u32(e + 4)
                if (tag < 0 || type < 0 || n < 0) break
                val unit = UNIT.getOrNull(type) ?: continue
                val bytes = unit * n
                if (bytes > 65536) continue
                val at0 = if (bytes <= 4) e + 8 else u32(e + 8)
                if (at0 < 0 || base + at0 + bytes > b.size) continue
                value(type, at0, n.toInt())?.let { out[tag] = it }
            }
            return out
        }

        private fun value(type: Int, at: Long, n: Int): Value? = when (type) {
            2 -> {
                val start = (base + at).toInt()
                var end = start + n
                while (end > start && b[end - 1].toInt() == 0) end--
                Value(splitNul(b, start, end).map { text(b, it.first, it.second) }, null)
            }
            1, 7 -> Value(null, List(n) { u8(b, (base + at).toInt() + it).toDouble() })
            6 -> Value(null, List(n) { b[(base + at).toInt() + it].toDouble() })
            3 -> Value(null, List(n) { u16(at + 2L * it).toDouble() })
            8 -> Value(null, List(n) { u16(at + 2L * it).toShort().toDouble() })
            4 -> Value(null, List(n) { u32(at + 4L * it).toDouble() })
            9 -> Value(null, List(n) { u32(at + 4L * it).toInt().toDouble() })
            5 -> Value(null, List(n) { u32(at + 8L * it).toDouble() / u32(at + 8L * it + 4).toDouble() })
            10 -> Value(null, List(n) { u32(at + 8L * it).toInt().toDouble() / u32(at + 8L * it + 4).toInt().toDouble() })
            else -> null
        }

        companion object {
            /** Bytes per value by TIFF type (1 BYTE … 12 DOUBLE); FLOAT and DOUBLE are not read. */
            val UNIT = listOf(null, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, null, null)
        }
    }

    // --- XMP -------------------------------------------------------------------------------------------------------

    private const val XMP_JPEG = "http://ns.adobe.com/xap/1.0/\u0000"

    /** The XMP properties [ExifTags] names, in attribute form or element form (a language alternative's `x-default`). */
    private fun xmp(packet: String, tags: ExifTags) {
        tags.xmpCapturedAt = xmpClock(xmpValue(packet, "exif", "DateTimeOriginal"))
            ?: xmpClock(xmpValue(packet, "photoshop", "DateCreated"))
            ?: xmpClock(xmpValue(packet, "xmp", "CreateDate"))
        tags.xmpCreatorTool = xmpValue(packet, "xmp", "CreatorTool")
        tags.xmpRating = xmpValue(packet, "xmp", "Rating")?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
        tags.xmpTitle = xmpValue(packet, "dc", "title")
        tags.xmpDescription = xmpValue(packet, "dc", "description")
    }

    internal fun xmpValue(packet: String, prefix: String, name: String): String? {
        val q = Regex.escape("$prefix:$name")
        Regex("""\s$q\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(packet)?.let { m ->
            return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
        }
        val element = Regex("""<$q(?:\s[^>]*)?>(.*?)</$q\s*>""", RegexOption.DOT_MATCHES_ALL).find(packet) ?: return null
        val inner = element.groupValues[1]
        val items = Regex("""<rdf:li(\s[^>]*)?>(.*?)</rdf:li\s*>""", RegexOption.DOT_MATCHES_ALL).findAll(inner).toList()
        if (items.isEmpty()) return if (inner.contains('<')) null else unescape(inner)
        val chosen = items.firstOrNull { it.groupValues[1].contains("\"x-default\"") || it.groupValues[1].contains("'x-default'") } ?: items.first()
        return unescape(chosen.groupValues[2])
    }

    private fun unescape(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e == "amp" -> "&"
            e == "lt" -> "<"
            e == "gt" -> ">"
            e == "quot" -> "\""
            e == "apos" -> "'"
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                code?.let { codePoint(it) } ?: m.value
            }
        }
    }

    private fun codePoint(c: Int): String = if (c < 0x10000) c.toChar().toString() else {
        val v = c - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    // --- dates -----------------------------------------------------------------------------------------------------

    /** `YYYY:MM:DD[ HH:MM:SS]` (or with dashes) as the device's wall clock; a blank or zeroed date is none. */
    internal fun exifClock(s: String): Long? =
        clock(Regex("""^(\d{4})[:-](\d{2})[:-](\d{2})(?: (\d{2}):(\d{2}):(\d{2}))?""").find(s.trim()))

    /** An ISO 8601 date with an optional time, its fraction and zone dropped: the wall clock, as an EXIF one. */
    internal fun xmpClock(s: String?): Long? =
        s?.let { clock(Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?""").find(it.trim())) }

    private fun clock(m: MatchResult?): Long? {
        m ?: return null
        val p = m.groupValues.drop(1).map { it.ifEmpty { "0" }.toInt() }
        if (p[0] == 0 || p[1] == 0 || p[2] == 0) return null
        return localWallClock(p[0], p[1], p[2], p[3], p[4], p[5])
    }

    // --- bytes -----------------------------------------------------------------------------------------------------

    private fun u8(b: ByteArray, at: Int) = if (at < 0 || at >= b.size) -1 else b[at].toInt() and 0xFF
    private fun u16be(b: ByteArray, at: Int) = (u8(b, at) shl 8) or u8(b, at + 1)
    private fun u16le(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun u32be(b: ByteArray, at: Int): Long =
        if (at < 0 || at + 4 > b.size) -1 else (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
    private fun u32le(b: ByteArray, at: Int): Long =
        if (at < 0 || at + 4 > b.size) -1 else (u8(b, at + 3).toLong() shl 24) or (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 1).toLong() shl 8) or u8(b, at).toLong()
    private fun fourcc(b: ByteArray, at: Int) = if (at < 0 || at + 4 > b.size) "" else CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }.concatToString()
    private fun ascii(b: ByteArray, at: Int, s: String) = at >= 0 && at + s.length <= b.size && s.indices.all { (b[at + it].toInt() and 0xFF) == s[it].code }
    private fun indexOf(b: ByteArray, value: Int, from: Int, end: Int): Int {
        for (i in from until minOf(end, b.size)) if (b[i].toInt() == value) return i
        return -1
    }

    private fun splitNul(b: ByteArray, start: Int, end: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var from = start
        for (i in start until end) if (b[i].toInt() == 0) {
            out += from to i
            from = i + 1
        }
        out += from to end
        return out
    }

    /** Text bytes as UTF-8 where they are valid UTF-8, else byte by byte (Latin-1). */
    private fun text(b: ByteArray, start: Int, end: Int): String =
        runCatching { b.decodeToString(start, end, throwOnInvalidSequence = true) }.getOrElse { CharArray(end - start) { (b[start + it].toInt() and 0xFF).toChar() }.concatToString() }
}
