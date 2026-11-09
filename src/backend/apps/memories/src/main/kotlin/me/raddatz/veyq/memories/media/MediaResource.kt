package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.toSpec
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.memories.generated.api.MediaApi
import me.raddatz.veyq.memories.generated.model.MediaBatchRequest
import me.raddatz.veyq.memories.generated.model.MediaBatchResponse
import me.raddatz.veyq.memories.generated.model.MediaDerivativeKind
import me.raddatz.veyq.memories.generated.model.MediaDerivativeSummary
import me.raddatz.veyq.memories.generated.model.MediaExifResponse
import me.raddatz.veyq.memories.generated.model.MediaListPage
import me.raddatz.veyq.memories.generated.model.MediaMetaResponse
import me.raddatz.veyq.memories.generated.model.MediaMetaUpdate
import me.raddatz.veyq.memories.generated.model.MediaRekeyPage
import me.raddatz.veyq.memories.generated.model.MediaRekeyStage
import me.raddatz.veyq.memories.generated.model.RekeyBucketName
import me.raddatz.veyq.memories.generated.model.RekeyMedium
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.node.rekey.RekeyOutcome
import me.raddatz.veyq.memories.generated.model.MediaResultItem
import me.raddatz.veyq.memories.generated.model.MediaSummary
import me.raddatz.veyq.memories.generated.model.ScopeMediaNode
import me.raddatz.veyq.memories.generated.model.ScopeMediaPage
import me.raddatz.veyq.memories.generated.model.ScopeMedium
import me.raddatz.veyq.memories.generated.model.UploaderKey
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsETag
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpStaleSealedMeta
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.moderation.block.ModerationBlocks

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
    private val rekey: MediaRekeyHandler,
    private val scopeMedia: ScopeMediaHandler,
    private val blocks: ModerationBlocks,
    private val identity: SecurityIdentity,
) : MediaApi {

    override fun completeMedia(scopeId: UUID, mediaBatchRequest: MediaBatchRequest): Response {
        val items = mediaBatchRequest.items.map { item ->
            MediaUploadCompletion.item(
                mediaId = item.mediaId.toString(),
                nodeId = item.nodeId.toString(),
                originalUploadId = item.originalUploadId?.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
                nodeEpoch = item.nodeEpoch.validated(),
                originalShared = item.originalShared?.toSpec(),
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

    override fun listNodeMedia(scopeId: UUID, nodeId: UUID, ifNoneMatch: String?, since: String?, sinceDelete: Int?, limit: Int?, state: String?): Response =
        when (
            val o = handler.listMedia(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                ItemsETag.parse(ifNoneMatch), ItemsCursor.parse(since), sinceDelete ?: 0, ItemsPage.limitOf(limit),
                trashed = state == "trashed",
            )
        ) {
            is ListMediaOutcome.Page -> Response.ok(
                MediaListPage(
                    media = blocks.contentView(scopeId.toString()).let { view ->
                        o.rows.map { toSummary(it, view.itemBlocked(it.mediaId, nodeId.toString())) }
                    },
                    mediaRevision = o.mediaRevision,
                    deleteRevision = o.deleteRevision,
                    mediaIds = o.mediaIds?.map(UUID::fromString),
                    more = o.more,
                    cursor = o.cursor,
                    sealedDropPrivateKey = o.sealedDropPrivateKey,
                    uploaderKeys = o.uploaderKeys.map { (id, wrap) -> UploaderKey(identityId = UUID.fromString(id), wrappedIdentityKey = wrap) },
                ),
            ).header("ETag", ItemsETag.of(o.mediaRevision)).build() // the ETag doubles as the client's next If-None-Match
            ListMediaOutcome.NotModified -> Response.notModified().build() // 304 — no body, no media query (idle fast path)
            is HttpFailure -> httpError(o)
        }

    override fun listScopeMedia(scopeId: UUID, after: UUID?, limit: Int?, ifNoneMatch: String?): Response =
        when (val o = scopeMedia.list(identity.principal.name, scopeId.toString(), after?.toString(), ItemsPage.limitOf(limit), ifNoneMatch)) {
            is ListScopeMediaOutcome.Page -> Response.ok(
                ScopeMediaPage(
                    media = o.rows.map { toScopeMedium(it, it.row.mediaId in o.blocked) },
                    more = o.more,
                    // Per node of the page with a drop key: the key and the WRITE_ONLY identities among that node's rows.
                    nodes = o.dropKeys.map { (nodeId, drop) ->
                        val identities = o.rows.filter { it.nodeId == nodeId }.mapNotNullTo(LinkedHashSet()) { ScopeMediaHandler.identityOf(it) }
                        ScopeMediaNode(
                            nodeId = UUID.fromString(nodeId),
                            sealedDropPrivateKey = drop,
                            uploaderKeys = identities.mapNotNull { id ->
                                o.uploaderKeys[id]?.let { UploaderKey(identityId = UUID.fromString(id), wrappedIdentityKey = it) }
                            },
                        )
                    },
                ),
            ).apply { if (o.etag != null) header("ETag", o.etag) }.build()
            // Nothing the listing depends on moved since the client's tag: no body, no media read.
            is ListScopeMediaOutcome.NotModified -> Response.notModified().header("ETag", o.etag).build()
            is HttpFailure -> httpError(o)
        }

    override fun listMediaRekey(scopeId: UUID, nodeId: UUID, since: String?, limit: Int?): Response =
        when (
            val o = rekey.list(identity.principal.name, scopeId.toString(), nodeId.toString(), ItemsCursor.parse(since), ItemsPage.limitOf(limit))
        ) {
            is MediaRekeyListOutcome.Page -> Response.ok(
                MediaRekeyPage(
                    media = o.media.items.map { m ->
                        RekeyMedium(
                            mediaId = UUID.fromString(m.mediaId),
                            metaRevision = m.metaRevision,
                            wrappedMeta = m.wrappedMeta,
                            wrappedExif = m.wrappedExif,
                        )
                    },
                    buckets = o.buckets.map {
                        RekeyBucketName(
                            bucketId = UUID.fromString(it.bucketId),
                            sealedName = it.sealedName,
                            shareId = it.shareId,
                            identityId = it.identityId?.let(UUID::fromString),
                        )
                    },
                    more = o.media.more,
                    cursor = o.media.cursor?.wire(),
                ),
            ).build()
            is MediaRekeyListOutcome.Refused -> httpError(o.failure)
        }

    override fun stageMediaRekey(scopeId: UUID, nodeId: UUID, rekeyId: UUID, mediaRekeyStage: MediaRekeyStage): Response {
        val values = buildMap {
            mediaRekeyStage.media.orEmpty().forEach { m ->
                val id = m.mediaId.toString()
                val revision = m.metaRevision.validated()
                m.wrappedMeta?.let { put(Key(id, MediaRekeyRows.META), Value(it, revision)) }
                m.wrappedExif?.let { put(Key(id, MediaRekeyRows.EXIF), Value(it, revision)) }
            }
            mediaRekeyStage.buckets.orEmpty().forEach { put(Key(it.bucketId.toString(), MediaRekeyRows.BUCKET_NAME), Value(it.sealedName, null)) }
        }
        return when (
            val o = rekey.stage(
                identity.principal.name, scopeId.toString(), nodeId.toString(), rekeyId.toString(),
                mediaRekeyStage.fromEpoch.validated(), values,
            )
        ) {
            RekeyOutcome.Staged -> Response.noContent().build()
            is HttpFailure -> httpError(o)
            else -> error("not a staging answer: $o")
        }
    }

    override fun getMediaExif(scopeId: UUID, mediaId: UUID): Response =
        when (val o = handler.mediaExif(identity.principal.name, scopeId.toString(), mediaId.toString())) {
            is MediaExifOutcome.Found -> Response.ok(MediaExifResponse(wrappedExif = o.wrappedExif)).build()
            is HttpFailure -> httpError(o)
        }

    override fun updateMediaMeta(scopeId: UUID, mediaId: UUID, mediaMetaUpdate: MediaMetaUpdate): Response {
        // `@NotNull` refuses a missing version at the boundary; the type is nullable only so that absence is visible
        // (a primitive would default to 0 — see the spec's MediaMetaUpdate.metaRevision).
        val metaRevision = mediaMetaUpdate.metaRevision ?: return httpError(Failures.invalidRequest)
        val nodeEpoch = mediaMetaUpdate.nodeEpoch.validated()
        return when (val o = handler.updateMeta(identity.principal.name, scopeId.toString(), mediaId.toString(), mediaMetaUpdate.wrappedMeta, metaRevision, nodeEpoch)) {
            is MetaOutcome.Found -> Response.ok(MediaMetaResponse(wrappedMeta = o.wrappedMeta)).build()
            is MetaOutcome.Stale -> httpStaleSealedMeta(o, o.wrappedMeta, o.metaRevision)
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

    private fun toSummary(row: MediaRow, blocked: Boolean) = MediaSummary(
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
        metaRevision = row.metaRevision,
        wrappedBlobMeta = row.wrappedBlobMeta,
        // Kept and marked, not left out (uc.moderation.take-a-measure): the tile shows the placeholder.
        blockedByOperator = blocked,
    )

    private fun toScopeMedium(r: ShareMediaRow, blocked: Boolean) = ScopeMedium(
        nodeId = UUID.fromString(r.nodeId),
        mediaId = UUID.fromString(r.row.mediaId),
        blobId = UUID.fromString(r.row.blobId),
        chunkCount = r.row.chunkCount,
        byteSize = r.row.byteSize,
        wrappedBlobKey = r.row.wrappedBlobKey,
        wrappedBlobMeta = r.row.wrappedBlobMeta,
        wrappedMeta = r.row.wrappedMeta,
        metaRevision = r.row.metaRevision,
        ownerKey = r.row.ownerKey,
        // Kept and marked, as listNodeMedia marks it (uc.moderation.take-a-measure); the search makes nothing of it.
        blockedByOperator = blocked,
    )

    private fun toDerivativeSummary(d: DerivativeRow) = MediaDerivativeSummary(
        kind = MediaDerivativeKind.decode(d.kind),
        blobId = UUID.fromString(d.blobId),
        chunkCount = d.chunkCount,
        byteSize = d.byteSize,
        wrappedBlobKey = d.wrappedBlobKey,
        manifest = d.manifest,
        wrappedBlobMeta = d.wrappedBlobMeta,
    )
}
