package me.raddatz.veyq.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A node's sealed meta written whole, revision-checked (ADR 0077), core's for every application — a port of
 * `writeSealedMeta` (`src/web/src/core/node/sealedMetaWrite.ts`) and the envelope of `core/node/nodeMeta.ts`. Any
 * member writes the blob whole and the server cannot merge what it cannot read, so the merge is the client's: every
 * write states the `metaRevision` it was built from; a `409 stale_revision` hands back the current blob, and the writer
 * re-runs ONLY its own change on it. Two rules underneath, each once a live defect on the web: a writer never re-seals
 * from its own cached copy (hence the revision), and a present blob that does not open is never rebuilt from empty
 * (hence [Unreadable]: only an ABSENT blob is a fresh one).
 */
object NodeMetaWrite {
    /** Bounds the re-base loop on `409 stale_revision` (the identity-pin store's bound). */
    const val MAX_ATTEMPTS = 5

    /** A blob as listed: its ciphertext (null when none) and the revision it is at. */
    data class State(val wrappedMeta: String?, val metaRevision: Long)

    /** A present blob the writer could not open or parse: refused, never rebuilt from empty. */
    class Unreadable : Exception("meta_unreadable")

    /** What the server answered a send: taken, a `409 stale_revision` with the state it holds, or anything else. */
    sealed interface Answer {
        data object Taken : Answer
        data class Stale(val current: State) : Answer
        data class Refused(val status: Int, val code: String?) : Answer
    }

    /** What a write came to: [state] the newest the server confirmed or handed back. */
    sealed interface Result {
        data class Wrote(val state: State) : Result
        /** The edit asked for no write on the blob as it is now. */
        data class Unchanged(val state: State) : Result
        /** Still stale after [MAX_ATTEMPTS], or refused otherwise. */
        data class Refused(val status: Int, val code: String?) : Result
    }

    fun context(scopeId: String, nodeId: String) = Context("node-meta", "scopeId" to scopeId, "nodeId" to nodeId)

    /** The blob for a write: an absent one is a fresh `{v:1}`; a present one that does not open or parse throws. */
    fun openForWrite(suite: V1, scopeId: String, nodeId: String, key: ByteArray, wrappedMeta: String?): JsonObject {
        if (wrappedMeta == null) return JsonObject(mapOf("v" to JsonPrimitive(1)))
        val parsed = runCatching {
            Json.parseToJsonElement(suite.openField(context(scopeId, nodeId), key, unb64(wrappedMeta)).decodeToString()) as? JsonObject
        }.getOrNull() ?: throw Unreadable()
        return JsonObject(parsed + ("v" to JsonPrimitive(1)))
    }

    /** [meta] sealed as the node's blob, its `v` set to 1; unknown sibling fields kept verbatim. */
    fun seal(suite: V1, scopeId: String, nodeId: String, key: ByteArray, meta: JsonObject): String =
        b64(suite.sealField(context(scopeId, nodeId), key, Json.encodeToString(JsonObject.serializer(), JsonObject(meta + ("v" to JsonPrimitive(1)))).encodeToByteArray()))

    /** The current state a `409 stale_revision` body (`StaleSealedMeta`) carries, or null for any other body. */
    fun staleOf(body: String): State? = runCatching {
        val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
        if (o["code"]?.jsonPrimitive?.content != "stale_revision") return null
        val revision = (o["metaRevision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        State((o["wrappedMeta"] as? JsonPrimitive)?.takeIf { it.isString }?.content, revision)
    }.getOrNull()

    /**
     * Applies [edit] to the blob at [base] and sends it with the revision it was built from; on a stale answer re-opens
     * the blob the server handed back, re-runs [edit] on it and sends again, at most [MAX_ATTEMPTS] times. [edit] answers
     * null for "no write". [open] throws [Unreadable] for a present blob that does not open.
     */
    suspend fun write(
        base: State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String,
        edit: (JsonObject) -> JsonObject?,
        send: suspend (wrappedMeta: String, metaRevision: Long) -> Answer,
    ): Result {
        var state = base
        for (attempt in 0 until MAX_ATTEMPTS) {
            val next = edit(open(state.wrappedMeta)) ?: return Result.Unchanged(state)
            val wrapped = seal(next)
            when (val a = send(wrapped, state.metaRevision)) {
                Answer.Taken -> return Result.Wrote(State(wrapped, state.metaRevision + 1))
                is Answer.Stale -> state = a.current
                is Answer.Refused -> return Result.Refused(a.status, a.code)
            }
        }
        return Result.Refused(409, "stale_revision")
    }
}
