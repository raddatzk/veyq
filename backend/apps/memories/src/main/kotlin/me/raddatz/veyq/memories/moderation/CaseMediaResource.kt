package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.hold.HeldUploaders
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.memories.generated.api.CaseMediaApi
import me.raddatz.veyq.memories.generated.model.ShareMedia
import me.raddatz.veyq.memories.generated.model.ShareMediaDerivative
import me.raddatz.veyq.memories.generated.model.CaseMediaListing
import me.raddatz.veyq.memories.generated.model.UploaderKey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * A moderator's review of a case's media (uc.moderation.review-a-case step 5): the Memories rows the case's hold copied
 * (`MediaHoldSnapshot`) in the guest listing's own shape — the media live when the hold was taken, with their derivatives
 * and blob refs, and the WRITE_ONLY identities whose wrapped keys open a guest's upload — read from the snapshot only.
 * Behind core's `ModerationProofGate` (the `MODERATOR` role and a key proof over this case), which finds this resource by
 * its path under `/api/moderation`; nothing is marked blocked, a block never hides evidence from the moderator.
 */
@Authenticated
@RateLimitExempt(
    reason = "Key-proved and MODERATOR-only: every call spends a single-use challenge of a bounded set of accounts the " +
        "Server Owner appointed, and serves only rows a hold already copied.",
)
class CaseMediaResource(
    private val snapshots: CaseSnapshots,
    private val blobs: BlobUploadStore,
) : CaseMediaApi {

    override fun listCaseMedia(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response {
        val held = QuarkusTransaction.requiringNew().call {
            if (ModerationCaseEntity.findById(caseId.toString()) == null) return@call null
            snapshots.rowsOf(caseId.toString(), Instant.now()) ?: emptyList()
        } ?: return httpError(CaseFailures.caseNotFound)
        if (held.isEmpty()) return httpError(CaseFailures.nothingHeldRead)
        return Response.ok(listingOf(held)).build()
    }

    private fun listingOf(held: List<HeldRow>): CaseMediaListing {
        val blobRows = held.filter { it.table == "veyq_blob" }.associateBy { it.sourceId }
        val derivatives = held.filter { it.table == "veyq_media_derivative" }.groupBy { it["media_id"] }
        val identities = held.filter { it.table == "veyq_share_identity" }.associateBy { it.sourceId }
        // What the reporter saw: the media live when the hold was taken (a trashed one is in no gallery and no listing).
        val media = held.filter { it.table == "veyq_media" && it["deleted_at"] == null }.mapNotNull { m ->
            val blob = blobRows[m["blob_id"]] ?: return@mapNotNull null
            ShareMedia(
                blockedByOperator = false,
                nodeId = UUID.fromString(m["node_id"]),
                mediaId = UUID.fromString(m.sourceId),
                blobId = UUID.fromString(blob.sourceId),
                chunkCount = blob["chunk_count"]!!.toInt(),
                byteSize = blob["byte_size"]!!.toLong(),
                wrappedBlobKey = blob["wrapped_blob_key"]!!,
                manifest = blobs.decodeManifest(blob["manifest"]),
                wrappedBlobMeta = blob["wrapped_meta"],
                derivatives = derivatives[m.sourceId].orEmpty().mapNotNull { d ->
                    val db = blobRows[d["blob_id"]] ?: return@mapNotNull null
                    ShareMediaDerivative(
                        kind = ShareMediaDerivative.Kind.valueOf(d["kind"]!!),
                        blobId = UUID.fromString(db.sourceId),
                        chunkCount = db["chunk_count"]!!.toInt(),
                        byteSize = db["byte_size"]!!.toLong(),
                        wrappedBlobKey = db["wrapped_blob_key"]!!,
                        manifest = blobs.decodeManifest(db["manifest"]),
                        wrappedBlobMeta = db["wrapped_meta"],
                    )
                },
                wrappedMeta = m["wrapped_meta"],
                identityId = m["share_identity_id"]?.takeIf { identities[it]?.get("wrapped_identity_key") != null }?.let(UUID::fromString),
            )
        }
        val keys = media.mapNotNull { it.identityId?.toString() }.distinct().mapNotNull { id ->
            identities[id]?.get("wrapped_identity_key")?.let { UploaderKey(identityId = UUID.fromString(id), wrappedIdentityKey = it) }
        }
        return CaseMediaListing(media = media, identities = keys)
    }
}

/** Memories' half of [HeldUploaders]: a held medium's member uploader (`created_by` is its owner key, ADR 0034). */
@ApplicationScoped
class MediaHeldUploaders : HeldUploaders {
    override fun membersAmong(rows: List<HeldRow>): Set<String> =
        rows.filter { it.table == "veyq_media" }.mapNotNullTo(LinkedHashSet()) { m ->
            m["created_by"]?.let { runCatching { PrincipalKey.parse(it) }.getOrNull() as? PrincipalKey.User }?.userId
        }
}
