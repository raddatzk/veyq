package com.veyq.app.features.tasks

import com.veyq.app.generated.api.TasksApi
import com.veyq.app.generated.model.ClaimRequest
import com.veyq.app.generated.model.ClearDeclinesRequest
import com.veyq.app.generated.model.DeclinesCleared
import com.veyq.app.generated.model.ClaimedJob
import com.veyq.app.generated.model.CompleteRequest
import com.veyq.app.generated.model.DeclineRequest
import com.veyq.app.generated.model.EnqueueRequest
import com.veyq.app.generated.model.FailRequest
import com.veyq.app.generated.model.GrantDeliveryTarget
import com.veyq.app.generated.model.JobCreated
import com.veyq.app.generated.model.JobStatus
import com.veyq.app.generated.model.LeaseState
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the operator-blind background-task pull queue (ADR 0021, cr-0076). Implements the generated
 * [TasksApi]; thin (ADR 0001) — delegates to [TasksHandler]. Central auth (`@Authenticated`): a missing/invalid
 * token is a generic 401 before this runs; the caller's worker identity is the `SecurityIdentity` principal.
 * The scope-access gate (uc.spaces.scope-access) is the handler's first guard on every job; the lease and the
 * grant checks behind it live in the store.
 */
@Authenticated
@RateLimitExempt(
    reason = "The operator-blind pull queue (ADR 0021), where high-frequency polling IS the design: clients " +
        "short-poll claim and heartbeat by construction, so a per-user request cap would throttle the " +
        "mechanism rather than an abuse of it. What a claim can actually consume is bounded by the lease " +
        "and attempt machinery, not by request count.",
)
class TasksResource(
    private val handler: TasksHandler,
    private val identity: SecurityIdentity,
) : TasksApi {

    private val me: String get() = identity.principal.name

    override fun claimTask(claimRequest: ClaimRequest): Response =
        when (val job = handler.claim(me, claimRequest.capabilityTags, claimRequest.scopeId?.toString())) {
            null -> Response.noContent().build()
            else -> Response.ok(job.toDto()).build()
        }

    override fun enqueueTask(enqueueRequest: EnqueueRequest): Response {
        // Structural tags are server-originated only — reject them at the front door with a clear 400 (the store
        // enforces this too as defence in depth). A client may only enqueue content-processing jobs.
        if (enqueueRequest.capabilityTag == CapabilityTags.GRANT_DELIVERY) {
            return httpError(Failures.invalidRequest)
        }
        return when (val outcome = handler.enqueue(
            me, enqueueRequest.scopeId.toString(), enqueueRequest.capabilityTag,
            enqueueRequest.resourceRef, enqueueRequest.sealedBody,
        )) {
            is EnqueueOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(JobCreated(jobId = UUID.fromString(outcome.jobId))).build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun heartbeatTask(jobId: UUID): Response =
        when (val r = handler.heartbeat(me, jobId.toString())) {
            is HeartbeatResult.Extended ->
                Response.ok(LeaseState(leaseExpiresAt = r.leaseExpiresAt.atOffset(ZoneOffset.UTC))).build()
            is HttpFailure -> httpError(r)
        }

    override fun completeTask(jobId: UUID, completeRequest: CompleteRequest?): Response =
        when (val r = handler.complete(
            me, jobId.toString(), completeRequest?.sealedResult, completeRequest?.epoch, completeRequest?.grantSignature,
        )) {
            CompleteResult.Done -> Response.noContent().build()
            is HttpFailure -> httpError(r)
        }

    override fun failTask(jobId: UUID, failRequest: FailRequest?): Response =
        when (val r = handler.fail(me, jobId.toString())) {
            is FailResult.Recorded -> Response.ok(JobStatus(status = JobStatus.Status.valueOf(r.status.lowercase()), attempts = r.attempts)).build()
            is HttpFailure -> httpError(r)
        }

    override fun declineTask(jobId: UUID, declineRequest: DeclineRequest?): Response =
        when (val r = handler.decline(me, jobId.toString(), declineRequest?.reason)) {
            is DeclineResult.Released -> Response.ok(JobStatus(status = JobStatus.Status.valueOf(r.status.lowercase()), attempts = r.attempts)).build()
            is HttpFailure -> httpError(r)
        }

    override fun clearDeclinesForAcceptedKey(clearDeclinesRequest: ClearDeclinesRequest): Response =
        Response.ok(
            DeclinesCleared(cleared = handler.clearDeclines(me, clearDeclinesRequest.targetUserId.toString())),
        ).build()

    private fun ClaimedJobView.toDto() = ClaimedJob(
        jobId = UUID.fromString(jobId),
        scopeId = UUID.fromString(scopeId),
        capabilityTag = capabilityTag,
        attempts = attempts,
        resourceRef = resourceRef,
        sealedBody = sealedBody,
        leaseExpiresAt = leaseExpiresAt?.atOffset(ZoneOffset.UTC),
        grantDelivery = grantDelivery?.let {
            GrantDeliveryTarget(
                targetUserId = UUID.fromString(it.targetUserId),
                targetMembershipId = UUID.fromString(it.targetMembershipId),
                identityPublicKey = it.identityPublicKey,
                admissionId = it.admission?.let { a -> UUID.fromString(a.id) },
                admissionEpoch = it.admission?.epoch,
                admissionTag = it.admission?.tag,
            )
        },
    )
}
