package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.memories.generated.api.MediaApi
import me.raddatz.veyq.memories.generated.model.BlobRef
import me.raddatz.veyq.memories.generated.model.CoverBlobCommitRequest
import me.raddatz.veyq.memories.generated.model.MediaBatchRequest
import me.raddatz.veyq.memories.generated.model.MediaBatchResponse
import me.raddatz.veyq.memories.generated.model.MediaDerivativeKind
import me.raddatz.veyq.memories.generated.model.MediaDerivativeSummary
import me.raddatz.veyq.memories.generated.model.MediaExifResponse
import me.raddatz.veyq.memories.generated.model.MediaListPage
import me.raddatz.veyq.memories.generated.model.MediaMetaResponse
import me.raddatz.veyq.memories.generated.model.MediaMetaUpdate
import me.raddatz.veyq.memories.generated.model.MediaResultItem
import me.raddatz.veyq.memories.generated.model.MediaSummary
import me.raddatz.veyq.memories.generated.model.UploaderKey
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpStaleSealedMeta
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for media (en.memory.media, cr-0028). Implements the generated [MediaApi]; thin (ADR 0001) —
 * delegates to [MediaHandler]. The generic blob transfer a media upload runs on (start, windows, downloads) is core's
 * `BlobResource`; this resource completes an upload into media and serves what Memories keeps about it.
 * `@Authenticated`; the caller (from the `SecurityIdentity` principal) must hold a grant for the scope (a
 * non-member gets 404). Operator-blind: only ciphertext wraps + opaque metadata cross. Batch-shaped: 200 with
 * per-item results (partial success); only access/auth failures are top-level.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated media plumbing on the main upload and browse path (completion, " +
        "listing, covers; the presign minting it runs on is core's BlobResource, exempt for that reason). " +
        "Deliberately NOT key = USER yet: a per-user cap on that path has to be verified against real traffic " +
        "before it ships, or it throttles a legitimate bulk upload. Recorded as the open item in " +
        "cc.security.rate-limit (\u00a7 Unbounded by decision).",
)
class MediaResource(
    private val handler: MediaHandler,
    private val identity: SecurityIdentity,
) : MediaApi {

    override fun completeMedia(scopeId: UUID, mediaBatchRequest: MediaBatchRequest): Response {
        val items = mediaBatchRequest.items.map { item ->
            MediaUploadCompletion.item(
                mediaId = item.mediaId.toString(),
                nodeId = item.nodeId.toString(),
                originalUploadId = item.originalUploadId.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
            )
        }
        return when (val o = handler.complete(identity.principal.name, scopeId.toString(), items)) {
            is CompleteOutcome.Results -> Response.ok(
                MediaBatchResponse(
                    results = o.items.map {
                        MediaResultItem(
                            blobId = it.blobId?.let(UUID::fromString),
                            mediaId = it.ownerId?.let(UUID::fromString), // core's generic ownerId is the media id
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
                    sealedDropPrivateKey = o.sealedDropPrivateKey,
                    uploaderKeys = o.uploaderKeys.map { (id, wrap) -> UploaderKey(identityId = UUID.fromString(id), wrappedIdentityKey = wrap) },
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

    override fun updateMediaMeta(scopeId: UUID, mediaId: UUID, mediaMetaUpdate: MediaMetaUpdate): Response {
        // `@NotNull` refuses a missing version at the boundary; the type is nullable only so that absence is visible
        // (a primitive would default to 0 — see the spec's MediaMetaUpdate.metaVersion).
        val metaVersion = mediaMetaUpdate.metaVersion ?: return httpError(Failures.invalidRequest)
        return when (val o = handler.updateMeta(identity.principal.name, scopeId.toString(), mediaId.toString(), mediaMetaUpdate.wrappedMeta, metaVersion)) {
            is MetaOutcome.Found -> Response.ok(MediaMetaResponse(wrappedMeta = o.wrappedMeta)).build()
            is MetaOutcome.Stale -> httpStaleSealedMeta(o, o.wrappedMeta, o.metaVersion)
            is HttpFailure -> httpError(o)
        }
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

    override fun deleteCoverBlob(scopeId: UUID, nodeId: UUID, blobId: UUID): Response =
        when (val o = handler.deleteCover(identity.principal.name, scopeId.toString(), nodeId.toString(), blobId.toString())) {
            CoverDeleteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

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
        metaVersion = row.metaVersion,
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
