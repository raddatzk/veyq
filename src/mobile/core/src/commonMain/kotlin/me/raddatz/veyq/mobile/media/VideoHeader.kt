package me.raddatz.veyq.mobile.media

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A video's capture time and display size read from its MP4 / QuickTime container header, never by a player (the web's
 * `shared/image/videoHeader.ts`): the top-level boxes are walked by their headers, so a `moov` behind the media data costs
 * one more read, never the bytes between; a box may name a 64-bit size. Reads are windows of [WINDOW], at most [MAX_READ]
 * in all, at most [MAX_VISITS] boxes; another container, a damaged header or a cap spent answers null.
 *
 * The capture time is Apple's `com.apple.quicktime.creationdate` (an ISO time WITH its offset, in the `mdta` keys of a
 * `meta` in the `moov`, or in its `udta` as ffmpeg writes it), else the `mvhd` creation time (seconds since 1904, UTC);
 * before 1970-01-02 or more than a day ahead of [now] is none. The size is the video track's `tkhd` width and height
 * turned by its matrix as a player shows it: a quarter turn swaps them; a matrix that is no plain rotation answers null.
 * Matroska carries no capture time a camera wrote, and its size is the platform's to read.
 */
object VideoHeader {
    const val WINDOW = 64 * 1024
    const val MAX_READ = 1024 * 1024
    const val MAX_VISITS = 4096
    private const val EPOCH_1904 = 2_082_844_800L
    private const val DAY = 86_400_000L
    private const val CREATION_DATE_KEY = "com.apple.quicktime.creationdate"
    private val TOP_LEVEL = setOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot")
    private val META_CHILDREN = setOf("hdlr", "keys", "ilst")

    class Size(val width: Int, val height: Int)

    /** The capture time (epoch ms) [source]'s header names, or null. */
    suspend fun captureTime(source: ByteSource, now: Long): Long? {
        val r = Reader(source)
        val head = r.view(0, 8) ?: return null
        if (head.size < 8 || fourcc(head, 4) !in TOP_LEVEL) return null
        val moov = r.child(Box("", 0, source.size), "moov") ?: return null
        fun plausible(at: Long?) = at?.takeIf { it >= DAY && it <= now + DAY }
        val udta = r.child(moov, "udta")
        val meta = r.child(moov, "meta") ?: udta?.let { r.child(it, "meta") }
        val tagged = meta?.let { plausible(parseIsoTime(quickTimeKey(r, it, CREATION_DATE_KEY))) }
        return tagged ?: plausible(mvhdCreation(r, moov))
    }

    /** The display size [source]'s video track names, as a player shows it, or null. */
    suspend fun size(source: ByteSource): Size? {
        val r = Reader(source)
        val head = r.view(0, 8) ?: return null
        if (head.size < 8 || fourcc(head, 4) !in TOP_LEVEL) return null
        val moov = r.child(Box("", 0, source.size), "moov") ?: return null
        var at = moov.body
        while (at < moov.end) {
            val box = r.boxAt(at, moov.end) ?: return null
            if (box.type == "trak") videoTrackSize(r, box)?.let { return it }
            at = box.end
        }
        return null
    }

    private class Box(val type: String, val body: Long, val end: Long)

    private class Reader(val source: ByteSource) {
        private var windowAt = 0L
        private var window = ByteArray(0)
        private var read = 0L
        private var visits = 0

        suspend fun view(at: Long, length: Int): ByteArray? {
            if (at < 0 || at >= source.size) return null
            val end = minOf(source.size, at + length)
            if (at < windowAt || end > windowAt + window.size) {
                val size = (minOf(source.size, at + maxOf(length, WINDOW)) - at).toInt()
                if (read + size > MAX_READ) return null
                read += size
                window = source.read(at, size)
                windowAt = at
            }
            val from = (at - windowAt).toInt()
            return window.copyOfRange(from, minOf(window.size, (end - windowAt).toInt()))
        }

        fun visit() = ++visits <= MAX_VISITS

        suspend fun boxAt(at: Long, limit: Long): Box? {
            if (!visit()) return null
            val h = view(at, 16) ?: return null
            if (h.size < 8) return null
            val type = fourcc(h, 4)
            var size = u32(h, 0)
            var body = at + 8
            if (size == 1L) {
                if (h.size < 16) return null
                size = (u32(h, 8) shl 32) + u32(h, 12)
                body = at + 16
            } else if (size == 0L) {
                size = limit - at
            }
            val end = at + size
            return if (end >= body && end <= limit) Box(type, body, end) else null
        }

        suspend fun child(parent: Box, type: String): Box? {
            var at = parent.body
            while (at < parent.end) {
                val box = boxAt(at, parent.end) ?: return null
                if (box.type == type) return box
                at = box.end
            }
            return null
        }
    }

    private suspend fun videoTrackSize(r: Reader, trak: Box): Size? {
        val mdia = r.child(trak, "mdia") ?: return null
        val hdlr = r.child(mdia, "hdlr") ?: return null
        val handler = r.view(hdlr.body + 8, 4) ?: return null
        if (handler.size < 4 || fourcc(handler, 0) != "vide") return null
        val tkhd = r.child(trak, "tkhd") ?: return null
        val version = r.view(tkhd.body, 1) ?: return null
        val matrixAt = tkhd.body + if (version[0].toInt() == 1) 52 else 40
        if (matrixAt + 44 > tkhd.end) return null
        val m = r.view(matrixAt, 44) ?: return null
        if (m.size < 44) return null
        val a = i32(m, 0)
        val b = i32(m, 4)
        val c = i32(m, 12)
        val d = i32(m, 16)
        val w = u32(m, 36) / 65536.0
        val h = u32(m, 40) / 65536.0
        val one = 0x10000
        val upright = b == 0 && c == 0 && abs(a) == one && a == d
        val quarter = a == 0 && d == 0 && abs(b) == one && b == -c
        return when {
            upright -> sized(w, h)
            quarter -> sized(h, w)
            else -> null
        }
    }

    private fun sized(w: Double, h: Double): Size? =
        if (w.isFinite() && h.isFinite() && w >= 1 && h >= 1) Size(w.roundToInt(), h.roundToInt()) else null

    private suspend fun mvhdCreation(r: Reader, moov: Box): Long? {
        val mvhd = r.child(moov, "mvhd") ?: return null
        val b = r.view(mvhd.body, 12) ?: return null
        if (b.size < 8) return null
        val seconds = if (b[0].toInt() == 1) (if (b.size < 12) return null else (u32(b, 4) shl 32) + u32(b, 8)) else u32(b, 4)
        return (seconds - EPOCH_1904) * 1000
    }

    private suspend fun quickTimeKey(r: Reader, meta: Box, key: String): String? {
        val first = r.view(meta.body, 12) ?: return null
        if (first.size < 12) return null
        val body = when {
            fourcc(first, 4) in META_CHILDREN -> meta.body
            fourcc(first, 8) in META_CHILDREN -> meta.body + 4
            else -> return null
        }
        val atoms = Box("", body, meta.end)
        val keys = r.child(atoms, "keys") ?: return null
        val index = keyIndex(r, keys, key) ?: return null
        val ilst = r.child(atoms, "ilst") ?: return null
        var at = ilst.body
        while (at < ilst.end) {
            val item = r.boxAt(at, ilst.end) ?: return null
            val id = r.view(at + 4, 4)
            if (id != null && id.size == 4 && u32(id, 0) == index.toLong()) return dataText(r, item)
            at = item.end
        }
        return null
    }

    private suspend fun keyIndex(r: Reader, keys: Box, key: String): Int? {
        val count = r.view(keys.body + 4, 4) ?: return null
        if (count.size < 4) return null
        var at = keys.body + 8
        var i = 1
        while (i <= u32(count, 0) && at + 8 <= keys.end && r.visit()) {
            val h = r.view(at, 8) ?: return null
            if (h.size < 8) return null
            val size = u32(h, 0)
            if (size < 8 || at + size > keys.end) return null
            if (fourcc(h, 4) == "mdta" && size - 8 == key.length.toLong()) {
                val name = r.view(at + 8, (size - 8).toInt())
                if (name != null && name.decodeToString() == key) return i
            }
            at += size
            i++
        }
        return null
    }

    private suspend fun dataText(r: Reader, item: Box): String? {
        val data = r.child(item, "data") ?: return null
        val length = data.end - data.body
        if (length < 8 || length > 256) return null
        val b = r.view(data.body, length.toInt()) ?: return null
        if (b.size < 8 || u32(b, 0) != 1L) return null
        return runCatching { b.decodeToString(8, b.size, throwOnInvalidSequence = true) }.getOrNull()
    }

    /** `YYYY-MM-DDTHH:MM:SS[.fff](Z|±HH[:]MM)` in epoch ms; null without a zone, since a local time names no instant. */
    internal fun parseIsoTime(text: String?): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|([+-])(\d{2}):?(\d{2}))$""").find(text ?: return null) ?: return null
        val g = m.groupValues
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        val h = g[4].toInt()
        val mi = g[5].toInt()
        val s = g[6].toInt()
        if (mo !in 1..12 || d < 1 || d > daysIn(y, mo) || h >= 24 || mi >= 60 || s >= 60) return null
        val local = (daysFromEpoch(y, mo, d) * 86_400L + h * 3600L + mi * 60L + s) * 1000
        val offset = if (g[7] == "Z") 0L else (if (g[8] == "-") -1 else 1) * (g[9].toLong() * 60 + g[10].toLong()) * 60_000
        return local - offset
    }

    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    private fun daysIn(y: Int, m: Int) = when (m) { 2 -> if (leap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    /** Days from 1970-01-01 to [y]-[m]-[d] (proleptic Gregorian). */
    private fun daysFromEpoch(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    private fun i32(b: ByteArray, at: Int): Int = u32(b, at).toInt()
    private fun fourcc(b: ByteArray, at: Int) = CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }.concatToString()
}
