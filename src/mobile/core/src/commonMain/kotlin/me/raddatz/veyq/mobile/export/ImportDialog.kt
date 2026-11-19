package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * The import's dialog as a model (ADR 0152 decision 5; the web's `ImportSubtreeDialog.vue`), run inline as the web runs
 * it, never in the upload queue: reading — the download opened and its dry run made, then the tenant asked whether it fits
 * — → ready (what would be created, how big, where, what is left out per application; a quota that does not fit keeps
 * *Import* shut) → running (nodes done of total) → done, or back to ready with what went wrong. *Import* is *Import the rest*
 * where something is left out; a download with nothing importable offers only *Cancel*; a ZIP that came from an upload also
 * offers *Upload as a file* ([asFile]). *Cancel* while it runs stops it, and what it made goes to the trash. Each platform
 * draws [state] in its own multi-step surface (ADR 0148 decision 10) and closes once [State.closed].
 */
class ImportDialog(
    private val input: ImportInput,
    /** Where it goes: the level's name as the app shows it. */
    private val targetName: String,
    private val scopeId: String,
    private val parentId: String?,
    private val target: SubtreeImport.Target,
    private val kinds: SubtreeImport.Kinds,
    private val tokenInput: (String, String) -> String?,
    /** The ordinary upload of the file instead (a ZIP from an upload); null for a folder, which offers none. */
    private val asFile: (() -> Unit)?,
    /** The dialog is gone: what it read may be let go (a share's file). */
    private val closed: () -> Unit = {},
    /** The tree changed: the app reads its lists anew. */
    private val changed: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    enum class Step { READING, READY, RUNNING, INVALID, DONE }

    /**
     * What the dialog shows: its [step]; the dry run's totals ([nodes], [bytes], [root] — the download's top name) and what it
     * leaves out ([leftOut], each said as the web says it); whether the tenant has room ([fits]: null where it did not say,
     * which never blocks); the running [progress]; and what it says ([said]: a refusal, a failure, or — once [Step.DONE] —
     * the imported line the app shows as its toast).
     */
    data class State(
        val step: Step = Step.READING,
        val nodes: Int = 0,
        val bytes: Long = 0,
        val root: String = "",
        val target: String = "",
        val leftOut: List<Notice> = emptyList(),
        val fits: Boolean? = null,
        val progress: SubtreeImport.Progress? = null,
        val said: List<Notice> = emptyList(),
        val canAsFile: Boolean = false,
        val closed: Boolean = false,
        /** The node the import made, once [Step.DONE]. */
        val nodeId: String? = null,
    ) {
        /** *Import* is offered: something to import, and room for it (or no answer). */
        val canImport: Boolean get() = step == Step.READY && nodes > 0 && fits != false

        /** The button says *Import the rest* where something is left out. */
        val importWord: Msg get() = if (leftOut.isNotEmpty()) Msg.IMPORT_REST else Msg.IMPORT_SUBMIT

        /** The summary line: the items, their size and where they go. */
        val summary: Notice? get() = if (nodes > 0) Notice(Msg.IMPORT_SUMMARY, nodes, Bytes(bytes), target) else null
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(State(target = targetName, canAsFile = asFile != null))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private var opened: SubtreeImport.Opened? = null
    private var running: Job? = null

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** Why a download is refused, in the member's words; a path or a node's name beside it. */
    private fun refusal(e: Throwable): List<Notice> {
        val r = (e as? ImportRefused) ?: return listOf(Notice(Msg.IMPORT_INVALID))
        return listOf(Notice(refusedWord(r.refusal))) + listOfNotNull(r.detail?.let { Notice(Msg.IMPORT_DETAIL, it) })
    }

    /** Reads the download and makes its dry run, then asks whether it fits; once, as the dialog opens. */
    fun start() {
        scope.launch {
            try {
                val o = SubtreeImport.open(input)
                val plan = SubtreeImport.plan(o.manifest, kinds)
                opened = o
                _state.update {
                    it.copy(
                        step = Step.READY, nodes = plan.nodes, bytes = plan.bytes, root = o.manifest.root.name,
                        leftOut = plan.groups.map { g -> Notice(skipWord(g.reason), g.nodes, g.application) },
                        said = if (plan.nodes == 0) listOf(Notice(Msg.IMPORT_NOTHING)) else emptyList(),
                    )
                }
                if (plan.bytes > 0) {
                    val fits = runCatching { target.fits(plan.bytes) }.getOrNull()
                    _state.update { it.copy(fits = fits, said = if (fits == false) listOf(Notice(Msg.IMPORT_NO_ROOM, Bytes(plan.bytes))) else it.said) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = Step.INVALID, said = refusal(e)) }
            }
        }
    }

    /** *Import* (or *Import the rest*): everything the dry run does not leave out, under the level. */
    fun import() {
        val o = opened ?: return
        if (!current.canImport || running != null) return
        _state.update { it.copy(step = Step.RUNNING, said = emptyList(), progress = SubtreeImport.Progress(0, it.nodes, 0, it.bytes)) }
        running = scope.launch {
            try {
                val r = SubtreeImport.run(o, scopeId, parentId, target, kinds, tokenInput, checkRoom = false) { p -> _state.update { it.copy(progress = p) } }
                changed()
                val line = if (r.skipped.isNotEmpty()) Notice(Msg.IMPORT_IMPORTED_LEFT_OUT, current.root, r.skipped.joinToString(", ") { it.name })
                else Notice(Msg.IMPORT_IMPORTED, current.root)
                _state.update { it.copy(step = Step.DONE, said = listOf(line), nodeId = r.nodeId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                changed()
                throw e
            } catch (e: Exception) {
                changed()
                _state.update { it.copy(step = Step.READY, progress = null, said = failure(e)) }
            } finally {
                running = null
            }
        }
    }

    /** What a failed run says (the web's `failureText`). */
    private fun failure(e: Throwable): List<Notice> {
        val f = e as? SubtreeImport.Failed ?: return refusal(e)
        val cause = f.cause
        val how = if (f.rolledBack) Notice(Msg.IMPORT_FAILED, f.done, f.total) else Notice(Msg.IMPORT_FAILED_LEFT, f.done, f.total, current.root)
        return when {
            cause is NodeImport.Failed && cause.code == "quota_exceeded" -> listOf(Notice(Msg.IMPORT_QUOTA), how)
            cause is ImportRefused && cause.refusal == ImportRefused.Refusal.CHECKSUM -> listOf(Notice(Msg.IMPORT_CHECKSUM), how)
            else -> listOf(how)
        }
    }

    /** *Upload as a file*: the import stops before it began, and the file goes the ordinary way. */
    fun uploadAsFile() {
        val up = asFile ?: return
        if (current.step == Step.RUNNING) return
        up()
        finish(letGo = false)
    }

    /** *Cancel*: a run stops and what it made goes to the trash; otherwise the dialog closes, nothing written. */
    fun cancel() {
        running?.cancel()
        finish(letGo = true)
    }

    /** Closed by the app (a done import's dialog, a window gone). */
    fun close() = cancel()

    private fun finish(letGo: Boolean) {
        if (current.closed) return
        _state.update { it.copy(closed = true) }
        // a run cancelled above trashes what it made on its own way out; the scope stays until then
        scope.launch {
            running?.join()
            if (letGo) runCatching { closed() }
            input.close()
            scope.cancel()
        }
    }

    companion object {
        fun refusedWord(r: ImportRefused.Refusal): Msg = when (r) {
            ImportRefused.Refusal.NOT_AN_EXPORT -> Msg.IMPORT_REFUSED_NOT_AN_EXPORT
            ImportRefused.Refusal.NEWER_VERSION -> Msg.IMPORT_REFUSED_NEWER_VERSION
            ImportRefused.Refusal.INVALID_MANIFEST -> Msg.IMPORT_REFUSED_INVALID_MANIFEST
            ImportRefused.Refusal.TOO_LARGE -> Msg.IMPORT_REFUSED_TOO_LARGE
            ImportRefused.Refusal.UNSAFE_PATH -> Msg.IMPORT_REFUSED_UNSAFE_PATH
            ImportRefused.Refusal.DAMAGED_ARCHIVE -> Msg.IMPORT_REFUSED_DAMAGED_ARCHIVE
            ImportRefused.Refusal.PAYLOAD_MISSING -> Msg.IMPORT_REFUSED_PAYLOAD_MISSING
            ImportRefused.Refusal.SIZE_MISMATCH -> Msg.IMPORT_REFUSED_SIZE_MISMATCH
            ImportRefused.Refusal.CHECKSUM -> Msg.IMPORT_REFUSED_CHECKSUM
            ImportRefused.Refusal.NOTHING_TO_IMPORT -> Msg.IMPORT_REFUSED_NOTHING_TO_IMPORT
        }

        fun skipWord(reason: String): Msg = if (reason == SubtreeImport.NEWER_CONTENT) Msg.IMPORT_SKIP_NEWER_CONTENT else Msg.IMPORT_SKIP_UNKNOWN_APPLICATION
    }
}
