package me.raddatz.veyq.mobile.memories.tracks

import kotlinx.coroutines.CancellationException
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
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A Memory's or a chapter's **Map** view's tracks (the web's `useMemoryTracks.ts` with `MemoryTracks.vue`; ADR 0130,
 * ADR 0143 D8): the node's GPX tracks as its [gallery] lists them, each read from its original **only while the view
 * shows** ([active]) and **one at a time**, parsed on the device and kept while the page stays on its node — so a Memory
 * of many long tracks never holds two files' text at once —; ordered by their listed capture time, coloured by that order;
 * shown or hidden and selected on this view only (nothing is written). What may be done with them:
 *
 * - **add** picked GPX files ([add]): each checked on the device and refused with its words; the accepted ones' name and
 *   activity asked ([State.asking]) before anything is uploaded — [confirm] queues them with their labels sealed at birth,
 *   [cancel] uploads nothing;
 * - **edit** a track's label ([edit], [TrackLabel.relabel]) where the medium's meta may be written; **download** its GPX
 *   ([save], the gallery's save); **remove** it ([remove], the gallery's trash with its Undo); **report** it
 *   ([reportSheet], an ITEM).
 *
 * Offline (ADR 0143 D9) the list shows from the kept originals, read-only; there is no map.
 */
class TrackList(
    val gallery: GalleryModel,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the view does beyond the gallery ([me.raddatz.veyq.mobile.navigation.ShellModel.trackList], a test's own). */
    interface Door {
        /** A picked file checked as a track, the node holding [held] already ([TrackUpload.check]). */
        suspend fun check(source: UploadSource, held: Int): TrackUpload.Check

        /** Checked tracks queued into the node with their labels; how many were. */
        fun queue(tracks: List<Pair<TrackUpload.Check.Taken, TrackLabel.Label>>): Int

        /** A picked file not taken: handed back at once. */
        fun left(source: UploadSource)

        /** [item] labelled [label] ([TrackLabel.relabel]). */
        suspend fun relabel(item: MemoryMedia.Item, label: TrackLabel.Label): TrackLabel.Outcome

        /** [item] reported as an ITEM. */
        suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String): NodeReport.Sent

        /** Whether the server takes reports at all. */
        suspend fun reportOffered(): Boolean

        /** The tenant's origin (the report sheet's). */
        val origin: String
    }

    enum class Status { LOADING, READY, FAILED }

    /** One track as the view lists and draws it. */
    data class Entry(
        val mediaId: String,
        /** The name a member gave it, else its file's own, else its file name; null: said "Track [number]". */
        val name: String?,
        /** Its place in the list, from 1 (what an unnamed one is called by). */
        val number: Int,
        /** What the name falls back to without the member's: the edit's hint. */
        val fileTrackName: String?,
        /** The name a member gave it; null: named by its file. */
        val ownName: String?,
        /** Its activity, where this build knows it; null: none (drawn solid). */
        val activity: String?,
        /** The dash its line is drawn with ([TrackLabel.dash]); null: solid. */
        val dash: List<Int>?,
        /** The file name it was picked under (the download's). */
        val fileName: String?,
        /** Its colour (ARGB), by its place in the list. */
        val color: Long,
        val status: Status,
        val stats: Gpx.Stats? = null,
        /** The facts the list says beside it ([TrackFacts]). */
        val facts: List<TrackFacts.Fact> = emptyList(),
        /** Each segment as drawn (simplified). */
        val lines: List<List<LatLng>> = emptyList(),
        val blocked: Boolean = false,
    )

    /** What the view offers, from the gallery's rights (who looks, online or not). */
    data class Rights(val add: Boolean = false, val edit: Boolean = false, val download: Boolean = false, val report: Boolean = false)

    /** An accepted file waiting for its label before it is uploaded. */
    class Asked internal constructor(internal val taken: TrackUpload.Check.Taken) {
        val fileName: String get() = taken.source.name

        /** The hint of the name field: what it is named by without one. */
        val fileTrackName: String? get() = taken.fileTrackName
    }

    data class State(
        /** The tracks; null while the gallery's first read runs. */
        val entries: List<Entry>? = null,
        val hidden: Set<String> = emptySet(),
        val selected: String? = null,
        val rights: Rights = Rights(),
        val offline: Boolean = false,
        val checking: Boolean = false,
        /** Accepted files whose labels are asked now ([confirm] or [cancel]); empty when none is. */
        val asking: List<Asked> = emptyList(),
        /** What the last add or edit left to say; null once seen. */
        val said: Notice? = null,
    ) {
        /** The tracks drawn now: shown and read. */
        val drawn: List<Entry> get() = entries.orEmpty().filter { it.mediaId !in hidden && it.status == Status.READY }

        /**
         * What the Map view frames as it opens ([me.raddatz.veyq.mobile.memories.place.MemoryLocation.opening]): the photos'
         * [gps] points and every drawn track's — null while the gallery's first read or a track's read runs, so the map
         * waits for its framing instead of opening on the world first (inbox #945).
         */
        fun framed(gps: List<LatLng>): List<LatLng>? =
            if (entries == null || entries.any { it.status == Status.LOADING }) null else gps + drawn.flatMap { it.lines.flatten() }
    }

    private class Read(val status: Status, val name: String? = null, val stats: Gpx.Stats? = null, val lines: List<List<LatLng>> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** What was read of each track, by its id (replaced whole: read from any thread). */
    private val reads = MutableStateFlow<Map<String, Read>>(emptyMap())
    private var active = false
    private var pumping: Job? = null
    private var reports = false

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        scope.launch {
            reports = runCatching { door.reportOffered() }.getOrDefault(false)
            refresh(gallery.current)
        }
        scope.launch {
            gallery.state.collect { g ->
                refresh(g)
                pump()
            }
        }
    }

    private fun rightsOf(g: GalleryModel.State): Rights {
        val r = g.rights
        val online = g.offline == null
        return Rights(add = r.upload && online, edit = r.editMeta && online, download = r.download, report = reports && online)
    }

    /** The entries in the list's order: by listed capture time, then id, so a track keeps its colour as others are read. */
    private fun refresh(g: GalleryModel.State) {
        val tracks = g.tracks.sortedWith(compareBy<MemoryMedia.Item> { it.meta?.effectiveCapturedAt ?: 0L }.thenBy { it.mediaId })
        val entries = tracks.mapIndexed { index, item ->
            val read = reads.value[item.mediaId]
            val fileTrackName = Gpx.trackName(read?.takeIf { it.status == Status.READY }?.name, item.name)
            val own = item.meta?.trackName
            val activity = TrackLabel.activityOf(item.meta?.trackActivity)
            Entry(
                mediaId = item.mediaId,
                name = own ?: fileTrackName,
                number = index + 1,
                fileTrackName = fileTrackName,
                ownName = own,
                activity = activity,
                dash = TrackLabel.dash(activity),
                fileName = item.name,
                color = Gpx.color(index),
                status = if (item.blocked) Status.FAILED else read?.status ?: Status.LOADING,
                stats = read?.stats,
                facts = read?.stats?.let(TrackFacts::of).orEmpty(),
                lines = read?.lines.orEmpty(),
                blocked = item.blocked,
            )
        }
        val ids = entries.map { it.mediaId }.toSet()
        _state.update { s ->
            s.copy(
                entries = if (g.rows == null && g.tracks.isEmpty()) null else entries,
                hidden = s.hidden intersect ids,
                selected = s.selected?.takeIf { it in ids },
                rights = rightsOf(g),
                offline = g.offline != null,
            )
        }
    }

    /** Whether the Map view is on screen: nothing is read before it is. */
    fun active(on: Boolean) {
        active = on
        if (on) pump()
    }

    /** Reads the next unread track, one at a time, for as long as the view shows. */
    private fun pump() {
        if (!active || pumping?.isActive == true) return
        pumping = scope.launch {
            while (active) {
                val next = gallery.current.tracks.firstOrNull { it.mediaId !in reads.value && !it.blocked } ?: break
                reads.update { it + (next.mediaId to Read(Status.LOADING)) }
                val read = try {
                    val bytes = gallery.trackOriginal(next.mediaId) ?: throw IllegalStateException("unread")
                    val parsed = Gpx.read(bytes)
                    Read(Status.READY, parsed.name, Gpx.stats(parsed.segments), Gpx.lines(parsed))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Read(Status.FAILED)
                }
                reads.update { it + (next.mediaId to read) }
                refresh(gallery.current)
            }
        }
    }

    /** Track [mediaId] shown on the map or hidden (this view only). A hidden one is no longer selected. */
    fun setShown(mediaId: String, shown: Boolean) = _state.update { s ->
        s.copy(hidden = if (shown) s.hidden - mediaId else s.hidden + mediaId, selected = if (!shown && s.selected == mediaId) null else s.selected)
    }

    /** Track [mediaId] selected (drawn heavier and framed; a hidden one shown), or none. */
    fun select(mediaId: String?) = _state.update { s ->
        s.copy(selected = mediaId, hidden = if (mediaId != null) s.hidden - mediaId else s.hidden)
    }

    private val readOnly get() = Notice(Msg.OFFLINE_READ_ONLY)

    /**
     * Picked [sources] offered as tracks where the rights allow it: each checked on the device, one at a time, against the
     * node's room; what is refused is handed back and said, in the order picked; the rest wait for their labels
     * ([State.asking]). What was said.
     */
    suspend fun add(sources: List<UploadSource>): List<Notice> {
        if (!current.rights.add) {
            sources.forEach { runCatching { door.left(it) } }
            return if (current.offline) listOf(readOnly) else emptyList()
        }
        _state.update { it.copy(checking = true) }
        val said = ArrayList<Notice>()
        val taken = ArrayList<Asked>()
        try {
            var held = gallery.current.tracks.size
            for (source in sources) {
                val c = try {
                    door.check(source, held)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TrackUpload.Check.Refused(source, Gpx.Problem.INVALID)
                }
                when (c) {
                    is TrackUpload.Check.Taken -> {
                        taken += Asked(c)
                        held++
                    }
                    is TrackUpload.Check.Refused -> {
                        said += c.notice
                        runCatching { door.left(source) }
                    }
                }
            }
        } finally {
            _state.update { it.copy(checking = false, asking = it.asking + taken, said = said.lastOrNull() ?: it.said) }
        }
        return said
    }

    /** The asked files uploaded with [labels] (one per [State.asking], in its order); how many were queued. */
    fun confirm(labels: List<TrackLabel.Label>): Int {
        val asked = current.asking
        _state.update { it.copy(asking = emptyList()) }
        if (asked.isEmpty()) return 0
        if (!current.rights.add) {
            asked.forEach { runCatching { door.left(it.taken.source) } }
            return 0
        }
        return door.queue(asked.mapIndexed { i, a -> a.taken to (labels.getOrNull(i) ?: TrackLabel.Label(null, null)) })
    }

    /** *Cancel* on the labels: nothing is uploaded, every file handed back. */
    fun cancel() {
        val asked = current.asking
        _state.update { it.copy(asking = emptyList()) }
        asked.forEach { runCatching { door.left(it.taken.source) } }
    }

    /**
     * Track [mediaId]'s label set to [label] where the rights allow it; the gallery reads anew either way, so a peer's
     * label that won shows. Why not, or null.
     */
    suspend fun edit(mediaId: String, label: TrackLabel.Label): Notice? {
        if (current.offline) return readOnly
        if (!current.rights.edit) return null
        val item = gallery.current.tracks.firstOrNull { it.mediaId == mediaId } ?: return null
        val outcome = runCatching { door.relabel(item, label) }.getOrElse { TrackLabel.Outcome.Failed(null) }
        gallery.reload()
        val said = when (outcome) {
            is TrackLabel.Outcome.Done -> null
            is TrackLabel.Outcome.Conflict -> Notice(Msg.TRACK_EDIT_CONFLICT)
            is TrackLabel.Outcome.Failed -> Notice(Msg.TRACK_EDIT_FAILED)
        }
        if (said != null) _state.update { it.copy(said = said) }
        return said
    }

    /** Whether track [mediaId] offers its remove to whoever looks (the gallery's delete rights). */
    fun removes(mediaId: String): Boolean = gallery.deletes(mediaId)

    /** Track [mediaId] removed the gallery's way (a member's to the trash with its Undo). What to say at once, or null. */
    suspend fun remove(mediaId: String): Notice? = gallery.trash(mediaId)

    /** Track [mediaId]'s GPX saved through [into] (the gallery's save), where it may be downloaded. */
    fun save(mediaId: String, into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? =
        if (current.rights.download) gallery.saveTrack(mediaId, into) else null

    /** Track [mediaId]'s report (an ITEM) in the one report sheet; null where not offered. */
    fun reportSheet(mediaId: String): ReportSheet? {
        if (!current.rights.report) return null
        val item = gallery.current.tracks.firstOrNull { it.mediaId == mediaId } ?: return null
        return ReportSheet(gallery.node, object : ReportSheet.Door {
            override val origin get() = door.origin
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
                door.report(item, category, reason, contact)
        }, offersName = false)
    }

    fun seen() = _state.update { it.copy(said = null) }

    /** The view went: the parsed tracks are let go, a file still asked handed back. */
    fun close() {
        cancel()
        scope.cancel()
        reads.value = emptyMap()
    }
}
