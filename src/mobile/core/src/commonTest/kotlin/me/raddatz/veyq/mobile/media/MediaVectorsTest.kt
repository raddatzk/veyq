package me.raddatz.veyq.mobile.media

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The media headers against `contracts/platform/media/v1-vectors.json` (ADR 0155 decision 7.2, the fifth slice): an image's
 * size ([ImageHeader]), a video's size and capture time ([VideoHeader]) and a photo's tags ([ExifReader], under the web's
 * names, per scope), with what each read may reach (`bounds`), on the JVM and on `macosArm64` (and on Linux and Windows
 * through `run.py test`); the web runs the same cases through `@veyq/core-js/media` (the package's `media.test.ts`, the web's
 * `media.vectors.test.ts`). A failing case names its section, index and rule.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :core:jvmTest --tests
 * '*MediaVectorsTest*'` records every case's `expected` from the core, as JavaScript's `JSON.stringify(vectors, null, 2)`
 * writes it. A tag's date is written as the wall clock it names; the writer keeps a case's wall clock where the core reads
 * the same instant and fails where it does not — such a change is written by hand, its rule read first.
 */
class MediaVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"
    private val now = vectors["now"]!!.jsonPrimitive.double

    /** A case's bytes from its parts: hex, `{fill, count}`, `{repeat, times}`. */
    private fun bytesOf(case: JsonObject): ByteArray {
        val parts = case["bytes"]!!.jsonArray
        fun length(p: JsonElement): Int = if (p is JsonPrimitive) p.content.length / 2 else p.jsonObject.let { o ->
            if ("fill" in o) o["count"]!!.jsonPrimitive.int else o["times"]!!.jsonPrimitive.int * o["repeat"]!!.jsonPrimitive.content.length / 2
        }
        val out = ByteArray(parts.sumOf { length(it) })
        var at = 0
        fun hex(h: String) {
            for (i in 0 until h.length / 2) out[at++] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        for (p in parts) {
            if (p is JsonPrimitive) hex(p.content)
            else {
                val o = p.jsonObject
                if ("fill" in o) {
                    out.fill(o["fill"]!!.jsonPrimitive.int.toByte(), at, at + o["count"]!!.jsonPrimitive.int)
                    at += o["count"]!!.jsonPrimitive.int
                } else repeat(o["times"]!!.jsonPrimitive.int) { hex(o["repeat"]!!.jsonPrimitive.content) }
            }
        }
        return out
    }

    /** A file over [b] that keeps the furthest byte a read asked for and the sum of what the reads asked for. */
    private class Recording(val b: ByteArray) {
        var end = 0.0
        var bytes = 0.0
        val file = MediaBytes(b.size.toDouble()) { from, length ->
            end = max(end, from + length)
            bytes += length
            b.copyOfRange(from.toInt(), minOf(b.size, from.toInt() + length))
        }
    }

    /** A number as JavaScript writes it: a whole one without a fraction. */
    private fun num(d: Double): JsonPrimitive = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.007199254740992E15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
    private fun num(d: Double?): JsonElement = d?.let(::num) ?: JsonNull
    private fun size(s: ImageHeader.Size?): JsonElement = s?.let { JsonObject(linkedMapOf("w" to num(it.width), "h" to num(it.height))) } ?: JsonNull

    /** A wall clock `YYYY-MM-DDTHH:MM:SS` as the instant the device's zone gives it. */
    private fun instantOf(wall: String): Double? = localWallClockMs(
        wall.substring(0, 4).toInt(), wall.substring(5, 7).toInt(), wall.substring(8, 10).toInt(),
        wall.substring(11, 13).toInt(), wall.substring(14, 16).toInt(), wall.substring(17, 19).toInt(),
    )

    private val dates = setOf("DateTimeDigitized", "DateTimeOriginal", "DateTime", "xmpCapturedAt")

    /** [t]'s fields under the web's names for [scope] (`date`, `xmpDate`, `all`), sorted by name; dates as instants. */
    private fun shown(t: ExifTags?, scope: String): Map<String, Any>? {
        t ?: return null
        val all = linkedMapOf<String, Any?>(
            "DateTimeOriginal" to t.dateTimeOriginalMs, "DateTimeDigitized" to t.dateTimeDigitizedMs, "DateTime" to t.dateTimeMs,
        )
        if (scope == "xmpDate") all.clear()
        if (scope != "date") all["xmpCapturedAt"] = t.xmpCapturedAtMs
        if (scope == "all") all.putAll(
            linkedMapOf(
                "Make" to t.make, "Model" to t.model, "Orientation" to t.orientation, "Software" to t.software, "Artist" to t.artist,
                "Copyright" to t.copyright, "Rating" to t.rating, "ImageDescription" to t.imageDescription, "ImageWidth" to t.imageWidth,
                "ImageLength" to t.imageLength, "ExposureTime" to t.exposureTime, "FNumber" to t.fNumber,
                "ISOSpeedRatings" to t.isoSpeedRatings, "ExposureBiasValue" to t.exposureBiasValue, "Flash" to t.flash,
                "FocalLength" to t.focalLength, "FocalLengthIn35mmFilm" to t.focalLengthIn35mmFilm,
                "PixelXDimension" to t.pixelXDimension, "PixelYDimension" to t.pixelYDimension, "LensMake" to t.lensMake,
                "LensModel" to t.lensModel, "GPSAltitude" to t.gpsAltitude, "GPSAltitudeRef" to t.gpsAltitudeRef,
                "GPSImgDirection" to t.gpsImgDirection, "GPSImgDirectionRef" to t.gpsImgDirectionRef,
                "GPSHPositioningError" to t.gpsHPositioningError, "latitude" to t.latitude, "longitude" to t.longitude,
                "xmpCreatorTool" to t.xmpCreatorTool, "xmpRating" to t.xmpRating, "xmpTitle" to t.xmpTitle,
                "xmpDescription" to t.xmpDescription, "iptcObjectName" to t.iptcObjectName,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val present = all.filterValues { it != null }.entries.sortedBy { it.key }.associate { it.key to it.value } as Map<String, Any>
        return present.ifEmpty { null }
    }

    /** The core's answer as JSON; a date as the case's own wall clock where it names the same instant (see the class). */
    private fun json(answer: Map<String, Any>?, recorded: JsonElement?, where: String): JsonElement {
        answer ?: return JsonNull
        val old = recorded as? JsonObject
        return JsonObject(
            answer.mapValues { (k, v) ->
                when {
                    k in dates -> {
                        val wall = (old?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
                        check(wall != null && instantOf(wall) == v as Double) { "$where: $k is ${v} — not the recorded wall clock $wall; write it by hand" }
                        JsonPrimitive(wall)
                    }
                    v is String -> JsonPrimitive(v)
                    else -> num(v as Double)
                }
            },
        )
    }

    /** Whether two answers are the same: numbers by value, the rest as JSON. */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull -> a.double == b.double
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }

    /** Each section's answer to one case, and the reads it made. */
    private suspend fun answer(section: String, case: JsonObject): Pair<JsonElement, Recording> {
        val rec = Recording(bytesOf(case))
        val where = "$section ${case["rule"]?.jsonPrimitive?.content}"
        val got: JsonElement = when (section) {
            "images" -> size(ImageHeader.size(rec.file))
            "videos" -> JsonObject(
                linkedMapOf(
                    "size" to size(VideoHeader.dimensions(rec.file)),
                    "capturedAt" to num(VideoHeader.captureTime(Recording(rec.b).file, now)),
                ),
            )
            else -> {
                val tags = ExifReader.read(rec.file)
                val recorded = case["expected"] as? JsonObject
                JsonObject(linkedMapOf("date" to "date", "xmpDate" to "xmpDate", "all" to "all").mapValues { (_, scope) ->
                    json(shown(tags, scope), recorded?.get(scope), "$where ($scope)")
                })
            }
        }
        return got to rec
    }

    private fun section(name: String) = runTest {
        if (writing) return@runTest
        val cases = vectors[name]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "$name holds no case")
        val failures = ArrayList<String>()
        cases.forEachIndexed { i, c ->
            val case = c.jsonObject
            val rule = case["rule"]?.jsonPrimitive?.content ?: ""
            val (got, rec) = answer(name, case)
            if (!same(got, case["expected"]!!)) failures += "$name #$i $rule: expected ${case["expected"]}, got $got"
            val bounds = case["bounds"] as? JsonObject
            bounds?.get("end")?.jsonPrimitive?.double?.let { if (rec.end > it) failures += "$name #$i $rule: read up to byte ${rec.end}, past $it" }
            bounds?.get("bytes")?.jsonPrimitive?.double?.let { if (rec.bytes > it) failures += "$name #$i $rule: read ${rec.bytes} bytes, more than $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun theFileIsTheFormatThisTestReads() = assertEquals("veyq-media-headers-v1", vectors["format"]!!.jsonPrimitive.content)
    @Test fun images() = section("images")
    @Test fun videos() = section("videos")
    @Test fun exif() = section("exif")

    /** The write mode (see the class): every section's answers as its cases' `expected`. */
    @Test
    fun writeTheVectors() = runTest {
        if (!writing) return@runTest
        val recorded = LinkedHashMap<String, JsonElement>()
        for ((key, value) in vectors) {
            if (key !in setOf("images", "videos", "exif")) {
                recorded[key] = value
                continue
            }
            recorded[key] = JsonArray(value.jsonArray.map { c ->
                val case = c.jsonObject
                val got = answer(key, case).first
                JsonObject(if ("expected" in case) case.mapValues { (k, v) -> if (k == "expected") got else v } else case + ("expected" to got))
            })
        }
        writeRepoFile(FILE, stringify(JsonObject(recorded), "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it, so a rewrite diffs only where an answer changed. */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A string as `JSON.stringify` quotes it: the short escapes, `\u` for the other controls. */
    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        const val FILE = "contracts/platform/media/v1-vectors.json"
    }
}
