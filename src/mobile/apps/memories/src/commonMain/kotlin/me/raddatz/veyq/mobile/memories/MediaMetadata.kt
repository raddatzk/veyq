package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.media.ExifReader
import me.raddatz.veyq.mobile.media.ExifTags
import me.raddatz.veyq.mobile.media.VideoHeader
import kotlin.math.abs

/**
 * What a medium's two metadata tiers are sealed from at its upload (the web's `media.ts` `readExif`, `readMetadata`,
 * `sealMetadata`), read on the device before any key is used: a photo's tags ([ExifReader]) split into its capture time
 * (EXIF's original date, its digitised date, XMP's capture date, then the date the file last changed), its position (the
 * listed tier's) and the panel bundle (the EXIF tier, date-free, each field its first-choice tag then the one an editor
 * writes); a video's capture time from its container ([VideoHeader]), which replaces any other. Where the bytes carry no
 * date, the file's own is the capture time, its source `file`.
 */
object MediaMetadata {
    /** The sealed EXIF tier's cap in base64 characters (`wrappedExif.maxLength` in `media.openapi.yaml`). */
    const val EXIF_TIER_MAX_B64 = 16384

    /** Each free-text EXIF field's cap in UTF-8 bytes. */
    const val EXIF_TEXT_MAX_BYTES = 1024

    /** What the bytes carry: the embedded capture time and where it came from, the position, the panel bundle. */
    class Read(val capturedAt: Long?, val capturedAtFrom: String, val gpsLat: Double?, val gpsLng: Double?, val exif: JsonObject?)

    /** [source]'s metadata as a [type] (the resolved media type) is read: an image's tags, a video's container time. */
    suspend fun read(source: ByteSource, type: String, now: Long): Read {
        val tags = if (type.startsWith("image/")) runCatching { ExifReader.read(source) }.getOrNull() else null
        val recorded = if (type.startsWith("video/")) runCatching { VideoHeader.captureTime(source, now) }.getOrNull() else null
        if (recorded != null) return Read(recorded, "container", tags?.latitude, tags?.longitude, tags?.let(::exifOf))
        return Read(tags?.let(::capturedAtOf), "exif", tags?.latitude, tags?.longitude, tags?.let(::exifOf))
    }

    /** A photo's embedded capture date by its one precedence rule. */
    fun capturedAtOf(tags: ExifTags): Long? = tags.dateTimeOriginal ?: tags.dateTimeDigitized ?: tags.xmpCapturedAt ?: tags.dateTime

    /** The panel bundle (`MediaExif`), in the web's key order; null where nothing but its version would be in it. */
    fun exifOf(t: ExifTags): JsonObject? {
        fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        val fields = linkedMapOf<String, JsonElement?>(
            "v" to JsonPrimitive(1),
            "make" to clean(t.make)?.let(::JsonPrimitive),
            "model" to clean(t.model)?.let(::JsonPrimitive),
            "lens" to clean(t.lensModel ?: t.lensMake)?.let(::JsonPrimitive),
            "focalLength" to number(t.focalLength),
            "focalLength35" to number(t.focalLengthIn35mmFilm),
            "fNumber" to number(t.fNumber),
            "exposureTime" to number(t.exposureTime),
            "exposureCompensation" to number(t.exposureBiasValue),
            "iso" to number(t.isoSpeedRatings),
            "flash" to number(t.flash),
            "orientation" to number(t.orientation),
            "software" to clean(t.software ?: t.xmpCreatorTool)?.let(::JsonPrimitive),
            "artist" to clean(t.artist)?.let(::JsonPrimitive),
            "copyright" to clean(t.copyright)?.let(::JsonPrimitive),
            "rating" to number(t.rating ?: t.xmpRating),
            "title" to clean(t.xmpTitle ?: t.iptcObjectName)?.let(::JsonPrimitive),
            "description" to clean(t.xmpDescription ?: t.imageDescription)?.let(::JsonPrimitive),
            "altitude" to number(t.gpsAltitude?.let { if (t.gpsAltitudeRef == 1.0) -it else it }),
            "imgDirection" to number(t.gpsImgDirection),
            "imgDirectionRef" to clean(t.gpsImgDirectionRef)?.let(::JsonPrimitive),
            "gpsAccuracy" to number(t.gpsHPositioningError),
            "w" to number(t.pixelXDimension ?: t.imageWidth),
            "h" to number(t.pixelYDimension ?: t.imageLength),
        )
        val present = fields.filterValues { it != null }.mapValues { it.value!! }
        return if (present.size > 1) JsonObject(present) else null
    }

    /**
     * The listed tier (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng}`, the web's order): the display size, the capture
     * time — embedded, else [lastModified] with the source `file` —, the position; null where nothing but its version is in it.
     */
    fun metaTier(width: Int?, height: Int?, read: Read, lastModified: Long?): JsonObject? {
        val capturedAt = read.capturedAt ?: lastModified
        val source = when {
            capturedAt == null -> null
            read.capturedAt != null -> read.capturedAtFrom
            else -> "file"
        }
        val fields = linkedMapOf<String, JsonElement?>(
            "v" to JsonPrimitive(1),
            "w" to width?.let(::JsonPrimitive),
            "h" to height?.let(::JsonPrimitive),
            "capturedAt" to capturedAt?.let(::JsonPrimitive),
            "capturedAtSource" to source?.let(::JsonPrimitive),
            "gpsLat" to number(read.gpsLat),
            "gpsLng" to number(read.gpsLng),
        )
        val present = fields.filterValues { it != null }.mapValues { it.value!! }
        return if (present.size > 1) JsonObject(present) else null
    }

    /** The listed tier sealed under [key] bound to the medium (`media-meta`), as the completion carries it. */
    fun sealMeta(suite: V1, key: ByteArray, scopeId: String, mediaId: String, tier: JsonObject): String =
        b64(suite.sealField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, tier.toString().encodeToByteArray()))

    /**
     * The EXIF tier sealed so it always fits [EXIF_TIER_MAX_B64] (`sealExifTier`): every text field clamped to
     * [EXIF_TEXT_MAX_BYTES]; one that still does not fit drops its longest text field, one at a time, until it does.
     */
    fun sealExif(suite: V1, key: ByteArray, scopeId: String, mediaId: String, exif: JsonObject): String {
        val context = Context("media-exif", "scopeId" to scopeId, "mediaId" to mediaId)
        val tier = LinkedHashMap<String, JsonElement>(exif)
        fun textKeys() = tier.filterValues { it is JsonPrimitive && it.isString }.keys
        for (k in textKeys()) tier[k] = JsonPrimitive(clampUtf8((tier.getValue(k) as JsonPrimitive).content, EXIF_TEXT_MAX_BYTES))
        fun seal() = b64(suite.sealField(context, key, JsonObject(tier).toString().encodeToByteArray()))
        var sealed = seal()
        while (sealed.length > EXIF_TIER_MAX_B64) {
            val keys = textKeys()
            if (keys.isEmpty()) break
            tier.remove(keys.maxBy { tier.getValue(it).toString().length })
            sealed = seal()
        }
        return sealed
    }

    /** A number as JSON: a whole one as an integer (as JavaScript writes it), else the double; none where not finite. */
    internal fun number(d: Double?): JsonPrimitive? = when {
        d == null || !d.isFinite() -> null
        d == kotlin.math.floor(d) && abs(d) < 9.007199254740992E15 -> JsonPrimitive(d.toLong())
        else -> JsonPrimitive(d)
    }

    /** The longest prefix of [s] whose UTF-8 fits [max] bytes, cut between code points. */
    internal fun clampUtf8(s: String, max: Int): String {
        val bytes = s.encodeToByteArray()
        if (bytes.size <= max) return s
        var end = max
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.decodeToString(0, end)
    }
}
