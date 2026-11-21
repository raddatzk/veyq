package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Unreachable
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * What a pin keeps of a node of each kind (ADR 0140 decision 9): the build's composition root names it
 * ([me.raddatz.veyq.mobile.AppKinds.pins]), never core — core walks the tree below a pinned node, a kind names the blobs
 * of a node of its own.
 */
interface PinKinds {
    /** Whether a node of [kind] offers "Available offline" (its header's toggle): a Memory, a chapter, a folder, a file. */
    fun pinnable(kind: String): Boolean

    /**
     * The blobs a pin keeps of [node] itself — its tiles and previews, the originals the app reads to show it (a video's,
     * a Depot file's; never a photo's, decision 8) — read through the kept reads, so its listings are kept as they are
     * read. Its children are the walk's. None for a kind with no content of its own (a folder) or one this build does not keep.
     */
    suspend fun blobs(tenant: TenantSession, node: OpenNode): List<PinBlob>

    /**
     * The blobs a pin keeps of [node] that only its sealed meta names — a Memory's banner, its slot and a video's poster
     * (ADR 0144 decision 7): no kept listing reaches them, so the pin's record names them by id. None by default.
     */
    fun named(tenant: TenantSession, node: OpenNode): List<PinBlob> = emptyList()
}

/** One blob a pin keeps: its id, its frames and stored size, and its key (in memory only), to check each frame fetched. */
class PinBlob(val scopeId: String, val blobId: String, val chunkCount: Int, val byteSize: Long, internal val key: ByteArray)

/**
 * Every pin fetch under way in this process, across accounts: what the platforms' background keeps running for (Android's
 * worker promoted to a `dataSync` foreground service, iOS's background time and continued-processing task) and what it
 * shows as progress. Sizes only.
 */
object OfflineFetching {
    /** Bytes fetched of the bytes a fetch set out to take. */
    data class Progress(val done: Long, val total: Long)

    private val _fetches = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Each fetch under way, by an opaque name of it: empty while none is. */
    val fetches: StateFlow<Map<String, Progress>> = _fetches.asStateFlow()

    /** What every fetch under way comes to; null while none is. */
    fun total(fetches: Map<String, Progress> = _fetches.value): Progress? =
        if (fetches.isEmpty()) null else Progress(fetches.values.sumOf { it.done }, fetches.values.sumOf { it.total })

    /** A host that cannot collect a flow (Swift): [listener] told the total at once and at each change, until [Watch.stop]. */
    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    private val watching = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun watch(listener: (Progress?) -> Unit): Watch = Watch(watching.launch { fetches.collect { listener(total(it)) } })

    internal fun set(name: String, progress: Progress?) = _fetches.update { if (progress == null) it - name else it + (name to progress) }
}

/**
 * An account's pins and their sync on this device (ADR 0140 decision 9), over its offline store ([offline]).
 *
 * - **A pin** is a node of a pinnable kind ([PinKinds.pinnable]); [plan] tells what pinning it would keep before the first
 *   byte (shown and confirmed), [pin] keeps it, [unpin] takes it off. A pin is held in the store's own record
 *   ([AccountOffline.pins]) and outlives the app.
 * - **Its fetch** walks the node's tree below it (the open tree: nothing the operator blocked), reads each node's listings
 *   through the kept reads and fetches every frame of every blob its kind names that the store does not hold — appended,
 *   so an interrupted fetch continues where it stopped, and a blob kept already (a re-key keeps `K_blob` and the blob's
 *   id, R5) is never fetched again. It pauses at the free-space floor ([Pin.State.NO_SPACE]).
 * - **The sync** ([sync]) runs every pin's fetch: when the account opens online ([start], from the session's door), when
 *   it is back online, on a read that sees a pinned listing's revision move ([wake], `VeyqAccount.kept`), and on its own
 *   poll while the account is open (each a conditional read: a `304` costs no listing). What left is swept by the reads'
 *   own sweep (decision 6). It runs while the app runs with the account open — the platforms' background keeps the
 *   process for it ([OfflineFetching]); a scheduled wake-up without the person is not this (#896).
 */
class OfflinePins internal constructor(
    private val account: VeyqAccount,
    val offline: AccountOffline,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    /** The pins as kept, with their states. */
    val pins: StateFlow<List<Pin>> get() = offline.pins

    /** The pins dropped since their node is no longer available, until [AccountOffline.seenGone]. */
    val gone: StateFlow<List<PinGone>> get() = offline.gone

    private val _progress = MutableStateFlow<Map<String, OfflineFetching.Progress>>(emptyMap())

    /** Each pin's fetch under way, by its node: bytes fetched of the bytes it set out to take. */
    val progress: StateFlow<Map<String, OfflineFetching.Progress>> = _progress.asStateFlow()

    private var kinds: PinKinds? = null
    private val sessions = HashMap<String, TenantSession>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val syncing = Mutex()
    private var loop: Job? = null

    /**
     * The name of [pin]'s node in the tree this account's session of its tenant reads (`TenantSession.trees`, a kept read
     * offline) — never the store's: it keeps no name; null where no session of the tenant is held or the node is not in it.
     */
    suspend fun nameOf(pin: Pin): String? = sessions[pin.tenant]?.let { t ->
        try {
            t.node(pin.scope, pin.node)?.name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Each change of the pins or of their fetches. */
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.combine(pins, progress) { _, _ -> }

    /** Whether node [nodeId] is pinned. */
    fun pinned(nodeId: String): Boolean = pins.value.any { it.node == nodeId }

    /** Whether a node of [kind] can be pinned here (the kinds are the session door's, once a tenant opened). */
    fun pinnable(kind: String): Boolean = kinds?.pinnable(kind) == true

    /** What pinning a node would keep on this device: the stored bytes of every blob it keeps, of how many blobs. */
    class Plan(val bytes: Long, val blobs: Int)

    /**
     * The size of pinning node [nodeId] of [scopeId], before the first byte (decision 9): what the pin keeps (the question
     * says "This keeps …"), every blob whole — the frames viewing kept already included, which the pin keeps too (#935: a
     * Memory whose videos were played asked "0 B" of the ~6 MB its pin then held); its fetch takes what is not kept yet.
     * Null where it cannot be pinned.
     */
    suspend fun plan(tenant: TenantSession, scopeId: String, nodeId: String): Plan? {
        val kinds = kinds ?: return null
        val node = tenant.node(scopeId, nodeId)?.takeIf { kinds.pinnable(it.kind) } ?: return null
        val blobs = blobsBelow(tenant, kinds, node).blobs
        return Plan(blobs.sumOf { it.byteSize }, blobs.size)
    }

    /** Node [nodeId] of [scopeId] pinned and its fetch started; false where it cannot be pinned. Offline, it waits. */
    suspend fun pin(tenant: TenantSession, scopeId: String, nodeId: String): Boolean {
        val kinds = kinds ?: return false
        val node = tenant.node(scopeId, nodeId)?.takeIf { kinds.pinnable(it.kind) } ?: return false
        sessions[tenant.tenant.tenantId] = tenant
        offline.addPin(Pin(tenant.tenant.tenantId, scopeId, nodeId, node.kind))
        wake()
        return true
    }

    /** The pin of [nodeId] taken off: what no other pin reaches is the automatic tier's again ([AccountOffline.removePin]). */
    suspend fun unpin(nodeId: String) {
        offline.removePin(nodeId)
        showProgress(nodeId, null)
    }

    /**
     * The sync started over the build's [kinds] (`AccountDoor.opened`: each tenant shown, after a sign-in, an unlock or a
     * resume), [tenant] the session it reads that tenant through; a pass runs now, and then on [wake] and its poll.
     */
    fun start(tenant: TenantSession, kinds: PinKinds) {
        this.kinds = kinds
        sessions[tenant.tenant.tenantId] = tenant
        if (loop?.isActive != true) loop = scope.launch { run() }
        wake()
    }

    /** The build's [kinds] without the loop: a test drives [sync] itself. */
    @PlatformApi
    @HiddenFromObjC
    fun use(kinds: PinKinds) {
        this.kinds = kinds
    }

    /** A pass soon: a pin added, a pinned listing's revision moved. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    private suspend fun run() {
        val poll = LivePoll(CADENCE, now)
        var wait = 0L
        while (true) {
            withTimeoutOrNull(wait) { wakeups.receive() }
            when (account.reach.value) {
                is Reach.Ended -> return
                is Reach.Offline -> {
                    // back online is a pass of its own
                    if (account.reach.first { it !is Reach.Offline } is Reach.Ended) return
                }
                Reach.Online -> Unit
            }
            poll.started()
            val outcome = try {
                sync()
                LivePoll.Tick.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LivePoll.Tick.FAILED
            }
            wait = poll.next(outcome)
        }
    }

    /**
     * One pass over every pin: per tenant its trees read anew (each conditional on its kept revision; the read's sweep
     * drops what left, a pin of a node gone with its notice), then each pin's fetch of what is new. Unreachable ends the
     * pass: the next one continues it.
     */
    suspend fun sync() = syncing.withLock {
        val kinds = kinds ?: return@withLock
        val byTenant = offline.loaded().pins.groupBy { it.tenant }
        for ((tenantId, _) in byTenant) {
            val tenant = session(tenantId) ?: continue
            tenant.forgetTree()
            try {
                tenant.trees()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unreachable) {
                throw e
            } catch (e: Exception) {
                continue
            }
            for (pin in offline.loaded().pins.filter { it.tenant == tenantId && it.state != Pin.State.UNAVAILABLE }) fetch(tenant, kinds, pin)
        }
    }

    /** The session tenant [tenantId] is read through: the door's, else one opened here; null where it does not open. */
    private suspend fun session(tenantId: String): TenantSession? = sessions[tenantId] ?: run {
        val ref = account.tenants.firstOrNull { it.tenantId == tenantId } ?: return null
        val opened = try {
            account.open(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unreachable) {
            throw e
        } catch (e: Exception) {
            return null
        }
        opened.also { sessions[tenantId] = it }
    }

    /** [pin]'s fetch: its blobs listed, its entries given the pin, then every frame not kept fetched and kept. */
    private suspend fun fetch(tenant: TenantSession, kinds: PinKinds, pin: Pin) {
        // a node no longer in the tree: the reads' sweep says so (its pin dropped with a notice)
        val node = tenant.node(pin.scope, pin.node) ?: return
        // its scope's member list kept with it, so offline its participants are named (ADR 0144 decision 7); a refusal keeps none
        tenant.scopeMembers(pin.scope)
        val below = blobsBelow(tenant, kinds, node)
        val blobs = below.blobs
        account.settle()
        // the covers and banners the pin keeps are named by the nodes' sealed meta, which the store cannot read: the pin records them
        offline.setPinCovers(pin.node, below.covers, below.named)
        offline.applyPins()
        val todo = blobs.map { it to offline.missing(keyOf(tenant, it), it.chunkCount, it.byteSize) }.filter { it.second > 0 }
        if (todo.isEmpty()) {
            offline.setPinState(pin.node, Pin.State.KEPT)
            return
        }
        val total = todo.sumOf { it.second }
        var done = 0L
        offline.setPinState(pin.node, Pin.State.FETCHING)
        showProgress(pin.node, OfflineFetching.Progress(0, total))
        try {
            for ((b, _) in todo) {
                val source = ApplicationBlobs(tenant).FrameSource(b.scopeId, b.blobId, b.chunkCount, b.key, keep = true)
                val took = source.fill { size ->
                    done += size
                    showProgress(pin.node, OfflineFetching.Progress(minOf(done, total), total))
                }
                if (!took) {
                    offline.setPinState(pin.node, Pin.State.NO_SPACE)
                    return
                }
            }
            offline.setPinState(pin.node, Pin.State.KEPT)
        } catch (e: CancellationException) {
            offline.setPinState(pin.node, Pin.State.WAITING)
            throw e
        } catch (e: Unreachable) {
            // the next pass continues where this one stopped
            offline.setPinState(pin.node, Pin.State.WAITING)
            throw e
        } catch (e: Exception) {
            // a frame refused (a blob gone meanwhile): the next pass, after the reads' sweep, tries what is left
            println("veyq: a pin's fetch stopped: $e")
            offline.setPinState(pin.node, Pin.State.WAITING)
        } finally {
            showProgress(pin.node, null)
        }
    }

    /** What a pin keeps below a node: the blobs, and the blobs only a node's meta names by its node — its cover, its kind's ([PinKinds.named]) — which [AccountOffline.reach] reads. */
    private class Below(val blobs: List<PinBlob>, val covers: Map<String, String>, val named: Map<String, List<String>>)

    /**
     * The blobs of [root] and of every node below it in the open tree, by the kinds' rule — and each node's cover (core's,
     * every kind's: a Memory's and its chapters' covers draw offline, a folder's too); a node whose listing is refused keeps
     * none of its kind's.
     */
    private suspend fun blobsBelow(tenant: TenantSession, kinds: PinKinds, root: OpenNode): Below {
        val nodes = tenant.trees()[root.scopeId].orEmpty()
        val children = nodes.groupBy { it.parentId }
        val out = ArrayList<PinBlob>()
        val covers = LinkedHashMap<String, String>()
        val named = LinkedHashMap<String, List<String>>()
        val seen = HashSet<String>()
        suspend fun down(n: OpenNode) {
            if (!seen.add(n.id)) return
            try {
                out += kinds.blobs(tenant, n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unreachable) {
                throw e
            } catch (e: Exception) {
                // a refused listing keeps nothing of this node; the others still are
            }
            coverOf(tenant, n)?.let { out += it; covers[n.id] = it.blobId }
            // what only the node's meta names, by its kind (a Memory's banner: it opens with it offline, ADR 0144 decision 7)
            runCatching { kinds.named(tenant, n) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { b -> out += b; named[n.id] = b.map { it.blobId } }
            for (c in children[n.id].orEmpty()) down(c)
        }
        down(root)
        return Below(out.distinctBy { it.blobId }, covers, named)
    }

    /** Node [n]'s cover, where its sealed meta names one whose key opens (`NodeCover.read`); null otherwise. */
    private fun coverOf(tenant: TenantSession, n: OpenNode): PinBlob? {
        val meta = runCatching { NodeMetaWrite.openForWrite(account.suite, n.scopeId, n.id, n.key, n.wrappedMeta) }.getOrNull() ?: return null
        val c = NodeCover.read(meta) ?: return null
        val key = ApplicationBlobs(tenant).openKey(n.scopeId, n, c.blobId, NodeCover.ROLE, c.wrappedBlobKey) ?: return null
        return PinBlob(n.scopeId, c.blobId, c.chunkCount, c.byteSize, key)
    }

    private fun keyOf(tenant: TenantSession, b: PinBlob) = offline.blobKey(tenant.tenant.tenantId, b.scopeId, b.blobId)

    private fun showProgress(node: String, p: OfflineFetching.Progress?) {
        _progress.update { if (p == null) it - node else it + (node to p) }
        OfflineFetching.set("${account.key}\u0000$node", p)
    }

    /** The sync stops (the account closes); a fetch under way ends where it is, to continue at the next open. */
    @PlatformApi
    @HiddenFromObjC
    fun stop() {
        loop?.cancel()
        for (node in _progress.value.keys) OfflineFetching.set("${account.key}\u0000$node", null)
        _progress.value = emptyMap()
    }

    internal companion object {
        /** The sync's own poll while the account is open: a pass a minute, backing off to five while it fails. */
        val CADENCE = LivePoll.Cadence(intervalMs = 60_000, maxBackoffMs = 300_000, jitter = 0.2, minGapMs = 0)
    }
}
