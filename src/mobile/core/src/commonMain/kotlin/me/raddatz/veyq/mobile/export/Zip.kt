package me.raddatz.veyq.mobile.export

/**
 * A store-only ZIP writer (ADR 0082 decision 3), every client's, the web's through `@veyq/core-js/export` (ADR 0155
 * decision 7.2): no compression, no dependency, entries one after another into a [sink], each record handed on as it is
 * made — the writer holds one chunk and a directory line per entry, never the archive. An entry's CRC-32 is not known when
 * its header is written, so the header sets the data-descriptor flag and the sizes and CRC follow the data; its size IS
 * declared up front, which is how the writer knows whether the entry needs ZIP64 before its first byte, and a source that
 * delivers another number of bytes fails the archive rather than writing a lie. ZIP64 is used exactly where a field would
 * overflow and nowhere else, so a small export opens in every tool. [length] gives the archive's exact length before the
 * first byte from the same record lengths the writer allocates through, so the two cannot drift. Held byte for byte by
 * `contracts/platform/export/v1-vectors.json`.
 */
class ZipWriter(private val sink: suspend (ByteArray) -> Unit, modifiedMs: Long, private val zip64At: Long = U32_MAX) {
    private class Entry(val name: ByteArray, val crc: Int, val size: Long, val offset: Long, val zip64: Boolean, val dir: Boolean)

    private var offset = 0L
    private val entries = ArrayList<Entry>()
    private val names = HashSet<String>()
    private var ended = false
    private val stamp = dosStamp(modifiedMs)
    private val time = stamp and 0xffff
    private val date = stamp ushr 16

    private suspend fun put(bytes: ByteArray) {
        sink(bytes)
        offset += bytes.size
    }

    /**
     * One file of [size] bytes, delivered by [chunks] in order; its CRC-32 (unsigned) once written. Refused
     * ([ZipReader.ZipError]) where they add up to anything but [size] (`size_mismatch`), for a path written twice
     * (`duplicate`) and for a size or a path that is not plain (`corrupt`).
     */
    suspend fun addFile(path: String, size: Long, chunks: suspend (put: suspend (ByteArray) -> Unit) -> Unit): Long {
        if (size < 0) throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "bad size for $path")
        val name = claim(path, path)
        val zip64 = size >= zip64At
        val entryOffset = offset
        val head = Le(localFileHeaderLength(name.size, zip64))
        head.u32(SIG_LOCAL).u16(if (zip64) VERSION_ZIP64 else VERSION_PLAIN).u16(FLAG_DESCRIPTOR or FLAG_UTF8).u16(0)
            .u16(time).u16(date).u32(0).u32(if (zip64) -1 else 0).u32(if (zip64) -1 else 0)
            .u16(name.size).u16(if (zip64) 20 else 0).bytes(name)
        if (zip64) head.u16(EXTRA_ZIP64).u16(16).u64(0).u64(0)
        put(head.done())
        var crc = Crc32.START_INT
        var written = 0L
        chunks { chunk ->
            if (chunk.isNotEmpty()) {
                written += chunk.size
                if (written > size) throw ZipReader.ZipError(ZipReader.ZipError.Code.SIZE_MISMATCH, "$path delivered more than the declared $size bytes")
                crc = Crc32.update(crc, chunk)
                put(chunk)
            }
        }
        if (written != size) throw ZipReader.ZipError(ZipReader.ZipError.Code.SIZE_MISMATCH, "$path delivered $written bytes, declared $size")
        val final = Crc32.finish(crc)
        val desc = Le(descriptorLength(zip64)).u32(SIG_DESCRIPTOR).u32(final)
        if (zip64) desc.u64(size).u64(size) else desc.u32(size.toInt()).u32(size.toInt())
        put(desc.done())
        entries += Entry(name, final, size, entryOffset, zip64, dir = false)
        return final.toLong() and 0xffffffffL
    }

    /** A small entry held whole (the manifest, a CSV). */
    suspend fun addBytes(path: String, bytes: ByteArray): Long = addFile(path, bytes.size.toLong()) { it(bytes) }

    /** A directory entry (`path/`, no bytes), written without a descriptor: its CRC and sizes (all 0) are known. */
    suspend fun addDirectory(path: String) {
        val name = claim(path, "$path/")
        val entryOffset = offset
        put(Le(localDirectoryHeaderLength(name.size)).u32(SIG_LOCAL).u16(VERSION_PLAIN).u16(FLAG_UTF8).u16(0).u16(time).u16(date)
            .u32(0).u32(0).u32(0).u16(name.size).u16(0).bytes(name).done())
        entries += Entry(name, 0, 0, entryOffset, zip64 = false, dir = true)
    }

    /** A plain relative path, written once: this writer is the last line against an archive a reader would have to distrust. */
    private fun claim(path: String, stored: String): ByteArray {
        if (ended) throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "archive already finished")
        if (!isPlainPath(path)) throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "unsafe path \"$path\"")
        if (stored in names) throw ZipReader.ZipError(ZipReader.ZipError.Code.DUPLICATE, "path written twice: $stored")
        val name = utf8(stored)
        if (name.size > U16_MAX) throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "path too long")
        names += stored
        return name
    }

    /** The central directory and the end records. */
    suspend fun finish() {
        if (ended) throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "archive already finished")
        ended = true
        val cdStart = offset
        for (e in entries) {
            val bigOffset = e.offset >= zip64At
            val fields = centralZip64Fields(e.zip64, bigOffset)
            val rec = Le(centralRecordLength(e.name.size, fields))
            val version = if (fields > 0) VERSION_ZIP64 else VERSION_PLAIN
            rec.u32(SIG_CENTRAL).u16(version).u16(version)
                .u16(if (e.dir) FLAG_UTF8 else FLAG_DESCRIPTOR or FLAG_UTF8).u16(0).u16(time).u16(date).u32(e.crc)
                .u32(if (e.zip64) -1 else e.size.toInt()).u32(if (e.zip64) -1 else e.size.toInt())
                .u16(e.name.size).u16(if (fields > 0) 4 + fields * 8 else 0).u16(0).u16(0).u16(0)
                .u32(if (e.dir) 0x10 else 0).u32(if (bigOffset) -1 else e.offset.toInt()).bytes(e.name)
            if (fields > 0) {
                rec.u16(EXTRA_ZIP64).u16(fields * 8)
                if (e.zip64) rec.u64(e.size).u64(e.size)
                if (bigOffset) rec.u64(e.offset)
            }
            put(rec.done())
        }
        val cdSize = offset - cdStart
        val count = entries.size
        val big = count >= U16_MAX || cdStart >= zip64At || cdSize >= zip64At
        if (big) {
            val at = offset
            put(Le(ZIP64_END_LENGTH).u32(SIG_EOCD64).u64(44).u16(VERSION_ZIP64).u16(VERSION_ZIP64).u32(0).u32(0)
                .u64(count.toLong()).u64(count.toLong()).u64(cdSize).u64(cdStart).u32(SIG_LOCATOR64).u32(0).u64(at).u32(1).done())
        }
        put(Le(END_LENGTH).u32(SIG_EOCD).u16(0).u16(0).u16(if (big) U16_MAX else count).u16(if (big) U16_MAX else count)
            .u32(if (big) -1 else cdSize.toInt()).u32(if (big) -1 else cdStart.toInt()).u16(0).done())
    }

    /** Ends the archive unwritten: whatever was written is the caller's to discard, and nothing more is taken. */
    fun abort() {
        ended = true
    }

    /** Little-endian record builder of a fixed length; [done] checks every byte was written. */
    private class Le(size: Int) {
        private val b = ByteArray(size)
        private var at = 0
        fun u16(v: Int) = apply { b[at++] = v.toByte(); b[at++] = (v shr 8).toByte() }
        fun u32(v: Int) = apply { b[at++] = v.toByte(); b[at++] = (v shr 8).toByte(); b[at++] = (v shr 16).toByte(); b[at++] = (v shr 24).toByte() }
        fun u64(v: Long) = u32(v.toInt()).u32((v ushr 32).toInt())
        fun bytes(v: ByteArray) = apply { v.copyInto(b, at); at += v.size }
        fun done(): ByteArray = b.also { check(at == b.size) { "record of ${b.size} bytes filled to $at" } }
    }

    /** One entry of a planned archive, in the order it will be written: a file of [size] bytes, or a directory (`path/`). */
    class Planned(val path: String, val size: Long, val directory: Boolean = false)

    companion object {
        const val U32_MAX = 0xffffffffL
        private const val U16_MAX = 0xffff
        private const val SIG_LOCAL = 0x04034b50
        private const val SIG_DESCRIPTOR = 0x08074b50
        private const val SIG_CENTRAL = 0x02014b50
        private const val SIG_EOCD = 0x06054b50
        private const val SIG_EOCD64 = 0x06064b50
        private const val SIG_LOCATOR64 = 0x07064b50
        private const val FLAG_DESCRIPTOR = 0x0008
        private const val FLAG_UTF8 = 0x0800
        private const val EXTRA_ZIP64 = 0x0001
        private const val VERSION_PLAIN = 20
        private const val VERSION_ZIP64 = 45
        /** The ZIP64 end record (56) and its locator (20); the classic end record (22) always follows. */
        private const val ZIP64_END_LENGTH = 56 + 20
        private const val END_LENGTH = 22

        // The length of every record, from what is known before a byte is written: a name's UTF-8 length, whether the entry
        // or an offset needs ZIP64. The writer allocates through these and [length] adds them up.
        private fun localFileHeaderLength(nameLength: Int, zip64: Boolean) = 30 + nameLength + if (zip64) 20 else 0
        private fun localDirectoryHeaderLength(nameLength: Int) = 30 + nameLength
        private fun descriptorLength(zip64: Boolean) = if (zip64) 24 else 16
        /** usize+csize travel together in the ZIP64 extra, the offset alone. */
        private fun centralZip64Fields(zip64: Boolean, bigOffset: Boolean) = (if (zip64) 2 else 0) + (if (bigOffset) 1 else 0)
        private fun centralRecordLength(nameLength: Int, fields: Int) = 46 + nameLength + if (fields > 0) 4 + fields * 8 else 0
        private fun endRecordsLength(big: Boolean) = (if (big) ZIP64_END_LENGTH else 0) + END_LENGTH

        /**
         * [s] as UTF-8 with a lone surrogate as U+FFFD — the bytes the web's `TextEncoder` writes, on every platform (the
         * JVM's own encoder writes `?` for one).
         */
        internal fun utf8(s: String): ByteArray {
            fun pairAt(i: Int) = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i].code
                n += when {
                    c < 0x80 -> 1
                    c < 0x800 -> 2
                    pairAt(i) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            val out = ByteArray(n)
            var at = 0
            i = 0
            while (i < s.length) {
                var c = s[i].code
                when {
                    c < 0x80 -> out[at++] = c.toByte()
                    c < 0x800 -> { out[at++] = (0xc0 or (c shr 6)).toByte(); out[at++] = (0x80 or (c and 0x3f)).toByte() }
                    pairAt(i) -> {
                        c = 0x10000 + ((c - 0xd800) shl 10) + (s[++i].code - 0xdc00)
                        out[at++] = (0xf0 or (c shr 18)).toByte(); out[at++] = (0x80 or ((c shr 12) and 0x3f)).toByte()
                        out[at++] = (0x80 or ((c shr 6) and 0x3f)).toByte(); out[at++] = (0x80 or (c and 0x3f)).toByte()
                    }
                    else -> {
                        if (c in 0xd800..0xdfff) c = 0xfffd
                        out[at++] = (0xe0 or (c shr 12)).toByte(); out[at++] = (0x80 or ((c shr 6) and 0x3f)).toByte(); out[at++] = (0x80 or (c and 0x3f)).toByte()
                    }
                }
                i++
            }
            return out
        }

        /**
         * A declared size as a number hands it (the web's): a safe integer of at least 0, else `corrupt` — checked before the
         * entry's path, as the web's writer did.
         */
        fun declaredSize(size: Double, path: String): Long {
            if (!(size >= 0 && size <= 9007199254740991.0 && size == kotlin.math.floor(size))) {
                throw ZipReader.ZipError(ZipReader.ZipError.Code.CORRUPT, "bad size for $path")
            }
            return size.toLong()
        }

        /** A path the writer takes: relative, of non-empty segments none `.` or `..`, no backslash. */
        private fun isPlainPath(path: String): Boolean {
            if (path.isEmpty() || path[0] == '/' || '\\' in path) return false
            var start = 0
            while (true) {
                val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
                val n = end - start
                if (n == 0 || (n == 1 && path[start] == '.') || (n == 2 && path[start] == '.' && path[start + 1] == '.')) return false
                if (end == path.length) return true
                start = end + 1
            }
        }

        /**
         * The exact length in bytes of the archive a [ZipWriter] writes for [entries] (same order, same [zip64At]): local
         * headers, the bytes, data descriptors, the central directory and the end records — known before the first byte,
         * since every record's length depends only on a name's length and a size. A save target told the length up front
         * (the web's download route's `Content-Length`) is given the number the writer then produces.
         */
        fun length(entries: List<Planned>, zip64At: Long = U32_MAX): Long {
            var offset = 0L
            var directory = 0L
            for (e in entries) {
                val nameLength = utf8(if (e.directory) "${e.path}/" else e.path).size
                val zip64 = !e.directory && e.size >= zip64At
                directory += centralRecordLength(nameLength, centralZip64Fields(zip64, offset >= zip64At))
                offset += if (e.directory) localDirectoryHeaderLength(nameLength).toLong() else localFileHeaderLength(nameLength, zip64) + e.size + descriptorLength(zip64)
            }
            val big = entries.size >= U16_MAX || offset >= zip64At || directory >= zip64At
            return offset + directory + endRecordsLength(big)
        }

        /**
         * The DOS date (high 16 bits) and time (low 16) of [ms] — UTC, 2-second resolution, the year held to the format's
         * 1980..2107 and the month and day kept as they are, as the web's `Date` getters gave them.
         */
        internal fun dosStamp(ms: Long): Int {
            val dayMs = 86_400_000L
            val days = ms.floorDiv(dayMs)
            val inDay = (ms - days * dayMs).toInt() / 1000
            // the proleptic Gregorian civil date of a day count (Howard Hinnant's `civil_from_days`)
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = (z - era * 146_097).toInt()
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).coerceIn(1980L, 2107L).toInt()
            val date = ((year - 1980) shl 9) or (month shl 5) or day
            val time = ((inDay / 3600) shl 11) or ((inDay / 60 % 60) shl 5) or (inDay % 60 shr 1)
            return (date shl 16) or time
        }
    }
}

/**
 * CRC-32 (IEEE 802.3, every ZIP tool's polynomial), incremental: fed chunk by chunk while the bytes stream past. The state
 * is an `Int` (a `Long` is emulated on JavaScript, where this runs over every byte of a download); [of] and the `Long`
 * forms answer the unsigned value.
 */
object Crc32 {
    const val START = 0xffffffffL
    internal const val START_INT = -1
    private val TABLE = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) c = if (c and 1 != 0) -0x12477ce0 xor (c ushr 1) else c ushr 1 // 0xEDB88320
            t[n] = c
        }
    }

    /** [state] fed with [bytes]. */
    fun update(state: Int, bytes: ByteArray): Int {
        val t = TABLE
        var c = state
        for (i in bytes.indices) c = t[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
        return c
    }

    fun finish(state: Int): Int = state.inv()

    fun update(state: Long, bytes: ByteArray): Long = update(state.toInt(), bytes).toLong() and 0xffffffffL
    fun finish(state: Long): Long = state xor 0xffffffffL
    fun of(bytes: ByteArray): Long = finish(update(START_INT, bytes)).toLong() and 0xffffffffL
}
