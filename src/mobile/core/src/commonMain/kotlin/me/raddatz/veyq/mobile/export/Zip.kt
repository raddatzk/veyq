package me.raddatz.veyq.mobile.export

/**
 * A store-only ZIP writer, a port of the web's `core/export/zip.ts` (ADR 0082 decision 3): no compression, no dependency,
 * entries one after another into a [sink]. An entry's CRC-32 is not known when its header is written, so the header sets the
 * data-descriptor flag and the sizes and CRC follow the data; its size IS declared up front, which is how the writer knows
 * whether the entry needs ZIP64 before its first byte, and a source that delivers another number of bytes fails the archive
 * rather than writing a lie. ZIP64 is used exactly where a field would overflow and nowhere else, so a small export opens in
 * every tool. The records are the web writer's, field for field: what one client writes the other's reader takes.
 */
class ZipWriter(private val sink: (ByteArray) -> Unit, modifiedMs: Long, private val zip64At: Long = U32_MAX) {
    class ZipFailure(message: String) : Exception(message)

    private class Entry(val name: ByteArray, val crc: Long, val size: Long, val offset: Long, val zip64: Boolean, val dir: Boolean)

    private var offset = 0L
    private val entries = mutableListOf<Entry>()
    private val names = mutableSetOf<String>()
    private var ended = false
    private val time: Int
    private val date: Int

    init {
        val (d, t) = dosStamp(modifiedMs)
        date = d
        time = t
    }

    private fun put(bytes: ByteArray) {
        sink(bytes)
        offset += bytes.size
    }

    /** One file of [size] bytes, delivered by [chunks] in order; refused when they add up to anything but [size]. */
    suspend fun addFile(path: String, size: Long, chunks: suspend ((ByteArray) -> Unit) -> Unit): Long {
        require(size >= 0) { "bad size for $path" }
        val name = claim(path, path)
        val zip64 = size >= zip64At
        val entryOffset = offset
        val head = Le(30 + name.size + if (zip64) 20 else 0)
        head.u32(SIG_LOCAL).u16(if (zip64) VERSION_ZIP64 else VERSION_PLAIN).u16(FLAG_DESCRIPTOR or FLAG_UTF8).u16(0)
            .u16(time).u16(date).u32(0).u32(if (zip64) U32_MAX else 0L).u32(if (zip64) U32_MAX else 0L)
            .u16(name.size).u16(if (zip64) 20 else 0).bytes(name)
        if (zip64) head.u16(EXTRA_ZIP64).u16(16).u64(0).u64(0)
        put(head.done())
        var crc = Crc32.START
        var written = 0L
        chunks { chunk ->
            if (chunk.isEmpty()) return@chunks
            written += chunk.size.toLong()
            if (written > size) throw ZipFailure("$path delivered more than the declared $size bytes")
            crc = Crc32.update(crc, chunk)
            put(chunk)
        }
        if (written != size) throw ZipFailure("$path delivered $written bytes, declared $size")
        val final = Crc32.finish(crc)
        val desc = Le(if (zip64) 24 else 16).u32(SIG_DESCRIPTOR).u32(final)
        if (zip64) desc.u64(size).u64(size) else desc.u32(size).u32(size)
        put(desc.done())
        entries += Entry(name, final, size, entryOffset, zip64, dir = false)
        return final
    }

    /** A small entry held whole (the manifest, a CSV). */
    suspend fun addBytes(path: String, bytes: ByteArray): Long = addFile(path, bytes.size.toLong()) { it(bytes) }

    /** A directory entry (`path/`, no bytes), written without a descriptor: its CRC and sizes (all 0) are known. */
    fun addDirectory(path: String) {
        val name = claim(path, "$path/")
        val entryOffset = offset
        put(Le(30 + name.size).u32(SIG_LOCAL).u16(VERSION_PLAIN).u16(FLAG_UTF8).u16(0).u16(time).u16(date)
            .u32(0).u32(0).u32(0).u16(name.size).u16(0).bytes(name).done())
        entries += Entry(name, 0, 0, entryOffset, zip64 = false, dir = true)
    }

    /** A plain relative path, written once: this writer is the last line against an archive a reader would have to distrust. */
    private fun claim(path: String, stored: String): ByteArray {
        if (ended) throw ZipFailure("archive already finished")
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            throw ZipFailure("unsafe path \"$path\"")
        }
        if (!names.add(stored)) throw ZipFailure("path written twice: $stored")
        val name = stored.encodeToByteArray()
        if (name.size > U16_MAX) throw ZipFailure("path too long")
        return name
    }

    /** The central directory and the end records. */
    fun finish() {
        if (ended) throw ZipFailure("archive already finished")
        ended = true
        val cdStart = offset
        for (e in entries) {
            val bigOffset = e.offset >= zip64At
            val fields = (if (e.zip64) 2 else 0) + (if (bigOffset) 1 else 0)
            val rec = Le(46 + e.name.size + if (fields > 0) 4 + fields * 8 else 0)
            rec.u32(SIG_CENTRAL).u16(if (fields > 0) VERSION_ZIP64 else VERSION_PLAIN).u16(if (fields > 0) VERSION_ZIP64 else VERSION_PLAIN)
                .u16(if (e.dir) FLAG_UTF8 else FLAG_DESCRIPTOR or FLAG_UTF8).u16(0).u16(time).u16(date).u32(e.crc)
                .u32(if (e.zip64) U32_MAX else e.size).u32(if (e.zip64) U32_MAX else e.size)
                .u16(e.name.size).u16(if (fields > 0) 4 + fields * 8 else 0).u16(0).u16(0).u16(0)
                .u32(if (e.dir) 0x10 else 0).u32(if (bigOffset) U32_MAX else e.offset).bytes(e.name)
            if (fields > 0) {
                rec.u16(EXTRA_ZIP64).u16(fields * 8)
                if (e.zip64) rec.u64(e.size).u64(e.size)
                if (bigOffset) rec.u64(e.offset)
            }
            put(rec.done())
        }
        val cdSize = offset - cdStart
        val count = entries.size.toLong()
        val big = count >= U16_MAX || cdStart >= zip64At || cdSize >= zip64At
        if (big) {
            val at = offset
            put(Le(76).u32(SIG_EOCD64).u64(44).u16(VERSION_ZIP64).u16(VERSION_ZIP64).u32(0).u32(0).u64(count).u64(count)
                .u64(cdSize).u64(cdStart).u32(SIG_LOCATOR64).u32(0).u64(at).u32(1).done())
        }
        put(Le(22).u32(SIG_EOCD).u16(0).u16(0).u16(if (big) U16_MAX else count.toInt()).u16(if (big) U16_MAX else count.toInt())
            .u32(if (big) U32_MAX else cdSize).u32(if (big) U32_MAX else cdStart).u16(0).done())
    }

    /** Little-endian record builder of a fixed length; [done] checks every byte was written. */
    private class Le(size: Int) {
        private val b = ByteArray(size)
        private var at = 0
        fun u16(v: Int) = apply { b[at++] = v.toByte(); b[at++] = (v shr 8).toByte() }
        fun u32(v: Long) = apply { for (i in 0 until 4) b[at++] = (v shr (8 * i)).toByte() }
        fun u32(v: Int) = u32(v.toLong() and 0xffffffffL)
        fun u64(v: Long) = apply { for (i in 0 until 8) b[at++] = (v shr (8 * i)).toByte() }
        fun bytes(v: ByteArray) = apply { v.copyInto(b, at); at += v.size }
        fun done(): ByteArray = b.also { check(at == b.size) { "record of ${b.size} bytes filled to $at" } }
    }

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

        /** The DOS date and time of [ms] (UTC, 2-second resolution), the year held to the format's 1980..2107. */
        internal fun dosStamp(ms: Long): Pair<Int, Int> {
            val t = kotlin.time.Instant.fromEpochMilliseconds(ms).toString() // 2026-11-09T09:30:12.345Z
            val year = t.substring(0, 4).toInt().coerceIn(1980, 2107)
            val month = t.substring(5, 7).toInt()
            val day = t.substring(8, 10).toInt()
            val hour = t.substring(11, 13).toInt()
            val minute = t.substring(14, 16).toInt()
            val second = t.substring(17, 19).toInt()
            return (((year - 1980) shl 9) or (month shl 5) or day) to ((hour shl 11) or (minute shl 5) or (second shr 1))
        }
    }
}

/** CRC-32 (IEEE 802.3, every ZIP tool's polynomial), incremental: fed chunk by chunk while the bytes stream past. */
object Crc32 {
    const val START = 0xffffffffL
    private val TABLE = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xedb88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    fun update(state: Long, bytes: ByteArray): Long {
        var c = state
        for (x in bytes) c = TABLE[((c xor x.toLong()) and 0xff).toInt()] xor (c ushr 8)
        return c
    }

    fun finish(state: Long): Long = state xor 0xffffffffL
    fun of(bytes: ByteArray): Long = finish(update(START, bytes))
}
