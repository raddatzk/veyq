package me.raddatz.veyq.mobile.depot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.export.ImportRefused
import me.raddatz.veyq.mobile.export.Manifest
import me.raddatz.veyq.mobile.export.NodeImport

/**
 * Depot's part in an import (ADR 0152 decision 3; the web's `features/depot/exportImport.ts` `importFile`,
 * `validateFileContent`, `validateDropBoxContent`): a file is created and its checked payload uploaded through the same
 * upload a picked file takes (`DepotFiles.upload`, read by offset, sealed under a fresh key like any upload); a drop box
 * imports as a plain folder — a drop key, its guests' identities and its links cannot be rebuilt from a download, so
 * nothing tries.
 */
object DepotImport {
    private const val APPLICATION = "depot"

    private fun bad(message: String) = NodeImport.Problem(ImportRefused.Refusal.INVALID_MANIFEST, message)

    /** A `depot.file` content from a download is its one payload reference (core checks the reference itself). */
    fun validateFile(content: JsonObject): NodeImport.Problem? {
        val type = (content["type"] as? JsonPrimitive)?.content
        if (type != DepotExport.FILE_TYPE) return bad("not a Depot file: $type")
        val f = content["file"] as? JsonObject
        if (f == null || (f["path"] as? JsonPrimitive)?.isString != true || Manifest.number(f["size"]) == null) return bad("a file without its payload")
        return null
    }

    /** A `depot.drop-box` content carries nothing but its type and version (it imports as a plain folder). */
    fun validateDropBox(content: JsonObject): NodeImport.Problem? {
        val type = (content["type"] as? JsonPrimitive)?.content
        if (type != DepotExport.DROP_BOX_TYPE) return bad("not a Depot drop box: $type")
        val extra = content.keys.filter { it != "type" && it != "schema" }
        return if (extra.isNotEmpty()) bad("a drop box with ${extra.joinToString(", ")}") else null
    }

    /** A drop box: a plain folder. */
    val dropBox = NodeImport.Hook(APPLICATION, DepotExport.SCHEMA, ::validateDropBox, asFolder = true)

    /** A file: its node created and its payload uploaded, named as the import says ([tokenInput] folds a sibling's name). */
    fun file(tokenInput: (String, String) -> String?) = NodeImport.Hook(APPLICATION, DepotExport.SCHEMA, ::validateFile) { ctx ->
        val ref = Manifest.payloadOf(ctx.node.content?.get("file") as? JsonObject ?: throw NodeImport.Failed("import_invalid_content"))
        val checked = ctx.payload(ref)
        val r = DepotFiles.upload(
            ctx.session(), ctx.scopeId, ctx.parentId, ctx.name, ref.size, ref.mediaType.ifBlank { "application/octet-stream" }, ref.modifiedAt,
            tokenInput, checked::read, ctx.progress,
        )
        when (r.outcome) {
            DepotFiles.Outcome.DONE -> NodeImport.Made(r.nodeId)
            DepotFiles.Outcome.EXISTS, DepotFiles.Outcome.TAKEN_BY_OTHER -> NodeImport.Made(null, nameTaken = true)
            DepotFiles.Outcome.NO_ROOM -> NodeImport.Made(null, failure = "quota_exceeded")
            else -> NodeImport.Made(null, failure = r.outcome.name.lowercase())
        }
    }
}
