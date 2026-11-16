package me.raddatz.veyq.mobile.memories.tracks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.MediaMetadata
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Notice

/**
 * A picked GPX file made a track of a Memory or a chapter (the web's `MemoryTracks.vue` add with `checkGpxFile`; ADR 0130
 * decisions 1, 2 and 9; ADR 0143 D8) — only from the Map view. **Checked on the device before anything is sent**
 * ([check]): room in the node, its size, that it reads as a track; each refusal its own words naming the file. The label a
 * member gives it as it is added — its name and activity — is **sealed at birth** into its listed tier, so no second write
 * follows the upload. Its original is the file as picked, sealed under the GPX type; no derived tier.
 */
object TrackUpload {
    /** What the check of one picked file came to. */
    sealed class Check(val source: UploadSource) {
        /** A track: what it reads as (its own name the label's hint). */
        class Taken(source: UploadSource, val parsed: Gpx.Parsed) : Check(source) {
            /** The name it is listed under before a member names it: the file's own, else its file name (`trackName`). */
            val fileTrackName: String? get() = Gpx.trackName(parsed.name, source.name)
        }

        /** Refused, with why: [notice] names the file. */
        class Refused(source: UploadSource, val problem: Gpx.Problem) : Check(source) {
            val notice: Notice get() = problem.notice(source.name)
        }
    }

    /** [source] checked with the node holding [held] tracks; its reads are let go after. */
    suspend fun check(source: UploadSource, held: Int): Check = try {
        when (val c = Gpx.check(source.size, held) { source.read(0, source.size.toInt()) }) {
            is Gpx.Check.Taken -> Check.Taken(source, c.parsed)
            is Gpx.Check.Refused -> Check.Refused(source, c.problem)
        }
    } finally {
        source.close()
    }

    /**
     * A track's listed tier (`media.ts` `sealMetadata` for a GPX file): what every medium's carries — the file's date as its
     * capture time, its source `file` —, then the label's name and activity, in the web's key order.
     */
    fun metaTier(label: TrackLabel.Label, modifiedAt: Long?): JsonObject {
        val base = MediaMetadata.metaTier(null, null, MediaMetadata.Read(null, "file", null, null, null), modifiedAt)
        val fields = LinkedHashMap<String, JsonElement>(base ?: JsonObject(mapOf("v" to JsonPrimitive(1))))
        TrackLabel.facts(label).forEach { (k, v) -> fields[k] = v }
        return JsonObject(fields)
    }

    /** A checked track and the label asked for it, added to Memory or chapter [nodeId] of [scopeId] as a job of the queue. */
    class Job(
        private val tenant: TenantSession, val scopeId: String, val nodeId: String, private val taken: Check.Taken, val label: TrackLabel.Label,
    ) : UploadQueue.Job {
        private val source get() = taken.source
        override val name get() = source.name
        override val sizeBytes get() = source.size
        override val prepares get() = true

        var mediaId: String? = null
            private set

        private val lock = Mutex()
        private var prepared: MediaUpload.Prepared? = null

        private val original get() = MediaUpload.Original(source.bytes(), Gpx.MEDIA_TYPE, source.name, source.modifiedAt)

        override suspend fun prepare() {
            ready()
        }

        private suspend fun ready(): MediaUpload.Prepared = lock.withLock {
            prepared ?: try {
                MediaUpload.Prepared(
                    Gpx.MEDIA_TYPE, MediaUpload.sha256(original.source), MediaUpload.Derived(null, null, null, null),
                    metaTier(label, source.modifiedAt), null,
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
