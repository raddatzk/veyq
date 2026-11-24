package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.music.Frames
import me.raddatz.veyq.mobile.memories.music.Wav
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Memories' rules against `contracts/memories/v1-vectors.json` (ADR 0155 decision 7.3, § Memories' move): a medium's display
 * aspect and the justified grid ([MediaGridLayout]), a Memory's medoid ([MemoryLocation.medoid]), the participants' merge
 * ([Participants.merge]), the audio frame headers and a raw ADTS file's count ([Frames]) and a WAV's header, samples and ends
 * ([Wav]) — on the JVM and on `macosArm64`; the web answers the same cases through `@veyq/core-js/memories` (the package's
 * `memories.test.ts`, the web's `features/memories/vectors.test.ts`). A failing case names its section and name.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :apps:memories:jvmTest
 * --tests '*MemoriesVectorsTest*'` records every `expected` as JavaScript's `JSON.stringify(vectors, null, 2)` lays it out;
 * the inputs are kept. Read each changed case against its rule before committing it.
 */
class MemoriesVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun dbl(e: JsonElement?): Double? = if (e == null || e is JsonNull) null else e.jsonPrimitive.double
    private fun strings(e: JsonElement?) = e!!.jsonArray.map { it.jsonPrimitive.content }
    private fun bytes(case: JsonObject, field: String) = unb64(case[field]!!.jsonPrimitive.content)

    /** A double as `JSON.stringify` writes it: an integral one without a fraction. */
    private fun num(d: Double): JsonElement =
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private fun format(f: Wav.Format) = obj(
        "channels" to JsonPrimitive(f.channels), "sampleRate" to JsonPrimitive(f.sampleRate),
        "bitsPerSample" to JsonPrimitive(f.bitsPerSample), "float" to JsonPrimitive(f.float), "blockAlign" to JsonPrimitive(f.blockAlign),
        "dataOffset" to JsonPrimitive(f.dataOffset), "dataBytes" to JsonPrimitive(f.dataBytes),
    )

    private fun formatOf(e: JsonElement?): Wav.Format {
        val o = e!!.jsonObject
        return Wav.Format(
            o["channels"]!!.jsonPrimitive.int, o["sampleRate"]!!.jsonPrimitive.int, o["bitsPerSample"]!!.jsonPrimitive.int,
            o["float"]!!.jsonPrimitive.boolean, o["blockAlign"]!!.jsonPrimitive.int, o["dataOffset"]!!.jsonPrimitive.long,
            o["dataBytes"]!!.jsonPrimitive.long,
        )
    }

    private val answers: Map<String, (JsonObject) -> JsonElement> = mapOf(
        "aspect" to { c -> num(MediaGridLayout.displayAspectOf(dbl(c["width"]), dbl(c["height"]))) },
        "grid" to { c ->
            val items = c["items"]!!.jsonArray.map { MediaGridLayout.Source(it.jsonObject["id"]!!.jsonPrimitive.content, dbl(it.jsonObject["aspect"])) }
            val l = MediaGridLayout.justify(
                items, c["containerWidth"]!!.jsonPrimitive.double, c["targetHeight"]!!.jsonPrimitive.double,
                c["gap"]!!.jsonPrimitive.double, c["showWidows"]!!.jsonPrimitive.boolean,
            )
            obj(
                "tiles" to JsonArray(l.tiles.map { t ->
                    obj(
                        "id" to JsonPrimitive(t.id), "left" to num(t.left), "top" to num(t.top), "width" to num(t.width),
                        "height" to num(t.height), "widow" to JsonPrimitive(t.widow),
                    )
                }),
                "height" to num(l.height),
            )
        },
        "medoid" to { c ->
            val points = c["points"]!!.jsonArray.map { LatLng(it.jsonArray[0].jsonPrimitive.double, it.jsonArray[1].jsonPrimitive.double) }
            MemoryLocation.medoid(points)?.let { JsonArray(listOf(num(it.lat), num(it.lng))) } ?: JsonNull
        },
        "participants" to { c -> JsonArray(Participants.merge(strings(c["stored"]), strings(c["wanted"]), strings(c["openedWith"])).map(::JsonPrimitive)) },
        "mp3Frame" to { c -> JsonPrimitive(Frames.mp3FrameLength(bytes(c, "bytes"), c["at"]!!.jsonPrimitive.int)) },
        "adtsFrame" to { c ->
            Frames.adtsFrame(bytes(c, "bytes"), c["at"]!!.jsonPrimitive.int)?.let {
                obj("length" to JsonPrimitive(it.length), "sampleRate" to JsonPrimitive(it.sampleRate), "samples" to JsonPrimitive(it.samples))
            } ?: JsonNull
        },
        "frameChain" to { c ->
            val frameAt: (ByteArray, Int) -> Int = if (c["kind"]!!.jsonPrimitive.content == "adts") Frames::adtsFrameLength else Frames::mp3FrameLength
            JsonPrimitive(Frames.firstFrameChain(bytes(c, "bytes"), frameAt, c["chain"]!!.jsonPrimitive.int))
        },
        "adtsCount" to { c ->
            val n = Frames.adtsCount(bytes(c, "chunk"), c["at"]!!.jsonPrimitive.double, c["size"]!!.jsonPrimitive.double, c["seconds"]!!.jsonPrimitive.double)
            obj("read" to JsonPrimitive(n.read), "seconds" to num(n.seconds))
        },
        "adtsEnd" to { c ->
            Frames.adtsEnd(c["seconds"]!!.jsonPrimitive.double, c["remaining"]!!.jsonPrimitive.double)?.let(::JsonPrimitive) ?: JsonNull
        },
        "wavHeader" to { c ->
            try {
                Wav.parseHeader(bytes(c, "head"), c["fileSize"]!!.jsonPrimitive.long)?.let(::format) ?: JsonNull
            } catch (e: Wav.Unreadable) {
                obj("error" to JsonPrimitive(e.message))
            }
        },
        "wavSamples" to { c ->
            val d = Wav.samples(bytes(c, "bytes"), formatOf(c["format"]))
            JsonArray(d.channels.map { ch -> JsonArray(ch.map { num(it.toDouble()) }) })
        },
        "wavEnds" to { c -> JsonPrimitive(Wav.endBytes(formatOf(c["format"]), c["limitMs"]!!.jsonPrimitive.long)) },
    )

    @Test
    fun everyCaseAsTheCoreAnswersIt() {
        val failures = ArrayList<String>()
        val written = LinkedHashMap<String, JsonElement>(vectors)
        for ((section, answer) in answers) {
            val cases = vectors[section]?.jsonArray ?: error("the vectors hold no section $section")
            assertTrue(cases.isNotEmpty(), "$section holds cases")
            written[section] = JsonArray(cases.mapIndexed { i, c ->
                val case = c.jsonObject
                val actual = answer(case)
                val name = case["name"]?.jsonPrimitive?.content ?: "#$i"
                if (case["expected"] != actual) failures += "$section '$name': expected ${case["expected"]}, the core answers $actual"
                JsonObject(case + ("expected" to actual))
            })
        }
        if (writing) {
            writeRepoFile(FILE, stringify(JsonObject(written), "") + "\n")
            return
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` lays it out, so a rewrite diffs only where an answer changed. */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A string as `JSON.stringify` quotes it (the vectors' strings need only the quote and the backslash). */
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val FILE = "contracts/memories/v1-vectors.json"
    }
}
