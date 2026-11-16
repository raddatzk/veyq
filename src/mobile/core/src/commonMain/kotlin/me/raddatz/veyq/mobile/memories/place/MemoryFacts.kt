package me.raddatz.veyq.mobile.memories.place

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.Geocoder
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/** Where place names come from for a tenant's writes: its [maps]' geocoder in the app's [lang] (ADR 0143 D2). */
class PlaceNames(val maps: MapServices, val lang: () -> String) {
    /** The levels at [point], or null: no geocoder, no consent for it, or no answer — no coordinate leaves then. */
    suspend fun levelsAt(point: LatLng): List<GeoLevel>? =
        if (maps.geocodingAvailable) maps.geocoder?.reverse(point.lat, point.lng, lang()) else null

    /** Whether a name may be looked up now: a geocoder served and this device's consent holding for it. */
    val available: Boolean get() = maps.geocodingAvailable
}

/**
 * A Memory's date and place as its node's sealed meta holds them, and every write of them (uc.memory.date,
 * uc.memory.place, uc.memory.concurrent-curation; ADR 0143 D5): the ports of the web's `setMemoryDate`,
 * `setMemoryLocation`, `reconcileMemoryDate` and `reconcileAutoLocation`, all through core's [NodeMetaWrite] — the blob
 * opened **raw** for the write and re-sealed with every other field verbatim (the cover, a field this build does not
 * know), never rebuilt from what was parsed of it, at the `metaRevision` it was built from. On a `409 stale_revision` an
 * editor's change re-runs on the server's blob unless a peer changed the **same** field meanwhile — then the editor
 * re-opens on the peer's value ([Outcome.Conflict]) —; the `auto` self-heal re-runs its whole decision there, so a manual
 * date or a fixed place a peer set is never overwritten.
 */
object MemoryFacts {
    /** A node's date and place as shown: each null where none is set or the blob does not open. */
    data class Facts(val date: MemoryDate?, val location: MemoryLocation?) {
        /** The place's name where it is materialized (not pending): the facts line's place. */
        val placeName: String? get() = location?.name?.takeIf { it.isNotEmpty() }

        companion object {
            val NONE = Facts(null, null)

            fun of(meta: JsonObject) = Facts(MemoryDate.of(meta), MemoryLocation.of(meta))
        }
    }

    /** What a write of the date or the place came to. */
    sealed interface Outcome {
        /** Written: the server took it. */
        data object Written : Outcome

        /** Nothing to write on the blob as it is now. */
        data object Unchanged : Outcome

        /** A peer changed the same field meanwhile: nothing written; the editor re-opens on [current]. */
        data class Conflict(val current: Facts) : Outcome

        /** The blob is present and does not open here: refused, never rebuilt from empty. */
        data object Unreadable : Outcome

        /** Refused otherwise ([code] the server's, or none). */
        data class Refused(val status: Int, val code: String?) : Outcome
    }

    /** Thrown from an edit that met a peer's change to its own field: the blob it met. */
    internal class Conflicted(val meta: JsonObject) : Exception("meta_conflict")

    /** A node's blob opened for display, best-effort: an absent or unopenable one is [Facts.NONE]. */
    fun read(suite: V1, scopeId: String, nodeId: String, key: ByteArray, wrappedMeta: String?): Facts =
        runCatching { Facts.of(NodeMetaWrite.openForWrite(suite, scopeId, nodeId, key, wrappedMeta)) }.getOrDefault(Facts.NONE)

    /** Node [nodeId]'s date and place as the tenant's tree holds them; null where the node is not opened here. */
    suspend fun of(tenant: TenantSession, scopeId: String, nodeId: String): Facts? {
        val n = tenant.node(scopeId, nodeId) ?: return null
        return read(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta)
    }

    /** The date of every Memory and chapter the tenant opens, by node id (a list row's second line); none left out. */
    suspend fun dates(tenant: TenantSession): Map<String, MemoryDate> =
        tenant.trees().values.flatten().filter { it.kind == "MEMORY" }.mapNotNull { n ->
            read(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta).date?.let { n.id to it }
        }.toMap()

    /**
     * An editor's change to the blob's [field] (`editField`): set to [value], or dropped for null. On a re-run after a
     * `409`, a [field] that is no longer what the editor opened with ([openedWith], as the listed blob held it) is a peer's:
     * no retry, [Conflicted]. A change to any other field merges silently. One edit per write: it counts its runs.
     */
    fun editField(field: String, value: JsonElement?, openedWith: JsonElement?): suspend (JsonObject) -> JsonObject? {
        var runs = 0
        return { meta ->
            if (runs++ > 0 && meta[field] != openedWith) throw Conflicted(meta)
            JsonObject(if (value == null) meta - field else meta + (field to value))
        }
    }

    /** The date editor's change: the blob's `date` set to [date] (`setMemoryDate`), every other field verbatim. */
    fun dateEdit(date: MemoryDate, openedWith: JsonElement?) = editField(MemoryDate.FIELD, date.toJson(), openedWith)

    /**
     * The place editor's change: the blob's `location` set to [location] — its name held to its bound — or dropped for
     * null (`setMemoryLocation`), the date, the cover and every other field verbatim.
     */
    fun placeEdit(location: MemoryLocation?, openedWith: JsonElement?) =
        editField(MemoryLocation.FIELD, location?.let { it.copy(name = MemoryLocation.bound(it.name)).toJson() }, openedWith)

    /** [NodeMetaWrite.write] with an editor's two refusals said: a conflict on its own field, a blob that does not open. */
    suspend fun write(
        base: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String,
        edit: suspend (JsonObject) -> JsonObject?,
        send: suspend (wrappedMeta: String, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Pair<Outcome, NodeMetaWrite.State> = try {
        when (val r = NodeMetaWrite.write(base, open, seal, edit, send)) {
            is NodeMetaWrite.Result.Wrote -> Outcome.Written to r.state
            is NodeMetaWrite.Result.Unchanged -> Outcome.Unchanged to r.state
            is NodeMetaWrite.Result.Refused -> Outcome.Refused(r.status, r.code) to base
        }
    } catch (e: Conflicted) {
        Outcome.Conflict(Facts.of(e.meta)) to base
    } catch (_: NodeMetaWrite.Unreadable) {
        Outcome.Unreadable to base
    }

    /** The field [field] of the blob as listed (the editor's "opened with"); null where absent or the blob does not open. */
    fun listedField(open: (String?) -> JsonObject, base: NodeMetaWrite.State, field: String): JsonElement? =
        runCatching { open(base.wrappedMeta)[field] }.getOrNull()

    /**
     * The next `auto` place for [stored] at medoid [point] (`nextAutoLocation`): re-medoided, and a geocoded name
     * materialized (a pending arm) or refreshed (a moved medoid) at the stored granularity, else the default one.
     * [levelsAt] answers null where no name may be looked up (no request leaves then).
     */
    suspend fun nextAuto(stored: MemoryLocation, point: LatLng, levelsAt: suspend (LatLng) -> List<GeoLevel>?): MemoryLocation {
        var next = stored.copy(derived = point)
        val moved = stored.derived != point
        if (stored.nameSource == MemoryLocation.NameSource.GEOCODED && (stored.pending || moved)) {
            val levels = levelsAt(point)
            if (levels != null) {
                val wanted = stored.nameAnchor?.level
                val level = wanted?.let { k -> Geocoder.nameAtLevel(levels, k)?.let { GeoLevel(k, it) } } ?: Geocoder.defaultLevel(levels)
                if (level != null) next = next.copy(name = level.name, nameAnchor = MemoryLocation.Anchor(point, level.key))
            }
        }
        return next
    }

    /**
     * The `auto` place's self-heal edit (`reconcileAutoLocation`): an `auto` place re-medoided to [point] and its geocoded
     * name followed; a `fixed` place or none is the member's (null: no write); unchanged, no write. Decided on the blob
     * being written, so a place a peer pinned meanwhile is never moved.
     */
    fun reconcileLocation(point: LatLng, levelsAt: suspend (LatLng) -> List<GeoLevel>?): suspend (JsonObject) -> JsonObject? = { meta ->
        val stored = MemoryLocation.of(meta)
        if (stored == null || stored.derivedMode != MemoryLocation.DerivedMode.AUTO) null
        else {
            val next = nextAuto(stored, point, levelsAt)
            if (next == stored) null else JsonObject(meta + (MemoryLocation.FIELD to next.toJson()))
        }
    }

    // ---- on the tenant's server ----

    /** [edit] written to node [nodeId]'s blob on the tenant's server: a stale epoch reads the node anew and tries again. */
    private suspend fun onServer(
        tenant: TenantSession, scopeId: String, nodeId: String,
        edit: (open: (String?) -> JsonObject, base: NodeMetaWrite.State) -> suspend (JsonObject) -> JsonObject?,
    ): Outcome {
        repeat(2) { attempt ->
            if (attempt > 0) tenant.forgetTree()
            val node = tenant.node(scopeId, nodeId) ?: return Outcome.Refused(404, "node_unreadable")
            val (outcome, _) = writeTo(tenant, node, edit)
            if (outcome is Outcome.Refused && outcome.code == "stale_epoch") return@repeat
            if (outcome == Outcome.Written) tenant.forgetTree() // the tree read before holds the blob it replaced
            return outcome
        }
        return Outcome.Refused(409, "stale_epoch")
    }

    private suspend fun writeTo(
        tenant: TenantSession, node: OpenNode,
        edit: (open: (String?) -> JsonObject, base: NodeMetaWrite.State) -> suspend (JsonObject) -> JsonObject?,
        base: NodeMetaWrite.State = NodeMetaWrite.State(node.wrappedMeta, node.metaRevision),
    ): Pair<Outcome, NodeMetaWrite.State> {
        val suite = tenant.account.suite
        val open: (String?) -> JsonObject = { NodeMetaWrite.openForWrite(suite, node.scopeId, node.id, node.key, it) }
        return write(base, open, { NodeMetaWrite.seal(suite, node.scopeId, node.id, node.key, it) }, edit(open, base)) { wrapped, revision ->
            send(tenant, node, wrapped, revision)
        }
    }

    private suspend fun send(tenant: TenantSession, node: OpenNode, wrapped: String, revision: Long): NodeMetaWrite.Answer = try {
        val r = tenant.account.respond {
            ContentApi(tenant.base, tenant.account.http).updateNode(
                node.scopeId, node.id, UpdateNodeRequest(wrappedMeta = wrapped, metaRevision = revision, nodeEpoch = node.epoch),
            )
        }
        if (r.success) NodeMetaWrite.Answer.Taken
        else {
            val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
            NodeMetaWrite.staleOf(body)?.let { NodeMetaWrite.Answer.Stale(it) }
                ?: NodeMetaWrite.Answer.Refused(r.status, runCatching { ((Json.parseToJsonElement(body) as JsonObject)["code"] as? JsonPrimitive)?.content }.getOrNull())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        NodeMetaWrite.Answer.Refused(-1, null)
    }

    /** Memory or chapter [nodeId]'s date set by its editor ([date]; `auto` with the live range, so a list shows it at once). */
    suspend fun setDate(tenant: TenantSession, scopeId: String, nodeId: String, date: MemoryDate): Outcome =
        onServer(tenant, scopeId, nodeId) { open, base -> dateEdit(date, listedField(open, base, MemoryDate.FIELD)) }

    /** Memory or chapter [nodeId]'s place set by its editor, or removed (null); the date and the cover kept either way. */
    suspend fun setLocation(tenant: TenantSession, scopeId: String, nodeId: String, location: MemoryLocation?): Outcome =
        onServer(tenant, scopeId, nodeId) { open, base -> placeEdit(location, listedField(open, base, MemoryLocation.FIELD)) }

    /**
     * The settle (uc.memory.date § How auto is derived, uc.memory.place A2): the `auto` range from [items] and then the
     * `auto` place at their medoid, each re-sealed only where it changed, the place's name looked up through [names] where
     * its consent holds. The second write starts from the state the first left, so the two never race. Whether either wrote.
     */
    suspend fun reconcile(tenant: TenantSession, scopeId: String, nodeId: String, items: List<MemoryMedia.Item>, names: PlaceNames?): Boolean {
        val node = tenant.node(scopeId, nodeId) ?: return false
        val reconcileDate = MemoryDate.reconcile(MemoryDate.autoRange(items))
        val (dated, state) = writeTo(tenant, node, { _, _ -> { meta -> reconcileDate(meta) } })
        var wrote = dated == Outcome.Written
        val point = MemoryLocation.medoid(MemoryLocation.gpsPoints(items))
        if (point != null) {
            val levelsAt: suspend (LatLng) -> List<GeoLevel>? = { p -> names?.levelsAt(p) }
            val (placed, _) = writeTo(tenant, node, { _, _ -> reconcileLocation(point, levelsAt) }, state)
            wrote = wrote || placed == Outcome.Written
        }
        if (wrote) tenant.forgetTree()
        return wrote
    }
}
