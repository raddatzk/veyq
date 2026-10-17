package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.StoreReconcileStore
import me.raddatz.veyq.core.blobstore.StoreReconcileView
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.generated.api.InstanceStoreReconcileApi
import me.raddatz.veyq.core.generated.model.StoreReclaimCounts
import me.raddatz.veyq.core.generated.model.StoreReconcile
import me.raddatz.veyq.core.generated.model.StoreReconcileCandidates
import me.raddatz.veyq.core.generated.model.StoreReconcileFailure
import me.raddatz.veyq.core.generated.model.StoreReconcilePass
import me.raddatz.veyq.core.generated.model.StoreReconcileProgress
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset

/**
 * The bucket reconcile on the admin channel (ADR 0062 § Consequences, uc.admin.configure-object-store A7): its read and
 * the Server Owner's two switches over its deletions — the exclusive-bucket confirmation, bound to the bucket the store
 * in use lists ([BlobStore.bucketDigest]), and the pause. Thin over [StoreReconcileStore]; every write answers the read.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only read of the reconcile's counts and two one-row switches on the admin channel: no store " +
        "call, no mail, no outbound request; the role gate already bounds the caller set to one account.",
)
class StoreReconcileResource(
    private val reconcile: StoreReconcileStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) : InstanceStoreReconcileApi {

    private val grace = config.storeReconcile().grace()

    override fun getStoreReconcile(): Response = ok()

    override fun confirmStoreExclusiveBucket(): Response {
        val digest = blobStore.bucketDigest ?: return httpError(NO_STORE)
        reconcile.confirmExclusive(digest, Instant.now())
        return ok()
    }

    override fun withdrawStoreExclusiveBucket(): Response {
        reconcile.withdrawExclusive()
        return ok()
    }

    override fun pauseStoreReconcile(): Response {
        reconcile.pause(true, Instant.now())
        return ok()
    }

    override fun resumeStoreReconcile(): Response {
        reconcile.pause(false, Instant.now())
        return ok()
    }

    private fun ok(): Response = Response.ok(reconcile.view(blobStore.bucketDigest, grace, Instant.now()).toWire()).build()

    private fun StoreReconcileView.toWire() = StoreReconcile(
        verdict = StoreReconcile.Verdict.valueOf(verdict.name),
        graceSeconds = grace.seconds,
        lastPass = lastPass?.let {
            StoreReconcilePass(
                startedAt = it.startedAt.utc(), completedAt = it.completedAt.utc(), objects = it.objects,
                orphanObjects = it.orphanObjects, orphanBytes = it.orphanBytes, notVeyqObjects = it.notVeyqObjects,
                enqueuedKeys = it.enqueuedKeys,
            )
        },
        passInProgress = passInProgress?.let { StoreReconcileProgress(startedAt = it.startedAt.utc(), objects = it.objects) },
        failure = failure?.let { StoreReconcileFailure(at = it.at.utc(), reason = StoreReconcileFailure.Reason.valueOf(it.reason.name)) },
        candidates = with(candidates) {
            StoreReconcileCandidates(
                storageKeys = keys, objects = objects, bytes = bytes, dueKeys = dueKeys,
                oldestFirstSeenAt = oldestFirstSeenAt?.utc(), newestFirstSeenAt = newestFirstSeenAt?.utc(),
                firstDueAt = firstDueAt?.utc(),
            )
        },
        exclusiveBucketConfirmedAt = exclusiveBucketConfirmedAt?.utc(),
        pausedAt = pausedAt?.utc(),
        clocksRestartedAt = clocksRestartedAt?.utc(),
        clocksRestartReason = clocksRestartReason?.let { StoreReconcile.ClocksRestartReason.valueOf(it.name) },
        reclaim = StoreReclaimCounts(pending = reclaimPending, abandoned = reclaimAbandoned),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val NO_STORE = httpFailure("no_store", FailureStatus.CONFLICT)
    }
}
