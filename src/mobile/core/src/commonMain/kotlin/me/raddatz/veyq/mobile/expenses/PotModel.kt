package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * The Pot open in the app (ADR 0127 decision 2): its session and what its screens show ([PotView]) — loading, failed,
 * saving a write and that write's refusal —, its live poll while a screen runs it (skipped while a write is saving), a
 * pull to refresh, the writes, and a new Pot's creation. Both apps observe [state] (or [watch]); what they draw is theirs.
 */
class PotModel(private val tenant: TenantSession, private val now: () -> Long) {
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

    /** The open Pot's session, while one is open. */
    var session: PotSession? = null
        private set

    private var poll: LivePoll? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A watch for a platform without flows (the state, or a live poll): stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with each state (null: no Pot open) on a background thread. */
    fun watch(listener: (State?) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** Opens and reads Pot [ref] unless it is the one open (and did open). */
    suspend fun open(ref: ObjectRef) {
        _state.value?.let { if (it.ref == ref && !it.failed) return }
        _state.value = State(ref)
        val s = runCatching { PotSession.open(tenant, ref.scopeId, ref.nodeId) }.getOrNull()
        if (_state.value?.ref != ref) return
        session = s
        poll = LivePoll(now = now)
        _state.value = State(ref, s?.let(::PotView), failed = s == null)
    }

    /** The Pot is gone (trashed, its space left): nothing is open. */
    fun close(ref: ObjectRef? = null) {
        if (ref != null && _state.value?.ref != ref) return
        _state.value = null
        session = null
        poll = null
    }

    /**
     * The open Pot's live poll (core's `LivePoll`) for as long as the caller runs it — a Pot screen while it is shown, so
     * nothing is asked in the background; skipped while a write shows saving; the view read anew when a tick moved it.
     */
    suspend fun live() {
        val s = session ?: return
        val cadence = poll ?: return
        s.live(cadence, busy = { _state.value?.saving != false }) {
            if (session === s) _state.update { it?.with(view = PotView(s)) }
        }
    }

    /** [live] in the model's own scope, for a platform that cannot run a suspend loop: stopped with [Watch.stop]. */
    fun startLive(): Watch = Watch(scope.launch { live() })

    /** Reads the open Pot again now (pull to refresh), its receipts too. */
    suspend fun refresh() {
        val s = session ?: return
        runCatching {
            s.refresh()
            s.readReceipts()
        }
        if (session === s) _state.update { it?.with(view = PotView(s)) }
    }

    /**
     * The open Pot read anew under its key, after a re-key that may have reached it (a link on it or above it closed, a
     * scope's re-key finished in the background); a Pot the re-key did not reach reads the same.
     */
    suspend fun rekeyed() {
        val s = session ?: return
        runCatching { s.rekeyed() }
        if (session === s) _state.update { it?.with(view = PotView(s)) }
    }

    /** A write begins: the state shows it saving; its session, or null when no Pot is open. */
    fun beginWrite(): PotSession? {
        val s = session ?: return null
        _state.update { it?.with(saving = true, message = null) }
        return s
    }

    /** A write ended in [outcome]: the Pot as read after it, a refusal as the message (its name); whether it was stored. */
    fun endWrite(outcome: PotSession.WriteOutcome): Boolean {
        val s = session
        _state.update { it?.with(view = s?.let(::PotView) ?: it.view, saving = false, message = outcome.takeIf { o -> o != PotSession.WriteOutcome.WRITTEN }?.name) }
        return outcome == PotSession.WriteOutcome.WRITTEN
    }

    /** One write to the open Pot ([op] on its session), between [beginWrite] and [endWrite]; whether it was stored. */
    suspend fun write(op: suspend (PotSession) -> PotSession.WriteOutcome): Boolean {
        val s = beginWrite() ?: return false
        return endWrite(runCatching { op(s) }.getOrDefault(PotSession.WriteOutcome.REFUSED))
    }

    /** Forgets the last write's refusal (a sheet closed). */
    fun clearMessage() {
        _state.update { it?.with(message = null) }
    }

    /** The row facts the open Pot's meta gives now (a settings write may have set its base currency). */
    fun rowDetails(): List<String>? = session?.let { PotRows.detailsOf(it.meta) }

    /** One unit of [currency] in the open Pot's base from [day]'s table (`yyyy-MM-dd`, the device's today), or null. */
    suspend fun rateFor(currency: String, day: String): ExchangeRates.Filled? = session?.let { runCatching { it.rateFor(currency, day) }.getOrNull() }

    /** A receipt of the open Pot opened to its bytes, or null (blocked, or it did not read). */
    suspend fun openReceipt(receiptId: String): ByteArray? = session?.let { runCatching { it.openReceipt(receiptId) }.getOrNull() }

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
        val r = runCatching { PotCopy.duplicate(s, tenant, ref.scopeId, to, deletedName, uuid) }.getOrDefault(failed)
        shell.refresh()
        return r
    }

    /** The "New pot" sheet: saving while it creates, and its refusal ([PotCreate.Outcome] name). */
    class Creating(val saving: Boolean = false, val message: String? = null)

    private val _creating = MutableStateFlow(Creating())
    val creating: StateFlow<Creating> = _creating.asStateFlow()

    fun clearCreating() {
        _creating.value = Creating()
    }

    /**
     * Creates a Pot [name] in [scopeId] under [parentId] (null: the space's root) with [base] and [people], reads the
     * shell's lists again and opens it there; whether it exists (its people may still have failed: it says so on its
     * Settings, where they can be added).
     */
    suspend fun create(
        shell: ShellModel, scopeId: String, parentId: String?, name: String, base: Ledger.BaseCurrency, people: List<PotCreate.NewPerson>,
    ): Boolean {
        _creating.value = Creating(saving = true)
        val r = runCatching { PotCreate.create(tenant, scopeId, parentId, name, base, people) }.getOrDefault(PotCreate.Result(PotCreate.Outcome.REFUSED))
        val nodeId = r.nodeId
        if (nodeId == null) {
            _creating.value = Creating(message = r.outcome.name)
            return false
        }
        tenant.forgetTree()
        shell.refresh()
        _creating.value = Creating(message = r.outcome.takeIf { it != PotCreate.Outcome.CREATED }?.name)
        shell.state.value?.lists?.objects?.firstOrNull { it.nodeId == nodeId }?.let(shell::open)
        return true
    }
}
