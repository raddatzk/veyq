package me.raddatz.veyq.mobile.sharing

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * A folder of a link as its guest sees it (ADR 0150 decision 5: core's level over the guest's tree): its children by
 * their order, each opened as its kind's page by the [GuestView]. The member's level, without a member's functions.
 */
class GuestLevel internal constructor(val node: NodeAt, private val tree: GuestTree) {
    /** One row: a child's id, kind and name (a guest's identity folder is marked, as the member's). */
    data class Row(val nodeId: String, val kind: String, val name: String, val identity: Boolean)

    val rows: List<Row> get() = tree.children(node.nodeId).map { Row(it.id, it.kind, it.name, it.shareIdentityId != null) }

    companion object {
        /** Core's answer for a folder of a READ or READ_WRITE link. */
        val door = GuestDoor { access, node, _ ->
            val tree = access.tree ?: return@GuestDoor GuestPage.NotShown(node)
            GuestPage.Level(node, GuestLevel(node, tree))
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
) {
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
        val a = GuestAccess(gates, head, tree, queue, deriver, now, sharesCopies)
        access = a
        _stack.value = emptyList()
        show(a.root)
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
        access?.tree?.close()
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
        ): GuestView {
            val guest = GuestClient.of(primitives, link.origin, client)
            val gates = ShareGates(link, guest, store, sessions, locale, handedPassword) { h -> h.rootKind?.let { doors(it, h.role) } != null }
            return GuestView(gates, doors, queue, deriver, now, sharesCopies)
        }

        /** Over an existing guest client (a test's played instance). */
        internal fun over(
            link: ReceivedLink.ShareLink, guest: GuestClient, store: DeviceStore, sessions: GuestSessions, locale: () -> String = { "en" },
            handedPassword: String? = null, queue: UploadQueue? = null, deriver: MediaUpload.Deriver? = null, now: () -> Long = { 0L },
            doors: (kind: String, role: String) -> GuestDoor? = AppKinds::guestDoor,
        ): GuestView {
            val gates = ShareGates(link, guest, store, sessions, locale, handedPassword) { h -> h.rootKind?.let { doors(it, h.role) } != null }
            return GuestView(gates, doors, queue, deriver, now)
        }
    }
}
