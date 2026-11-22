package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.u32be

/**
 * One account's offline store on this device (ADR 0140 decision 2): ciphertext exactly as the server sent it — a
 * listing's answer, a blob's frames — each entry sealed again under this store's own key `K_off`, in one directory whose
 * file names are opaque. It never sees a decrypted byte: what it is handed is what the server served, and what it hands
 * back is that again, opened by the same code that opens a server's answer.
 *
 * - **An entry** is `nonce12 ‖ AES-256-GCM(K_off, nonce, content, aad)`, its aad `"veyq/offline/v1" ‖ lp(account) ‖
 *   tag ‖ key` ([EntryKey]): an entry moved under another name, into another account's directory or another store does
 *   not open, and is dropped as missing. **Its file's name** is `hex(HMAC(K_off, tag ‖ key))`, so no blob id or node id
 *   can be matched to a file.
 * - **A blob** is one file of its frames appended in the order fetched, each sealed as an entry of its own (its frame
 *   index in the aad), and a sealed frame index (index → offset) rewritten beside it.
 * - **The manifest** — each entry's key, size, last view, pins and a listing's revision and `asOf` — is 256 shards by the
 *   first byte of the entry's name, each sealed and rewritten whole beside its file, then renamed over it, so a write a
 *   crash tore leaves the previous shard. The whole manifest is read into memory when the store opens ([OfflineStores.open]).
 *   A change writes its shard at once; a last view ([touch]) marks it, written at most once per shard per 30 s and on
 *   [flush] (the app going to the background).
 *
 * Every call takes the store's lock: one writer at a time. Once [close]d, the store answers nothing and writes nothing —
 * so a read still running when its account signs out cannot put a file back into a directory a wipe removed.
 */
class OfflineStore internal constructor(
    private val files: DeviceFiles,
    private val primitives: Primitives,
    /** The store's directory: `<root>/<random id>`. */
    val directory: String,
    private val account: String,
    private val key: ByteArray,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private val shards = Array(SHARDS) { HashMap<EntryKey, Kept>() }
    private val dirty = BooleanArray(SHARDS)
    private val written = LongArray(SHARDS) { Long.MIN_VALUE }
    private val indexes = HashMap<EntryKey, HashMap<Int, Pair<Long, Int>>>()
    private var closed = false

    // ---- what the store holds

    /** Every entry the manifest names, a copy. */
    suspend fun entries(): List<KeptEntry> = lock.withLock { if (closed) emptyList() else shards.flatMap { s -> s.values.map { it.shown() } } }

    /** The entry [key] names, null when none is kept. */
    suspend fun entry(key: EntryKey): KeptEntry? = lock.withLock { if (closed) null else find(key)?.shown() }

    /** The bytes the store's files take on disk, the manifest's own shards left out. */
    suspend fun used(): Long = lock.withLock { shards.sumOf { s -> s.values.sumOf { it.size } } }

    // ---- a listing's answer

    /**
     * Keeps [content] (a listing's answer as the server sent it) as [key], replacing what was kept — never merged
     * (ADR 0140 decision 5) — with the server's [revision] and [asOf], the time the server last confirmed it.
     */
    suspend fun put(key: EntryKey, content: ByteArray, revision: String? = null, asOf: Long? = null, pins: Set<String>? = null) = lock.withLock {
        if (closed) return@withLock
        val name = name(TAG_ENTRY, key)
        val sealed = seal(content, aad(TAG_ENTRY, key))
        replace(path(name), sealed)
        val now = clock()
        val old = find(key)
        dropBlobState(key, old)
        shards[shard(name)][key] = Kept(key, name, blob = false, sealed.size.toLong(), now, pins ?: old?.pins.orEmpty(), revision, asOf)
        writeShard(shard(name))
    }

    /** The server confirmed [key]'s kept answer unchanged (a `304`) at [asOf]: its line moves, its file stays. */
    suspend fun confirm(key: EntryKey, asOf: Long) = lock.withLock {
        if (closed) return@withLock
        val kept = find(key)?.takeIf { !it.blob } ?: return@withLock
        kept.asOf = asOf
        writeShard(kept.shard)
    }

    /** The answer kept as [key], opened; null when none is, or when it does not open (then it is dropped as missing). */
    suspend fun get(key: EntryKey): ByteArray? = lock.withLock {
        if (closed) return@withLock null
        val kept = find(key)?.takeIf { !it.blob } ?: return@withLock null
        val bytes = files.read(path(kept.name))
        val opened = bytes?.let { open(it, aad(TAG_ENTRY, key)) }
        if (opened == null) drop(kept)
        opened
    }

    // ---- a blob's frames

    /**
     * Appends each of [frames] (a frame index to the frame as the store served it) to [key]'s blob, the ones it holds left
     * as they are; [pins] the pins that reach it now (null: those it had).
     */
    suspend fun putFrames(key: EntryKey, frames: Map<Int, ByteArray>, pins: Set<String>? = null) = lock.withLock {
        if (closed) return@withLock
        val name = name(TAG_ENTRY, key)
        val old = find(key)
        if (old != null && !old.blob) drop(old)
        val index = indexOf(key) ?: HashMap()
        var changed = false
        for ((i, frame) in frames) {
            if (i in index) continue
            val sealed = seal(frame, frameAad(key, i))
            index[i] = files.append(path(name), sealed) to sealed.size
            changed = true
        }
        if (!changed && old != null) return@withLock
        replace(path(name(TAG_INDEX, key)), seal(FrameIndexCodec.encode(index), aad(TAG_INDEX, key)))
        indexes[key] = index
        val size = (files.size(path(name)) ?: 0L)
        shards[shard(name)][key] = Kept(key, name, blob = true, size, clock(), pins ?: old?.pins.orEmpty(), null, null)
        writeShard(shard(name))
    }

    /** Frame [index] of [key]'s blob as the store served it; null when it is not kept or does not open (then the blob is dropped). */
    suspend fun frame(key: EntryKey, index: Int): ByteArray? = lock.withLock {
        if (closed) return@withLock null
        val kept = find(key)?.takeIf { it.blob } ?: return@withLock null
        val at = indexOf(key)?.get(index) ?: return@withLock null
        val opened = files.read(path(kept.name), at.first, at.second)?.let { open(it, frameAad(key, index)) }
        if (opened == null) drop(kept)
        opened
    }

    /** The frame indexes kept of [key]'s blob. */
    suspend fun frames(key: EntryKey): Set<Int> = lock.withLock {
        if (closed || find(key)?.blob != true) emptySet() else indexOf(key)?.keys?.toSet().orEmpty()
    }

    // ---- views, removal, durability

    /** [key] was viewed now: its last view moves, written with its shard within 30 s or at [flush]. */
    suspend fun touch(key: EntryKey) = lock.withLock {
        if (closed) return@withLock
        val kept = find(key) ?: return@withLock
        kept.lastView = clock()
        dirty[kept.shard] = true
        val now = clock()
        if (now - written[kept.shard] >= BATCH_MS) writeShard(kept.shard)
    }

    /** Writes every shard a last view changed (the app going to the background). */
    suspend fun flush() = lock.withLock { if (!closed) for (s in 0 until SHARDS) if (dirty[s]) writeShard(s) }

    /** Drops [key]: its files and its line in the manifest. */
    suspend fun remove(key: EntryKey) = lock.withLock { if (!closed) find(key)?.let { drop(it) }; Unit }

    /** Drops every one of [keys], each shard they touch written once (a sweep's, an eviction's). */
    suspend fun removeAll(keys: Collection<EntryKey>) = lock.withLock {
        if (closed) return@withLock
        val touched = HashSet<Int>()
        for (k in keys) {
            val kept = find(k) ?: continue
            files.remove(path(kept.name))
            dropBlobState(kept.key, kept)
            shards[kept.shard].remove(kept.key)
            touched += kept.shard
        }
        for (s in touched) writeShard(s)
    }

    /** [key]'s pins set to [pins] (a test's): an entry with a pin is never evicted. */
    @PlatformApi
    @HiddenFromObjC
    suspend fun setPins(key: EntryKey, pins: Set<String>) = lock.withLock {
        if (closed) return@withLock
        val kept = find(key) ?: return@withLock
        kept.pins = pins
        writeShard(kept.shard)
    }

    /** Each entry of [pins] given those pins (the pins that reach it now, [AccountOffline.applyPins]), each shard written once. */
    internal suspend fun setPinsAll(pins: Map<EntryKey, Set<String>>) = lock.withLock {
        if (closed) return@withLock
        val touched = HashSet<Int>()
        for ((k, p) in pins) {
            val kept = find(k) ?: continue
            if (kept.pins == p) continue
            kept.pins = p
            touched += kept.shard
        }
        for (s in touched) writeShard(s)
    }

    /**
     * Removes every file of the directory the manifest does not name — a write beside that a crash left, an entry of a
     * shard that no longer opened. Not part of [OfflineStores.open] (it lists the whole directory): the caller runs it
     * once the account is open.
     */
    suspend fun sweep() = lock.withLock {
        if (closed) return@withLock
        val named = HashSet<String>()
        named += HEADER
        for (s in 0 until SHARDS) named += shardName(s)
        for (s in shards) for (k in s.values) { named += k.name; if (k.blob) named += name(TAG_INDEX, k.key) }
        for (n in files.list(directory).orEmpty()) if (n !in named) files.remove(path(n))
    }

    /**
     * Forgets the store's key; the store answers nothing and writes nothing after. Waits for a call under way, so nothing
     * it writes lands after the close (ADR 0140 decision 11: a store a session holds is closed before its wipe).
     */
    suspend fun close() = lock.withLock {
        closed = true
        key.fill(0)
    }

    /**
     * The entries [carry] picks, each opened under this store's key and sealed again under [target]'s — a store under a
     * fresh `K_off` in the same directory, whose file names (HMACs under its own key) are not this one's —, a blob frame by
     * frame in batches; then this store closes. The rest stays under the old key until the target's [sweep] removes every
     * file its manifest does not name: a Master Key rotation's carry of the outbox ([OfflineStores.rekey]).
     */
    internal suspend fun carryInto(target: OfflineStore, carry: (EntryKey) -> Boolean) = lock.withLock {
        check(!closed) { "the store is closed" }
        for (s in shards) for (k in s.values.toList()) {
            if (!carry(k.key)) continue
            if (k.blob) {
                val index = indexOf(k.key) ?: continue
                for (batch in index.keys.sorted().chunked(CARRY_BATCH)) {
                    val frames = HashMap<Int, ByteArray>()
                    for (i in batch) {
                        val (offset, length) = index.getValue(i)
                        frames[i] = files.read(path(k.name), offset, length)?.let { open(it, frameAad(k.key, i)) }
                            ?: throw IllegalStateException("a carried frame does not open")
                    }
                    target.putFrames(k.key, frames, k.pins)
                }
            } else {
                val opened = files.read(path(k.name))?.let { open(it, aad(TAG_ENTRY, k.key)) }
                    ?: throw IllegalStateException("a carried entry does not open")
                target.put(k.key, opened, k.revision, k.asOf, k.pins)
            }
        }
        closed = true
        key.fill(0)
    }

    /**
     * Lines in the manifest for [entries] without their files, every shard written once: how the manifest's load is
     * measured at 100,000 entries (`OfflineManifestLoadTest`, D2's bound). What a later [sweep] finds no file for stays
     * a line until it is read.
     */
    internal suspend fun putManifest(entries: Collection<KeptEntry>) = lock.withLock {
        for (e in entries) {
            val name = name(TAG_ENTRY, e.key)
            shards[shard(name)][e.key] = Kept(e.key, name, e.blob, e.size, e.lastView, e.pins, e.revision, e.asOf)
        }
        for (s in 0 until SHARDS) writeShard(s)
    }

    // ---- inside

    internal fun load() {
        for (s in 0 until SHARDS) {
            val bytes = files.read(path(shardName(s))) ?: continue
            val plain = open(bytes, aad(TAG_SHARD, shardKey(s))) ?: continue
            val entries = runCatching { ShardCodec.decode(plain) }.getOrNull() ?: continue
            val map = shards[s]
            for (e in entries) if (e.shard == s) map[e.key] = e
        }
    }

    private fun find(key: EntryKey): Kept? = shards[shard(name(TAG_ENTRY, key))][key]

    private fun indexOf(key: EntryKey): HashMap<Int, Pair<Long, Int>>? = indexes[key] ?: run {
        val bytes = files.read(path(name(TAG_INDEX, key))) ?: return null
        val plain = open(bytes, aad(TAG_INDEX, key)) ?: return null
        runCatching { FrameIndexCodec.decode(plain) }.getOrNull()?.also { indexes[key] = it }
    }

    private fun drop(kept: Kept) {
        files.remove(path(kept.name))
        dropBlobState(kept.key, kept)
        shards[kept.shard].remove(kept.key)
        writeShard(kept.shard)
    }

    private fun dropBlobState(key: EntryKey, kept: Kept?) {
        if (kept?.blob != true) return
        files.remove(path(name(TAG_INDEX, key)))
        indexes.remove(key)
    }

    private fun writeShard(s: Int) {
        replace(path(shardName(s)), seal(ShardCodec.encode(shards[s].values), aad(TAG_SHARD, shardKey(s))))
        dirty[s] = false
        written[s] = clock()
    }

    /** [bytes] as the whole file at [path], written beside it and renamed over it: a torn write leaves the previous one. */
    private fun replace(path: String, bytes: ByteArray) {
        val beside = "$path$BESIDE"
        files.write(beside, bytes)
        files.rename(beside, path)
    }

    private fun seal(content: ByteArray, aad: ByteArray): ByteArray {
        val nonce = primitives.randomBytes(NONCE)
        return concat(nonce, primitives.aesGcmSeal(key, nonce, content, aad))
    }

    private fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < NONCE + TAG) return null
        return runCatching { primitives.aesGcmOpen(key, sealed.copyOfRange(0, NONCE), sealed.copyOfRange(NONCE, sealed.size), aad) }.getOrNull()
    }

    private fun aad(tag: Int, key: EntryKey): ByteArray = aad(tag, key.bytes())

    private fun aad(tag: Int, key: ByteArray): ByteArray = concat(AAD, lp(account.encodeToByteArray()), byteArrayOf(tag.toByte()), key)

    private fun frameAad(key: EntryKey, index: Int): ByteArray = aad(TAG_FRAME, concat(key.bytes(), u32be(index)))

    private fun name(tag: Int, key: EntryKey): String = name(tag, key.bytes())

    private fun name(tag: Int, key: ByteArray): String =
        hex(primitives.hmacSha256(this.key, concat(byteArrayOf(tag.toByte()), key)).copyOf(NAME_BYTES))

    private fun shardKey(s: Int) = byteArrayOf(s.toByte())

    private val shardNames = arrayOfNulls<String>(SHARDS)

    private fun shardName(s: Int): String = shardNames[s] ?: name(TAG_SHARD, shardKey(s)).also { shardNames[s] = it }

    private fun shard(name: String): Int = name.substring(0, 2).toInt(16)

    private fun path(name: String) = "$directory${files.separator}$name"

    @PlatformApi
    @HiddenFromObjC
    companion object {
        const val SHARDS = 256
        const val CARRY_BATCH = 16
        const val BATCH_MS = 30_000L
        const val NONCE = 12
        const val TAG = 16
        const val NAME_BYTES = 16
        const val HEADER = "header"
        const val BESIDE = ".new"
        val AAD = "veyq/offline/v1".encodeToByteArray()
        const val TAG_ENTRY = 1
        const val TAG_FRAME = 2
        const val TAG_INDEX = 3
        const val TAG_SHARD = 4
    }
}
