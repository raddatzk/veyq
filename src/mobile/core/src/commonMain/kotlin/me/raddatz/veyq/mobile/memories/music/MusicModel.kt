package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.blob.FileSave
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A Memory's or a chapter's Music view (the web's `MemoryMusic.vue`, ADR 0142 decisions 6–11): its tracks as its
 * [gallery] lists them — sorted by title, the untitled numbered, each with its length, the one playing marked, one that did
 * not play on this device marked so for the session (the player's skipped ids) — and what may be done with them. It reads
 * nothing of its own: the gallery's listing, its live poll and its trash with Undo are the view's too, and the player is
 * the gallery's ([GalleryModel.music]).
 *
 * - **play** a track first, then the rest shuffled ([play]);
 * - **add** picked audio files ([add]): each checked on the device ([MusicUpload.check]) and refused with its words, the
 *   rest queued into this node, where the rights let whoever looks upload;
 * - **rename** ([rename], [TrackLabel]) where the medium's meta may be written; **download** ([save]) where the gallery's
 *   save is offered; **remove** ([remove]: the gallery's trash with its Undo); **report** ([reportSheet], an ITEM).
 *
 * Offline (ADR 0140 decision 7) it is read-only: no add, rename, remove or report; playing works.
 */
class MusicModel(
    val gallery: GalleryModel,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the view does beyond the gallery ([me.raddatz.veyq.mobile.navigation.ShellModel.musicModel], a test's own). */
    interface Door {
        /** A picked file checked as a track (`MusicUpload.check` with the platform's `Measure`). */
        suspend fun check(source: UploadSource): MusicUpload.Check

        /** Checked tracks queued into the node ([me.raddatz.veyq.mobile.memories.MediaUploads.uploadMusic]); how many were. */
        fun queue(tracks: List<MusicUpload.Check.Taken>): Int

        /** A picked file not taken: handed back at once (the platform lets a copy go). */
        fun left(source: UploadSource)

        /** [item] renamed to [name] ([TrackLabel.rename]). */
        suspend fun rename(item: MemoryMedia.Item, name: String?): TrackLabel.Outcome

        /** [item] reported as an ITEM ([me.raddatz.veyq.mobile.memories.MediumReport.report]). */
        suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String): NodeReport.Sent

        /** Whether the server takes reports at all (it publishes a moderation key). */
        suspend fun reportOffered(): Boolean

        /** The tenant's origin, under which its privacy notice is served (the report sheet's). */
        val origin: String
    }

    /** A track as the view shows it. */
    class Shown(val track: MusicTrack, val playing: Boolean, val unplayable: Boolean) {
        val mediaId: String get() = track.mediaId

        /** Its title; null where untitled — said as "Track [number]" ([Msg.MUSIC_UNTITLED]). */
        val title: String? get() = track.title
        val number: Int get() = track.number

        /** The file's whole length as the list shows it (`m:ss`), where its facts say it. */
        val length: String? get() = track.lengthMs?.let(MusicQueue::formatLength)
        val blocked: Boolean get() = track.blocked
    }

    /** What the view offers on its tracks, from the gallery's rights (who looks, online or not). */
    data class Rights(val add: Boolean = false, val rename: Boolean = false, val download: Boolean = false, val report: Boolean = false)

    data class State(
        /** The tracks; null while the gallery's first read runs. */
        val tracks: List<Shown>? = null,
        val rights: Rights = Rights(),
        /** Shown from the offline store, read-only. */
        val offline: Boolean = false,
        /** Picked files being checked before they are queued. */
        val checking: Boolean = false,
        /** What the last add or rename left to say; null once seen ([seen]). */
        val said: Notice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private var reports = false

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        val player = gallery.music?.player
        scope.launch {
            reports = runCatching { door.reportOffered() }.getOrDefault(false)
            _state.update { it.copy(rights = rightsOf(gallery.current)) }
        }
        scope.launch {
            val playerStates = player?.state ?: MutableStateFlow(me.raddatz.veyq.mobile.audio.MusicPlayer.State())
            combine(gallery.state, playerStates) { g, p -> g to p }.collect { (g, p) ->
                val now = p.nowPlaying?.takeIf { it.key == gallery.musicKey }
                _state.update { s ->
                    s.copy(
                        tracks = if (g.rows == null && g.music.isEmpty()) null else g.music.map { Shown(it, it.mediaId == now?.trackId, it.mediaId in p.skipped) },
                        rights = rightsOf(g),
                        offline = g.offline != null,
                    )
                }
            }
        }
    }

    private fun rightsOf(g: GalleryModel.State): Rights {
        val r = g.rights
        val online = g.offline == null
        return Rights(add = r.upload && online, rename = r.editMeta && online, download = r.download, report = reports && online)
    }

    /** Whether track [mediaId] offers its remove to whoever looks (the gallery's delete rights). */
    fun removes(mediaId: String): Boolean = gallery.deletes(mediaId)

    /** Track [mediaId] played first, then the rest shuffled (ADR 0135 decision 6). */
    fun play(mediaId: String) = gallery.playMusic(mediaId)

    private val readOnly get() = Notice(Msg.OFFLINE_READ_ONLY)

    /**
     * Picked [sources] added as tracks where the rights allow it: each checked on the device, one at a time; what is refused
     * is handed back and said ([MusicUpload.Check.Refused.notice], in the order picked); the rest queued as one batch.
     */
    suspend fun add(sources: List<UploadSource>): List<Notice> {
        if (!current.rights.add) {
            sources.forEach { runCatching { door.left(it) } }
            return if (current.offline) listOf(readOnly) else emptyList()
        }
        _state.update { it.copy(checking = true) }
        val said = ArrayList<Notice>()
        val taken = ArrayList<MusicUpload.Check.Taken>()
        try {
            for (source in sources) {
                when (val c = try {
                    door.check(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    MusicUpload.Check.Refused(source, MusicUpload.Problem.UNPLAYABLE)
                }) {
                    is MusicUpload.Check.Taken -> taken += c
                    is MusicUpload.Check.Refused -> {
                        said += c.notice
                        runCatching { door.left(source) }
                    }
                }
            }
            if (taken.isNotEmpty()) door.queue(taken)
        } finally {
            _state.update { it.copy(checking = false, said = said.lastOrNull() ?: it.said) }
        }
        return said
    }

    /**
     * Track [mediaId] renamed to [name] (blank: named by its file again), where the rights allow it; the gallery reads
     * anew either way, so a peer's name that won shows. Why not, or null.
     */
    suspend fun rename(mediaId: String, name: String?): Notice? {
        if (current.offline) return readOnly
        if (!current.rights.rename) return null
        val track = gallery.current.music.firstOrNull { it.mediaId == mediaId } ?: return null
        val outcome = runCatching { door.rename(track.item, name) }.getOrElse { TrackLabel.Outcome.Failed(null) }
        gallery.reload()
        val said = when (outcome) {
            is TrackLabel.Outcome.Done -> null
            is TrackLabel.Outcome.Conflict -> Notice(Msg.MUSIC_RENAME_CONFLICT)
            is TrackLabel.Outcome.Failed -> Notice(Msg.MUSIC_RENAME_FAILED)
        }
        if (said != null) _state.update { it.copy(said = said) }
        return said
    }

    /** Track [mediaId] removed the gallery's way (a member's to the trash with its Undo). What to say at once, or null. */
    suspend fun remove(mediaId: String): Notice? = gallery.trash(mediaId)

    /** Track [mediaId]'s file saved through [into] (as the lightbox saves a medium's), where it may be downloaded. */
    fun save(mediaId: String, into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? =
        if (current.rights.download) gallery.saveTrack(mediaId, into) else null

    /** Track [mediaId]'s report (an ITEM, ADR 0142 decision 11) in the one report sheet, whole only; null where not offered. */
    fun reportSheet(mediaId: String): ReportSheet? {
        if (!current.rights.report) return null
        val track = gallery.current.music.firstOrNull { it.mediaId == mediaId } ?: return null
        return ReportSheet(gallery.node, object : ReportSheet.Door {
            override val origin get() = door.origin
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
                door.report(track.item, category, reason, contact)
        }, offersName = false)
    }

    /** What [State.said] said was seen: it goes. */
    fun seen() = _state.update { it.copy(said = null) }

    /** The view went: nothing more is followed (the gallery and the player stay). */
    fun close() = scope.cancel()

    companion object {
        /** Whether a page in [gallery]'s state offers its Music view at all: it has music, or whoever looks may add some (ADR 0142 decision 6). */
        fun shown(gallery: GalleryModel.State): Boolean = gallery.music.isNotEmpty() || gallery.rights.upload
    }
}
