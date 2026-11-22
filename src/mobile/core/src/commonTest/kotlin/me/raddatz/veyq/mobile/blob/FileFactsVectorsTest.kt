package me.raddatz.veyq.mobile.blob

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.export.ArchiveName
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file facts against `contracts/platform/files/v1-vectors.json` (ADR 0155 decision 7.2, the fourth slice): every sniffed
 * head ([Sniff]), the claims, a name's extension, the download name ([DownloadName]) and the archive's safe names
 * ([ArchiveName]), on the JVM and on `macosArm64` (and on Linux and Windows through `run.py test`); the web runs the same
 * cases through `@veyq/core-js/files` (the package's `files.test.ts`, the web's `files.vectors.test.ts`). A failing case
 * names its section, index and rule.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :core:jvmTest --tests
 * '*FileFactsVectorsTest*'` records every case's `expected` from the core, written as JavaScript's
 * `JSON.stringify(vectors, null, 2)` writes it, so an unchanged rule rewrites the file byte for byte. Read each changed case
 * against its rule before committing it.
 */
class FileFactsVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun s(v: String?): JsonElement = JsonPrimitive(v)
    private fun b(v: Boolean): JsonElement = JsonPrimitive(v)

    private fun hex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val answers: Map<String, (JsonObject) -> JsonElement> = mapOf(
        "sniff" to { c ->
            val head = hex(c["hex"].str()!!)
            JsonObject(
                linkedMapOf(
                    "image" to s(Sniff.image(head)?.mime),
                    "stream" to (Sniff.stream(head)?.let {
                        JsonObject(linkedMapOf("element" to s(if (it.video) "video" else "audio"), "type" to s(it.mime)))
                    } ?: JsonNull),
                    "pdf" to b(Sniff.pdf(head)),
                    "text" to s(Sniff.textEncoding(head)),
                    "textComplete" to s(Sniff.textEncoding(head, complete = true)),
                    "svg" to b(Sniff.svg(head)),
                ),
            )
        },
        "claims" to { c ->
            JsonObject(linkedMapOf("text" to b(Sniff.claimsText(c["type"].str(), c["name"].str())), "svg" to b(Sniff.claimsSvg(c["type"].str(), c["name"].str()))))
        },
        "extension" to { c -> s(Sniff.extensionOf(c["name"].str())) },
        "downloadName" to { c ->
            s(DownloadName.of(c["type"].str(), c["fallback"].str()!!, c["objectName"].str(), c["sealedName"].str()))
        },
        "archiveName" to { c ->
            val taken = mutableSetOf<String>()
            JsonArray(c["names"]!!.jsonArray.map { s(ArchiveName.of(it.jsonPrimitive.content, taken)) })
        },
        "isArchiveName" to { c -> b(ArchiveName.isName(c["segment"].str()!!)) },
        "isArchivePath" to { c -> b(ArchiveName.isPath(c["path"].str()!!)) },
    )

    private fun section(name: String) {
        if (writing) return
        val cases = vectors[name]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "$name holds no case")
        val failures = cases.mapIndexedNotNull { i, c ->
            val case = c.jsonObject
            val got = answers.getValue(name)(case)
            if (got == case["expected"]) null else "$name #$i ${case["rule"].str() ?: ""}: expected ${case["expected"]}, got $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun theFileIsTheFormatThisTestReads() = assertEquals("veyq-file-facts-v1", vectors["format"].str())
    @Test fun sniff() = section("sniff")
    @Test fun claims() = section("claims")
    @Test fun extension() = section("extension")
    @Test fun downloadName() = section("downloadName")
    @Test fun archiveName() = section("archiveName")
    @Test fun isArchiveName() = section("isArchiveName")
    @Test fun isArchivePath() = section("isArchivePath")

    @Test
    fun everyCaseIsRecorded() {
        for (name in answers.keys) for (c in vectors[name]!!.jsonArray) assertTrue("expected" in c.jsonObject, "$name case without its expected: $c")
    }

    /** The write mode (see the class): every section's answers as its cases' `expected`. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        val recorded = JsonObject(vectors.mapValues { (key, value) ->
            val answer = answers[key] ?: return@mapValues value
            JsonArray(value.jsonArray.map { c ->
                val case = c.jsonObject
                JsonObject(if ("expected" in case) case.mapValues { (k, v) -> if (k == "expected") answer(case) else v } else case + ("expected" to answer(case)))
            })
        })
        writeRepoFile(FILE, stringify(recorded, "") + "\n")
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
        const val FILE = "contracts/platform/files/v1-vectors.json"
    }
}
