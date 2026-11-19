package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's Contributions view (`uc.memory.buckets`, ADR 0149 decision 6; the web's `MemoryBuckets.vue`): the uploader
 * picker — every primary with the sub-buckets curated below it ([Buckets.rows]) — narrowing the gallery to the chosen
 * bucket's media, and its sub-buckets created, renamed, deleted and filled. Shown where `MemoryRights.buckets` holds (a
 * member's; **the fourth segment of the view switch, pending the owner**: [OFFERED]). A member curates any root, their own,
 * another member's or a guest's (ADR 0034 point 6, the server's gate is membership) — but the caller's own root is told apart
 * ([Buckets.Row.own], the virtual "You" until it exists, inbox #991), and only what the server takes is offered: a sub-bucket
 * under a primary ([State.createsSub]), a sub-bucket renamed or deleted ([State.renames]), a move within one root
 * ([State.moveTargets]). Online only for a write (ADR 0140 decision 7); offline the rows read from the kept listing.
 */
class Contributions(private val gallery: GalleryModel, private val door: Door) {
    companion object {
        /** Whether the Contributions view is offered beside Photos | Music | Map — pending the owner (ADR 0149 decision 6); recommended: yes. */
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

    /** What [State.refused] said was seen. */
    fun seen() = _state.update { it.copy(refused = null) }
}
