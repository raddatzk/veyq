package me.raddatz.veyq.mobile.memories

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
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A medium's listed tier written by a member (ADR 0144 decision 3): the web's `persistMeta` in `media.ts`, the one
 * read-modify-write a track's rename and label (`TrackLabel`), a photo's place-name backfill (`PhotoPlace`) and a corrected
 * capture time (`CaptureTime`) share. The tier is read **raw** and re-sealed with every other field verbatim and in its
 * place (a field this build does not know included: never rebuilt from the parsed `MemoryMedia.Meta`), PUT at the revision
 * it was listed at (`updateMediaMeta`, ADR 0077). On `409 stale_revision` the edit is done once more on the tier the server
 * handed back — unless a peer changed the edit's **own** field meanwhile ([write]'s `own`): then nothing is written and the
 * peer's tier is handed back ([Result.Conflict]). Any other peer's write merges. Operator-blind: the server sees a re-sealed
 * blob of about the same size.
 */
object ListedTierWrite {
    /** How often a write is sent at most: once, and once more on the tier a `409 stale_revision` handed back. */
    const val MAX_ATTEMPTS = 2

    /** What a write came to. */
    sealed interface Result {
        /** The server took it. */
        data object Done : Result

        /** The edit asked for no write on the tier as it is now: nothing was sent. */
        data object Unchanged : Result

        /** A peer changed this edit's own field meanwhile: nothing written; [raw] the peer's tier. */
        data class Conflict(val raw: JsonObject) : Result

        /** Not written: refused ([code]), the tier does not open here, or still stale. */
        data class Failed(val code: String) : Result
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
     * The read-modify-write over [send] (the seam a test plays a server through): [edit] on the tier as [listed] ([open]
     * and [seal] the medium's, raw), null for "no write"; on a stale answer once more on the server's tier unless [own] —
     * the edit's own field — reads otherwise there than on the listed tier.
     */
    suspend fun write(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        own: (JsonObject) -> Any?,
        edit: (JsonObject) -> JsonObject?,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Result {
        val listedOwn = try {
            own(open(listed.wrappedMeta))
        } catch (_: NodeMetaWrite.Unreadable) {
            return Result.Failed("meta_unreadable")
        }
        var state = listed
        for (attempt in 0 until MAX_ATTEMPTS) {
            val raw = try {
                open(state.wrappedMeta)
            } catch (_: NodeMetaWrite.Unreadable) {
                return Result.Failed("meta_unreadable")
            }
            // a peer changed what this edit changes meanwhile: theirs stays, and this one is said to have lost
            if (attempt > 0 && own(raw) != listedOwn) return Result.Conflict(raw)
            val next = edit(raw) ?: return Result.Unchanged
            when (val a = send(seal(next), state.metaRevision)) {
                NodeMetaWrite.Answer.Taken -> return Result.Done
                is NodeMetaWrite.Answer.Stale -> state = a.current
                is NodeMetaWrite.Answer.Refused -> return Result.Failed(a.code ?: "status_${a.status}")
            }
        }
        return Result.Failed("stale_revision")
    }

    /**
     * [run] over [item]'s listed tier on the tenant's server: opened and sealed raw under the medium's key, sent with
     * `updateMediaMeta` at its node's epoch; [unopened] where its node is not opened here.
     */
    suspend fun <T> onServer(
        tenant: TenantSession,
        item: MemoryMedia.Item,
        unopened: T,
        run: suspend (
            NodeMetaWrite.State, (String?) -> JsonObject, (JsonObject) -> String?,
            suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
        ) -> T,
    ): T {
        val suite = tenant.account.suite
        val node = tenant.node(item.scopeId, item.nodeId) ?: return unopened
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
