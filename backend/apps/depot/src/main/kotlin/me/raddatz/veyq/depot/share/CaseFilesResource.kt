package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.DepotBlobRoles
import me.raddatz.veyq.depot.content.FileBlobData
import me.raddatz.veyq.depot.content.FilesResource
import me.raddatz.veyq.depot.generated.api.CaseFilesApi
import me.raddatz.veyq.depot.generated.model.ShareFileListItem
import me.raddatz.veyq.depot.generated.model.ShareFileListing
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * A moderator's review of a case's files (uc.moderation.review-a-case step 5): the content blob of every `FILE` node the
 * case's hold copied — core's held node and attachment rows, Depot owning no table — in the guest listing's own shape,
 * read from the snapshot only. Behind core's `ModerationProofGate`, found by this resource's path under
 * `/api/moderation`; a block never hides evidence from the moderator.
 */
@Authenticated
@RateLimitExempt(
    reason = "Key-proved and MODERATOR-only: every call spends a single-use challenge of a bounded set of accounts the " +
        "Server Owner appointed, and serves only rows a hold already copied.",
)
class CaseFilesResource(
    private val snapshots: CaseSnapshots,
    private val blobs: BlobUploadStore,
) : CaseFilesApi {

    override fun listCaseFiles(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response {
        val held = QuarkusTransaction.requiringNew().call {
            if (ModerationCaseEntity.findById(caseId.toString()) == null) return@call null
            snapshots.rowsOf(caseId.toString(), Instant.now()) ?: emptyList()
        } ?: return httpError(CaseFailures.caseNotFound)
        if (held.isEmpty()) return httpError(CaseFailures.nothingHeldRead)
        val blobRows = held.filter { it.table == "veyq_blob" }.associateBy { it.sourceId }
        val contents = held.filter { it.table == "veyq_node_attachment" && it["slot"] == DepotBlobRoles.FILE_CONTENT }
            .associateBy { it["node_id"] }
        val items = held.filter { it.table == "veyq_node" && it["kind"] == DepotNodeKinds.FILE && it["deleted_at"] == null }.map { node ->
            val blob = contents[node.sourceId]?.get("blob_id")?.let { blobRows[it] }
            ShareFileListItem(
                nodeId = UUID.fromString(node.sourceId),
                fileBlob = blob?.let {
                    FilesResource.toRef(
                        FileBlobData(
                            blobId = it.sourceId,
                            chunkCount = it["chunk_count"]!!.toInt(),
                            byteSize = it["byte_size"]!!.toLong(),
                            wrappedBlobKey = it["wrapped_blob_key"]!!,
                            manifest = blobs.decodeManifest(it["manifest"]),
                            wrappedBlobMeta = it["wrapped_meta"],
                        ),
                    )
                },
            )
        }
        return Response.ok(ShareFileListing(items = items)).build()
    }
}
