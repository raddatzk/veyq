package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The uploads as the shell shows them (the web's `UploadStatusMenu.vue`, Depot's `uploadFile.ts` and `uploadFolder.ts`;
 * ADR 0127 decision 2), a screen model over the app's one [UploadQueue]:
 *
 * - **files** picked into a level ([upload]) and a **folder** ([uploadFolder]): the walk's entries kept by
 *   [FolderUpload.keeps], what it left out said, the folders made while [State.preparing] names it, then its files queued;
 * - a file's **replace** ([replace]), expected over the content shown when Replace was chosen ([FileModel.chooseReplace]);
 * - each Depot item's **settle**: the lists read anew ([Door.changed]); a name a file holds offers that file's replace, a
 *   replace refused as stale "Replace anyway" — the item taken out of the tray, the offer said once ([State.said]) with its
 *   action ([take]);
 * - the **tray's acknowledge**: a batch all in, none failed, leaves by itself [UploadQueue.ACKNOWLEDGE_AFTER_MS] after it is
 *   ([UploadQueue.State.clean]), run here on the model's clock, never by a view; one with a failure waits for its close.
 *
 * What it says is [State.said], one at a time: the app shows it (a snackbar, an alert, a toast) and answers it — [take]
 * for its action, [seen] otherwise. Each app draws [state] (or [watch]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepotUploads(
    private val queue: UploadQueue,
    private val door: Door,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What the uploads read and make ([over] a shell, a test over a server it plays). */
    interface Door {
        /** [source] as a new file named [name] under [parentId] (null: the space's top) of [scopeId]; null without a tenant. */
        fun uploadJob(scopeId: String, parentId: String?, source: UploadSource, name: String): DepotFiles.UploadJob?

        /** The folders of a directory named [rootName] made under [parentId], its files handed back as jobs (`FolderUpload.prepare`). */
        suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<FolderUpload.Entry>): FolderUpload.Report

        /** File [nodeId] of [scopeId] as it is listed now (its content read anew); null where it is not. */
        suspend fun file(scopeId: String, nodeId: String): DepotFiles.File?

        /** The job that replaces [file]'s content with [source] over [expectedBlobId]. */
        fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?): DepotFiles.ReplaceJob

        /** Something was made or changed: the lists are read anew. */
        fun changed()
    }

    /** What the uploads say once: a folder upload's outcome (one line each), or a replace offered with its [action]. */
    class Said(val id: Long, val notices: List<Notice>, val offer: DepotFiles.Offer? = null) {
        /** The offer's button ("Replace", "Replace anyway"); null where it offers nothing. */
        val action: Msg? get() = offer?.action
    }

    data class State(
        val queue: UploadQueue.State = UploadQueue.State(emptyList()),
        /** The name of a picked directory whose folders are being made before its files queue up. */
        val preparing: String? = null,
        /** What waits to be said, in order: [said] is the first. */
        val saying: List<Said> = emptyList(),
    ) {
        /** What the app shows now (one at a time); null where nothing waits. */
        val said: Said? get() = saying.firstOrNull()
    }

    private val lane = dispatcher.limitedParallelism(1)
    private val work = dispatcher
    private val scope = CoroutineScope(SupervisorJob() + lane)

    private val _state = MutableStateFlow(State(queue.state.value))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private var said = 0L
    private var acknowledging: Job? = null
    private var acknowledgeKey: List<Pair<String, Boolean>>? = null

    init {
        scope.launch {
            queue.state.collect { q ->
                _state.update { it.copy(queue = q) }
                // the tray's acknowledge, keyed by the batches and whether each is in: a change starts the moment anew
                val key = q.batches.map { it.id to it.finished }
                if (key != acknowledgeKey) {
                    acknowledgeKey = key
                    acknowledging?.cancel()
                    val clean = q.clean
                    acknowledging = if (clean.isEmpty()) null else scope.launch {
                        delay(UploadQueue.ACKNOWLEDGE_AFTER_MS)
                        clean.forEach { queue.acknowledge(it.id) }
                    }
                }
            }
        }
    }

    /** [notices] (and [offer]) to be said, after what waits already; on the model's lane, where its ids are counted. */
    private suspend fun say(notices: List<Notice>, offer: DepotFiles.Offer? = null) = withContext(lane) {
        if (notices.isEmpty()) return@withContext
        val next = Said(++said, notices, offer)
        _state.update { it.copy(saying = it.saying + next) }
    }

    /**
     * The picked [sources] queued as new files under [parentId] (null: the space's top) of [scopeId], one batch labelled
     * [label] (where they go): a name a file already holds offers its replace once it settles. The number queued.
     */
    fun upload(scopeId: String, parentId: String?, label: String, sources: List<UploadSource>): Int {
        val jobs = sources.mapNotNull { door.uploadJob(scopeId, parentId, it, it.name) }
        enqueue(jobs, label, parentId ?: scopeId)
        return jobs.size
    }

    /**
     * A picked directory uploaded under [parentId] of [scopeId]: what its walk [found] kept ([FolderUpload.keeps]; each link
     * and unreadable entry said), its folders made — [State.preparing] naming it ([FolderUpload.rootName] of [shownName] and
     * [path]) — and its files queued as one batch under the folder's name; a refusal or a folder not made said in Depot's
     * words. Runs off the caller's thread.
     */
    suspend fun uploadFolder(scopeId: String, parentId: String?, shownName: String?, path: String, found: List<FolderUpload.Found>) =
        withContext(work) {
            val rootName = FolderUpload.rootName(shownName, path)
            val walked = FolderUpload.walked(found)
            _state.update { it.copy(preparing = rootName) }
            val report = try {
                runCatching { door.prepareFolder(scopeId, parentId, rootName, walked.entries) }.getOrNull()
            } finally {
                _state.update { it.copy(preparing = null) }
            }
            door.changed()
            val outcome = if (report == null) listOf(Notice(Msg.DEPOT_FOLDER_FAILED, rootName)) else Notices.folderUpload(report)
            say(Notices.folderLeftOut(walked) + outcome)
            if (report?.outcome == FolderUpload.Outcome.READY) enqueue(report.jobs, report.name, report.rootId)
        }

    /** The header's replace of [file]'s content with the picked [source] (chosen first, [FileModel.chooseReplace]), queued. */
    fun replace(file: FileModel, source: UploadSource): Boolean {
        val job = file.replace(source) ?: return false
        enqueue(listOf(job), source.name, file.node.nodeId)
        return true
    }

    /** The offer [said] makes, taken (its action): the replace queued over the file as it is listed now. */
    fun take(said: Said) {
        val offer = said.offer
        drop(said.id)
        if (offer == null) return
        scope.launch {
            val file = withContext(work) { runCatching { door.file(offer.scopeId, offer.nodeId) }.getOrNull() } ?: return@launch
            enqueue(listOf(door.replaceJob(file, offer.source, offer.expectedBlobId)), offer.source.name, file.nodeId)
        }
    }

    /** [id] was shown and answered without its action: the next waits. */
    fun seen(id: Long) = drop(id)

    private fun drop(id: Long) = _state.update { s -> s.copy(saying = s.saying.filterNot { it.id == id }) }

    fun cancel(id: String) = queue.cancel(id)
    fun retry(id: String) = queue.retry(id)
    fun cancelAll() = queue.cancelAll()

    /** The tray closed batch [batchId] (one with a failure stays until this). */
    fun acknowledge(batchId: String) = queue.acknowledge(batchId)

    /** The model goes (a sign-out, a switch of tenant): nothing more is watched or said. */
    fun close() = scope.cancel()

    private fun enqueue(jobs: List<UploadQueue.Job>, label: String, key: String?) {
        if (jobs.isEmpty()) return
        queue.enqueue(jobs, label, key, onSettled = { item -> scope.launch { settled(item) } }, onFinished = null)
    }

    /** A Depot item settled: the lists read anew; its offer, where it makes one, said, and the item out of the tray. */
    private suspend fun settled(item: UploadQueue.Item) {
        door.changed()
        val offer = withContext(work) { runCatching { offerAfter(item) }.getOrNull() } ?: return
        queue.remove(item.id)
        say(listOf(offer.notice), offer)
    }

    /** [item]'s offer as the tenant lists it now: the colliding file's content read anew for its expected blob. */
    internal suspend fun offerAfter(item: UploadQueue.Item): DepotFiles.Offer? {
        val job = item.job as? DepotFiles.UploadJob
        val existing = job?.existing?.let { door.file(job.scopeId, it) }
        return DepotFiles.offerFor(item) { id -> existing?.takeIf { it.nodeId == id }?.blobId }
    }
}
