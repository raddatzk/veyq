package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.generated.api.SharingApi
import me.raddatz.veyq.core.generated.model.CreateShareIdentityRequest
import me.raddatz.veyq.core.generated.model.CreateShareNodeRequest
import me.raddatz.veyq.core.generated.model.ShareNodeCreated
import me.raddatz.veyq.core.generated.model.UpdateShareNodeRequest
import me.raddatz.veyq.core.generated.model.CreateShareRequest
import me.raddatz.veyq.core.generated.model.LocalizedMarkdown
import me.raddatz.veyq.core.generated.model.PresignedUrl
import me.raddatz.veyq.core.generated.model.PublicShare
import me.raddatz.veyq.core.generated.model.ReissueShareRequest
import me.raddatz.veyq.core.generated.model.ReportSubmission
import me.raddatz.veyq.core.generated.model.SetShareSealedNameRequest
import me.raddatz.veyq.core.generated.model.ShareBlobDownload
import me.raddatz.veyq.core.generated.model.ShareBlobUploadResultItem
import me.raddatz.veyq.core.generated.model.ShareBlobUploadWindowResultItem
import me.raddatz.veyq.core.generated.model.ShareBlobUploadWindowsRequest
import me.raddatz.veyq.core.generated.model.ShareBlobUploadWindowsResponse
import me.raddatz.veyq.core.generated.model.ShareBlobUploadsRequest
import me.raddatz.veyq.core.generated.model.ShareBlobUploadsResponse
import me.raddatz.veyq.core.generated.model.ShareCreated
import me.raddatz.veyq.core.generated.model.ShareGuestStop
import me.raddatz.veyq.core.generated.model.ShareIdentityCreated
import me.raddatz.veyq.core.generated.model.ShareNode
import me.raddatz.veyq.core.generated.model.ShareRole
import me.raddatz.veyq.core.generated.model.ShareSummary
import me.raddatz.veyq.core.generated.model.ShareTerms
import me.raddatz.veyq.core.terms.toWire
import me.raddatz.veyq.core.generated.model.UpdateShareRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.RequestClient
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.moderation.report.ReportHandler
import me.raddatz.veyq.core.moderation.report.ReportReceived
import me.raddatz.veyq.core.moderation.report.Reporter
import me.raddatz.veyq.core.moderation.report.ReportsResource
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.order.GuestNotices
import me.raddatz.veyq.core.moderation.report.ReportsResource.Companion.toReport
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.tenant.RequestTenant
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

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
    /** A guest's tree operations (`createShareNode`, `updateShareNode`, `abandonShareNode`). */
    private val nodes: ShareNodesHandler,
    private val identity: SecurityIdentity,
    private val tenant: RequestTenant,
    private val reports: ReportHandler,
    private val received: ReportReceived,
    private val blocks: ModerationBlocks,
    private val guestNotices: GuestNotices,
    /** The client a guest write records (ADR 0093 § 10, ADR 0094): resolved once per write, handed down to it. */
    private val client: RequestClient,
    /** What a guest page discloses before the first write (ADR 0093 § 10). */
    private val guestWriteRecords: GuestWriteRecords,
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
            role = createShareRequest.role.value, // READ | READ_WRITE (cr-0101) | WRITE_ONLY (cr-0102)
            wrap = createShareRequest.wrap,
            sealedDropPublicKey = createShareRequest.sealedDropPublicKey, // WRITE_ONLY: instead of the wrap (ADR 0031 point 7)
            sealedDropPrivateKey = createShareRequest.sealedDropPrivateKey, // the node's first WRITE_ONLY share only
            sealedName = createShareRequest.sealedName,
            expiresAt = createShareRequest.expiresAt?.toInstant(),
            sealedNote = createShareRequest.sealedNote, // cr-0115: optional curator note under K_share
            sealedShareKey = createShareRequest.sealedShareKey, // K_share under K_SC(epoch): members recover it (ADR 0036, mandatory per ADR 0080)
            sealedShareKeyEpoch = createShareRequest.sealedShareKeyEpoch.validated(), // the scope epoch it is sealed at
            sealedShareName = createShareRequest.sealedShareName, // cr-0116: optional private label under K_share
            passwordRequired = createShareRequest.passwordRequired ?: false, // cr-0118: optional access-control gate
            pwSalt = createShareRequest.pwSalt,
            pwVerifier = createShareRequest.pwVerifier,
            sealedPassword = createShareRequest.sealedPassword, // cr-0118: management-only reveal copy
            writeVerifier = createShareRequest.writeVerifier, // ADR 0076: the hash of the link's key-derived write proof
            nodeEpoch = createShareRequest.nodeEpoch.validated(), // ADR 0109 point 6: the share wraps the node's key
        )) {
            is CreateShareOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareCreated(shareId = o.shareId)).build()
            is HttpFailure -> httpError(o)
        }

    override fun listShares(nodeId: UUID?): Response {
        val me = identity.principal.name
        // Without nodeId: the caller's own shares. With nodeId: every share on that node visible to a scope
        // member (cr-0104) — `mine` tells the UI which the caller may revoke vs merely see / adopt.
        val shares = if (nodeId == null) {
            handler.list(me)
        } else {
            when (val o = handler.listForNode(me, nodeId.toString())) {
                is NodeSharesOutcome.Shares -> o.rows
                is HttpFailure -> return httpError(o)
            }
        }
        return Response.ok(shares.map { (s, reissueRequired, deletable, newGuestsRefused, guestStops) ->
            ShareSummary(
                shareId = s.id,
                scopeId = UUID.fromString(s.scopeId), // the recovery wrap is bound to (scope, node, share), ADR 0072
                nodeId = UUID.fromString(s.nodeId),
                role = ShareRole.decode(s.role),
                sealedName = checkNotNull(s.sealedName) { "a listed share is never a tombstone" },
                enabled = s.enabled,
                mine = s.ownerAccount == me,
                reissueRequired = reissueRequired, // ADR 0075: the predicate the re-activation refusal runs
                deletable = deletable, // ADR 0029 §7: the predicate the delete gate runs
                newGuestsRefused = newGuestsRefused, // ADR 0076 point 5: re-issue to let new guests in (#270)
                // …and the same missing verifier for any role: the role edit warns before it makes a guest link (#303)
                writeVerifierMissing = s.writeVerifier == null,
                guestStops = guestStops.map { it.toDto() }, // inbox #457: the guest gate's own answer, for members only
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

    private fun GuestStop.toDto() = ShareGuestStop(
        reason = when (reason) {
            GuestStop.Reason.REVOKED -> ShareGuestStop.Reason.revoked
            GuestStop.Reason.EXPIRED -> ShareGuestStop.Reason.expired
            GuestStop.Reason.SPACE_UNAVAILABLE -> ShareGuestStop.Reason.spaceUnavailable
            GuestStop.Reason.INSTANCE_SWITCH -> ShareGuestStop.Reason.instanceSwitch
            GuestStop.Reason.TENANT_SWITCH -> ShareGuestStop.Reason.tenantSwitch
            GuestStop.Reason.LINK_PASSWORD -> ShareGuestStop.Reason.linkPassword
            GuestStop.Reason.LINK_MAX_DAYS -> ShareGuestStop.Reason.linkMaxDays
            GuestStop.Reason.OPERATOR_BLOCKED -> ShareGuestStop.Reason.operatorBlocked
        },
        capabilityId = capabilityId,
        maxDays = maxDays,
    )

    override fun revokeShare(shareId: String): Response =
        when (val o = handler.revoke(identity.principal.name, shareId)) {
            RevokeShareOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun deleteShare(shareId: String): Response =
        when (val o = handler.delete(identity.principal.name, shareId)) {
            DeleteShareOutcome.Done -> Response.noContent().build()
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
            sealedShareKeyEpoch = reissueShareRequest.sealedShareKeyEpoch.validated(),
            sealedShareName = reissueShareRequest.sealedShareName,
            passwordRequired = reissueShareRequest.passwordRequired ?: false,
            pwSalt = reissueShareRequest.pwSalt,
            pwVerifier = reissueShareRequest.pwVerifier,
            sealedPassword = reissueShareRequest.sealedPassword,
            writeVerifier = reissueShareRequest.writeVerifier, // the new K_share's proof (ADR 0076)
            nodeEpoch = reissueShareRequest.nodeEpoch.validated(), // ADR 0109 point 6: the new link wraps the node's key
        )) {
            is ReissueShareOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareCreated(shareId = o.shareId)).build()
            is HttpFailure -> httpError(o)
        }

    // Edit a share in place (cr-0115/cr-0116/cr-0119, ADR 0036) — owner-gated in the handler (404 if not the
    // caller's). The K_share-sealed ciphertexts (note + name) are re-sealed client-side; an optional `role` changes
    // the grant in place (READ ↔ READ_WRITE — WRITE_ONLY is 422). The link/K_share/wrap are untouched either way.
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

    // A share root's re-sealed name (ADR 0080 decision 14) — any member who may write in the share's space, not
    // only its owner; the handler changes `sealedName` and nothing else. Opaque ciphertext in, nothing opened.
    override fun setShareSealedName(shareId: String, setShareSealedNameRequest: SetShareSealedNameRequest): Response =
        when (val o = handler.setSealedName(identity.principal.name, shareId, setShareSealedNameRequest.sealedName)) {
            SetShareSealedNameOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of the sealed subtree. Volume is unbounded BY DECISION, not by omission: neither " +
            "available axis fits, and the residual gap is recorded in cc.security.rate-limit " +
            "(§ Unbounded by decision) rather than left silent.",
    )
    override fun resolveShare(shareId: String, xSharePassword: String?, ifNoneMatch: String?): Response =
        when (val o = handler.resolve(shareId, xSharePassword)) {
            is ResolveShareOutcome.Found -> {
                val body = PublicShare(
                    role = ShareRole.decode(o.share.role),
                    scopeId = UUID.fromString(o.share.scopeId), // every seal under a node's content key the viewer opens is bound to it (ADR 0072)
                    passwordRequired = o.share.passwordRequired, // cr-0118: false, or true once unlocked with a valid token
                    pwSalt = if (o.share.passwordRequired) o.share.pwSalt else null,
                    // ADR 0031 point 7: a WRITE_ONLY guest is handed the drop public key, never the node's content key. The role
                    // decides here too, not only the stored row, so no WRITE_ONLY response can ever carry a wrap.
                    wrap = if (o.share.role == ShareEntity.WRITE_ONLY) null else o.share.wrap,
                    sealedDropPublicKey = if (o.share.role == ShareEntity.WRITE_ONLY) o.share.sealedDropPublicKey else null,
                    nodeId = UUID.fromString(o.share.nodeId), // a WRITE_ONLY guest binds its identity key's wrap to it
                    rootKind = o.rootKind, // every unlocked role, WRITE_ONLY included (inbox #329); the Locked arm sets none
                    // ADR 0109 point 6: what a guest's write states as the root's epoch. In the body, so the tag below moves
                    // when a re-key reaches the root and an open page re-reads its keys.
                    rootEpoch = o.rootEpoch,
                    sealedName = o.share.sealedName,
                    sealedNote = o.share.sealedNote, // cr-0115: the share page renders it (null when none)
                    nodes = blocks.contentView(o.share.scopeId).let { view -> o.nodes.map { toShareNode(it, view.nodeBlocked(it.id)) } },
                    // N while the role writes — the sentence the page shows before a first write. In the body, so the
                    // tag below changes when N does and an open page learns it.
                    guestWriteRecordDays = guestWriteRecords.disclosedDays(o.share.role),
                )
                // ADR 0081: the tag is a digest of what this response serves — nothing the guest could not already read, and
                // so (unlike the member listing's scope revision) blind to every change outside the shared subtree.
                val etag = ShareETag.of(body.copy(nodes = body.nodes.sortedBy { it.id.toString() }), o.attachments) // the store's row order is no part of the content
                if (ShareETag.matches(ifNoneMatch, etag)) Response.notModified().header("ETag", etag).build()
                else Response.ok(body).header("ETag", etag).build()
            }
            // cr-0118: password required + no token → a LOCKED shell (only the challenge; no wrap/name/note/subtree).
            is ResolveShareOutcome.Locked -> Response.ok(
                PublicShare(
                    role = ShareRole.decode(o.share.role),
                    passwordRequired = true,
                    pwSalt = o.share.pwSalt,
                    wrap = null, sealedDropPublicKey = null, nodeId = null, sealedName = null, sealedNote = null,
                    nodes = emptyList(),
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest presign of one share blob — the costliest of the four anonymous guest reads, since it mints a " +
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
                    termsRevision = o.termsRevision,
                    // null when the space owner has published no terms (termsRevision 0) — the client never gates.
                    terms = if (o.de == null && o.en == null) null else LocalizedMarkdown(de = o.de, en = o.en),
                    operatorSection = o.section.toWire(),
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    // A guest's report (uc.moderation.report, ADR 0093 §§ 1-2): behind the link, its password and its write proof —
    // the same handler as a member's report (ReportsResource).
    @PermitAll
    // Per address at the boundary; per link in the handler (ReportRateLimiter.allowShare) — a guest has no account to
    // count open reports against (owner decision Q8).
    @RateLimit(key = RateLimitKey.IP)
    override fun reportInShare(shareId: String, reportSubmission: ReportSubmission, xSharePassword: String?, xShareWrite: String?): Response =
        reportSubmission.toReport().let { report ->
            ReportsResource.answer(reports.report(Reporter.Guest(shareId, xSharePassword, xShareWrite), report), received, report.receiptAddress)
        }

    // What a guest uploader is told of a measure on its upload — an authority's removal order (TCO Art. 11, ADR 0093
    // § 12), a moderator's statement of reasons (ADR 0093 § 7): named by its contributor token, answered even on a
    // blocked link, and the link's ordinary unknown answer for anything else.
    @PermitAll
    @RateLimit(key = RateLimitKey.IP)
    override fun getShareGuestNotice(shareId: String, xContributorToken: String): Response =
        guestNotices.noticeFor(shareId, xContributorToken)?.let { Response.ok(it).build() }
            ?: httpError(CreateIdentityOutcome.NotFound)

    private fun toShareNode(n: ShareNodeRow, blocked: Boolean) = ShareNode(
        id = UUID.fromString(n.id),
        kind = n.kind,
        parentId = n.parentId?.let(UUID::fromString),
        orderIndex = n.orderIndex,
        epoch = n.epoch,
        wrappedContentKey = n.wrappedContentKey,
        encryptedName = n.encryptedName,
        placementRevision = n.placementRevision,
        wrappedMeta = n.wrappedMeta,
        sealedDropPrivateKey = n.sealedDropPrivateKey,
        shareIdentityId = n.shareIdentityId?.let(UUID::fromString),
        wrappedIdentityKey = n.wrappedIdentityKey,
        // Kept and marked, not left out (uc.moderation.take-a-measure): a guest sees the placeholder a member sees.
        blockedByOperator = blocked,
    )

    // --- contributor guest ops (cr-0101; @PermitAll — account-less, gated only by share state + token) ---

    @PermitAll
    // The one guest call that writes a row before any upload exists, so an unbounded link holder could grow the
    // identity table on its own. Bounded per share in the handler (ShareRateLimiter.allowIdentity).
    @RateLimit(key = RateLimitKey.SHARE)
    override fun createShareIdentity(
        shareId: String, createShareIdentityRequest: CreateShareIdentityRequest, xSharePassword: String?, xShareWrite: String?,
    ): Response =
        when (val o = handler.createIdentity(
            shareId, xSharePassword, xShareWrite, createShareIdentityRequest.identityId.toString(), createShareIdentityRequest.sealedDisplayName,
            createShareIdentityRequest.wrappedIdentityKey, createShareIdentityRequest.nodeEpoch.validated(), client.seen(),
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
            UploadDeclare(it.chunkCount, it.byteSize, it.wrappedBlobKey, it.blobId.toString(), it.ownerId.toString(), it.role, it.wrappedBlobMeta)
        }
        return when (
            val o = handler.startUploads(
                shareId, xSharePassword, shareBlobUploadsRequest.contributorToken, declares, signingDomain(),
                shareBlobUploadsRequest.nodeId?.toString(),
            )
        ) {
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
        val requests = shareBlobUploadWindowsRequest.items.map { WindowRequest(it.uploadId.toString(), it.fromChunk.validated()) }
        return when (
            val o = handler.windowUploads(
                shareId, xSharePassword, shareBlobUploadWindowsRequest.contributorToken, requests, signingDomain(),
                shareBlobUploadWindowsRequest.nodeId?.toString(),
            )
        ) {
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

    // --- a READ_WRITE guest's tree operations (ADR 0080 decisions 6 and 10; @PermitAll — gated by the link + token) ---

    @PermitAll
    // A create adds a node row, so it rides the per-share upload budget like the other row-creating guest writes
    // (ShareRateLimiter.allowUpload, spent in the handler after the gate's other checks).
    @RateLimit(key = RateLimitKey.SHARE)
    override fun createShareNode(
        shareId: String, xContributorToken: String, createShareNodeRequest: CreateShareNodeRequest, xSharePassword: String?,
    ): Response =
        when (val o = nodes.create(
            shareId, xSharePassword, xContributorToken,
            nodeId = createShareNodeRequest.nodeId.toString(),
            parentId = createShareNodeRequest.parentId.toString(),
            kind = createShareNodeRequest.kind,
            encryptedName = createShareNodeRequest.encryptedName,
            slugToken = createShareNodeRequest.slugToken,
            orderIndex = createShareNodeRequest.orderIndex ?: 0,
            epoch = createShareNodeRequest.epoch.validated(),
            wrappedContentKey = createShareNodeRequest.wrappedContentKey,
            parentEpoch = createShareNodeRequest.parentEpoch.validated(),
            seen = client.seen(),
        )) {
            is CreateShareNodeOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareNodeCreated(nodeId = UUID.fromString(o.nodeId))).build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "A placement adds no row: it rewrites one node's entry inside the shared subtree, compare-and-set on " +
            "its placement revision, so its volume is bounded by the nodes the subtree already holds — which members " +
            "and the create budget bound. Raw call volume by a link holder stays open, as for the guest reads " +
            "(cc.security.rate-limit § Unbounded by decision).",
    )
    override fun updateShareNode(
        shareId: String, nodeId: UUID, xContributorToken: String, updateShareNodeRequest: UpdateShareNodeRequest, xSharePassword: String?,
    ): Response =
        when (val o = nodes.update(
            shareId, xSharePassword, xContributorToken,
            nodeId = nodeId.toString(),
            parentId = updateShareNodeRequest.parentId.toString(),
            encryptedName = updateShareNodeRequest.encryptedName,
            slugToken = updateShareNodeRequest.slugToken,
            wrappedContentKey = updateShareNodeRequest.wrappedContentKey,
            placementRevision = updateShareNodeRequest.placementRevision.validated(),
            parentEpoch = updateShareNodeRequest.parentEpoch.validated(),
            seen = client.seen(),
        )) {
            UpdateShareNodeOutcome.Updated -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    @PermitAll
    @RateLimitExempt(
        reason = "An abandon adds no row: it erases one empty placeholder a guest created, so its volume is bounded by " +
            "the creates, which spend the share's upload budget. Raw call volume by a link holder stays open, as for " +
            "the guest reads (cc.security.rate-limit § Unbounded by decision).",
    )
    override fun abandonShareNode(shareId: String, nodeId: UUID, xContributorToken: String, xSharePassword: String?): Response =
        when (val o = nodes.abandon(shareId, xSharePassword, xContributorToken, nodeId.toString())) {
            AbandonShareNodeOutcome.Abandoned -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
