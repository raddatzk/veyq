package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.media.ByteSource

/**
 * The account's writes made offline, waiting to be sent (ADR 0153 decisions 1 and 2): entries of the account's offline
 * store, so sealed under `K_off`, named opaquely and wiped with it — never evicted, never cleared, never swept
 * ([KeptRead.QUEUED]).
 *
 * - **A write is its intent** ([QueuedWrite]): the fields the person set, the ids the client minted, the revision or
 *   placement they saw — never a request sealed for the server. Its logical key is its own id, so queuing it again
 *   replaces it in place: a re-queue is idempotent and keeps its turn.
 * - **The order** is an entry of its own (id `""`): every write's id, oldest first, rewritten whole and atomically as a
 *   manifest shard is. A write the order does not name (a crash between the two writes) is put back at its time; an id
 *   whose write is gone is dropped.
 * - **A queued blob** ([sealBlob]) is the exception to "intent only": its plaintext is read once and sealed at once, frame
 *   by frame, under a fresh `K_blob`, exactly the frames the online upload would put; `K_blob` travels inside the sealed
 *   intent ([QueuedBlob.key]) and is wrapped under the node's key only when the write is sent. So nothing waits as
 *   plaintext, and a re-key while offline costs nothing (decision 1).
 * - **The free-space floor:** a new write and a blob's frames are refused below ADR 0140's 5 % ([AccountOffline.putQueued]);
 *   the order and a write's state are rewritten whatever is free, so a send or a discard always lands.
 */
class Outbox internal constructor(
    private val offline: AccountOffline,
    private val suite: V1,
    private val server: String,
) {
    private val lock = Mutex()
    private var tidied = false

    private val orderKey get() = EntryKey(server, "", "", KeptRead.OUTBOX, ORDER)

    private fun writeKey(id: String) = EntryKey(server, "", "", KeptRead.OUTBOX, id)

    private fun blobKey(id: String) = EntryKey(server, "", "", KeptRead.OUTBOX_FRAMES, id)

    /**
     * [write] queued — or, where a write of its id waits already, replaced in place, keeping its turn. False where the volume
     * is below the free-space floor: nothing is queued, and the caller says so.
     */
    suspend fun put(write: QueuedWrite): Boolean = lock.withLock {
        require(write.id != ORDER) { "a queued write has an id" }
        val order = order()
        if (!offline.putQueued(writeKey(write.id), encode(write), floor = write.id !in order)) return@withLock false
        if (write.id !in order) setOrder(order + write.id)
        true
    }

    /** [write] stored again as it now is (its state after a send's outcome): never refused by the floor; nothing if it is gone. */
    internal suspend fun update(write: QueuedWrite) = lock.withLock {
        if (write.id in order()) offline.putQueued(writeKey(write.id), encode(write), floor = false)
    }

    /** Every waiting write, oldest first. */
    suspend fun writes(): List<QueuedWrite> = lock.withLock {
        order().mapNotNull { read(it) }
    }

    /** The write of id [id]; null where none waits. */
    suspend fun write(id: String): QueuedWrite? = lock.withLock { if (id in order()) read(id) else null }

    /** How many writes wait. */
    suspend fun count(): Int = lock.withLock { order().size }

    /** Drops write [id] with the blobs it carries — sent, or discarded by the person. */
    suspend fun remove(id: String) = lock.withLock {
        val write = read(id)
        offline.store.removeAll(listOf(writeKey(id)) + write?.blobs.orEmpty().map { blobKey(it.id) })
        setOrder(order() - id)
    }

    /**
     * [source] read once and sealed at once (ADR 0153 decision 1): a fresh `K_blob`, each frame of [V1.FRAME_PLAINTEXT]
     * bytes sealed under it exactly as an upload seals it ([V1.sealFrame]), kept as a queued blob in batches. Null where
     * the floor refuses a batch — what was written of it is gone again. The caller lets go of the picked file after.
     */
    suspend fun sealBlob(source: ByteSource): QueuedBlob? {
        // the order made whole first: its tidy drops a blob no write carries, which this one is until its write is queued
        lock.withLock { order() }
        val id = hex(suite.p.randomBytes(16))
        val key = suite.p.randomBytes(32)
        try {
            val total = V1.chunkCount(source.size)
            var i = 0
            while (i < total) {
                val batch = HashMap<Int, ByteArray>()
                while (i < total && batch.size < BATCH) {
                    val from = i.toLong() * V1.FRAME_PLAINTEXT
                    val length = minOf(V1.FRAME_PLAINTEXT.toLong(), source.size - from).toInt()
                    val plain = if (length > 0) source.read(from, length) else ByteArray(0)
                    batch[i] = suite.sealFrame(key, i, total, plain)
                    plain.fill(0)
                    i++
                }
                if (!offline.putQueuedFrames(blobKey(id), batch)) {
                    offline.store.remove(blobKey(id))
                    return null
                }
            }
            return QueuedBlob(id, b64(key), total, source.size)
        } finally {
            key.fill(0)
        }
    }

    /** Frame [index] of queued blob [blob] as it is sent: sealed under its `K_blob`, never plaintext. Null where it is not kept. */
    internal suspend fun frame(blob: QueuedBlob, index: Int): ByteArray? = offline.store.frame(blobKey(blob.id), index)

    /** A blob sealed for a write that was never queued (a crash between the two), or a discarded one's: dropped. */
    internal suspend fun dropBlob(blob: QueuedBlob) = offline.store.remove(blobKey(blob.id))

    // ---- inside

    private suspend fun read(id: String): QueuedWrite? =
        offline.store.get(writeKey(id))?.let { runCatching { JSON.decodeFromString(QueuedWrite.serializer(), it.decodeToString()) }.getOrNull() }

    /**
     * The order as kept, made whole once per store (the first read): a write the order does not name is put back by its
     * time, an id without its write dropped, and a blob no write carries removed.
     */
    private suspend fun order(): List<String> {
        val kept = offline.store.get(orderKey)?.let { runCatching { JSON.decodeFromString(IDS, it.decodeToString()) }.getOrNull() }.orEmpty()
        if (tidied) return kept
        tidied = true
        val entries = offline.store.entries()
        val ids = entries.filter { it.key.kind == KeptRead.OUTBOX && it.key.id != ORDER }.map { it.key.id }.toSet()
        val writes = ids.mapNotNull { read(it) }
        val stray = writes.filter { it.id !in kept }.sortedBy { it.at }.map { it.id }
        val whole = kept.filter { it in ids && writes.any { w -> w.id == it } } + stray
        val carried = writes.flatMap { w -> w.blobs.map { it.id } }.toSet()
        val loose = entries.filter { it.key.kind == KeptRead.OUTBOX_FRAMES && it.key.id !in carried }.map { it.key }
        if (loose.isNotEmpty()) offline.store.removeAll(loose)
        if (whole != kept) setOrder(whole)
        return whole
    }

    private suspend fun setOrder(ids: List<String>) {
        offline.putQueued(orderKey, JSON.encodeToString(IDS, ids).encodeToByteArray(), floor = false)
    }

    private fun encode(write: QueuedWrite) = JSON.encodeToString(QueuedWrite.serializer(), write).encodeToByteArray()

    internal companion object {
        /** The order's id: no write has it. */
        const val ORDER = ""

        /** Frames sealed and kept per write: one frame index and one manifest shard per batch, never per frame. */
        const val BATCH = 16

        private val IDS = ListSerializer(kotlinx.serialization.serializer<String>())
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * One write made offline, as the person made it (ADR 0153 decision 1): [op] the [Queued] it is (its name, so a build that
 * no longer knows it holds it as refused rather than losing it), the [tenant] and [scope] it writes in, the [node] it writes
 * (the one it creates, renames, trashes or adds into), the write it waits for ([after]: a Chapter made offline before its
 * media), and its [intent] — the fields the person set and what they saw, each [Queued]'s own shape. Never a key but a
 * queued blob's `K_blob`, which the store's seal covers; never a token, never a request sealed for the server.
 */
@Serializable
data class QueuedWrite(
    /** The write's own id, minted by the client when it was made: a create's node id, else a fresh one. */
    val id: String,
    val op: String,
    val tenant: String,
    val scope: String,
    val node: String? = null,
    val after: String? = null,
    val intent: JsonObject = JsonObject(emptyMap()),
    val blobs: List<QueuedBlob> = emptyList(),
    /** When it was made (epoch ms): what the order falls back to and what the tray says. */
    val at: Long,
    val state: State = State.WAITING,
    /** Why it is [State.HELD] or [State.REFUSED]: the server's code or the core's reason, never content. */
    val reason: String? = null,
) {
    /**
     * Where it is (ADR 0153 decision 4): [WAITING] to be sent at the next reconnect; [HELD] for the person, the state it
     * would have overwritten having changed meanwhile (a conflict, opened with the online conflict's own screen);
     * [REFUSED] where the target is gone or no longer the person's to write — offered *Discard* and, for a blob, *Save a
     * copy*. A sent write is no longer in the outbox.
     */
    enum class State { WAITING, HELD, REFUSED }
}

/**
 * A blob queued with a write (ADR 0153 decision 1): its frames kept under [id], sealed under [key] (`K_blob`, base64),
 * [chunkCount] frames of [size] plaintext bytes. The wrap of [key] under the node's key is made only when it is sent.
 */
@Serializable
data class QueuedBlob(val id: String, val key: String, val chunkCount: Int, val size: Long)
