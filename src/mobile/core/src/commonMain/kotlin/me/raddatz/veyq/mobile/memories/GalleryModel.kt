package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg

/**
 * A Memory's or a chapter's page (Memories' `MemoryDetail.vue` with `MediaGrid.vue`, ADR 0130 decision 2): the chapters a
 * Memory holds, then its media as [Row]s in the grid's order ([MemoryMedia.order]), each tile the grid's tier
 * ([MemoryMedia.Item.gridBlob]) read when the platform asks for it ([tile]) — the core caches no bytes; decoding and the
 * decoded picture's cache are the platform's (ADR 0130 decision 1).
 *
 * **When it reads anew is the core's:** on each revision of the shell's lists ([Door.revisions]), and when a medium this
 * device uploaded into this node is in ([Door.uploaded], [MediaUploads]); nothing else re-reads it. A lightbox opened on it
 * ([lightbox]) follows each read. The details' uploaders are read once per gallery for every owner key it shows.
 *
 * Each app draws [state] (or [watch]); [close] when the page goes (a lightbox on it closes with it).
 */
class GalleryModel(
    val node: NodeAt,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Where an original's save runs ([Lightbox.save]): not the page's own [scope], so a save outlives its page. */
    private val saves: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the page reads ([me.raddatz.veyq.mobile.navigation.ShellModel.galleryModel] over a tenant, a test over its own). */
    interface Door {
        /** The node's gallery as listed now (`MemoryMedia.list`); null or a throw where it did not read. */
        suspend fun gallery(): MemoryMedia.Gallery?

        /** Every node of the node's space this device opens (a Memory's chapters are among them). */
        suspend fun scopeNodes(): List<NodeRef>

        /** [blob] of [item], whole (a tile, a lightbox's picture); null where it does not read. */
        suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray?

        /** [item]'s original read at any offset (a video played); null where it does not open. */
        fun reader(item: MemoryMedia.Item): RangeReader?

        /** [item]'s original handed to [sink] a frame at a time; false where it did not open. */
        suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean

        /** [item]'s heavy EXIF tier; null where none was sealed. */
        suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif?

        /** Who added each of [items], by owner key (`MediaDetails.uploaders`). */
        suspend fun uploaders(items: List<MemoryMedia.Item>): Map<String, MediaDetails.Uploader>

        /** The picked [sources] queued into this node ([MediaUploads.upload]); how many were. */
        fun upload(sources: List<UploadSource>): Int

        /** Each revision of the shell's lists (its first value: the lists as they are when the page opens). */
        val revisions: Flow<Any?>

        /** The node ids a medium this device uploaded went into, each as it is in ([MediaUploads.uploaded]). */
        val uploaded: Flow<String>
    }

    /** One medium as the page shows it. */
    class Row internal constructor(internal val item: MemoryMedia.Item) {
        val mediaId: String get() = item.mediaId
        /** The original's sealed name (a claim). */
        val name: String? get() = item.name
        /** The original's sealed type (a claim; a player is handed it). */
        val type: String? get() = item.type
        val isVideo: Boolean get() = item.isVideo
        /** The operator blocked it: no bytes are served, the lightbox says [said]. */
        val blocked: Boolean get() = item.blocked
        /** The blob the grid draws ([tile] reads it; a platform's cache keys by it); null: the grid shows an icon. */
        val tile: String? get() = item.gridBlob?.blobId?.takeIf { !item.blocked }
        /** The blob the lightbox shows ([Lightbox.picture] reads it); null for a video without a poster and a blocked one. */
        val picture: String? get() = item.lightboxBlob.blobId.takeIf { item.hasPicture && !item.blocked }
        /** The effective capture time (epoch ms), the platform formats it; null where none is known. */
        val capturedAt: Long? get() = item.meta?.effectiveCapturedAt
        val width: Int? get() = item.meta?.width
        val height: Int? get() = item.meta?.height
        /** What the lightbox says in its picture's place: a blocked medium's words; null otherwise. */
        val said: Msg? get() = if (item.blocked) Msg.MEMORY_BLOCKED else null
    }

    data class State(
        /** The media in the grid's order; null while the first read runs. */
        val rows: List<Row>? = null,
        /** A Memory's chapters (none for a chapter). */
        val chapters: List<NodeRef> = emptyList(),
        /** The gallery did not read (the last read stays shown). */
        val failed: Boolean = false,
    ) {
        val count: Int get() = rows?.size ?: 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private val reading = Mutex()

    init {
        scope.launch { door.revisions.collect { read() } }
        scope.launch { door.uploaded.collect { if (it == node.nodeId) read() } }
    }

    /** The gallery read anew (a pull to refresh). */
    fun reload() {
        scope.launch { read() }
    }

    private suspend fun read() = reading.withLock {
        val read = runCatching { door.gallery() }.getOrNull()
        val chapters = if (node.kind == MEMORY) {
            runCatching { MediaDetails.chapters(node.kind, node.nodeId, door.scopeNodes()) }.getOrNull()
        } else {
            emptyList()
        }
        _state.update { s ->
            val withChapters = if (chapters != null) s.copy(chapters = chapters) else s
            if (read == null) withChapters.copy(failed = true) else withChapters.copy(rows = read.items.map(::Row), failed = false)
        }
    }

    private fun row(mediaId: String): Row? = current.rows?.firstOrNull { it.mediaId == mediaId }

    /** The grid's tier of medium [mediaId], whole, for the platform to decode; null where it has none or it did not read. */
    suspend fun tile(mediaId: String): ByteArray? {
        val row = row(mediaId) ?: return null
        val blob = row.item.gridBlob?.takeIf { row.tile != null } ?: return null
        return runCatching { door.read(row.item, blob) }.getOrNull()
    }

    /** The lightbox's tier of medium [mediaId] (its preview, else the original); null where it shows none. */
    internal suspend fun picture(mediaId: String): ByteArray? {
        val row = row(mediaId)?.takeIf { it.picture != null } ?: return null
        return runCatching { door.read(row.item, row.item.lightboxBlob) }.getOrNull()
    }

    /** The picked [sources] added to this Memory or chapter (the app's one upload queue); how many were queued. */
    fun upload(sources: List<UploadSource>): Int = door.upload(sources)

    // ---- the details: the EXIF tier once per medium, the uploaders once per gallery ----

    private val exifs = HashMap<String, MediaDetails.Exif?>()
    private val uploaders = HashMap<String, MediaDetails.Uploader>()
    private val asked = HashSet<String>()
    private val details = Mutex()

    /**
     * Medium [mediaId]'s details: its rows with its EXIF tier (read once, when first shown) and who added it — the
     * uploaders read once for every owner key the gallery shows (one request, not one per medium), a key not read yet
     * (a medium that came since) asked with the next. Null where the medium is not in the gallery.
     */
    internal suspend fun details(mediaId: String): Lightbox.Details? = details.withLock {
        val row = row(mediaId) ?: return@withLock null
        val item = row.item
        val exif = if (mediaId in exifs) exifs[mediaId] else runCatching { door.exif(item) }.getOrNull().also { exifs[mediaId] = it }
        val key = item.ownerKey
        if (key != null && key !in asked) {
            val all = current.rows.orEmpty().map { it.item }.filter { it.ownerKey != null && it.ownerKey !in asked }
            val read = runCatching { door.uploaders(all) }.getOrNull()
            if (read != null) {
                uploaders.putAll(read)
                // a member the space's list did not read is left out of the answer: asked again with the next
                asked.addAll(read.keys)
            }
        }
        val rows = MediaDetails.rows(item, exif)
        Lightbox.Details(mediaId, rows, key?.let { uploaders[it] }, MediaDetails.uploaderAt(rows))
    }

    internal fun reader(row: Row): RangeReader? = if (row.isVideo && !row.blocked) door.reader(row.item) else null

    internal fun save(row: Row, index: Int, into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? {
        if (row.blocked) return null
        val item = row.item
        val name = MediaDetails.downloadName(item, node.name, index)
        return FileSave(name, item.original.size, saves) { progress ->
            into { sink ->
                var written = 0L
                door.stream(item) { frame ->
                    sink(frame)
                    written += frame.size
                    progress(written)
                }
            }
        }
    }

    // ---- the lightbox ----

    private var open: Lightbox? = null

    /** The lightbox opened at row [index] (one at a time: an open one closes). */
    fun lightbox(index: Int): Lightbox {
        open?.close()
        return Lightbox(this, index, scope).also { open = it }
    }

    /** The page went: nothing more is read or watched; its lightbox closes (its reader with it). */
    fun close() {
        open?.close()
        open = null
        scope.cancel()
    }

    companion object {
        const val MEMORY = "MEMORY"
        const val CHAPTER = "CHAPTER"
    }
}

/**
 * The lightbox over a [GalleryModel] (the web's `MediaLightbox.vue`): the current medium by index over the gallery's rows,
 * kept by its id when the gallery reads anew — a removed one gives way to the one now at its place (its neighbour) —, its
 * picture read when asked ([picture]), a video's original opened for the player ([play]) and **closed by the model** when
 * the lightbox pages away, stops, closes or its gallery does; the details ([details]); the original saved ([save]).
 * Zoom is the platform's; the model knows nothing of it.
 */
class Lightbox internal constructor(private val gallery: GalleryModel, start: Int, parent: CoroutineScope) {
    /** A medium's details as the lightbox shows them: its rows, who added it, where that row stands ([MediaDetails.uploaderAt]). */
    class Details(val mediaId: String, val rows: List<MediaDetails.Row>, val uploader: MediaDetails.Uploader?, val uploaderAt: Int)

    data class State(
        /** The gallery's rows, paged through. */
        val rows: List<GalleryModel.Row>,
        /** The current row's index; -1 where the gallery holds none. */
        val index: Int,
        /** The current video's original, open while it plays; null otherwise. */
        val reader: RangeReader? = null,
        /** The last save of an original started here (it outlives the lightbox). */
        val save: FileSave? = null,
        val closed: Boolean = false,
    ) {
        val count: Int get() = rows.size
        val row: GalleryModel.Row? get() = rows.getOrNull(index)
        val playing: Boolean get() = reader != null
    }

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>
    val current: State get() = _state.value

    // the gallery's own dispatcher, a child of its scope: it goes when the gallery does
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    init {
        val rows = gallery.current.rows.orEmpty()
        _state = MutableStateFlow(State(rows, if (rows.isEmpty()) -1 else start.coerceIn(0, rows.size - 1)))
        state = _state.asStateFlow()
        scope.launch { gallery.state.collect { s -> s.rows?.let(::follow) } }
    }

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The gallery read anew: the current medium kept by its id, else the one now at its place. */
    private fun follow(rows: List<GalleryModel.Row>) {
        var stale: RangeReader? = null
        _state.update { s ->
            if (s.closed || s.rows === rows) return@update s
            val id = s.row?.mediaId
            val kept = rows.indexOfFirst { it.mediaId == id }
            val index = when {
                rows.isEmpty() -> -1
                kept >= 0 -> kept
                else -> s.index.coerceIn(0, rows.size - 1)
            }
            val same = kept >= 0
            if (!same) stale = s.reader
            s.copy(rows = rows, index = index, reader = if (same) s.reader else null)
        }
        stale?.close()
    }

    /** Paged to row [index]: a video playing on the page left is stopped, its reader closed. */
    fun go(index: Int) {
        var stale: RangeReader? = null
        _state.update { s ->
            if (s.closed || index !in s.rows.indices || index == s.index) return@update s
            stale = s.reader
            s.copy(index = index, reader = null)
        }
        stale?.close()
    }

    /** Medium [mediaId]'s picture in the lightbox (its preview, else its original), whole; null where it shows none. */
    suspend fun picture(mediaId: String): ByteArray? = gallery.picture(mediaId)

    /**
     * The current medium's original opened for the player, where it is a video not blocked (null otherwise); held as
     * [State.reader] until the lightbox pages away, [stop]s, closes or its gallery does.
     */
    fun play(): RangeReader? {
        val s = current
        if (s.closed) return null
        s.reader?.let { return it }
        val row = s.row ?: return null
        val reader = gallery.reader(row) ?: return null
        var kept = false
        _state.update { now ->
            if (now.closed || now.row?.mediaId != row.mediaId || now.reader != null) now
            else now.copy(reader = reader).also { kept = true }
        }
        if (!kept) {
            reader.close()
            return current.reader
        }
        return reader
    }

    /** The player went (its view left): the reader closes. */
    fun stop() {
        var stale: RangeReader? = null
        _state.update { s -> stale = s.reader; s.copy(reader = null) }
        stale?.close()
    }

    /** Medium [mediaId]'s details ([GalleryModel]'s reads: the EXIF tier once per medium, the uploaders once per gallery). */
    suspend fun details(mediaId: String): Details? = gallery.details(mediaId)

    /** What the current medium's original is saved as (`MediaDetails.downloadName`), what a save dialog proposes; null for none. */
    fun downloadName(): String? {
        val s = current
        val row = s.row?.takeIf { !it.blocked } ?: return null
        return MediaDetails.downloadName(row.item, gallery.node.name, s.index)
    }

    /**
     * The current medium's original saved through [into] (as `FileModel.save`: the platform opens where the person chose
     * and hands its sink — natively core's `FileSink`, which removes a copy that did not finish — and answers whether it
     * kept it), under [downloadName], a frame at a time; it outlives the lightbox. Null for a blocked medium.
     */
    fun save(into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? {
        val s = current
        val row = s.row ?: return null
        val save = gallery.save(row, s.index, into) ?: return null
        _state.update { it.copy(save = save) }
        return save
    }

    /** The lightbox went: its reader closes, nothing more is followed. */
    fun close() {
        var stale: RangeReader? = null
        _state.update { s -> stale = s.reader; s.copy(reader = null, closed = true) }
        stale?.close()
        scope.cancel()
    }
}
