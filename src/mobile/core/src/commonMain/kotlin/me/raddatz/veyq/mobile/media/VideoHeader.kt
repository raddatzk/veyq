package me.raddatz.veyq.mobile.media

import kotlin.math.abs
import kotlin.math.floor

/**
 * A video's display size and capture time read from its container's header, never by a player (inbox #793, #794; the web
 * draws it through `@veyq/core-js/media`, ADR 0155 § The media headers' slice).
 *
 * The two container families a camera, a phone or a browser's recorder writes:
 *  - **MP4 / QuickTime** (ISO BMFF): the top-level boxes are walked by their headers only, so a `moov` behind the media data
 *    costs one more read, never the bytes between; a box may name a 64-bit size. The size is the video track's `tkhd` width
 *    and height (16.16 fixed point) turned by its matrix as a player shows it: a quarter turn swaps them; a matrix that is no
 *    plain rotation answers null.
 *  - **WebM / Matroska** (EBML): Segment → Tracks → the first TrackEntry of TrackType 1 → Video's PixelWidth and PixelHeight,
 *    or its DisplayWidth and DisplayHeight where given in pixels (DisplayUnit 0). An element of unknown size (MediaRecorder's
 *    Segment) is descended into; Tracks precede the first Cluster, so the walk stops there. A Projection, a crop or a display
 *    size in another unit answers null.
 *
 * The capture time is MP4 / QuickTime's only: Apple's `com.apple.quicktime.creationdate` (an ISO time with its offset, in
 * the `mdta` keys of a `meta` in the `moov`, or in its `udta` as ffmpeg writes it), else the `mvhd` creation time (seconds
 * since 1904, UTC); before 1970-01-02 or more than a day ahead of `now` is none. Matroska's `DateUTC` is when the file was
 * muxed, not read.
 *
 * Reads are windows of [WINDOW], at most [MAX_READ] in all, at most [MAX_VISITS] boxes or elements; another container, a
 * damaged header or a cap spent answers null.
 */
object VideoHeader {
    const val WINDOW = 64 * 1024
    const val MAX_READ = 1024 * 1024
    const val MAX_VISITS = 4096
    private const val EPOCH_1904 = 2_082_844_800.0
    private const val DAY = 86_400_000.0
    private const val CREATION_DATE_KEY = "com.apple.quicktime.creationdate"

    class Size(val width: Int, val height: Int)

    /** The capture time (epoch ms) [source]'s header names, or null. */
    suspend fun captureTime(source: ByteSource, now: Long): Long? = captureTime(source.media(), now.toDouble())?.toLong()

    /** The display size [source]'s video track names, as a player shows it, or null. */
    suspend fun size(source: ByteSource): Size? = dimensions(source.media())?.let { Size(it.width.toInt(), it.height.toInt()) }

    /** The capture time (epoch ms) [file]'s header names, or null; [now] bounds a time ahead of the clock. */
    suspend fun captureTime(file: MediaBytes, now: Double): Double? {
        val r = reader(file)
        if (!isBmff(r)) return null
        val moov = child(r, 0.0, file.size, "moov") ?: return null
        val udta = child(r, moov.body, moov.end, "udta")
        val meta = child(r, moov.body, moov.end, "meta") ?: udta?.let { child(r, it.body, it.end, "meta") }
        val tagged = meta?.let { plausible(parseIsoTime(quickTimeKey(r, it, CREATION_DATE_KEY)), now) }
        return tagged ?: plausible(mvhdCreation(r, moov), now)
    }

    /** The display size [file]'s video track names, as a player shows it, or null. */
    suspend fun dimensions(file: MediaBytes): ImageHeader.Size? {
        val r = reader(file)
        if (r.hold(0.0, 8) < 8) return null
        if (r.u32be(0.0) == 0x1A45DFA3.toDouble()) return matroskaSize(r)
        if (isTopLevel(r, 4.0)) return bmffSize(r)
        return null
    }

    private fun reader(file: MediaBytes) = HeaderReader(file, WINDOW, MAX_READ.toDouble(), MAX_VISITS)

    private fun plausible(at: Double?, now: Double): Double? = at?.takeIf { it >= DAY && it <= now + DAY }

    /** A size, or null where either side is not a positive whole number of pixels. */
    private fun sized(w: Double, h: Double): ImageHeader.Size? =
        if (w.isFinite() && h.isFinite() && w >= 1 && h >= 1) ImageHeader.Size(jsRound(w), jsRound(h)) else null

    /** JavaScript's `Math.round`: half up, toward positive infinity. */
    private fun jsRound(v: Double): Double = floor(v + 0.5)

    // --- MP4 / QuickTime ---------------------------------------------------------------------------------------------

    /** The box types an ISO BMFF or QuickTime file may open with (an old QuickTime file has no `ftyp`). */
    private fun isTopLevel(r: HeaderReader, at: Double): Boolean =
        r.fourcc(at, "ftyp") || r.fourcc(at, "moov") || r.fourcc(at, "mdat") || r.fourcc(at, "wide") || r.fourcc(at, "free") ||
            r.fourcc(at, "skip") || r.fourcc(at, "pnot")

    private suspend fun isBmff(r: HeaderReader): Boolean = r.hold(0.0, 8) >= 8 && isTopLevel(r, 4.0)

    /** A box: where its type is, where its body starts and where it ends. */
    private class Box(val at: Double, val body: Double, val end: Double)

    /** The box at [at], inside a parent that ends at [limit]; null where its header is bad or the walk is spent. */
    private suspend fun boxAt(r: HeaderReader, at: Double, limit: Double): Box? {
        if (!r.visit()) return null
        val n = r.hold(at, 16)
        if (n < 8) return null
        var size = r.u32be(at)
        var body = at + 8
        if (size == 1.0) {
            if (n < 16) return null
            size = r.u32be(at + 8) * 4_294_967_296.0 + r.u32be(at + 12) // a 64-bit `largesize`
            body = at + 16
        } else if (size == 0.0) {
            size = limit - at // the last box, to the end of its parent
        }
        val end = at + size
        return if (end >= body && end <= limit) Box(at, body, end) else null
    }

    /** The first child of the parent `[body, end)` of [type]; null where none is or a header is bad. */
    private suspend fun child(r: HeaderReader, body: Double, end: Double, type: String): Box? {
        var at = body
        while (at < end) {
            val box = boxAt(r, at, end) ?: return null
            if (r.hold(box.at + 4, 4) == 4 && r.fourcc(box.at + 4, type)) return box
            at = box.end
        }
        return null
    }

    private suspend fun bmffSize(r: HeaderReader): ImageHeader.Size? {
        val moov = child(r, 0.0, r.size, "moov") ?: return null
        var at = moov.body
        while (at < moov.end) {
            val box = boxAt(r, at, moov.end) ?: return null
            if (r.hold(box.at + 4, 4) == 4 && r.fourcc(box.at + 4, "trak")) videoTrackSize(r, box)?.let { return it }
            at = box.end
        }
        return null
    }

    /** A `trak`'s display size where its media handler is video (`hdlr` `vide`); null for any other track. */
    private suspend fun videoTrackSize(r: HeaderReader, trak: Box): ImageHeader.Size? {
        val mdia = child(r, trak.body, trak.end, "mdia") ?: return null
        val hdlr = child(r, mdia.body, mdia.end, "hdlr") ?: return null
        // version and flags, pre_defined, then handler_type
        if (r.hold(hdlr.body + 8, 4) < 4 || !r.fourcc(hdlr.body + 8, "vide")) return null
        val tkhd = child(r, trak.body, trak.end, "tkhd") ?: return null
        if (r.hold(tkhd.body, 1) < 1) return null
        val matrixAt = tkhd.body + if (r.u8(tkhd.body) == 1) 52 else 40 // past the times, the track id, layer, group, volume
        if (matrixAt + 44 > tkhd.end || r.hold(matrixAt, 44) < 44) return null
        val a = r.i32be(matrixAt) // {a b u / c d v / x y w}, 16.16
        val b = r.i32be(matrixAt + 4)
        val c = r.i32be(matrixAt + 12)
        val d = r.i32be(matrixAt + 16)
        val w = r.u32be(matrixAt + 36) / 65536
        val h = r.u32be(matrixAt + 40) / 65536
        val one = 0x10000
        val upright = b == 0 && c == 0 && abs(a) == one && a == d // none, or a half turn
        val quarter = a == 0 && d == 0 && abs(b) == one && b == -c // a quarter turn either way
        return when {
            upright -> sized(w, h)
            quarter -> sized(h, w)
            else -> null // a flip, a scale or a skew: the platform answers
        }
    }

    /** The `mvhd` creation time in epoch ms; null where the box is missing or short. */
    private suspend fun mvhdCreation(r: HeaderReader, moov: Box): Double? {
        val mvhd = child(r, moov.body, moov.end, "mvhd") ?: return null
        val n = r.hold(mvhd.body, 12)
        if (n < 8) return null
        val seconds = if (r.u8(mvhd.body) == 1) {
            if (n < 12) return null
            r.u32be(mvhd.body + 4) * 4_294_967_296.0 + r.u32be(mvhd.body + 8)
        } else {
            r.u32be(mvhd.body + 4)
        }
        return (seconds - EPOCH_1904) * 1000
    }

    /**
     * The UTF-8 value a QuickTime `meta` (`hdlr` `mdta`, then `keys` and `ilst`) holds for [key]; null where there is none.
     * Apple writes this `meta` as a plain atom, an ISO writer as a full box with four bytes of version and flags first.
     */
    private suspend fun quickTimeKey(r: HeaderReader, meta: Box, key: String): String? {
        if (r.hold(meta.body, 12) < 12) return null
        val body = when {
            isMetaChild(r, meta.body + 4) -> meta.body
            isMetaChild(r, meta.body + 8) -> meta.body + 4
            else -> return null
        }
        val keys = child(r, body, meta.end, "keys") ?: return null
        val index = keyIndex(r, keys, key) ?: return null
        val ilst = child(r, body, meta.end, "ilst") ?: return null
        var at = ilst.body
        while (at < ilst.end) {
            val item = boxAt(r, at, ilst.end) ?: return null
            // an item's type is the key's 1-based index, not a fourcc
            if (r.hold(at + 4, 4) == 4 && r.u32be(at + 4) == index.toDouble()) return dataText(r, item)
            at = item.end
        }
        return null
    }

    private fun isMetaChild(r: HeaderReader, at: Double) = r.fourcc(at, "hdlr") || r.fourcc(at, "keys") || r.fourcc(at, "ilst")

    /** The 1-based index [key] has in a `keys` atom (version and flags, a count, then size + namespace + name each). */
    private suspend fun keyIndex(r: HeaderReader, keys: Box, key: String): Int? {
        if (r.hold(keys.body + 4, 4) < 4) return null
        val count = r.u32be(keys.body + 4)
        var at = keys.body + 8
        var i = 1
        while (i <= count && at + 8 <= keys.end && r.visit()) {
            if (r.hold(at, 8) < 8) return null
            val size = r.u32be(at)
            if (size < 8 || at + size > keys.end) return null
            if (r.fourcc(at + 4, "mdta") && size - 8 == key.length.toDouble()) {
                val length = (size - 8).toInt()
                if (r.hold(at + 8, length) == length && r.copy(at + 8, length).decodeToString() == key) return i
            }
            at += size
            i++
        }
        return null
    }

    /** The text of an `ilst` item's `data` atom (type 1, UTF-8); null for any other type. */
    private suspend fun dataText(r: HeaderReader, item: Box): String? {
        val data = child(r, item.body, item.end, "data") ?: return null
        val length = data.end - data.body
        if (length < 8 || length > 256) return null
        val n = length.toInt()
        if (r.hold(data.body, n) < n || r.u32be(data.body) != 1.0) return null // the type indicator, then the locale
        val b = r.copy(data.body, n)
        return try {
            b.decodeToString(8, b.size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * `YYYY-MM-DDTHH:MM:SS[.fff](Z|±HH[:]MM)` in epoch ms; null without a zone, since a local time names no instant. Read as
     * the web read it with `Date.UTC`: a month out of range rolls into the next or the last year, a day must exist in the
     * month it lands in, and the hour, minute and second must be below 24, 60 and 60.
     */
    internal fun parseIsoTime(text: String?): Double? {
        val s = text ?: return null
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
        val y = digits(4) ?: return null
        if (!char('-')) return null
        val mo = digits(2) ?: return null
        if (!char('-')) return null
        val d = digits(2) ?: return null
        if (!char('T')) return null
        val h = digits(2) ?: return null
        if (!char(':')) return null
        val mi = digits(2) ?: return null
        if (!char(':')) return null
        val sec = digits(2) ?: return null
        if (char('.')) {
            val from = at
            while (at < s.length && s[at] in '0'..'9') at++
            if (at == from) return null
        }
        val offset: Int = when {
            char('Z') -> 0
            at < s.length && (s[at] == '+' || s[at] == '-') -> {
                val sign = if (s[at++] == '-') -1 else 1
                val oh = digits(2) ?: return null
                char(':')
                val om = digits(2) ?: return null
                sign * (oh * 60 + om)
            }
            else -> return null
        }
        if (at != s.length) return null
        // Date.UTC(y, mo - 1, d, …): the month rolls over the year; the day must not
        val months = y * 12 + (mo - 1)
        val year = floorDiv(months, 12)
        val month = months - year * 12 + 1
        if (d < 1 || d > daysIn(year, month) || h >= 24 || mi >= 60 || sec >= 60) return null
        val local = (daysFromEpoch(year, month, d) * 86_400.0 + h * 3600 + mi * 60 + sec) * 1000
        return local - offset * 60_000.0
    }

    private fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt()
    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    private fun daysIn(y: Int, m: Int) = when (m) {
        2 -> if (leap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days from 1970-01-01 to [y]-[m]-[d] (proleptic Gregorian). */
    private fun daysFromEpoch(y: Int, m: Int, d: Int): Double {
        val yy = if (m <= 2) y - 1 else y
        val era = floorDiv(yy, 400)
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097.0 + doe - 719_468
    }

    // --- WebM / Matroska ---------------------------------------------------------------------------------------------

    private const val SEGMENT = 0x18538067
    private const val CLUSTER = 0x1F43B675
    private const val TRACKS = 0x1654AE6B
    private const val TRACK_ENTRY = 0xAE
    private const val TRACK_TYPE = 0x83
    private const val VIDEO = 0xE0
    private const val PIXEL_WIDTH = 0xB0
    private const val PIXEL_HEIGHT = 0xBA
    private const val DISPLAY_WIDTH = 0x54B0
    private const val DISPLAY_HEIGHT = 0x54BA
    private const val DISPLAY_UNIT = 0x54B2
    private const val PROJECTION = 0x7670

    /** Which of the four crops (bottom, top, left, right) [id] is, or -1. */
    private fun cropAt(id: Int) = when (id) {
        0x54AA -> 0
        0x54BB -> 1
        0x54CC -> 2
        0x54DD -> 3
        else -> -1
    }

    /** An element: its id, where its body starts and where it ends; [unknown] where its size was all ones. */
    private class Element(val id: Int, val body: Double, val end: Double, val unknown: Boolean)

    /** The element at [at] inside a parent that ends at [limit]; an unknown size (all ones) reaches the parent's end. */
    private suspend fun elementAt(r: HeaderReader, at: Double, limit: Double): Element? {
        if (!r.visit()) return null
        val n = r.hold(at, 12)
        if (n < 1) return null
        // the id: its length marker kept, at most 4 bytes
        val first = r.u8(at)
        if (first == 0) return null
        var idLength = 1
        while (first and (0x80 shr (idLength - 1)) == 0) idLength++
        if (idLength > 4 || idLength > n) return null
        var id = first
        for (i in 1 until idLength) id = (id shl 8) or r.u8(at + i)
        // the size: its marker dropped, at most 8 bytes; all ones is unknown
        val sizeAt = at + idLength
        if (idLength >= n) return null
        val head = r.u8(sizeAt)
        if (head == 0) return null
        var sizeLength = 1
        while (head and (0x80 shr (sizeLength - 1)) == 0) sizeLength++
        if (sizeLength > 8 || idLength + sizeLength > n) return null
        val bits = head and (0xFF shr sizeLength)
        var value = bits.toDouble()
        var ones = bits == (0xFF shr sizeLength)
        for (i in 1 until sizeLength) {
            val b = r.u8(sizeAt + i)
            value = value * 256 + b
            ones = ones && b == 0xFF
        }
        val body = sizeAt + sizeLength
        val end = if (ones) limit else body + value
        return if (end >= body && end <= limit) Element(id, body, end, ones) else null
    }

    /** The unsigned integer an element holds (at most 8 bytes); null where it does not read. */
    private suspend fun uintOf(r: HeaderReader, el: Element): Double? {
        val length = (el.end - el.body).toInt()
        if (el.unknown || length < 1 || length > 8) return null
        if (r.hold(el.body, length) < length) return null
        var n = 0.0
        for (i in 0 until length) n = n * 256 + r.u8(el.body + i)
        return n
    }

    private suspend fun matroskaSize(r: HeaderReader): ImageHeader.Size? {
        var at = 0.0
        while (at < r.size) {
            val el = elementAt(r, at, r.size) ?: return null
            if (el.id == SEGMENT) return segmentSize(r, el) // the EBML header, then the Segment
            if (el.unknown) return null
            at = el.end
        }
        return null
    }

    private suspend fun segmentSize(r: HeaderReader, segment: Element): ImageHeader.Size? {
        var at = segment.body
        while (at < segment.end) {
            val el = elementAt(r, at, segment.end) ?: return null
            if (el.id == CLUSTER) return null // Tracks precede the first Cluster: none came
            if (el.id == TRACKS) return tracksSize(r, el)
            if (el.unknown) return null // only a Segment or a Cluster may have no size, and neither is skipped over
            at = el.end
        }
        return null
    }

    private suspend fun tracksSize(r: HeaderReader, tracks: Element): ImageHeader.Size? {
        var at = tracks.body
        while (at < tracks.end) {
            val el = elementAt(r, at, tracks.end) ?: return null
            if (el.id == TRACK_ENTRY) videoEntrySize(r, el)?.let { return it }
            if (el.unknown) return null
            at = el.end
        }
        return null
    }

    /** A TrackEntry's display size where it is a video track (TrackType 1); null for any other track. */
    private suspend fun videoEntrySize(r: HeaderReader, entry: Element): ImageHeader.Size? {
        var type: Double? = null
        var video: Element? = null
        var at = entry.body
        while (at < entry.end) {
            val el = elementAt(r, at, entry.end) ?: break
            if (el.id == TRACK_TYPE) type = uintOf(r, el)
            if (el.id == VIDEO) video = el
            if (el.unknown) break
            at = el.end
        }
        if (type != 1.0 || video == null) return null
        var pixelWidth: Double? = null
        var pixelHeight: Double? = null
        var displayWidth: Double? = null
        var displayHeight: Double? = null
        var displaySeen = false
        var unit: Double? = null
        var unitSeen = false
        val crops = DoubleArray(4)
        var projected = false
        at = video.body
        while (at < video.end) {
            val el = elementAt(r, at, video.end) ?: break
            when {
                el.id == PROJECTION -> projected = true
                el.id == PIXEL_WIDTH -> pixelWidth = uintOf(r, el)
                el.id == PIXEL_HEIGHT -> pixelHeight = uintOf(r, el)
                el.id == DISPLAY_WIDTH -> { displayWidth = uintOf(r, el); displaySeen = true }
                el.id == DISPLAY_HEIGHT -> { displayHeight = uintOf(r, el); displaySeen = true }
                el.id == DISPLAY_UNIT -> { unit = uintOf(r, el); unitSeen = true }
                // the last of each crop counts; one that does not read is none (the web's `?? 0`)
                cropAt(el.id) >= 0 -> crops[cropAt(el.id)] = uintOf(r, el) ?: 0.0
            }
            if (el.unknown) break
            at = el.end
        }
        if (projected || crops.any { it != 0.0 }) return null
        val pixels = sized(pixelWidth ?: 0.0, pixelHeight ?: 0.0)
        if (!displaySeen) return pixels
        if (unitSeen && (unit ?: 0.0) != 0.0) return null // a display size in centimetres, inches or an aspect ratio
        return sized(displayWidth ?: pixels?.width ?: 0.0, displayHeight ?: pixels?.height ?: 0.0)
    }
}
