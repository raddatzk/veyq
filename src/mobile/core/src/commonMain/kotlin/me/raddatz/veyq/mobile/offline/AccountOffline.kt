package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * One account's offline store as its reads use it (ADR 0140 decisions 5, 6 and 8): the answers of [KeptRead]'s reads
 * kept, replaced — never merged — by each newer one, a blob's frames kept as they are read, the store swept after each
 * replace of what no current answer reaches, and the automatic tier held under the person's limit.
 *
 * - **The limit** is per account on this device ([limit], kept in the device store; the host's default where none was
 *   set: 2 GB on a phone, 10 GB on a desktop). It bounds what both tiers use; pins may exceed it, and then the automatic
 *   tier keeps nothing. `0` turns the automatic tier off. An eviction runs after each write that takes the store over it
 *   and at once when it is lowered ([setLimit]): the least recently viewed entry first, a pinned one never, down to 90 %
 *   of it. A blob larger than a quarter of the limit is not kept by the automatic tier, and neither tier writes while the
 *   volume has less than 5 % free. The answers an account opens with ([KeptRead.OPENING]) are never evicted: an offline
 *   unlock needs them.
 * - **A write the store does not take** (the tier off, the floor) drops what it kept under that key: an older answer is
 *   never left to stand for a newer one.
 * - **The sweep** ([sweep]) marks from the kept `listMyTenants` and each tenant's `listMyScopes` down through each
 *   scope's tree to the media, files and application blobs of every node it opens, and drops every entry it does not
 *   reach: a trashed medium, a removed membership, a tenant left, a replaced file — and a node or medium the operator
 *   blocked with all below it, and a tenant answered as suspended ([suspend]): kept ciphertext is never a way around a
 *   hold the server states (decision 6). A pin of such a node keeps its place, not its content; pins are phase 4's.
 */
class AccountOffline internal constructor(
    internal val store: OfflineStore,
    private val files: DeviceFiles,
    private val settings: DeviceStore,
    /** The account's key on this device ([me.raddatz.veyq.mobile.session.VeyqAccount.key]). */
    private val account: String,
    private val server: String,
    private val defaultLimit: Long,
    private val clock: () -> Long,
) {
    /** A kept answer: its pages as the server sent them, the server's revision and when the server last confirmed it. */
    class Answer(val pages: List<ByteArray>, val revision: String?, val asOf: Long?)

    /** What the store holds against the limit (the Settings section's sizes, ADR 0140 decision 10). */
    data class Usage(val limit: Long, val automatic: Long, val pinned: Long, val free: Long?)

    internal fun key(read: KeptRead, tenant: String = "", scope: String = "", id: String = "") = EntryKey(server, tenant, scope, read.kind, id)

    internal fun blobKey(tenant: String, scope: String, blobId: String) = EntryKey(server, tenant, scope, KeptRead.FRAMES, blobId)

    /** The limit in bytes for this account on this device. */
    val limit: Long get() = settings.read(LIMIT + account)?.toLongOrNull()?.takeIf { it >= 0 } ?: defaultLimit

    /** Sets the limit; lowering it below what is kept evicts at once, down to the new limit (ADR 0140 decision 8). */
    suspend fun setLimit(bytes: Long) {
        require(bytes >= 0) { "a limit is never negative" }
        settings.write(LIMIT + account, bytes.toString())
        evict()
    }

    suspend fun usage(): Usage {
        val entries = store.entries()
        return Usage(
            limit,
            entries.filter { it.pins.isEmpty() }.sumOf { it.size },
            entries.filter { it.pins.isNotEmpty() }.sumOf { it.size },
            files.freeBytes(store.directory),
        )
    }

    // ---- a listing's answer

    /** The answer kept under [key], its last view moved; null where none is (or it no longer opens). */
    internal suspend fun answer(key: EntryKey): Answer? {
        val entry = store.entry(key) ?: return null
        val bytes = store.get(key) ?: return null
        val pages = runCatching { Pages.decode(bytes) }.getOrNull() ?: return null.also { store.remove(key) }
        store.touch(key)
        return Answer(pages, entry.revision, entry.asOf)
    }

    /** [pages] (an answer as the server sent it) kept under [key] with the server's [revision], replacing what was kept. */
    internal suspend fun keep(key: EntryKey, pages: List<ByteArray>, revision: String?) {
        val content = Pages.encode(pages)
        if (key.kind !in KeptRead.OPENING && !mayWrite(content.size.toLong())) {
            store.remove(key)
            return
        }
        store.put(key, content, revision, clock())
        evict()
    }

    /** The server answered [key]'s kept revision unchanged: it is current as of now. */
    internal suspend fun confirm(key: EntryKey) {
        store.confirm(key, clock())
        store.touch(key)
    }

    internal suspend fun drop(key: EntryKey) = store.remove(key)

    // ---- a blob's frames

    /** The frame indexes kept of [key]'s blob. */
    internal suspend fun frames(key: EntryKey): Set<Int> = store.frames(key)

    /** Frame [index] of [key]'s blob as the store served it; null where it is not kept. */
    internal suspend fun frame(key: EntryKey, index: Int): ByteArray? = store.frame(key, index)

    internal suspend fun touch(key: EntryKey) = store.touch(key)

    /**
     * [frames] (as the object store served them) appended to [key]'s blob in one write — one frame index and one shard
     * written per batch, never per frame — where the automatic tier takes a blob of [size] bytes; then the eviction.
     */
    internal suspend fun keepFrames(key: EntryKey, frames: Map<Int, ByteArray>, size: Long) {
        if (frames.isEmpty()) return
        if (store.entry(key)?.pins?.isNotEmpty() != true) {
            val l = limit
            if (l == 0L || size > l / 4) return
        }
        if (!mayWrite(frames.values.sumOf { it.size.toLong() })) return
        store.putFrames(key, frames)
        evict()
    }

    // ---- the limit

    /** Whether the volume keeps 5 % free after [bytes] more, and the automatic tier is on. */
    private fun mayWrite(bytes: Long): Boolean {
        if (limit == 0L) return false
        val free = files.freeBytes(store.directory) ?: return true
        val total = files.totalBytes(store.directory) ?: return true
        return (free - bytes) * 100 >= total * FLOOR_PERCENT
    }

    /**
     * Down to 90 % of the limit when the store holds more than it: the automatic tier's least recently viewed entries
     * first; a pinned one and the answers an account opens with never.
     */
    internal suspend fun evict() {
        val entries = store.entries()
        val used = entries.sumOf { it.size }
        val l = limit
        if (used <= l) return
        val target = l / 10 * 9
        var left = used
        val drop = ArrayList<EntryKey>()
        for (e in entries.filter { it.pins.isEmpty() && it.key.kind !in KeptRead.OPENING }.sortedBy { it.lastView }) {
            if (left <= target) break
            drop += e.key
            left -= e.size
        }
        store.removeAll(drop)
    }

    /** "Clear cache": the automatic tier dropped, the pins and the answers an account opens with kept (ADR 0140 decision 8). */
    suspend fun clearCache() {
        store.removeAll(store.entries().filter { it.pins.isEmpty() && it.key.kind !in KeptRead.OPENING }.map { it.key })
    }

    // ---- the sweep

    /** Drops every entry no current kept answer reaches (ADR 0140 decision 6). */
    internal suspend fun sweep() {
        val reached = mark()
        store.removeAll(store.entries().filter { it.key !in reached }.map { it.key })
    }

    /** Tenant [tenant] answered as suspended (the scope-access gate's `403 tenant_suspended`): what is kept of it goes. */
    internal suspend fun suspend(tenant: String) {
        store.removeAll(store.entries().filter { it.key.tenant == tenant }.map { it.key })
    }

    /** What the kept answers reach now. */
    internal suspend fun mark(): Set<EntryKey> {
        val reached = HashSet<EntryKey>()
        reached += key(KeptRead.KEY_MATERIAL)
        val tenantsKey = key(KeptRead.MY_TENANTS)
        reached += tenantsKey
        for (tenant in json(tenantsKey).flatMap { it.array() }.mapNotNull { it.text("tenantId") }) {
            val scopesKey = key(KeptRead.MY_SCOPES, tenant)
            reached += scopesKey
            val scopes = json(scopesKey).flatMap { it.array() }.filter { it.text("deletedAt") == null }.mapNotNull { it.text("id") }
            for (scope in scopes) {
                val nodesKey = key(KeptRead.NODES, tenant, scope, scope)
                reached += nodesKey
                val rows = json(nodesKey).flatMap { it.array() }
                for (node in walk(rows) + "") belowNode(tenant, scope, node, reached)
            }
        }
        return reached
    }

    /** A tree's nodes from its roots down, none at or below a node the operator blocked ([me.raddatz.veyq.mobile.session.Tree.open]'s rule). */
    private fun walk(rows: List<JsonElement>): Set<String> {
        val children = rows.groupBy { it.text("parentId") }
        val open = HashSet<String>()
        fun down(parent: String?) {
            for (row in children[parent].orEmpty()) {
                val id = row.text("id") ?: continue
                if (id in open || row.flag("blockedByOperator")) continue
                open += id
                down(id)
            }
        }
        down(null)
        return open
    }

    /** Node [node]'s listings and the blobs they name, into [into] (a medium or a blob the operator blocked: none). */
    private suspend fun belowNode(tenant: String, scope: String, node: String, into: MutableSet<EntryKey>) {
        val media = key(KeptRead.NODE_MEDIA, tenant, scope, node)
        into += media
        for (row in json(media).flatMap { it.field("media").array() }) {
            if (row.flag("blockedByOperator")) continue
            row.text("blobId")?.let { into += blobKey(tenant, scope, it) }
            for (d in row.field("derivatives").array()) d.text("blobId")?.let { into += blobKey(tenant, scope, it) }
        }
        val files = key(KeptRead.FILES, tenant, scope, node)
        into += files
        for (item in json(files).flatMap { it.field("items").array() }) item.field("fileBlob").text("blobId")?.let { into += blobKey(tenant, scope, it) }
        val blobs = key(KeptRead.APPLICATION_BLOBS, tenant, scope, node)
        into += blobs
        for (b in json(blobs).flatMap { it.field("blobs").array() }) {
            if (b.flag("blockedByOperator")) continue
            b.text("blobId")?.let { into += blobKey(tenant, scope, it) }
        }
    }

    /** The pages kept under [key], parsed; none where nothing is kept. */
    private suspend fun json(key: EntryKey): List<JsonElement> {
        val bytes = store.get(key) ?: return emptyList()
        return runCatching { Pages.decode(bytes).map { JSON.parseToJsonElement(it.decodeToString()) } }.getOrDefault(emptyList())
    }

    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)
    private fun JsonElement?.text(name: String): String? = (field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.flag(name: String): Boolean = (field(name) as? JsonPrimitive)?.booleanOrNull == true

    /** Writes the last views a session moved (the app going to the background), then forgets the store's key. */
    suspend fun close() {
        runCatching { store.flush() }
        store.close()
    }

    companion object {
        /** The device store's key of an account's limit, its account key after it. */
        const val LIMIT = "veyq.offline-limit."

        /** The defaults the hosts pass (owner 2026-11-15): 2 GB on a phone, 10 GB on a desktop. */
        const val PHONE_LIMIT = 2_000_000_000L
        const val DESKTOP_LIMIT = 10_000_000_000L

        /** Neither tier writes while the volume has less than this share free. */
        private const val FLOOR_PERCENT = 5

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
