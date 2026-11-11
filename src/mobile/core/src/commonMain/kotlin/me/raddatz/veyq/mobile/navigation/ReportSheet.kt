package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * A node's report to its server's moderators, for every kind (the web's `ReportDialog.vue`, ADR 0093 — a legal duty; ADR 0127
 * decision 2): the form's state and its rules ([NodeReport]) —
 *
 * - what is reported: the node whole or only its name ([State.whole]);
 * - the category, the reason (required, cut to [NodeReport.REASON_MAX]), the good-faith confirmation (required) and an
 *   optional contact for the receipt and the decision (cut to [NodeReport.CONTACT_MAX]);
 * - the privacy notice's passage on that contact ([privacyUrl], GDPR Art. 13), built here from the tenant's origin;
 * - one send at a time, the form held while it runs; then the receipt ([State.sent]) or why not ([State.problem]).
 *
 * Each app draws [state] (or [watch]); a sent report's sheet takes no more edits.
 */
class ReportSheet(
    val node: NodeAt,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the sheet sends ([ShellModel.reportSheet] over a tenant, a test over a server it plays). */
    interface Door {
        /** The tenant's origin, under which its privacy notice is served. */
        val origin: String
        suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent
    }

    data class State(
        /** The node and everything in it (true), or only its name. */
        val whole: Boolean = true,
        /** The category's token ([NodeReport.CATEGORIES]); null until one is chosen. */
        val category: String? = null,
        val reason: String = "",
        val goodFaith: Boolean = false,
        val contact: String = "",
        /** The report is being sent: the form holds still. */
        val sending: Boolean = false,
        /** Why the last send did not go through; null where it says nothing. */
        val problem: Notice? = null,
        /** The receipt once the server took the report; the sheet then only shows it. */
        val sent: NodeReport.Sent? = null,
    ) {
        /** Whether Send is offered: the form complete, nothing under way, nothing sent yet. */
        val canSend: Boolean get() = !sending && sent == null && NodeReport.canSend(category, reason, goodFaith)

        /** What the form says under the chosen category (the note on child sexual abuse material), or nothing. */
        val note: Msg? get() = NodeReport.note(category)

        /** What the scope's choice says under it. */
        val scopeHint: Msg get() = if (whole) Msg.REPORT_SCOPE_WHOLE_HINT else Msg.REPORT_SCOPE_NAME_HINT

        /** The receipt's line: its reference and when the server received it (an instant the platform says in its words). */
        val receipt: Notice? get() = sent?.let { Notice(Msg.REPORT_RECEIPT, it.reference.orEmpty(), it.receivedAt.orEmpty()) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The categories in the web's order, each with its words. */
    val categories: List<NodeReport.Category> get() = NodeReport.CATEGORIES

    /** The privacy notice's passage on a reporter's contact, on this tenant's server. */
    val privacyUrl: String get() = NodeReport.privacyUrl(door.origin)

    /** A change of the form, taken only while it may still change (nothing under way, nothing sent). */
    private fun edit(change: (State) -> State) = _state.update { if (it.sending || it.sent != null) it else change(it) }

    fun whole(on: Boolean) = edit { it.copy(whole = on) }

    /** The category by its token; one the contract does not know is not taken. */
    fun category(token: String) = edit { s -> if (NodeReport.CATEGORIES.any { it.token == token }) s.copy(category = token) else s }

    fun reason(text: String) = edit { it.copy(reason = NodeReport.reason(text)) }

    fun goodFaith(on: Boolean) = edit { it.copy(goodFaith = on) }

    fun contact(text: String) = edit { it.copy(contact = NodeReport.contact(text)) }

    /**
     * Sends the report as the form holds it, once: nothing while it may not be sent ([State.canSend]). The state after it —
     * the receipt, or why not with the form kept for another try. Runs off the caller's thread.
     */
    suspend fun send(): State {
        var taken: State? = null
        _state.update { if (it.canSend) it.copy(sending = true, problem = null).also { s -> taken = s } else it }
        val s = taken ?: return current
        val sent = withContext(Dispatchers.Default) {
            runCatching { door.send(if (s.whole) NodeReport.Scope.WHOLE else NodeReport.Scope.NAME, s.category!!, s.reason, s.contact) }
                .getOrElse { NodeReport.Sent(NodeReport.Outcome.FAILED) }
        }
        _state.update {
            if (sent.outcome == NodeReport.Outcome.SENT) it.copy(sending = false, sent = sent)
            else it.copy(sending = false, problem = Notices.reportRefused(sent.outcome))
        }
        return current
    }
}
