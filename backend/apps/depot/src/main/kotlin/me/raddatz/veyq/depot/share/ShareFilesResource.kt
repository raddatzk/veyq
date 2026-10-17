package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.RequestClient
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.validation.validated
import me.raddatz.veyq.depot.content.FilesResource
import me.raddatz.veyq.depot.generated.api.ShareFilesApi
import me.raddatz.veyq.depot.generated.model.DropFolder
import me.raddatz.veyq.depot.generated.model.DropFolderRequest
import me.raddatz.veyq.depot.generated.model.ShareFileListItem
import me.raddatz.veyq.depot.generated.model.ShareFileListing
import me.raddatz.veyq.depot.generated.model.ShareFileCompleteRequest
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a guest's files on a share link (tag `share-files` of `files/files.openapi.yaml`,
 * cr-files-application F2).
 * Implements the generated [ShareFilesApi], whose root `/api/shares/{shareId}/files` is longer than core's `sharing`
 * root `/api/shares`, so RESTEasy routes these paths here and every other share route to core. Thin (ADR 0001) —
 * delegates to [ShareFilesHandler]. Every operation is `@PermitAll`: an account-less guest reaches it, gated only by
 * the link's state and the guest's token (core's `ShareGuestGate`). Each declares its own rate-limit posture.
 */
class ShareFilesResource(
    private val handler: ShareFilesHandler,
    private val dropFolders: DropFolderHandler,
    /** The client a guest's write records (the guest-write record, ADR 0093 § 10): resolved once, handed down. */
    private val client: RequestClient,
) : ShareFilesApi {

    @PermitAll
    // A drop-box guest's folder is a row a guest adds (once per identity), so it rides the per-share upload budget,
    // as createShareNode does.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun ensureShareDropFolder(shareId: String, dropFolderRequest: DropFolderRequest, xSharePassword: String?): Response {
        val r = dropFolderRequest
        return when (
            val o = dropFolders.ensure(
                shareId, xSharePassword, r.contributorToken, r.nodeId.toString(), r.encryptedName, r.slugToken,
                r.epoch.validated(), r.wrappedContentKey, client.seen(),
            )
        ) {
            is DropFolderOutcome.Folder -> Response.ok(
                DropFolder(
                    nodeId = UUID.fromString(o.folder.nodeId), epoch = o.folder.epoch, placementRevision = o.folder.placementRevision,
                    wrappedContentKey = o.folder.wrappedContentKey, created = o.folder.created,
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of the file blob references under a share — the Depot half of resolveShare's read, as " +
            "listShareMedia is Memories'. Volume is unbounded BY DECISION, not by omission: neither available axis " +
            "fits, and the residual gap is recorded in cc.security.rate-limit (§ Unbounded by decision).",
    )
    override fun listShareFiles(shareId: String, xSharePassword: String?): Response =
        when (val o = handler.list(shareId, xSharePassword)) {
            is ShareFilesListOutcome.Listed -> Response.ok(
                // The guest's own item: no uploader (inbox #190, ADR 0083 Decision 8) — FileRow.uploaderKey stops here.
                ShareFileListing(items = o.files.map { ShareFileListItem(nodeId = UUID.fromString(it.nodeId), fileBlob = it.blob?.let(FilesResource::toRef)) }),
            ).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    // The attach half of a guest's file upload — it creates the attachment row, so it rides the same per-share upload
    // budget as startShareBlobUploads (ShareRateLimiter.allowUpload), as completeShareMedia does.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun completeShareFile(
        shareId: String, nodeId: UUID, shareFileCompleteRequest: ShareFileCompleteRequest, xSharePassword: String?,
    ): Response {
        val r = shareFileCompleteRequest
        return when (
            val o = handler.complete(
                shareId, xSharePassword, r.contributorToken, nodeId.toString(), r.uploadId.toString(),
                r.expectedBlobId?.toString(), client.seen(),
            )
        ) {
            is ShareFileCompleteOutcome.Completed -> FilesResource.respond(o.outcome)
            is HttpFailure -> httpError(o)
        }
    }
}
