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
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.session.LinkForm
import me.raddatz.veyq.mobile.session.LinkRow
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * A node's share sheet, for every kind that shares (the web's `FolderShareDialog`, a Pot's with Expenses' words, a drop
 * box's `DropBoxShareDialog`; ADR 0127 decision 2): its guest links listed newest first, each row's actions by
 * [LinkRow]'s rules, and every write on them —
 *
 * - **mint** through the new-link form ([LinkForm], held here as the sheet's state), the link shown once ([State.fresh]);
 *   on a folder "Add files only" makes a drop box with its first link instead (`DropBoxes.create`);
 * - **edit** an own open link in place, through the same form filled with its settings;
 * - **renew** and **delete**, each asked first ([State.renewing], [State.deleting]), a renewed link's new address shown
 *   once;
 * - **switch off** at once (the node below re-keyed) and **switch on** again at its old address.
 *
 * One write runs at a time, its row busy ([State.busy]); after each the links are read again, and what it left to say is
 * [State.notice] (beside the list) or [State.problem] (under the form). A write that closed a link re-keyed what was below
 * it: the door says so to the tenant ([Door.rekeyed]), whose open models read again. Each app draws [state] (or [watch]).
 */
class ShareSheet(
    val node: NodeAt,
    private val door: Door,
    /** The device's clock (epoch ms): today for the form, whether a link is still open. */
    private val now: () -> Long,
    /**
     * The share of a space's top level ([topLevel]): no link roots there, so it offers "Add files only" alone — a drop box
     * at the top with its first link, its form open from the start; nothing listed.
     */
    val atTopLevel: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the sheet reads and writes ([ShellModel.shareSheet] over a tenant, a test over a server it plays). */
    interface Door {
        /** The tenant's link rules (its capability set). */
        val rules: ShareLinks.Rules
        /** The node's links (a drop box's: its WRITE_ONLY ones), newest first; null when they do not list. */
        suspend fun list(): List<ShareLinks.Link>?
        suspend fun mint(role: String, label: String, password: String, expiresAt: String?): ShareLinks.Minted
        /** "Add files only" on a folder: a drop box [name] in it with its first link. */
        suspend fun createDropBox(name: String, password: String, expiresAt: String?): DropBoxes.Created
        suspend fun edit(shareId: String, edit: ShareLinkChanges.Edit): ShareLinkChanges.Changed
        suspend fun renew(shareId: String): ShareLinkChanges.Changed
        suspend fun switchOff(shareId: String): ShareLinks.Closed
        suspend fun reactivate(shareId: String): ShareLinkChanges.Changed
        suspend fun delete(shareId: String): ShareLinkChanges.Changed
        /** A write may have re-keyed what is below the node (a link closed): the tenant's open models read again. */
        fun rekeyed()
        /** A node was made (a drop box): the lists are read again. */
        fun changed()
    }

    data class State(
        /** The links, newest first; null while the first read runs. */
        val links: List<ShareLinks.Link>? = null,
        /** The links did not read. */
        val failed: Boolean = false,
        /** The link a write runs on. */
        val busy: String? = null,
        /** What the last write on a row left to say (beside the list); null where it says nothing. */
        val notice: Notice? = null,
        /** The new-link or edit form while it is open; null shows the list. */
        val form: LinkForm? = null,
        /** The link the form edits; null while it mints a new one. */
        val editing: String? = null,
        /** The form is being sent. */
        val saving: Boolean = false,
        /** Why the form's write did not go through (under the form). */
        val problem: Notice? = null,
        /** A link just minted or renewed, shown once to copy or share; the list shows again once it is seen. */
        val fresh: String? = null,
        /** The link whose renew is asked ([renew] sends, [cancelAsk] does not). */
        val renewing: String? = null,
        /** The link whose delete is asked ([delete] sends, [cancelAsk] does not). */
        val deleting: String? = null,
    )

    private val _state = MutableStateFlow(if (atTopLevel) State(links = emptyList(), form = uploadOnlyForm()) else State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The sheet's words by the node's kind. */
    val words: ShareWords = ShareWords.of(node.kind)

    /** The sheet's title: the node's links, or at a space's top level "Add files only". */
    val title: Msg get() = if (atTopLevel) Msg.SHARE_ROLE_UPLOADER else words.title

    /** The top level's form: a drop box's name, a password and an end, today the core's day. */
    private fun uploadOnlyForm() = LinkForm.start(
        door.rules, editing = null, writeOnly = false, uploadOffered = false, uploadOnly = true,
        today = LocalDays.dayOf(now()), editingEndDay = null,
    )

    /** A drop box's own links: WRITE_ONLY only, never edited or renewed, no name of their own. */
    val dropBox: Boolean = node.kind == DepotFiles.DROPBOX

    /** The tenant's link rules: what the form offers and requires. */
    val rules: ShareLinks.Rules get() = door.rules

    /** Whether "New link" is offered: links are on in this tenant. */
    val canMint: Boolean get() = door.rules.on

    /** Each link as its row shows it, with what this member may do with it. */
    fun rows(state: State = current): List<LinkRow> = state.links.orEmpty().map { LinkRow(it, dropBox, words) }

    /** The row of link [shareId], while it is listed. */
    fun row(shareId: String): LinkRow? = rows().firstOrNull { it.link.shareId == shareId }

    companion object {
        /** The share of space [scopeId]'s top level: "Add files only" alone, its drop box made at the top ([Door.createDropBox]). */
        fun topLevel(scopeId: String, door: Door, now: () -> Long): ShareSheet =
            ShareSheet(NodeAt(scopeId, "", NodeRef.FOLDER, ""), door, now, atTopLevel = true)
    }

    /** The form's button: create, or save an edit. */
    fun submit(state: State = current): Msg = if (state.editing != null) Msg.SHARE_SAVE else Msg.SHARE_CREATE

    /** What the password's switch says under it: the tenant's rule, or the offer (each with the least characters). */
    val passwordHint: Notice get() = Notice(if (door.rules.passwordRequired) Msg.SHARE_PASSWORD_BY_RULE else Msg.SHARE_PASSWORD_HINT, LinkForm.PASSWORD_MIN)

    /** What the end's switch says under it: the tenant's most days, or what an end does. */
    val endHint: Notice get() = door.rules.maxDays?.let { Notice(Msg.SHARE_EXPIRY_BY_RULE, it) } ?: Notice(Msg.SHARE_EXPIRY_HINT)

    /** Reads the links (again); the reads and writes run off the caller's thread. */
    suspend fun load() = withContext(Dispatchers.Default) { reread() }

    private suspend fun reread() {
        if (atTopLevel) return
        val read = runCatching { door.list() }.getOrNull()
        _state.update { if (read == null) it.copy(failed = true) else it.copy(links = read, failed = false) }
    }

    /** "New link": the form opens for a link of this node — on a folder "Add files only" offered where the tenant allows it. */
    fun addLink() {
        val s = current
        if (!canMint || s.busy != null || s.saving) return
        if (atTopLevel) return _state.update { it.copy(form = uploadOnlyForm(), editing = null, problem = null, notice = null, fresh = null) }
        val form = LinkForm.start(
            door.rules, editing = null, writeOnly = dropBox, uploadOffered = node.kind == NodeRef.FOLDER && door.rules.dropBox,
            uploadOnly = false, today = LocalDays.dayOf(now()), editingEndDay = null,
        )
        _state.update { it.copy(form = form, editing = null, problem = null, notice = null) }
    }

    /** "Edit" on link [shareId]: the form opens with its settings (its password left blank stays as it is). */
    fun edit(shareId: String) {
        val s = current
        val row = row(shareId) ?: return
        if (!row.canEdit || s.busy != null || s.saving) return
        val link = row.link
        val form = LinkForm.start(
            door.rules, editing = link, writeOnly = dropBox, uploadOffered = false, uploadOnly = false,
            today = LocalDays.dayOf(now()), editingEndDay = link.expiresAt?.let(LocalDays::dayOf),
        )
        _state.update { it.copy(form = form, editing = shareId, problem = null, notice = null) }
    }

    /** One change of the form as the person made it (`LinkForm.withRole` …); a new form each time. */
    fun form(change: (LinkForm) -> LinkForm) {
        _state.update { s -> s.form?.takeIf { !s.saving }?.let { s.copy(form = change(it)) } ?: s }
    }

    fun role(role: String) = form { it.withRole(role) }
    fun label(label: String) = form { it.withLabel(label) }
    fun passwordOn(on: Boolean) = form { it.withPasswordOn(on) }
    fun password(password: String) = form { it.withPassword(password) }
    fun endOn(on: Boolean) = form { it.withEndOn(on) }
    fun endDay(day: Long) = form { it.withEndDay(day) }

    /** The form closed without sending. */
    fun cancelForm() = _state.update { if (it.saving) it else it.copy(form = null, editing = null, problem = null) }

    /**
     * The form sent: an edit changes its link in place; a new link is minted — on a folder "Add files only" makes a drop
     * box with its first link instead. Once through, the form closes, the new address shows once, and the links are read
     * again; else the form stays with why ([State.problem]). An end is sent as the end of its local day ([LocalDays]).
     */
    suspend fun save(): State = withContext(Dispatchers.Default) {
        val s = current
        val form = s.form ?: return@withContext current
        if (!form.canSave || s.saving) return@withContext current
        _state.update { it.copy(saving = true, problem = null) }
        val expiresAt = form.submittedEndDay?.let(LocalDays::endOf)
        val editing = s.editing
        if (editing != null) {
            val r = runCatching {
                door.edit(editing, ShareLinkChanges.Edit(form.label, form.submittedPassword, expiresAt, form.role))
            }.getOrDefault(ShareLinkChanges.Changed(ShareLinkChanges.Outcome.FAILED))
            if (r.outcome != ShareLinkChanges.Outcome.DONE) return@withContext refused(Notices.linkChanged(r.outcome))
            _state.update { it.copy(saving = false, form = null, editing = null) }
            reread()
            return@withContext current
        }
        val mint = LinkRow.mint(form.role, form.label, onDropBox = dropBox)
        val password = form.submittedPassword.orEmpty()
        if (mint.makesDropBox) {
            val made = runCatching { door.createDropBox(mint.label, password, expiresAt) }.getOrDefault(DropBoxes.Created(DropBoxes.Outcome.FAILED))
            door.changed()
            if (made.outcome != DropBoxes.Outcome.CREATED) return@withContext refused(Notices.dropBoxRefused(made.outcome))
            _state.update { it.copy(saving = false, form = null, fresh = made.url) }
            return@withContext current
        }
        val minted = runCatching { door.mint(mint.role, mint.label, password, expiresAt) }.getOrDefault(ShareLinks.Minted(ShareLinks.Outcome.FAILED))
        if (minted.outcome != ShareLinks.Outcome.CREATED || minted.url == null) return@withContext refused(Notices.linkRefused(minted.outcome))
        _state.update { it.copy(saving = false, form = null, fresh = minted.url) }
        reread()
        current
    }

    private fun refused(why: Notice?): State {
        _state.update { it.copy(saving = false, problem = why ?: Notice(Msg.SHARE_FAILED)) }
        return current
    }

    /** The link shown once was seen ("Done"): the list shows again. */
    fun freshSeen() = _state.update { it.copy(fresh = null) }

    /** "Renew link" on [shareId]: asked first. */
    fun askRenew(shareId: String) {
        if (row(shareId)?.canRenew == true && current.busy == null) _state.update { it.copy(renewing = shareId, deleting = null) }
    }

    /** "Delete link" on [shareId]: asked first. */
    fun askDelete(shareId: String) {
        if (row(shareId)?.canDelete == true && current.busy == null) _state.update { it.copy(deleting = shareId, renewing = null) }
    }

    /** The question declined: nothing is sent. */
    fun cancelAsk() = _state.update { it.copy(renewing = null, deleting = null) }

    /** The renew asked, answered: a new address carrying the link's settings (shown once), the old one closed. */
    suspend fun renew(): State {
        val id = current.renewing ?: return current
        _state.update { it.copy(renewing = null) }
        return write(id, closes = true) {
            val r = door.renew(id)
            if (r.url != null) _state.update { it.copy(fresh = r.url) }
            Notices.linkChanged(r.outcome)
        }
    }

    /** The delete asked, answered: the link gone for good (one still open has what is below it re-keyed). */
    suspend fun delete(): State {
        val id = current.deleting ?: return current
        _state.update { it.copy(deleting = null) }
        return write(id, closes = true) { Notices.linkChanged(door.delete(id).outcome) }
    }

    /** "Revoke" on [shareId], at once (as the web): switched off, what is below it re-keyed so it opens nothing. */
    suspend fun switchOff(shareId: String): State {
        if (row(shareId)?.canSwitchOff != true) return current
        return write(shareId, closes = true) { Notices.linkClosed(door.switchOff(shareId)) }
    }

    /** "Switch on again" on [shareId], at once: its old address opens again. */
    suspend fun reactivate(shareId: String): State {
        if (row(shareId)?.canReactivate != true) return current
        return write(shareId, closes = false) { Notices.linkChanged(door.reactivate(shareId).outcome) }
    }

    /** One write on a row: busy while it runs, what it says after, the links read again; a close says the tenant re-keyed. */
    private suspend fun write(shareId: String, closes: Boolean, act: suspend () -> Notice?): State = withContext(Dispatchers.Default) {
        if (current.busy != null || current.saving) return@withContext current
        _state.update { it.copy(busy = shareId, notice = null) }
        val said = runCatching { act() }.getOrElse { Notice(Msg.SHARE_FAILED) }
        if (closes) door.rekeyed()
        _state.update { it.copy(busy = null, notice = said) }
        reread()
        current
    }
}
