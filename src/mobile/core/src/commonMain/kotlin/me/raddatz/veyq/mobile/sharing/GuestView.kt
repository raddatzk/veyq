package me.raddatz.veyq.mobile.sharing

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * A folder of a link as its guest sees it (ADR 0150 decision 5: core's level over the guest's tree): its children by
 * their order, each opened as its kind's page by the [GuestView]. The member's level, without a member's functions — and
 * a READ_WRITE guest's writes as the web's share page grants them at a folder (`ShareTreePage`, #1034): a new folder
 * (`createShareNodeAsGuest`) and what an application's level action offers (Depot: files uploaded into it), once the
 * guest named itself; never in a guest's identity folder. After a write the link is read anew ([GuestView.reload]).
 */
class GuestLevel internal constructor(
    val node: NodeAt,
    private val tree: GuestTree,
    private val access: GuestAccess? = null,
    private val view: GuestView? = null,
) {
    /** One row: a child's id, kind and name (a guest's identity folder is marked, as the member's). */
    data class Row(val nodeId: String, val kind: String, val name: String, val identity: Boolean)

    val rows: List<Row> get() = tree.children(node.nodeId).map { Row(it.id, it.kind, it.name, it.shareIdentityId != null) }

    /**
     * Whether the guest adds here — a READ_WRITE link, outside a guest's identity folder (the server refuses it there,
     * `illegal_structure`): New folder and the upload are offered. A READ guest's level offers nothing.
     */
    val writes: Boolean get() = access != null && access.role == GuestAccess.READ_WRITE && !access.inIdentityFolder(node.nodeId)

    /**
     * The level's New (ADR 0154 decision 2, a plan every platform draws whole and in this order): where the guest [writes],
     * a folder, then files uploaded here (the web's share page: New folder and Depot's level action); a guest makes no
     * Memory, Pot, drop box or kept link. None where it does not write.
     */
    val creates: List<me.raddatz.veyq.mobile.AppKinds.Create>
        get() = if (writes) listOf(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER, me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES) else emptyList()

    /** The guest's session: a write asks the guest's name first ([GuestAccess.nameGuest]) while it is null. */
    val guest: StateFlow<GuestSession?>? get() = access?.session

    /**
     * New folder [name] here as the guest, as New Folder's dialog answers it ([NameAnswer], ADR 0154 decision 1): an empty
     * name or a sibling's is a problem (the dialog asks again); once made the link is read anew; a refusal, a thrown error
     * and a write the guest declined to name itself for are said. A guest not named yet is asked its name at the write
     * ([GuestAccess.naming], the web's `requestGuest`), never by a platform's own rule.
     */
    suspend fun createFolder(name: String): me.raddatz.veyq.mobile.navigation.NameAnswer {
        val failed = me.raddatz.veyq.mobile.navigation.NameAnswer.said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.FOLDER_FAILED))
        val a = access ?: return failed
        val n = name.trim()
        if (n.isEmpty()) return me.raddatz.veyq.mobile.navigation.NameAnswer.problem(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.FOLDER_NAME_EMPTY))
        val parent = tree.node(node.nodeId)
        if (!writes || parent == null) return failed
        val session = a.naming.request() ?: return failed
        val made = runCatching { a.createNode(session, parent, NodeRef.FOLDER, n) }.getOrNull()
        return when (made) {
            is GuestAccess.Created.Done -> {
                made.key.fill(0)
                view?.reload()
                me.raddatz.veyq.mobile.navigation.NameAnswer.DONE
            }
            GuestAccess.Created.Taken -> me.raddatz.veyq.mobile.navigation.NameAnswer.problem(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.FOLDER_NAME_TAKEN))
            else -> failed
        }
    }

    /**
     * [sources] uploaded into this folder as the guest (the application's level action, `AppKinds.guestLevelUploads`:
     * Depot's files), on the guest view's queue; how many were queued — none before the guest named itself, on a READ
     * link, without a queue. Each one settled reads the link anew, so its row shows.
     */
    fun upload(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>): Int {
        val a = access ?: return 0
        val queue = a.queue ?: return 0
        val session = a.session.value ?: return 0
        val parent = tree.node(node.nodeId) ?: return 0
        if (!writes || sources.isEmpty()) return 0
        val jobs = AppKinds.guestLevelUploads(a, session, parent, sources)
        if (jobs.isEmpty()) return 0
        queue.enqueue(jobs, node.name, null, { view?.reloadSoon() }, null)
        return jobs.size
    }

    /**
     * [sources] uploaded here as [upload] does, a guest not named yet asked its name first through the core's one naming
     * flow ([GuestAccess.naming], ADR 0154 decision 1); declined, none is queued (and each is the caller's to close).
     */
    suspend fun uploadAsking(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>): Int {
        val a = access ?: return 0
        if (!writes || sources.isEmpty()) return 0
        a.naming.request() ?: return 0
        return upload(sources)
    }

    companion object {
        /** Core's answer for a folder of a READ or READ_WRITE link. */
        val door = GuestDoor { access, node, view ->
            val tree = access.tree ?: return@GuestDoor GuestPage.NotShown(node)
            GuestPage.Level(node, GuestLevel(node, tree, access, view))
        }
    }
}

/**
 * One received link open in the app (ADR 0150 decisions 2–5, 8): a screen above the shell, with its own header (the root's
 * name) and back stack, no drawer and no bar. It runs the link's [gates] and, behind them, opens each node as its kind's
 * guest page through [doors] (`AppKinds.guestDoor`). Holds `K_share` and the tree's keys until [close]; nothing of it is
 * kept, logged or published ([page] names nodes only).
 */
class GuestView internal constructor(
    val gates: ShareGates,
    private val doors: (kind: String, role: String) -> GuestDoor?,
    private val queue: UploadQueue?,
    private val deriver: MediaUpload.Deriver?,
    private val now: () -> Long,
    private val sharesCopies: Boolean = false,
    private val music: me.raddatz.veyq.mobile.memories.GalleryModel.Music? = null,
) {
    /** Where a write's settle reads the link anew ([reloadSoon]); cancelled at [close]. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Trees a [reload] replaced: their keys are still held by pages opened before it (an upload under way), zeroed at [close]. */
    private val retired = mutableListOf<GuestTree>()

    private val _page = MutableStateFlow<GuestPage?>(null)

    /** The page on screen, once the gates are passed; null before. */
    val page: StateFlow<GuestPage?> = _page.asStateFlow()

    private val _stack = MutableStateFlow<List<NodeAt>>(emptyList())

    /** The nodes opened from the root down (the guest view's own back stack; the root first). */
    val stack: StateFlow<List<NodeAt>> = _stack.asStateFlow()

    /** The open link's access, once its gates are passed. */
    var access: GuestAccess? = null
        private set

    /** Read the link and decide its gates ([ShareGates.load]); behind them the root's page opens ([enter]). */
    suspend fun load() {
        gates.load()
        if (gates.state.value is ShareGates.State.Ready) enter()
    }

    /** The gates passed (after a password, the terms, the note): the root's page. */
    suspend fun enter() {
        if (gates.state.value !is ShareGates.State.Ready || access != null) return
        val head = gates.head ?: return
        val tree = if (head.role == GuestAccess.WRITE_ONLY) null else runCatching { GuestTree.open(gates.guestClient.suite, head) }.getOrNull()
        val a = GuestAccess(gates, head, tree, queue, deriver, now, sharesCopies, music)
        access = a
        _stack.value = emptyList()
        show(a.root)
    }

    /**
     * The link read anew behind its passed gates (after a guest's write; the web's share page re-resolves): a fresh tree,
     * the nodes on the back stack that are still served kept, the page on top opened again (a node gone: the deepest one
     * left). The tree before is kept for what still holds its keys until [close]. Nothing where it does not resolve now.
     */
    suspend fun reload() {
        val old = access ?: return
        if (old.tree == null || !gates.reresolve()) return
        val head = gates.head ?: return
        val tree = runCatching { GuestTree.open(gates.guestClient.suite, head) }.getOrNull() ?: return
        val a = GuestAccess(gates, head, tree, queue, deriver, now, sharesCopies, music)
        access = a
        retired += old.tree
        val kept = _stack.value.mapNotNull { at ->
            if (at.nodeId == old.root.nodeId) a.root else tree.node(at.nodeId)?.let { NodeAt(it.scopeId, it.id, it.kind, it.name) }
        }.ifEmpty { listOf(a.root) }
        _stack.value = kept.dropLast(1)
        show(kept.last())
    }

    /** [reload] off the caller's thread (an upload's settle). */
    internal fun reloadSoon() {
        scope.launch { runCatching { reload() } }
    }

    /** Child [nodeId] of the node on screen opened as its kind's guest page. */
    suspend fun open(nodeId: String) {
        val a = access ?: return
        val node = a.tree?.node(nodeId) ?: return
        show(NodeAt(node.scopeId, node.id, node.kind, node.name))
    }

    private suspend fun show(node: NodeAt) {
        val a = access ?: return
        _stack.value = _stack.value + node
        _page.value = pageOf(a, node)
    }

    private suspend fun pageOf(a: GuestAccess, node: NodeAt): GuestPage {
        val door = doors(node.kind, a.role) ?: return GuestPage.NotShown(node)
        return runCatching { door.open(a, node, this) }.getOrElse { GuestPage.NotShown(node) }
    }

    /** Whether the page on screen is the link's root: *Back* there leaves the link (after the platform asked, decision 4). */
    val atRoot: Boolean get() = _stack.value.size <= 1

    /** One step back inside the link; false at its root (the caller then asks before it leaves the link). */
    suspend fun back(): Boolean {
        val a = access ?: return false
        if (atRoot) return false
        val rest = _stack.value.dropLast(1)
        _stack.value = rest.dropLast(1)
        show(rest.last())
        return true
    }

    /** The link let go: its key and the tree's keys zeroed, its client closed. */
    fun close() {
        scope.cancel()
        access?.tree?.close()
        retired.forEach { it.close() }
        retired.clear()
        access = null
        _page.value = null
        gates.close()
    }

    companion object {
        /**
         * The guest view of [link] over the platform's HTTP [client] factory, its [store] (the terms' and the note's
         * acceptances) and [sessions] (the credential store), in the viewer's [locale]; [handedPassword] a bookmark's.
         * [queue] and [deriver] are the host's for a guest's uploads (null: none offered).
         */
        fun open(
            primitives: Primitives, link: ReceivedLink.ShareLink, client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            store: DeviceStore, sessions: GuestSessions, locale: () -> String, handedPassword: String? = null,
            queue: UploadQueue? = null, deriver: MediaUpload.Deriver? = null, now: () -> Long = { 0L }, sharesCopies: Boolean = false,
            doors: (kind: String, role: String) -> GuestDoor? = AppKinds::guestDoor,
            music: me.raddatz.veyq.mobile.memories.GalleryModel.Music? = null,
        ): GuestView {
            val guest = GuestClient.of(primitives, link.origin, client)
            val gates = ShareGates(link, guest, store, sessions, locale, handedPassword) { h -> h.rootKind?.let { doors(it, h.role) } != null }
            return GuestView(gates, doors, queue, deriver, now, sharesCopies, music)
        }

        /** Over an existing guest client (a test's played instance). */
        internal fun over(
            link: ReceivedLink.ShareLink, guest: GuestClient, store: DeviceStore, sessions: GuestSessions, locale: () -> String = { "en" },
            handedPassword: String? = null, queue: UploadQueue? = null, deriver: MediaUpload.Deriver? = null, now: () -> Long = { 0L },
            doors: (kind: String, role: String) -> GuestDoor? = AppKinds::guestDoor,
            music: me.raddatz.veyq.mobile.memories.GalleryModel.Music? = null,
        ): GuestView {
            val gates = ShareGates(link, guest, store, sessions, locale, handedPassword) { h -> h.rootKind?.let { doors(it, h.role) } != null }
            return GuestView(gates, doors, queue, deriver, now, music = music)
        }
    }
}
