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
 * A node's report to its server's moderators, for every kind (the web's `ReportDialog.vue`, ADR 0093 — a legal duty; ADR 0136
 * decision 2): the form's state and its rules ([NodeReport]) —
 *
 * - what is reported: the node whole or only its name ([State.whole]);
 * - the category, the reason (required, cut to [NodeReport.REASON_MAX]), the good-faith confirmation (required) and an
 *   optional contact for the receipt and the decision (cut to [NodeReport.CONTACT_MAX]);
 * - the privacy notice's passage on that contact ([privacyUrl], GDPR Art. 13), built here from the tenant's origin;
 * - one send at a time, the form held while it runs; then the receipt ([State.sent]) or why not ([State.problem]).
 *
 * Several [nodes] (a level's selection, the web's `reportNodes`) are one report of each whole: the sheet says how many
 * ([items]), offers no name-only choice, and above [NodeReport.MAX_ITEMS] sends nothing but offers the folder that holds
 * them instead ([tooMany], [useEnclosing]), as the web's dialog does.
 *
 * Each app draws [state] (or [watch]); a sent report's sheet takes no more edits.
 */
class ReportSheet(
    val node: NodeAt,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Whether "only its name" may be offered beside the whole: a node's report offers it; an item's (a Memory's music track,
     * ADR 0142 decision 11) has no name of its own to report apart, so the sheet shows no choice and sends it whole.
     */
    offersName: Boolean = true,
    /**
     * Only the name is reported, never the whole (a kept link, ADR 0120 decision 7, ADR 0150 decision 6): the node's key
     * would hand this instance's moderators the key of a link to another operator's content, which the backend refuses.
     */
    val nameOnly: Boolean = false,
    /** What is reported: [node] alone, or a level's selection (its first [node]). */
    val nodes: List<NodeAt> = listOf(node),
    /** The folder on screen, offered instead above [NodeReport.MAX_ITEMS] (null: a space's top, nothing to offer). */
    private val enclosing: NodeAt? = null,
) {
    /** The selection's sheet: [nodes] (never empty), [enclosing] the folder on screen. */
    constructor(nodes: List<NodeAt>, enclosing: NodeAt?, door: Door) :
        this(nodes.first(), door, CoroutineScope(SupervisorJob() + Dispatchers.Default), offersName = nodes.size == 1, nameOnly = false, nodes = nodes, enclosing = enclosing)

    /**
     * Whether the sheet offers a choice of "it and everything in it" or "only its name" — what every platform draws the
     * scope's choice by. None for an item (sent whole) and none for a [nameOnly] sheet: a kept link has no whole to offer,
     * so its sheet reports the name without asking (offering the whole would offer what the backend refuses).
     */
    val offersName: Boolean = offersName && !nameOnly && nodes.size == 1

    /** What the sheet sends (`ShellModel.reportSheet` over a tenant, a test over a server it plays). */
    interface Door {
        /** The tenant's origin, under which its privacy notice is served. */
        val origin: String
        suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent
        /** Several nodes, each whole, as one report (a selection's, or the folder holding it): only a level's door sends them. */
        suspend fun sendAll(nodes: List<NodeAt>, category: String, reason: String, contact: String): NodeReport.Sent =
            NodeReport.Sent(NodeReport.Outcome.FAILED)
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
        /** How many items are reported: the selection's, or 1 once the folder holding them was chosen instead. */
        val count: Int = 1,
        /** The folder holding the selection was chosen instead ([useEnclosing]). */
        val enclosing: Boolean = false,
    ) {
        /** More than one report holds: nothing is sent; the folder holding them may be ([offersEnclosing]). */
        val tooMany: Boolean get() = count > NodeReport.MAX_ITEMS

        /** Whether Send is offered: the form complete, not too many, nothing under way, nothing sent yet. */
        val canSend: Boolean get() = !tooMany && !sending && sent == null && NodeReport.canSend(category, reason, goodFaith)

        /** "You are reporting n items." (the web's `report.items`). */
        val items: Notice get() = Notice(Msg.REPORT_ITEMS, count)

        /** Why nothing can be sent with this many (the web's `report.tooMany`), or null. */
        val tooManyNotice: Notice? get() = if (tooMany) Notice(Msg.REPORT_TOO_MANY, count) else null

        /** What the form says under the chosen category (the note on child sexual abuse material), or nothing. */
        val note: Msg? get() = NodeReport.note(category)

        /** What the scope's choice says under it. */
        val scopeHint: Msg get() = if (whole) Msg.REPORT_SCOPE_WHOLE_HINT else Msg.REPORT_SCOPE_NAME_HINT

        /** The receipt's line: its reference and when the server received it (an instant the platform says in its words). */
        val receipt: Notice? get() = sent?.let { Notice(Msg.REPORT_RECEIPT, it.reference.orEmpty(), it.receivedAt.orEmpty()) }
    }

    private val _state = MutableStateFlow(State(whole = !nameOnly, count = nodes.size))

    /** Whether the folder holding a selection above [NodeReport.MAX_ITEMS] is offered instead (the web's `reportEnclosing`). */
    fun offersEnclosing(state: State): Boolean = state.tooMany && enclosing != null

    /** Its words. */
    val enclosingWord: Msg get() = Msg.REPORT_ENCLOSING

    /** The folder holding the selection is reported instead, whole. */
    fun useEnclosing() = edit { if (offersEnclosing(it)) it.copy(enclosing = true, count = 1, whole = true) else it }
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

    fun whole(on: Boolean) = edit { if (offersName) it.copy(whole = on) else it }

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
            runCatching {
                val chosen = if (s.enclosing) listOfNotNull(enclosing) else nodes
                if (chosen.size == 1 && chosen.single() == node) {
                    door.send(if (s.whole && !nameOnly) NodeReport.Scope.WHOLE else NodeReport.Scope.NAME, s.category!!, s.reason, s.contact)
                } else {
                    door.sendAll(chosen, s.category!!, s.reason, s.contact)
                }
            }
                .getOrElse { NodeReport.Sent(NodeReport.Outcome.FAILED) }
        }
        _state.update {
            if (sent.outcome == NodeReport.Outcome.SENT) it.copy(sending = false, sent = sent)
            else it.copy(sending = false, problem = Notices.reportRefused(sent.outcome))
        }
        return current
    }
}
