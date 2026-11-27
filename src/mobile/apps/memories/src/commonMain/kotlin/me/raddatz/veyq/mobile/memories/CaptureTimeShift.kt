package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A selection's capture-time shift, its arithmetic (uc.memory.buckets § Correct a selection's capture time; the web's
 * `captureTimeShift.ts`, answer for answer — `CaptureTimeShiftTest` holds a case per web case). A camera whose clock was
 * wrong is wrong by the same offset on every photo it took, so a selection is corrected through ONE of its media (the
 * anchor) and every other selected medium follows by that medium's offset. Pure: no write ([SelectionShift] writes).
 */
object CaptureTimeShift {
    private const val MINUTE_MS = 60_000L

    /** One selected medium as the shift reads it: its id and its listed tier (null: none sealed). */
    data class Input(val mediaId: String, val meta: MemoryMedia.Meta?)

    /** What a shift does to one medium: a new override, the override dropped (the shifted time is its own original again,
     *  as a reset leaves it), or nothing — it has no capture time to shift. */
    sealed interface Step {
        val mediaId: String

        data class Set(override val mediaId: String, val overrideMs: Long) : Step
        data class Clear(override val mediaId: String) : Step
        data class SkipUndated(override val mediaId: String) : Step
    }

    data class Plan(
        /** The anchor's offset in ms: the new time minus its effective time as the editor showed it (to the minute). */
        val offsetMs: Long,
        /** One step per selected medium, in the order handed in (the anchor among them). */
        val steps: List<Step>,
        /** How many selected media have no capture time and stay as they are. */
        val undated: Int,
    )

    /**
     * The shift of [selection] through its anchor ([anchorBefore], set to [afterMs]), or null where there is none: the
     * anchor has no capture time (no offset exists), the edit cleared the field (null), or it moved nothing.
     *
     * The offset is taken against the anchor's effective time **as the editor showed it**, to the minute: the anchor read
     * 10:03:27 and showed 10:03; set to 11:15, every medium moves by exactly +1 h 12 min, the anchor to 11:15:27 — the
     * difference the member saw, and seconds kept, so the order among the selected stays as it was.
     */
    fun plan(anchorBefore: MemoryMedia.Meta?, afterMs: Long?, selection: List<Input>): Plan? {
        val before = anchorBefore?.effectiveCapturedAt ?: return null
        afterMs ?: return null
        val offsetMs = afterMs - before.floorDiv(MINUTE_MS) * MINUTE_MS
        if (offsetMs == 0L) return null
        var undated = 0
        val steps = selection.map { m ->
            val effective = m.meta?.effectiveCapturedAt
            if (effective == null) {
                undated += 1
                Step.SkipUndated(m.mediaId)
            } else {
                val shifted = effective + offsetMs
                // back on its own original: no correction left, so the override goes (the details' "Corrected" stays truthful)
                if (m.meta.capturedAt == shifted) Step.Clear(m.mediaId) else Step.Set(m.mediaId, shifted)
            }
        }
        return Plan(offsetMs, steps, undated)
    }

    /** An offset written out: its sign and its whole days, hours and minutes (a shift is minute-granular). */
    data class Parts(val negative: Boolean, val days: Long, val hours: Long, val minutes: Long)

    fun parts(offsetMs: Long): Parts {
        val total = (kotlin.math.abs(offsetMs) + MINUTE_MS / 2) / MINUTE_MS
        return Parts(offsetMs < 0, total / (24 * 60), (total % (24 * 60)) / 60, total % 60)
    }

    /** The offset as the save says it (*"+1 h 12 min"*), the web's: the parts that are not zero, after the sign. */
    fun offsetText(offsetMs: Long): Joined {
        val p = parts(offsetMs)
        val pieces = buildList<Any> {
            if (p.days > 0) add(Notice(Msg.MEMORY_SHIFT_DAYS, p.days.toInt()))
            if (p.hours > 0) add(Notice(Msg.MEMORY_SHIFT_HOURS, p.hours.toInt()))
            if (p.minutes > 0) add(Notice(Msg.MEMORY_SHIFT_MINUTES, p.minutes.toInt()))
        }
        // one level of parts, a string or a word each (`Joined`'s form): the sign, then the parts with a space between
        return Joined(listOf<Any>(if (p.negative) "−" else "+") + pieces.flatMapIndexed { i, piece -> if (i == 0) listOf(piece) else listOf(" ", piece) }, "")
    }
}

/**
 * A selection's capture-time shift as the member runs it (uc.memory.buckets § Correct a selection's capture time; the
 * web's lightbox save and `useCaptureTimeShift.ts`), opened from the Contributions view's selection
 * ([Contributions.shift]): the member sets the anchor's time ([propose]) — the first selected medium with a capture time,
 * its time picked as the details' editor picks one —, then chooses [onlyThis] or [all] (every selected medium by the
 * anchor's offset, [State.all] saying it), and may take it back ([undo]).
 *
 * Client-only: each medium is one ordinary sealed meta write with the revision the selection was read at (ADR 0077), at
 * most [CONCURRENCY] at once; a refusal does not stop the rest, and each refused medium is named, one line per reason
 * ([State.refused]). **Never against a time it no longer has:** every write starts from the medium as it was when the
 * shift was opened, so a peer's change of its capture time meanwhile is a conflict — refused and named, never shifted from
 * the peer's value. The Undo writes each written medium's override back (or none) from the gallery as read after the
 * run, and refuses a medium whose override is no longer the one the shift wrote.
 */
class SelectionShift internal constructor(
    private val anchor: MemoryMedia.Item,
    private val selection: List<MemoryMedia.Item>,
    /** The details' own write of one medium (to the minute): [onlyThis]. */
    private val one: suspend (MemoryMedia.Item, Long?) -> CaptureTime.Outcome,
    /** One medium's override written exactly (or removed for null): [all] and [undo]. */
    private val exact: suspend (MemoryMedia.Item, Long?) -> CaptureTime.Outcome,
    /** The gallery read anew, the selection's items as it lists them now. */
    private val rereadGallery: suspend () -> List<MemoryMedia.Item>,
) {
    companion object {
        /** How many of a shift's writes run at once (the web's `SHIFT_CONCURRENCY`). */
        const val CONCURRENCY = 4
    }

    enum class Step {
        /** The anchor's time is picked. */
        EDIT,

        /** The time moved the anchor: only it, or all of them ([State.all]). */
        CHOOSE,

        /** Writing ([State.progress]). */
        RUN,

        /** Written: what it came to ([State.done], [State.refused]); [State.undoable] offers the Undo. */
        DONE,
    }

    /** Why a medium was not written. */
    enum class Refusal(val msg: Msg) {
        CHANGED(Msg.MEMORY_REFUSED_CHANGED),
        UNREADABLE(Msg.MEMORY_REFUSED_UNREADABLE),
        GONE(Msg.MEMORY_REFUSED_GONE),
        FAILED(Msg.MEMORY_REFUSED_FAILED),
    }

    data class State(
        /** The anchor's effective capture time (epoch ms), where the picker opens. */
        val anchorAtMs: Long,
        /** "Set the time of “…”; the other N follow." */
        val intro: Notice,
        val step: Step = Step.EDIT,
        /** The time picked for the anchor ([propose]). */
        val pickedMs: Long? = null,
        /** The choice to shift every selected medium: "All N, shifted by +1 h 12 min". */
        val all: Notice? = null,
        /** How many selected have no capture time and stay as they are; null: none. */
        val undated: Notice? = null,
        /** "Shifting 2 of 5…" while it runs. */
        val progress: Notice? = null,
        /** What the run (or the Undo) came to; null where nothing was written. */
        val done: Notice? = null,
        /** What the server refused, one line per reason. */
        val refused: List<Notice> = emptyList(),
        /** The Undo is offered: something was shifted and not yet taken back. */
        val undoable: Boolean = false,
        /** Only the anchor was written and it landed, or an Undo put everything back: the platform closes. */
        val closed: Boolean = false,
    )

    private val _state = MutableStateFlow(
        State(anchor.meta?.effectiveCapturedAt ?: 0L, Notice(Msg.MEMORY_SHIFT_INTRO, selection.size - 1, nameOf(anchor))),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private val acting = Mutex()

    /** What follows each run and Undo once the gallery read anew (the Contributions' buckets read). */
    internal var afterRun: suspend () -> Unit = {}

    private suspend fun reread(): List<MemoryMedia.Item> = rereadGallery().also { afterRun() }

    private var plan: CaptureTimeShift.Plan? = null

    /** What the shift wrote, by medium: the override it had before and the one written (null: removed). */
    private var written: List<Triple<MemoryMedia.Item, Long?, Long?>> = emptyList()

    private fun nameOf(item: MemoryMedia.Item): Any =
        item.name?.takeIf { it.isNotBlank() } ?: Notice(Msg.MEMORY_UNNAMED, selection.indexOfFirst { it.mediaId == item.mediaId } + 1)

    /**
     * The anchor's time set to [epochMs] (the platform's picked local time, converted by its own calendar): whether it
     * moved the anchor — then the choice is offered ([Step.CHOOSE]); otherwise nothing changes.
     */
    fun propose(epochMs: Long): Boolean {
        if (current.step != Step.EDIT && current.step != Step.CHOOSE) return false
        val p = CaptureTimeShift.plan(anchor.meta, CaptureTime.minute(epochMs), selection.map { CaptureTimeShift.Input(it.mediaId, it.meta) })
            ?: return false
        plan = p
        _state.update {
            it.copy(
                step = Step.CHOOSE, pickedMs = CaptureTime.minute(epochMs),
                all = Notice(Msg.MEMORY_SHIFT_ALL, selection.size, CaptureTimeShift.offsetText(p.offsetMs)),
                undated = if (p.undated > 0) Notice(Msg.MEMORY_SHIFT_UNDATED, p.undated) else null,
            )
        }
        return true
    }

    /** Back from the choice to the picker. */
    fun back() = _state.update { if (it.step == Step.CHOOSE) it.copy(step = Step.EDIT) else it }

    /** Only the anchor is set to the picked time, as the details' editor sets one. */
    suspend fun onlyThis() = acting.withLock {
        val at = current.pickedMs ?: return@withLock
        if (current.step != Step.CHOOSE) return@withLock
        _state.update { it.copy(step = Step.RUN, progress = Notice(Msg.MEMORY_SHIFT_PROGRESS, 0, 1)) }
        val out = one(anchor, at)
        reread()
        _state.update {
            if (out is CaptureTime.Outcome.Done) it.copy(step = Step.DONE, progress = null, closed = true)
            else it.copy(step = Step.DONE, progress = null, refused = refusedLines(Msg.MEMORY_SHIFT_REFUSED, listOf(nameOf(anchor) to refusalOf(out))))
        }
    }

    /** Every selected medium with a capture time shifted by the anchor's offset. */
    suspend fun all() = acting.withLock {
        val p = plan ?: return@withLock
        if (current.step != Step.CHOOSE) return@withLock
        val byId = selection.associateBy { it.mediaId }
        val targets = p.steps.mapNotNull { s ->
            val item = byId[s.mediaId] ?: return@mapNotNull null
            when (s) {
                is CaptureTimeShift.Step.Set -> item to s.overrideMs
                is CaptureTimeShift.Step.Clear -> item to null
                is CaptureTimeShift.Step.SkipUndated -> null
            }
        }
        val results = writeAll(targets)
        written = targets.zip(results).filter { it.second is CaptureTime.Outcome.Done }.map { (t, _) -> Triple(t.first, t.first.meta?.capturedAtOverride, t.second) }
        reread()
        val refused = targets.zip(results).filter { it.second !is CaptureTime.Outcome.Done }.map { (t, r) -> nameOf(t.first) to refusalOf(r) }
        _state.update {
            it.copy(
                step = Step.DONE, progress = null,
                done = if (written.isNotEmpty()) Notice(Msg.MEMORY_SHIFT_DONE, written.size) else null,
                refused = refusedLines(Msg.MEMORY_SHIFT_REFUSED, refused), undoable = written.isNotEmpty(),
            )
        }
    }

    /** The shift taken back: each written medium's override as it was (or none); one a peer changed since is refused. */
    suspend fun undo() = acting.withLock {
        if (!current.undoable) return@withLock
        val now = reread().associateBy { it.mediaId }
        val refused = mutableListOf<Pair<Any, Refusal>>()
        val targets = mutableListOf<Pair<MemoryMedia.Item, Long?>>()
        for ((item, previous, wrote) in written) {
            val listed = now[item.mediaId]
            if (listed == null) refused += nameOf(item) to Refusal.GONE
            else if (listed.meta?.capturedAtOverride != wrote) refused += nameOf(item) to Refusal.CHANGED
            else targets += listed to previous
        }
        val results = writeAll(targets)
        refused += targets.zip(results).filter { it.second !is CaptureTime.Outcome.Done }.map { (t, r) -> nameOf(t.first) to refusalOf(r) }
        written = emptyList()
        reread()
        _state.update {
            val lines = it.refused + refusedLines(Msg.MEMORY_RESTORE_REFUSED, refused)
            // a clean Undo leaves nothing to say: the dialog closes, as the web's Undo leaves no toast behind
            it.copy(progress = null, done = null, undoable = false, refused = lines, closed = lines.isEmpty())
        }
    }

    /** Each target written at most [CONCURRENCY] at a time, the progress said as each settles; results in their order. */
    private suspend fun writeAll(targets: List<Pair<MemoryMedia.Item, Long?>>): List<CaptureTime.Outcome> {
        var done = 0
        val count = Mutex()
        _state.update { it.copy(step = Step.RUN, progress = Notice(Msg.MEMORY_SHIFT_PROGRESS, 0, targets.size)) }
        val permits = Semaphore(CONCURRENCY)
        return coroutineScope {
            targets.map { (item, ms) ->
                async {
                    permits.withPermit {
                        val r = exact(item, ms)
                        count.withLock {
                            done += 1
                            _state.update { it.copy(progress = Notice(Msg.MEMORY_SHIFT_PROGRESS, done, targets.size)) }
                        }
                        r
                    }
                }
            }.awaitAll()
        }.also { _state.update { it.copy(step = Step.DONE) } }
    }

    private fun refusalOf(out: CaptureTime.Outcome): Refusal = when (out) {
        is CaptureTime.Outcome.Conflict -> Refusal.CHANGED
        CaptureTime.Outcome.Unreadable -> Refusal.UNREADABLE
        is CaptureTime.Outcome.Failed -> if (out.code == "status_404" || out.code == "gone" || out.code == "node_unreadable") Refusal.GONE else Refusal.FAILED
        is CaptureTime.Outcome.Done -> Refusal.FAILED
    }

    /** One line per reason, its media named in their order: "Not shifted: a.jpg, b.jpg. Someone else changed …". */
    private fun refusedLines(msg: Msg, refused: List<Pair<Any, Refusal>>): List<Notice> =
        refused.groupBy({ it.second }, { it.first }).map { (why, names) -> Notice(msg, Joined(names, ", "), Notice(why.msg)) }
}
