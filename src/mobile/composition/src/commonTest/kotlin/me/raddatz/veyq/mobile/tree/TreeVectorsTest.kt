package me.raddatz.veyq.mobile.tree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
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
 * The platform's tree rules against `contracts/platform/tree/v1-vectors.json`: every case of every section, on the JVM and on
 * `macosArm64` (and on Linux and Windows through `run.py test`); the web runs the same cases through `@veyq/core-js/tree`
 * (its `tree.vectors.test.ts`). A case that fails names its section, index and rule. The kinds the rules read are the apps'
 * registry's ([AppKinds.facts], every application composed), which must be the file's `kinds` — the table the web's registry
 * is held to as well.
 *
 * **The write mode is here** (ADR 0155 § Consequences: the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew
 * :composition:jvmTest --tests '*TreeVectorsTest*'` records every case's `expected` and the kind table from the core, written
 * as JavaScript's `JSON.stringify(vectors, null, 2)` writes it, so an unchanged rule rewrites the file byte for byte. Read each
 * changed case against its rule before committing it.
 */
class TreeVectorsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"
    private val kinds = AppKinds.facts

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.ids(): List<String> = jsonArray.map { it.jsonPrimitive.content }
    private fun ids(l: List<String?>) = JsonArray(l.map { JsonPrimitive(it) })

    private class Entry(val id: String, val name: String, val createdAt: Double?)

    private fun entries(j: JsonElement?): List<Entry> = j!!.jsonArray.map {
        val o = it.jsonObject
        Entry(o["id"].str()!!, o["name"].str()!!, o["createdAt"]?.jsonPrimitive?.doubleOrNull)
    }

    private fun nodes(j: JsonElement): List<TreeFacts.Node> = j.jsonArray.map {
        val o = it.jsonObject
        TreeFacts.Node(
            o["id"].str()!!, o["parentId"].str(), o["kind"].str()!!, o["name"].str()!!, o["createdAt"]?.jsonPrimitive?.doubleOrNull,
            o["identity"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    private class Tree(val facts: TreeFacts, val top: String?)

    private fun tree(c: JsonObject): Tree {
        val t = vectors["trees"]!!.jsonObject[c["tree"].str()!!]!!.jsonObject
        return Tree(TreeFacts(nodes(t["nodes"]!!)), t["rootId"].str())
    }

    private fun moved(t: Tree, c: JsonObject): List<MovePlan.Moved> = c["nodes"]!!.ids().map { id ->
        val n = t.facts.node(id)!!
        MovePlan.Moved(n.id, n.kind, n.parentId, n.name)
    }

    private fun guest(c: JsonObject) = c["mode"].str() == "guest"
    private fun copy(c: JsonObject) = c["intent"].str() == "copy"

    private fun kindsJson(k: KindFacts): JsonArray = JsonArray(k.kinds.map {
        JsonObject(
            linkedMapOf(
                "kind" to JsonPrimitive(it.kind), "route" to JsonPrimitive(it.route), "folding" to JsonPrimitive(it.folding.name),
                "root" to JsonPrimitive(it.root), "parents" to ids(it.parents),
            ),
        )
    })

    private val answers: Map<String, (JsonObject) -> JsonElement> = mapOf(
        "order" to { c ->
            ids(LevelOrder.order(entries(c["folders"]), entries(c["others"]), { it.id }, { it.name }, { it.createdAt }).map { it.id })
        },
        "displayNames" to { c ->
            val labels = DisplayNames.of(entries(c["siblings"]), { it.id }, { it.name }, { it.createdAt }, byCreation = c["order"].str() == "creation")
            JsonObject(labels.mapValues { JsonPrimitive(it.value) })
        },
        "collision" to { c ->
            val listed = c["nodes"]!!.let { n -> n.str()?.let { vectors["collisionNodes"]!!.jsonObject[it]!! } ?: n }
            val facts = TreeFacts(listed.jsonArray.map {
                val o = it.jsonObject
                TreeFacts.Node(o["id"].str()!!, o["parentId"].str(), o["kind"].str()!!, o["name"].str()!!, null, false)
            })
            JsonPrimitive(SiblingNames.colliding(kinds, facts, c["parentId"].str(), c["kind"].str()!!, c["name"].str()!!, c["self"].str())?.id)
        },
        "moveBlock" to { c ->
            val t = tree(c)
            JsonPrimitive(MovePlan.hereBlock(kinds::placementAllows, t.facts, moved(t, c), guest(c), c["at"].str(), copy(c))?.word)
        },
        "moveBlockOf" to { c ->
            val t = tree(c)
            MovePlan.hereBlockOf(kinds::placementAllows, t.facts, moved(t, c), guest(c), c["at"].str(), copy(c))
                ?.let { JsonObject(linkedMapOf("block" to JsonPrimitive(it.block.word), "node" to JsonPrimitive(it.node?.id))) } ?: JsonNull
        },
        "planMove" to { c ->
            val t = tree(c)
            val plan = MovePlan.plan(kinds, t.facts, moved(t, c), c["to"].str())
            JsonObject(
                linkedMapOf(
                    "send" to ids(plan.send.map { it.id }),
                    "leftOut" to JsonArray(plan.leftOut.map { l ->
                        val fields = linkedMapOf<String, JsonElement>("node" to JsonPrimitive(l.node.id), "reason" to JsonPrimitive(l.reason.word))
                        l.sibling?.let { fields["sibling"] = JsonPrimitive(it.id) }
                        JsonObject(fields)
                    }),
                ),
            )
        },
        "movePlaces" to { c ->
            val t = tree(c)
            JsonArray(MovePlan.places(kinds::placementAllows, t.facts, moved(t, c), guest(c), c["at"].str()).map {
                JsonObject(linkedMapOf("node" to JsonPrimitive(it.node.id), "blocked" to JsonPrimitive(it.blocked?.word)))
            })
        },
        "moveTrail" to { c -> val t = tree(c); ids(MovePlan.trail(t.facts, t.top, c["at"].str())) },
        "moveStart" to { c -> val t = tree(c); JsonPrimitive(MovePlan.start(t.facts, t.top, moved(t, c))) },
    )

    private fun section(name: String) {
        if (writing) return
        val failures = vectors[name]!!.jsonArray.mapIndexedNotNull { i, c ->
            val case = c.jsonObject
            val got = answers.getValue(name)(case)
            if (got == case["expected"]) null else "$name #$i ${case["rule"].str()}: expected ${case["expected"]}, got $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun kinds() {
        if (!writing) assertEquals(vectors["kinds"], kindsJson(kinds), "the apps' registry is not the vectors' kind table")
    }
    @Test fun order() = section("order")
    @Test fun displayNames() = section("displayNames")
    @Test fun collision() = section("collision")
    @Test fun moveBlock() = section("moveBlock")
    @Test fun moveBlockOf() = section("moveBlockOf")
    @Test fun planMove() = section("planMove")
    @Test fun movePlaces() = section("movePlaces")
    @Test fun moveTrail() = section("moveTrail")
    @Test fun moveStart() = section("moveStart")

    /** The write mode (see the class): every section's answers as its cases' `expected`, the kind table the registry's. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        val recorded = JsonObject(vectors.mapValues { (key, value) ->
            val answer = answers[key]
            when {
                key == "kinds" -> kindsJson(kinds)
                answer == null -> value
                else -> JsonArray(value.jsonArray.map { c ->
                    val case = c.jsonObject
                    JsonObject(if ("expected" in case) case.mapValues { (k, v) -> if (k == "expected") answer(case) else v } else case + ("expected" to answer(case)))
                })
            }
        })
        writeRepoFile(FILE, stringify(recorded, "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it, so a rewrite diffs only where an answer changed. */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) quote(e.content) else number(e.content)
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}"
            else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A number as JavaScript prints it: an integral one without a fraction (`1000`, not `1000.0`). */
    private fun number(s: String): String = s.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 1e15 }?.toLong()?.toString() ?: s

    /** A string as `JSON.stringify` quotes it (see `SearchVectorsTest.quote`). */
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

    @Test
    fun everyCaseIsRecorded() {
        for (s in answers.keys) for (c in vectors[s]!!.jsonArray) assertTrue("expected" in c.jsonObject, "$s case without its expected: $c")
    }

    companion object {
        const val FILE = "contracts/platform/tree/v1-vectors.json"
    }
}
