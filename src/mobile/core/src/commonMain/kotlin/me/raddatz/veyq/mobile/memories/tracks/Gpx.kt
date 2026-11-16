package me.raddatz.veyq.mobile.memories.tracks

import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.GalleryMedium
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A GPX track, read on the device only (ADR 0130 decisions 1, 2 and 4; ADR 0143 D8) — the port of the web's
 * `track/gpx.ts` over the core's own [Xml] reader: what counts as a GPX file, how it is read, what is drawn and the numbers
 * shown beside it. Pure: no key, no request.
 */
object Gpx {
    /** The type a track's original is sealed under (the web's `GPX_MEDIA_TYPE`). */
    const val MEDIA_TYPE = GalleryMedium.GPX_TYPE

    /** The largest GPX file a track is made from (ADR 0130 decision 2): refused on the device before anything is uploaded. */
    const val MAX_BYTES = 25L * 1024 * 1024

    /** How many tracks one Memory or chapter holds at most (ADR 0130 decision 2), held by the client that adds one. */
    const val PER_MEMORY = 50

    /** How many points of one segment are drawn at most; the numbers are computed over every point. */
    const val DRAWN_POINTS_MAX = 2000

    /** One point: degrees, metres above sea level and epoch milliseconds where the file has them. */
    data class Point(val lat: Double, val lng: Double, val ele: Double? = null, val time: Long? = null)

    /** A track as its file holds it: its own name (null where none) and its segments, each a line of its own. */
    data class Parsed(val name: String?, val segments: List<List<Point>>)

    /** The numbers beside a track; a field the file cannot give is null. */
    data class Stats(
        val distanceMeters: Double,
        val durationMs: Long? = null,
        val ascentMeters: Double? = null,
        val descentMeters: Double? = null,
        val startedAt: Long? = null,
    )

    /** Why a picked file is no track — each its own words, naming the file. */
    enum class Problem(val msg: Msg) {
        TOO_LARGE(Msg.TRACK_PROBLEM_TOO_LARGE),
        INVALID(Msg.TRACK_PROBLEM_INVALID),
        TOO_SHORT(Msg.TRACK_PROBLEM_TOO_SHORT),
        TOO_MANY(Msg.TRACK_PROBLEM_TOO_MANY);

        /** What is said of file [name] refused for this. */
        fun notice(name: String) = Notice(msg, name)
    }

    /** A text that does not read as a track, with why ([Problem.INVALID] or [Problem.TOO_SHORT]). */
    class Refused(val problem: Problem) : Exception("gpx_${problem.name.lowercase()}")

    /** Whether a picked file is offered as GPX: by its type, else by its extension (`isGpxFile`). */
    fun isGpxFile(name: String, type: String?): Boolean = type == MEDIA_TYPE || name.lowercase().endsWith(".gpx")

    private val NUMBER = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /** JavaScript's `Number(text)` for what a GPX file holds: a decimal, else null (never a type suffix or a hex). */
    private fun number(text: String?): Double? {
        val t = text?.trim() ?: return null
        if (!NUMBER.matches(t)) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    @OptIn(ExperimentalTime::class)
    private fun timeOf(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    /** One `trkpt`/`rtept`, or null for a point whose coordinates are missing or out of range (it is skipped). */
    private fun pointOf(el: Xml.Element): Point? {
        val lat = number(el.attributes["lat"]) ?: return null
        val lng = number(el.attributes["lon"]) ?: return null
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
        // an elevation outside what the earth has is a broken file's, not a place's: left out, the point kept
        val ele = number(el.textOf("ele"))?.takeIf { abs(it) <= 100_000 }
        return Point(lat, lng, ele, timeOf(el.textOf("time")))
    }

    /**
     * A GPX document read (ADR 0130 decision 2): its tracks' segments (`trk`/`trkseg`/`trkpt`) are the lines; a file with
     * none is read as its routes (`rte`/`rtept`), one line each; waypoints are not drawn. The name is the first track's
     * `name`, else the file's `metadata/name`, else the first route's. Throws [Refused]: [Problem.INVALID] for a document
     * that is no GPX (not XML, another root, a document type declaration — refused before anything is read, so no entity
     * is ever declared or expanded), [Problem.TOO_SHORT] for one with fewer than two usable points.
     */
    fun parse(text: String): Parsed {
        // the document type's refusal is the reader's, before it reads a byte (Xml.parse): one place, one bite
        val root = try {
            Xml.parse(text)
        } catch (_: Xml.Malformed) {
            throw Refused(Problem.INVALID)
        }
        if (root.name != "gpx") throw Refused(Problem.INVALID)
        fun lines(groups: List<Xml.Element>, point: String) =
            groups.map { g -> g.children(point).mapNotNull(::pointOf) }.filter { it.isNotEmpty() }
        val tracks = root.children("trk")
        val routes = root.children("rte")
        var segments = lines(tracks.flatMap { it.children("trkseg") }, "trkpt")
        if (segments.isEmpty()) segments = lines(routes, "rtept")
        if (segments.sumOf { it.size } < 2) throw Refused(Problem.TOO_SHORT)
        val name = tracks.firstOrNull()?.textOf("name")
            ?: root.children("metadata").firstOrNull()?.textOf("name")
            ?: routes.firstOrNull()?.textOf("name")
        return Parsed(name, segments)
    }

    /** A picked file's bytes read as a track: UTF-8 text (bytes that are none are [Problem.INVALID]), then [parse]. */
    fun read(bytes: ByteArray): Parsed {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw Refused(Problem.INVALID)
        }
        return parse(text)
    }

    /** The name a track is listed under: its file's own, else the file name without `.gpx`, else none (`trackName`). */
    fun trackName(own: String?, fileName: String?): String? {
        own?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return fileName?.trim()?.replace(Regex("""\.gpx$""", RegexOption.IGNORE_CASE), "")?.trim()?.ifEmpty { null }
    }

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** The great-circle distance between two points, in metres (haversine). */
    fun distanceMeters(a: Point, b: Point): Double {
        val rad = kotlin.math.PI / 180
        val dLat = (b.lat - a.lat) * rad
        val dLng = (b.lng - a.lng) * rad
        val s1 = sin(dLat / 2)
        val s2 = sin(dLng / 2)
        val h = s1 * s1 + cos(a.lat * rad) * cos(b.lat * rad) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** How much an elevation must change before it counts as a climb or a descent (a GPS wobble is not a hill). */
    private const val ELEVATION_STEP_M = 3.0

    /**
     * The numbers beside a track (ADR 0130 decision 4), over every point: the distance along each segment (the gaps
     * between segments are not walked), the time from its first to its last timestamp, and the climb and the descent
     * counted once the elevation moved by [ELEVATION_STEP_M] from the last counted level.
     */
    fun stats(segments: List<List<Point>>): Stats {
        var distance = 0.0
        var first: Long? = null
        var last: Long? = null
        var ascent = 0.0
        var descent = 0.0
        var anyEle = false
        for (segment in segments) {
            var level: Double? = null
            for ((i, p) in segment.withIndex()) {
                if (i > 0) distance += distanceMeters(segment[i - 1], p)
                p.time?.let { t ->
                    first = first?.let { minOf(it, t) } ?: t
                    last = last?.let { maxOf(it, t) } ?: t
                }
                val ele = p.ele ?: continue
                anyEle = true
                val l = level
                if (l == null) level = ele
                else if (ele - l >= ELEVATION_STEP_M) {
                    ascent += ele - l
                    level = ele
                } else if (l - ele >= ELEVATION_STEP_M) {
                    descent += l - ele
                    level = ele
                }
            }
        }
        val f = first
        val la = last
        return Stats(
            distanceMeters = distance,
            durationMs = if (f != null && la != null && la > f) la - f else null,
            ascentMeters = if (anyEle) ascent else null,
            descentMeters = if (anyEle) descent else null,
            startedAt = f,
        )
    }

    /** A segment as it is drawn: at most [max] points, evenly picked by index, its first and last always kept. */
    fun <T> simplifyLine(points: List<T>, max: Int = DRAWN_POINTS_MAX): List<T> {
        if (points.size <= max) return points.toList()
        val step = (points.size - 1).toDouble() / (max - 1)
        val out = ArrayList<T>(max)
        for (i in 0 until max - 1) out += points[jsRound(i * step)]
        out += points.last()
        return out
    }

    /** JavaScript's `Math.round` (halves up, toward +∞). */
    private fun jsRound(v: Double): Int = kotlin.math.floor(v + 0.5).toInt()

    /** The colours tracks are drawn in, by their place in the list (ARGB); a Memory with more tracks starts again. */
    val COLORS: List<Long> = listOf(0xFF7C6CF0, 0xFF0E8FD0, 0xFF0F9D6E, 0xFFD88A06, 0xFFDC3B3B, 0xFFD6457F)

    /** The colour of the track at [index] in the list. */
    fun color(index: Int): Long = COLORS[((index % COLORS.size) + COLORS.size) % COLORS.size]

    /** A checked file: refused with why, or the track it reads as. */
    sealed interface Check {
        data class Refused(val problem: Problem) : Check
        data class Taken(val parsed: Parsed) : Check
    }

    /**
     * A picked file checked before it is uploaded as a track (ADR 0130 decision 2), in the web's order: room in the node
     * ([held] tracks already), its [size], then that its bytes ([read], called only past both) read as a GPX track.
     */
    suspend fun check(size: Long, held: Int, read: suspend () -> ByteArray): Check {
        if (held >= PER_MEMORY) return Check.Refused(Problem.TOO_MANY)
        if (size > MAX_BYTES) return Check.Refused(Problem.TOO_LARGE)
        return try {
            Check.Taken(read(read()))
        } catch (e: Refused) {
            Check.Refused(e.problem)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Check.Refused(Problem.INVALID)
        }
    }

    /** A drawn line: each segment simplified ([simplifyLine]) to its coordinates. */
    fun lines(parsed: Parsed): List<List<LatLng>> = parsed.segments.map { s -> simplifyLine(s).map { LatLng(it.lat, it.lng) } }

    /** A length rounded as the list says it: whole metres under one kilometre, else kilometres to one decimal. */
    fun roundedDistance(meters: Double): Pair<Double, Boolean> =
        if (meters < 1000) jsRound(meters).toDouble() to false else (jsRound(meters / 100) / 10.0) to true

    /** Whole minutes of [ms], as the list says a duration (`Math.round`). */
    internal fun minutes(ms: Long): Int = (ms / 60_000.0).roundToInt()
}
