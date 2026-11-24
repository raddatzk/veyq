package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's Contributions view (`uc.memory.buckets`, ADR 0149 decision 6; the web's `MemoryBuckets.vue`): the uploader
 * picker — every primary with the sub-buckets curated below it ([Buckets.rows]) — narrowing the gallery to the chosen
 * bucket's media, and its sub-buckets created, renamed, deleted and filled. Shown where `MemoryRights.buckets` holds (a
 * member's; **the fourth segment of the view switch**, as the web offers it, owner 2026-11-19: [OFFERED]). A member curates any root, their own,
 * another member's or a guest's (ADR 0034 point 6, the server's gate is membership) — but the caller's own root is told apart
 * ([Buckets.Row.own], the virtual "You" until it exists, inbox #991), and only what the server takes is offered: a sub-bucket
 * under a primary ([State.createsSub]), a sub-bucket renamed or deleted ([State.renames]), a move within one root
 * ([State.moveTargets]). Online only for a write (ADR 0140 decision 7); offline the rows read from the kept listing.
 */
class Contributions(private val gallery: GalleryModel, private val door: Door) : me.raddatz.veyq.mobile.navigation.PageIntake {
    companion object {
        /** Whether the Contributions view is offered beside Photos | Music | Map — yes, exactly as the web offers it (ADR 0149 decision 6, owner 2026-11-19). */
        const val OFFERED = true

        /** The view over [tenant]'s server. */
        fun of(tenant: TenantSession, gallery: GalleryModel): Contributions = Contributions(gallery, object : Door {
            private val scopeId = gallery.node.scopeId
            private val nodeId = gallery.node.nodeId
            override suspend fun list(): Pair<Buckets.Listed, Map<String, String?>> {
                val listed = Buckets.list(tenant, scopeId, nodeId)
                val node = tenant.node(scopeId, nodeId) ?: return listed to emptyMap()
                val above = Buckets.ancestorKeys(tenant, scopeId, nodeId)
                return listed to listed.buckets.associate { it.bucketId to Buckets.name(tenant.account.suite, scopeId, nodeId, node.key, listed, it, above) }
            }
            override val self: String get() = tenant.account.userId
            override fun person(userId: String) = tenant.people[userId]?.displayName
            override suspend fun create(name: String, parent: String?) = Buckets.create(tenant, scopeId, nodeId, name, parent)
            override suspend fun rename(bucketId: String, name: String) = Buckets.rename(tenant, scopeId, nodeId, bucketId, name)
            override suspend fun delete(bucketId: String) = Buckets.delete(tenant, scopeId, nodeId, bucketId)
            override suspend fun move(mediaIds: List<String>, target: String?) = Buckets.move(tenant, scopeId, nodeId, mediaIds, target)
        })
    }

    /** What the view reads and writes ([of]; a test's own). */
    interface Door {
        /** The buckets with each one's opened name (null: none opens); a throw where they do not read. */
        suspend fun list(): Pair<Buckets.Listed, Map<String, String?>>
        /** The caller's user id: whose primary is their own ([Buckets.Row.own]). */
        val self: String
        fun person(userId: String): String?
        suspend fun create(name: String, parent: String?): Buckets.Outcome
        suspend fun rename(bucketId: String, name: String): Buckets.Outcome
        suspend fun delete(bucketId: String): Buckets.Outcome
        suspend fun move(mediaIds: List<String>, target: String?): Buckets.Outcome
    }

    data class State(
        val rows: List<Buckets.Row> = emptyList(),
        /** The chosen bucket; null before the first read. */
        val selected: String? = null,
        /** The chosen bucket's media, in the gallery's order. */
        val media: List<GalleryModel.Row> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false,
        /** The last curation refused or failed; null once seen. */
        val refused: Buckets.Outcome? = null,
    ) {
        val row: Buckets.Row? get() = rows.firstOrNull { it.bucketId == selected }
        /** Whether a sub-bucket may be made under the chosen bucket: a primary (the server nests one level only). */
        val createsSub: Boolean get() = row?.isPrimary == true
        /** Whether the chosen bucket may be renamed and deleted: a sub-bucket (a primary is neither). */
        val renames: Boolean get() = row?.isPrimary == false
        /** Where a medium of the chosen bucket may move: the other buckets of its root (never across owners). */
        val moveTargets: List<Buckets.Row> get() = Buckets.moveTargets(rows, selected)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private var listed: Buckets.Listed? = null

    /** The buckets read (anew): the picker keeps its choice where it still stands, else opens on the caller's own. */
    suspend fun read() {
        val read = try {
            door.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (read == null) return _state.update { it.copy(loading = false, failed = true) }
        val (l, names) = read
        listed = l
        val items = gallery.current.rows.orEmpty()
        val rows = Buckets.rows(l, names, door::person, Buckets.mediaOf(Buckets.SELF, items.map { it.item }, door.self).size, door.self)
        _state.update { s ->
            val keep = s.selected?.takeIf { id -> rows.any { it.bucketId == id } } ?: Buckets.preferred(rows)
            s.copy(rows = rows, selected = keep, media = mediaOf(keep, gallery.current.rows), loading = false, failed = false)
        }
    }

    /** Bucket [bucketId]'s media among the gallery's [rows], in their order. */
    private fun mediaOf(bucketId: String?, rows: List<GalleryModel.Row>?): List<GalleryModel.Row> {
        bucketId ?: return emptyList()
        val all = rows.orEmpty()
        val ids = Buckets.mediaOf(bucketId, all.map { it.item }, door.self).mapTo(HashSet()) { it.mediaId }
        return all.filter { it.mediaId in ids }
    }

    /** The picker's choice: the grid narrows to that bucket's media. */
    fun pick(bucketId: String) = _state.update { s -> if (s.rows.none { it.bucketId == bucketId }) s else s.copy(selected = bucketId, media = mediaOf(bucketId, gallery.current.rows)) }

    /**
     * The lightbox opened on medium [mediaId] over the chosen bucket's media (the web's `MemoryBuckets.vue` pages the
     * bucket, inbox #996): it pages those and follows them across the gallery's reads; null before a bucket is chosen.
     */
    fun lightbox(mediaId: String): Lightbox? {
        val bucket = current.selected ?: return null
        return gallery.lightboxOver(mediaId) { s -> s.rows?.let { mediaOf(bucket, it) } }
    }

    private suspend fun curate(write: suspend () -> Buckets.Outcome): Buckets.Outcome {
        if (gallery.current.offline != null || !gallery.current.rights.buckets) return Buckets.Outcome.REFUSED
        val out = write()
        if (out == Buckets.Outcome.DONE) {
            // the gallery's rows read anew first: the buckets' media are read from them (inbox #990)
            gallery.readAnew()
            read()
        } else {
            _state.update { it.copy(refused = out) }
        }
        return out
    }

    /** A sub-bucket named [name] under the chosen primary (the caller's own where that is the virtual one); refused under a sub-bucket. */
    suspend fun create(name: String): Buckets.Outcome =
        current.row?.takeIf { it.isPrimary }?.let { r -> curate { door.create(name, r.bucketId) } } ?: Buckets.Outcome.REFUSED

    /** The chosen sub-bucket renamed. */
    suspend fun rename(name: String): Buckets.Outcome = current.row?.takeIf { !it.isPrimary }?.let { r -> curate { door.rename(r.bucketId, name) } } ?: Buckets.Outcome.REFUSED

    /** The chosen sub-bucket deleted: its media go back to its root's primary. */
    suspend fun delete(): Buckets.Outcome = current.row?.takeIf { !it.isPrimary }?.let { r -> curate { door.delete(r.bucketId) } } ?: Buckets.Outcome.REFUSED

    /** The chosen bucket's [mediaIds] moved into bucket [target], one of [State.moveTargets]; refused across roots. */
    suspend fun move(mediaIds: List<String>, target: String): Buckets.Outcome =
        if (current.moveTargets.none { it.bucketId == target }) Buckets.Outcome.REFUSED else curate { door.move(mediaIds, target) }

    /** Whether the chosen bucket takes an upload now ([intake]). */
    enum class Intake {
        /** It does: a bucket of the caller's own root, and the page takes uploads (a sub-bucket online only). */
        TAKES,

        /** Another uploader's root: an upload lands in its uploader's primary and never moves across owners. */
        NOT_OWN,

        /** A sub-bucket offline: filing into it is a move, a write, online only (ADR 0140 decision 7). */
        OFFLINE,

        /** No bucket chosen, or the page takes no upload (no right; offline with nothing queued). */
        NONE,
    }

    /**
     * Whether the chosen bucket takes an upload now (inbox #1065, the web's `MemoryBuckets.vue` `takesUpload`): only a bucket
     * of the caller's own root — an upload lands in its uploader's primary and a move files it, within one root only.
     */
    fun intake(): Intake {
        val row = current.row ?: return Intake.NONE
        return when {
            !gallery.takesUploads -> Intake.NONE
            !row.own -> Intake.NOT_OWN
            !row.isPrimary && gallery.current.offline != null -> Intake.OFFLINE
            else -> Intake.TAKES
        }
    }

    /**
     * The picked or dropped [sources] uploaded into the chosen bucket (inbox #1065): into the caller's primary — virtual or
     * not — as any upload lands, into a sub-bucket of their root by a move of each medium once it is in. How many were
     * queued; none where [intake] is not [Intake.TAKES]. A move refused or failed leaves the medium in the primary and is
     * said as a curation's is ([State.refused]).
     */
    override fun upload(sources: List<UploadSource>): Int {
        if (intake() != Intake.TAKES) return 0
        val row = current.row ?: return 0
        if (row.isPrimary) return gallery.upload(sources)
        val bucketId = row.bucketId
        return gallery.upload(sources) { mediaId ->
            val out = door.move(listOf(mediaId), bucketId)
            if (out != Buckets.Outcome.DONE) _state.update { it.copy(refused = out) }
        }
    }

    // ---- the bucket's intake: a drop (core's `PageIntake`, ADR 0156; inbox #1065) ----

    /** The page's node: its gallery's. */
    override val node: me.raddatz.veyq.mobile.navigation.NodeAt get() = gallery.node

    /** What a drop keeps here: photos and videos, as the gallery's ([MemoryShare.answer]). */
    override val answer: me.raddatz.veyq.mobile.navigation.ShareAnswer get() = MemoryShare.answer

    /** A drop lands here per [intake]: another uploader's root and a sub-bucket offline refuse it in their own words. */
    override fun dropIntake(): me.raddatz.veyq.mobile.navigation.PageIntake.Intake = when (intake()) {
        Intake.TAKES -> me.raddatz.veyq.mobile.navigation.PageIntake.Intake.Takes
        Intake.NOT_OWN -> me.raddatz.veyq.mobile.navigation.PageIntake.Intake.Refused(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_DROP_NOT_OWN))
        Intake.OFFLINE -> me.raddatz.veyq.mobile.navigation.PageIntake.Intake.Refused(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.OFFLINE_READ_ONLY))
        Intake.NONE -> me.raddatz.veyq.mobile.navigation.PageIntake.Intake.NotHere
    }

    /** A dropped folder is said by name, as on the gallery. */
    override fun foldersNotTaken(names: List<String>): List<me.raddatz.veyq.mobile.words.Notice> = gallery.foldersNotTaken(names)

    /** Dropped files that are no photo or video, said by count, as on the gallery. */
    override fun filesNotTaken(count: Int): me.raddatz.veyq.mobile.words.Notice? = gallery.filesNotTaken(count)

    /** What [State.refused] said was seen. */
    fun seen() = _state.update { it.copy(refused = null) }
}
