package me.raddatz.veyq.mobile.export

/**
 * A store-only ZIP read without being held (ADR 0152 decision 2), a port of the web's `core/export/zip.ts` reader, refusal
 * for refusal: the archive is untrusted input (ADR 0082 decision 10), so [open] checks the STRUCTURE before anything is read
 * from an entry — the end records and every ZIP64 field in range, the directory inside the file and before its end record,
 * and for every entry its local header (signature, the same name), its bytes inside the file and before the directory, the
 * sizes and CRC its header or data descriptor declares equal to the directory's, and no two entries' spans overlapping. What
 * this format never produces is refused rather than guessed at: a compressed or encrypted entry, a multi-disk archive, a name
 * given twice or not UTF-8. Nothing but the end of the file, the directory and each entry's header and descriptor is read
 * here; an entry's bytes are read when asked for, [READ_CHUNK] at a time, through the source's own `read(from, length)`.
 * Both clients' readers answer to `contracts/export/import-cases`.
 */
class ZipReader private constructor(private val read: Reader, val size: Long, private val byName: LinkedHashMap<String, Entry>) {
    /** Bytes of a file at any offset: up to [length] from [from] (fewer only at its end), as `UploadSource.read`. */
    fun interface Reader {
        suspend fun read(from: Long, length: Int): ByteArray
    }

    /** Anything wrong with an archive being read; [code] is stable for callers, the message is for developers. */
    class ZipError(val code: Code, message: String) : Exception(message) {
        enum class Code { NOT_ZIP, TRUNCATED, CORRUPT, UNSUPPORTED, CRC_MISMATCH, DUPLICATE, MISSING }
    }

    /** One file of the archive: its name, its size, the CRC-32 its directory records, and where its bytes start. */
    class Entry(val name: String, val size: Long, val crc32: Long, val offset: Long, val dataAt: Long)

    val entries: List<Entry> get() = byName.values.toList()

    fun has(name: String): Boolean = name in byName

    fun entry(name: String): Entry? = byName[name]

    private fun need(name: String): Entry = byName[name] ?: throw ZipError(ZipError.Code.MISSING, "no entry $name")

    /**
     * An entry's bytes to [sink], [READ_CHUNK] at a time; at the end its CRC-32 is checked against the directory. The check
     * runs after the last chunk was handed on, so a caller must treat a [ZipError.Code.CRC_MISMATCH] as "everything taken
     * from this entry is bad": an import checks a payload whole before a hook takes it.
     */
    suspend fun stream(name: String, sink: suspend (ByteArray) -> Unit) {
        val e = need(name)
        var crc = Crc32.START
        var at = e.dataAt
        val end = e.dataAt + e.size
        while (at < end) {
            val chunk = readAt(read, size, at, minOf(at + READ_CHUNK, end))
            crc = Crc32.update(crc, chunk)
            sink(chunk)
            at += chunk.size
        }
        if (Crc32.finish(crc) != e.crc32) throw ZipError(ZipError.Code.CRC_MISMATCH, "$name does not match its checksum")
    }

    /** [length] bytes of entry [name] from [from] within it (fewer only at its end): seekable, its checksum NOT checked. */
    suspend fun readEntry(name: String, from: Long, length: Int): ByteArray {
        val e = need(name)
        if (from < 0 || from > e.size) throw ZipError(ZipError.Code.TRUNCATED, "read outside $name")
        val until = minOf(e.size, from + length)
        return readAt(read, size, e.dataAt + from, e.dataAt + until)
    }

    /** A small entry (the manifest) read whole, refused above [max] bytes; its checksum checked. */
    suspend fun bytes(name: String, max: Long): ByteArray {
        val e = need(name)
        if (e.size > max) throw ZipError(ZipError.Code.UNSUPPORTED, "$name is larger than $max bytes")
        val out = ByteArray(e.size.toInt())
        var at = 0
        stream(name) { chunk ->
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }

    companion object {
        /** How much of an entry one read takes: the memory bound of a streamed read (the web's `READ_CHUNK`). */
        const val READ_CHUNK = 1024 * 1024

        private const val U32_MAX = 0xffffffffL
        private const val U16_MAX = 0xffff
        private const val SIG_LOCAL = 0x04034b50L
        private const val SIG_DESCRIPTOR = 0x08074b50L
        private const val SIG_CENTRAL = 0x02014b50L
        private const val SIG_EOCD = 0x06054b50L
        private const val SIG_EOCD64 = 0x06064b50L
        private const val SIG_LOCATOR64 = 0x07064b50L
        private const val FLAG_DESCRIPTOR = 0x0008
        private const val FLAG_ENCRYPTED = 0x0001
        private const val EXTRA_ZIP64 = 0x0001
        private const val MAX_TAIL = 22 + U16_MAX
        /** JavaScript's `Number.MAX_SAFE_INTEGER`: the web refuses a ZIP64 value above it, and so does this reader. */
        private const val MAX_SAFE = 9007199254740991L

        private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        private fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

        /** A little-endian u64, refused where it is above what the web reads (`toNumber`). */
        private fun u64(b: ByteArray, at: Int, what: String): Long {
            val high = u32(b, at + 4)
            if (high > (MAX_SAFE ushr 32)) throw ZipError(ZipError.Code.UNSUPPORTED, "$what too large")
            return u32(b, at) or (high shl 32)
        }

        /** Exactly the bytes [start] until [end] of a file of [size]: a read outside it is `truncated`. */
        private suspend fun readAt(read: Reader, size: Long, start: Long, end: Long): ByteArray {
            if (start < 0 || end > size || start > end) throw ZipError(ZipError.Code.TRUNCATED, "read outside the file")
            val length = end - start
            if (length > Int.MAX_VALUE) throw ZipError(ZipError.Code.UNSUPPORTED, "a read too large to hold")
            val out = ByteArray(length.toInt())
            var at = 0
            while (at < out.size) {
                val got = read.read(start + at, out.size - at)
                if (got.isEmpty()) throw ZipError(ZipError.Code.TRUNCATED, "the file ended early")
                got.copyInto(out, at, 0, minOf(got.size, out.size - at))
                at += got.size
            }
            return out
        }

        private class Central(val name: String, val nameBytes: ByteArray, val crc: Long, val size: Long, val offset: Long)

        /** The UTF-8 name of [bytes], refused where it is not well-formed (the web's `TextDecoder` with `fatal`). */
        private fun utf8(bytes: ByteArray): String? {
            val s = bytes.decodeToString()
            return if (s.encodeToByteArray().contentEquals(bytes)) s else null
        }

        /**
         * Opens the archive of [size] bytes [read] reads, without reading its entries' bytes: the end record, the central
         * directory (one line per entry), each entry's local header and data descriptor, and the structure checked (see the
         * header). Throws [ZipError].
         */
        suspend fun open(size: Long, read: Reader): ZipReader {
            if (size < 22) throw ZipError(ZipError.Code.NOT_ZIP, "too small to be an archive")
            val tailStart = maxOf(0L, size - MAX_TAIL)
            val tail = readAt(read, size, tailStart, size)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (u32(tail, i) == SIG_EOCD && i + 22 + u16(tail, i + 20) == tail.size) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw ZipError(ZipError.Code.NOT_ZIP, "no end-of-central-directory record")
            if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0) throw ZipError(ZipError.Code.UNSUPPORTED, "multi-disk archive")
            var count = u16(tail, eocd + 10).toLong()
            var cdSize = u32(tail, eocd + 12)
            var cdOffset = u32(tail, eocd + 16)
            // where the directory must end: the end record, or the ZIP64 record before it
            var directoryEnd = tailStart + eocd
            if (count == U16_MAX.toLong() || cdSize == U32_MAX || cdOffset == U32_MAX) {
                val at = tailStart + eocd - 20
                val loc = if (at >= 0) readAt(read, size, at, at + 20) else null
                if (loc == null || u32(loc, 0) != SIG_LOCATOR64) throw ZipError(ZipError.Code.CORRUPT, "zip64 locator missing")
                val recAt = u64(loc, 8, "zip64 record offset")
                if (recAt + 56 > at) throw ZipError(ZipError.Code.CORRUPT, "zip64 record outside the file")
                val rec = readAt(read, size, recAt, recAt + 56)
                if (u32(rec, 0) != SIG_EOCD64) throw ZipError(ZipError.Code.CORRUPT, "zip64 record missing")
                count = u64(rec, 32, "entry count")
                cdSize = u64(rec, 40, "directory size")
                cdOffset = u64(rec, 48, "directory offset")
                directoryEnd = recAt
            }
            if (cdOffset + cdSize > directoryEnd) throw ZipError(ZipError.Code.TRUNCATED, "directory outside the file")
            if (count * 46 > cdSize) throw ZipError(ZipError.Code.CORRUPT, "more entries than the directory holds")
            val cd = readAt(read, size, cdOffset, cdOffset + cdSize)
            val centrals = ArrayList<Central>()
            val names = HashSet<String>()
            var p = 0
            for (n in 0 until count) {
                if (p + 46 > cd.size || u32(cd, p) != SIG_CENTRAL) throw ZipError(ZipError.Code.CORRUPT, "bad directory entry")
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                val crc = u32(cd, p + 16)
                var csize = u32(cd, p + 20)
                var usize = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                val disk = u16(cd, p + 34)
                var offset = u32(cd, p + 42)
                val next = p + 46 + nameLen + extraLen + commentLen
                if (next > cd.size) throw ZipError(ZipError.Code.CORRUPT, "directory entry runs past the directory")
                if (flags and FLAG_ENCRYPTED != 0 || method != 0) throw ZipError(ZipError.Code.UNSUPPORTED, "only plain stored entries are read")
                if (disk != 0 && disk != U16_MAX) throw ZipError(ZipError.Code.UNSUPPORTED, "multi-disk archive")
                val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
                val name = utf8(nameBytes) ?: throw ZipError(ZipError.Code.CORRUPT, "entry name is not UTF-8")
                // the ZIP64 extra carries, in this order and only for a field that reads 0xFFFFFFFF: sizes, then the offset
                var x = p + 46 + nameLen
                val xEnd = x + extraLen
                while (x + 4 <= xEnd) {
                    val id = u16(cd, x)
                    val len = u16(cd, x + 2)
                    if (x + 4 + len > xEnd) throw ZipError(ZipError.Code.CORRUPT, "extra field runs past its entry")
                    if (id == EXTRA_ZIP64) {
                        var f = x + 4
                        fun take(cond: Boolean, what: String): Long? {
                            if (!cond) return null
                            if (f + 8 > x + 4 + len) throw ZipError(ZipError.Code.CORRUPT, "zip64 extra too short")
                            return u64(cd, f, what).also { f += 8 }
                        }
                        val u = take(usize == U32_MAX, "entry size")
                        val c = take(csize == U32_MAX, "entry size")
                        val o = take(offset == U32_MAX, "entry offset")
                        if (u != null) usize = u
                        if (c != null) csize = c
                        if (o != null) offset = o
                    }
                    x += 4 + len
                }
                if (usize == U32_MAX || csize == U32_MAX || offset == U32_MAX) throw ZipError(ZipError.Code.CORRUPT, "zip64 value missing")
                if (csize != usize) throw ZipError(ZipError.Code.UNSUPPORTED, "only plain stored entries are read")
                if (usize > size || offset >= cdOffset) throw ZipError(ZipError.Code.CORRUPT, "$name lies outside the file")
                if (!names.add(name)) throw ZipError(ZipError.Code.DUPLICATE, "entry listed twice: $name")
                centrals += Central(name, nameBytes, crc, usize, offset)
                p = next
            }

            // each entry's local header and data descriptor; then no two spans may overlap
            val entries = LinkedHashMap<String, Entry>()
            class Span(val start: Long, val end: Long, val name: String)
            val spans = ArrayList<Span>()
            for (c in centrals) {
                val headEnd = minOf(c.offset + 30 + c.nameBytes.size, cdOffset)
                val head = try {
                    readAt(read, size, c.offset, headEnd)
                } catch (e: ZipError) {
                    ByteArray(0)
                }
                if (head.size < 30 + c.nameBytes.size) throw ZipError(ZipError.Code.CORRUPT, "${c.name}: local header outside the file")
                if (u32(head, 0) != SIG_LOCAL) throw ZipError(ZipError.Code.CORRUPT, "${c.name}: bad local header")
                if (u16(head, 8) != 0) throw ZipError(ZipError.Code.UNSUPPORTED, "${c.name}: not a stored entry")
                if (u16(head, 26) != c.nameBytes.size || !head.copyOfRange(30, head.size).contentEquals(c.nameBytes)) {
                    throw ZipError(ZipError.Code.CORRUPT, "${c.name}: local name differs from the directory")
                }
                val dataAt = c.offset + 30 + c.nameBytes.size + u16(head, 28)
                val dataEnd = dataAt + c.size
                var end = dataEnd
                if (dataEnd > cdOffset) throw ZipError(ZipError.Code.CORRUPT, "${c.name} runs into the directory")
                val localFlags = u16(head, 6)
                if (localFlags and FLAG_DESCRIPTOR != 0) {
                    // a descriptor follows the bytes: an optional signature, the CRC, then the sizes (8 bytes each in ZIP64)
                    val desc = readAt(read, size, dataEnd, minOf(dataEnd + 24, cdOffset))
                    val skip = if (desc.size >= 4 && u32(desc, 0) == SIG_DESCRIPTOR) 4 else 0
                    fun u64raw(at: Int) = u32(desc, at) or (u32(desc, at + 4) shl 32)
                    val wide = desc.size >= skip + 20 && u64raw(skip + 4) == c.size && u64raw(skip + 12) == c.size
                    val narrow = desc.size >= skip + 12 && u32(desc, skip + 4) == c.size && u32(desc, skip + 8) == c.size
                    if (desc.size < skip + 12 || u32(desc, skip) != c.crc || !(wide || narrow)) {
                        throw ZipError(ZipError.Code.CORRUPT, "${c.name}: the declared size or checksum is not the directory's")
                    }
                    end = dataEnd + skip + if (wide && !narrow) 20 else 12
                } else {
                    val lcrc = u32(head, 14)
                    val lc = u32(head, 18)
                    val lu = u32(head, 22)
                    val zip64Local = lc == U32_MAX && lu == U32_MAX // its sizes are in the local ZIP64 extra: the directory's decide
                    if (lcrc != c.crc || (!zip64Local && (lc != c.size || lu != c.size))) {
                        throw ZipError(ZipError.Code.CORRUPT, "${c.name}: the declared size or checksum is not the directory's")
                    }
                }
                spans += Span(c.offset, end, c.name)
                entries[c.name] = Entry(c.name, c.size, c.crc, c.offset, dataAt)
            }
            spans.sortBy { it.start }
            for (i in 1 until spans.size) {
                if (spans[i].start < spans[i - 1].end) throw ZipError(ZipError.Code.CORRUPT, "${spans[i].name} overlaps ${spans[i - 1].name}")
            }
            return ZipReader(read, size, entries)
        }
    }
}
