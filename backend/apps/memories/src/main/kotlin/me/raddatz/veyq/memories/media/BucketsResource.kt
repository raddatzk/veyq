package me.raddatz.veyq.memories.media

import me.raddatz.veyq.memories.generated.api.BucketsApi
import me.raddatz.veyq.memories.generated.model.BucketListResponse
import me.raddatz.veyq.memories.generated.model.BucketSummary
import me.raddatz.veyq.memories.generated.model.CreateBucketRequest
import me.raddatz.veyq.memories.generated.model.MoveMediaRequest
import me.raddatz.veyq.memories.generated.model.RenameBucketRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for uploader buckets (en.memory.buckets, ADR 0034, cr-0113). Implements the generated [BucketsApi];
 * thin (ADR 0001) — delegates to [MediaHandler]. `@Authenticated`; the caller (the `SecurityIdentity` principal)
 * must hold a grant for the scope (a non-member gets a 404). Member-gated: a member may curate any owner-root's
 * SUB-buckets (create/rename/delete) + move media within one root, but never a primary and never across owners
 * (cr-0123). Operator-blind — opaque owner_key + bucket structure + sealed names cross, never a cleartext name.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated + membership-gated bucket curation inside one owner-root. No external cost and no " +
        "fan-out.",
)
class BucketsResource(
    private val handler: MediaHandler,
    private val identity: SecurityIdentity,
) : BucketsApi {

    override fun listNodeBuckets(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.listBuckets(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            is ListBucketsOutcome.Ok -> Response.ok(
                BucketListResponse(buckets = o.buckets.map(::toSummary), sealedDropPrivateKey = o.sealedDropPrivateKey),
            ).build()
            is HttpFailure -> httpError(o)
        }

    override fun createBucket(scopeId: UUID, nodeId: UUID, createBucketRequest: CreateBucketRequest): Response =
        when (
            val o = handler.createBucket(
                identity.principal.name, scopeId.toString(), nodeId.toString(), createBucketRequest.bucketId.toString(),
                createBucketRequest.sealedName, createBucketRequest.parentBucketId?.toString(),
            )
        ) {
            is CreateBucketOutcome.Ok -> Response.ok(toSummary(o.bucket)).build()
            is HttpFailure -> httpError(o)
        }

    override fun renameBucket(scopeId: UUID, nodeId: UUID, bucketId: UUID, renameBucketRequest: RenameBucketRequest): Response =
        when (val o = handler.renameBucket(identity.principal.name, scopeId.toString(), nodeId.toString(), bucketId.toString(), renameBucketRequest.sealedName)) {
            is RenameBucketOutcome.Ok -> Response.ok(toSummary(o.bucket)).build()
            is HttpFailure -> httpError(o)
        }

    override fun deleteBucket(scopeId: UUID, nodeId: UUID, bucketId: UUID): Response =
        when (val o = handler.deleteBucket(identity.principal.name, scopeId.toString(), nodeId.toString(), bucketId.toString())) {
            DeleteBucketOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun moveBucketMedia(scopeId: UUID, nodeId: UUID, moveMediaRequest: MoveMediaRequest): Response =
        when (
            val o = handler.moveMedia(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                moveMediaRequest.mediaIds.map { it.toString() }, moveMediaRequest.targetBucketId?.toString(),
            )
        ) {
            MoveMediaOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    private fun toSummary(b: BucketRow) = BucketSummary(
        bucketId = UUID.fromString(b.bucketId),
        ownerKey = b.ownerKey,
        isPrimary = b.isPrimary,
        mediaCount = b.mediaCount,
        parentBucketId = b.parentBucketId?.let(UUID::fromString),
        sealedName = b.sealedName,
        shareId = b.shareId,
        wrappedIdentityKey = b.wrappedIdentityKey,
    )
}
