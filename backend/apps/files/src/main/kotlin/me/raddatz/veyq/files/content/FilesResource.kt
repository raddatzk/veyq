package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.files.generated.api.FilesApi
import me.raddatz.veyq.files.generated.model.FileBlobRef
import me.raddatz.veyq.files.generated.model.FileCompleteRequest
import me.raddatz.veyq.files.generated.model.FileConflict
import me.raddatz.veyq.files.generated.model.FileListItem
import me.raddatz.veyq.files.generated.model.FileListPage
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a member's files (cr-files-application F1a, `contracts/api/files/files.openapi.yaml`). Implements the
 * generated [FilesApi]; thin (ADR 0001) — delegates to [FilesHandler]. `@Authenticated`; the caller must hold a grant
 * for the scope (a non-member gets 404). Operator-blind: only ids, opaque sizes and the `K_blob` wrap cross.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated file plumbing on the upload and browse path (completion and listing; " +
        "the presign minting it runs on is core's BlobResource, exempt for that reason), the same posture as Memories' " +
        "MediaResource and for the same reason: a per-user cap has to be verified against real traffic before it " +
        "ships. Recorded as the open item in cc.security.rate-limit (§ Unbounded by decision).",
)
class FilesResource(
    private val handler: FilesHandler,
    private val identity: SecurityIdentity,
) : FilesApi {

    override fun completeFile(scopeId: UUID, nodeId: UUID, fileCompleteRequest: FileCompleteRequest): Response =
        respond(
            handler.complete(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                fileCompleteRequest.uploadId.toString(), fileCompleteRequest.expectedBlobId?.toString(),
            ),
        )

    override fun listFiles(scopeId: UUID, parentId: UUID?): Response =
        when (val o = handler.list(identity.principal.name, scopeId.toString(), parentId?.toString())) {
            is ListFilesOutcome.Ok -> Response.ok(
                FileListPage(
                    items = o.files.map {
                        FileListItem(nodeId = UUID.fromString(it.nodeId), fileBlob = it.blob?.let(::toRef), uploaderKey = it.uploaderKey)
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    companion object {
        /** A completion's answer on the wire — the member's `completeFile` and the guest's `completeShareFile` alike. */
        fun respond(o: CompleteFileOutcome): Response = when (o) {
            is CompleteFileOutcome.Ok -> Response.ok(toRef(o.blob)).build()
            // The one failure body that carries more than its code: the slot's current blob, the client's re-read.
            is CompleteFileOutcome.Stale -> Response.status(o.status.httpStatus)
                .entity(FileConflict(code = o.code, currentBlobId = o.currentBlobId?.let(UUID::fromString)))
                .build()
            is HttpFailure -> httpError(o)
        }

        fun toRef(b: FileBlobData) = FileBlobRef(
            blobId = UUID.fromString(b.blobId),
            chunkCount = b.chunkCount,
            byteSize = b.byteSize,
            wrappedBlobKey = b.wrappedBlobKey,
            manifest = b.manifest,
            wrappedBlobMeta = b.wrappedBlobMeta,
        )
    }
}
