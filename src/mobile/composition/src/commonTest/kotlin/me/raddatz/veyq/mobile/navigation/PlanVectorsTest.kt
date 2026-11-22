package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A node header's and a level row's plans, a level's New and tools, and core's own rights against `contracts/platform/plans/v1-vectors.json`
 * (ADR 0154 decision 2, ADR 0155 decision 7.2's third slice): every case on the JVM and on `macosArm64` (and on Linux and
 * Windows through `run.py test`); the web runs the same cases through `@veyq/core-js/navigation` (its
 * `plans.vectors.test.ts`). Each kind's header order is the apps' registry's ([AppKinds.headerOrder], every application
 * composed), which must be the file's `kinds` — the table the web's registry is held to as well. A failing case names its
 * section, index and rule.
 *
 * **The write mode is here** (the core's output is the one source, ADR 0155 § Consequences): `VEYQ_WRITE_VECTORS=1
 * ./gradlew :composition:jvmTest --tests '*PlanVectorsTest*'` records every case's `expected` and the kinds' orders from the
 * core, as JavaScript's `JSON.stringify(vectors, null, 2)` writes them, so an unchanged rule rewrites the file byte for byte.
 * Read each changed case against its rule before committing it.
 */
class PlanVectorsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    /** An entry as a wire type carries it (the bridge's `HeaderShown.PlanAction`): the enum's serializer at its use site, the
     *  one every platform has (Kotlin/Native finds none by type for an enum that is not itself `@Serializable`). */
    @Serializable private class Carried(val id: HeaderAction)

    private val entryNames = Carried.serializer().descriptor.getElementDescriptor(0)
    private val byName: Map<String, HeaderAction> = HeaderAction.entries.associateBy { entryNames.getElementName(it.ordinal) }
    private fun id(a: HeaderAction): String = entryNames.getElementName(a.ordinal)
    private fun actions(j: JsonElement?): List<HeaderAction> = j!!.jsonArray.map { byName.getValue(it.jsonPrimitive.content) }
    private fun ids(l: List<HeaderAction>) = JsonArray(l.map { JsonPrimitive(id(it)) })
    private fun JsonObject.flag(k: String) = getValue(k).jsonPrimitive.boolean

    private fun rights(c: JsonObject): NodeHeader.Rights = c.getValue("rights").jsonObject.let {
        NodeHeader.Rights(
            favorite = it.flag("favorite"), share = it.flag("share"), rename = it.flag("rename"), move = it.flag("move"),
            cover = it.flag("cover"), copy = it.flag("copy"), download = it.flag("download"), report = it.flag("report"),
            delete = it.flag("delete"), offline = it.flag("offline"),
        )
    }

    private fun header(c: JsonObject): JsonElement {
        val p = NodeHeader.plan(rights(c), actions(c["order"]), c["maxButtons"]?.jsonPrimitive?.int ?: Int.MAX_VALUE, actions(c["own"]))
        return JsonObject(linkedMapOf("buttons" to ids(p.buttons), "menu" to ids(p.menu), "last" to ids(p.last)))
    }

    private fun row(c: JsonObject): JsonElement {
        val p = LevelRow.plan(rights(c), actions(c["own"]))
        return JsonObject(linkedMapOf("menu" to ids(p.menu), "last" to ids(p.last)))
    }

    private fun favorite(c: JsonObject): JsonElement = JsonPrimitive(CoreRights.favorite(c.flag("member"), c.flag("node"), c.flag("known")))

    private fun cover(c: JsonObject): JsonElement = JsonPrimitive(
        CoreRights.cover(
            c.flag("member"), c.flag("keys"), c.flag("readonly"), c.flag("node"), c.flag("inIdentityFolder"), c.flag("applicationOn"),
            c.flag("takesCover"),
        ),
    )

    private fun bookmark(c: JsonObject): JsonElement {
        val b = CoreRights.bookmark(c.flag("member"), c.flag("node"), c.flag("keys"), c.flag("rename"), c.flag("move"), c.flag("delete"))
        return JsonObject(
            linkedMapOf("open" to JsonPrimitive(b.open), "rename" to JsonPrimitive(b.rename), "move" to JsonPrimitive(b.move), "delete" to JsonPrimitive(b.delete)),
        )
    }

    private fun creates(l: List<AppKinds.Create>) = JsonArray(l.map { JsonPrimitive(it.name) })

    private fun levelCreates(c: JsonObject): JsonElement =
        creates(AppKinds.createsWhere(isHome = c.getValue("at").jsonPrimitive.content == "level", bookmarks = c.flag("bookmarks")))

    private fun guestCreates(c: JsonObject): JsonElement =
        creates(me.raddatz.veyq.mobile.sharing.GuestLevel.createsFor(c.getValue("role").jsonPrimitive.content, c.flag("inIdentityFolder")))

    private fun levelTools(c: JsonObject): JsonElement {
        val views = LevelPlan.views(c.flag("mapTab"), c.flag("identityFolder"), c.getValue("rows").jsonPrimitive.int)
        val tools = LevelPlan.of(emptyList(), c.flag("trash"), views).tools
        return JsonArray(tools.map { JsonPrimitive(LevelTool.serializer().descriptor.getElementName(it.ordinal)) })
    }

    private val answers: Map<String, (JsonObject) -> JsonElement> = mapOf("header" to ::header, "row" to ::row)
    private val levelAnswers: Map<String, (JsonObject) -> JsonElement> = mapOf("creates" to ::levelCreates, "guest" to ::guestCreates, "tools" to ::levelTools)
    private val rightsAnswers: Map<String, (JsonObject) -> JsonElement> = mapOf("favorite" to ::favorite, "cover" to ::cover, "bookmark" to ::bookmark)

    /** Each kind of the apps' registry and its header order, by kind. */
    private fun kindsJson(): JsonArray = JsonArray(AppKinds.facts.kinds.map { it.kind }.sorted().map {
        JsonObject(linkedMapOf("kind" to JsonPrimitive(it), "header" to ids(AppKinds.headerOrder(it))))
    })

    private fun cases(j: JsonElement?, answer: (JsonObject) -> JsonElement, where: String) {
        if (writing) return
        for ((i, c) in j!!.jsonArray.withIndex()) {
            val case = c.jsonObject
            assertEquals(case["expected"], answer(case), "$where $i: ${case["rule"]?.jsonPrimitive?.content ?: case}")
        }
    }

    @Test fun header() = cases(vectors["header"], ::header, "header")
    @Test fun row() = cases(vectors["row"], ::row, "row")
    @Test fun favoriteRights() = cases(vectors["rights"]!!.jsonObject["favorite"], ::favorite, "favorite")
    @Test fun coverRights() = cases(vectors["rights"]!!.jsonObject["cover"], ::cover, "cover")
    @Test fun bookmarkRights() = cases(vectors["rights"]!!.jsonObject["bookmark"], ::bookmark, "bookmark")
    @Test fun levelCreates() = cases(vectors["level"]!!.jsonObject["creates"], ::levelCreates, "level.creates")
    @Test fun guestCreates() = cases(vectors["level"]!!.jsonObject["guest"], ::guestCreates, "level.guest")
    @Test fun levelTools() = cases(vectors["level"]!!.jsonObject["tools"], ::levelTools, "level.tools")

    /** The web's façade reads an entry's serial name as its constant's name in lower case, `_` as `-` (`NavigationWeb.kt`). */
    @Test
    fun everySerialNameIsItsConstantsNameAsTheFacadeReadsIt() {
        for (a in HeaderAction.entries) assertEquals(a.name.lowercase().replace('_', '-'), id(a), "$a's serial name")
    }

    @Test
    fun theKindsHeaderOrdersAreTheRegistrys() {
        if (!writing) assertEquals(vectors["kinds"], kindsJson(), "the apps' registry is not the vectors' kinds")
    }

    @Test
    fun everyCaseIsRecorded() {
        if (writing) return
        for (s in answers.keys) for (c in vectors[s]!!.jsonArray) assertTrue(c.jsonObject["expected"] !is JsonNull, "$s case without its expected: $c")
        for (s in rightsAnswers.keys) assertTrue(vectors["rights"]!!.jsonObject[s]!!.jsonArray.isNotEmpty(), "no $s cases")
        for (s in levelAnswers.keys) assertTrue(vectors["level"]!!.jsonObject[s]!!.jsonArray.isNotEmpty(), "no level.$s cases")
    }

    /** The write mode (see the class): every case's answer as its `expected`, the kinds' orders the registry's. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        fun recorded(list: JsonElement, answer: (JsonObject) -> JsonElement) = JsonArray(list.jsonArray.map { c ->
            val case = c.jsonObject
            JsonObject(if ("expected" in case) case.mapValues { (k, v) -> if (k == "expected") answer(case) else v } else case + ("expected" to answer(case)))
        })
        val out = JsonObject(vectors.mapValues { (key, value) ->
            when (key) {
                "kinds" -> kindsJson()
                "rights" -> JsonObject(value.jsonObject.mapValues { (k, v) -> recorded(v, rightsAnswers.getValue(k)) })
                "level" -> JsonObject(value.jsonObject.mapValues { (k, v) -> recorded(v, levelAnswers.getValue(k)) })
                else -> answers[key]?.let { recorded(value, it) } ?: value
            }
        })
        writeRepoFile(FILE, stringify(out, "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it (`TreeVectorsTest.stringify`; no number is written here). */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}"
            else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A string as `JSON.stringify` quotes it: the file's strings are printable ASCII and `'`, so only `"` and `\` escape. */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val FILE = "contracts/platform/plans/v1-vectors.json"
    }
}
