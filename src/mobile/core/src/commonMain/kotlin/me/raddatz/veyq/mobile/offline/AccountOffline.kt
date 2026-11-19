package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
 *   scope's tree to the media, files, application blobs and a Pot's ledger of every node it opens, and drops every entry it does not
 *   reach: a trashed medium, a removed membership, a tenant left, a replaced file — and a node or medium the operator
 *   blocked with all below it, and a tenant answered as suspended ([suspend]): kept ciphertext is never a way around a
 *   hold the server states (decision 6). A pin of such a node keeps its place, not its content ([Pin.State.UNAVAILABLE]).
 * - **The pins** (decision 9, [pins]): each a node this account keeps available offline, kept in the store's own sealed
 *   record ([KeptRead.PINS]). What a pin reaches — its scope's tree, the listings of its node and of every node below it
 *   the operator did not block, the blobs they name ([reach]) — carries the pin in the manifest ([applyPins]), so neither
 *   the limit nor "Clear cache" drops it, and it is written whatever the limit is (the free-space floor alone stops it).
 *   An entry two pins reach keeps the one left when the other goes. A pin goes with its node: at the sweep, a node no
 *   current answer lists any more (trashed, a scope left, a tenant left) drops its pin with a notice ([gone]); a node the
 *   operator blocked and a tenant answered as suspended keep it, as "not available right now".
 */
class AccountOffline internal constructor(
    internal val store: OfflineStore,
    private val files: DeviceFiles,
    /** Ends a holder's use of the store, which [OfflineStores.openShared] shares; null: this holder owns it alone. */
    private val release: (suspend (holder: AccountOffline, all: Boolean) -> Boolean)?,
    private val settings: DeviceStore,
    /** The account's key on this device ([me.raddatz.veyq.mobile.session.VeyqAccount.key]). */
    private val account: String,
    private val server: String,
    private val defaultLimit: Long,
    private val clock: () -> Long,
) {
    /** A kept answer: its pages as the server sent them, the server's revision and when the server last confirmed it. */
    class Answer(val pages: List<ByteArray>, val revision: String?, val asOf: Long?)

    /**
     * What the store holds against the limit (the Settings section's sizes, ADR 0140 decision 10): [automatic] is content
     * viewed ("Recently viewed", what "Clear cache" drops), [pinned] what the pins reach. The answers an account opens
     * with and the pins' record ([KeptRead.PROTECTED], a few kB) count against the limit but in neither: they are no
     * content, and no button but a wipe drops them — counted as "Recently viewed" they showed after "Clear cache".
     */
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
            entries.filter { it.pins.isEmpty() && it.key.kind !in KeptRead.PROTECTED }.sumOf { it.size },
            entries.filter { it.pins.isNotEmpty() }.sumOf { it.size },
            files.freeBytes(store.directory),
        )
    }

    /** The bytes the store keeps that each pin reaches, by its node (an entry two pins reach counts in each). */
    suspend fun pinnedBytes(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (e in store.entries()) for (p in e.pins) out[p] = (out[p] ?: 0L) + e.size
        return out
    }

    // ---- a listing's answer

    /** The pins that reach [key] now, null where the account pins nothing (an entry then keeps the pins it had). */
    private suspend fun pinsOf(key: EntryKey): Set<String>? {
        if (loaded().pins.isEmpty()) return null
        return reach()[key].orEmpty()
    }

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
        val pins = pinsOf(key)
        if (key.kind !in KeptRead.OPENING && !mayWrite(content.size.toLong(), pinned = !pins.isNullOrEmpty())) {
            store.remove(key)
            listingChanged(key)
            return
        }
        store.put(key, content, revision, clock(), pins)
        listingChanged(key)
        // the server answered the scopes of a tenant once answered as suspended: its pins are back
        if (key.kind == KeptRead.MY_SCOPES.kind) resumed(key.tenant)
        evict()
    }

    /** The server answered [key]'s kept revision unchanged: it is current as of now. */
    internal suspend fun confirm(key: EntryKey) {
        store.confirm(key, clock())
        store.touch(key)
    }

    /** The server refused [key]'s read (`403`/`404`): its answer goes — a scope's tree with the pins in it. */
    internal suspend fun drop(key: EntryKey) {
        store.remove(key)
        listingChanged(key)
        if (key.kind == KeptRead.NODES.kind) pinLock.withLock {
            val r = loaded()
            val (gone, kept) = r.pins.partition { it.tenant == key.tenant && it.scope == key.scope }
            if (gone.isNotEmpty()) save(r.copy(pins = kept, gone = r.gone + gone.map { PinGone(it.tenant, it.node, it.kind) }))
        }
    }

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
    internal suspend fun keepFrames(key: EntryKey, frames: Map<Int, ByteArray>, size: Long): Boolean {
        if (frames.isEmpty()) return true
        val pins = pinsOf(key)
        val pinned = pins?.isNotEmpty() ?: (store.entry(key)?.pins?.isNotEmpty() == true)
        if (!pinned) {
            val l = limit
            if (l == 0L || size > l / 4) return false
        }
        if (!mayWrite(frames.values.sumOf { it.size.toLong() }, pinned)) return false
        store.putFrames(key, frames, pins)
        evict()
        return true
    }

    /** What of a blob of [chunkCount] frames and [byteSize] bytes under [key] is not kept yet, in bytes (a pin's size, shown first). */
    internal suspend fun missing(key: EntryKey, chunkCount: Int, byteSize: Long): Long {
        if (chunkCount <= 0) return 0
        val kept = store.frames(key).count { it in 0 until chunkCount }
        return byteSize * (chunkCount - kept) / chunkCount
    }

    // ---- a map tile (ADR 0143 D4 as amended for inbox #939)

    private fun tileKey(tenant: String, id: String) = EntryKey(server, tenant, "", KeptRead.TILE, id)

    /** The tile kept as [id] for [tenant], its last view moved; null where none is (or it no longer opens). */
    internal suspend fun tile(tenant: String, id: String): ByteArray? {
        val key = tileKey(tenant, id)
        val bytes = store.get(key) ?: return null
        store.touch(key)
        return bytes
    }

    /**
     * [content] kept as [tenant]'s tile [id] in the automatic tier — never where the tier is off or the floor reached (then
     * what was kept under it goes: an older tile never stands for a newer answer) —, then the eviction.
     */
    internal suspend fun keepTile(tenant: String, id: String, content: ByteArray) {
        val key = tileKey(tenant, id)
        if (!mayWrite(content.size.toLong())) {
            store.remove(key)
            return
        }
        store.put(key, content, null, clock(), emptySet())
        evict()
    }

    /** Drops [tenant]'s kept tiles but those whose id starts with [keep] (null: all of them). */
    internal suspend fun dropTiles(tenant: String, keep: String?) {
        store.removeAll(
            store.entries().filter { it.key.kind == KeptRead.TILE && it.key.tenant == tenant && (keep == null || !it.key.id.startsWith(keep)) }.map { it.key },
        )
    }

    // ---- the limit

    /** Whether the volume keeps 5 % free after [bytes] more, and — for what no pin reaches — the automatic tier is on. */
    private fun mayWrite(bytes: Long, pinned: Boolean = false): Boolean {
        if (!pinned && limit == 0L) return false
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
        for (e in entries.filter { it.pins.isEmpty() && it.key.kind !in KeptRead.PROTECTED }.sortedBy { it.lastView }) {
            if (left <= target) break
            drop += e.key
            left -= e.size
        }
        store.removeAll(drop)
    }

    /** "Clear cache": the automatic tier dropped, the pins and the answers an account opens with kept (ADR 0140 decision 8). */
    suspend fun clearCache() {
        store.removeAll(store.entries().filter { it.pins.isEmpty() && it.key.kind !in KeptRead.PROTECTED }.map { it.key })
    }

    // ---- the sweep

    /**
     * Drops every entry no current kept answer reaches (ADR 0140 decision 6) — first the pins whose node no current answer
     * lists ([settlePins]), so their entries go with them —, then gives each entry left the pins that reach it.
     */
    internal suspend fun sweep() {
        settlePins()
        val reached = mark()
        store.removeAll(store.entries().filter { it.key !in reached }.map { it.key })
        applyPins()
    }

    /**
     * Tenant [tenant] answered as suspended (the scope-access gate's `403 tenant_suspended`): what is kept of it goes; its
     * pins stay, not available until the server answers the tenant's scopes again.
     */
    internal suspend fun suspend(tenant: String) {
        pinLock.withLock {
            val r = loaded()
            if (r.pins.any { it.tenant == tenant } || tenant in r.suspended) {
                save(r.copy(pins = r.pins.map { if (it.tenant == tenant) it.copy(state = Pin.State.UNAVAILABLE) else it }, suspended = r.suspended + tenant))
            }
        }
        store.removeAll(store.entries().filter { it.key.tenant == tenant }.map { it.key })
    }

    /** What the kept answers reach now. */
    internal suspend fun mark(): Set<EntryKey> {
        val reached = HashSet<EntryKey>()
        reached += key(KeptRead.KEY_MATERIAL)
        reached += pinsKey
        val tenantsKey = key(KeptRead.MY_TENANTS)
        reached += tenantsKey
        val tenants = json(tenantsKey).flatMap { it.array() }.mapNotNull { it.text("tenantId") }.toSet()
        // a map tile is reached by no listing: it stays while its tenant does (inbox #939)
        store.entries().filter { it.key.kind == KeptRead.TILE && it.key.tenant in tenants }.mapTo(reached) { it.key }
        for (tenant in tenants) {
            val scopesKey = key(KeptRead.MY_SCOPES, tenant)
            reached += scopesKey
            val scopes = json(scopesKey).flatMap { it.array() }.filter { it.text("deletedAt") == null }.mapNotNull { it.text("id") }
            for (scope in scopes) {
                // a scope's member list (ADR 0144 decision 7) is reached while the scope is the account's; a list refused drops itself
                reached += key(KeptRead.MEMBERS, tenant, scope, scope)
                // the space's media as the search reads them (ADR 0153 decision 7): kept while the scope is the account's
                reached += key(KeptRead.SCOPE_MEDIA, tenant, scope, scope)
                val nodesKey = key(KeptRead.NODES, tenant, scope, scope)
                reached += nodesKey
                val rows = json(nodesKey).flatMap { it.array() }
                for (node in walk(rows) + "") belowNode(tenant, scope, node, reached)
            }
        }
        // what the pins reach beside the listings: each pinned node's covers and meta-named blobs, which only the pin's record names
        reached += reach().keys
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
        // a Memory's marks and buckets (ADR 0149 decisions 4 and 6): its badges and its guests' names, kept as its listing is
        into += key(KeptRead.ITEM_FAVORITES, tenant, scope, node)
        into += key(KeptRead.NODE_BUCKETS, tenant, scope, node)
        // a Pot's ledger (ADR 0153 decision 6): its receipts are its application blobs, below
        into += key(KeptRead.POT_REVISIONS, tenant, scope, node)
        val files = key(KeptRead.FILES, tenant, scope, node)
        into += files
        filesOf(files, tenant, scope, into, null)
        val blobs = key(KeptRead.APPLICATION_BLOBS, tenant, scope, node)
        into += blobs
        for (b in json(blobs).flatMap { it.field("blobs").array() }) {
            if (b.flag("blockedByOperator")) continue
            b.text("blobId")?.let { into += blobKey(tenant, scope, it) }
        }
    }

    /** The blobs folder listing [files] names, into [into]; [only]: of these file nodes alone. */
    private suspend fun filesOf(files: EntryKey, tenant: String, scope: String, into: MutableSet<EntryKey>, only: Set<String>?) {
        for (item in json(files).flatMap { it.field("items").array() }) {
            if (only != null && item.text("nodeId") !in only) continue
            item.field("fileBlob").text("blobId")?.let { into += blobKey(tenant, scope, it) }
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

    // ---- the pins (ADR 0140 decision 9)

    private val pinsKey get() = EntryKey(server, "", "", KeptRead.PINS, "")
    private val pinLock = Mutex()
    private var record: PinRecord? = null
    private var reachCache: Map<EntryKey, Set<String>>? = null
    private val _pins = MutableStateFlow<List<Pin>>(emptyList())
    private val _gone = MutableStateFlow<List<PinGone>>(emptyList())

    /** The pins, as the store keeps them (read at the first ask). */
    val pins: StateFlow<List<Pin>> get() = _pins.asStateFlow()

    /** The pins dropped since their node is no longer available: each said once, until [seenGone]. */
    val gone: StateFlow<List<PinGone>> get() = _gone.asStateFlow()

    /** The pins' record, read from the store once. A record that does not open is none: the store is a copy. */
    internal suspend fun loaded(): PinRecord = record ?: run {
        val read = store.get(pinsKey)?.let { runCatching { JSON.decodeFromString(PinRecord.serializer(), it.decodeToString()) }.getOrNull() } ?: PinRecord()
        // a fetch a process end interrupted waits for the next sync
        read.copy(pins = read.pins.map { if (it.state == Pin.State.FETCHING) it.copy(state = Pin.State.WAITING) else it })
    }.also { if (record == null) { record = it; _pins.value = it.pins; _gone.value = it.gone } }

    private suspend fun save(r: PinRecord) {
        store.put(pinsKey, JSON.encodeToString(PinRecord.serializer(), r).encodeToByteArray())
        record = r
        reachCache = null
        _pins.value = r.pins
        _gone.value = r.gone
    }

    /** [pin] kept (its state reset where it was pinned before), then every entry it reaches given it. */
    internal suspend fun addPin(pin: Pin) {
        pinLock.withLock {
            val r = loaded()
            save(r.copy(pins = r.pins.filter { it.node != pin.node } + pin))
        }
        applyPins()
    }

    /**
     * The pin of [node] taken off: what it alone reached is the automatic tier's again (under the limit, evicted where the
     * limit says so); what another pin reaches keeps that pin.
     */
    internal suspend fun removePin(node: String) {
        pinLock.withLock {
            val r = loaded()
            if (r.pins.none { it.node == node }) return@withLock
            save(r.copy(pins = r.pins.filter { it.node != node }))
        }
        applyPins()
        evict()
    }

    /** The pin of [node] is now in [state] (the sync's). */
    internal suspend fun setPinState(node: String, state: Pin.State) = pinLock.withLock {
        val r = loaded()
        val pin = r.pins.firstOrNull { it.node == node } ?: return@withLock
        if (pin.state == state) return@withLock
        // a fetch's own state never overwrites what the sweep found (a node now blocked)
        if (pin.state == Pin.State.UNAVAILABLE && state != Pin.State.WAITING) return@withLock
        save(r.copy(pins = r.pins.map { if (it.node == node) it.copy(state = state) else it }))
    }

    /**
     * The covers the pin of [node] keeps (each node below it, its own included, to its cover's blob id) and the blobs its
     * kinds name by meta alone ([PinKinds.named]: a Memory's banner, ADR 0144 decision 7): named by the nodes' sealed
     * meta, which the store does not read, so the pin's fetch records them here ([reach] reads them).
     */
    internal suspend fun setPinCovers(node: String, covers: Map<String, String>, named: Map<String, List<String>> = emptyMap()) = pinLock.withLock {
        val r = loaded()
        val pin = r.pins.firstOrNull { it.node == node } ?: return@withLock
        if (pin.covers == covers && pin.named == named) return@withLock
        save(r.copy(pins = r.pins.map { if (it.node == node) it.copy(covers = covers, named = named) else it }))
    }

    /** The notices of [gone] said: they are not said again. */
    suspend fun seenGone() = pinLock.withLock {
        val r = loaded()
        if (r.gone.isNotEmpty()) save(r.copy(gone = emptyList()))
    }

    /** Tenant [tenant]'s scopes answered: a suspension the store held for it ended, its pins wait for the next sync. */
    private suspend fun resumed(tenant: String) = pinLock.withLock {
        val r = loaded()
        if (tenant !in r.suspended) return@withLock
        save(r.copy(
            pins = r.pins.map { if (it.tenant == tenant && it.state == Pin.State.UNAVAILABLE) it.copy(state = Pin.State.WAITING) else it },
            suspended = r.suspended - tenant,
        ))
    }

    /** A listing written or dropped: what the pins reach is read anew at the next ask. */
    private fun listingChanged(key: EntryKey) {
        if (key.kind in KeptRead.LISTINGS) reachCache = null
    }

    /**
     * What each pin reaches now, by the kept answers alone (no key: the store opens nothing): its scope's tree, the listings
     * of its node and of every node below it the operator did not block, and the blobs they name — a pinned file's own blob
     * in its folder's listing. An entry several pins reach carries each of them.
     */
    internal suspend fun reach(): Map<EntryKey, Set<String>> {
        reachCache?.let { return it }
        val out = HashMap<EntryKey, MutableSet<String>>()
        for (pin in loaded().pins) {
            val nodesKey = key(KeptRead.NODES, pin.tenant, pin.scope, pin.scope)
            out.getOrPut(nodesKey) { HashSet() } += pin.node
            // who the pinned node names (ADR 0144 decision 7): its scope's members, and the tenant's directory that names them
            out.getOrPut(key(KeptRead.MEMBERS, pin.tenant, pin.scope, pin.scope)) { HashSet() } += pin.node
            out.getOrPut(key(KeptRead.MEMBERS, pin.tenant, pin.tenant, pin.tenant)) { HashSet() } += pin.node
            val rows = json(nodesKey).flatMap { it.array() }
            val open = walk(rows)
            if (pin.node !in open) continue
            val children = rows.groupBy { it.text("parentId") }
            val below = LinkedHashSet<String>()
            fun down(id: String) {
                if (!below.add(id)) return
                for (c in children[id].orEmpty()) c.text("id")?.takeIf { it in open }?.let { down(it) }
            }
            down(pin.node)
            val into = HashSet<EntryKey>()
            for (n in below) {
                belowNode(pin.tenant, pin.scope, n, into)
                pin.covers[n]?.let { into += blobKey(pin.tenant, pin.scope, it) }
                pin.named[n]?.forEach { into += blobKey(pin.tenant, pin.scope, it) }
            }
            // a pinned file is listed in its folder's files (the space's top: ""): that listing is the pin's too, before it is kept
            val row = rows.firstOrNull { it.text("id") == pin.node }
            val parent = key(KeptRead.FILES, pin.tenant, pin.scope, row?.text("parentId") ?: "")
            val own = HashSet<EntryKey>()
            filesOf(parent, pin.tenant, pin.scope, own, only = below)
            if (own.isNotEmpty() || row?.text("kind") == FILE_KIND) into += own + parent
            for (k in into) out.getOrPut(k) { HashSet() } += pin.node
        }
        return out.also { reachCache = it }
    }

    /** Every entry given exactly the pins that reach it now ([reach]): an entry no pin reaches any more is the automatic tier's. */
    internal suspend fun applyPins() {
        val reach = if (loaded().pins.isEmpty()) emptyMap() else reach()
        val changes = HashMap<EntryKey, Set<String>>()
        for (e in store.entries()) {
            if (e.key.kind in KeptRead.PROTECTED) continue
            val want = reach[e.key].orEmpty()
            if (want != e.pins) changes[e.key] = want
        }
        if (changes.isNotEmpty()) store.setPinsAll(changes)
    }

    /**
     * Each pin held against the kept answers (decision 6): its node no longer listed — its tenant left, its scope gone from
     * the tenant's scopes, the node gone from its scope's tree (trashed) — drops the pin with its notice; a node the
     * operator blocked (it or one above it) keeps the pin as not available, and one no longer blocked waits for the sync.
     * A pin whose tree is not kept is left as it is: nothing current says it went.
     */
    private suspend fun settlePins() = pinLock.withLock {
        val r = loaded()
        if (r.pins.isEmpty()) return@withLock
        val tenantsKept = store.entry(key(KeptRead.MY_TENANTS)) != null
        val tenants = json(key(KeptRead.MY_TENANTS)).flatMap { it.array() }.mapNotNull { it.text("tenantId") }.toSet()
        val gone = ArrayList<Pin>()
        val now = r.pins.mapNotNull { pin ->
            if (pin.tenant in r.suspended) return@mapNotNull pin.copy(state = Pin.State.UNAVAILABLE)
            if (tenantsKept && pin.tenant !in tenants) return@mapNotNull null.also { gone += pin }
            val scopesKey = key(KeptRead.MY_SCOPES, pin.tenant)
            if (store.entry(scopesKey) != null) {
                val scopes = json(scopesKey).flatMap { it.array() }.filter { it.text("deletedAt") == null }.mapNotNull { it.text("id") }
                if (pin.scope !in scopes) return@mapNotNull null.also { gone += pin }
            }
            val nodesKey = key(KeptRead.NODES, pin.tenant, pin.scope, pin.scope)
            if (store.entry(nodesKey) == null) return@mapNotNull pin
            val rows = json(nodesKey).flatMap { it.array() }
            when {
                rows.none { it.text("id") == pin.node } -> null.also { gone += pin }
                pin.node !in walk(rows) -> pin.copy(state = Pin.State.UNAVAILABLE)
                pin.state == Pin.State.UNAVAILABLE -> pin.copy(state = Pin.State.WAITING)
                else -> pin
            }
        }
        if (now != r.pins) save(r.copy(pins = now, gone = r.gone + gone.map { PinGone(it.tenant, it.node, it.kind) }))
    }

    /** "Remove all offline content": every entry but the answers an account opens with, the pins with them (asked first). */
    suspend fun removeAll() {
        pinLock.withLock { save(PinRecord()) }
        store.removeAll(store.entries().filter { it.key.kind !in KeptRead.OPENING }.map { it.key })
    }

    /**
     * Ends this holder's use of the store: the last one writes the last views a session moved (the app going to the
     * background) and the store forgets its key.
     */
    suspend fun close() {
        if (release != null && !release.invoke(this, false)) return
        runCatching { store.flush() }
        store.close()
    }

    /** Closes the store whoever else holds it: its account leaves this device and the store is wiped next (decision 11). */
    internal suspend fun closeForWipe() {
        release?.invoke(this, true)
        store.close()
    }

    companion object {
        /** The device store's key of an account's limit, its account key after it. */
        const val LIMIT = "veyq.offline-limit."

        /** The defaults the hosts pass (owner 2026-11-15): 2 GB on a phone, 10 GB on a desktop. */
        const val PHONE_LIMIT = 2_000_000_000L
        const val DESKTOP_LIMIT = 10_000_000_000L

        /** A Depot file's node kind: its content is listed in its folder's files (`listFiles`, [KeptRead.FILES]). */
        private const val FILE_KIND = "FILE"

        /** Neither tier writes while the volume has less than this share free. */
        private const val FLOOR_PERCENT = 5

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}

/**
 * A node this account keeps available offline on this device (ADR 0140 decision 9), named by its own ids; [covers] the
 * cover blob of each node below it its last fetch found (node id to blob id: ids only, the meta that names them is sealed),
 * [named] each node's blobs its kind names by meta alone ([PinKinds.named]: a Memory's banner, ADR 0144 decision 7) likewise.
 */
@Serializable
data class Pin(
    val tenant: String, val scope: String, val node: String, val kind: String, val state: State = State.WAITING,
    val covers: Map<String, String> = emptyMap(),
    val named: Map<String, List<String>> = emptyMap(),
) {
    /**
     * Where its fetch is: [WAITING] for the next sync (pinned, or interrupted), [FETCHING], [KEPT] whole as of the last
     * sync, paused at the free-space floor ([NO_SPACE]), or [UNAVAILABLE] — its node blocked or its tenant suspended, its
     * content not kept until the server serves it again.
     */
    enum class State { WAITING, FETCHING, KEPT, NO_SPACE, UNAVAILABLE }
}

/** A pin dropped because its node is no longer available (trashed, a space or a tenant left): said once ("no longer available"). */
@Serializable
data class PinGone(val tenant: String, val node: String, val kind: String)

/** The pins' record in the store. No name is in it: a node's name is plaintext, and the store keeps none (decision 1). */
@Serializable
internal data class PinRecord(val pins: List<Pin> = emptyList(), val suspended: Set<String> = emptySet(), val gone: List<PinGone> = emptyList())
