package com.veyq.app.features.media

import com.veyq.app.generated.api.MediaApi
import com.veyq.app.generated.model.BlobRef
import com.veyq.app.generated.model.CoverBlobCommitRequest
import com.veyq.app.generated.model.BlobDownloadResultItem
import com.veyq.app.generated.model.BlobDownloadsBatchRequest
import com.veyq.app.generated.model.BlobDownloadsBatchResponse
import com.veyq.app.generated.model.BlobUploadResultItem
import com.veyq.app.generated.model.BlobUploadsBatchRequest
import com.veyq.app.generated.model.BlobUploadsBatchResponse
import com.veyq.app.generated.model.MediaBatchRequest
import com.veyq.app.generated.model.MediaBatchResponse
import com.veyq.app.generated.model.MediaDerivativeKind
import com.veyq.app.generated.model.MediaDerivativeSummary
import com.veyq.app.generated.model.MediaExifResponse
import com.veyq.app.generated.model.MediaListPage
import com.veyq.app.generated.model.MediaMetaResponse
import com.veyq.app.generated.model.MediaMetaUpdate
import com.veyq.app.generated.model.MediaResultItem
import com.veyq.app.generated.model.MediaSummary
import com.veyq.app.generated.model.PresignedUrl
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import com.veyq.app.shared.tenant.RequestTenantDomain
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for media blobs (en.memory.media, cr-0028). Implements the generated [MediaApi]; thin
 * (ADR 0001) — delegates to [MediaHandler]. `@Authenticated`; the caller (from the `SecurityIdentity`
 * principal) must hold a grant for the scope (a non-member gets 404). Operator-blind: only ciphertext wraps +
 * opaque metadata + presigned URLs cross. Batch-shaped: 200 with per-item results (partial success); only
 * access/auth failures are top-level.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated media plumbing. Deliberately NOT key = USER yet: presign " +
        "minting is a real amplification axis, but a per-user cap on the main upload and browse path has " +
        "to be verified against real traffic before it ships, or it throttles a legitimate bulk upload. " +
        "Recorded as the open item in cc.security.rate-limit (\u00a7 Unbounded by decision).",
)
class MediaResource(
    private val handler: MediaHandler,
    private val identity: SecurityIdentity,
    private val tenantDomain: RequestTenantDomain,
) : MediaApi {

    /**
     * The tenant domain this request arrived on, for signing its presigned URLs (ADR 0063). Resolved through the
     * `tenant` table, **never** the raw `Host`/`X-Forwarded-Host` value — see [RequestTenantDomain]; null (an
     * unresolvable or spoofed host, or the bootstrap domain) signs against the configured default.
     */
    private fun signingDomain(): String? = tenantDomain.resolve()

    override fun startBlobUploads(scopeId: UUID, blobUploadsBatchRequest: BlobUploadsBatchRequest): Response {
        val declares = blobUploadsBatchRequest.items.map {
            UploadDeclare(it.chunkCount, it.byteSize, it.wrappedBlobKey)
        }
        return when (val o = handler.start(identity.principal.name, scopeId.toString(), declares, signingDomain())) {
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

    override fun completeMedia(scopeId: UUID, mediaBatchRequest: MediaBatchRequest): Response {
        val items = mediaBatchRequest.items.map { item ->
            CompleteItem(
                nodeId = item.nodeId.toString(),
                originalUploadId = item.originalUploadId.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
                bucketId = item.bucketId?.toString(), // cr-0113: an explicit target bucket, else the caller's primary
            )
        }
        return when (val o = handler.complete(identity.principal.name, scopeId.toString(), items)) {
            is CompleteOutcome.Results -> Response.ok(
                MediaBatchResponse(
                    results = o.items.map {
                        MediaResultItem(
                            blobId = it.blobId?.let(UUID::fromString),
                            mediaId = it.mediaId?.let(UUID::fromString),
                            error = it.error,
                        )
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    override fun listNodeMedia(scopeId: UUID, nodeId: UUID, ifNoneMatch: String?, since: String?, sinceDelete: Int?, state: String?): Response =
        when (
            val o = handler.listMedia(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                parseETag(ifNoneMatch), parseCursor(since), sinceDelete ?: 0, trashed = state == "trashed",
            )
        ) {
            is ListMediaOutcome.Page -> Response.ok(
                MediaListPage(
                    media = o.rows.map(::toSummary),
                    mediaRevision = o.mediaRevision,
                    deleteRevision = o.deleteRevision,
                    mediaIds = o.mediaIds?.map(UUID::fromString),
                ),
            ).header("ETag", "\"${o.mediaRevision}\"").build() // the ETag doubles as the client's next If-None-Match
            ListMediaOutcome.NotModified -> Response.notModified().build() // 304 — no body, no media query (idle fast path)
            is HttpFailure -> httpError(o)
        }

    /** The client's `If-None-Match` echo of `media_revision` — lenient (strips a weak marker + quotes); null when absent/unparseable ⇒ never a 304. */
    private fun parseETag(raw: String?): Int? =
        raw?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.toIntOrNull()

    /**
     * Parse the keyset cursor `"updatedAt,mediaId"`; null (⇒ the full list) when absent or malformed. Robust to
     * the timestamp's wire form — an offset date-time (`…Z` / `…+00:00`, what `MediaSummary.updatedAt` serializes
     * as) or a bare instant — so the client can echo back exactly what it received.
     */
    private fun parseCursor(raw: String?): MediaCursor? {
        val s = raw?.trim().orEmpty()
        val comma = s.indexOf(',')
        if (comma <= 0 || comma == s.length - 1) return null
        val ts = s.substring(0, comma).trim()
        val id = s.substring(comma + 1).trim()
        val instant = runCatching { OffsetDateTime.parse(ts).toInstant() }
            .recoverCatching { Instant.parse(ts) }
            .getOrNull() ?: return null
        return MediaCursor(instant, id)
    }

    override fun getMediaExif(scopeId: UUID, mediaId: UUID): Response =
        when (val o = handler.mediaExif(identity.principal.name, scopeId.toString(), mediaId.toString())) {
            is MediaExifOutcome.Found -> Response.ok(MediaExifResponse(wrappedExif = o.wrappedExif)).build()
            is HttpFailure -> httpError(o)
        }

    override fun updateMediaMeta(scopeId: UUID, mediaId: UUID, mediaMetaUpdate: MediaMetaUpdate): Response =
        when (val o = handler.updateMeta(identity.principal.name, scopeId.toString(), mediaId.toString(), mediaMetaUpdate.wrappedMeta)) {
            is MetaOutcome.Found -> Response.ok(MediaMetaResponse(wrappedMeta = o.wrappedMeta)).build()
            is HttpFailure -> httpError(o)
        }

    override fun deleteMedia(scopeId: UUID, mediaId: UUID): Response =
        when (val o = handler.deleteMedia(identity.principal.name, scopeId.toString(), mediaId.toString())) {
            DeleteMediaOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun restoreMedia(scopeId: UUID, mediaId: UUID): Response =
        when (val o = handler.restoreMedia(identity.principal.name, scopeId.toString(), mediaId.toString())) {
            RestoreMediaOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun purgeMedia(scopeId: UUID, mediaId: UUID): Response =
        when (val o = handler.purgeMedia(identity.principal.name, scopeId.toString(), mediaId.toString())) {
            PurgeMediaOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun commitCoverBlob(scopeId: UUID, nodeId: UUID, coverBlobCommitRequest: CoverBlobCommitRequest): Response =
        when (val o = handler.commitCover(identity.principal.name, scopeId.toString(), nodeId.toString(), coverBlobCommitRequest.uploadId.toString())) {
            is CoverCommitOutcome.Ok -> Response.ok(
                BlobRef(
                    blobId = UUID.fromString(o.ref.blobId),
                    chunkCount = o.ref.chunkCount,
                    byteSize = o.ref.byteSize,
                    wrappedBlobKey = o.ref.wrappedBlobKey,
                    manifest = o.ref.manifest,
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    override fun deleteCoverBlob(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.deleteCover(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            CoverDeleteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun presignBlobDownloads(scopeId: UUID, blobDownloadsBatchRequest: BlobDownloadsBatchRequest): Response {
        val blobIds = blobDownloadsBatchRequest.items.map { it.blobId.toString() }
        return when (val o = handler.download(identity.principal.name, scopeId.toString(), blobIds, signingDomain())) {
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

    private fun toPresigned(ref: PresignedRef) = PresignedUrl(index = ref.index, url = ref.url)

    private fun toSummary(row: MediaRow) = MediaSummary(
        mediaId = UUID.fromString(row.mediaId),
        blobId = UUID.fromString(row.blobId),
        chunkCount = row.chunkCount,
        byteSize = row.byteSize,
        wrappedBlobKey = row.wrappedBlobKey,
        updatedAt = row.updatedAt.atOffset(ZoneOffset.UTC), // cr-0107: the keyset cursor's sort key
        manifest = row.manifest,
        derivatives = row.derivatives.map(::toDerivativeSummary),
        wrappedMeta = row.wrappedMeta,
        deletedAt = row.deletedAt?.atOffset(ZoneOffset.UTC), // cr-0110: non-null only in the trashed listing
        deletedBy = row.deletedBy,
        ownerKey = row.ownerKey, // cr-0113: the uploader's opaque owner_key (the buckets attribution)
        bucketId = row.bucketId?.let(UUID::fromString), // cr-0113: the media's bucket (null = the uploader's primary)
    )

    private fun toDerivativeSummary(d: DerivativeRow) = MediaDerivativeSummary(
        kind = MediaDerivativeKind.decode(d.kind),
        blobId = UUID.fromString(d.blobId),
        chunkCount = d.chunkCount,
        byteSize = d.byteSize,
        wrappedBlobKey = d.wrappedBlobKey,
        manifest = d.manifest,
    )
}
