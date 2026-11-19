package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A kept link's page (ADR 0150 decision 6; the web's `BookmarkDetail.vue` and `BookmarkEmbed.vue`, uc.sharing.browse-a-
 * received-share-in-place): its meta opened under the bookmark's key; while `core.share-embedding` is on for the tenant the
 * share shown in place under the page's header ([State.InPlace]: a guest view of the link, its password handed in memory);
 * otherwise the row opens the link on its own page ([State.OnItsOwn], the header's *Open on its own page* in either case).
 * *Report shared content* opens the link's guest view with its report dialog open — to the share's own operator —, and
 * *Report name* reports the bookmark's name to this instance, never its key ([reportNameSheet]). A meta that does not
 * read is [State.Unreadable], offering only its removal.
 */
class BookmarkPage(val node: NodeAt, private val door: Door) {
    interface Door {
        /** The bookmark this node keeps, opened; null where its meta does not read. */
        suspend fun bookmark(): BookmarkMeta.Bookmark?

        /** Whether `core.share-embedding` is on for the tenant (null: its set did not read — not shown in place). */
        fun embedding(): Boolean?

        /** A guest view of [link], [password] handed in memory. */
        fun guest(link: ReceivedLink.ShareLink, password: String?): GuestView

        /** The name's report to this instance's moderators. */
        fun reportName(): ReportSheet
    }

    sealed class State {
        object Loading : State()
        /** The meta does not read: [me.raddatz.veyq.mobile.words.Msg.BOOKMARK_UNREADABLE], its removal offered. */
        object Unreadable : State()
        /** The share shown in place under the header ([me.raddatz.veyq.mobile.words.Msg.BOOKMARK_EMBED_REGION] with [host]). */
        class InPlace(val host: String, val screen: GuestScreen) : State()
        /** The row opens the link on its own page. */
        class OnItsOwn(val host: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private var bookmark: BookmarkMeta.Bookmark? = null

    suspend fun load() {
        val b = door.bookmark()
        bookmark = b
        if (b == null) {
            _state.value = State.Unreadable
            return
        }
        _state.value = if (door.embedding() == true) {
            val screen = GuestScreen(door.guest(b.link, b.password))
            State.InPlace(b.link.host, screen).also { screen.load() }
        } else {
            State.OnItsOwn(b.link.host)
        }
    }

    /** *Open on its own page* (the header's `open-own-page`): the link's screen above the shell, its password handed. */
    fun openOwnPage(tenant: TenantSession? = null): GuestScreen? {
        val b = bookmark ?: return null
        return GuestScreen(door.guest(b.link, b.password), tenant)
    }

    /** *Report shared content*: the link's screen with its report dialog asked (to the share's own operator). */
    fun reportShared(): GuestScreen? {
        val b = bookmark ?: return null
        return GuestScreen(door.guest(b.link, b.password), reportAsked = true)
    }

    /** *Report name*: the bookmark's name only, to this instance (its key is never handed over). */
    fun reportNameSheet(): ReportSheet = door.reportName()

    /** The page left: an in-place link let go. */
    fun close() {
        (state.value as? State.InPlace)?.screen?.view?.close()
    }

    companion object {
        const val EMBEDDING = "core.share-embedding"

        /** The page of bookmark [node] in [tenant]; [guest] opens a link as its guest (the platform's client, stores and queue). */
        fun of(tenant: TenantSession, node: NodeAt, guest: (ReceivedLink.ShareLink, String?) -> GuestView): BookmarkPage =
            BookmarkPage(node, object : Door {
                override suspend fun bookmark(): BookmarkMeta.Bookmark? =
                    tenant.node(node.scopeId, node.nodeId)?.let { BookmarkMeta.open(tenant.account.suite, it) }
                override fun embedding(): Boolean? = tenant.capabilityOn(EMBEDDING)
                override fun guest(link: ReceivedLink.ShareLink, password: String?) = guest(link, password)
                override fun reportName(): ReportSheet = ReportSheet(node, object : ReportSheet.Door {
                    override val origin: String get() = tenant.origin
                    override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
                        NodeReport.report(tenant, node.scopeId, node.nodeId, NodeReport.Scope.NAME, category, reason, contact)
                }, nameOnly = true)
            })
    }
}
