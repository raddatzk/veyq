package com.veyq.core.blob

import com.veyq.core.blobstore.PresignedRef
import com.veyq.core.generated.api.BlobApi
import com.veyq.core.generated.model.BlobDownloadResultItem
import com.veyq.core.generated.model.BlobDownloadsBatchRequest
import com.veyq.core.generated.model.BlobDownloadsBatchResponse
import com.veyq.core.generated.model.BlobUploadResultItem
import com.veyq.core.generated.model.BlobUploadWindowResultItem
import com.veyq.core.generated.model.BlobUploadWindowsRequest
import com.veyq.core.generated.model.BlobUploadWindowsResponse
import com.veyq.core.generated.model.BlobUploadsBatchRequest
import com.veyq.core.generated.model.BlobUploadsBatchResponse
import com.veyq.core.generated.model.PresignedUrl
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.ratelimit.RateLimitExempt
import com.veyq.core.tenant.RequestTenant
import com.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for the generic blob transfer (the core `blob` tag, ADR 0079): start uploads, presign each next
 * window, presign downloads. Implements the generated [BlobApi]; thin (ADR 0001) — delegates to [BlobUploadHandler].
 * Completing an upload is the owning application's operation (Memories' `completeMedia`, `commitCoverBlob`), not
 * this resource's. `@Authenticated`; the caller (from the `SecurityIdentity` principal) must hold a grant for the
 * scope (a non-member gets 404). Operator-blind: only ciphertext wraps + opaque metadata + presigned URLs cross.
 * Batch-shaped: 200 with per-item results (partial success); only access/auth failures are top-level.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated media plumbing. Deliberately NOT key = USER yet: presign " +
        "minting is an amplification axis, bounded per call (a start or a window signs at most 256 items x " +
        "veyq.blob.presign-window URLs) but not per caller, and a per-user cap on the main upload and browse " +
        "path has to be verified against real traffic before it ships, or it throttles a legitimate bulk " +
        "upload. Recorded as the open item in cc.security.rate-limit (§ Unbounded by decision).",
)
class BlobResource(
    private val blobs: BlobUploadHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
) : BlobApi {

    /**
     * The tenant domain this request arrived on, for signing its presigned URLs (ADR 0063). Resolved through the
     * `tenant` table, **never** the raw `Host`/`X-Forwarded-Host` value — see [RequestTenant]; null (an
     * unresolvable or spoofed host, or the bootstrap domain) signs against the configured default.
     */
    private fun signingDomain(): String? = tenant.resolve()?.domain

    override fun startBlobUploads(scopeId: UUID, blobUploadsBatchRequest: BlobUploadsBatchRequest): Response {
        val declares = blobUploadsBatchRequest.items.map {
            UploadDeclare(it.chunkCount, it.byteSize, it.wrappedBlobKey, it.blobId.toString(), it.ownerId.toString(), it.role.value)
        }
        return when (val o = blobs.start(identity.principal.name, scopeId.toString(), declares, signingDomain())) {
            is StartOutcome.Results -> Response.ok(
                BlobUploadsBatchResponse(
                    results = o.items.map {
                        BlobUploadResultItem(
                            uploadId = it.uploadId?.let(UUID::fromString),
                            presignedPuts = it.presignedPuts?.map(::toPresigned),
                            error = it.error,
                        )
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    override fun presignBlobDownloads(scopeId: UUID, blobDownloadsBatchRequest: BlobDownloadsBatchRequest): Response {
        val blobIds = blobDownloadsBatchRequest.items.map { it.blobId.toString() }
        return when (val o = blobs.download(identity.principal.name, scopeId.toString(), blobIds, signingDomain())) {
            is DownloadOutcome.Results -> Response.ok(
                BlobDownloadsBatchResponse(
                    results = o.items.map {
                        BlobDownloadResultItem(presignedGets = it.presignedGets?.map(::toPresigned), error = it.error)
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    override fun presignBlobUploadWindows(scopeId: UUID, blobUploadWindowsRequest: BlobUploadWindowsRequest): Response {
        val requests = blobUploadWindowsRequest.items.map { WindowRequest(it.uploadId.toString(), it.fromChunk.validated()) }
        return when (val o = blobs.window(identity.principal.name, scopeId.toString(), requests, signingDomain())) {
            is WindowOutcome.Results -> Response.ok(
                BlobUploadWindowsResponse(
                    results = o.items.map {
                        BlobUploadWindowResultItem(presignedPuts = it.presignedPuts?.map(::toPresigned), error = it.error)
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    private fun toPresigned(ref: PresignedRef) = PresignedUrl(index = ref.index, url = ref.url)
}
