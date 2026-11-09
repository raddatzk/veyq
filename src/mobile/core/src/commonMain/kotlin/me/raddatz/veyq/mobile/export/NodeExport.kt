package me.raddatz.veyq.mobile.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A node downloaded as a plaintext ZIP (ADR 0082, uc.platform.export-import), the web's `core/export/exportSubtree.ts` for a node
 * with nothing under it — a Pot, the one kind the app holds — in format version 2 (`contracts/export/veyq-subtree.schema.json`):
 * the node a directory named after it, its payloads files in it under their own safe names ([ArchiveName]), and the manifest
 * beside the tree at `.veyq/manifest.json`, written last, once every payload's SHA-256 is known. What the node holds is its
 * kind's to say ([Content], the web's `exportContent` hook); core owns the envelope and never reads the content but its
 * payload references. The archive leaves the end-to-end encryption by design (ADR 0082 decision 2): it is what the person
 * saves, never sent anywhere.
 */
object NodeExport {
    const val FORMAT = "veyq-subtree"
    const val VERSION = 2
    const val MANIFEST_PATH = "${ArchiveName.MANIFEST_DIR}/manifest.json"

    /** One file the content refers to: its name in the node's directory, its size, its type, and where its bytes come from. */
    class Payload(
        val fileName: String,
        val size: Long,
        val mediaType: String,
        val modifiedAt: Long? = null,
        val read: suspend () -> ByteArray,
    )

    /** What a kind exports of one node: its content type (`<application>.<kind>`) and schema version, its payloads, and the
     *  content itself, built from their manifest references (`{path, size, mediaType, modifiedAt?, sha256}`, payload order). */
    class Content(val type: String, val schema: Int, val payloads: List<Payload>, val build: (refs: List<JsonObject>) -> JsonObject)

    class Written(val payloads: Int, val bytes: Long)

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** The file a download of node [name] is saved as (the web's `${archiveName(name)}.zip`). */
    fun fileName(name: String): String = ArchiveName.of(name, mutableSetOf()) + ".zip"

    /** The application a content type belongs to (`expenses.pot` → `expenses`). */
    fun applicationOf(type: String): String = type.substringBefore('.')

    /** An ISO-8601 instant as JavaScript's `toISOString` writes it: always milliseconds, always `Z`. */
    fun isoMs(ms: Long): String {
        val s = kotlin.time.Instant.fromEpochMilliseconds(ms - ms % 1000).toString().removeSuffix("Z")
        return "$s.${(ms % 1000).toString().padStart(3, '0')}Z"
    }

    /**
     * Writes the ZIP of node [name] of [kind], holding [content], into [sink]: the directory, each payload (read whole —
     * the app's payloads are capped, a receipt at 10 MiB — and hashed), then the manifest. [sha256] is the suite's.
     * A payload that reads to another size than it declared fails the archive (`ZipWriter.ZipFailure`).
     */
    suspend fun write(
        name: String, kind: String, content: Content, appVersion: String, nowMs: Long,
        sha256: (ByteArray) -> ByteArray, sink: (ByteArray) -> Unit,
    ): Written {
        val path = ArchiveName.of(name, mutableSetOf(ArchiveName.MANIFEST_DIR))
        val inside = mutableSetOf<String>()
        val paths = content.payloads.map { "$path/${ArchiveName.of(it.fileName, inside)}" }
        val zip = ZipWriter(sink, nowMs)
        zip.addDirectory(path)
        var bytes = 0L
        val refs = content.payloads.mapIndexed { i, p ->
            val data = p.read()
            zip.addBytes(paths[i], data.also { if (it.size.toLong() != p.size) throw ZipWriter.ZipFailure("${paths[i]} is ${it.size} bytes, declared ${p.size}") })
            bytes += data.size
            buildJsonObject {
                put("path", paths[i]); put("size", p.size); put("mediaType", p.mediaType)
                p.modifiedAt?.let { put("modifiedAt", it) }
                put("sha256", sha256(data).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') })
            }
        }
        val manifest = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", isoMs(nowMs))
            put("appVersion", appVersion)
            put("applications", JsonArray(listOf(buildJsonObject { put("id", applicationOf(content.type)); put("contentSchema", content.schema) })))
            put("root", buildJsonObject {
                put("id", "n1"); put("kind", kind); put("name", name); put("path", path)
                put("content", content.build(refs))
            })
        }
        zip.addBytes(MANIFEST_PATH, pretty.encodeToString(JsonObject.serializer(), manifest).encodeToByteArray())
        zip.finish()
        return Written(content.payloads.size, bytes)
    }
}
