package me.raddatz.veyq.core.moderation.review

import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.presignGets
import me.raddatz.veyq.core.generated.api.CaseReviewApi
import me.raddatz.veyq.core.generated.model.CaseBlobDownload
import me.raddatz.veyq.core.generated.model.CaseBlobDownloads
import me.raddatz.veyq.core.generated.model.CaseBlobsRequest
import me.raddatz.veyq.core.generated.model.CaseHeldLink
import me.raddatz.veyq.core.generated.model.CaseReview
import me.raddatz.veyq.core.generated.model.CaseReviewReport
import me.raddatz.veyq.core.generated.model.PresignedUrl
import me.raddatz.veyq.core.generated.model.PublicShare
import me.raddatz.veyq.core.generated.model.ShareNode
import me.raddatz.veyq.core.generated.model.ShareRole
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.tenant.RequestTenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A moderator's review of a case (uc.moderation.review-a-case steps 3-5; decision 6 of cr-moderation-review-and-measures):
 * the held snapshot in the guest viewer's own shapes, read **from `veyq_hold_row` only** — never through the guest gate,
 * a link's budget or its password — so it answers after the share, the node or the space were deleted, and for content a
 * measure blocks (a block never hides evidence from the moderator). Behind `ModerationProofGate`: the `MODERATOR` role and
 * a key proof over this case, found by this interface's path under `/api/moderation`.
 *
 * Each fetch of the report shares is the case's **opened** event (ADR 0093 § 11): the server served what opens the
 * evidence; the opening itself happens in the browser, which the server cannot see.
 */
@Authenticated
@RateLimitExempt(
    reason = "Key-proved and MODERATOR-only: every call spends a single-use challenge of a bounded set of accounts the " +
        "Server Owner appointed, and serves only rows a hold already copied.",
)
class CaseReviewResource(
    private val snapshots: CaseSnapshots,
    private val log: ModerationLog,
    private val blobStore: BlobStore,
    private val tenant: RequestTenant,
    private val identity: SecurityIdentity,
) : CaseReviewApi {

    override fun getCaseReview(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response {
        val now = Instant.now()
        val review = QuarkusTransaction.requiringNew().call {
            val case = ModerationCaseEntity.findById(caseId.toString()) ?: return@call CaseFailures.caseNotFound
            val held = snapshots.rowsOf(case.id, now) ?: return@call CaseFailures.nothingHeldRead
            val reports = ReportShareEntity.list("caseId", case.id).mapNotNull { share ->
                ModerationReportEntity.findById(share.reportId)?.let { r ->
                    CaseReviewReport(
                        reportId = UUID.fromString(r.id), epoch = r.epoch, receivedAt = r.receivedAt.atOffset(ZoneOffset.UTC),
                        sealedReport = r.sealedReport, sealedShare = share.sealedKeys, sealedContact = r.sealedContact,
                    )
                }
            }.sortedBy { it.receivedAt }
            log.appendCase(ModerationEvent.OPENED, now, case.id, moderatorId = identity.principal.name)
            CaseReview(
                caseId = UUID.fromString(case.id),
                itemKind = CaseReview.ItemKind.valueOf(case.itemKind),
                itemId = case.itemId,
                reports = reports,
                share = shareOf(case, held),
                heldLinks = held.filter { it.table == SHARE }.map { s ->
                    CaseHeldLink(
                        shareId = s.sourceId,
                        role = ShareRole.decode(s["role"]),
                        nodeId = UUID.fromString(s["node_id"]),
                        sealedName = s["sealed_name"],
                        sealedNote = s["sealed_note"],
                    )
                },
            )
        }
        return when (review) {
            is HttpFailure -> httpError(review)
            else -> Response.ok(review).build()
        }
    }

    override fun presignCaseBlobs(
        caseId: UUID, caseBlobsRequest: CaseBlobsRequest, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response {
        val held = QuarkusTransaction.requiringNew().call {
            if (ModerationCaseEntity.findById(caseId.toString()) == null) return@call null
            snapshots.rowsOf(caseId.toString(), Instant.now()) ?: emptyList()
        } ?: return httpError(CaseFailures.caseNotFound)
        if (held.isEmpty()) return httpError(CaseFailures.nothingHeldRead)
        val blobs = held.filter { it.table == BLOB }.associateBy { it.sourceId }
        val domain = tenant.resolve()?.domain
        val out = caseBlobsRequest.blobIds.map { it.toString() }.distinct().mapNotNull { id ->
            val blob = blobs[id] ?: return@mapNotNull null
            // The pinned object (en.moderation.hold): a block never hides evidence from the moderator, so this caller of
            // presignGets is the one `PresignGetsHonourContentBlocksTest` exempts.
            val gets = blobStore.presignGets(blob["storage_key"]!!, blob["chunk_count"]!!.toInt(), domain)
                ?: return httpError(storeUnavailable)
            CaseBlobDownload(UUID.fromString(id), gets.map { PresignedUrl(index = it.index, url = it.url) })
        }
        return Response.ok(CaseBlobDownloads(out)).build()
    }

    /**
     * The snapshot as a `PublicShare` the guest viewer reads: a reported node's held subtree (its root carrying its own
     * held name and placement, which the report's `NODE_NAME` field key opens — a guest's root carries none), a reported
     * link's held name and note, or nothing for an item (its review is the lightbox). `wrap` is null and `role` `READ`:
     * the moderator holds the node key from the report share and writes nothing.
     */
    private fun shareOf(case: ModerationCaseEntity, held: List<HeldRow>): PublicShare = when (case.itemKind) {
        ReportItemKind.LINK.name -> {
            val link = held.firstOrNull { it.table == SHARE && it.sourceId == case.itemId }
            PublicShare(
                role = ShareRole.decode(link?.get("role") ?: "WRITE_ONLY"),
                nodes = emptyList(),
                scopeId = UUID.fromString(case.scopeId),
                nodeId = link?.get("node_id")?.let(UUID::fromString),
                sealedName = link?.get("sealed_name"),
                sealedNote = link?.get("sealed_note"),
            )
        }
        ReportItemKind.NODE.name -> {
            val identities = held.filter { it.table == IDENTITY }.associateBy { it.sourceId }
            val nodes = held.filter { it.table == NODE }
            val root = nodes.firstOrNull { it.sourceId == case.itemId }
            PublicShare(
                role = ShareRole.READ,
                nodes = nodes.map { n ->
                    val isRoot = n.sourceId == case.itemId
                    ShareNode(
                        id = UUID.fromString(n.sourceId),
                        kind = n["kind"]!!,
                        epoch = n["epoch"]!!.toInt(),
                        blockedByOperator = false,
                        parentId = if (isRoot) null else n["parent_id"]?.let(UUID::fromString),
                        orderIndex = n["order_index"]?.toInt(),
                        placementRevision = n["placement_revision"]?.toLong(),
                        encryptedName = n["encrypted_name"],
                        wrappedContentKey = if (isRoot) null else n["wrapped_content_key"],
                        wrappedMeta = n["wrapped_meta"],
                        sealedDropPrivateKey = n["sealed_drop_private_key"],
                        shareIdentityId = n["share_identity_id"]?.let(UUID::fromString),
                        wrappedIdentityKey = n["share_identity_id"]?.let { identities[it]?.get("wrapped_identity_key") },
                    )
                },
                scopeId = UUID.fromString(case.scopeId),
                nodeId = UUID.fromString(case.itemId),
                rootKind = root?.get("kind"),
            )
        }
        // A reported name: its one held node with the name and the placement its field key is bound to — nothing else of
        // the node (no key wrap, no meta, no drop key), since the report handed over that name's key alone.
        ReportItemKind.NAME.name -> {
            val node = held.firstOrNull { it.table == NODE && it.sourceId == case.itemId }
            PublicShare(
                role = ShareRole.READ,
                nodes = listOfNotNull(
                    node?.let { n ->
                        ShareNode(
                            id = UUID.fromString(n.sourceId),
                            kind = n["kind"]!!,
                            epoch = n["epoch"]!!.toInt(),
                            blockedByOperator = false,
                            placementRevision = n["placement_revision"]?.toLong(),
                            encryptedName = n["encrypted_name"],
                        )
                    },
                ),
                scopeId = UUID.fromString(case.scopeId),
                nodeId = UUID.fromString(case.itemId),
                rootKind = node?.get("kind"),
            )
        }
        else -> PublicShare(role = ShareRole.READ, nodes = emptyList(), scopeId = UUID.fromString(case.scopeId))
    }

    private companion object {
        const val NODE = "veyq_node"
        const val SHARE = "veyq_share"
        const val BLOB = "veyq_blob"
        const val IDENTITY = "veyq_share_identity"
        val storeUnavailable = httpFailure("store_unavailable", FailureStatus.SERVICE_UNAVAILABLE)
    }
}
