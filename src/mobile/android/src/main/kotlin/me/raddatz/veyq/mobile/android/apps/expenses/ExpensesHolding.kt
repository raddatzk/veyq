package me.raddatz.veyq.mobile.android.apps.expenses

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.Holding
import me.raddatz.veyq.mobile.android.core.HoldingKey
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.expenses.EntryEditor
import me.raddatz.veyq.mobile.expenses.PotCreateSheet
import me.raddatz.veyq.mobile.expenses.PotExport
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotSettings
import me.raddatz.veyq.mobile.expenses.PotShare
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShareInbox
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg

/** The key Expenses' UI reaches its [ExpensesHolding] by (registered in [ExpensesUi.register]). */
internal val ExpensesHeld = HoldingKey(::ExpensesHolding)

/** What Expenses holds in the app's model: the open Pot ([ExpensesHolding]). */
internal val AppModel.expenses: ExpensesHolding get() = held(ExpensesHeld)

/**
 * What Expenses holds in the app's model for the open tenant (ADR 0154 decision 3): the open Pot (core's `PotModel`, one
 * per tenant), a link guest's Pot while its page is on screen, and the Pot's writes run in the model's scope.
 */
internal class ExpensesHolding(private val model: AppModel) : Holding() {
    private val scope get() = model.viewModelScope

    /** The Pot open in the app (core's `PotModel`): its views, its live poll, its writes. */
    private var potModel: PotModel? = null
    private var potWatch: Job? = null
    private val potSession: PotSession? get() = openPot?.session

    /**
     * The Pot of a link open as its guest (ADR 0150 decision 5, `PotGuest.Pot`) while its page is on screen: the Pot's
     * page and its sheets are the member's, so they read and write through whichever Pot is shown — the guest's above
     * the shell while it is, the shell's otherwise ([openPot], [pot]).
     */
    private val _guestPot = MutableStateFlow<PotModel?>(null)
    private val openPot: PotModel? get() = _guestPot.value ?: potModel

    private val _pot = MutableStateFlow<PotModel.State?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pot: StateFlow<PotModel.State?> =
        _guestPot.flatMapLatest { g -> g?.state ?: _pot }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun shown(shell: ShellModel, tenant: TenantSession, switched: Boolean) {
        val pots = PotModel(tenant, System::currentTimeMillis)
        potWatch?.cancel()
        potModel = pots
        potWatch = scope.launch { pots.state.collect { _pot.value = it } }
    }

    /** An open Pot of the node closes once it went to the trash. */
    override fun trashed(ref: NodeAt) {
        _pot.value?.ref?.takeIf { it.nodeId == ref.nodeId }?.let { potModel?.close(it) }
    }

    /** A Pot's copy goes by the open Pot (core's `PotModel.duplicate`); a Pot's move as any node's. */
    override fun moveSheet(shell: ShellModel, node: NodeAt, copying: Boolean, changed: () -> Unit): MoveSheet? {
        if (node.kind != PotSession.KIND) return null
        return shell.moveSheet(node, copying, potModel, model.getApplication<Application>().getString(R.string.pot_deleted_person), changed)
    }

    /** Files dropped on the app while a Pot that takes them is open: its new expense opens with them. */
    override fun takesDrop(open: ObjectRef, incoming: ShareInbox.Incoming): Boolean {
        val pot = _pot.value
        return open.kind == PotSession.KIND && pot?.ref == open && PotShare.takesDropOn(pot.view, incoming)
    }

    /** The guest's Pot [pot] shown (its page composed) or, null, gone. */
    fun showGuestPot(pot: PotModel?) {
        if (pot == null) _guestPot.value?.close()
        _guestPot.value = pot
    }

    /** The waiting files, taken by the Pot opened as their place: its new expense's receipts (each read whole, within its budget). */
    suspend fun takeShare(): PotShare.Taken? = withContext(Dispatchers.IO) {
        PotShare.take(model.shareInbox) { java.util.UUID.randomUUID().toString() }
    }

    /** The held editor of write [writeId] in the open Pot (core's `PotModel.heldEditor`); null where it is no held entry of it. */
    suspend fun heldEntryEditor(writeId: String, separator: Char): EntryEditor? =
        openPot?.heldEditor(writeId, separator)

    /** Opens and reads the Pot [ref] unless it is the one shown. */
    fun loadPot(ref: ObjectRef) {
        val pots = openPot ?: return
        scope.launch { pots.open(ref) }
    }

    /** The open Pot's live poll, for as long as the caller runs it (the Pot's screen while it is started). */
    suspend fun livePot() {
        openPot?.live()
    }

    /** Reads the open Pot again now (pull to refresh); [done] when it settled, moved or not. */
    fun refreshPot(done: () -> Unit) {
        val pots = openPot ?: return done()
        scope.launch {
            pots.refresh()
            done()
        }
    }

    /** Records settle-up [rows] as paid, dated the core's today (core's `PotModel.record`); a refusal is the Pot's message. */
    fun recordSettleUp(rows: List<PotView.SettleRow>) {
        val pots = openPot ?: return
        scope.launch { pots.record(rows) }
    }

    /** Whether deleted entry [entryId] may be restored now, by the core's clock. */
    fun restorable(entryId: String): Boolean = openPot?.restorable(entryId) == true

    /** Restores deleted entry [entryId] (core's `PotModel.restore`, its window the core's); [done] once it was. */
    fun restoreEntry(entryId: String, done: () -> Unit) {
        val pots = openPot ?: return
        scope.launch { if (pots.restore(entryId)) done() }
    }

    /** The open Pot's settings and people (core's `PotSettings`); null while none is read. */
    fun potSettings(): PotSettings? = openPot?.settings()

    /**
     * One change of the open Pot's settings or people ([op], a `PotSettings` action); [done] once it was stored. A new base
     * currency is said on the Pot's row at once.
     */
    fun settingsWrite(op: suspend () -> Boolean, done: () -> Unit = {}) {
        val pots = openPot ?: return
        val ref = _pot.value?.ref ?: return
        scope.launch {
            val stored = op()
            pots.rowDetails()?.let { facts -> model.rowDetail(ref.nodeId, facts) }
            if (stored) done()
        }
    }

    /** "New pot" (core's `PotCreateSheet`) at the shell's place now, in the person's language's currency; null before the shell is read. */
    fun potCreateSheet(): PotCreateSheet? {
        val shell = model.shellModel ?: return null
        return potModel?.createSheet(shell, java.util.Locale.getDefault().language)
    }

    /** "New pot" made one: the new Pot's row says its facts. */
    fun potCreated() {
        val shell = model.shellModel ?: return
        scope.launch { model.readRowDetails(shell.tenant) }
    }

    /**
     * The entry editor of [entryId] in the open Pot (null: a new entry), core's [EntryEditor] over the Pot's model;
     * [pending] what another app shared as its receipts. Null when the Pot cannot take one.
     */
    fun entryEditor(entryId: String?, separator: Char, pending: List<PotSession.PendingReceipt> = emptyList()): EntryEditor? =
        openPot?.editor(entryId, separator, pending)

    /**
     * Downloads the open Pot [ref] into [out] as the web's ZIP (uc.platform.export-import, ADR 0082): its settings, people and
     * live entries, each receipt, and a CSV of the entries. False when it did not complete; [out] is closed either way.
     */
    suspend fun downloadPot(ref: ObjectRef, out: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        val session = potSession?.takeIf { _pot.value?.ref == ref }
        val deleted = model.getApplication<Application>().getString(R.string.pot_deleted_person)
        val done = session != null && runCatching {
            out.buffered(1 shl 16).let { b ->
                PotExport.download(session, deleted, System.currentTimeMillis()) { b.write(it) }.also { b.flush() }
            } != null
        }.getOrDefault(false)
        runCatching { out.close() }
        done
    }

    /** A receipt of the open Pot opened to its bytes, or null (blocked, or it did not read). */
    suspend fun openReceipt(receiptId: String): ByteArray? = openPot?.openReceipt(receiptId)

    /** Why the open Pot's receipt did not open (core's `PotModel.receiptFailure`): not kept offline, or unreadable. */
    fun receiptFailure(): Msg = openPot?.receiptFailure() ?: Msg.RECEIPTS_UNREADABLE

    /** Forgets the open Pot's last refusal (a dialog closed). */
    fun clearPotMessage() {
        openPot?.clearMessage()
    }
}
