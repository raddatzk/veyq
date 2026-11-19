package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg

/**
 * The bookmark dialog (ADR 0150 decision 6; the web's `BookmarkCreateDialog.vue`, uc.sharing.keep-a-received-link): the
 * link checked as it is typed by [ReceivedLink.parse], its password, its name — the link's host until the person names
 * it —, and, in a group space, the line that everyone who opens the place opens the link (ADR 0120 § Consequences).
 * *New → Bookmark* opens it empty; a guest view's *Keep* opens it with the link as opened and the password the person
 * entered. Its save creates the `SHARE_BOOKMARK` node with its meta sealed in the same request ([Door.create]).
 */
class BookmarkCreate(
    private val door: Door,
    /** The place is a group space's (the tenant's or a group's): the dialog says who else opens the link. */
    val groupSpace: Boolean,
    link: ReceivedLink.ShareLink? = null,
    password: String? = null,
) {
    /** Where the bookmark is created. */
    fun interface Door {
        suspend fun create(name: String, link: ReceivedLink.ShareLink, password: String?): NodeCreate.Result
    }

    data class State(
        val text: String = "",
        val password: String = "",
        /** The name typed; empty: the link's host is used ([nameShown]). */
        val name: String = "",
        /** Why the text is no link to keep; null for none (or an empty field, which asks nothing yet). */
        val problem: Msg? = null,
        /** The link's host once the text reads as one: the name used where none is typed. */
        val host: String? = null,
        val saving: Boolean = false,
        /** The node made, once saved. */
        val created: String? = null,
        /** The save did not go through ([Msg.BOOKMARK_CREATE_FAILED]), the form kept. */
        val failed: Boolean = false,
    ) {
        /** The name the bookmark gets: the one typed, else the host. */
        val nameShown: String get() = name.trim().ifEmpty { host ?: "" }
        val canSave: Boolean get() = host != null && problem == null && !saving && created == null && nameShown.isNotEmpty()
    }

    private var parsed: ReceivedLink.ShareLink? = link
    private val _state = MutableStateFlow(State(text = link?.link ?: "", password = password ?: "", host = link?.host))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The link as typed or pasted: checked at once. */
    fun link(text: String) {
        val r = ReceivedLink.parse(text)
        parsed = (r as? ReceivedLink.Parsed.Ok)?.link
        _state.update {
            it.copy(
                text = text, host = parsed?.host, failed = false,
                problem = (r as? ReceivedLink.Parsed.Refused)?.problem?.takeIf { p -> p != ReceivedLink.Problem.EMPTY }?.let(::wordOf),
            )
        }
    }

    fun password(text: String) = _state.update { it.copy(password = text.take(BookmarkMeta.MAX_PASSWORD_LENGTH), failed = false) }
    fun name(text: String) = _state.update { it.copy(name = text, failed = false) }

    /** Creates the bookmark as the form holds it; whether it was made. */
    suspend fun save(): Boolean {
        val s = _state.value
        val link = parsed ?: return false
        if (!s.canSave) return false
        _state.update { it.copy(saving = true, failed = false) }
        val r = runCatching { door.create(s.nameShown, link, s.password.ifEmpty { null }) }.getOrNull()
        val made = (r as? NodeCreate.Result.Done)?.created?.nodeId
        _state.update { it.copy(saving = false, created = made, failed = made == null) }
        return made != null
    }

    companion object {
        /** A refusal's words (the bookmark dialog's and *Open a link*'s). */
        fun wordOf(problem: ReceivedLink.Problem): Msg? = when (problem) {
            ReceivedLink.Problem.EMPTY -> null
            ReceivedLink.Problem.TOO_LONG -> Msg.LINK_PROBLEM_TOO_LONG
            ReceivedLink.Problem.NOT_A_URL -> Msg.LINK_PROBLEM_NOT_A_URL
            ReceivedLink.Problem.NOT_HTTPS -> Msg.LINK_PROBLEM_NOT_HTTPS
            ReceivedLink.Problem.CREDENTIALS -> Msg.LINK_PROBLEM_CREDENTIALS
            ReceivedLink.Problem.NOT_A_SHARE -> Msg.LINK_PROBLEM_NOT_A_SHARE
            ReceivedLink.Problem.NO_KEY -> Msg.LINK_PROBLEM_NO_KEY
            ReceivedLink.Problem.MODERATION -> Msg.LINK_PROBLEM_MODERATION
        }

        /** The door that creates the bookmark in [tenant]'s [scopeId] under [parentId] (null: the space's top). */
        fun into(tenant: TenantSession, scopeId: String, parentId: String?): Door = Door { name, link, password ->
            NodeCreate.create(
                tenant, scopeId, parentId, AppKinds.BOOKMARK_KIND, name, AppKinds.tokenInput(AppKinds.BOOKMARK_KIND, name)!!,
                sealMeta = { key, nodeId -> BookmarkMeta.seal(tenant.account.suite, scopeId, nodeId, key, link, password) },
            ).also { if (it is NodeCreate.Result.Done) tenant.forgetTree() }
        }
    }
}
