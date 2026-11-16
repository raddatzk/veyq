package me.raddatz.veyq.mobile.memories.music

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaMetaUpdate
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
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

    /** How often the write is sent at most: once, and once more on the tier a `409 stale_revision` handed back. */
    const val MAX_ATTEMPTS = 2

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

    /** A listed tier opened raw for a write: an absent one is a fresh `{v:1}`; one that does not open or parse throws. */
    fun openRaw(suite: V1, scopeId: String, mediaId: String, key: ByteArray, wrapped: String?): JsonObject {
        if (wrapped == null) return JsonObject(mapOf("v" to JsonPrimitive(1)))
        val parsed = runCatching {
            Json.parseToJsonElement(suite.openField(context(scopeId, mediaId), key, unb64(wrapped)).decodeToString()) as? JsonObject
        }.getOrNull() ?: throw NodeMetaWrite.Unreadable()
        val v = parsed["v"] as? JsonPrimitive
        return if (v != null && !v.isString) parsed else JsonObject(LinkedHashMap<String, JsonElement>(parsed).also { it["v"] = JsonPrimitive(1) })
    }

    /** [raw] sealed as the medium's listed tier; null (no tier) where nothing but its version is in it (the web's). */
    fun seal(suite: V1, scopeId: String, mediaId: String, key: ByteArray, raw: JsonObject): String? {
        if (raw.keys.none { it != "v" }) return null
        return b64(suite.sealField(context(scopeId, mediaId), key, raw.toString().encodeToByteArray()))
    }

    private fun context(scopeId: String, mediaId: String) = Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId)

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

    /** The read-modify-write both share: [edit] on the tier, re-run once on a stale one unless a peer changed [own]. */
    private suspend fun loop(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        own: (JsonObject) -> Any?,
        conflict: (JsonObject) -> Outcome,
        edit: (JsonObject) -> JsonObject,
        done: Outcome,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Outcome {
        val listedOwn = try {
            own(open(listed.wrappedMeta))
        } catch (_: NodeMetaWrite.Unreadable) {
            return Outcome.Failed("meta_unreadable")
        }
        var state = listed
        for (attempt in 0 until MAX_ATTEMPTS) {
            val raw = try {
                open(state.wrappedMeta)
            } catch (_: NodeMetaWrite.Unreadable) {
                return Outcome.Failed("meta_unreadable")
            }
            // a peer changed what this edit changes meanwhile: theirs stays, and this one is said to have lost
            if (attempt > 0 && own(raw) != listedOwn) return conflict(raw)
            when (val a = send(seal(edit(raw)), state.metaRevision)) {
                NodeMetaWrite.Answer.Taken -> return done
                is NodeMetaWrite.Answer.Stale -> state = a.current
                is NodeMetaWrite.Answer.Refused -> return Outcome.Failed(a.code ?: "status_${a.status}")
            }
        }
        return Outcome.Failed("stale_revision")
    }

    /** Music track [item] renamed to [name] (null or blank: named by its file again) on the tenant's server. */
    suspend fun rename(tenant: TenantSession, item: MemoryMedia.Item, name: String?): Outcome =
        onServer(tenant, item) { listed, open, seal, send -> write(listed, open, seal, name, send) }

    /** GPX track [item] labelled [label] on the tenant's server ([relabel]). */
    suspend fun relabel(tenant: TenantSession, item: MemoryMedia.Item, label: Label): Outcome =
        onServer(tenant, item) { listed, open, seal, send -> relabel(listed, open, seal, label, send) }

    /**
     * [run] over [item]'s listed tier on the tenant's server: opened and sealed raw under the medium's key, sent with
     * `updateMediaMeta` at its node's epoch — the door [MemoryMedia]'s place-name backfill uses too.
     */
    internal suspend fun onServer(
        tenant: TenantSession,
        item: MemoryMedia.Item,
        run: suspend (
            NodeMetaWrite.State, (String?) -> JsonObject, (JsonObject) -> String?,
            suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
        ) -> Outcome,
    ): Outcome {
        val suite = tenant.account.suite
        val node = tenant.node(item.scopeId, item.nodeId) ?: return Outcome.Failed("node_unreadable")
        val api = MediaApi(tenant.base, tenant.account.http)
        return run(
            NodeMetaWrite.State(item.wrappedMeta, item.metaRevision),
            { openRaw(suite, item.scopeId, item.mediaId, item.key, it) },
            { seal(suite, item.scopeId, item.mediaId, item.key, it) },
        ) { wrapped, revision ->
            try {
                val r = tenant.account.respond { api.updateMediaMeta(item.scopeId, item.mediaId, MediaMetaUpdate(node.epoch, revision, wrapped)) }
                if (r.success) return@run NodeMetaWrite.Answer.Taken
                val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
                if (r.status == 409) NodeMetaWrite.staleOf(body)?.let { return@run NodeMetaWrite.Answer.Stale(it) }
                val code = runCatching { ((Json.parseToJsonElement(body) as JsonObject)["code"] as? JsonPrimitive)?.content }.getOrNull()
                NodeMetaWrite.Answer.Refused(r.status, code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                NodeMetaWrite.Answer.Refused(-1, null)
            }
        }
    }
}
