package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.MediaMetadata
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A picked audio file made a music track of a Memory or a chapter (the web's `music/audio.ts` `checkAudioFile` with
 * `media.ts`' upload of it; ADR 0135 decisions 2–4, ADR 0142 decision 7) — only from the Music view; the gallery's picker
 * keeps refusing audio (`MediaUpload.isMedia`).
 *
 * **Checked on the device before anything is sent** ([check]), refused in the web's order: over [MAX_BYTES] (nothing read
 * of it), then not an audio container by its head's bytes (`Sniff.stream`, never the type the platform claims), then a
 * length the platform's player cannot read ([Measure]: it could not play it). **The type sealed is the sniff's** — an M4A
 * is `audio/mp4`, never the `video/mp4` a platform may claim. Its facts ([AudioFacts.measure]) go into its listed tier
 * beside what every medium's carries (the file's date as its capture time); no derived tier: a track has no picture.
 */
object MusicUpload {
    /** The largest audio file a track is made from (ADR 0135 decision 2): refused on the device before anything is uploaded. */
    const val MAX_BYTES = 200L * 1024 * 1024

    /** How much of a file's head is read to type it: every signature `Sniff.stream` knows lies in it. */
    const val HEAD_BYTES = 4096

    /** Why a picked file is refused, each with its own words. */
    enum class Problem(val msg: Msg) {
        TOO_LARGE(Msg.MUSIC_TOO_LARGE),
        NOT_AUDIO(Msg.MUSIC_NOT_AUDIO),
        UNPLAYABLE(Msg.MUSIC_UNPLAYABLE),
    }

    /** What the check of one picked file came to. */
    sealed class Check(val source: UploadSource) {
        /** A track: the type its bytes name and its facts. */
        class Taken(source: UploadSource, val type: String, val facts: AudioFacts) : Check(source)

        /** Refused, with why: [notice] names the file. */
        class Refused(source: UploadSource, val problem: Problem) : Check(source) {
            val notice: Notice get() = Notice(problem.msg, source.name)
        }
    }

    /**
     * The platform's reading of a picked file's length, as its player would play it (Android `MediaMetadataRetriever`,
     * Apple `AVURLAsset`, Windows and Linux the host's `Media.Duration`): [done] once, on any thread, with the length in
     * ms, or null (or 0) where it cannot read it — the file is then refused [Problem.UNPLAYABLE].
     */
    interface Measure {
        fun durationMs(source: UploadSource, type: String, done: (Long?) -> Unit)

        /**
         * A decoded format's two ends (inbox #932): the first and the last [limitMs] of [source]'s audio of [durationMs]
         * as the platform decodes them, each a WAV file's bytes (`PcmWav`) — [done] once, on any thread; null where the
         * platform does not decode it, and the file is sealed untrimmed. Never the whole file.
         */
        fun ends(source: UploadSource, type: String, durationMs: Long, limitMs: Long, done: (Pair<ByteArray, ByteArray>?) -> Unit)
    }

    /**
     * [source] checked (see the header); [durationOf] reads its length as typed by its bytes, [endsOf] a decoded format's
     * ends for its trim (none: untrimmed). Its reads are let go after.
     */
    suspend fun check(
        source: UploadSource,
        endsOf: suspend (type: String, durationMs: Long, limitMs: Long) -> Pair<ByteArray, ByteArray>? = { _, _, _ -> null },
        durationOf: suspend (type: String) -> Long?,
    ): Check {
        if (source.size > MAX_BYTES) return Check.Refused(source, Problem.TOO_LARGE)
        try {
            val head = try {
                source.read(0, minOf(source.size, HEAD_BYTES.toLong()).toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return Check.Refused(source, Problem.NOT_AUDIO)
            }
            val kind = Sniff.stream(head)?.takeIf { !it.video } ?: return Check.Refused(source, Problem.NOT_AUDIO)
            val durationMs = try {
                durationOf(kind.mime)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (durationMs == null || durationMs <= 0) return Check.Refused(source, Problem.UNPLAYABLE)
            return Check.Taken(source, kind.mime, AudioFacts.measure(source.bytes(), kind.mime, durationMs) { limit -> endsOf(kind.mime, durationMs, limit) })
        } finally {
            source.close()
        }
    }

    /**
     * A track's listed tier (`media.ts` `sealMetadata` for an audio file): what every medium's carries — no size or
     * position, the file's date as its capture time, its source `file` —, then the three facts, in the web's key order.
     */
    fun metaTier(facts: AudioFacts, modifiedAt: Long?): JsonObject {
        val base = MediaMetadata.metaTier(null, null, MediaMetadata.Read(null, "file", null, null, null), modifiedAt)
        val fields = LinkedHashMap(base ?: JsonObject(mapOf("v" to JsonPrimitive(1))))
        fields["durationMs"] = JsonPrimitive(facts.durationMs)
        fields["trimStartMs"] = JsonPrimitive(facts.trimStartMs)
        fields["trimEndMs"] = JsonPrimitive(facts.trimEndMs)
        return JsonObject(fields)
    }

    /**
     * A checked track added to Memory or chapter [nodeId] of [scopeId] as a job of the app's upload queue: its digest is
     * its on-device stage, kept for a retry; then the original alone, sealed under the sniffed type with its listed tier
     * (`MediaUpload`'s one medium's upload). [mediaId] is the medium it added.
     */
    class Job(
        private val tenant: TenantSession, val scopeId: String, val nodeId: String, private val taken: Check.Taken,
    ) : UploadQueue.Job {
        private val source get() = taken.source
        override val name get() = source.name
        override val sizeBytes get() = source.size
        override val prepares get() = true

        /** The file it uploads (where the app reads it). */
        val picked: UploadSource get() = source

        /** The type its original is sealed under (the sniff's). */
        val type: String get() = taken.type

        var mediaId: String? = null
            private set

        private val lock = Mutex()
        private var prepared: MediaUpload.Prepared? = null

        private val original get() = MediaUpload.Original(source.bytes(), taken.type, source.name, source.modifiedAt)

        override suspend fun prepare() {
            ready()
        }

        private suspend fun ready(): MediaUpload.Prepared = lock.withLock {
            prepared ?: try {
                MediaUpload.Prepared(
                    taken.type, MediaUpload.sha256(original.source), Derivation.Derived(null, null, null, null),
                    metaTier(taken.facts, source.modifiedAt), null,
                )
            } finally {
                source.close()
            }.also { prepared = it }
        }

        override suspend fun run(progress: (Double) -> Unit) {
            val p = ready()
            val r = try {
                MediaUpload.once(tenant, scopeId, nodeId, original, p, progress)
            } finally {
                source.close()
            }
            when (r.outcome) {
                MediaUpload.Outcome.DONE -> {
                    mediaId = r.mediaId
                    prepared = null
                }
                MediaUpload.Outcome.NO_ROOM -> throw UploadQueue.Failed(UploadQueue.QUOTA_EXCEEDED)
                MediaUpload.Outcome.SWITCHED_OFF -> throw UploadQueue.Failed(UploadQueue.APPLICATION_OFF)
                MediaUpload.Outcome.BUSY -> throw UploadQueue.Failed("node_rekey_pending")
                MediaUpload.Outcome.FAILED -> throw UploadQueue.Failed(r.code ?: "failed")
            }
        }
    }
}
