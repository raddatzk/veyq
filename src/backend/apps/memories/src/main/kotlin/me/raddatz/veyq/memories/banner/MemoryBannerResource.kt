package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.validation.validated
import me.raddatz.veyq.memories.generated.api.MemoryBannerApi
import me.raddatz.veyq.memories.generated.model.MemoryBannerConflict
import me.raddatz.veyq.memories.generated.model.RemoveMemoryBannerRequest
import me.raddatz.veyq.memories.generated.model.SetMemoryBannerRequest
import me.raddatz.veyq.core.blob.toSpec
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a Memory's banner (uc.memory.banner, ADR 0102). Implements the generated [MemoryBannerApi]; thin
 * (ADR 0001) - delegates to [MemoryBannerHandler]. `@Authenticated`: a member's bearer token, and the handler asks for
 * write access to the scope; no share route reaches it. Operator-blind: only upload ids, blob ids and the opaque
 * `wrappedMeta` cross - never the frame or the focal point. A shared row's source blob id names the medium whose stored
 * object the banner shares (ADR 0013 § 5), the accepted residual of ADR 0102 § What the server sees.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated + write-gated, as core's node cover: every call touches one node of a scope the caller may " +
        "write, consumes uploads the caller already opened (each budgeted at its start), and has no anonymous surface.",
)
class MemoryBannerResource(
    private val handler: MemoryBannerHandler,
    private val identity: SecurityIdentity,
) : MemoryBannerApi {

    override fun setMemoryBanner(scopeId: UUID, nodeId: UUID, setMemoryBannerRequest: SetMemoryBannerRequest): Response =
        respond(
            handler.set(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                setMemoryBannerRequest.uploadId?.toString(), setMemoryBannerRequest.shared?.toSpec(),
                setMemoryBannerRequest.posterUploadId?.toString(), setMemoryBannerRequest.posterShared?.toSpec(),
                setMemoryBannerRequest.expectedBlobId?.toString(), setMemoryBannerRequest.expectedPosterBlobId?.toString(),
                setMemoryBannerRequest.wrappedMeta, setMemoryBannerRequest.metaRevision.validated(),
                setMemoryBannerRequest.nodeEpoch.validated(),
            ),
        )

    override fun removeMemoryBanner(scopeId: UUID, nodeId: UUID, removeMemoryBannerRequest: RemoveMemoryBannerRequest): Response =
        respond(
            handler.remove(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                removeMemoryBannerRequest.blobId.toString(), removeMemoryBannerRequest.posterBlobId?.toString(),
                removeMemoryBannerRequest.wrappedMeta, removeMemoryBannerRequest.metaRevision.validated(),
                removeMemoryBannerRequest.nodeEpoch.validated(),
            ),
        )

    /** `204`, or the refusal; the two 409s that carry more than their code answer the contract's `MemoryBannerConflict`. */
    private fun respond(o: MemoryBannerOutcome): Response = when (o) {
        MemoryBannerOutcome.Done -> Response.noContent().build()
        is MemoryBannerOutcome.BannerStale ->
            Response.status(o.status.httpStatus).entity(
                MemoryBannerConflict(
                    code = o.code,
                    currentBlobId = o.currentBlobId?.let(UUID::fromString),
                    currentPosterBlobId = o.currentPosterBlobId?.let(UUID::fromString),
                ),
            ).build()
        is MemoryBannerOutcome.StaleMeta ->
            Response.status(o.status.httpStatus)
                .entity(MemoryBannerConflict(code = o.code, wrappedMeta = o.wrappedMeta, metaRevision = o.metaRevision)).build()
        is HttpFailure -> httpError(o)
    }
}
