package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/** One of the two lists a device keeps for its account and can start over (ADR 0028 decision 7): the pin store, the device register. */
enum class TrustDocument { PINS, REGISTER }

/**
 * *Start a list over* (ADR 0028 decision 7's one way back, inbox #192), the port of the web's `TrustResetPanel.vue` and
 * `TrustResetDialog.vue` over `trustReset.ts` and `refusedDocuments.ts`: each list this device refuses — the pin store or
 * the device register served older than this device's mark ([TrustStateRefused.Code.ROLLED_BACK]) or not opening
 * ([TrustStateRefused.Code.UNREADABLE]) — a row ([load]); its *Start over…* asks first ([ask]: what it costs, that the
 * server can wait, an acknowledgement unticked at every open), and its confirm ([confirm]) writes the EMPTY list and lowers
 * this device's mark to it ([StartOver.RESET]) — or, where the list opens after all, writes nothing
 * ([StartOver.ALREADY_READABLE]). It asks for no password and no Secret Key, by design (the web's). Verifications live
 * in the tenant and are untouched.
 */
class TrustReset(private val door: Door) {
    /** What the reset reads and writes: the account's ([VeyqAccount.trustReset]), a played one in a test. */
    interface Door {
        /** Why [document] does not open on this device, or null where it does; a failure that is no refusal is thrown. */
        suspend fun refusal(document: TrustDocument): TrustStateRefused.Code?

        /** [document] started over ([PinStore.startOver], [DeviceRegisterStore.startOver]). */
        suspend fun startOver(document: TrustDocument): StartOver

        /** The lists were read: whether one is refused ([VeyqAccount.trustRefused], the Settings section's gate). */
        fun checked(refused: Boolean) {}
    }

    /** A refused list's row: its name and why it is refused. */
    @Serializable
    data class RefusedList(
        val document: TrustDocument,
        @Serializable(with = MsgKey::class) val title: Msg,
        @Serializable(with = MsgKey::class) val why: Msg,
    )

    /** The question before a list is started over: its heading and what it costs ([Msg.TRUST_RESET_SERVER_CAN_WAIT] and the acknowledgement follow). */
    @Serializable
    data class ResetQuestion(
        val document: TrustDocument,
        @Serializable(with = MsgKey::class) val title: Msg,
        @Serializable(with = MsgKey::class) val cost: Msg,
        /** *I understand what starting over costs.*: the confirm is offered only once ticked. */
        val acknowledged: Boolean = false,
        val working: Boolean = false,
        /** The start-over did not come through ([Msg.TRUST_RESET_FAILED]); nothing was changed. */
        @Serializable(with = MsgKey::class) val failed: Msg? = null,
    )

    @Serializable
    data class State(
        val loading: Boolean = true,
        val rows: List<RefusedList> = emptyList(),
        /** The lists could not be checked ([Msg.TRUST_RESET_LOAD_FAILED]). */
        @Serializable(with = MsgKey::class) val loadFailed: Msg? = null,
        /** The last start-over's outcome: [Msg.TRUST_RESET_DONE] or [Msg.TRUST_RESET_ALREADY_READABLE]. */
        @Serializable(with = MsgKey::class) val said: Msg? = null,
        /** The question open over the page, or null. */
        val asking: ResetQuestion? = null,
    ) {
        /** Nothing to start over: [Msg.TRUST_RESET_NONE] is said. */
        val none: Boolean = !loading && loadFailed == null && rows.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /** Which lists this device refuses, pins then register (the web's `refusedDocuments`). */
    suspend fun load() = acting.withLock { read() }

    private suspend fun read() {
        _state.value = current.copy(loading = true, loadFailed = null)
        val rows = try {
            TrustDocument.entries.mapNotNull { d -> door.refusal(d)?.let { rowOf(d, it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = current.copy(loading = false, rows = emptyList(), loadFailed = Msg.TRUST_RESET_LOAD_FAILED)
            return
        }
        _state.value = current.copy(loading = false, rows = rows)
        door.checked(rows.isNotEmpty())
    }

    /** *Start over…* on [document]'s row: the question, its acknowledgement unticked. */
    fun ask(document: TrustDocument) {
        if (current.rows.none { it.document == document }) return
        _state.value = current.copy(asking = askOf(document), said = null)
    }

    /** The acknowledgement ticked or unticked. */
    fun acknowledge(on: Boolean) {
        val a = current.asking ?: return
        if (a.working) return
        _state.value = current.copy(asking = a.copy(acknowledged = on))
    }

    /** The question cancelled (refused while the start-over runs, as the web's dialog cannot be closed then). */
    fun cancel() {
        if (current.asking?.working == true) return
        _state.value = current.copy(asking = null)
    }

    /** *Start over*: only once acknowledged; the outcome said and the lists read again, a failure said in the question. */
    suspend fun confirm() = acting.withLock {
        val a = current.asking ?: return@withLock
        if (!a.acknowledged || a.working) return@withLock
        _state.value = current.copy(asking = a.copy(working = true, failed = null))
        val outcome = try {
            door.startOver(a.document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = current.copy(asking = a.copy(working = false, failed = Msg.TRUST_RESET_FAILED))
            return@withLock
        }
        _state.value = current.copy(
            asking = null,
            said = if (outcome == StartOver.RESET) Msg.TRUST_RESET_DONE else Msg.TRUST_RESET_ALREADY_READABLE,
        )
        read()
    }

    companion object {
        fun rowOf(document: TrustDocument, code: TrustStateRefused.Code) = RefusedList(
            document,
            if (document == TrustDocument.PINS) Msg.TRUST_RESET_DOCUMENT_PINS else Msg.TRUST_RESET_DOCUMENT_REGISTER,
            if (code == TrustStateRefused.Code.ROLLED_BACK) Msg.TRUST_RESET_CODE_ROLLED_BACK else Msg.TRUST_RESET_CODE_UNREADABLE,
        )

        fun askOf(document: TrustDocument) = ResetQuestion(
            document,
            if (document == TrustDocument.PINS) Msg.TRUST_RESET_CONFIRM_TITLE_PINS else Msg.TRUST_RESET_CONFIRM_TITLE_REGISTER,
            if (document == TrustDocument.PINS) Msg.TRUST_RESET_COST_PINS else Msg.TRUST_RESET_COST_REGISTER,
        )
    }
}
