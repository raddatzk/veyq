package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The entry editor of an open Pot (the web's `EntryEditor.vue`, ADR 0129 decision 2): one entry new or edited — its form
 * ([EntryForm], every rule of what may be saved), the refusal said only once a save was tried, the preview in the base
 * currency, another currency's rate filled from the day's table, the receipts picked and held until the save, the save
 * with what it came to (a stale save reads the other writer's version into the form and says so; the receipts a full
 * storage left out are named), and the delete, done only once asked. Every app draws [state] (or [watch]) and sends the
 * person's edits as [change]; what a host must act on at once is what [save] and [delete] answer.
 *
 * Opened by [PotModel.editor]; a test opens it over a [Door] it plays.
 */
class EntryEditor internal constructor(
    private val door: Door,
    /** The record this editor writes: the opened entry's, or one minted at open for a new entry (its receipts are
     *  uploaded against it before its first revision, so a save tried again names the same record). */
    private val recordId: String,
    private val isNew: Boolean,
    private var basedOn: Long?,
    /** The reader's decimal separator, as amounts are typed (the platform's locale data). */
    private val separator: Char,
    first: State,
    pending: List<PotSession.PendingReceipt>,
    parent: CoroutineScope,
    /** Where a save, a delete and the rate's request run: off the caller's thread, which may be a UI's. */
    private val work: CoroutineDispatcher,
) {
    /** What the editor reads and writes: the open Pot ([PotModel.editor]), or a test's. */
    interface Door {
        /** The Pot as read now; null once none is open. */
        fun view(): PotView?

        /** Today, the device's local day (`yyyy-MM-dd`): the core's clock, never a platform's. */
        fun today(): String

        /** A fresh id, for a new entry's record and each receipt picked. */
        fun newId(): String

        /** One unit of [currency] in the Pot's base from [day]'s table, or null: the rate is typed then. */
        suspend fun rate(currency: String, day: String): ExchangeRates.Filled?

        /** [PotSession.saveEntry] of [form] as record [entryId] on [basedOn] (null: its first revision). */
        suspend fun save(entryId: String, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, own: Set<String>): Written

        suspend fun delete(entryId: String, basedOn: Long): PotSession.WriteOutcome
    }

    /** What a save came to: its outcome, the receipts it left out for want of room and those it uploaded. */
    class Written(val outcome: PotSession.WriteOutcome, val skipped: List<String> = emptyList(), val uploaded: List<String> = emptyList())

    /** A file a person picked as a receipt: its bytes (null: it did not read, or was larger than [Receipts.READ_LIMIT]). */
    class Picked(internal val bytes: ByteArray?, val type: String, val name: String?, val modifiedAt: Long? = null)

    /** A receipt picked and not uploaded yet: its id and the name it is listed under. */
    data class Held(val id: String, val name: String)

    /** Where the rate's fill is: asked of the day's table, filled from the rows of [Rate.day], or not in the table. */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("RateState")
    enum class RateState { FILLING, FILLED, MISSING }

    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("EditorRate")
    data class Rate(val state: RateState, val day: String? = null)

    data class State(
        /** A new entry (no history, no delete). */
        val isNew: Boolean,
        val form: EntryForm,
        /** The people the editor offers, in join order (an edited entry's also the inactive ones it names). */
        val people: List<PotView.Person>,
        /** False for a reader: the same entry, read only (rights only remove functions, inbox #401). */
        val editable: Boolean,
        /** The entry does not add up (`Ledger.Status.INVALID`): said above it. */
        val invalid: Boolean = false,
        /** A save was tried: the refusal is said from then on. */
        val tried: Boolean = false,
        /** Why the form cannot be saved, at its field — only once [tried]. */
        val refusal: EntryForm.Refusal? = null,
        /** The shares in the base currency as the save would seal them; empty for a transfer or a form that forms no entry. */
        val preview: List<PotView.ShareRow> = emptyList(),
        /** The rate's fill; null: the hint (a rate typed, or the base currency). */
        val rate: Rate? = null,
        /** Receipts picked, uploaded only when the entry is saved. The kept ones are [EntryForm.receipts]. */
        val pending: List<Held> = emptyList(),
        /** Whether another receipt may be picked (the entry holds fewer than [Receipts.MAX_PER_ENTRY]). */
        val canAddReceipt: Boolean = false,
        /** The last pick had a file that is no receipt, too large, or one too many. */
        val refusedPick: Boolean = false,
        val saving: Boolean = false,
        /** Why the last save or delete did not land, or that it was stale and the form shows the other version now. */
        val notice: Notice? = null,
        /** The delete is asked: it is done only on [delete]. */
        val asking: Boolean = false,
        /** Saved or deleted: the editor has nothing more to do. */
        val done: Boolean = false,
    )

    /** What [save] answers: whether the editor is done (the host closes it at once), and what to say. */
    class Saved(val done: Boolean, val notice: Notice?)

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var pendingFiles: List<PotSession.PendingReceipt> = pending
    /** Receipts an earlier save of this editor uploaded: never uploaded twice, answered for by the drop rule. */
    private val own = mutableSetOf<String>()
    /** Which rate request is the one asked last: an answer to an earlier one is dropped. */
    private var rateAsk = 0

    private val _state = MutableStateFlow(shown(first))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        fillRate()
    }

    /** [s] with what follows from its form: the refusal (once tried), the preview, the receipts' room. */
    private fun shown(s: State): State {
        val v = door.view()
        val held = s.form.receipts.size + pendingFiles.size
        return s.copy(
            refusal = if (s.tried) v?.check(s.form) else null,
            preview = v?.preview(s.form).orEmpty(),
            pending = pendingFiles.map { Held(it.id, Receipts.downloadName(it.name, it.type)) },
            canAddReceipt = s.editable && held < Receipts.MAX_PER_ENTRY,
        )
    }

    private fun set(change: (State) -> State) = _state.update { shown(change(it)) }

    /** One edit of the form; a reader's are ignored. Typed text is kept as typed. */
    fun change(change: EditorChange) {
        val s = _state.value
        if (!s.editable || s.done) return
        val ids = s.people.map { it.id }
        val f = s.form
        val next = when (change) {
            is EditorChange.Kind -> f.withKind(change.kind, separator)
            is EditorChange.Day -> f.copy(date = change.iso)
            is EditorChange.Title -> f.copy(title = change.text)
            is EditorChange.Category -> f.copy(category = change.id)
            is EditorChange.Currency -> {
                val v = door.view() ?: return
                val base = v.currency ?: return
                f.withCurrency(change.code, Currencies.minorUnits(change.code), Ledger.BaseCurrency(base, v.minorUnits))
            }
            is EditorChange.RateTyped -> f.copy(rate = change.text)
            is EditorChange.Amount -> f.copy(amount = change.text)
            is EditorChange.From -> f.copy(from = change.person)
            is EditorChange.To -> f.copy(to = change.person)
            is EditorChange.AddPayer -> f.nextPayer(ids)?.let { f.copy(payers = f.payers + EntryForm.Payer(it, "")) } ?: return
            is EditorChange.RemovePayer -> if (f.canRemovePayer && change.index in f.payers.indices) f.copy(payers = f.payers.filterIndexed { i, _ -> i != change.index }) else return
            is EditorChange.Payer -> f.copy(payers = f.payers.mapIndexed { i, p -> if (i == change.index) p.copy(participant = change.person) else p })
            is EditorChange.Paid -> f.copy(payers = f.payers.mapIndexed { i, p -> if (i == change.index) p.copy(amount = change.text) else p })
            is EditorChange.AddItem -> f.copy(items = f.items + EntryForm.emptyItem(ids))
            is EditorChange.RemoveItem -> f.copy(items = f.items.filterIndexed { i, _ -> i != change.index })
            is EditorChange.ItemLabel -> f.copy(items = f.items.mapIndexed { i, it -> if (i == change.index) it.copy(label = change.text) else it })
            is EditorChange.ItemAmount -> f.copy(items = f.items.mapIndexed { i, it -> if (i == change.index) it.copy(amount = change.text) else it })
            is EditorChange.SplitMode -> f.splitAt(change.item)?.let { f.withSplit(change.item, it.copy(mode = change.mode)) } ?: return
            is EditorChange.Toggle -> f.splitAt(change.item)?.let { f.withSplit(change.item, EntryForm.toggled(it, change.person, change.on, ids)) } ?: return
            is EditorChange.Share -> f.splitAt(change.item)?.let { f.withSplit(change.item, EntryForm.typed(it, change.person, change.text)) } ?: return
            is EditorChange.Equally -> f.splitAt(change.item)?.let { f.withSplit(change.item, EntryForm.everyoneEqually(it, ids)) } ?: return
            is EditorChange.Expand -> f.splitAt(change.item)?.let { f.withSplit(change.item, it.copy(expanded = true)) } ?: return
        }
        set { it.copy(form = next) }
        if (change is EditorChange.Currency && next.currency != f.currency) fillRate()
    }

    /**
     * Another currency with no rate typed: its rate is asked of the day's table (one request a device and day, at most) and
     * filled where nothing was typed meanwhile; an answer for a currency changed since is dropped.
     */
    private fun fillRate() {
        val ask = ++rateAsk
        val v = door.view()
        val code = _state.value.form.currency
        if (v == null || !ExchangeRates.wanted(v.ratesOffered, v.currency, code, _state.value.form.rate)) {
            set { it.copy(rate = null) }
            return
        }
        set { it.copy(rate = Rate(RateState.FILLING)) }
        scope.launch {
            val filled = runCatching { withContext(work) { door.rate(code, door.today()) } }.getOrNull()
            set { s ->
                if (ask != rateAsk) return@set s
                val fill = ExchangeRates.fill(filled, s.form.rate, separator)
                s.copy(
                    form = fill.rate?.let { s.form.copy(rate = it) } ?: s.form,
                    rate = fill.filledOn?.let { Rate(RateState.FILLED, it) } ?: if (fill.missing) Rate(RateState.MISSING) else null,
                )
            }
        }
    }

    /**
     * Files picked as receipts: each one that is an image or a PDF of at most [Receipts.MAX_BYTES], while the entry holds
     * fewer than [Receipts.MAX_PER_ENTRY], is held for the save under an id minted here; any other makes [State.refusedPick].
     */
    fun addReceipts(picked: List<Picked>) {
        val s = _state.value
        if (!s.editable || s.done) return
        val v = door.view() ?: return
        var held = s.form.receipts.size + pendingFiles.size
        var refused = false
        val added = ArrayList<PotSession.PendingReceipt>()
        for (p in picked) {
            val bytes = p.bytes
            if (bytes == null || !v.receiptAllowed(bytes.size.toLong(), p.type, p.name, held)) {
                refused = true
                continue
            }
            added += PotSession.PendingReceipt(door.newId(), bytes, p.type, p.name, p.modifiedAt)
            held++
        }
        pendingFiles = pendingFiles + added
        set { it.copy(refusedPick = refused) }
    }

    /** A receipt taken off: a picked one is forgotten, a kept one dropped by the save. */
    fun dropReceipt(id: String) {
        val s = _state.value
        if (!s.editable || s.done) return
        pendingFiles = pendingFiles.filter { it.id != id }
        set { it.copy(form = it.form.copy(receipts = it.form.receipts - id)) }
    }

    /**
     * Saves the form: a form that cannot be saved is only marked tried (its refusal shows at its field). A stale save reads
     * the other writer's version into the form, keeping the receipts this editor added, and says so; the next save goes on
     * top. What it answers is what the host acts on at once: done (close it, saying the receipts left out), or not.
     */
    suspend fun save(): Saved {
        val s = _state.value
        if (!s.editable || s.saving || s.done) return Saved(s.done, null)
        val v = door.view() ?: return Saved(false, null)
        set { it.copy(tried = true) }
        if (v.check(s.form) != null) return Saved(false, null)
        set { it.copy(saving = true, notice = null, asking = false) }
        val sent = s.form
        val files = pendingFiles
        val written = runCatching { withContext(work) { door.save(recordId, sent, basedOn, files, own.toSet()) } }
            .getOrDefault(Written(PotSession.WriteOutcome.REFUSED))
        own += written.uploaded
        pendingFiles = pendingFiles.filter { it.id !in written.uploaded }
        return when (written.outcome) {
            PotSession.WriteOutcome.WRITTEN -> {
                val notice = Notices.receiptsSkipped(written.skipped)
                set { it.copy(saving = false, done = true, notice = notice) }
                Saved(true, notice)
            }
            PotSession.WriteOutcome.STALE -> Saved(false, stale(written.uploaded))
            else -> {
                val notice = Notice(Notices.entryRefused(written.outcome.name))
                set { it.copy(form = it.form.copy(receipts = it.form.receipts + written.uploaded.filter { id -> id !in it.form.receipts }), saving = false, notice = notice) }
                Saved(false, notice)
            }
        }
    }

    /** The other writer's version in the form (the session read it again), with this editor's own receipts carried. */
    private fun stale(uploaded: List<String>): Notice {
        val notice = Notice(Msg.ED_STALE)
        val e = door.view()?.editing(recordId, separator, door.today())
        set { s ->
            val carried = s.form.receipts.filter { it in own } + uploaded
            if (e == null) {
                s.copy(form = s.form.copy(receipts = (s.form.receipts + uploaded).distinct()), saving = false, notice = notice)
            } else {
                basedOn = e.basedOn
                s.copy(
                    form = e.form.copy(receipts = (e.form.receipts + carried).distinct()), people = e.people,
                    invalid = e.status == Ledger.Status.INVALID, saving = false, notice = notice,
                )
            }
        }
        return notice
    }

    /** Asks for the delete of the entry edited; nothing is written yet. */
    fun askDelete() {
        val s = _state.value
        if (s.isNew || !s.editable || s.saving || s.done) return
        set { it.copy(asking = true) }
    }

    fun cancelAsk() = set { it.copy(asking = false) }

    /** Deletes the entry, once [askDelete] asked; whether it was (the host closes the editor then). */
    suspend fun delete(): Boolean {
        val s = _state.value
        val on = basedOn
        if (!s.asking || s.saving || s.done || on == null) return false
        set { it.copy(asking = false, saving = true, notice = null) }
        val outcome = runCatching { withContext(work) { door.delete(recordId, on) } }.getOrDefault(PotSession.WriteOutcome.REFUSED)
        return when (outcome) {
            PotSession.WriteOutcome.WRITTEN -> {
                set { it.copy(saving = false, done = true) }
                true
            }
            PotSession.WriteOutcome.STALE -> {
                stale(emptyList())
                false
            }
            else -> {
                set { it.copy(saving = false, notice = Notice(Notices.entryRefused(outcome.name))) }
                false
            }
        }
    }

    /** The editor is gone: a rate still being asked is forgotten. */
    fun close() {
        scope.cancel()
    }

    internal companion object {
        /**
         * The editor of entry [entryId] (null: a new one, dated the door's today and paid by the first person offered), or
         * null when the Pot is not read, has no base currency yet, or holds no such live entry.
         */
        fun open(
            door: Door, entryId: String?, separator: Char, pending: List<PotSession.PendingReceipt>,
            parent: CoroutineScope, work: CoroutineDispatcher,
        ): EntryEditor? {
            val v = door.view() ?: return null
            val today = door.today()
            return if (entryId == null) {
                val form = v.blankForm(today, separator) ?: return null
                EntryEditor(door, door.newId(), true, null, separator, State(true, form, v.people, v.canWrite), pending, parent, work)
            } else {
                val e = v.editing(entryId, separator, today) ?: return null
                val first = State(false, e.form, e.people, v.canWrite, invalid = e.status == Ledger.Status.INVALID)
                EntryEditor(door, entryId, false, e.basedOn, separator, first, pending, parent, work)
            }
        }
    }
}

private fun EntryForm.splitAt(item: Int?): EntryForm.FormSplit? = if (item == null) rest else items.getOrNull(item)?.split

private fun EntryForm.withSplit(item: Int?, split: EntryForm.FormSplit): EntryForm =
    if (item == null) copy(rest = split) else copy(items = items.mapIndexed { i, it -> if (i == item) it.copy(split = split) else it })

/**
 * One edit of the entry editor's form ([EntryEditor.change]). A split's edit names its [item]'s index, or null for the
 * rest's split (the whole amount's while there are no items). Serializable, since the native bridge carries it as it is
 * (`Editor.Change`); each variant's serial name is `Edit.<name>`, so a host's generated type never takes a common name.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("EditorChange")
sealed class EditorChange {
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Kind") data class Kind(val kind: EntryForm.Kind) : EditorChange()
    /** The date picked, `yyyy-MM-dd`. */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Day") data class Day(val iso: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Title") data class Title(val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Category") data class Category(val id: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Currency") data class Currency(val code: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.RateTyped") data class RateTyped(val text: String) : EditorChange()
    /** A transfer's amount. */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Amount") data class Amount(val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.From") data class From(val person: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.To") data class To(val person: String) : EditorChange()
    /** The next person not paying yet. */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.AddPayer") data object AddPayer : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.RemovePayer") data class RemovePayer(val index: Int) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Payer") data class Payer(val index: Int, val person: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Paid") data class Paid(val index: Int, val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.AddItem") data object AddItem : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.RemoveItem") data class RemoveItem(val index: Int) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.ItemLabel") data class ItemLabel(val index: Int, val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.ItemAmount") data class ItemAmount(val index: Int, val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.SplitMode") data class SplitMode(val item: Int?, val mode: EntryForm.Mode) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Toggle") data class Toggle(val item: Int?, val person: String, val on: Boolean) : EditorChange()
    /** A person's value typed in the split's mode. */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Share") data class Share(val item: Int?, val person: String, val text: String) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Equally") data class Equally(val item: Int?) : EditorChange()
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("Edit.Expand") data class Expand(val item: Int?) : EditorChange()
}
