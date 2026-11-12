package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A copy of a file saved where the person chose ("Save a copy", the file page's download; the Depot CR's D4): the one place
 * a decrypted copy is written (ADR 0047). Its content goes into [write]'s sink a frame at a time, never held whole, and
 * [state] says how far it is and how it ended — saved, failed, or [cancel]led with nothing left at its path (the sink, core's
 * `FileSink` natively, removes what a write that did not finish left).
 *
 * It runs in its own [scope], not its page's: a save goes on while the person looks elsewhere.
 */
class FileSave internal constructor(
    /** What the copy is saved as (`DepotFiles.downloadName`), which its words name. */
    val name: String,
    /** The file's plaintext size. */
    val total: Long,
    scope: CoroutineScope,
    /** Writes the copy, calling its progress with the bytes written so far; true once whole. */
    private val write: suspend (progress: (Long) -> Unit) -> Boolean,
) {
    /** How a save ended (named `SaveEnd` on the bridge's wire). */
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("SaveEnd")
    enum class End { SAVED, FAILED, CANCELLED }

    data class State(
        val written: Long,
        val total: Long,
        /** Null while it runs. */
        val end: End?,
        /** What it says: its progress while it runs (`download_saving`), then how it ended. */
        val notice: Notice,
    ) {
        /** How much is written, 0 to 1 (a file of no size is whole once saved). */
        val fraction: Double get() = if (total <= 0) (if (end == End.SAVED) 1.0 else 0.0) else (written.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private val _state = MutableStateFlow(State(0, total, null, progress(0)))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private val job: Job = scope.launch {
        val whole = try {
            write { n -> _state.update { if (it.end == null) it.copy(written = n, notice = progress(n)) else it } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _state.update { it.copy(end = if (whole) End.SAVED else End.FAILED, notice = Notice(if (whole) Msg.DOWNLOAD_DONE else Msg.DOWNLOAD_FAILED, name)) }
    }

    /**
     * Stops the save and returns once it stopped, its partial file removed: true where it was still running (it ends
     * [End.CANCELLED]), false where it had ended already.
     */
    suspend fun cancel(): Boolean {
        if (current.end != null) return false
        job.cancelAndJoin()
        var cancelled = false
        _state.update {
            if (it.end != null) it
            else it.copy(end = End.CANCELLED, notice = Notice(Msg.DOWNLOAD_CANCELLED, name)).also { cancelled = true }
        }
        return cancelled
    }

    private fun progress(written: Long) = Notice(Msg.DOWNLOAD_SAVING, name, Bytes(written), Bytes(total))
}
