package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A medium's corrected capture time (ADR 0144 decision 3; the web's `setMediaMeta` in `media.ts` and the lightbox's
 * *Edit capture time*, uc.memory.concurrent-curation): `capturedAtOverride` (epoch ms) set in the medium's listed tier
 * beside the `capturedAt` its bytes gave, or removed again (*Reset to original*), through [ListedTierWrite] — the raw
 * read-modify-write with every other field verbatim (a peer's place-name backfill, a track's name, a field this build
 * does not know). Its own field is the override: where a peer wrote a different one meanwhile, nothing is written and the
 * editor reopens on the peer's ([Outcome.Conflict]); any other peer's write is rebased.
 *
 * The platform picks a local date and time with its own pickers and converts it with its own calendar (the device in the
 * browser's place, the web's wall-clock rule); the core takes epoch ms, holds it to the minute ([minute]) and does no zone
 * arithmetic.
 */
object CaptureTime {
    /** The listed tier's field: the member's correction. */
    const val FIELD = "capturedAtOverride"

    private const val MINUTE_MS = 60_000L

    /** [epochMs] held to its minute (the editor's precision; the web's `datetime-local` has no seconds). */
    fun minute(epochMs: Long): Long = epochMs.floorDiv(MINUTE_MS) * MINUTE_MS

    /** The override a raw tier carries: a finite number (the web's `finiteNum`), else none. */
    fun overrideOf(raw: JsonObject): Long? =
        (raw[FIELD] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.toLong()

    /** [raw] with its override set to [overrideMs], or taken away for null; every other field verbatim and in its place. */
    fun corrected(raw: JsonObject, overrideMs: Long?): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(raw)
        if (overrideMs == null) fields.remove(FIELD) else fields[FIELD] = JsonPrimitive(overrideMs)
        return JsonObject(fields)
    }

    /** What a correction or a reset came to. */
    sealed interface Outcome {
        /** Written: the medium's override is [overrideMs] now (null: reset to the original). */
        data class Done(val overrideMs: Long?) : Outcome

        /** A peer corrected it first: nothing written; their override [peerOverrideMs] stands (null: they reset it). */
        data class Conflict(val peerOverrideMs: Long?) : Outcome

        /** The tier is present and does not open here: never rebuilt, nothing written. */
        data object Unreadable : Outcome

        /** Refused otherwise ([code] the server's, or a status). */
        data class Failed(val code: String) : Outcome
    }

    /**
     * The correction's read-modify-write over [send] (the seam a test plays a server through): the override set to
     * [overrideMs] (held to the minute, or as it is where [exact]: a selection's shift keeps each medium's seconds,
     * [CaptureTimeShift]), or removed for null, on the tier as [listed].
     */
    suspend fun write(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        overrideMs: Long?,
        exact: Boolean = false,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Outcome {
        val wanted = if (exact) overrideMs else overrideMs?.let(::minute)
        return when (val r = ListedTierWrite.write(listed, open, seal, own = ::overrideOf, edit = { corrected(it, wanted) }, send = send)) {
            ListedTierWrite.Result.Done, ListedTierWrite.Result.Unchanged -> Outcome.Done(wanted)
            is ListedTierWrite.Result.Conflict -> Outcome.Conflict(overrideOf(r.raw))
            is ListedTierWrite.Result.Failed -> if (r.code == "meta_unreadable") Outcome.Unreadable else Outcome.Failed(r.code)
        }
    }

    /** Medium [item]'s capture time corrected to [epochMs] on the tenant's server (to the minute, or as it is where [exact]). */
    suspend fun set(tenant: TenantSession, item: MemoryMedia.Item, epochMs: Long, exact: Boolean = false): Outcome = onServer(tenant, item, epochMs, exact)

    /** Medium [item]'s correction removed on the tenant's server: its original capture time counts again. */
    suspend fun reset(tenant: TenantSession, item: MemoryMedia.Item): Outcome = onServer(tenant, item, null)

    private suspend fun onServer(tenant: TenantSession, item: MemoryMedia.Item, overrideMs: Long?, exact: Boolean = false): Outcome =
        ListedTierWrite.onServer(tenant, item, Outcome.Failed("node_unreadable")) { listed, open, seal, send -> write(listed, open, seal, overrideMs, exact, send) }
}

/**
 * A medium's capture-time editor in its details (ADR 0144 decision 3; the web's lightbox editor): offered where the
 * medium's meta may be written and online ([GalleryModel.captureTimeEditor]). It opens on the effective time ([State.atMs],
 * epoch ms the platform shows in its zone; null where the medium has none, and a time can still be set) and says whether
 * a correction is set ([State.corrected]: *Reset to original* is offered). A save a peer's correction beat reopens on the
 * peer's ([State.said] [Said.CONFLICT]); every save has the gallery read anew, so it re-orders by the effective time.
 */
class CaptureTimeEditor internal constructor(
    captured: Long?,
    override: Long?,
    private val save: suspend (overrideMs: Long?) -> CaptureTime.Outcome,
) {
    /** What the editor says after a save that did not land. */
    enum class Said { CONFLICT, FAILED, UNREADABLE }

    data class State(
        /** The effective capture time (the correction, else the original); null: none known. */
        val atMs: Long?,
        /** A correction is set: *Reset to original* is offered. */
        val corrected: Boolean,
        /** The original capture time the bytes gave, which a reset returns to; null: none. */
        val originalMs: Long?,
        val saving: Boolean = false,
        val said: Said? = null,
        /** Saved: the platform closes the editor. */
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(State(override ?: captured, override != null, captured))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** The capture time set to [epochMs] (the platform's picked local time, converted by its own calendar). */
    suspend fun set(epochMs: Long): CaptureTime.Outcome = run(epochMs)

    /** The correction removed: the original capture time counts again. Only where one is set. */
    suspend fun reset(): CaptureTime.Outcome? = if (current.corrected) run(null) else null

    private suspend fun run(overrideMs: Long?): CaptureTime.Outcome {
        _state.update { it.copy(saving = true, said = null) }
        val out = save(overrideMs)
        _state.update { s ->
            when (out) {
                is CaptureTime.Outcome.Done -> s.copy(saving = false, saved = true, atMs = out.overrideMs ?: s.originalMs, corrected = out.overrideMs != null)
                is CaptureTime.Outcome.Conflict ->
                    s.copy(saving = false, said = Said.CONFLICT, atMs = out.peerOverrideMs ?: s.originalMs, corrected = out.peerOverrideMs != null)
                CaptureTime.Outcome.Unreadable -> s.copy(saving = false, said = Said.UNREADABLE)
                is CaptureTime.Outcome.Failed -> s.copy(saving = false, said = Said.FAILED)
            }
        }
        return out
    }
}
