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
 */
object TrackLabel {
    /** A track's own name's cap in UTF-8 bytes (the web's `TRACK_NAME_MAX_BYTES`). */
    const val NAME_MAX_BYTES = 120

    /** How often the write is sent at most: once, and once more on the tier a `409 stale_revision` handed back. */
    const val MAX_ATTEMPTS = 2

    sealed class Outcome {
        /** Written: the track is named [name] now (null: by its file). */
        data class Done(val name: String?) : Outcome()

        /** A peer renamed it first: its name [peerName] stays (null: the peer took the name away). */
        data class Conflict(val peerName: String?) : Outcome()

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
        val listedName = try {
            nameOf(open(listed.wrappedMeta))
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
            // a peer renamed it meanwhile: theirs stays, and this one is said to have lost
            if (attempt > 0 && nameOf(raw) != listedName) return Outcome.Conflict(nameOf(raw))
            when (val a = send(seal(renamed(raw, wanted)), state.metaRevision)) {
                NodeMetaWrite.Answer.Taken -> return Outcome.Done(wanted)
                is NodeMetaWrite.Answer.Stale -> state = a.current
                is NodeMetaWrite.Answer.Refused -> return Outcome.Failed(a.code ?: "status_${a.status}")
            }
        }
        return Outcome.Failed("stale_revision")
    }

    /** Music track [item] renamed to [name] (null or blank: named by its file again) on the tenant's server. */
    suspend fun rename(tenant: TenantSession, item: MemoryMedia.Item, name: String?): Outcome {
        val suite = tenant.account.suite
        val node = tenant.node(item.scopeId, item.nodeId) ?: return Outcome.Failed("node_unreadable")
        val api = MediaApi(tenant.base, tenant.account.http)
        return write(
            NodeMetaWrite.State(item.wrappedMeta, item.metaRevision),
            open = { openRaw(suite, item.scopeId, item.mediaId, item.key, it) },
            seal = { seal(suite, item.scopeId, item.mediaId, item.key, it) },
            name = name,
        ) { wrapped, revision ->
            try {
                val r = tenant.account.respond { api.updateMediaMeta(item.scopeId, item.mediaId, MediaMetaUpdate(node.epoch, revision, wrapped)) }
                if (r.success) return@write NodeMetaWrite.Answer.Taken
                val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
                if (r.status == 409) NodeMetaWrite.staleOf(body)?.let { return@write NodeMetaWrite.Answer.Stale(it) }
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
