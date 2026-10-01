package com.veyq.app.features.sharing

import com.veyq.app.features.media.DerivativeDeclare
import com.veyq.app.features.media.UploadDeclare
import com.veyq.app.features.media.WindowRequest
import com.veyq.app.generated.api.SharingApi
import com.veyq.app.generated.model.CreateShareIdentityRequest
import com.veyq.app.generated.model.CreateShareRequest
import com.veyq.app.generated.model.LocalizedMarkdown
import com.veyq.app.generated.model.PresignedUrl
import com.veyq.app.generated.model.PublicShare
import com.veyq.app.generated.model.ReissueShareRequest
import com.veyq.app.generated.model.ShareBlobDownload
import com.veyq.app.generated.model.ShareBlobUploadResultItem
import com.veyq.app.generated.model.ShareBlobUploadsRequest
import com.veyq.app.generated.model.ShareBlobUploadWindowResultItem
import com.veyq.app.generated.model.ShareBlobUploadWindowsRequest
import com.veyq.app.generated.model.ShareBlobUploadWindowsResponse
import com.veyq.app.generated.model.ShareBlobUploadsResponse
import com.veyq.app.generated.model.ShareCreated
import com.veyq.app.generated.model.ShareIdentity
import com.veyq.app.generated.model.ShareIdentityCreated
import com.veyq.app.generated.model.ShareMedia
import com.veyq.app.generated.model.ShareMediaBatchRequest
import com.veyq.app.generated.model.ShareMediaBatchResponse
import com.veyq.app.generated.model.ShareMediaDerivative
import com.veyq.app.generated.model.ShareMediaResultItem
import com.veyq.app.generated.model.ShareNode
import com.veyq.app.generated.model.ShareRole
import com.veyq.app.generated.model.ShareSummary
import com.veyq.app.generated.model.ShareTerms
import com.veyq.app.generated.model.UpdateShareRequest
import com.veyq.app.generated.model.NodeKind
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import com.veyq.app.shared.tenant.RequestTenant
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimit
import com.veyq.app.shared.ratelimit.RateLimitExempt
import com.veyq.app.shared.ratelimit.RateLimitKey

/**
 * HTTP adapter for public viewer shares (uc.sharing.share-public-viewer, cr-0100). Implements the generated
 * [SharingApi]; thin (ADR 0001) — delegates to [SharingHandler]. The class is `@Authenticated` (curator
 * create/list/revoke); the two **guest** ops (`resolveShare`, `resolveShareBlob`) override with `@PermitAll`
 * so an account-less viewer reaches them — gated only by share state (a non-enumerable 404), never by auth.
 * Operator-blind: only ciphertext wraps + opaque metadata + presigned URLs cross.
 */
@Authenticated
@RateLimitExempt(
    reason = "Curator surface: authenticated + owner-gated on the caller's own shares. The guest (@PermitAll) " +
        "operations each declare their own posture and override this — a class-level default must never be what " +
        "silently covers the unauthenticated ones.",
)
class SharingResource(
    private val handler: SharingHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
) : SharingApi {

    /** As in `MediaResource`: the resolved tenant's own domain (ADR 0063), never the raw `Host` header. A share
     *  is opened by an accountless guest on the tenant's domain, so its bytes must be signed for that origin too
     *  — the same-origin blob route (ADR 0013 §4) is what a public share depends on for having no CORS policy. */
    private fun signingDomain(): String? = tenant.resolve()?.domain

    override fun createShare(createShareRequest: CreateShareRequest): Response =
        when (val o = handler.create(
            userId = identity.principal.name,
            shareId = createShareRequest.shareId,
            nodeId = createShareRequest.nodeId.toString(),
            role = createShareRequest.role.value, // VIEWER | CONTRIBUTOR (cr-0101) | UPLOADER (cr-0102)
            wrap = createShareRequest.wrap,
            sealedDropPublicKey = createShareRequest.sealedDropPublicKey, // UPLOADER: instead of the wrap (ADR 0031 point 7)
            sealedDropPrivateKey = createShareRequest.sealedDropPrivateKey, // the Memory's first UPLOADER share only
            sealedName = createShareRequest.sealedName,
            expiresAt = createShareRequest.expiresAt?.toInstant(),
            sealedNote = createShareRequest.sealedNote, // cr-0115: optional curator note under K_share
            sealedShareKey = createShareRequest.sealedShareKey, // K_share under K_SC(epoch): members recover it (ADR 0036)
            sealedShareKeyEpoch = createShareRequest.sealedShareKeyEpoch, // the scope epoch it is sealed at
            sealedShareName = createShareRequest.sealedShareName, // cr-0116: optional private label under K_share
            passwordRequired = createShareRequest.passwordRequired ?: false, // cr-0118: optional access-control gate
            pwSalt = createShareRequest.pwSalt,
            pwVerifier = createShareRequest.pwVerifier,
            sealedPassword = createShareRequest.sealedPassword, // cr-0118: management-only reveal copy
        )) {
            is CreateShareOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareCreated(shareId = o.shareId)).build()
            is HttpFailure -> httpError(o)
        }

    override fun listShares(nodeId: UUID?): Response {
        val me = identity.principal.name
        // Without nodeId: the caller's own shares. With nodeId: every share on that Memory visible to a scope
        // member (cr-0104) — `mine` tells the UI which the caller may revoke vs merely see / adopt.
        val shares = if (nodeId == null) {
            handler.list(me)
        } else {
            when (val o = handler.listForNode(me, nodeId.toString())) {
                is NodeSharesOutcome.Shares -> o.rows
                is HttpFailure -> return httpError(o)
            }
        }
        return Response.ok(shares.map { s ->
            ShareSummary(
                shareId = s.id,
                scopeId = UUID.fromString(s.scopeId), // the recovery wrap is bound to (scope, node, share), ADR 0072
                nodeId = UUID.fromString(s.nodeId),
                role = ShareRole.decode(s.role),
                sealedName = s.sealedName,
                enabled = s.enabled,
                mine = s.ownerAccount == me,
                createdAt = s.createdAt.atOffset(ZoneOffset.UTC),
                expiresAt = s.expiresAt?.atOffset(ZoneOffset.UTC),
                sealedNote = s.sealedNote, // cr-0115: the manage UI shows + edits it
                sealedShareName = s.sealedShareName, // cr-0116: the manage list's row title (falls back to the role)
                sealedShareKey = s.sealedShareKey, // a member's manage UI recovers K_share through its epoch keys
                sealedShareKeyEpoch = s.sealedShareKeyEpoch,
                passwordRequired = s.passwordRequired, // cr-0118: a lock badge in the manage list
                sealedPassword = s.sealedPassword, // cr-0118: the reveal copy (management-only; never a viewer)
            )
        }).build()
    }

    override fun revokeShare(shareId: String): Response =
        when (val o = handler.revoke(identity.principal.name, shareId)) {
            RevokeShareOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun reactivateShare(shareId: String): Response =
        when (val o = handler.reactivate(identity.principal.name, shareId)) {
            ReactivateShareOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // Re-issue (ADR 0075): the new share's node + role come from the old row, never from the request.
    override fun reissueShare(shareId: String, reissueShareRequest: ReissueShareRequest): Response =
        when (val o = handler.reissue(
            userId = identity.principal.name,
            shareId = shareId,
            newShareId = reissueShareRequest.shareId,
            wrap = reissueShareRequest.wrap,
            sealedDropPublicKey = reissueShareRequest.sealedDropPublicKey,
            sealedName = reissueShareRequest.sealedName,
            expiresAt = reissueShareRequest.expiresAt?.toInstant(),
            sealedNote = reissueShareRequest.sealedNote,
            sealedShareKey = reissueShareRequest.sealedShareKey,
            sealedShareKeyEpoch = reissueShareRequest.sealedShareKeyEpoch,
            sealedShareName = reissueShareRequest.sealedShareName,
            passwordRequired = reissueShareRequest.passwordRequired ?: false,
            pwSalt = reissueShareRequest.pwSalt,
            pwVerifier = reissueShareRequest.pwVerifier,
            sealedPassword = reissueShareRequest.sealedPassword,
        )) {
            is ReissueShareOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareCreated(shareId = o.shareId)).build()
            is HttpFailure -> httpError(o)
        }

    // Edit a share in place (cr-0115/cr-0116/cr-0119, ADR 0036) — owner-gated in the handler (404 if not the
    // caller's). The K_share-sealed ciphertexts (note + name) are re-sealed client-side; an optional `role` changes
    // the grant in place (VIEWER ↔ CONTRIBUTOR — UPLOADER is 422). The link/K_share/wrap are untouched either way.
    override fun updateShare(shareId: String, updateShareRequest: UpdateShareRequest): Response =
        when (val o = handler.updateShare(
            identity.principal.name, shareId,
            updateShareRequest.sealedNote, updateShareRequest.sealedShareName, updateShareRequest.role?.value,
            updateShareRequest.expiresAt?.toInstant(),
            updateShareRequest.passwordRequired, updateShareRequest.pwSalt, updateShareRequest.pwVerifier, updateShareRequest.sealedPassword,
        )) {
            UpdateShareOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of the sealed subtree. Volume is unbounded BY DECISION, not by omission: neither " +
            "available axis fits, and the residual gap is recorded in cc.security.rate-limit " +
            "(§ Unbounded by decision) rather than left silent.",
    )
    override fun resolveShare(shareId: String, xSharePassword: String?): Response =
        when (val o = handler.resolve(shareId, xSharePassword)) {
            is ResolveShareOutcome.Found -> Response.ok(
                PublicShare(
                    role = ShareRole.decode(o.share.role),
                    scopeId = UUID.fromString(o.share.scopeId), // every K_mem seal the viewer opens is bound to it (ADR 0072)
                    passwordRequired = o.share.passwordRequired, // cr-0118: false, or true once unlocked with a valid token
                    pwSalt = if (o.share.passwordRequired) o.share.pwSalt else null,
                    // ADR 0031 point 7: an UPLOADER guest is handed the drop public key, never `K_mem`. The role
                    // decides here too, not only the stored row, so no UPLOADER response can ever carry a wrap.
                    wrap = if (o.share.role == ShareEntity.UPLOADER) null else o.share.wrap,
                    sealedDropPublicKey = if (o.share.role == ShareEntity.UPLOADER) o.share.sealedDropPublicKey else null,
                    nodeId = UUID.fromString(o.share.nodeId), // an UPLOADER guest binds its identity key's wrap to it
                    sealedName = o.share.sealedName,
                    sealedNote = o.share.sealedNote, // cr-0115: the share page renders it (null when none)
                    nodes = o.subtree.nodes.map(::toShareNode),
                    media = o.subtree.media.map(::toShareMedia),
                    identities = o.uploaderKeys.map { (id, wrap) -> ShareIdentity(identityId = UUID.fromString(id), wrappedIdentityKey = wrap) },
                ),
            ).build()
            // cr-0118: password required + no token → a LOCKED shell (only the challenge; no wrap/name/note/subtree).
            is ResolveShareOutcome.Locked -> Response.ok(
                PublicShare(
                    role = ShareRole.decode(o.share.role),
                    passwordRequired = true,
                    pwSalt = o.share.pwSalt,
                    wrap = null, sealedDropPublicKey = null, nodeId = null, sealedName = null, sealedNote = null,
                    nodes = emptyList(), media = emptyList(), identities = null,
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest presign of one share blob — the costliest of the three guest reads, since it mints a " +
            "presigned URL. Unbounded by the same decision as resolveShare; cc.security.rate-limit " +
            "(§ Unbounded by decision) names it as the open item rather than waving it away.",
    )
    override fun resolveShareBlob(shareId: String, blobId: UUID, xSharePassword: String?): Response =
        when (val o = handler.resolveBlob(shareId, blobId.toString(), xSharePassword, signingDomain())) {
            is ShareBlobOutcome.Found -> Response.ok(
                ShareBlobDownload(presignedGets = o.gets.map { PresignedUrl(index = it.index, url = it.url) }),
            ).build()
            is HttpFailure -> httpError(o)
        }

    // The accountless terms gate (uc.sharing.share-accept, ADR 0035 §3; @PermitAll — no auth, keyed on the
    // non-enumerable shareId, no X-Share-Password: terms are public). Returns the owning tenant's public terms so
    // the client can gate acceptance in localStorage. NOTE: this endpoint records NOTHING about the visitor — the
    // accountless acceptance is an operational client-side gate, not enforcement (see SharingHandler.shareTerms).
    @PermitAll
    @RateLimitExempt(
        reason = "Account-less read of a share's terms — a constant document with no per-caller state. Same " +
            "guest-read volume decision as resolveShare (cc.security.rate-limit § Unbounded by decision).",
    )
    override fun getShareTerms(shareId: String): Response =
        when (val o = handler.shareTerms(shareId)) {
            is ShareTermsOutcome.Found -> Response.ok(
                ShareTerms(
                    tenantId = UUID.fromString(o.tenantId),
                    termsVersion = o.termsVersion,
                    // null when the operator has published no terms (termsVersion 0) — the client never gates.
                    terms = if (o.de == null && o.en == null) null else LocalizedMarkdown(de = o.de, en = o.en),
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    private fun toShareNode(n: ShareNodeRow) = ShareNode(
        id = UUID.fromString(n.id),
        kind = NodeKind.decode(n.kind),
        parentId = n.parentId?.let(UUID::fromString),
        orderIndex = n.orderIndex,
        epoch = n.epoch,
        wrappedContentKey = n.wrappedContentKey,
        wrappedMeta = n.wrappedMeta,
        sealedDropPrivateKey = n.sealedDropPrivateKey,
    )

    private fun toShareMedia(m: ShareMediaRow) = ShareMedia(
        nodeId = UUID.fromString(m.nodeId),
        mediaId = UUID.fromString(m.mediaId),
        blobId = UUID.fromString(m.blobId),
        chunkCount = m.chunkCount,
        byteSize = m.byteSize,
        wrappedBlobKey = m.wrappedBlobKey,
        manifest = m.manifest,
        derivatives = m.derivatives.map { d ->
            ShareMediaDerivative(
                kind = ShareMediaDerivative.Kind.valueOf(d.kind),
                blobId = UUID.fromString(d.blobId),
                chunkCount = d.chunkCount,
                byteSize = d.byteSize,
                wrappedBlobKey = d.wrappedBlobKey,
                manifest = d.manifest,
            )
        },
        wrappedMeta = m.wrappedMeta,
        identityId = m.identityId?.let(UUID::fromString),
    )

    // --- contributor guest ops (cr-0101; @PermitAll — account-less, gated only by share state + token) ---

    @PermitAll
    // The one guest call that writes a row before any upload exists, so an unbounded link holder could grow the
    // identity table on its own. Bounded per share in the handler (ShareRateLimiter.allowIdentity).
    @RateLimit(key = RateLimitKey.SHARE)
    override fun createShareIdentity(shareId: String, createShareIdentityRequest: CreateShareIdentityRequest, xSharePassword: String?): Response =
        when (val o = handler.createIdentity(
            shareId, xSharePassword, createShareIdentityRequest.identityId.toString(), createShareIdentityRequest.sealedDisplayName,
            createShareIdentityRequest.wrappedIdentityKey,
        )) {
            is CreateIdentityOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareIdentityCreated(identityId = UUID.fromString(o.identityId), contributorToken = o.contributorToken)).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    // The one guest write that already carries a real volume bound: ShareRateLimiter, per shareId, checked in the
    // handler before any presign is minted. SHARE is a handler-enforced marker (ADR 0051) — a per-IP bound would
    // be the wrong axis here, since a share's guests can all sit behind one proxy address.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun startShareBlobUploads(shareId: String, shareBlobUploadsRequest: ShareBlobUploadsRequest, xSharePassword: String?): Response {
        val declares = shareBlobUploadsRequest.items.map {
            UploadDeclare(it.chunkCount, it.byteSize, it.wrappedBlobKey, it.blobId.toString(), it.ownerId.toString(), it.role.value)
        }
        return when (val o = handler.startUploads(shareId, xSharePassword, shareBlobUploadsRequest.contributorToken, declares, signingDomain())) {
            is ShareUploadOutcome.Results -> Response.ok(
                ShareBlobUploadsResponse(
                    results = o.items.map {
                        ShareBlobUploadResultItem(
                            uploadId = it.uploadId?.let(UUID::fromString),
                            presignedPuts = it.presignedPuts?.map { p -> PresignedUrl(index = p.index, url = p.url) },
                            error = it.error,
                        )
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    @RateLimitExempt(
        reason = "A window creates no session and no row, so it does not spend the share's upload budget (ShareRateLimiter " +
            "counts those writes). It runs the link's whole gate again, signs only sessions the token's identity " +
            "opened through that budget, and at most 256 items x veyq.blob.presign-window URLs per call; raw call " +
            "volume by a link holder stays open, as for the guest reads (cc.security.rate-limit \u00a7 Unbounded by decision).",
    )
    override fun presignShareBlobUploadWindows(shareId: String, shareBlobUploadWindowsRequest: ShareBlobUploadWindowsRequest, xSharePassword: String?): Response {
        val requests = shareBlobUploadWindowsRequest.items.map { WindowRequest(it.uploadId.toString(), it.fromChunk) }
        return when (val o = handler.windowUploads(shareId, xSharePassword, shareBlobUploadWindowsRequest.contributorToken, requests, signingDomain())) {
            is ShareWindowOutcome.Results -> Response.ok(
                ShareBlobUploadWindowsResponse(
                    results = o.items.map {
                        ShareBlobUploadWindowResultItem(presignedPuts = it.presignedPuts?.map { p -> PresignedUrl(index = p.index, url = p.url) }, error = it.error)
                    },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    // The attach half of the contributor upload flow — it creates the media rows, so it rides the same per-share
    // upload budget as startShareBlobUploads (ShareRateLimiter.allowUpload).
    @RateLimit(key = RateLimitKey.SHARE)
    override fun completeShareMedia(shareId: String, shareMediaBatchRequest: ShareMediaBatchRequest, xSharePassword: String?): Response {
        val items = shareMediaBatchRequest.items.map { item ->
            ShareCompleteItem(
                mediaId = item.mediaId.toString(),
                originalUploadId = item.originalUploadId.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
            )
        }
        return when (val o = handler.completeUploads(shareId, xSharePassword, shareMediaBatchRequest.contributorToken, items)) {
            is ShareCompleteOutcome.Results -> Response.ok(
                ShareMediaBatchResponse(
                    results = o.items.map { ShareMediaResultItem(blobId = it.blobId?.let(UUID::fromString), mediaId = it.mediaId?.let(UUID::fromString), error = it.error) },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    @RateLimitExempt(
        reason = "Delete-own is bounded by what the caller contributed: the store authorizes by single-row " +
            "ownership (the media's share_identity_id must equal the token's identity), and those rows are " +
            "already capped by the per-share upload budget. A separate delete bound would cap nothing new.",
    )
    override fun deleteShareMedia(shareId: String, mediaId: UUID, xContributorToken: String, xSharePassword: String?): Response =
        when (val o = handler.deleteMedia(shareId, xSharePassword, xContributorToken, mediaId.toString())) {
            ShareDeleteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
