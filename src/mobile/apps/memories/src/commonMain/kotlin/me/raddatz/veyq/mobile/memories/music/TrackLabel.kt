package me.raddatz.veyq.mobile.memories.music

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.ListedTierWrite
import me.raddatz.veyq.mobile.memories.MediaMetadata
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A music track renamed (the web's `media.ts` `setTrackLabel`, ADR 0135 decision 4, ADR 0142 decision 10): its own name
 * set — or, emptied, taken away, so its file names it again — in its listed tier, read **raw** and re-sealed with every
 * other field verbatim (a field this build does not know included: never rebuilt from the parsed `MemoryMedia.Meta`, which
 * knows the photo fields only), PUT at the revision it was listed at (`updateMediaMeta`, ADR 0077). On `409
 * stale_revision` it is done once more on the tier the server handed back: where a peer changed the **name** itself
 * meanwhile, that is a conflict, the peer's name kept ([Outcome.Conflict]); any other peer's write merges.
 * Operator-blind: the server sees a re-sealed blob of about the same size.
 *
 * A **GPX track's label** (ADR 0130 decision 9, ADR 0143 D8) is its name and its **activity** (`trackActivity`, one of
 * [ACTIVITIES]): [relabel] writes both the same way, a peer's change to either a conflict. An activity this build does not
 * know is read as none ([activityOf]) and kept in the tier, verbatim, until a member edits the activity.
 */
object TrackLabel {
    /** A track's own name's cap in UTF-8 bytes (the web's `TRACK_NAME_MAX_BYTES`). */
    const val NAME_MAX_BYTES = 120

    /** How often the write is sent at most ([ListedTierWrite.MAX_ATTEMPTS]). */
    const val MAX_ATTEMPTS = ListedTierWrite.MAX_ATTEMPTS

    /** The activities a track may be given — the web's `TRACK_ACTIVITIES`, as sealed tokens (each said by its own words). */
    val ACTIVITIES: List<String> = listOf(
        "hiking", "walking", "running", "cycling", "mountain-biking", "road-cycling", "gravel-cycling", "skiing", "water", "other",
    )

    /** A sealed activity this build knows, else null: none was given, or a newer client's value (left in the tier). */
    fun activityOf(value: String?): String? = value?.takeIf { it in ACTIVITIES }

    /** The activity token a raw tier carries, trimmed, whatever it is (known or not); null where none. */
    private fun rawActivityOf(raw: JsonObject): String? =
        (raw["trackActivity"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** What a member gave a GPX track: its own name (null: named by its file) and its activity (null: none). */
    data class Label(val name: String?, val activity: String?)

    /** The label a raw tier carries: its name, its activity where this build knows it. */
    fun labelOf(raw: JsonObject): Label = Label(nameOf(raw), activityOf(rawActivityOf(raw)))

    /**
     * The dash a track is drawn with for its [activity], as on-off lengths in pixels (the web's `activityDash`): on foot,
     * other and none solid (null), running dotted, cycling dashed, mountain biking dash-dot, skiing a long dash-dot,
     * water a fine dot.
     */
    fun dash(activity: String?): List<Int>? = when (activity) {
        "running" -> listOf(2, 8)
        "cycling", "road-cycling", "gravel-cycling" -> listOf(10, 7)
        "mountain-biking" -> listOf(10, 5, 2, 5)
        "skiing" -> listOf(14, 6, 3, 6)
        "water" -> listOf(3, 9)
        else -> null
    }

    /**
     * [raw] labelled: its `trackName` set to [label]'s name (or taken away), its `trackActivity` to [label]'s activity —
     * unless the activity is the one the editor opened with ([opened]), when the tier's own value stays verbatim (a newer
     * client's included) —, every other field verbatim and in its place.
     */
    fun labelled(raw: JsonObject, label: Label, opened: Label): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(renamed(raw, label.name))
        val activity = activityOf(label.activity)
        if (activity != opened.activity) {
            if (activity == null) fields.remove("trackActivity") else fields["trackActivity"] = JsonPrimitive(activity)
        }
        return JsonObject(fields)
    }

    /** A label as the listed tier's fields an upload seals at birth (`trackLabelFacts`), in the tier's key order. */
    fun facts(label: Label): List<Pair<String, JsonPrimitive>> = listOfNotNull(
        clean(label.name)?.let { "trackName" to JsonPrimitive(it) },
        activityOf(label.activity)?.let { "trackActivity" to JsonPrimitive(it) },
    )

    sealed class Outcome {
        /** Written: the track is named [name] now (null: by its file). */
        data class Done(val name: String?) : Outcome()

        /** A peer renamed it first: its name [peerName] stays (null: the peer took the name away). */
        data class Conflict(val peerName: String?, val peerActivity: String? = null) : Outcome()

        /** Not written: refused ([code]), or the tier does not open here. */
        data class Failed(val code: String?) : Outcome()
    }

    /** The own name a raw tier carries: trimmed, none where blank or not a string. */
    fun nameOf(raw: JsonObject): String? = (raw["trackName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [name] as the tier holds it: trimmed, clamped to [NAME_MAX_BYTES]; null (no field) where blank. */
    fun clean(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }?.let { MediaMetadata.clampUtf8(it, NAME_MAX_BYTES) }

    /** [raw] with its `trackName` set to [name] (or taken away for null), every other field verbatim and in its place. */
    fun renamed(raw: JsonObject, name: String?): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(raw)
        val n = clean(name)
        if (n == null) fields.remove("trackName") else fields["trackName"] = JsonPrimitive(n)
        return JsonObject(fields)
    }

    /** A listed tier opened raw for a write ([ListedTierWrite.openRaw]). */
    fun openRaw(suite: V1, scopeId: String, mediaId: String, key: ByteArray, wrapped: String?): JsonObject =
        ListedTierWrite.openRaw(suite, scopeId, mediaId, key, wrapped)

    /** [raw] sealed as the medium's listed tier ([ListedTierWrite.seal]); null where nothing but its version is in it. */
    fun seal(suite: V1, scopeId: String, mediaId: String, key: ByteArray, raw: JsonObject): String? =
        ListedTierWrite.seal(suite, scopeId, mediaId, key, raw)

    /**
     * The rename's read-modify-write over [send]: [listed] the tier as listed, [open] and [seal] the medium's (raw), the
     * name [name]. The seam a test plays a server through; [rename] sends it to the tenant's.
     */
    suspend fun write(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        name: String?,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Outcome {
        val wanted = clean(name)
        return loop(listed, open, seal, own = ::nameOf, conflict = { Outcome.Conflict(nameOf(it)) }, edit = { renamed(it, wanted) }, done = Outcome.Done(wanted), send = send)
    }

    /**
     * A GPX track's label written the rename's way ([write]): [label] over the tier as listed; on a `409 stale_revision` a
     * peer's change to the name or the activity is a conflict (its label kept), any other peer's write merges.
     */
    suspend fun relabel(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        label: Label,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Outcome {
        val opened = try {
            labelOf(open(listed.wrappedMeta))
        } catch (_: NodeMetaWrite.Unreadable) {
            return Outcome.Failed("meta_unreadable")
        }
        return loop(
            listed, open, seal,
            own = { nameOf(it) to rawActivityOf(it) },
            conflict = { Outcome.Conflict(nameOf(it), activityOf(rawActivityOf(it))) },
            edit = { labelled(it, label, opened) },
            done = Outcome.Done(clean(label.name)),
            send = send,
        )
    }

    /** The read-modify-write both share ([ListedTierWrite.write]): [edit] on the tier, a peer's change to [own] a conflict. */
    private suspend fun loop(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        own: (JsonObject) -> Any?,
        conflict: (JsonObject) -> Outcome,
        edit: (JsonObject) -> JsonObject,
        done: Outcome,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Outcome = when (val r = ListedTierWrite.write(listed, open, seal, own, edit, send)) {
        ListedTierWrite.Result.Done, ListedTierWrite.Result.Unchanged -> done
        is ListedTierWrite.Result.Conflict -> conflict(r.raw)
        is ListedTierWrite.Result.Failed -> Outcome.Failed(r.code)
    }

    /** Music track [item] renamed to [name] (null or blank: named by its file again) on the tenant's server. */
    suspend fun rename(tenant: TenantSession, item: MemoryMedia.Item, name: String?): Outcome =
        onServer(tenant, item) { listed, open, seal, send -> write(listed, open, seal, name, send) }

    /** GPX track [item] labelled [label] on the tenant's server ([relabel]). */
    suspend fun relabel(tenant: TenantSession, item: MemoryMedia.Item, label: Label): Outcome =
        onServer(tenant, item) { listed, open, seal, send -> relabel(listed, open, seal, label, send) }

    /** [run] over [item]'s listed tier on the tenant's server ([ListedTierWrite.onServer]). */
    internal suspend fun onServer(
        tenant: TenantSession,
        item: MemoryMedia.Item,
        run: suspend (
            NodeMetaWrite.State, (String?) -> JsonObject, (JsonObject) -> String?,
            suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
        ) -> Outcome,
    ): Outcome = ListedTierWrite.onServer(tenant, item, Outcome.Failed("node_unreadable"), run)
}
