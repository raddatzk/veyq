package me.raddatz.veyq.mobile.depot

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Depot's part in a download and a copy (the web's `features/depot/exportImport.ts`): a file is its content, streamed frame
 * by frame into the archive at the file's own path (`depot.file` schema 1, `downloadsAsFile`) — a file without content
 * (an upload that never finished) holds nothing to export —, a drop box a directory of its guests' folders with no payload
 * of its own (`depot.drop-box` schema 1). A file's copy is read here and uploaded again under a fresh `K_blob` (the server
 * copies nothing: it sees an ordinary upload). One per run: the tenant's files are listed once.
 */
class DepotExport {
    private var listed: Map<String, DepotFiles.File>? = null

    private suspend fun fileOf(tenant: TenantSession, node: OpenNode): DepotFiles.File? =
        (listed ?: DepotFiles.list(tenant).also { listed = it })[node.id]

    /** File [node]'s export content; null where it holds no content. */
    suspend fun file(tenant: TenantSession, node: OpenNode): NodeExport.Content? {
        val f = fileOf(tenant, node)?.takeIf { it.hasContent } ?: return null
        val size = f.size ?: return null
        val payload = NodeExport.Payload(node.name, size, f.type?.takeIf { it.isNotBlank() } ?: "application/octet-stream", f.modifiedAt) { emit ->
            DepotFiles.read(tenant, f, sink = emit)
        }
        return NodeExport.Content(FILE_TYPE, SCHEMA, listOf(payload), asFile = true) { refs ->
            buildJsonObject {
                put("type", FILE_TYPE)
                put("schema", SCHEMA)
                put("file", refs.single())
            }
        }
    }

    /**
     * File [node] copied under [parentId] as [name]: its content read frame by frame and uploaded again (`fileUploadJob`
     * through `importFile`); a name already held there is said ([SubtreeCopy.Made.nameTaken]), nothing made.
     */
    suspend fun duplicate(
        tenant: TenantSession, node: OpenNode, parentId: String?, name: String, tokenInput: (String, String) -> String?,
    ): SubtreeCopy.Made {
        val f = fileOf(tenant, node)?.takeIf { it.hasContent } ?: return SubtreeCopy.Made(null)
        val size = f.size ?: return SubtreeCopy.Made(null)
        val frames = FrameReader(tenant, f)
        val r = DepotFiles.upload(
            tenant, node.scopeId, parentId, name, size, f.type?.takeIf { it.isNotBlank() } ?: "application/octet-stream", f.modifiedAt,
            tokenInput, frames::read,
        )
        return when (r.outcome) {
            DepotFiles.Outcome.DONE -> SubtreeCopy.Made(r.nodeId)
            DepotFiles.Outcome.EXISTS, DepotFiles.Outcome.TAKEN_BY_OTHER -> SubtreeCopy.Made(null, nameTaken = true)
            else -> SubtreeCopy.Made(null)
        }
    }

    /** The source's plaintext by frame, a window of [WINDOW] frames read at a time (the upload asks frame after frame). */
    private class FrameReader(private val tenant: TenantSession, private val file: DepotFiles.File) {
        private var first = -1
        private val window = ArrayList<ByteArray>()

        suspend fun read(from: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var at = 0
            while (at < length) {
                val pos = from + at
                val frame = (pos / V1.FRAME_PLAINTEXT).toInt()
                val inFrame = (pos % V1.FRAME_PLAINTEXT).toInt()
                if (first < 0 || frame < first || frame >= first + window.size) fill(frame)
                val bytes = window.getOrNull(frame - first) ?: throw IllegalStateException("source_changed")
                val n = minOf(length - at, bytes.size - inFrame)
                if (n <= 0) throw IllegalStateException("source_changed")
                bytes.copyInto(out, at, inFrame, inFrame + n)
                at += n
            }
            return out
        }

        private suspend fun fill(frame: Int) {
            window.clear()
            first = frame
            DepotFiles.read(tenant, file, frame, frame + WINDOW) { window += it }
        }
    }

    companion object {
        const val FILE_TYPE = "depot.file"
        const val DROP_BOX_TYPE = "depot.drop-box"
        const val SCHEMA = 1
        private const val WINDOW = 8
        private const val FILE = "FILE"

        /** A drop box's content: none of its own (its guests' folders are its children). */
        fun dropBox(): NodeExport.Content = NodeExport.Content(DROP_BOX_TYPE, SCHEMA, emptyList()) {
            buildJsonObject {
                put("type", DROP_BOX_TYPE)
                put("schema", SCHEMA)
            }
        }
    }
}
