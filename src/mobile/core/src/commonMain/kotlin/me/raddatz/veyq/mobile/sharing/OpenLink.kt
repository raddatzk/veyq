package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg

/**
 * *Open a link* (ADR 0150 decisions 4 and 8): a field the person pastes a received link into — on the sign-in screen
 * under the sign-in alone (no session needed; owner 2026-11-19: not in the shell's menus) —, checked as it is typed, opened as the link's guest
 * ([GuestScreen]) by [open], or refused with its reason and, where the link may still be a browser's, *Open in the
 * browser* ([State.browser]: the text as received, which the platform hands to the system browser — the same thing a click
 * on it would have done). The platform's paste control writes into [text]; nothing here reads a clipboard.
 */
class OpenLink(private val opener: (ReceivedLink.ShareLink, report: Boolean) -> GuestView) {
    data class State(
        val text: String = "",
        /** Why the text is no link the app opens; null while empty or fine. */
        val problem: Msg? = null,
        /** The link as received, offered to the system browser where the app does not open it ([Msg.LINK_OPEN_IN_BROWSER]). */
        val browser: String? = null,
        val canOpen: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var parsed: ReceivedLink.Parsed.Ok? = null

    fun text(value: String) {
        val r = ReceivedLink.open(value)
        parsed = r as? ReceivedLink.Parsed.Ok
        val problem = (r as? ReceivedLink.Parsed.Refused)?.problem
        _state.value = State(
            text = value,
            problem = problem?.let(BookmarkCreate::wordOf),
            // a moderator's review is a browser's (the share page's own mode); anything else a browser would not open better
            browser = if (problem == ReceivedLink.Problem.MODERATION) value.trim() else null,
            canOpen = parsed != null,
        )
    }

    /** The link opened as its guest: a fresh guest view, not loaded yet ([GuestScreen.load]). Null while the text is no link. */
    fun open(): GuestView? {
        val ok = parsed ?: return null
        val view = opener(ok.link, ok.report)
        _state.update { State() } // the text holds the key: it is not kept once opened
        parsed = null
        return view
    }
}

/**
 * One opened link as its screen above the shell (ADR 0150 decision 4): the [view] behind its gates, *Back* inside the link,
 * and at its root a *Back* that asks first — the key lives only in memory, so leaving drops the link unless it was kept —
 * and then lets go of it, returning the person where they were (the platform's: the sign-in screen, or the shell's
 * place). [refusal] carries an instance the app does not open (decision 8), with the link for the browser. [keep] is
 * offered to a signed-in person ([tenant] non-null): the link kept as a bookmark in a place they pick, their personal
 * space first.
 */
class GuestScreen(
    val view: GuestView,
    /** The tenant a signed-in person keeps the link in; null before the sign-in (no *Keep*). */
    private val tenant: TenantSession? = null,
    /** The link's fragment asked for the report dialog (`&report`, a bookmark's *Report shared content*). */
    val reportAsked: Boolean = false,
) {
    data class State(
        /** *Back* at the root asked: the platform shows [Msg.LINK_LEAVE_TITLE] with [Msg.LINK_LEAVE_ACCEPT]. */
        val leaving: Boolean = false,
        /** The link let go: the platform returns where the person was. */
        val left: Boolean = false,
        /** The report dialog is to open (once the gates passed). */
        val reporting: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The link's host (the header names the root once the gates passed). */
    val host: String get() = view.gates.link.host

    /** The link as received, for *Open in the browser* where the app does not open its instance (decision 8). */
    val browserLink: String? get() = if (view.gates.state.value is ShareGates.State.Refused) view.gates.link.link else null

    suspend fun load() {
        view.load()
        if (reportAsked && view.access != null) _state.update { it.copy(reporting = true) }
    }

    /** After a gate was passed (password, terms, note): the content, and an asked report. */
    suspend fun enter() {
        view.enter()
        if (reportAsked && view.access != null) _state.update { it.copy(reporting = true) }
    }

    /** The report of the link to its own operator ([GuestReport]); null before the gates passed. */
    fun reportSheet() = GuestReport.sheet(view)

    fun reportShown() = _state.update { it.copy(reporting = false) }

    /** *Back*: a step inside the link, or at its root the question ([State.leaving]). */
    suspend fun back() {
        if (view.access != null && view.back()) return
        _state.update { it.copy(leaving = true) }
    }

    fun stay() = _state.update { it.copy(leaving = false) }

    /** The question confirmed: the link let go (its key zeroed), the screen to close. */
    fun leave() {
        view.close()
        _state.update { State(left = true) }
    }

    /** Whether *Keep* is offered: signed in, and the link behind its gates. */
    val keepOffered: Boolean get() = tenant != null && view.access != null

    /** The places a link may be kept: the person's personal space first, then the others' tops (a folder is chosen inside one). */
    suspend fun keepPlaces(): List<SpaceRef> {
        val t = tenant ?: return emptyList()
        return t.spaces().sortedBy { if (it.kind == "PERSONAL") 0 else 1 }
    }

    /**
     * The bookmark dialog for keeping the link in space [space] under [parentId] (null: its top), filled with the link as
     * opened and [password] where the person entered one; null where *Keep* is not offered.
     */
    fun keep(space: SpaceRef, parentId: String? = null, password: String? = null): BookmarkCreate? {
        val t = tenant ?: return null
        if (view.access == null) return null
        return BookmarkCreate(
            BookmarkCreate.into(t, space.scopeId, parentId), groupSpace = space.kind != "PERSONAL", view.gates.link, password ?: view.gates.enteredPassword,
        )
    }

    /** The page's node, as the header shows it. */
    val node: NodeAt? get() = view.page.value?.node
}
