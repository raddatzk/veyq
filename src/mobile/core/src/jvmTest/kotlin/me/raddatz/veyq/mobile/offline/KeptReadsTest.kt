@file:OptIn(ExperimentalSerializationApi::class)

package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No kept body carries a token or an address (ADR 0140 decisions 1 and 5): every field the store may hold is derived from
 * the generated client's model of each [KeptRead], core's and each application's — walked down through every object, list and map it holds, so a field
 * added to the contract is checked without a list of its own — and each is refused whose name, or whose description in
 * `contracts/api`, marks a token, a credential or an address. A presigned URL is a bearer credential until it lapses; a
 * token is one for as long as it lives.
 *
 * Let through by owner and field, each with why: the one name that only shares the word.
 *
 * Bite: add `presignBlobDownloads` (`BlobDownloadsBatchResponse`) to [KeptRead] → its `presignedGets` and their `url` are
 * refused here.
 */
class KeptReadsTest {
    private val allowed = mapOf(
        "NodeSummary.slugToken" to "a node's blinded name token: an HMAC of its folded name under the parent's key, for the " +
            "server's sibling-uniqueness check (ADR 0015); it opens nothing and grants nothing",
    )

    private val contracts = File(System.getProperty("veyq.repoRoot"), "contracts/api")

    @Test
    fun noKeptBodyCarriesATokenOrAnAddress() {
        // core's and every application's, as the composition installs them
        val reads = KeptRead.platform + me.raddatz.veyq.mobile.composition.AppComposition.applications.mapNotNull { it.kept }.flatMap { it.reads }
        val fields = fields(reads.map { it.model.descriptor })
        assertTrue(fields.size > 40, "the walk found ${fields.size} fields: it walks nothing")
        assertEquals(emptyList(), refused(fields))
        // each exception still names a field the kept bodies have
        assertEquals(emptySet(), allowed.keys - fields.map { "${it.first}.${it.second}" }.toSet())
    }

    /** The check against what it must refuse: a presigned download's answer (`presignBlobDownloads`) is red. */
    @Test
    fun aPresignedDownloadIsRefused() {
        val presigned = me.raddatz.veyq.mobile.api.models.BlobDownloadsBatchResponse.serializer().descriptor
        assertEquals(
            listOf("BlobDownloadResultItem.presignedGets: named", "PresignedUrl.url: named"),
            refused(fields(listOf(presigned))).filter { !it.endsWith("described") },
        )
    }

    /** Every (owner, field, description) below [roots]: an object's fields, a list's elements, a map's values. */
    private fun fields(roots: List<SerialDescriptor>): List<Triple<String, String, String?>> {
        val seen = HashSet<String>()
        val out = ArrayList<Triple<String, String, String?>>()
        fun walk(d: SerialDescriptor) {
            when (d.kind) {
                StructureKind.LIST -> walk(d.getElementDescriptor(0))
                StructureKind.MAP -> walk(d.getElementDescriptor(1))
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    val owner = d.serialName.substringAfterLast('.').removeSuffix("?")
                    if (!seen.add(owner)) return
                    val described = descriptions(owner)
                    for (i in 0 until d.elementsCount) {
                        val name = d.getElementName(i)
                        out += Triple(owner, name, described[name])
                        walk(d.getElementDescriptor(i))
                    }
                }
                else -> if (d.kind is SerialKind.CONTEXTUAL) Unit
            }
        }
        roots.forEach(::walk)
        return out
    }

    private fun refused(fields: List<Triple<String, String, String?>>): List<String> = fields.flatMap { (owner, name, text) ->
        val at = "$owner.$name"
        if (at in allowed) emptyList() else listOfNotNull(
            "$at: named".takeIf { NAMED.containsMatchIn(name) },
            "$at: described".takeIf { text != null && DESCRIBED.containsMatchIn(text) },
        )
    }

    private val schemaCache = HashMap<String, Map<String, String>>()

    /**
     * Schema [name]'s properties and the contract text of each (its description and type lines, its example left out),
     * read from whichever `contracts/api` file defines it under `components.schemas`.
     */
    private fun descriptions(name: String): Map<String, String> = schemaCache.getOrPut(name) {
        for (f in contracts.walkTopDown().filter { it.isFile && it.name.endsWith(".openapi.yaml") }) {
            val lines = f.readLines()
            val start = lines.indexOfFirst { it == "    $name:" }
            if (start < 0 || lines.subList(0, start).none { it == "  schemas:" }) continue
            val out = HashMap<String, StringBuilder>()
            var current: StringBuilder? = null
            var inProperties = false
            for (line in lines.drop(start + 1)) {
                if (line.isNotBlank() && line.length - line.trimStart().length <= 4) break
                if (line == "      properties:") { inProperties = true; continue }
                if (inProperties && Regex("^ {6}\\S").containsMatchIn(line)) { inProperties = false; current = null; continue }
                if (!inProperties) continue
                val prop = Regex("^ {8}([A-Za-z0-9_]+):(.*)$").find(line)
                if (prop != null) {
                    current = StringBuilder(prop.groupValues[2]).also { out[prop.groupValues[1]] = it }
                    continue
                }
                if (line.trimStart().startsWith("example:")) continue
                current?.append(' ')?.append(line.trim())
            }
            return@getOrPut out.mapValues { it.value.toString().replace(Regex("example:\\s*[^,}]*"), "") }
        }
        emptyMap()
    }

    companion object {
        /** A field named like a credential or an address. */
        private val NAMED = Regex("token|url|uri\\b|presign|bearer|cookie|passw|secret", RegexOption.IGNORE_CASE)

        /** A field whose contract text says it is a credential or an address to fetch from. */
        private val DESCRIBED = Regex(
            "presigned|bearer|signed (url|address)|(access|refresh|session|share|password|download|upload) token|a (url|uri|link) (to|that|where)",
            RegexOption.IGNORE_CASE,
        )
    }
}
