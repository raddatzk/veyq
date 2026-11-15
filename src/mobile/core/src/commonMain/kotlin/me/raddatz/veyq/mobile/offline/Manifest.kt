package me.raddatz.veyq.mobile.offline

/**
 * What an entry of the offline store is, as the server names it (ADR 0140 decision 2): the server, the tenant, the
 * scope, the kind of read (a listing's operation, or a blob) and the id it was read for. It is bound into every
 * entry's seal and names its file only through an HMAC, so a file moved to another name or another store does not open.
 */
data class EntryKey(val server: String, val tenant: String, val scope: String, val kind: String, val id: String) {
    /** The five parts, each length-prefixed: one encoding for every seal, name and shard. */
    internal fun bytes(): ByteArray = Writer().apply { text(server); text(tenant); text(scope); text(kind); text(id) }.bytes()

    internal companion object {
        fun read(r: Reader) = EntryKey(r.text(), r.text(), r.text(), r.text(), r.text())
    }
}

/**
 * One entry as the manifest keeps it: its [size] on disk, when it was last viewed ([lastView], epoch milliseconds), the
 * pins that reach it, and for a listing the server's [revision] and when the server last confirmed it ([asOf]).
 */
class KeptEntry(
    val key: EntryKey,
    /** A blob's frames (one file of them), else one sealed answer. */
    val blob: Boolean,
    val size: Long,
    val lastView: Long,
    val pins: Set<String>,
    val revision: String?,
    val asOf: Long?,
)

/** The manifest in memory: per shard (the first byte of an entry's opaque name), its entries by key. */
internal class Kept(
    val key: EntryKey,
    val name: String,
    val blob: Boolean,
    var size: Long,
    var lastView: Long,
    var pins: Set<String>,
    var revision: String?,
    var asOf: Long?,
) {
    val shard: Int get() = name.substring(0, 2).toInt(16)

    fun shown() = KeptEntry(key, blob, size, lastView, pins, revision, asOf)
}

/**
 * A shard's plaintext: a version byte, the count, then each entry — its key, opaque name, flags, size, last view,
 * revision, `asOf` and pins. Binary and length-prefixed, since the whole manifest is read at every unlock (D2's bound:
 * 100,000 entries within a second on the slowest target).
 */
internal object ShardCodec {
    private const val VERSION = 1
    private const val BLOB = 1
    private const val REVISION = 2
    private const val AS_OF = 4

    fun encode(entries: Collection<Kept>): ByteArray = Writer().apply {
        byte(VERSION)
        int(entries.size)
        for (e in entries) {
            text(e.key.server); text(e.key.tenant); text(e.key.scope); text(e.key.kind); text(e.key.id)
            text(e.name)
            byte((if (e.blob) BLOB else 0) or (if (e.revision != null) REVISION else 0) or (if (e.asOf != null) AS_OF else 0))
            long(e.size)
            long(e.lastView)
            e.revision?.let { text(it) }
            e.asOf?.let { long(it) }
            int(e.pins.size)
            for (p in e.pins) text(p)
        }
    }.bytes()

    /** The entries of [bytes]; throws on anything that is not a shard this version wrote. */
    fun decode(bytes: ByteArray): List<Kept> {
        val r = Reader(bytes)
        require(r.byte() == VERSION) { "shard version" }
        val n = r.int()
        require(n >= 0) { "shard count" }
        val out = ArrayList<Kept>(n)
        repeat(n) {
            val key = EntryKey.read(r)
            val name = r.text()
            val flags = r.byte()
            val size = r.long()
            val lastView = r.long()
            val revision = if (flags and REVISION != 0) r.text() else null
            val asOf = if (flags and AS_OF != 0) r.long() else null
            val pins = r.int().let { c -> require(c >= 0) { "pin count" }; if (c == 0) emptySet() else HashSet<String>(c).apply { repeat(c) { add(r.text()) } } }
            out += Kept(key, name, flags and BLOB != 0, size, lastView, pins, revision, asOf)
        }
        require(r.done) { "shard trailing bytes" }
        return out
    }
}

/** A blob file's frame index: each frame's offset and sealed length in the file, in the order fetched. */
internal object FrameIndexCodec {
    fun encode(index: Map<Int, Pair<Long, Int>>): ByteArray = Writer().apply {
        int(index.size)
        for ((i, at) in index) { int(i); long(at.first); int(at.second) }
    }.bytes()

    fun decode(bytes: ByteArray): HashMap<Int, Pair<Long, Int>> {
        val r = Reader(bytes)
        val n = r.int()
        require(n >= 0) { "index count" }
        val out = HashMap<Int, Pair<Long, Int>>(n)
        repeat(n) { out[r.int()] = r.long() to r.int() }
        require(r.done) { "index trailing bytes" }
        return out
    }
}

internal class Writer {
    private var buf = ByteArray(256)
    private var n = 0

    private fun room(k: Int) {
        if (n + k <= buf.size) return
        var size = buf.size * 2
        while (size < n + k) size *= 2
        buf = buf.copyOf(size)
    }

    fun byte(v: Int) { room(1); buf[n++] = v.toByte() }

    fun int(v: Int) {
        room(4)
        buf[n++] = (v ushr 24).toByte(); buf[n++] = (v ushr 16).toByte(); buf[n++] = (v ushr 8).toByte(); buf[n++] = v.toByte()
    }

    fun long(v: Long) { int((v ushr 32).toInt()); int(v.toInt()) }

    fun raw(b: ByteArray) { room(b.size); b.copyInto(buf, n); n += b.size }

    fun text(s: String) { val b = s.encodeToByteArray(); int(b.size); raw(b) }

    fun bytes(): ByteArray = buf.copyOf(n)
}

internal class Reader(private val b: ByteArray) {
    private var at = 0
    val done: Boolean get() = at == b.size

    private fun need(k: Int) = require(k >= 0 && at + k <= b.size) { "truncated" }

    fun byte(): Int { need(1); return b[at++].toInt() and 0xff }

    fun int(): Int {
        need(4)
        val v = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
        at += 4
        return v
    }

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffffffffL)

    fun raw(k: Int): ByteArray {
        need(k)
        return b.copyOfRange(at, at + k).also { at += k }
    }

    fun text(): String {
        val k = int()
        need(k)
        return b.decodeToString(at, at + k).also { at += k }
    }
}
