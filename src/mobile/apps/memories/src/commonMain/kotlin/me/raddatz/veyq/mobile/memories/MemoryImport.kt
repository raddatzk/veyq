package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.export.ImportRefused
import me.raddatz.veyq.mobile.export.Manifest
import me.raddatz.veyq.mobile.export.NodeImport
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.memories.music.AudioFacts
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.memories.place.PhotoPlace
import me.raddatz.veyq.mobile.memories.tracks.Gpx
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite

/**
 * Memories' part in an import (ADR 0152 decision 3), a port of the web's `features/memories/exportImport.ts`
 * `validateMemoryContent` and `importMemoryContent`: the node (a Memory or a chapter) is created with its date and place
 * sealed at birth; each medium's checked original goes the way a picked one does — its tiers derived by the platform's
 * [Derivation.Deriver] (decoded, never held whole), its digest and metadata read ([MediaUpload.prepare]), the facts the
 * bytes cannot carry restored and sealed at birth ([restored]) —, then the cover (its JPEG as it is) and the banner (its
 * files as they are; a banner that does not come back is a loss, never a failed import). Who was there and who added each
 * photo are not carried (ADR 0083).
 */
object MemoryImport {
    private const val APPLICATION = "memories"
    private const val SCHEMA = 1
    const val MEMORY_TYPE = "memories.memory"
    const val CHAPTER_TYPE = "memories.chapter"
    /** The contract's lengths of Memories' fields (the web's `MEMORIES_LIMITS`). */
    private const val NAME = 1024
    private const val PLACE = 1024
    private const val PARTICIPANT = 1024
    private const val PARTICIPANTS = 100
    private const val TRACK_NAME = 1024
    /** The largest cover read whole: a cover is a cover-sized JPEG. */
    private const val COVER_MAX = 16L * 1024 * 1024
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun shape(message: String) = NodeImport.Problem(ImportRefused.Refusal.INVALID_MANIFEST, message)
    private fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun isPayload(v: JsonElement?): Boolean = v is JsonObject && str(v["path"]) != null && Manifest.number(v["size"]) != null
    private fun num(v: JsonElement?, min: Double, max: Double): Boolean = Manifest.number(v)?.let { it in min..max } == true

    /** A string field at most [max] long (absent is fine), or the problem with it. */
    private fun long(what: String, v: JsonElement?, max: Int): NodeImport.Problem? = when {
        v == null -> null
        str(v) == null -> shape("$what is not text")
        str(v)!!.length > max -> NodeImport.Problem(ImportRefused.Refusal.TOO_LARGE, "$what is longer than $max")
        else -> null
    }

    /** A `memories.memory` / `memories.chapter` content from a download, held to its contract (the web's `validateMemoryContent`). */
    fun validate(content: JsonObject): NodeImport.Problem? {
        val type = str(content["type"])
        if (type != MEMORY_TYPE && type != CHAPTER_TYPE) return shape("not a Memory or chapter: $type")
        val media = content["media"] as? JsonArray ?: return shape("media is not a list")
        for ((i, x) in media.withIndex()) {
            val m = x as? JsonObject
            if (m == null || !isPayload(m["file"])) return shape("medium $i has no file")
            (long("medium $i name", m["name"], NAME) ?: long("medium $i contributor", m["contributor"], NAME))?.let { return it }
            m["capturedAt"]?.let { if (str(it)?.let(::isoMs) == null) return shape("medium $i capturedAt") }
            m["capturedAtCorrected"]?.let { if ((it as? JsonPrimitive)?.let { p -> !p.isString && p.content == "true" } != true) return shape("medium $i capturedAtCorrected") }
            m["exif"]?.let { if (it !is JsonObject) return shape("medium $i exif") }
            m["location"]?.let { l ->
                if (l !is JsonObject || (l["lat"] != null && !num(l["lat"], -90.0, 90.0)) || (l["lon"] != null && !num(l["lon"], -180.0, 180.0))) return shape("medium $i location")
                long("medium $i place", l["place"], PLACE)?.let { return it }
            }
            m["track"]?.let { tr ->
                if (tr !is JsonObject || (tr["activity"] != null && TrackLabel.activityOf(str(tr["activity"])) == null)) return shape("medium $i track")
                long("medium $i track name", tr["name"], TRACK_NAME)?.let { return it }
            }
            m["audio"]?.let { a ->
                fun int(v: JsonElement?) = Manifest.number(v)?.let { it >= 0 && it == kotlin.math.floor(it) } == true
                if (a !is JsonObject || !int(a["durationMs"]) || !int(a["trimStartMs"]) || !int(a["trimEndMs"])) return shape("medium $i audio")
            }
        }
        content["date"]?.let { d ->
            val o = d as? JsonObject
            val modeOk = str(o?.get("mode")) in setOf("auto", "single", "range", "ongoing")
            fun day(k: String) = o?.get(k)?.let { v -> str(v)?.let(DATE::matches) == true } ?: true
            if (o == null || !modeOk || !day("from") || !day("to")) return shape("date")
        }
        content["location"]?.let { l ->
            if (l !is JsonObject) return shape("location")
            long("place", l["name"], PLACE)?.let { return it }
        }
        content["participants"]?.let { p ->
            val list = p as? JsonArray ?: return shape("participants is not a list")
            if (list.size > PARTICIPANTS) return NodeImport.Problem(ImportRefused.Refusal.TOO_LARGE, "more than $PARTICIPANTS participants")
            for ((i, x) in list.withIndex()) long("participant $i", x, PARTICIPANT)?.let { return it }
        }
        // a cover without a file (an older archive's photo reference) is not restored, and not a refusal
        content["cover"]?.let { c -> if (c !is JsonObject || (c["file"] != null && !isPayload(c["file"]))) return shape("cover") }
        content["banner"]?.let { b ->
            if (b !is JsonObject || !isPayload(b["file"]) || str(b["medium"]) !in setOf("image", "video")) return shape("banner")
            if ((b["poster"] != null && !isPayload(b["poster"])) || (b["frame"] != null && str(b["frame"]) !in setOf("fill", "fit"))) return shape("banner")
            b["focal"]?.let { f -> if (f !is JsonObject || !num(f["x"], 0.0, 1.0) || !num(f["y"], 0.0, 1.0)) return shape("banner focal") }
        }
        return null
    }

    /** An ISO time as `Date.parse` reads one the archive writes; null where it does not read. */
    internal fun isoMs(s: String): Long? = runCatching { kotlin.time.Instant.parse(s).toEpochMilliseconds() }.getOrNull()

    /** The node's first meta: its date and place as the archive carries them, normalized; null where it carries neither. */
    internal fun birth(content: JsonObject): JsonObject? {
        val raw = JsonObject(listOfNotNull(content["date"]?.let { MemoryDate.FIELD to it }, content["location"]?.let { MemoryLocation.FIELD to it }).toMap())
        val fields = LinkedHashMap<String, JsonElement>()
        MemoryDate.of(raw)?.let { fields[MemoryDate.FIELD] = it.toJson() }
        MemoryLocation.of(raw)?.let { fields[MemoryLocation.FIELD] = it.toJson() }
        return if (fields.isEmpty()) null else JsonObject(mapOf("v" to JsonPrimitive(1)) + fields)
    }

    /**
     * The listed tier [tier] (what the bytes said) with what a download's [medium] restores beside them (the web's
     * `restoredFacts`, sealed at birth): its corrected capture time as an override, its place name (not a GPX's or a music
     * track's), a track's own name and activity, and a music track's facts. A time not corrected is the bytes' own, read again.
     */
    @PlatformApi
    @HiddenFromObjC
    fun restored(tier: JsonObject?, medium: JsonObject, type: String, name: String): JsonObject? {
        val isAudio = type.startsWith("audio/")
        val isTrack = isAudio || Gpx.isGpxFile(name, type)
        val override = if ((medium["capturedAtCorrected"] as? JsonPrimitive)?.content == "true") str(medium["capturedAt"])?.let(::isoMs) else null
        val place = if (isTrack) null else str((medium["location"] as? JsonObject)?.get("place"))?.trim()?.takeIf { it.isNotEmpty() }
        val track = medium["track"] as? JsonObject
        val label = TrackLabel.facts(TrackLabel.Label(str(track?.get("name"))?.takeIf { it.isNotBlank() }, str(track?.get("activity"))))
        val audio = if (isAudio) AudioFacts.from(medium["audio"] as? JsonObject) else null
        if (override == null && place == null && label.isEmpty() && audio == null) return tier
        val base = tier ?: JsonObject(mapOf("v" to JsonPrimitive(1)))
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in base) {
            out[k] = v
            if (k == "capturedAt" && override != null) out[CaptureTime.FIELD] = JsonPrimitive(override)
        }
        if (override != null && CaptureTime.FIELD !in out) out[CaptureTime.FIELD] = JsonPrimitive(override)
        place?.let { out["placeName"] = JsonPrimitive(MediaMetadata.clampUtf8(it, PhotoPlace.NAME_MAX_BYTES)) }
        label.forEach { (k, v) -> out[k] = v }
        audio?.let {
            out["durationMs"] = JsonPrimitive(it.durationMs)
            out["trimStartMs"] = JsonPrimitive(it.trimStartMs)
            out["trimEndMs"] = JsonPrimitive(it.trimEndMs)
        }
        return JsonObject(out)
    }

    /** Memories' hook for [kind] (a Memory or a chapter): [deriver] the platform's tiers (null: an original goes alone). */
    fun hook(kind: String, deriver: Derivation.Deriver?, now: () -> Long) = NodeImport.Hook(APPLICATION, SCHEMA, ::validate) { ctx ->
        val content = ctx.node.content ?: throw NodeImport.Failed("import_invalid_content")
        val tenant = ctx.session()
        val suite = tenant.account.suite
        val meta = birth(content)
        val input = me.raddatz.veyq.mobile.AppKinds.tokenInput(kind, ctx.name) ?: throw NodeImport.Failed("unknown_kind")
        val seal = meta?.let { m -> { key: ByteArray, nodeId: String -> NodeMetaWrite.seal(suite, ctx.scopeId, nodeId, key, m) } }
        val nodeId = when (val r = NodeCreate.create(tenant, ctx.scopeId, ctx.parentId, kind, ctx.name, input, seal)) {
            is NodeCreate.Result.Done -> r.created.nodeId
            is NodeCreate.Result.Refused ->
                return@Hook if (r.reason == NodeCreate.Reason.NAME_TAKEN) NodeImport.Made(null, nameTaken = true) else NodeImport.Made(null, failure = r.reason.name.lowercase())
        }
        tenant.forgetTree()
        val media = (content["media"] as? JsonArray).orEmpty().map { it as JsonObject }
        val total = media.sumOf { Manifest.number((it["file"] as JsonObject)["size"]) ?: 0.0 }.coerceAtLeast(1.0)
        var done = 0.0
        for (m in media) {
            val ref = Manifest.payloadOf(m["file"] as JsonObject)
            val name = str(m["name"]) ?: "file"
            val checked = ctx.payload(ref)
            val source = UploadSource.of(name, ref.size, ref.mediaType, ref.modifiedAt) { from, length -> checked.read(from, length) }
            val original = MediaUpload.Original(source.bytes(), ref.mediaType, name, ref.modifiedAt)
            val prepared = MediaUpload.prepare(original, now()) { type ->
                if (deriver == null) Derivation.Derived(null, null, null, null)
                else suspendCancellableCoroutine { c -> deriver.derive(source, type) { d -> c.resume(d) { _, _, _ -> } } }
            }
            val withFacts = MediaUpload.Prepared(prepared.type, prepared.sha256, prepared.derived, restored(prepared.metaTier, m, prepared.type, name), prepared.exif)
            val before = done
            val r = MediaUpload.once(tenant, ctx.scopeId, nodeId, original, withFacts) { f -> ctx.progress((before + f * ref.size) / total) }
            source.close()
            if (r.outcome != MediaUpload.Outcome.DONE) throw NodeImport.Failed(r.code ?: r.outcome.name.lowercase())
            done += ref.size
        }
        (content["cover"] as? JsonObject)?.let { cover ->
            val file = cover["file"] as? JsonObject ?: return@let
            val ref = Manifest.payloadOf(file)
            if (ref.size > COVER_MAX) return@let
            val jpeg = ctx.payload(ref).read(0, ref.size.toInt())
            when (NodeCover.set(tenant, ctx.scopeId, nodeId, jpeg, NodeCover.crop(cover["crop"]))) {
                NodeCover.Outcome.DONE -> {}
                else -> throw NodeImport.Failed("cover_not_restored")
            }
        }
        (content["banner"] as? JsonObject)?.let { banner ->
            try {
                restoreBanner(ctx, nodeId, banner)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ImportRefused) {
                throw e // a banner file that is not what the manifest says refuses the import, as any payload
            } catch (e: Exception) {
                // a banner that does not come back is a loss, never a failed import: the Memory and its media stand
            }
        }
        NodeImport.Made(nodeId)
    }

    /** The banner's files uploaded as they are, with its framing (the web's `restoreBanner`); a video needs its poster. */
    private suspend fun restoreBanner(ctx: NodeImport.Context, nodeId: String, banner: JsonObject) {
        val ref = Manifest.payloadOf(banner["file"] as JsonObject)
        val file = ctx.payload(ref)
        val medium = if (str(banner["medium"]) == "video") Banner.Medium.VIDEO else Banner.Medium.IMAGE
        val frame = if (str(banner["frame"]) == "fit") Banner.Frame.FIT else Banner.Frame.FILL
        val focal = Banner.focal(banner["focal"])
        val prepared = if (medium == Banner.Medium.IMAGE) {
            BannerWrite.Prepared(medium, BannerWrite.Part.Upload(ref.size, "image/jpeg", null, file::read), null, null, "")
        } else {
            val posterRef = (banner["poster"] as? JsonObject)?.let(Manifest::payloadOf) ?: return
            val poster = ctx.payload(posterRef)
            val type = Banner.videoType(ref.mediaType)
            val sha = b64url(file.sha256)
            BannerWrite.Prepared(
                medium, BannerWrite.Part.Upload(ref.size, type, sha, file::read),
                BannerWrite.Part.Upload(posterRef.size, "image/jpeg", null, poster::read), type, sha,
            )
        }
        val out = BannerWrite.copy(ctx.session(), ctx.scopeId, nodeId, prepared, frame, focal)
        if (out != BannerWrite.Outcome.Done) throw IllegalStateException("banner_not_restored")
    }
}
