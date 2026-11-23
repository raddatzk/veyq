package me.raddatz.veyq.mobile.export

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
import kotlinx.serialization.json.long
import me.raddatz.veyq.mobile.json.KotlinxJsonAccess
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A download's archive against `contracts/platform/export/v1-vectors.json` (ADR 0155 decision 7.2, the sixth slice): the
 * CRC-32 ([Crc32]), the DOS stamp, every written archive byte for byte and the sink's write count ([ZipWriter]), the exact
 * length before the first byte ([ZipWriter.length]), what the reader answers and refuses and how much it reads to open an
 * archive ([ZipReader]), the manifest's check and its payload walk ([ManifestRules]) — on the JVM and on `macosArm64` (and
 * on Linux and Windows through `run.py test`); the web runs the same cases through `@veyq/core-js/export` (the package's
 * `export.test.ts`, the web's `archive.vectors.test.ts`). A failing case names its section, index and rule.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :core:jvmTest --tests
 * '*ArchiveVectorsTest*'` records every case's `expected` from the core, as JavaScript's `JSON.stringify(vectors, null, 2)`
 * writes it; a read's `bounds` is kept as written (a cap, not an answer).
 */
class ArchiveVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun str(e: JsonElement?) = e!!.jsonPrimitive.content
    private fun num(n: Long) = JsonPrimitive(n)
    private fun obj(vararg fields: Pair<String, JsonElement?>) = JsonObject(LinkedHashMap<String, JsonElement>().apply { for ((k, v) in fields) if (v != null) put(k, v) })

    /** A case's bytes from its parts: hex, `{fill, count}`, `{repeat, times}`. */
    private fun bytesOf(parts: JsonArray): ByteArray {
        val out = ArrayList<Byte>()
        fun hex(h: String) {
            for (i in 0 until h.length / 2) out += h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        for (p in parts) {
            if (p is JsonPrimitive) hex(p.content)
            else {
                val o = p.jsonObject
                if ("fill" in o) repeat(o["count"]!!.jsonPrimitive.int) { out += o["fill"]!!.jsonPrimitive.int.toByte() }
                else repeat(o["times"]!!.jsonPrimitive.int) { hex(str(o["repeat"])) }
            }
        }
        return out.toByteArray()
    }

    private fun pathOf(p: JsonElement): String = if (p is JsonPrimitive) p.content else p.jsonObject.let { str(it["repeat"]).repeat(it["times"]!!.jsonPrimitive.int) }

    private fun hexOf(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun sha(b: ByteArray) = hexOf(Sha256().apply { update(b) }.digest())

    private fun codeOf(e: ZipReader.ZipError) = JsonPrimitive(e.code.name.lowercase())

    // --- write ---------------------------------------------------------------------------------------------------------

    private class Written(val bytes: ByteArray?, val writes: Int, val crcs: List<Long>, val error: JsonObject?)

    private suspend fun write(case: JsonObject): Written {
        val parts = ArrayList<ByteArray>()
        var writes = 0
        val zip = ZipWriter({ parts += it.copyOf(); writes++ }, case["modified"]!!.jsonPrimitive.long, case["zip64At"]?.jsonPrimitive?.long ?: ZipWriter.U32_MAX)
        val crcs = ArrayList<Long>()
        val ops = case["ops"]!!.jsonArray
        for ((i, op) in ops.withIndex()) {
            val o = op.jsonObject
            try {
                when {
                    "dir" in o -> zip.addDirectory(pathOf(o["dir"]!!))
                    "file" in o -> {
                        val b = bytesOf(o["bytes"]!!.jsonArray)
                        val path = pathOf(o["file"]!!)
                        val size = ZipWriter.declaredSize(o["size"]?.jsonPrimitive?.double ?: b.size.toDouble(), path)
                        val lengths: List<Int> = o["chunks"]?.jsonArray?.map { it.jsonPrimitive.int }
                            ?: (o["every"]?.jsonPrimitive?.int ?: maxOf(1, b.size)).let { every -> (0 until b.size step every).map { minOf(every, b.size - it) } }
                        crcs += zip.addFile(path, size) { put ->
                            var at = 0
                            for (n in lengths) {
                                put(b.copyOfRange(at, at + n))
                                at += n
                            }
                        }
                    }
                    "many" in o -> {
                        val m = o["many"]!!.jsonObject
                        repeat(m["count"]!!.jsonPrimitive.int) { n -> zip.addFile("${str(m["prefix"])}$n", 0) {} }
                    }
                    else -> zip.finish()
                }
            } catch (e: ZipReader.ZipError) {
                return Written(null, writes, crcs, obj("at" to num(i.toLong()), "code" to codeOf(e)))
            }
        }
        if (ops.none { "finish" in it.jsonObject }) zip.finish()
        val all = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(all, at); at += p.size }
        return Written(all, writes, crcs, null)
    }

    private fun planOf(ops: JsonArray): List<ZipWriter.Planned> = ops.flatMap { op ->
        val o = op.jsonObject
        when {
            "dir" in o -> listOf(ZipWriter.Planned(pathOf(o["dir"]!!), 0, directory = true))
            "file" in o -> listOf(ZipWriter.Planned(pathOf(o["file"]!!), bytesOf(o["bytes"]!!.jsonArray).size.toLong()))
            "many" in o -> o["many"]!!.jsonObject.let { m -> List(m["count"]!!.jsonPrimitive.int) { ZipWriter.Planned("${str(m["prefix"])}$it", 0) } }
            "path" in o -> listOf(ZipWriter.Planned(str(o["path"]), o["size"]?.jsonPrimitive?.long ?: 0, o["directory"]?.jsonPrimitive?.content == "true"))
            else -> emptyList()
        }
    }

    private val archives = HashMap<String, ByteArray>()
    /** What the last [answerRead] read to open its archive. */
    private var lastOpened = 0L

    private suspend fun answerWrite(case: JsonObject): JsonElement {
        val w = write(case)
        if (w.error != null) return obj("error" to w.error)
        val b = w.bytes!!
        val length = ZipWriter.length(planOf(case["ops"]!!.jsonArray), case["zip64At"]?.jsonPrimitive?.long ?: ZipWriter.U32_MAX)
        check(length == b.size.toLong()) { "the planned length $length is not the written ${b.size}" }
        archives[str(case["rule"])] = b
        return obj(
            "length" to num(b.size.toLong()), "sha256" to JsonPrimitive(sha(b)), "hex" to (if (b.size <= 4096) JsonPrimitive(hexOf(b)) else null),
            "writes" to num(w.writes.toLong()), "crc32" to JsonArray(w.crcs.map(::num)),
        )
    }

    // --- read ----------------------------------------------------------------------------------------------------------

    private suspend fun archiveOf(source: JsonObject): ByteArray {
        var b = if ("write" in source) {
            val rule = str(source["write"])
            (archives[rule] ?: run {
                answerWrite(vectors["write"]!!.jsonArray.map { it.jsonObject }.first { str(it["rule"]) == rule })
                archives.getValue(rule)
            }).copyOf()
        } else bytesOf(source["bytes"]!!.jsonArray)
        for (p in source["patch"]?.jsonArray.orEmpty()) bytesOf(JsonArray(listOf(p.jsonObject["hex"]!!))).copyInto(b, p.jsonObject["at"]!!.jsonPrimitive.int)
        source["cut"]?.jsonPrimitive?.int?.let { b = b.copyOf(it) }
        source["comment"]?.let { b += bytesOf(JsonArray(listOf(it))) }
        return b
    }

    private fun entry(e: ZipReader.Entry) = obj("name" to JsonPrimitive(e.name), "size" to num(e.size), "crc32" to num(e.crc32), "offset" to num(e.offset), "dataAt" to num(e.dataAt))

    private suspend fun answerRead(case: JsonObject): Pair<JsonElement, Long> {
        val b = archiveOf(case["archive"]!!.jsonObject)
        var read = 0L
        val zip = try {
            ZipReader.open(b.size.toLong()) { from, length ->
                val until = minOf(b.size.toLong(), from + length).toInt()
                read += until - from
                b.copyOfRange(from.toInt(), until)
            }
        } catch (e: ZipReader.ZipError) {
            return obj("error" to codeOf(e)) to 0L
        }
        val opened = read
        val all = zip.entries.map(::entry)
        val streams = case["stream"]?.jsonArray?.let { names ->
            JsonObject(LinkedHashMap<String, JsonElement>().apply {
                for (n in names) {
                    val name = str(n)
                    val chunks = ArrayList<JsonElement>()
                    val error = try {
                        zip.stream(name) { chunks += num(it.size.toLong()) }
                        null
                    } catch (e: ZipReader.ZipError) {
                        codeOf(e)
                    }
                    put(name, obj("chunks" to JsonArray(chunks), "error" to error))
                }
            })
        }
        val small = case["small"]?.jsonArray?.let { asks ->
            JsonArray(asks.map { a ->
                val name = str(a.jsonObject["name"])
                val max = a.jsonObject["max"]!!.jsonPrimitive.long
                val answer = try {
                    "sha256" to JsonPrimitive(sha(zip.bytes(name, max)))
                } catch (e: ZipReader.ZipError) {
                    "error" to codeOf(e)
                }
                obj("name" to JsonPrimitive(name), "max" to num(max), answer)
            })
        }
        val listed = if (all.size > 50) arrayOf("count" to num(all.size.toLong()), "head" to JsonArray(all.take(3)), "tail" to JsonArray(all.takeLast(3)))
        else arrayOf("entries" to JsonArray(all))
        val recorded = (case["expected"] as? JsonObject)?.get("bounds")
        return obj(*listed, "streams" to streams, "small" to small, "bounds" to (recorded ?: obj("open" to num(opened)))) to opened
    }

    // --- manifest ------------------------------------------------------------------------------------------------------

    private fun folder(id: String, path: String, children: List<JsonElement> = emptyList()) = obj(
        "id" to JsonPrimitive(id), "kind" to JsonPrimitive("FOLDER"), "name" to JsonPrimitive(path.substringAfterLast('/').let { if (id == "root") "Home" else it }),
        "path" to JsonPrimitive(path), "children" to JsonArray(children),
    )

    private fun built(b: JsonObject): JsonElement {
        val root = b["deep"]?.jsonPrimitive?.int?.let { deep ->
            var node = folder("n$deep", List(deep + 1) { "d" }.joinToString("/"))
            for (i in deep - 1 downTo 0) node = folder("n$i", List(i + 1) { "d" }.joinToString("/"), listOf(node))
            node
        } ?: folder("root", "Home", List(b["wide"]!!.jsonPrimitive.int - 1) { i ->
            obj("id" to JsonPrimitive("c$i"), "kind" to JsonPrimitive("FOLDER"), "name" to JsonPrimitive("c"), "path" to JsonPrimitive("Home/c$i"))
        })
        return obj(
            "format" to JsonPrimitive("veyq-subtree"), "version" to num(2), "exportedAt" to JsonPrimitive("2026-11-23T10:00:00.000Z"), "appVersion" to JsonPrimitive("1"),
            "applications" to JsonArray(listOf(obj("id" to JsonPrimitive("depot"), "contentSchema" to num(1)), obj("id" to JsonPrimitive("memories"), "contentSchema" to num(1)))),
            "root" to root,
        )
    }

    private fun answerManifest(case: JsonObject): JsonElement {
        val json = case["json"] ?: built(case["build"]!!.jsonObject)
        return try {
            ManifestRules.check(KotlinxJsonAccess, json)
            Manifest.parse(json) // the apps' read of a checked manifest never fails
            obj("ok" to JsonPrimitive(true))
        } catch (e: ImportRefused) {
            obj("code" to JsonPrimitive(e.refusal.wire), "detail" to e.detail?.let(::JsonPrimitive))
        }
    }

    // --- every section -------------------------------------------------------------------------------------------------

    private suspend fun answer(section: String, case: JsonObject): JsonElement = when (section) {
        "crc32" -> {
            val b = bytesOf(case["bytes"]!!.jsonArray)
            var state = Crc32.START
            var at = 0
            for (n in case["chunks"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(b.size)) {
                state = Crc32.update(state, b.copyOfRange(at, at + n))
                at += n
            }
            check(Crc32.finish(state) == Crc32.of(b)) { "the chunked CRC differs from the whole" }
            num(Crc32.of(b))
        }
        "dos" -> ZipWriter.dosStamp(case["ms"]!!.jsonPrimitive.long).let { obj("date" to num((it ushr 16).toLong()), "time" to num((it and 0xffff).toLong())) }
        "write" -> answerWrite(case)
        "length" -> num(ZipWriter.length(planOf(case["plan"]!!.jsonArray), case["zip64At"]?.jsonPrimitive?.long ?: ZipWriter.U32_MAX))
        "read" -> answerRead(case).let { lastOpened = it.second; it.first }
        "manifest" -> answerManifest(case)
        "payloads" -> JsonArray(ManifestRules.payloadsOf(KotlinxJsonAccess, case["value"]!!).map { (it as JsonObject)["path"]!! })
        else -> error("no section $section")
    }

    /** Two JSON values alike, a number by its value (a JSON `1` and `1.0` are one number). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull ->
            a.content == b.content || a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } == true
        else -> a == b
    }

    private fun section(name: String) = runTest {
        if (writing) return@runTest
        val cases = vectors[name]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "$name holds no case")
        val failures = ArrayList<String>()
        cases.forEachIndexed { i, c ->
            val case = c.jsonObject
            val rule = case["rule"]?.jsonPrimitive?.content ?: ""
            val got = answer(name, case)
            if (!same(got, case["expected"]!!)) failures += "$name #$i $rule: expected ${case["expected"].toString().take(400)}, got ${got.toString().take(400)}"
            if (name == "read") {
                val opened = lastOpened
                case["expected"]!!.jsonObject["bounds"]?.jsonObject?.get("open")?.jsonPrimitive?.long?.let {
                    if (opened > it) failures += "$name #$i $rule: opening read $opened bytes, more than $it"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun theFileIsTheFormatThisTestReads() = assertEquals("veyq-export-archive-v1", str(vectors["format"]))

    @Test
    fun theFactsAreTheCores() = assertEquals(facts(), vectors["facts"])

    private fun facts() = obj(
        "format" to JsonPrimitive(ManifestRules.FORMAT), "version" to num(ManifestRules.VERSION.toLong()), "path" to JsonPrimitive(ManifestRules.PATH),
        "pathV1" to JsonPrimitive(ManifestRules.PATH_V1), "maxBytes" to num(ManifestRules.MAX_BYTES), "maxDepth" to num(ManifestRules.MAX_DEPTH.toLong()),
        "maxNodes" to num(ManifestRules.MAX_NODES.toLong()), "limits" to JsonObject(Manifest.LIMITS.mapValues { num(it.value.toLong()) }),
        "readChunk" to num(ZipReader.READ_CHUNK.toLong()),
    )

    @Test fun crc32() = section("crc32")
    @Test fun dos() = section("dos")
    @Test fun write() = section("write")
    @Test fun length() = section("length")
    @Test fun read() = section("read")
    @Test fun manifest() = section("manifest")
    @Test fun payloads() = section("payloads")

    /** The write mode (see the class): every section's answers as its cases' `expected`. */
    @Test
    fun writeTheVectors() = runTest {
        if (!writing) return@runTest
        val recorded = LinkedHashMap<String, JsonElement>()
        for ((key, value) in vectors) {
            recorded[key] = when (key) {
                "facts" -> facts()
                in SECTIONS -> JsonArray(value.jsonArray.map { c ->
                    val case = c.jsonObject
                    val got = answer(key, case)
                    JsonObject(case.mapValues { (k, v) -> if (k == "expected") got else v })
                })
                else -> value
            }
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

    /** A string as `JSON.stringify` quotes it: the short escapes, `\u` for the other controls and a lone surrogate. */
    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for ((i, c) in s.withIndex()) {
            val lone = (c.isHighSurrogate() && !(i + 1 < s.length && s[i + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && !(i > 0 && s[i - 1].isHighSurrogate()))
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || lone -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        const val FILE = "contracts/platform/export/v1-vectors.json"
        private val SECTIONS = setOf("crc32", "dos", "write", "length", "read", "manifest", "payloads")
    }
}
