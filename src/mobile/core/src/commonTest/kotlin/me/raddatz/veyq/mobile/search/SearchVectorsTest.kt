package me.raddatz.veyq.mobile.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.memories.MemoriesSearch
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The core's search against `contracts/platform/search/v1-vectors.json` (ADR 0146 decision 1): every case of every section,
 * on the JVM and on `macosArm64` (and on Linux and Windows through `run.py test`); the web runs the same cases through
 * `@veyq/core-js` (its `vectors.test.ts`). A case that fails names its section, index and rule. The answers are compared as
 * JSON, in the shape the web reads them.
 *
 * **The write mode is here** (ADR 0155 § Consequences: the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew
 * :core:jvmTest --tests '*SearchVectorsTest*'` records every case's `expected` from the core's own functions — the
 * `matcher` section's as fresh scans, which a field's remembering matcher must then give —, written as JavaScript's
 * `JSON.stringify(vectors, null, 2)` writes it, so an unchanged rule rewrites the file byte for byte. Read each changed
 * case against its rule before committing it. The `match` cases order names by [NameOrder]; the web's collator must agree
 * on them, which its own run of the cases holds.
 */
class SearchVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun facetsOf(j: JsonElement?): SearchFacets {
        val o = j as? JsonObject ?: return SearchFacets.NONE
        return SearchFacets(
            years = (o["years"] as? JsonObject)?.let { YearSpan(it["from"]!!.jsonPrimitive.int, it["to"]!!.jsonPrimitive.int) },
            months = (o["months"] as? JsonObject)?.let { MonthSpan(it["from"].str()!!, it["to"].str()!!) },
            capturedAt = (o["capturedAt"] as? JsonPrimitive)?.long,
            places = (o["places"] as? JsonArray)?.map { it.jsonPrimitive.content },
            people = (o["people"] as? JsonArray)?.map { it.jsonPrimitive.content },
        )
    }

    private fun docOf(j: JsonElement): SearchDocument {
        val o = j.jsonObject
        val id = o["id"].str()!!
        return SearchDocument.of(
            scopeId = o["scopeId"].str()!!, kind = o["kind"].str()!!, id = id, name = o["name"].str()!!, nodeId = o["nodeId"].str() ?: id,
            path = o.strings("path"), pathIds = o.strings("pathIds"), facets = facetsOf(o["facets"]),
            opensItself = (o["opensItself"] as? JsonPrimitive)?.booleanOrNull == true, aliases = o.strings("aliases"),
            keywords = o.strings("keywords"), subtitle = o["subtitle"].str(),
        )
    }

    private val corpus by lazy { SearchDocuments(vectors["corpus"]!!.jsonArray.map(::docOf)) }
    private fun docsOf(j: JsonElement?): SearchDocuments = if (j.str() == "corpus") corpus else SearchDocuments(j!!.jsonArray.map(::docOf))

    private fun chipOf(j: JsonElement): SearchChip {
        val o = j.jsonObject
        return when (o["type"].str()) {
            "space" -> SearchChip.Space(o["scopeId"].str()!!, o["label"].str()!!)
            "year" -> SearchChip.Year(o["year"]!!.jsonPrimitive.int)
            "month" -> SearchChip.Month(o["year"]!!.jsonPrimitive.int, o["month"]!!.jsonPrimitive.int)
            "place" -> SearchChip.Place(o["place"].str()!!, o["label"].str()!!)
            "person" -> SearchChip.Person(o["userId"].str()!!, o["label"].str()!!)
            "kind" -> SearchChip.Kind(o.strings("kinds"), o["label"].str()!!)
            "in" -> SearchChip.In(o["scopeId"].str()!!, o["nodeId"].str()!!, o["label"].str()!!, o.strings("path"))
            else -> error("chip $o")
        }
    }

    private fun queryOf(j: JsonElement) = SearchQuery(j.jsonObject["chips"]!!.jsonArray.map(::chipOf), j.jsonObject["text"].str()!!)

    /** A chip as the web's JSON spells it (its keys in the web's order). */
    private fun chipJson(c: SearchChip): JsonObject = buildJsonObject {
        when (c) {
            is SearchChip.Space -> { put("type", "space"); put("scopeId", c.scopeId); put("label", c.label) }
            is SearchChip.Year -> { put("type", "year"); put("year", c.year) }
            is SearchChip.Month -> { put("type", "month"); put("year", c.year); put("month", c.month) }
            is SearchChip.Place -> { put("type", "place"); put("place", c.place); put("label", c.label) }
            is SearchChip.Person -> { put("type", "person"); put("userId", c.userId); put("label", c.label) }
            is SearchChip.Kind -> { put("type", "kind"); put("kinds", JsonArray(c.kinds.map(::JsonPrimitive))); put("label", c.label) }
            is SearchChip.In -> {
                put("type", "in"); put("scopeId", c.scopeId); put("nodeId", c.nodeId); put("label", c.label)
                put("path", JsonArray(c.path.map(::JsonPrimitive)))
            }
        }
    }

    private fun facetsJson(f: SearchFacets): JsonObject = buildJsonObject {
        f.years?.let { put("years", buildJsonObject { put("from", it.from); put("to", it.to) }) }
        f.months?.let { put("months", buildJsonObject { put("from", it.from); put("to", it.to) }) }
        f.capturedAt?.let { put("capturedAt", it) }
        f.places?.let { put("places", JsonArray(it.map(::JsonPrimitive))) }
        f.people?.let { put("people", JsonArray(it.map(::JsonPrimitive))) }
    }

    private val sortedRanks = { ms: List<Query.Match> -> ms.map { "${it.doc.id}:${it.rank}" }.sorted() }

    /** Each section's answer for one case, in the web's JSON shape. */
    @OptIn(ExperimentalTime::class)
    private val answers: Map<String, (JsonObject) -> JsonElement> = mapOf(
        "fold" to { c ->
            val input = c["input"].str()!!
            buildJsonObject {
                put("fold", Fold.fold(input))
                put("map", Fold.foldMap(input)?.let { m ->
                    buildJsonObject {
                        put("folded", m.folded)
                        put("start", JsonArray(m.start.map(::JsonPrimitive)))
                        put("end", JsonArray(m.end.map(::JsonPrimitive)))
                    }
                } ?: JsonNull)
            }
        },
        "tails" to { c -> JsonArray(Query.tailsOf(c["text"].str()!!).map { buildJsonObject { put("tail", it.tail); put("rest", it.rest) } }) },
        "wordPrefix" to { c -> JsonPrimitive(Query.wordPrefix(c["name"].str()!!, c["prefix"].str()!!)) },
        "suggest" to { c ->
            val s = vectors["suggestSources"]!!.jsonObject
            val sources = Query.ChipSources(
                documents = docsOf(s["documents"]),
                spaces = s["spaces"]!!.jsonArray.map { it.jsonObject["scopeId"].str()!! to it.jsonObject["label"].str()!! },
                people = s["people"]!!.jsonArray.map { it.jsonObject["userId"].str()!! to it.jsonObject["name"].str()!! },
                kinds = s["kinds"]!!.jsonArray.map { it.jsonObject["label"].str()!! to it.jsonObject.strings("kinds") },
                months = if ((c["months"] as? JsonPrimitive)?.booleanOrNull == false) null
                else s["months"]!!.jsonArray.map { m -> m.jsonArray.map { it.jsonPrimitive.content } },
            )
            JsonArray(Query.suggestChips(c["text"].str()!!, c["chips"]!!.jsonArray.map(::chipOf), sources).map { x ->
                buildJsonObject { put("key", Query.chipKey(x.chip)); put("chip", chipJson(x.chip)); put("text", x.text) }
            })
        },
        "match" to { c ->
            val query = queryOf(c["query"]!!)
            val ctx = c["context"]!!.jsonObject.let { Results.SearchContext(it["activeScopeId"].str(), it["openNodeId"].str()) }
            val limit = (c["limit"] as? JsonPrimitive)?.int ?: Int.MAX_VALUE
            val found = Results.searchResults(docsOf(c["documents"]), query, ctx, limit)
            val t = Query.matchedText(query.text)
            buildJsonObject {
                put("results", JsonArray(found.results.map { d ->
                    buildJsonObject { put("id", d.id); Query.reasonKeyword(d, t)?.let { put("reason", it) } }
                }))
                put("total", found.total)
            }
        },
        "matcher" to { c ->
            val docs = docsOf(c["documents"])
            val matcher = Query.Matcher()
            // the field's matcher, remembering and narrowing, must give each step's fresh scan
            JsonArray(c["steps"]!!.jsonArray.map { q -> JsonArray(sortedRanks(matcher.match(docs, queryOf(q))).map(::JsonPrimitive)) })
        },
        "nameParts" to { c ->
            JsonArray(Query.nameParts(c["name"].str()!!, Query.matchedText(c["text"].str()!!)).map {
                buildJsonObject { put("text", it.text); put("strong", it.strong) }
            })
        },
        "facets" to { c ->
            val now = Instant.parse(c["now"].str()!!).toEpochMilliseconds()
            val meta = buildJsonObject { put("v", 1); c["meta"]!!.jsonObject.forEach { (k, v) -> put(k, v) } }
            facetsJson(MemoriesSearch.facets(meta, now))
        },
        "medium" to { c ->
            val item = c["item"]!!.jsonObject
            val unnamed = vectors["unnamed"]!!.jsonObject
            val blob = c["blobMeta"] as? JsonObject
            val blocked = (item["blocked"] as? JsonPrimitive)?.booleanOrNull == true
            val doc = if (blocked) null else MemoriesSearch.mediumDocument(
                mediaId = item["mediaId"].str()!!, nodeId = "", metaRevision = item["metaRevision"]!!.jsonPrimitive.long,
                blobId = item["blobId"].str()!!, meta = (c["meta"] as? JsonObject)?.let(MemoryMedia::metaOf),
                type = blob?.get("type").str()?.takeIf { it.isNotBlank() }, name = blob?.get("name").str(),
                unnamed = { video -> (if (video) unnamed["video"] else unnamed["photo"]).str()!! },
            )
            doc?.let { d ->
                buildJsonObject {
                    put("kind", d.kind); put("id", d.id); put("revision", d.revision); put("name", d.name)
                    put("facets", facetsJson(d.facets)); d.icon?.let { put("icon", it) }
                }
            } ?: JsonNull
        },
    )

    private fun section(name: String) {
        if (writing) return // the write mode records instead (writeTheVectors)
        val cases = vectors[name]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "no $name vectors")
        val failures = cases.mapIndexedNotNull { i, c ->
            val case = c.jsonObject
            val got = answers.getValue(name)(case)
            val want = case["expected"]!!
            if (same(got, want)) null else "$name #$i (${case["rule"].str()}): want $want, got $got"
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} $name vectors")
    }

    /** JSON equality as the web's `toEqual` reads it: objects by their keys (in any order), numbers by value. */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString -> a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } ?: (a.content == b.content)
        else -> a == b
    }

    private companion object {
        const val FILE = "contracts/platform/search/v1-vectors.json"
    }

    @Test fun fold() = section("fold")
    @Test fun tails() = section("tails")
    @Test fun wordPrefix() = section("wordPrefix")
    @Test fun suggest() = section("suggest")
    @Test fun match() = section("match")
    @Test fun matcher() = section("matcher")
    @Test fun nameParts() = section("nameParts")
    @Test fun facets() = section("facets")
    @Test fun medium() = section("medium")

    /** The write mode (see the class): every section's answers as its cases' `expected`, the rest of the file as it was. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        val recorded = JsonObject(vectors.mapValues { (key, value) ->
            val answer: ((JsonObject) -> JsonElement)? = when (key) {
                // the recorded answer is a fresh scan; the field's matcher is held to it (the `matcher` section)
                "matcher" -> { c: JsonObject ->
                    val docs = docsOf(c["documents"])
                    JsonArray(c["steps"]!!.jsonArray.map { q -> JsonArray(sortedRanks(Query.matchAll(docs.list, queryOf(q))).map(::JsonPrimitive)) })
                }
                else -> answers[key]
            }
            if (answer == null) value
            else JsonArray(value.jsonArray.map { c ->
                val case = c.jsonObject
                JsonObject(if ("expected" in case) case.mapValues { (k, v) -> if (k == "expected") answer(case) else v } else case + ("expected" to answer(case)))
            })
        })
        writeRepoFile(FILE, stringify(recorded, "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it: what the web's write mode wrote, so a rewrite diffs only
     *  where an answer changed. */
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

    /** A string as `JSON.stringify` quotes it: the quote and the backslash escaped, the C0 controls as their short escape or a
     *  `u00xx` escape, a lone surrogate as its `udxxx` escape, every other character as itself. */
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
        // a case without its `expected` would compare nothing: the file is the recorded one, every case answered
        for (s in listOf("fold", "tails", "wordPrefix", "suggest", "match", "matcher", "nameParts", "facets", "medium")) {
            for (c in vectors[s]!!.jsonArray) assertTrue("expected" in c.jsonObject, "$s case without its expected: $c")
        }
    }
}
