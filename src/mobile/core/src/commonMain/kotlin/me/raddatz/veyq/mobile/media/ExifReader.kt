package me.raddatz.veyq.mobile.media

import me.raddatz.veyq.mobile.search.JsText
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** A file read at any offset: its size, and its bytes `[from, from + length)` — fewer only at its end. */
class ByteSource(val size: Long, val read: suspend (from: Long, length: Int) -> ByteArray)

/**
 * A photo's metadata tags, read on the device from its bytes: the tags by their standard names, each already undone — a
 * rational is a number, an ASCII tag a string (the first of several), a date epoch ms read in the device's zone
 * ([localWallClockMs]), the GPS position signed decimal degrees (both or neither). A tag the file does not carry, or
 * carries unreadably, is null. The web reads the same through `@veyq/core-js/media` under the web's own names.
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
    /** `DateTime`, when the file was last changed, epoch ms (as every date here: the wall clock in the device's zone). */
    var dateTimeMs: Double? = null; internal set
    var imageWidth: Double? = null; internal set
    var imageLength: Double? = null; internal set
    var exposureTime: Double? = null; internal set
    var fNumber: Double? = null; internal set
    var isoSpeedRatings: Double? = null; internal set
    /** When the picture was taken. */
    var dateTimeOriginalMs: Double? = null; internal set
    /** When it was digitised — for a camera, the same moment. */
    var dateTimeDigitizedMs: Double? = null; internal set
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
    /** XMP's capture time: the first of `DateTimeOriginal`, `DateCreated`, `CreateDate` that reads, a wall clock. */
    var xmpCapturedAtMs: Double? = null; internal set
    var xmpCreatorTool: String? = null; internal set
    var xmpRating: Double? = null; internal set
    var xmpTitle: String? = null; internal set
    var xmpDescription: String? = null; internal set
    var iptcObjectName: String? = null; internal set

    val dateTime: Long? get() = dateTimeMs?.toLong()
    val dateTimeOriginal: Long? get() = dateTimeOriginalMs?.toLong()
    val dateTimeDigitized: Long? get() = dateTimeDigitizedMs?.toLong()
    val xmpCapturedAt: Long? get() = xmpCapturedAtMs?.toLong()

    internal fun isEmpty(): Boolean =
        make == null && model == null && orientation == null && software == null && artist == null && copyright == null &&
            rating == null && imageDescription == null && dateTimeMs == null && imageWidth == null && imageLength == null &&
            exposureTime == null && fNumber == null && isoSpeedRatings == null && dateTimeOriginalMs == null &&
            dateTimeDigitizedMs == null && exposureBiasValue == null && flash == null && focalLength == null &&
            focalLengthIn35mmFilm == null && pixelXDimension == null && pixelYDimension == null && lensMake == null &&
            lensModel == null && gpsAltitude == null && gpsAltitudeRef == null && gpsImgDirection == null &&
            gpsImgDirectionRef == null && gpsHPositioningError == null && latitude == null && longitude == null &&
            xmpCapturedAtMs == null && xmpCreatorTool == null && xmpRating == null && xmpTitle == null &&
            xmpDescription == null && iptcObjectName == null
}

/**
 * Reads [ExifTags] from a photo's container, as the web read them through the ExifReader library until ADR 0155's media
 * headers' slice (`contracts/platform/media/v1-vectors.json` holds every client to the same answers): a JPEG's last `APP1`
 * Exif, its `APP1` XMP and its `APP13` IPTC object name; a bare TIFF (its first [TIFF_PREFIX]); a PNG's `eXIf` and
 * uncompressed XMP `iTXt` and a WebP's `EXIF` and `XMP ` (each within its first [CHUNKED_PREFIX]: a PNG's metadata usually
 * precedes its pixels, a WebP's follows them, so a larger WebP answers nothing); a HEIC's or an AVIF's `Exif` item through
 * its `meta` box. Only the tags [ExifTags] names are read; what is not parsed cannot be mis-parsed. Never the whole file: a
 * JPEG's segments and a HEIF's boxes are read by their headers in windows of [WINDOW], at most [SLICED_MAX] in all. A
 * container this does not know answers null, and so does a damaged one past what it read. No `Regex`, `HashMap` or `Long`
 * on the way (the web's path): the XMP is scanned by hand ([Xmp]), the dates parsed by hand.
 */
object ExifReader {
    const val CHUNKED_PREFIX = 512 * 1024
    const val TIFF_PREFIX = 1024 * 1024
    const val SLICED_MAX = 4 * 1024 * 1024
    /** A JPEG's or a HEIF's read window: a JPEG's head usually holds every segment before its image data. */
    const val WINDOW = 128 * 1024

    /** [source]'s tags, or null where it carries none this reads. */
    suspend fun read(source: ByteSource): ExifTags? = read(source.media())

    /**
     * [file]'s tags, or null where it carries none this reads. A read the file refuses fails the whole read, as any read of
     * the file would; a block damaged past what was read leaves what was read standing.
     */
    suspend fun read(file: MediaBytes): ExifTags? {
        var refused: Throwable? = null
        val reads = MediaBytes(file.size) { from, length ->
            try {
                file.read(from, length)
            } catch (e: Throwable) {
                refused = e
                throw e
            }
        }
        if (reads.size < 12) return null
        val head = reads.read(0.0, 12)
        if (head.size < 12) return null
        val tags = ExifTags()
        try {
            when {
                isTiff(head, 0) -> tiff(reads.read(0.0, min(reads.size, TIFF_PREFIX.toDouble()).toInt()), 0, tags)
                u8(head, 0) == 0x89 && Bytes.ascii(head, 1, "PNG") -> png(reads.read(0.0, min(reads.size, CHUNKED_PREFIX.toDouble()).toInt()), tags)
                Bytes.ascii(head, 0, "RIFF") && Bytes.ascii(head, 8, "WEBP") -> webp(reads.read(0.0, min(reads.size, CHUNKED_PREFIX.toDouble()).toInt()), tags)
                u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 -> jpeg(HeaderReader(reads, WINDOW, SLICED_MAX.toDouble(), Int.MAX_VALUE), tags)
                Bytes.ascii(head, 4, "ftyp") -> heif(HeaderReader(reads, WINDOW, SLICED_MAX.toDouble(), Int.MAX_VALUE), tags)
                else -> return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            refused?.let { throw it }
            // a block damaged past what was read: what was read stands
        }
        return if (tags.isEmpty()) null else tags
    }

    // --- containers ------------------------------------------------------------------------------------------------

    private const val XMP_JPEG = "http://ns.adobe.com/xap/1.0/\u0000"

    private suspend fun jpeg(r: HeaderReader, tags: ExifTags) {
        if (r.hold(0.0, 4) < 4) return
        var at = 2.0
        var exif: ByteArray? = null // the last Exif segment is the one read (the web's)
        while (at + 4 <= r.size) {
            if (r.hold(at, 4) < 4 || r.u8(at) != 0xFF) break
            val marker = r.u8(at + 1)
            if (marker == 0xFF) {
                at += 1 // a fill byte
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                at += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) break // the image data, or its end: no metadata follows
            val length = r.u16be(at + 2)
            if (length < 2) break
            if (marker == 0xE1 || marker == 0xED) {
                val n = length - 2
                if (r.hold(at + 4, n) < n) break
                val body = r.copy(at + 4, n)
                when {
                    marker == 0xE1 && Bytes.ascii(body, 0, "Exif\u0000\u0000") -> exif = body
                    marker == 0xE1 && Bytes.ascii(body, 0, XMP_JPEG) -> Xmp.read(body.decodeToString(XMP_JPEG.length, body.size), tags)
                    marker == 0xED && Bytes.ascii(body, 0, "Photoshop 3.0\u0000") -> photoshop(body, 14, tags)
                }
            }
            at += 2 + length
        }
        exif?.let { tiff(it, 6, tags) }
    }

    private fun png(b: ByteArray, tags: ExifTags) {
        var at = 8
        while (at + 8 <= b.size) {
            val length = u32be(b, at)
            val body = at + 8
            if (length < 0 || body + length > b.size) return
            val end = body + length.toInt()
            when {
                Bytes.ascii(b, at + 4, "eXIf") -> tiff(b.copyOfRange(body, end), 0, tags)
                Bytes.ascii(b, at + 4, "iTXt") -> {
                    val keyEnd = indexOf(b, 0, body, end)
                    // keyword, NUL, compression flag (0: none), method, language tag NUL, translated keyword NUL, text
                    if (keyEnd > 0 && Bytes.ascii(b, body, "XML:com.adobe.xmp") && keyEnd == body + 17 && keyEnd + 2 < end && b[keyEnd + 1].toInt() == 0) {
                        val lang = indexOf(b, 0, keyEnd + 3, end)
                        val translated = if (lang < 0) -1 else indexOf(b, 0, lang + 1, end)
                        if (translated >= 0) Xmp.read(b.decodeToString(translated + 1, end), tags)
                    }
                }
                Bytes.ascii(b, at + 4, "IEND") -> return
            }
            at = end + 4
        }
    }

    private fun webp(b: ByteArray, tags: ExifTags) {
        var at = 12
        while (at + 8 <= b.size) {
            val length = u32le(b, at + 4)
            val body = at + 8
            if (length < 0 || body + length > b.size) return
            val end = body + length.toInt()
            when {
                Bytes.ascii(b, at, "EXIF") -> tiff(b.copyOfRange(body, end), if (Bytes.ascii(b, body, "Exif\u0000\u0000")) 6 else 0, tags)
                Bytes.ascii(b, at, "XMP ") -> Xmp.read(b.decodeToString(body, end), tags)
            }
            at = end + (length.toInt() and 1)
        }
    }

    /** A HEIC's or an AVIF's `Exif` item: the top-level `meta` box's `iinf` names it, its `iloc` places it. */
    private suspend fun heif(r: HeaderReader, tags: ExifTags) {
        var at = 0.0
        var visits = 0
        var metaAt = -1.0
        var metaEnd = -1.0
        while (at + 8 <= r.size && visits++ < 64) {
            val n = r.hold(at, 16)
            if (n < 8) return
            var size = r.u32be(at)
            var body = at + 8
            if (size == 1.0) {
                if (n < 16) return
                size = r.u32be(at + 8) * 4_294_967_296.0 + r.u32be(at + 12)
                body = at + 16
            } else if (size == 0.0) {
                size = r.size - at
            }
            if (size < body - at) return
            if (r.fourcc(at + 4, "meta")) {
                metaAt = body
                metaEnd = at + size
                break
            }
            at += size
        }
        if (metaAt < 0 || metaEnd - metaAt > SLICED_MAX || metaEnd > r.size) return
        val length = (metaEnd - metaAt).toInt()
        if (r.hold(metaAt, length) < length) return
        val m = r.copy(metaAt, length)
        // a full box: version and flags, then its children; the `iinf` names the Exif item, the `iloc` places it
        val exifId = boxIn(m, 4, m.size, "iinf")?.let { exifItem(m, it, boxEnd(m, it)) } ?: return
        if (exifId < 0) return
        val iloc = boxIn(m, 4, m.size, "iloc") ?: return
        val extents = itemExtents(m, iloc, boxEnd(m, iloc), exifId) ?: return
        var total = 0.0
        for (i in 1 until extents.size step 2) total += extents[i]
        if (total <= 4 || total > SLICED_MAX) return
        val data = ByteArray(total.toInt())
        var o = 0
        for (i in 0 until extents.size step 2) {
            val len = extents[i + 1].toInt()
            if (r.hold(extents[i], len) < len) return
            r.copy(extents[i], len).copyInto(data, o)
            o += len
        }
        val tiffAt = 4 + u32be(data, 0)
        if (tiffAt >= 4 && tiffAt < data.size) tiff(data, tiffAt.toInt(), tags)
    }

    /** The body of the first child box of [type] in `[from, end)` of [b] (a box's size, then its type), or null. */
    private fun boxIn(b: ByteArray, from: Int, end: Int, type: String): Int? {
        var at = from
        var visits = 0
        while (at + 8 <= end && visits++ < 4096) {
            val size = u32be(b, at)
            if (size < 8 || at + size > end) return null
            if (Bytes.ascii(b, at + 4, type)) return at + 8
            at += size.toInt()
        }
        return null
    }

    /** Where the box whose body starts at [body] ends. */
    private fun boxEnd(b: ByteArray, body: Int): Int = body - 8 + u32be(b, body - 8).toInt()

    /** The id of the `Exif` item an `iinf` lists, or -1. */
    private fun exifItem(b: ByteArray, body: Int, end: Int): Double {
        val version = u8(b, body)
        var at = body + 4 + (if (version == 0) 2 else 4)
        var visits = 0
        while (at + 8 <= end && visits++ < 4096) {
            val size = u32be(b, at)
            if (size < 8 || at + size > end) return -1.0
            val e = at + 8
            if (Bytes.ascii(b, at + 4, "infe")) {
                val v = u8(b, e)
                if (v >= 2) {
                    val id = if (v == 2) u16be(b, e + 4).toDouble() else u32be(b, e + 4)
                    val typeAt = e + 4 + (if (v == 2) 2 else 4) + 2
                    if (Bytes.ascii(b, typeAt, "Exif")) return id
                }
            }
            at += size.toInt()
        }
        return -1.0
    }

    /** The extents (offset, length, offset, length, …) an `iloc` gives item [id] in the file itself (construction 0), or null. */
    private fun itemExtents(b: ByteArray, body: Int, end: Int, id: Double): DoubleArray? {
        val version = u8(b, body)
        var at = body + 4
        val offsetSize = u8(b, at) shr 4
        val lengthSize = u8(b, at) and 0xF
        val baseSize = u8(b, at + 1) shr 4
        val indexSize = if (version == 1 || version == 2) u8(b, at + 1) and 0xF else 0
        at += 2
        val count: Double
        if (version < 2) {
            count = u16be(b, at).toDouble()
            at += 2
        } else {
            count = u32be(b, at)
            at += 4
        }
        fun sized(n: Int): Double {
            var v = 0.0
            for (i in 0 until n) v = v * 256 + u8(b, at + i)
            at += n
            return v
        }
        var i = 0.0
        while (i < count && i < 4096 && at < end) {
            val item = if (version < 2) sized(2) else sized(4)
            val construction = if (version == 1 || version == 2) sized(2).toInt() and 0xF else 0
            sized(2) // data reference index
            val base = sized(baseSize)
            val n = sized(2).toInt()
            val extents = DoubleArray(2 * n)
            for (k in 0 until n) {
                if (indexSize > 0) sized(indexSize)
                extents[2 * k] = base + sized(offsetSize)
                extents[2 * k + 1] = sized(lengthSize)
            }
            if (item == id && construction == 0) return extents
            i++
        }
        return null
    }

    /** A Photoshop `APP13`'s image resources: the IPTC block (`0x0404`) carries the object name (record 2, dataset 5). */
    private fun photoshop(b: ByteArray, from: Int, tags: ExifTags) {
        var at = from
        while (at + 12 <= b.size && Bytes.ascii(b, at, "8BIM")) {
            val id = u16be(b, at + 4)
            val nameLength = u8(b, at + 6)
            val sizeAt = at + 6 + ((nameLength + 2) and 1.inv()) // the Pascal name padded to an even length
            if (sizeAt + 4 > b.size) return
            val size = u32be(b, sizeAt)
            val data = sizeAt + 4
            if (size < 0 || data + size > b.size) return
            if (id == 0x0404) iptc(b, data, data + size.toInt(), tags)
            at = data + size.toInt() + (size.toInt() and 1)
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
        (Bytes.ascii(b, at, "II") && u8(b, at + 2) == 0x2A && u8(b, at + 3) == 0) ||
            (Bytes.ascii(b, at, "MM") && u8(b, at + 2) == 0 && u8(b, at + 3) == 0x2A)

    /**
     * A TIFF header at [base] of [b]: IFD0, then the Exif IFD and the GPS IFD it points at; offsets count from [base]. IFD0's
     * and the Exif IFD's tags are read by their numbers alike (a date in IFD0 is read as the web read it), the Exif IFD's
     * after IFD0's.
     */
    private fun tiff(b: ByteArray, base: Int, tags: ExifTags) {
        if (!isTiff(b, base)) return
        val t = Tiff(b, base, u8(b, base) == 'I'.code)
        var exifAt = -1.0
        var gpsAt = -1.0
        t.each(t.u32(4.0)) { tag, e ->
            when (tag) {
                0x8769 -> exifAt = t.number(e) ?: -1.0
                0x8825 -> gpsAt = t.number(e) ?: -1.0
                else -> main(t, tag, e, tags)
            }
        }
        if (exifAt > 0) t.each(exifAt) { tag, e -> main(t, tag, e, tags) }
        if (gpsAt > 0) {
            var latRef: String? = null
            var lngRef: String? = null
            var lat: Double? = null
            var lng: Double? = null
            t.each(gpsAt) { tag, e ->
                when (tag) {
                    0x0001 -> latRef = t.text(e)
                    0x0002 -> lat = t.degrees(e)
                    0x0003 -> lngRef = t.text(e)
                    0x0004 -> lng = t.degrees(e)
                    0x0005 -> tags.gpsAltitudeRef = t.number(e)
                    0x0006 -> tags.gpsAltitude = t.number(e)
                    0x0010 -> tags.gpsImgDirectionRef = t.text(e)
                    0x0011 -> tags.gpsImgDirection = t.number(e)
                    0x001F -> tags.gpsHPositioningError = t.number(e)
                }
            }
            // a coordinate is its reference's too: both halves, each with its hemisphere, or no position
            val la = lat
            val ln = lng
            if (la != null && ln != null && latRef != null && lngRef != null) {
                tags.latitude = if (latRef == "S") -la else la
                tags.longitude = if (lngRef == "W") -ln else ln
            }
        }
    }

    /** One of IFD0's or the Exif IFD's tags into [tags]. */
    private fun main(t: Tiff, tag: Int, e: Int, tags: ExifTags) {
        when (tag) {
            0x010F -> tags.make = t.text(e)
            0x0110 -> tags.model = t.text(e)
            0x0112 -> tags.orientation = t.number(e)
            0x0131 -> tags.software = t.text(e)
            0x013B -> tags.artist = t.text(e)
            0x8298 -> tags.copyright = t.text(e)
            0x4746 -> tags.rating = t.number(e)
            0x010E -> tags.imageDescription = t.text(e)
            0x0132 -> tags.dateTimeMs = t.text(e)?.let(::exifClock)
            0x0100 -> tags.imageWidth = t.number(e)
            0x0101 -> tags.imageLength = t.number(e)
            0x829A -> tags.exposureTime = t.number(e)
            0x829D -> tags.fNumber = t.number(e)
            0x8827 -> tags.isoSpeedRatings = t.number(e)
            0x9003 -> tags.dateTimeOriginalMs = t.text(e)?.let(::exifClock)
            0x9004 -> tags.dateTimeDigitizedMs = t.text(e)?.let(::exifClock)
            0x9204 -> tags.exposureBiasValue = t.number(e)
            0x9209 -> tags.flash = t.number(e)
            0x920A -> tags.focalLength = t.number(e)
            0xA405 -> tags.focalLengthIn35mmFilm = t.number(e)
            0xA002 -> tags.pixelXDimension = t.number(e)
            0xA003 -> tags.pixelYDimension = t.number(e)
            0xA433 -> tags.lensMake = t.text(e)
            0xA434 -> tags.lensModel = t.text(e)
        }
    }

    /** A TIFF block's reader: its offsets count from [base], every read bounds-checked (-1 outside the block). */
    private class Tiff(val b: ByteArray, val base: Int, val le: Boolean) {
        private fun at(off: Double): Int = if (off < 0 || base + off > b.size) -1 else (base + off).toInt()
        fun u16(off: Double): Int = at(off).let { if (it < 0 || it + 2 > b.size) -1 else if (le) Bytes.u16le(b, it) else Bytes.u16be(b, it) }
        fun u32(off: Double): Double = at(off).let { if (it < 0 || it + 4 > b.size) -1.0 else if (le) Bytes.u32le(b, it) else Bytes.u32be(b, it) }

        /** A signed 32-bit number at [off], which the caller has bounds-checked. */
        private fun i32(off: Double): Int = (base + off).toInt().let { if (le) Bytes.i32le(b, it) else Bytes.i32be(b, it) }

        /** Each entry of the IFD at [off]: its tag and the entry's offset; nothing where it is not inside the block. */
        inline fun each(off: Double, visit: (tag: Int, entry: Int) -> Unit) {
            if (off <= 0) return
            val count = u16(off)
            if (count < 0 || count > 1024) return
            for (i in 0 until count) {
                val e = off + 2 + 12.0 * i
                val tag = u16(e)
                if (tag < 0 || u16(e + 2) < 0 || u32(e + 4) < 0) return
                visit(tag, e.toInt())
            }
        }

        /** Where entry [e]'s value is (inline or at its offset) and its count, or -1 where it lies outside the block. */
        private fun valueAt(e: Int, unit: Int): Double {
            val bytes = unit * u32(e + 4.0)
            if (bytes > 65536) return -1.0
            val off = if (bytes <= 4) e + 8.0 else u32(e + 8.0)
            return if (off < 0 || base + off + bytes > b.size) -1.0 else off
        }

        private fun unit(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9 -> 4
            5, 10 -> 8
            else -> 0
        }

        /** An ASCII tag's text: of several NUL-separated strings, the first; an empty one is none. */
        fun text(e: Int): String? {
            if (u16(e + 2.0) != 2) return null
            val off = valueAt(e, 1)
            if (off < 0) return null
            val start = base + off.toInt()
            var end = start + u32(e + 4.0).toInt()
            while (end > start && b[end - 1].toInt() == 0) end--
            var first = start
            while (first < end && b[first].toInt() != 0) first++
            return if (first == start) null else ExifReader.text(b, start, first)
        }

        /** The [i]th value of entry [e] as a number (a rational divided out), or NaN. */
        private fun value(e: Int, type: Int, off: Double, i: Int): Double = when (type) {
            1, 7 -> Bytes.u8(b, base + off.toInt() + i).toDouble()
            6 -> b[base + off.toInt() + i].toDouble()
            3 -> u16(off + 2.0 * i).toDouble()
            8 -> u16(off + 2.0 * i).toShort().toDouble()
            4 -> u32(off + 4.0 * i)
            9 -> i32(off + 4.0 * i).toDouble()
            5 -> u32(off + 8.0 * i) / u32(off + 8.0 * i + 4)
            10 -> i32(off + 8.0 * i).toDouble() / i32(off + 8.0 * i + 4).toDouble()
            else -> Double.NaN
        }

        /** A single number; a count above one, a text or a division by zero is none. */
        fun number(e: Int): Double? {
            val type = u16(e + 2.0)
            if (type == 2 || unit(type) == 0 || u32(e + 4.0) != 1.0) return null
            val off = valueAt(e, unit(type))
            if (off < 0) return null
            return value(e, type, off, 0).takeIf { it.isFinite() }
        }

        /** Degrees, minutes, seconds (three rationals) as decimal degrees. */
        fun degrees(e: Int): Double? {
            val type = u16(e + 2.0)
            if ((type != 5 && type != 10) || u32(e + 4.0) != 3.0) return null
            val off = valueAt(e, 8)
            if (off < 0) return null
            return (value(e, type, off, 0) + value(e, type, off, 1) / 60 + value(e, type, off, 2) / 3600).takeIf { it.isFinite() }
        }
    }

    // --- dates -----------------------------------------------------------------------------------------------------

    /** `YYYY:MM:DD[ HH:MM:SS]` (or with dashes), trimmed, as the device's wall clock; a blank or zeroed date is none. */
    internal fun exifClock(s: String): Double? {
        val p = Parts(JsText.trim(s))
        val y = p.digits(4) ?: return null
        if (!p.oneOf(':', '-')) return null
        val mo = p.digits(2) ?: return null
        if (!p.oneOf(':', '-')) return null
        val d = p.digits(2) ?: return null
        var h = 0
        var mi = 0
        var sec = 0
        val mark = p.at
        if (p.char(' ')) {
            val hh = p.digits(2)
            val mm = if (hh != null && p.char(':')) p.digits(2) else null
            val ss = if (mm != null && p.char(':')) p.digits(2) else null
            if (ss != null) {
                h = hh!!
                mi = mm!!
                sec = ss
            } else {
                p.at = mark
            }
        }
        return clock(y, mo, d, h, mi, sec)
    }

    /** An ISO 8601 date with an optional `Thh:mm[:ss]`, trimmed, its fraction and zone dropped: the wall clock. */
    internal fun xmpClock(s: String?): Double? {
        val p = Parts(JsText.trim(s ?: return null))
        val y = p.digits(4) ?: return null
        if (!p.char('-')) return null
        val mo = p.digits(2) ?: return null
        if (!p.char('-')) return null
        val d = p.digits(2) ?: return null
        var h = 0
        var mi = 0
        var sec = 0
        if (p.char('T')) {
            val hh = p.digits(2)
            val mm = if (hh != null && p.char(':')) p.digits(2) else null
            if (mm != null) {
                h = hh!!
                mi = mm
                val mark = p.at
                if (p.char(':')) sec = p.digits(2) ?: 0.also { p.at = mark }
            }
        }
        return clock(y, mo, d, h, mi, sec)
    }

    private fun clock(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Double? =
        if (y == 0 || mo == 0 || d == 0) null else localWallClockMs(y, mo, d, h, mi, s)

    /** A cursor over a date's text: ASCII digits only (JavaScript's `\d`). */
    private class Parts(val s: String) {
        var at = 0
        fun digits(n: Int): Int? {
            if (at + n > s.length) return null
            var v = 0
            for (i in 0 until n) {
                val c = s[at + i]
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            at += n
            return v
        }
        fun char(c: Char): Boolean = (at < s.length && s[at] == c).also { if (it) at++ }
        fun oneOf(a: Char, b: Char): Boolean = char(a) || char(b)
    }

    /**
     * JavaScript's `Number(s)` for an XMP number, which XMP carries as text: decimal with an optional sign, fraction and
     * exponent, or `0x`, `0o`, `0b`; trimmed; anything else none, and so is a value that is not finite.
     */
    internal fun jsNumber(text: String?): Double? {
        val s = JsText.trim(text ?: return null)
        if (s.isEmpty()) return null
        if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X' || s[1] == 'o' || s[1] == 'O' || s[1] == 'b' || s[1] == 'B')) {
            val radix = when (s[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                else -> 2
            }
            var v = 0.0
            for (i in 2 until s.length) {
                val digit = s[i].digitToIntOrNull(radix) ?: return null
                v = v * radix + digit
            }
            return v.takeIf { it.isFinite() }
        }
        var i = 0
        if (s[i] == '+' || s[i] == '-') i++
        var digits = 0
        while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
        }
        if (digits == 0) return null
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val from = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == from) return null
        }
        if (i != s.length) return null
        return s.toDouble().takeIf { it.isFinite() }
    }

    // --- bytes -----------------------------------------------------------------------------------------------------

    private fun u8(b: ByteArray, at: Int) = if (at < 0 || at >= b.size) -1 else b[at].toInt() and 0xFF
    private fun u16be(b: ByteArray, at: Int) = if (at < 0 || at + 2 > b.size) -1 else Bytes.u16be(b, at)
    private fun u32be(b: ByteArray, at: Int): Double = if (at < 0 || at + 4 > b.size) -1.0 else Bytes.u32be(b, at)
    private fun u32le(b: ByteArray, at: Int): Double = if (at < 0 || at + 4 > b.size) -1.0 else Bytes.u32le(b, at)
    private fun indexOf(b: ByteArray, value: Int, from: Int, end: Int): Int {
        for (i in from until min(end, b.size)) if (b[i].toInt() == value) return i
        return -1
    }

    /** Text bytes as UTF-8 where they are valid UTF-8, else byte by byte (Latin-1). */
    internal fun text(b: ByteArray, start: Int, end: Int): String = try {
        b.decodeToString(start, end, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        val chars = CharArray(end - start)
        for (i in chars.indices) chars[i] = (b[start + i].toInt() and 0xFF).toChar()
        chars.concatToString()
    }
}
