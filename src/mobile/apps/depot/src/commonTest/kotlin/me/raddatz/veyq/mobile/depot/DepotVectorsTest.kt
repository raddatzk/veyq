package me.raddatz.veyq.mobile.depot

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
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import me.raddatz.veyq.mobile.tree.KindFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Depot's rules against `contracts/depot/v1-vectors.json` (ADR 0155 decision 7.3, § Depot's move): what an opened file is
 * ([FileView.answer], the web's reading of [FileView.decide] with an SVG drawn), a folder upload's plan ([FolderUpload]'s
 * names, tree and top numbering), an SVG's raster ([SvgView.plan]) and the zoom steps — on the JVM and on `macosArm64`; the
 * web answers the same cases through `@veyq/core-js/depot` (the package's `depot.test.ts`, the web's
 * `features/depot/vectors.test.ts`). A failing case names its section and name.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :apps:depot:jvmTest
 * --tests '*DepotVectorsTest*'` records every `expected`, and a plan's `tokens` — the folding's answer for each (kind, name)
 * the plan asks, by the apps' real kinds ([FOLDING]) —, as JavaScript's `JSON.stringify(vectors, null, 2)` writes it. The
 * inputs are kept. Read each changed case against its rule before committing it.
 */
class DepotVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    private fun strOf(e: JsonElement?): String? = if (e == null || e is JsonNull) null else e.jsonPrimitive.content

    /** A double as `JSON.stringify` writes it: an integral one without a fraction, NaN or an infinite one as null. */
    private fun num(d: Double): JsonElement = when {
        d.isInfinite() || d.isNaN() -> JsonNull
        d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15 -> JsonPrimitive(d.toLong())
        else -> JsonPrimitive(d)
    }

    // --- view ------------------------------------------------------------------------------------------------------------

    private fun answerView(case: JsonObject): JsonElement {
        val a = FileView.answer(
            unb64(case["head"]!!.jsonPrimitive.content), case["chunkCount"]!!.jsonPrimitive.int,
            case["total"]!!.jsonPrimitive.long, strOf(case["type"]), strOf(case["claimedName"]), svg = true,
        )
        return obj(
            "kind" to str(a.kind), "type" to str(a.type), "element" to str(a.element), "encoding" to str(a.encoding),
            "frames" to JsonPrimitive(a.frames), "truncated" to JsonPrimitive(a.truncated), "limit" to JsonPrimitive(a.limit),
        )
    }

    // --- folderPlan ------------------------------------------------------------------------------------------------------

    private class Entry(val path: String, val size: Long)

    /** The plan of [case] with [tokenInput]; its answer, and every (kind, name) asked in order. */
    private fun answerPlan(case: JsonObject, tokenInput: (String, String) -> String): JsonElement {
        val entries = case["entries"]!!.jsonArray.map { Entry(it.jsonObject["path"]!!.jsonPrimitive.content, it.jsonObject["size"]!!.jsonPrimitive.long) }
        val siblings = case["siblings"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content }
        val planned = FolderUpload.planned(case["rootName"]!!.jsonPrimitive.content, entries.indices.toList(), { entries[it].path }, tokenInput)
        val roots = FolderUpload.Namer(tokenInput, siblings.map { (k, n) -> tokenInput(k, n) })
        fun tree(p: FolderUpload.Planned<Int>, name: String): JsonElement = obj(
            "name" to JsonPrimitive(name),
            "folders" to JsonArray(p.dirs.map { tree(it, it.name) }),
            "files" to JsonArray(p.files.map { (n, i) -> obj("name" to JsonPrimitive(n), "entry" to JsonPrimitive(i)) }),
        )
        val top = roots.next("FOLDER", planned.root.name)
        val next = (1..3).map { JsonPrimitive(roots.next("FOLDER", planned.root.name)) }
        return obj(
            "root" to tree(planned.root, top), "files" to JsonPrimitive(planned.placed.size),
            "bytes" to JsonPrimitive(planned.placed.sumOf { entries[it].size }), "nextRootNames" to JsonArray(next),
        )
    }

    // --- raster and zoom -------------------------------------------------------------------------------------------------

    private fun answerRaster(case: JsonObject): JsonElement {
        val ratio = case["ratio"].let { if (it == null || it is JsonNull) Double.NaN else it.jsonPrimitive.double }
        val r = SvgView.plan(case["width"]!!.jsonPrimitive.double, case["height"]!!.jsonPrimitive.double, ratio)
        return obj("width" to JsonPrimitive(r.width), "height" to JsonPrimitive(r.height), "ratio" to num(r.ratio))
    }

    private fun answerZoom(case: JsonObject): JsonElement {
        val z = case["zoom"]!!.jsonPrimitive.double
        return obj("in" to num(SvgView.zoomIn(z)), "out" to num(SvgView.zoomOut(z)))
    }

    @Test
    fun everyCaseAsTheCoreAnswersIt() {
        val failures = ArrayList<String>()
        fun check(section: String, name: String, expected: JsonElement?, actual: JsonElement) {
            if (expected != actual) failures += "$section '$name': expected $expected, the core answers $actual"
        }
        val view = vectors["view"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            val answer = answerView(case)
            check("view", case["name"]!!.jsonPrimitive.content, case["expected"], answer)
            JsonObject(case + ("expected" to answer))
        }
        val plans = vectors["folderPlan"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            if (writing) {
                val asked = LinkedHashMap<Pair<String, String>, String>()
                val answer = answerPlan(case) { k, n -> asked.getOrPut(k to n) { FOLDING.tokenInput(k, n) } }
                val tokens = JsonArray(asked.map { (kn, t) -> JsonArray(listOf(JsonPrimitive(kn.first), JsonPrimitive(kn.second), JsonPrimitive(t))) })
                JsonObject(case + ("tokens" to tokens) + ("expected" to answer))
            } else {
                val name = case["name"]!!.jsonPrimitive.content
                val table = case["tokens"]!!.jsonArray.associate { t ->
                    (t.jsonArray[0].jsonPrimitive.content to t.jsonArray[1].jsonPrimitive.content) to t.jsonArray[2].jsonPrimitive.content
                }
                val answer = answerPlan(case) { k, n -> table[k to n] ?: error("folderPlan '$name': asks ($k, $n), a token the case does not list") }
                check("folderPlan", name, case["expected"], answer)
                // the tokens are the real folding's, so the table is no free input
                table.forEach { (kn, t) -> check("folderPlan tokens", "$name ${kn.first} ${kn.second}", JsonPrimitive(t), JsonPrimitive(FOLDING.tokenInput(kn.first, kn.second))) }
                case
            }
        }
        val raster = vectors["raster"]!!.jsonArray.mapIndexed { i, c ->
            val case = c.jsonObject
            val answer = answerRaster(case)
            check("raster", "#$i", case["expected"], answer)
            JsonObject(case + ("expected" to answer))
        }
        val zoom = vectors["zoom"]!!.jsonObject
        val steps = JsonArray(SvgView.ZOOM_STEPS.map(::num))
        check("zoom", "steps", zoom["steps"], steps)
        val zoomCases = zoom["cases"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            val answer = answerZoom(case)
            check("zoom", case["zoom"].toString(), case["expected"], answer)
            JsonObject(case + ("expected" to answer))
        }
        if (writing) {
            val all = JsonObject(
                vectors + ("view" to JsonArray(view)) + ("folderPlan" to JsonArray(plans)) + ("raster" to JsonArray(raster)) +
                    ("zoom" to obj("steps" to steps, "cases" to JsonArray(zoomCases))),
            )
            writeRepoFile(FILE, stringify(all, "") + "\n")
            return
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(43, view.size, "every view case read")
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

    /** A string as `JSON.stringify` quotes it (the vectors' strings need only the quote and the backslash). */
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val FILE = "contracts/depot/v1-vectors.json"

        /** The folding of the apps' kinds as each application registers it (Depot's, Memories', Expenses', core's folder). */
        val FOLDING = KindFacts(
            listOf(
                KindFacts.Kind("FOLDER", null, KindFacts.NAME, root = true, parents = listOf("FOLDER")),
                KindFacts.Kind("FILE", null, KindFacts.FILE_NAME, root = true, parents = listOf("FOLDER")),
                KindFacts.Kind("DEPOT_DROPBOX", null, KindFacts.NAME, root = true, parents = listOf("FOLDER")),
                KindFacts.Kind("MEMORY", "memories", KindFacts.NAME, root = true, parents = listOf("FOLDER")),
                KindFacts.Kind("EXPENSES_POT", "expenses", KindFacts.NAME, root = true, parents = listOf("FOLDER")),
            ),
        )
    }
}
