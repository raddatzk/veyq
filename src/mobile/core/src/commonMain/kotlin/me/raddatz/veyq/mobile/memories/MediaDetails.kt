package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.blob.DownloadName
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the lightbox's details show of a medium (the web's `MediaLightbox.vue` `metadataRows`, its place and position rows):
 * the capture time and the file's own facts from the listing at once, the camera's from the heavy EXIF tier once it is
 * read ([exif], members only), in the web's order, a row without a value left out. A value that reads the same in every
 * language is spelled here; a date, a size, the flash and the direction are the app's to phrase ([Row.number]).
 */
object MediaDetails {
    enum class Field {
        CAPTURED, FILE_NAME, FILE_SIZE, FILE_FORMAT, CAMERA, LENS, FOCAL_LENGTH, FOCAL_LENGTH_35, APERTURE, EXPOSURE,
        EXPOSURE_COMPENSATION, ISO, FLASH, DIMENSIONS, TITLE, DESCRIPTION, RATING, SOFTWARE, ARTIST, COPYRIGHT, ALTITUDE,
        DIRECTION_TRUE, DIRECTION_MAGNETIC, GPS_ACCURACY, CORRECTED, PLACE, LOCATION,
    }

    /**
     * One row: [value] as shown, or — for [Field.CAPTURED] (epoch ms), [Field.FILE_SIZE] (bytes), [Field.FLASH] (1 fired,
     * 0 not) and the two directions (whole degrees) — [number] for the app to phrase, [value] then empty.
     */
    class Row(val field: Field, val value: String, val number: Long? = null)

    /** A row's label, for both apps (the web's `media.details.*`). */
    fun label(field: Field): me.raddatz.veyq.mobile.words.Msg = when (field) {
        Field.CAPTURED -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_CAPTURED
        Field.FILE_NAME -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FILE_NAME
        Field.FILE_SIZE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FILE_SIZE
        Field.FILE_FORMAT -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FILE_FORMAT
        Field.CAMERA -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_CAMERA
        Field.LENS -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_LENS
        Field.FOCAL_LENGTH -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FOCAL_LENGTH
        Field.FOCAL_LENGTH_35 -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FOCAL_LENGTH_35
        Field.APERTURE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_APERTURE
        Field.EXPOSURE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_EXPOSURE
        Field.EXPOSURE_COMPENSATION -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_EXPOSURE_COMPENSATION
        Field.ISO -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_ISO
        Field.FLASH -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_FLASH
        Field.DIMENSIONS -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_DIMENSIONS
        Field.TITLE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_TITLE
        Field.DESCRIPTION -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_DESCRIPTION
        Field.RATING -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_RATING
        Field.SOFTWARE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_SOFTWARE
        Field.ARTIST -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_ARTIST
        Field.COPYRIGHT -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_COPYRIGHT
        Field.ALTITUDE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_ALTITUDE
        Field.GPS_ACCURACY -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_GPS_ACCURACY
        Field.PLACE -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_PLACE
        Field.LOCATION -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_LOCATION
        Field.DIRECTION_TRUE, Field.DIRECTION_MAGNETIC -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_DIRECTION
        Field.CORRECTED -> me.raddatz.veyq.mobile.words.Msg.MEMORY_FIELD_STATUS
    }

    /** How a row's value shows: in words, as a size or a moment the platform writes its way, or as read. */
    sealed class Shown {
        class Words(val notice: me.raddatz.veyq.mobile.words.Notice) : Shown()
        class Size(val bytes: Long) : Shown()
        class Moment(val epochMs: Long) : Shown()
        class Plain(val text: String) : Shown()
    }

    fun shown(row: Row): Shown {
        val n = row.number ?: 0
        return when (row.field) {
            Field.CAPTURED -> Shown.Moment(n)
            Field.FILE_SIZE -> Shown.Size(n)
            Field.FLASH -> Shown.Words(me.raddatz.veyq.mobile.words.Notice(if (n == 1L) me.raddatz.veyq.mobile.words.Msg.MEMORY_FLASH_FIRED else me.raddatz.veyq.mobile.words.Msg.MEMORY_FLASH_NOT_FIRED))
            Field.DIRECTION_TRUE -> Shown.Words(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_DIRECTION_TRUE, n.toInt()))
            Field.DIRECTION_MAGNETIC -> Shown.Words(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_DIRECTION_MAGNETIC, n.toInt()))
            Field.CORRECTED -> Shown.Words(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_CORRECTED))
            else -> Shown.Plain(row.value)
        }
    }

    /** The chapters a Memory holds (its children of kind `CHAPTER`), as listed; none for a chapter itself. */
    fun chapters(memoryKind: String, memoryId: String, scopeNodes: List<me.raddatz.veyq.mobile.navigation.NodeRef>): List<me.raddatz.veyq.mobile.navigation.NodeRef> =
        if (memoryKind != "MEMORY") emptyList() else scopeNodes.filter { it.parentId == memoryId && it.kind == "CHAPTER" }

    /** The heavy EXIF tier (`media-exif`, the web's `MediaExif`): what does not parse is absent. */
    class Exif(
        val make: String?, val model: String?, val lens: String?, val focalLength: Double?, val focalLength35: Double?,
        val fNumber: Double?, val exposureTime: Double?, val exposureCompensation: Double?, val iso: Double?, val flash: Double?,
        val software: String?, val artist: String?, val copyright: String?, val rating: Double?, val title: String?,
        val description: String?, val altitude: Double?, val imgDirection: Double?, val imgDirectionRef: String?,
        val gpsAccuracy: Double?, val width: Double?, val height: Double?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** [item]'s heavy EXIF tier, or null where none was sealed or it does not open (`loadMediaExif`). A member's read. */
    @Throws(Exception::class)
    suspend fun exif(tenant: TenantSession, item: MemoryMedia.Item): Exif? {
        val api = MediaApi(tenant.base, tenant.account.http)
        val wrapped = tenant.account.call { api.getMediaExif(item.scopeId, item.mediaId) }.wrappedExif ?: return null
        return openExif(tenant.account.suite, item.scopeId, item.mediaId, item.key, wrapped)
    }

    internal fun openExif(suite: me.raddatz.veyq.mobile.crypto.V1, scopeId: String, mediaId: String, key: ByteArray, wrapped: String): Exif? = runCatching {
        val text = suite.openField(Context("media-exif", "scopeId" to scopeId, "mediaId" to mediaId), key, unb64(wrapped)).decodeToString()
        exifOf(json.parseToJsonElement(text) as JsonObject)
    }.getOrNull()

    internal fun exifOf(o: JsonObject): Exif {
        fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        return Exif(
            str("make"), str("model"), str("lens"), num("focalLength"), num("focalLength35"), num("fNumber"), num("exposureTime"),
            num("exposureCompensation"), num("iso"), num("flash"), str("software"), str("artist"), str("copyright"), num("rating"),
            str("title"), str("description"), num("altitude"), num("imgDirection"), str("imgDirectionRef"), num("gpsAccuracy"),
            num("w"), num("h"),
        )
    }

    /** [item]'s rows, with [exif] where it was read. */
    fun rows(item: MemoryMedia.Item, exif: Exif?): List<Row> {
        val rows = ArrayList<Row>()
        fun add(field: Field, value: String?) {
            if (!value.isNullOrEmpty()) rows += Row(field, value)
        }
        item.meta?.effectiveCapturedAt?.let { rows += Row(Field.CAPTURED, "", it) }
        add(Field.FILE_NAME, item.name)
        item.original.size.takeIf { it > 0 }?.let { rows += Row(Field.FILE_SIZE, "", it) }
        add(Field.FILE_FORMAT, item.type)
        if (exif != null) {
            add(Field.CAMERA, listOfNotNull(exif.make, exif.model).joinToString(" ").trim())
            add(Field.LENS, exif.lens)
            add(Field.FOCAL_LENGTH, focal(exif.focalLength))
            add(Field.FOCAL_LENGTH_35, focal(exif.focalLength35))
            add(Field.APERTURE, exif.fNumber?.takeIf { it != 0.0 }?.let { "f/${fixed(it, 1)}" })
            add(Field.EXPOSURE, exposure(exif.exposureTime))
            add(Field.EXPOSURE_COMPENSATION, exif.exposureCompensation?.let { "${if (it > 0) "+" else ""}${fixed(it, 1)} EV" })
            add(Field.ISO, exif.iso?.let { fixed(it, 0) })
            exif.flash?.let { rows += Row(Field.FLASH, "", if (it.toLong() and 1L == 1L) 1 else 0) }
            add(Field.DIMENSIONS, if (exif.width != null && exif.height != null && exif.width != 0.0 && exif.height != 0.0) "${fixed(exif.width, 0)} × ${fixed(exif.height, 0)} px" else null)
            add(Field.TITLE, exif.title)
            add(Field.DESCRIPTION, exif.description)
            add(Field.RATING, stars(exif.rating))
            add(Field.SOFTWARE, exif.software)
            add(Field.ARTIST, exif.artist)
            add(Field.COPYRIGHT, exif.copyright)
            add(Field.ALTITUDE, exif.altitude?.let { "${fixed(it, 0)} m" })
            exif.imgDirection?.let { rows += Row(if (exif.imgDirectionRef == "M") Field.DIRECTION_MAGNETIC else Field.DIRECTION_TRUE, "", it.roundToLong()) }
            add(Field.GPS_ACCURACY, exif.gpsAccuracy?.let { "${fixed(it, 1)} m" })
        }
        if (item.meta?.capturedAtOverride != null) rows += Row(Field.CORRECTED, "")
        add(Field.PLACE, item.meta?.placeName)
        val lat = item.meta?.gpsLat
        val lng = item.meta?.gpsLng
        if (lat != null && lng != null) add(Field.LOCATION, "${fixed(lat, 6, keepZeros = true)}, ${fixed(lng, 6, keepZeros = true)}")
        return rows
    }

    private fun focal(mm: Double?): String? = mm?.takeIf { it != 0.0 }?.let { "${it.roundToLong()} mm" }

    private fun exposure(s: Double?): String? {
        if (s == null || s <= 0) return null
        return if (s >= 1) "${fixed(s, 1)} s" else "1/${(1 / s).roundToLong()} s"
    }

    /** A 0..5 rating drawn as stars (it reads the same in every language); none at 0 or below. */
    private fun stars(r: Double?): String? {
        if (r == null || r <= 0) return null
        val n = r.roundToLong().coerceIn(0, 5).toInt()
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    /**
     * [x] to [digits] decimals as JavaScript's `Number(x.toFixed(digits))` prints it — trailing zeros dropped — or, with
     * [keepZeros], as `toFixed` itself does.
     */
    internal fun fixed(x: Double, digits: Int, keepZeros: Boolean = false): String {
        var m = 1L
        repeat(digits) { m *= 10 }
        val q = (abs(x) * m).roundToLong()
        val whole = q / m
        var frac = (q % m).toString().padStart(digits, '0')
        if (!keepZeros) frac = frac.trimEnd('0')
        val sign = if (x < 0 && q != 0L) "-" else ""
        return if (digits == 0 || frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
    }

    /** The name [item]'s original is saved under (`originalDownloadName`): its uploaded name, else the Memory's and its place. */
    fun downloadName(item: MemoryMedia.Item, memoryName: String, index: Int): String {
        val base = memoryName.replace(Regex("[\\\\/ -]+"), " ").trim().ifEmpty { "memory" }
        val ext = DownloadName.extensionOf(item.type) ?: "bin"
        return DownloadName.of(item.type, "$base ${index + 1}.$ext", item.name)
    }

    enum class UploaderKind { MEMBER, FORMER, GUEST }

    /** Who added a medium: a member by name, one who left, or a link's guest (its name is the buckets', not read here yet). */
    class Uploader(val kind: UploaderKind, val name: String? = null)

    /**
     * Who added each of [items], by its owner key (`useUploaderNames`): `user:<id>` a member, named by core's display
     * rule while in the space, else a former member; `share:<identity>` a link's guest. Empty where the space's members could
     * not be read (a member is then left unnamed, never "former").
     */
    @Throws(Exception::class)
    suspend fun uploaders(tenant: TenantSession, scopeId: String, items: List<MemoryMedia.Item>): Map<String, Uploader> {
        val keys = items.mapNotNull { it.ownerKey }.toSet()
        if (keys.isEmpty()) return emptyMap()
        val members = runCatching {
            tenant.account.call { ScopesApi(tenant.base, tenant.account.http).listScopeMembers(scopeId) }
        }.getOrNull()
        val out = HashMap<String, Uploader>()
        for (key in keys) {
            when {
                key.startsWith("share:") -> out[key] = Uploader(UploaderKind.GUEST)
                key.startsWith("user:") && members != null -> {
                    val userId = key.removePrefix("user:")
                    val member = members.firstOrNull { it.userId == userId }
                    out[key] = if (member == null) Uploader(UploaderKind.FORMER) else Uploader(UploaderKind.MEMBER, tenant.people[userId]?.displayName ?: member.username)
                }
            }
        }
        return out
    }
}
