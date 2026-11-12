package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * The Pot open in the app (ADR 0127 decision 2): its session and what its screens show ([PotView]) — loading, failed,
 * saving a write and that write's refusal —, its live poll while a screen runs it (skipped while a write is saving), a
 * pull to refresh, the writes, and a new Pot's creation. Both apps observe [state] (or [watch]); what they draw is theirs.
 */
class PotModel internal constructor(
    private val tenant: TenantSession?,
    private val now: () -> Long,
    private val opener: suspend (ObjectRef) -> Opened?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(tenant: TenantSession, now: () -> Long) : this(tenant, now, { ref -> PotSession.open(tenant, ref.scopeId, ref.nodeId)?.let(::SessionOpened) })

    /**
     * A Pot the caller plays ([Opened]), for a test outside the core (the bridge's dispatch): no tenant, so no re-key,
     * copy or create; [now] is its clock.
     */
    constructor(now: () -> Long, opener: suspend (ObjectRef) -> Opened?) : this(null, now, opener)

    /** What the model asks of an open Pot: its session's ([SessionOpened]), or a test's. */
    interface Opened {
        /** The session, where a real Pot is open. */
        val session: PotSession?
        fun view(): PotView
        /** One tick of the live poll ([PotSession.poll]): whether anything moved, null when skipped. */
        suspend fun poll(): Boolean?
        /** Read again now, the receipts too. */
        suspend fun refresh()
        suspend fun rekeyed()
        /** A fresh record id ([PotSession.newId]). */
        fun newId(): String
        /** One write of the Pot's screens ([Op]) on its session. */
        suspend fun write(op: Op): PotSession.WriteOutcome

        /**
         * The entry editor's save ([PotSession.saveEntry] of [form] as record [entryId] on [basedOn]): what it came to, the
         * receipts it left out and those it uploaded. A played Pot that does not play the editor refuses.
         */
        suspend fun saveEntry(entryId: String, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, own: Set<String>): EntryEditor.Written =
            EntryEditor.Written(PotSession.WriteOutcome.REFUSED)

        /** The entry editor's delete ([PotSession.deleteEntry]); a played Pot that does not play it refuses. */
        suspend fun deleteEntry(entryId: String, basedOn: Long): PotSession.WriteOutcome = PotSession.WriteOutcome.REFUSED

        /** One unit of [currency] in the base from [day]'s table ([PotSession.rateFor]), or null: the rate is typed then. */
        suspend fun rateFor(currency: String, day: String): ExchangeRates.Filled? = null

        /** A receipt's bytes ([PotSession.openReceipt]), or null: blocked, not held, or it did not read. */
        suspend fun openReceipt(receiptId: String): ByteArray? = null
    }

    /**
     * A write the Pot's screens make, besides the entry editor's: a settle-up recorded, a deleted entry restored, the
     * settings and the people ([PotSettings]). One type, so a test plays every write the screens send.
     */
    sealed class Op {
        data class Record(val rows: List<PotView.SettleRow>, val date: String) : Op()
        data class Restore(val entryId: String, val basedOn: Long) : Op()
        data class BaseCurrency(val code: String, val minorUnits: Int) : Op()
        data class AddCategory(val name: String) : Op()
        data class RenameCategory(val id: String, val name: String) : Op()
        data class AddPerson(val name: String) : Op()
        data class AddMember(val userId: String) : Op()
        data class Rename(val personId: String, val name: String) : Op()
        data class SetActive(val personId: String, val active: Boolean) : Op()
        data class Erase(val personId: String) : Op()
    }

    internal class SessionOpened(override val session: PotSession) : Opened {
        override fun view() = PotView(session)
        override suspend fun poll() = session.poll()
        override suspend fun refresh() {
            session.refresh()
            session.readReceipts()
        }
        override suspend fun rekeyed() {
            session.rekeyed()
        }
        override fun newId() = session.newId()
        override suspend fun write(op: Op): PotSession.WriteOutcome = when (op) {
            is Op.Record -> session.recordPayments(op.rows, op.date)
            is Op.Restore -> session.restoreEntry(op.entryId, op.basedOn)
            is Op.BaseCurrency -> session.setBaseCurrency(op.code, op.minorUnits)
            is Op.AddCategory -> session.addCategory(op.name)
            is Op.RenameCategory -> session.renameCategory(op.id, op.name)
            is Op.AddPerson -> session.addPerson(op.name)
            is Op.AddMember -> session.addMember(op.userId)
            is Op.Rename -> session.rename(op.personId, op.name)
            is Op.SetActive -> session.setActive(op.personId, op.active)
            is Op.Erase -> session.erase(op.personId)
        }
        override suspend fun saveEntry(entryId: String, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, own: Set<String>): EntryEditor.Written {
            val outcome = try {
                session.saveEntry(entryId, form, basedOn, pending, own)
            } catch (e: Exception) {
                // a save that failed half-way: what it uploaded is still the editor's, so one tried again never uploads it twice
                PotSession.WriteOutcome.REFUSED
            }
            return EntryEditor.Written(outcome, session.skippedReceipts, session.uploadedReceipts)
        }
        override suspend fun deleteEntry(entryId: String, basedOn: Long) = session.deleteEntry(entryId, basedOn)
        override suspend fun rateFor(currency: String, day: String) = session.rateFor(currency, day)
        override suspend fun openReceipt(receiptId: String) = session.openReceipt(receiptId)
    }

    /**
     * The open Pot's views: [view] null while it loads, [failed] when it did not open; [message] the last write's refusal.
     * Serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge).
     */
    @kotlinx.serialization.Serializable
    class State(val ref: ObjectRef, val view: PotView? = null, val failed: Boolean = false, val saving: Boolean = false, val message: String? = null) {
        internal fun with(view: PotView? = this.view, failed: Boolean = this.failed, saving: Boolean = this.saving, message: String? = this.message) =
            State(ref, view, failed, saving, message)
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The state now, for a platform that reads it at once after an action. */
    val current: State? get() = _state.value

    private var opened: Opened? = null

    /** The open Pot's session, while one is open. */
    val session: PotSession? get() = opened?.session

    private var poll: LivePoll? = null

    /** A watch for a platform without flows (the state, or a live poll): stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with each state (null: no Pot open) on a background thread. */
    fun watch(listener: (State?) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** While a Pot is open: its re-read after each re-key of the tenant ([TenantSession.rekeyed]). */
    private var rekeys: kotlinx.coroutines.Job? = null

    /**
     * Opens and reads Pot [ref] unless it is the one open (and did open). While it is open it is read anew under its key
     * after each re-key the tenant ran ([rekeyed]): a link on it or above it closed, a scope's re-key finished in the
     * background — no app asks for that.
     */
    suspend fun open(ref: ObjectRef) {
        _state.value?.let { if (it.ref == ref && !it.failed) return }
        _state.value = State(ref)
        val o = runCatching { opener(ref) }.getOrNull()
        if (_state.value?.ref != ref) return
        opened = o
        poll = LivePoll(now = now)
        _state.value = State(ref, o?.view(), failed = o == null)
        rekeys?.cancel()
        rekeys = tenant?.let { t -> scope.launch { t.rekeyed.drop(1).collect { rekeyed() } } }
    }

    /** The Pot is gone (trashed, its space left): nothing is open. */
    fun close(ref: ObjectRef? = null) {
        if (ref != null && _state.value?.ref != ref) return
        rekeys?.cancel()
        rekeys = null
        _state.value = null
        opened = null
        poll = null
    }

    /**
     * The open Pot's live poll (core's `LivePoll`) for as long as the caller runs it — a Pot screen while it is shown, so
     * nothing is asked in the background; skipped while a write shows saving; the view read anew when a tick moved it.
     * Started while the Pot is still opening, it waits for the read (a desktop watches the poll as its page appears, before
     * the open's answer); it ends when that Pot closes or another opens in this model, so a poll never reads a Pot no
     * screen shows.
     */
    suspend fun live() {
        val read = _state.first { it != null && (it.view != null || it.failed) } ?: return
        val o = opened ?: return
        val cadence = poll ?: return
        coroutineScope {
            val ticking = launch {
                cadence.run {
                    when (if (_state.value?.saving != false) null else o.poll()) {
                        null -> LivePoll.Tick.SKIP
                        true -> LivePoll.Tick.DONE.also { if (opened === o) _state.update { it?.with(view = o.view()) } }
                        false -> LivePoll.Tick.DONE
                    }
                }
            }
            _state.first { it?.ref != read.ref || opened !== o }
            ticking.cancel()
        }
    }

    /** [live] in the model's own scope, for a platform that cannot run a suspend loop: stopped with [Watch.stop]. */
    fun startLive(): Watch = Watch(scope.launch { live() })

    /** Reads the open Pot again now (pull to refresh), its receipts too. */
    suspend fun refresh() {
        val o = opened ?: return
        runCatching { o.refresh() }
        if (opened === o) _state.update { it?.with(view = o.view()) }
    }

    /**
     * The open Pot read anew under its key, after a re-key that may have reached it (a link on it or above it closed, a
     * scope's re-key finished in the background); a Pot the re-key did not reach reads the same.
     */
    suspend fun rekeyed() {
        val o = opened ?: return
        runCatching { o.rekeyed() }
        if (opened === o) _state.update { it?.with(view = o.view()) }
    }

    /** The write ended: the Pot as read after it, and — where [said] — a refusal as the message. */
    private fun ended(outcome: PotSession.WriteOutcome, said: Boolean): Boolean {
        val o = opened
        _state.update {
            it?.with(view = o?.view() ?: it.view, saving = false, message = if (said) outcome.takeIf { x -> x != PotSession.WriteOutcome.WRITTEN }?.name else it.message)
        }
        return outcome == PotSession.WriteOutcome.WRITTEN
    }

    /** One [Op] on the open Pot, its refusal the Pot's message where [said]; what it came to. */
    internal suspend fun written(op: Op, said: Boolean): PotSession.WriteOutcome {
        val o = opened ?: return PotSession.WriteOutcome.REFUSED
        var outcome = PotSession.WriteOutcome.REFUSED
        writing(said) { o.write(op).also { outcome = it } }
        return outcome
    }

    private suspend fun write(op: Op): Boolean = written(op, said = true) == PotSession.WriteOutcome.WRITTEN

    /**
     * Records settle-up [rows] as paid (one row, or all of [PotView.settleUp]), dated the core's today: one transfer each,
     * in one batch; its refusal the Pot's message. Whether it was stored.
     */
    suspend fun record(rows: List<PotView.SettleRow>): Boolean {
        if (rows.isEmpty() || _state.value?.view?.canWrite != true) return false
        return write(Op.Record(rows, today()))
    }

    /** Whether deleted entry [entryId] is offered a restore now, by the core's clock ([PotView.restorable]). */
    fun restorable(entryId: String): Boolean = _state.value?.view?.restorable(entryId, now()) == true

    /**
     * Restores deleted entry [entryId] on its latest revision while it is [restorable] by the core's clock; outside its
     * window nothing is sent and the message says it can no longer be restored ([NOT_RESTORABLE]). Whether it was stored.
     */
    suspend fun restore(entryId: String): Boolean {
        val view = _state.value?.view ?: return false
        if (!view.restorable(entryId, now())) {
            _state.update { it?.with(message = NOT_RESTORABLE) }
            return false
        }
        return write(Op.Restore(entryId, view.latestRevision(entryId)))
    }

    /** The open Pot's settings and people ([PotSettings]); null while none is read. */
    fun settings(): PotSettings? = _state.value?.view?.let { PotSettings(this, scope) }

    /** The open Pot's view now, for its screen models. */
    internal fun view(): PotView? = _state.value?.view

    /**
     * [op] as a write of the open Pot: saving while it runs (the live poll skips meanwhile), the Pot read after it; its
     * refusal the Pot's message where [said] (a sheet that says its own, the entry editor, passes false).
     */
    internal suspend fun writing(said: Boolean, op: suspend () -> PotSession.WriteOutcome): Boolean {
        if (opened == null) return false
        _state.update { it?.with(saving = true, message = if (said) null else it.message) }
        return ended(runCatching { op() }.getOrDefault(PotSession.WriteOutcome.REFUSED), said)
    }

    /**
     * The entry editor of entry [entryId] of the open Pot (null: a new entry, dated the core's today), the reader's
     * decimal [separator] handed for typed amounts, [pending] receipts it starts with (what another app shared, ADR 0129);
     * null when no Pot is read, it has no base currency yet, or holds no such entry. Its saves and deletes are this
     * model's writes (saving, the live poll skipped); it says their outcome itself.
     */
    fun editor(entryId: String?, separator: Char, pending: List<PotSession.PendingReceipt> = emptyList()): EntryEditor? =
        EntryEditor.open(EditorDoor(), entryId, separator, pending, scope, Dispatchers.Default)

    /** The editor's door onto the open Pot: its saves and deletes are this model's writes, each on the Pot it opened on. */
    private inner class EditorDoor : EntryEditor.Door {
        override fun view(): PotView? = _state.value?.view
        override fun today(): String = this@PotModel.today()
        override fun newId(): String = opened?.newId() ?: error("no Pot open")
        override suspend fun rate(currency: String, day: String) = rateFor(currency, day)
        override suspend fun save(entryId: String, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, own: Set<String>): EntryEditor.Written {
            var written = EntryEditor.Written(PotSession.WriteOutcome.REFUSED)
            writing(said = false) {
                val o = opened ?: return@writing PotSession.WriteOutcome.REFUSED
                written = o.saveEntry(entryId, form, basedOn, pending, own)
                written.outcome
            }
            return written
        }
        override suspend fun delete(entryId: String, basedOn: Long): PotSession.WriteOutcome {
            var outcome = PotSession.WriteOutcome.REFUSED
            writing(said = false) { (opened?.deleteEntry(entryId, basedOn) ?: PotSession.WriteOutcome.REFUSED).also { outcome = it } }
            return outcome
        }
    }

    /**
     * What the last write of the Pot's screens that did not land says ([State.message] as `Notices.potRefused`; a write
     * refused before it was sent — nothing to record, a reader — as a plain refusal): for a platform that says it at once,
     * from the write's answer rather than a state that may arrive after.
     */
    fun refusal(): me.raddatz.veyq.mobile.words.Notice =
        me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Notices.potRefused(_state.value?.message ?: PotSession.WriteOutcome.REFUSED.name))

    /** Forgets the last write's refusal (a sheet closed). */
    fun clearMessage() {
        _state.update { it?.with(message = null) }
    }

    /** Today, the device's local day (`yyyy-MM-dd`) by the core's clock: what a payment recorded now is dated. */
    fun today(): String = LocalDays.isoOf(now())

    /** The row facts the open Pot's meta gives now (a settings write may have set its base currency). */
    fun rowDetails(): List<String>? = session?.let { PotRows.detailsOf(it.meta) }

    /** One unit of [currency] in the open Pot's base from [day]'s table (`yyyy-MM-dd`, the device's today), or null. */
    suspend fun rateFor(currency: String, day: String): ExchangeRates.Filled? = opened?.let { runCatching { it.rateFor(currency, day) }.getOrNull() }

    /** A receipt of the open Pot opened to its bytes, or null (blocked, or it did not read). */
    suspend fun openReceipt(receiptId: String): ByteArray? = opened?.let { runCatching { it.openReceipt(receiptId) }.getOrNull() }

    /**
     * The open Pot downloaded as the web's ZIP into [sink] (`PotExport.download`), or null when nothing is open or it has
     * no settings to export in. [deletedName] is the platform's word for an erased person, which the CSV carries.
     */
    @Throws(Exception::class)
    suspend fun download(deletedName: String, sink: (ByteArray) -> Unit): me.raddatz.veyq.mobile.export.NodeExport.Written? =
        session?.let { PotExport.download(it, deletedName, now(), sink) }

    /** The file the open Pot's download is saved as (`NodeExport.fileName`: the web's archive name), or null: none open. */
    fun archiveName(): String? = session?.let { me.raddatz.veyq.mobile.export.NodeExport.fileName(it.name) }

    /** The receipts the last save left out for want of room (the entry was saved). */
    val skippedReceipts: List<String> get() = session?.skippedReceipts.orEmpty()

    /** The open Pot [ref] copied to [to] (null: the space's root): a new Pot under fresh keys; the shell's lists read anew. */
    suspend fun duplicate(shell: ShellModel, ref: ObjectRef, to: String?, deletedName: String, uuid: () -> String): PotCopy.Copied {
        val failed = PotCopy.Copied(PotCopy.Outcome.FAILED)
        val s = session?.takeIf { _state.value?.ref == ref } ?: return failed
        val t = tenant ?: return failed
        val r = runCatching { PotCopy.duplicate(s, t, ref.scopeId, to, deletedName, uuid) }.getOrDefault(failed)
        shell.refresh()
        return r
    }

    /**
     * The "New pot" sheet ([PotCreateSheet]) opened at the shell's place and over its spaces now, [language] the person's
     * (the base currency it starts with); null before the shell is read. Its create reads the shell's lists again and
     * opens the new Pot there.
     */
    fun createSheet(shell: ShellModel, language: String): PotCreateSheet? {
        val t = tenant ?: return null
        val now = shell.state.value ?: return null
        return PotCreateSheet.open(CreateDoor(t, shell), now.shell, now.lists.spaces, language, scope, Dispatchers.Default)
    }

    private class CreateDoor(private val t: TenantSession, private val shell: ShellModel) : PotCreateSheet.Door {
        override suspend fun members(scopeId: String) = t.scopeMembers(scopeId)
        override suspend fun create(scopeId: String, parentId: String?, name: String, base: Ledger.BaseCurrency, people: List<PotCreate.NewPerson>): PotCreate.Result {
            val r = runCatching { PotCreate.create(t, scopeId, parentId, name, base, people) }.getOrDefault(PotCreate.Result(PotCreate.Outcome.REFUSED))
            val nodeId = r.nodeId ?: return r
            t.forgetTree()
            shell.refresh()
            shell.state.value?.lists?.objects?.firstOrNull { it.nodeId == nodeId }?.let(shell::open)
            return r
        }
    }

    companion object {
        /** The message of a restore asked once its window closed ([restore]): no write was sent. */
        const val NOT_RESTORABLE = "NOT_RESTORABLE"
    }
}
