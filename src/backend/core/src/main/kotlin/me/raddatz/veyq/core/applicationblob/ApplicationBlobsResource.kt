package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.generated.api.ApplicationBlobsApi
import me.raddatz.veyq.core.generated.api.CaseApplicationBlobsApi
import me.raddatz.veyq.core.generated.api.ShareApplicationBlobsApi
import me.raddatz.veyq.core.generated.model.ApplicationBlob
import me.raddatz.veyq.core.generated.model.ApplicationBlobCompleteRequest
import me.raddatz.veyq.core.generated.model.ApplicationBlobPage
import me.raddatz.veyq.core.generated.model.CaseApplicationBlob
import me.raddatz.veyq.core.generated.model.CaseApplicationBlobListing
import me.raddatz.veyq.core.generated.model.ShareApplicationBlob
import me.raddatz.veyq.core.generated.model.ShareApplicationBlobCompleteRequest
import me.raddatz.veyq.core.generated.model.ShareApplicationBlobPage
import me.raddatz.veyq.core.http.RequestClient
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.validation.validated
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for a member's application blobs (tag `application-blobs` of `blob.openapi.yaml`, ADR 0112). Implements
 * the generated [ApplicationBlobsApi]; thin (ADR 0001) — delegates to [ApplicationBlobsHandler]. `@Authenticated`; the
 * caller must hold a grant for the scope (a non-member gets the gate's 404). Operator-blind: only ciphertext, ids, sizes,
 * marks, writers and times cross.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated, as a Memory's media plumbing: an application's blobs are read on its main " +
        "path and completed after core's rate-free upload start, and a per-user cap on it has to be verified against real " +
        "traffic before it ships. Recorded with the media's open item in cc.security.rate-limit (§ Unbounded by decision).",
)
class ApplicationBlobsResource(private val handler: ApplicationBlobsHandler, private val identity: SecurityIdentity) : ApplicationBlobsApi {

    override fun listApplicationBlobs(scopeId: UUID, nodeId: UUID, after: UUID?, limit: Int?, ifNoneMatch: String?): Response =
        when (val o = handler.list(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            is ListBlobsOutcome.Listed -> ApplicationBlobWire.respond(o.blobs.map(ApplicationBlobWire::member), { it.id }, after, limit, ifNoneMatch) { page, more, cursor ->
                ApplicationBlobPage(blobs = page, more = more, cursor = cursor)
            }
            is HttpFailure -> httpError(o)
        }

    override fun completeApplicationBlob(scopeId: UUID, nodeId: UUID, applicationBlobCompleteRequest: ApplicationBlobCompleteRequest): Response {
        val r = applicationBlobCompleteRequest
        return when (
            val o = handler.complete(
                identity.principal.name, scopeId.toString(), nodeId.toString(), r.id.toString(), r.ownerId.toString(), r.role,
                r.uploadId.toString(), r.nodeEpoch.validated(),
            )
        ) {
            is CompleteBlobOutcome.Ok -> Response.ok(ApplicationBlobWire.member(o.blob)).build()
            is HttpFailure -> httpError(o)
        }
    }
}

/**
 * HTTP adapter for a guest's application blobs (tag `share-application-blobs`, unauthenticated): core's guest gate, then
 * [ApplicationBlobsHandler]. The guest's shapes carry no writer (inbox #190).
 */
class ShareApplicationBlobsResource(
    private val handler: ApplicationBlobsHandler,
    /** The client a guest's write records (the guest-write record, ADR 0093 § 10): resolved once, handed down. */
    private val client: RequestClient,
) : ShareApplicationBlobsApi {

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of a node's application blob references under a share — core's half of an application's guest " +
            "read, as listShareFiles is Depot's. Volume is unbounded BY DECISION, not by omission, recorded in " +
            "cc.security.rate-limit (§ Unbounded by decision) beside listSharePotRevisions.",
    )
    override fun listShareApplicationBlobs(
        shareId: String, nodeId: UUID, xSharePassword: String?, after: UUID?, limit: Int?, ifNoneMatch: String?,
    ): Response = when (val o = handler.listShared(shareId, xSharePassword, nodeId.toString())) {
        is ListBlobsOutcome.Listed -> ApplicationBlobWire.respond(o.blobs.map(ApplicationBlobWire::guest), { it.id }, after, limit, ifNoneMatch) { page, more, cursor ->
            ShareApplicationBlobPage(blobs = page, more = more, cursor = cursor)
        }
        is HttpFailure -> httpError(o)
    }

    @PermitAll
    // A guest's blob creates a service row and a blob row, so it rides the per-share upload budget, as completeShareFile does.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun completeShareApplicationBlob(
        shareId: String, nodeId: UUID, shareApplicationBlobCompleteRequest: ShareApplicationBlobCompleteRequest, xSharePassword: String?,
    ): Response {
        val r = shareApplicationBlobCompleteRequest
        return when (
            val o = handler.completeShared(
                shareId, xSharePassword, r.contributorToken, nodeId.toString(), r.id.toString(), r.ownerId.toString(), r.role,
                r.uploadId.toString(), r.nodeEpoch.validated(), client.seen(),
            )
        ) {
            is CompleteBlobOutcome.Ok -> Response.ok(ApplicationBlobWire.guest(o.blob)).build()
            is HttpFailure -> httpError(o)
        }
    }
}

/**
 * A moderator's review of a case's application blobs (uc.moderation.review-a-case, ADR 0112 decision 3): every service
 * row (with its held blob row) the case's hold copied, read from the snapshot only — so it answers after the blobs, their
 * node or the space were deleted (what a hold copied stays until the hold ends, ADR 0093 § 4). The guest's shape plus the
 * node, never a writer. Behind core's `ModerationProofGate`, found by this resource's path under `/api/moderation`.
 */
@Authenticated
@RateLimitExempt(
    reason = "Key-proved and MODERATOR-only: every call spends a single-use challenge of a bounded set of accounts the " +
        "Server Owner appointed, and serves only rows a hold already copied.",
)
class CaseApplicationBlobsResource(private val snapshots: CaseSnapshots) : CaseApplicationBlobsApi {

    override fun listCaseApplicationBlobs(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response {
        val held = QuarkusTransaction.requiringNew().call {
            if (ModerationCaseEntity.findById(caseId.toString()) == null) return@call null
            snapshots.rowsOf(caseId.toString(), Instant.now()) ?: emptyList()
        } ?: return httpError(CaseFailures.caseNotFound)
        if (held.isEmpty()) return httpError(CaseFailures.nothingHeldRead)
        // A held row with its held blob row (core copies the row of every blob a contributor names, and pins it).
        val blobRows = held.filter { it.table == "veyq_blob" }.associateBy { it.sourceId }
        val blobs = held.filter { it.table == ApplicationBlobs.TABLE }.sortedWith(compareBy({ it["node_id"] }, { it.sourceId }))
            .mapNotNull { r -> blobRows[r["blob_id"]]?.let { toWire(r, it) } }
        return Response.ok(CaseApplicationBlobListing(blobs = blobs)).build()
    }

    private fun toWire(row: HeldRow, blob: HeldRow) = CaseApplicationBlob(
        nodeId = UUID.fromString(row["node_id"]),
        id = UUID.fromString(row.sourceId),
        ownerId = UUID.fromString(row["owner_id"]),
        role = row["role"]!!,
        blobId = UUID.fromString(blob.sourceId),
        chunkCount = blob["chunk_count"]!!.toInt(),
        byteSize = blob["byte_size"]!!.toLong(),
        wrappedBlobKey = blob["wrapped_blob_key"]!!,
        wrappedBlobMeta = blob["wrapped_meta"],
        createdAt = Instant.parse(row["created_at"]!!).atOffset(ZoneOffset.UTC),
        keptAt = row["kept_at"]?.let { Instant.parse(it).atOffset(ZoneOffset.UTC) },
        releasedAt = row["released_at"]?.let { Instant.parse(it).atOffset(ZoneOffset.UTC) },
    )
}

/**
 * The service's wire, the member's and the guest's: the two shapes — the guest's has no writer field at all (inbox
 * #190) — and the one paged, tagged answer both reads give.
 */
object ApplicationBlobWire {

    /** The member's shape. */
    fun member(row: ApplicationBlobRow) = ApplicationBlob(
        id = UUID.fromString(row.row.id),
        ownerId = UUID.fromString(row.row.ownerId),
        role = row.row.role,
        blobId = UUID.fromString(row.blob.id),
        chunkCount = row.blob.chunkCount,
        byteSize = row.blob.byteSize,
        manifest = row.manifest,
        wrappedBlobKey = row.blob.wrappedBlobKey,
        wrappedBlobMeta = row.blob.wrappedBlobMeta,
        writer = row.row.writer.format(),
        createdAt = row.row.createdAt.atOffset(ZoneOffset.UTC),
        keptAt = row.row.keptAt?.atOffset(ZoneOffset.UTC),
        releasedAt = row.row.releasedAt?.atOffset(ZoneOffset.UTC),
        purgeAfter = row.purgeAfter?.atOffset(ZoneOffset.UTC),
        blockedByOperator = row.blocked,
    )

    /** The guest's shape — no writer (inbox #190). */
    fun guest(row: ApplicationBlobRow) = ShareApplicationBlob(
        id = UUID.fromString(row.row.id),
        ownerId = UUID.fromString(row.row.ownerId),
        role = row.row.role,
        blobId = UUID.fromString(row.blob.id),
        chunkCount = row.blob.chunkCount,
        byteSize = row.blob.byteSize,
        manifest = row.manifest,
        wrappedBlobKey = row.blob.wrappedBlobKey,
        wrappedBlobMeta = row.blob.wrappedBlobMeta,
        createdAt = row.row.createdAt.atOffset(ZoneOffset.UTC),
        keptAt = row.row.keptAt?.atOffset(ZoneOffset.UTC),
        releasedAt = row.row.releasedAt?.atOffset(ZoneOffset.UTC),
        purgeAfter = row.purgeAfter?.atOffset(ZoneOffset.UTC),
        blockedByOperator = row.blocked,
    )

    /**
     * One page of [all] (already in id order) past [after], at most [limit], built by [page]; a read without [after]
     * carries an `ETag` that is a digest of everything the read serves, every page of it, and answers `304` when
     * [ifNoneMatch] equals it (ADR 0081 decision 4: nothing outside the response, never the node's item revision). Each
     * blob enters the digest as the generated model names it (its `toString` lists every field, so a field added to the
     * wire is in the tag without anyone remembering it) — truncated SHA-256, 128 bits, quoted, `ShareMediaTag`'s form.
     */
    fun <T : Any> respond(
        all: List<T>, idOf: (T) -> UUID, after: UUID?, limit: Int?, ifNoneMatch: String?, page: (List<T>, Boolean, String?) -> Any,
    ): Response {
        val from = after?.let { cut -> all.indexOfFirst { idOf(it).toString() > cut.toString() }.let { if (it < 0) all.size else it } } ?: 0
        val shown = all.drop(from).take(ItemsPage.limitOf(limit))
        val body = page(shown, from + shown.size < all.size, shown.lastOrNull()?.let { idOf(it).toString() })
        if (after != null) return Response.ok(body).build() // a later page is never conditional and carries no tag
        val sha = MessageDigest.getInstance("SHA-256")
        for (r in all) sha.update((r.toString() + "\n").toByteArray(Charsets.UTF_8))
        val tag = "\"" + sha.digest().take(16).joinToString("") { "%02x".format(it) } + "\""
        return if (ifNoneMatch?.trim() == tag) Response.notModified().build() else Response.ok(body).header("ETag", tag).build()
    }
}
