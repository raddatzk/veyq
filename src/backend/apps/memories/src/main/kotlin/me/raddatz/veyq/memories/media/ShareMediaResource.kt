package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.RequestClient
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.memories.generated.api.ShareMediaApi
import me.raddatz.veyq.memories.generated.model.ShareMediaBatchRequest
import me.raddatz.veyq.memories.generated.model.ShareMediaBatchResponse
import me.raddatz.veyq.memories.generated.model.ShareMediaListing
import me.raddatz.veyq.memories.generated.model.ShareMediaResultItem
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a guest's media on a share link (`memories/share-media.openapi.yaml`). Implements the generated
 * [ShareMediaApi], whose root `/api/shares/{shareId}/media` is longer than core's `sharing` root `/api/shares`, so
 * RESTEasy routes these paths here and every other share route to core (`ShareMediaRouteSliceTest`). Thin
 * (ADR 0001) — delegates to [ShareMediaHandler]. Every operation is `@PermitAll`: an account-less guest reaches it,
 * gated only by the link's state and the guest's token (core's `ShareGuestGate`), never by auth. Each declares its
 * own rate-limit posture; there is no class-level default to silently cover them.
 */
class ShareMediaResource(
    private val handler: ShareMediaHandler,
    /** The client a guest's upload records (the guest-write record, ADR 0093 § 10): resolved once, handed down. */
    private val client: RequestClient,
) : ShareMediaApi {

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of the sealed media under a share — the Memories half of resolveShare's read. Volume is " +
            "unbounded BY DECISION, not by omission: neither available axis fits, and the residual gap is recorded in " +
            "cc.security.rate-limit (§ Unbounded by decision) rather than left silent.",
    )
    override fun listShareMedia(shareId: String, xSharePassword: String?, nodeId: UUID?, after: UUID?, limit: Int?, ifNoneMatch: String?): Response =
        when (val o = handler.list(shareId, xSharePassword, nodeId?.toString(), after?.toString(), ItemsPage.limitOf(limit), ifNoneMatch)) {
            is ShareMediaListOutcome.Listed -> Response.ok(
                ShareMediaListing(
                    media = o.media.map { ShareMediaWire.media(it, it.row.mediaId in o.blocked) },
                    identities = ShareMediaWire.identities(o.uploaderKeys),
                    more = o.more,
                ),
            ).apply { if (o.etag != null) header("ETag", o.etag) }.build()
            // inbox #497: the digest of what the read would serve still equals the guest's — nothing to send.
            is ShareMediaListOutcome.NotModified -> Response.notModified().header("ETag", o.etag).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    // The attach half of the contributor upload flow — it creates the media rows, so it rides the same per-share
    // upload budget as startShareBlobUploads (ShareRateLimiter.allowUpload).
    @RateLimit(key = RateLimitKey.SHARE)
    override fun completeShareMedia(shareId: String, shareMediaBatchRequest: ShareMediaBatchRequest, xSharePassword: String?): Response {
        val items = shareMediaBatchRequest.items.map { item ->
            ShareMediaItem(
                mediaId = item.mediaId.toString(),
                originalUploadId = item.originalUploadId.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
            )
        }
        return when (
            val o = handler.complete(
                shareId, xSharePassword, shareMediaBatchRequest.contributorToken, items, shareMediaBatchRequest.nodeId?.toString(),
                client.seen(),
            )
        ) {
            is ShareMediaCompleteOutcome.Results -> Response.ok(
                ShareMediaBatchResponse(
                    results = o.items.map { ShareMediaResultItem(blobId = it.blobId?.let(UUID::fromString), mediaId = it.ownerId?.let(UUID::fromString), error = it.error) },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    @RateLimitExempt(
        reason = "Delete-own is bounded by what the caller contributed: the store authorizes by single-row " +
            "ownership (the media's share_identity_id must equal the token's identity), and those rows are " +
            "already capped by the per-share upload budget. A separate delete bound would cap nothing new.",
    )
    override fun deleteShareMedia(shareId: String, mediaId: UUID, xContributorToken: String, xSharePassword: String?): Response =
        when (val o = handler.delete(shareId, xSharePassword, xContributorToken, mediaId.toString())) {
            ShareMediaDeleteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
