package me.raddatz.veyq.mobile.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A file in memory as a picker hands one over, every read counted ([read] bytes in [reads] calls). */
class CountingSource(name: String, private val bytes: ByteArray, size: Long = bytes.size.toLong(), private val at: (Long, Int) -> ByteArray = { from, length ->
    bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
}) : UploadSource(name, size, "application/zip", null) {
    var read = 0L
    var reads = 0
    override suspend fun read(from: Long, length: Int): ByteArray = at(from, length).also { read += it.size; reads++ }
}

/** One case of `contracts/export/import-cases/cases.v1.json` (ADR 0152 decision 4). */
class ImportCase(val id: String, val input: ImportInput, val screen: Boolean, val expect: JsonObject) {
    val refused: String? get() = expect["refused"]?.jsonPrimitive?.content
    val at: String? get() = expect["at"]?.jsonPrimitive?.content
    val plan: JsonObject? get() = expect["plan"]?.jsonObject
}

@OptIn(ExperimentalEncodingApi::class)
object ImportFixtures {
    fun bytes(b64: String): ByteArray = Base64.Default.decode(b64)

    /** The cases both clients answer to, each a fresh input. */
    fun cases(): List<ImportCase> {
        val doc = Json.parseToJsonElement(readRepoFile("contracts/export/import-cases/cases.v1.json").decodeToString()).jsonObject
        return doc["cases"]!!.jsonArray.map { it.jsonObject }.map { c ->
            val id = c["id"]!!.jsonPrimitive.content
            val input = c["zip"]?.let { ImportInput.Zip(CountingSource("$id.zip", bytes(it.jsonPrimitive.content))) }
                ?: ImportInput.Folder("download", (c["directory"] as JsonArray).map { it.jsonObject }.map { f ->
                    val path = f["path"]!!.jsonPrimitive.content
                    path to CountingSource(path.substringAfterLast('/'), bytes(f["bytes"]!!.jsonPrimitive.content))
                })
            ImportCase(id, input, c["screen"]!!.jsonPrimitive.content == "true", c["expect"]!!.jsonObject)
        }
    }

    /** The web's download (`contracts/export/import-cases/web-download.zip`, written by the web's `webDownload.fixture.test.ts`). */
    fun webDownload(): ByteArray = readRepoFile("contracts/export/import-cases/web-download.zip")
}
