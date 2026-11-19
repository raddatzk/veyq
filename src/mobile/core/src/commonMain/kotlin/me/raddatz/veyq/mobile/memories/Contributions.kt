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
 * bucket's media, and a member's own sub-buckets created, renamed, deleted and filled. Shown where `MemoryRights.buckets`
 * holds (a member's; **the fourth segment of the view switch, pending the owner**: [OFFERED]). Online only for a write
 * (ADR 0140 decision 7); offline the rows read from the kept listing.
 */
class Contributions internal constructor(private val gallery: GalleryModel, private val door: Door) {
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
        /** Whether the chosen bucket is the caller's to curate (a sub-bucket made, renamed, deleted, media moved into it). */
        val curates: Boolean get() = row?.canManage == true
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
        val rows = Buckets.rows(l, names, door::person, Buckets.mediaOf(Buckets.SELF, items.map { it.item }).size)
        _state.update { s ->
            val keep = s.selected?.takeIf { id -> rows.any { it.bucketId == id } } ?: Buckets.preferred(rows, l)
            s.copy(rows = rows, selected = keep, media = mediaOf(keep), loading = false, failed = false)
        }
    }

    private fun mediaOf(bucketId: String?): List<GalleryModel.Row> {
        bucketId ?: return emptyList()
        val rows = gallery.current.rows.orEmpty()
        val ids = Buckets.mediaOf(bucketId, rows.map { it.item }).mapTo(HashSet()) { it.mediaId }
        return rows.filter { it.mediaId in ids }
    }

    /** The picker's choice: the grid narrows to that bucket's media. */
    fun pick(bucketId: String) = _state.update { s -> if (s.rows.none { it.bucketId == bucketId }) s else s.copy(selected = bucketId, media = mediaOf(bucketId)) }

    private suspend fun curate(write: suspend () -> Buckets.Outcome): Buckets.Outcome {
        if (gallery.current.offline != null || !gallery.current.rights.buckets) return Buckets.Outcome.REFUSED
        val out = write()
        if (out == Buckets.Outcome.DONE) {
            gallery.reload()
            read()
        } else {
            _state.update { it.copy(refused = out) }
        }
        return out
    }

    /** A sub-bucket named [name] under the chosen bucket (the caller's own primary where that is the virtual one). */
    suspend fun create(name: String): Buckets.Outcome = curate { door.create(name, current.selected) }

    /** The chosen sub-bucket renamed. */
    suspend fun rename(name: String): Buckets.Outcome = current.row?.takeIf { !it.isPrimary && it.canManage }?.let { r -> curate { door.rename(r.bucketId, name) } } ?: Buckets.Outcome.REFUSED

    /** The chosen sub-bucket deleted: its media go back to the primary. */
    suspend fun delete(): Buckets.Outcome = current.row?.takeIf { !it.isPrimary && it.canManage }?.let { r -> curate { door.delete(r.bucketId) } } ?: Buckets.Outcome.REFUSED

    /** The caller's [mediaIds] moved into bucket [target] (its own primary: [Buckets.SELF]). */
    suspend fun move(mediaIds: List<String>, target: String): Buckets.Outcome = curate { door.move(mediaIds, target) }

    /** What [State.refused] said was seen. */
    fun seen() = _state.update { it.copy(refused = null) }
}
