package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.memories.music.MusicUpload
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * Memories' uploads over the app's one [UploadQueue] (the web's `addMedia` through `uploadQueue.ts`; ADR 0136 decision 2):
 * picked photos and videos queued into a Memory or a chapter as [MediaUpload.Job]s, the platform's [MediaUpload.Deriver]
 * making their tiers, and each one's **settle**, the core's:
 *
 * - a medium in names its node on [uploaded] (the gallery of that node reads itself anew, [GalleryModel]);
 * - a batch's end settles its node ([Door.settled]: the `auto` range and place re-derived and re-sealed where they
 *   changed, ADR 0143 D5), then reads the lists anew ([Door.changed]: the Memory's date range shows);
 * - a picked file the queue is done with is handed back ([Door.left]) once no run reads it again — its item done or
 *   cancelled, or gone from the queue after it failed (abandoned: its batch closed with it failed) —, and every one still
 *   held when the tenant closes ([close], beside the queue's own clear). A platform that copied a pick (Apple's Photos
 *   picker) removes the copy there; one that reads the picker's own file in place (Android) has nothing to do. A failed
 *   item's file is kept for its retry. What is done with a file is read from the queue's state, so a successor
 *   ([handOver]: the same tenant opened again over the same queue) follows what its predecessor queued.
 *
 * The tray stays `DepotUploads`', which mirrors the whole queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploads(
    private val queue: UploadQueue,
    private val door: Door,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What the uploads make and tell ([me.raddatz.veyq.mobile.navigation.ShellModel.mediaUploads], a test's own). */
    interface Door {
        /** [source] added to node [nodeId] of [scopeId], as a job of the queue ([MediaUpload.Job]). */
        fun job(scopeId: String, nodeId: String, source: UploadSource): UploadQueue.Job

        /** A checked music track added to node [nodeId] of [scopeId], as a job of the queue ([MusicUpload.Job]). */
        fun musicJob(scopeId: String, nodeId: String, track: MusicUpload.Check.Taken): UploadQueue.Job

        /** A checked GPX track and its label added to node [nodeId] of [scopeId], as a job of the queue ([TrackUpload.Job]). */
        fun trackJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken, label: me.raddatz.veyq.mobile.memories.music.TrackLabel.Label): UploadQueue.Job =
            throw UnsupportedOperationException("no tracks here")

        /** A batch into node [nodeId] of [scopeId] ended: its `auto` range and place settled (before [changed]). Absent: nothing. */
        suspend fun settled(scopeId: String, nodeId: String) {}

        /** A batch ended: the lists are read anew. */
        fun changed()

        /** The queue is done with [source]: no run reads it again. */
        fun left(source: UploadSource)
    }

    private val lane = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)

    private val _uploaded = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Each node a medium of this device went into, as it is in. */
    val uploaded: Flow<String> = _uploaded.asSharedFlow()

    /** The picked files the queue holds, by their job (touched on the lane only). */
    private val held = HashMap<UploadQueue.Job, UploadSource>()

    /** The held jobs the queue showed failed: one gone from the queue after it is abandoned. */
    private val failed = HashSet<UploadQueue.Job>()

    init {
        scope.launch { queue.state.collect(::follow) }
    }

    /**
     * The photos and videos queued into node [nodeId], in the order queued, as its gallery's placeholder tiles (ADR 0145
     * decision 3): every medium job of a batch into that node, whatever its state — the gallery leaves out what it lists.
     * Music and GPX tracks are another view's, not the grid's.
     */
    fun tiles(nodeId: String): Flow<List<UploadTile>> = queue.state.map { q ->
        q.batches.filter { it.key == nodeId }.flatMap { it.items }.mapNotNull { item ->
            val job = item.job as? MediaUpload.Job ?: return@mapNotNull null
            UploadTile(item.id, item.name, MediaUpload.claimsVideo(job.picked.type, job.picked.name), item.status, item.progress, job.mediaId)
        }
    }.distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { i -> a[i].id == b[i].id && a[i].status == b[i].status && a[i].progress == b[i].progress && a[i].mediaId == b[i].mediaId } }

    /** What the queue shows of each held job: done or cancelled hands its file back, failed waits, failed and gone is abandoned. */
    private fun follow(q: UploadQueue.State) {
        val items = q.batches.flatMap { it.items }.associateBy { it.job }
        for (job in held.keys.toList()) {
            val item = items[job]
            when {
                item == null -> if (job in failed) leave(job)
                item.status == UploadQueue.Status.DONE || item.status == UploadQueue.Status.CANCELLED -> leave(job)
                item.status == UploadQueue.Status.FAILED -> failed += job
                else -> failed -= job // retried
            }
        }
    }

    /**
     * The picked [sources] queued into Memory or chapter [nodeId] of [scopeId], one batch labelled [label] (its name).
     * The number queued; a source that is no medium ([MediaUpload.isMedia]) is handed back at once and not queued.
     */
    fun upload(scopeId: String, nodeId: String, label: String, sources: List<UploadSource>): Int {
        val (media, others) = sources.partition { MediaUpload.isMedia(it.type, it.name) }
        others.forEach { door.left(it) }
        if (media.isEmpty()) return 0
        return enqueue(scopeId, nodeId, label, media.map { door.job(scopeId, nodeId, it) to it })
    }

    /**
     * Music tracks checked on the device ([MusicUpload.check]) queued into Memory or chapter [nodeId] of [scopeId],
     * one batch labelled [label]; the number queued. Each is handed back as a medium's is, once the queue is done with it.
     */
    fun uploadMusic(scopeId: String, nodeId: String, label: String, tracks: List<MusicUpload.Check.Taken>): Int {
        if (tracks.isEmpty()) return 0
        return enqueue(scopeId, nodeId, label, tracks.map { door.musicJob(scopeId, nodeId, it) to it.source })
    }

    /**
     * GPX tracks checked on the device ([me.raddatz.veyq.mobile.memories.tracks.TrackUpload.check]) queued with the labels
     * asked for them into Memory or chapter [nodeId] of [scopeId], one batch labelled [label]; the number queued.
     */
    fun uploadTracks(
        scopeId: String, nodeId: String, label: String,
        tracks: List<Pair<me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken, me.raddatz.veyq.mobile.memories.music.TrackLabel.Label>>,
    ): Int {
        if (tracks.isEmpty()) return 0
        return enqueue(scopeId, nodeId, label, tracks.map { (t, l) -> door.trackJob(scopeId, nodeId, t, l) to t.source })
    }

    private fun enqueue(scopeId: String, nodeId: String, label: String, jobs: List<Pair<UploadQueue.Job, UploadSource>>): Int {
        scope.launch {
            jobs.forEach { (job, source) -> held[job] = source }
            queue.enqueue(
                jobs.map { it.first }, label, nodeId,
                onSettled = { item -> if (item.status == UploadQueue.Status.DONE) _uploaded.tryEmit(nodeId) },
                onFinished = {
                    scope.launch {
                        runCatching { door.settled(scopeId, nodeId) }
                        door.changed()
                    }
                },
            )
        }
        return jobs.size
    }

    private fun leave(job: UploadQueue.Job) {
        failed -= job
        val source = held.remove(job) ?: return
        runCatching { door.left(source) }
    }

    /**
     * The same tenant opened again over the same queue: what this one holds goes on in [next], which follows and hands
     * each file back; nothing is handed back here, and this one follows nothing more.
     */
    fun handOver(next: MediaUploads) {
        scope.launch {
            val files = HashMap(held)
            val waiting = HashSet(failed)
            held.clear()
            failed.clear()
            next.scope.launch {
                next.held.putAll(files)
                next.failed.addAll(waiting)
                next.follow(next.queue.state.value)
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    companion object {
        /**
         * What is said of [count] picked files that were not taken ([upload] queues only a medium, `MediaUpload.isMedia`; a
         * path that did not open is none either), or null where every one was.
         */
        fun notTaken(count: Int): Notice? = if (count > 0) Notice(Msg.MEMORY_NOT_MEDIA, count) else null
    }

    /** The tenant closes: every picked file still held is handed back, nothing more is followed. */
    fun close() {
        scope.launch {
            held.keys.toList().forEach(::leave)
        }.invokeOnCompletion { scope.cancel() }
    }
}
