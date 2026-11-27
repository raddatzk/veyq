package me.raddatz.veyq.mobile.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.words.Msg

/**
 * The account's kit record (ADR 0157 decisions 3-4, en.account.key-model § The kit record; the web's `kitRecord.ts`):
 * which layout of the Emergency Kit the account last made. ONE blob sealed under the Master Key, purpose `kit-record` with
 * no binding field (vector `seal.sym.kit-record`), the plaintext the compact JSON `{"layout":n}`; the server stores and
 * serves it to the account alone and never sees the number. Last writer wins. **No record, or one that does not open, is
 * layout 1** — a kit made before the record existed; never an error on screen.
 */
object KitRecord {
    /** What an account without a record made: every kit before ADR 0157. */
    const val LAYOUT_WITHOUT_RECORD = 1

    private val CONTEXT = Context("kit-record")

    /** The record of a kit of this writer's layout ([KitWriter.LAYOUT]), sealed under [mk] → the base64 blob a request carries. */
    fun seal(suite: V1, mk: ByteArray): String = b64(suite.seal(CONTEXT, mk, "{\"layout\":${KitWriter.LAYOUT}}".encodeToByteArray()))

    /** The layout a served [blob] names, opened with [mk]; [LAYOUT_WITHOUT_RECORD] for none, one that does not open, and JSON
     *  without a positive whole `layout` (the web's `layoutOf`, E4). */
    fun layoutOf(suite: V1, mk: ByteArray, blob: String?): Int {
        if (blob.isNullOrEmpty()) return LAYOUT_WITHOUT_RECORD
        return runCatching {
            val plain = suite.open(CONTEXT, mk, unb64(blob))
            val layout = ((Json.parseToJsonElement(plain.decodeToString()) as? JsonObject)?.get("layout") as? JsonPrimitive)
                ?.takeIf { !it.isString }?.longOrNull
            layout?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }.getOrNull() ?: LAYOUT_WITHOUT_RECORD
    }
}

/**
 * Which kit the account has, as this unlock read it (ADR 0157 decisions 5-6; the web's `kitLayoutState`): read once
 * ([ensure]) and shared by Settings → Security and the shell's notice; a failed read leaves it unknown and the next caller
 * asks again; a kit made here ([remember]) makes it current. In memory only, per signed-in account.
 */
class KitStatus internal constructor(private val read: suspend () -> Int) {
    /** What Settings → Security says of the account's kit. */
    @Serializable
    enum class Standing {
        /** The account's last kit is of this layout. */
        CURRENT,

        /** It is of an older one: a clearer kit exists ([NEWS] says what it does better), *Make a new kit* offered. */
        OLDER,

        /** It could not be read just now. */
        UNREADABLE,
    }

    data class State(val layout: Int? = null, val failed: Boolean = false) {
        /** The one condition of the suggestion: the account's last kit is of an older layout than this app prints. */
        val older: Boolean get() = layout != null && layout < KitWriter.LAYOUT

        /** Null until read. */
        val standing: Standing?
            get() = when {
                layout != null -> if (older) Standing.OLDER else Standing.CURRENT
                failed -> Standing.UNREADABLE
                else -> null
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val reading = Mutex()

    /** The layout read once for this unlock: a later call shares the answer; a failed read is asked again next time. */
    suspend fun ensure() {
        if (current.layout != null) return
        reading.withLock {
            if (current.layout != null) return
            _state.value = try {
                State(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State(failed = true)
            }
        }
    }

    /** The account just made a kit of this layout and its record says so (a rotation's commit, a record written). */
    fun remember() {
        _state.value = State(KitWriter.LAYOUT)
    }

    companion object {
        /** What the kit of [KitWriter.LAYOUT] does better (ADR 0157 decision 2): one sentence per raised layout. */
        val NEWS: Msg get() = Msg.KIT_LAYOUT_NEWS_2

        /**
         * The shell's one-time notice (ADR 0157 decision 5): [NEWS] while the account's last kit is older ([State.older])
         * and the notice this device last dismissed spoke of an earlier layout than this one ([dismissed], 0 for none).
         */
        fun notice(state: State, dismissed: Int): me.raddatz.veyq.mobile.words.Notice? =
            if (state.older && dismissed < KitWriter.LAYOUT) me.raddatz.veyq.mobile.words.Notice(NEWS) else null
    }
}
