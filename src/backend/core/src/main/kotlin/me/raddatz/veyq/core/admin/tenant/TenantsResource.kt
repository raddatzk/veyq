package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.generated.api.TenantsApi
import me.raddatz.veyq.core.generated.model.AcceptInviteRequest
import me.raddatz.veyq.core.generated.model.AcceptOwnershipRequest
import me.raddatz.veyq.core.generated.model.DeleteTenantRequest
import me.raddatz.veyq.core.generated.model.InviteTenantOwnerRequest
import me.raddatz.veyq.core.generated.model.SetTenantMemberRoleRequest
import me.raddatz.veyq.core.generated.model.TenantDeletion
import me.raddatz.veyq.core.generated.model.TenantSuspension
import me.raddatz.veyq.core.generated.model.AcceptPreviewRequest
import me.raddatz.veyq.core.generated.model.AcceptTermsRequest
import me.raddatz.veyq.core.generated.model.ChangeDomainRequest
import me.raddatz.veyq.core.generated.model.CreateTenantRequest
import me.raddatz.veyq.core.generated.model.InviteRole
import me.raddatz.veyq.core.generated.model.InviteStatus
import me.raddatz.veyq.core.generated.model.LocalizedMarkdown
import me.raddatz.veyq.core.generated.model.MemberAvatar
import me.raddatz.veyq.core.generated.model.MemberVerification
import me.raddatz.veyq.core.generated.model.PutVerificationRequest
import me.raddatz.veyq.core.generated.model.VerificationPage
import me.raddatz.veyq.core.generated.model.MemberInviteRequest
import me.raddatz.veyq.core.generated.model.MemberInviteState
import me.raddatz.veyq.core.generated.model.PendingMemberInvite
import me.raddatz.veyq.core.generated.model.ReassignOwnerRequest
import me.raddatz.veyq.core.generated.model.InvitePreview
import me.raddatz.veyq.core.generated.model.RegisterAndAcceptRequest
import me.raddatz.veyq.core.generated.model.TenantCapabilityRequirement
import me.raddatz.veyq.core.generated.model.TenantCapabilityRequirementUpdate
import me.raddatz.veyq.core.platform.capabilities.capabilityWire
import me.raddatz.veyq.core.generated.model.CapabilityText
import me.raddatz.veyq.core.generated.model.TenantCapabilities
import me.raddatz.veyq.core.generated.model.ApplicationErasure
import me.raddatz.veyq.core.generated.model.TenantCapabilitySwitch
import me.raddatz.veyq.core.generated.model.TenantCapabilitySwitchUpdate
import me.raddatz.veyq.core.generated.model.TenantMailSettings
import me.raddatz.veyq.core.generated.model.TenantMailSettingsUpdate
import me.raddatz.veyq.core.generated.model.TenantActivated
import me.raddatz.veyq.core.generated.model.TenantActivatedWithUser
import me.raddatz.veyq.core.generated.model.TenantCreated
import me.raddatz.veyq.core.generated.model.TenantInviteState
import me.raddatz.veyq.core.generated.model.TenantStatus
import me.raddatz.veyq.core.generated.model.SuspendTenantRequest
import me.raddatz.veyq.core.moderation.statement.toStepInput
import me.raddatz.veyq.core.generated.model.TenantStorage
import me.raddatz.veyq.core.generated.model.TenantStorageFit
import me.raddatz.veyq.core.generated.model.TenantStorageFitRequest
import me.raddatz.veyq.core.generated.model.TenantStorageQuotaUpdate
import me.raddatz.veyq.core.generated.model.TenantStorageUpdate
import me.raddatz.veyq.core.generated.model.TenantSummary
import me.raddatz.veyq.core.generated.model.TenantTerms
import me.raddatz.veyq.core.generated.model.TenantTermsUpdate
import me.raddatz.veyq.core.terms.toWire
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.generated.model.ServedMemberInvite
import me.raddatz.veyq.core.generated.model.KeyChainStep
import me.raddatz.veyq.core.spaces.scope.ScopeSuccession
import me.raddatz.veyq.core.spaces.scope.toWire
import me.raddatz.veyq.core.identity.keychain.KeyChainLink
import me.raddatz.veyq.core.generated.model.InviteSelfGrant
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.identity.WrapData
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for Server-Owner tenant administration (uc.admin.create-tenant Slice A, cr-0009). Implements
 * the generated [TenantsApi]; thin (ADR 0001) — delegates to [TenantHandler] and maps outcomes to status codes.
 * Field validation (name/domain/email) is enforced by @Valid at the boundary (400) before this runs. The
 * Server-Owner gate is the class-level **@RolesAllowed** (cr-0012): a missing/invalid token → 401, a non-owner
 * → 403, both produced centrally by the security ExceptionMappers — so this adapter no longer reads the token,
 * carries no `unauthorized()`/`forbidden()`, and only sees already-authorized calls.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason =
        "Authenticated tenant administration — owner- or server-owner-gated. The two unauthenticated " +
        "methods (invite preview, register-and-accept) and the two invite mints carry their own posture " +
        "and override this.",
)
class TenantsResource(
    private val handler: TenantHandler,
    private val acceptHandler: TenantAcceptHandler,
    private val termsHandler: TenantTermsHandler,
    private val mailSettingsHandler: TenantMailSettingsHandler,
    private val storageHandler: TenantStorageHandler,
    private val avatarHandler: TenantMemberAvatarHandler,
    private val verificationHandler: TenantVerificationHandler,
    private val capabilitiesHandler: TenantCapabilitySwitchHandler,
    private val identity: SecurityIdentity,
) : TenantsApi {

    override fun createTenant(createTenantRequest: CreateTenantRequest): Response =
        when (val outcome = handler.create(
            createTenantRequest.tenantName,
            createTenantRequest.domain,
            createTenantRequest.ownerEmail,
        )) {
            is CreateTenantOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(TenantCreated(UUID.fromString(outcome.tenantId), TenantStatus.PENDING, outcome.expiresAt.utc(), outcome.bootstrapSecret, outcome.delivery))
                .build()
            is HttpFailure -> httpError(outcome)
        }

    override fun listTenants(): Response =
        Response.ok(handler.list().map { it.toSummary() }).build()

    override fun resendTenantInvite(tenantId: UUID): Response =
        handler.resendInvite(tenantId.toString()).toResponse()

    override fun reassignTenantInvite(tenantId: UUID, reassignOwnerRequest: ReassignOwnerRequest): Response =
        handler.reassignInvite(tenantId.toString(), reassignOwnerRequest.ownerEmail).toResponse()

    // Change-domain (uc.admin.create-tenant): Server-Owner-gated like the rest, and the one tenant-admin write
    // that is NOT PENDING-only — a domain is how a tenant is reached, not part of its invite lifecycle.
    override fun changeTenantDomain(tenantId: UUID, changeDomainRequest: ChangeDomainRequest): Response =
        when (val o = handler.changeDomain(tenantId.toString(), changeDomainRequest.domain)) {
            ChangeDomainOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun revokeTenant(tenantId: UUID): Response =
        when (val o = handler.revoke(tenantId.toString())) {
            RevokeOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // Delete an ACTIVE or SUSPENDED tenant and stop it (uc.admin.delete-tenant, ADR 0101 decision 10): Server-Owner-gated
    // like suspend, and like it offline at once; the purge erases it after the scope trash's retention.
    override fun deleteTenant(tenantId: UUID, deleteTenantRequest: DeleteTenantRequest): Response =
        when (
            val o = handler.delete(
                tenantId.toString(), deleteTenantRequest.domain, identity.principal.name, deleteTenantRequest.overContent ?: false,
                deleteTenantRequest.statement?.toStepInput(),
            )
        ) {
            is DeleteTenantOutcome.Deleted -> Response.ok(TenantDeletion(o.dueAt.utc(), o.statement?.delivery())).build()
            is HttpFailure -> httpError(o)
        }

    override fun stopTenantDeletion(tenantId: UUID): Response =
        when (val o = handler.stopDeletion(tenantId.toString(), identity.principal.name)) {
            StopTenantDeletionOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // An owner invite into an ACTIVE tenant, to one of its members (ADR 0101 decision 9): the way out of an ownerless one.
    override fun inviteTenantOwner(tenantId: UUID, inviteTenantOwnerRequest: InviteTenantOwnerRequest): Response =
        when (val o = handler.inviteOwner(tenantId.toString(), inviteTenantOwnerRequest.email)) {
            is OwnerInviteOutcome.Done -> Response.ok(
                TenantInviteState(o.invite.ownerEmail, InviteStatus.decode(o.invite.status), o.invite.expiresAt.utc(), o.invite.bootstrapSecret!!, o.delivery),
            ).build()
            is HttpFailure -> httpError(o)
        }

    // Suspend / reactivate (uc.admin.suspend-tenant, ADR 0066): Server-Owner-gated by the class-level
    // @RolesAllowed, exactly like revokeTenant. Note these two are themselves subject to TenantSuspensionFilter,
    // which guards the tenant's own domain: reaching them **there** is refused like any other request, which is the
    // deliberate reading of the use-case's E1 — Server-Owner status is global, it is not a per-tenant exemption.
    // The Server Owner reactivates from the server-admin surface (ADR 0065), which is not that domain.
    override fun suspendTenant(tenantId: UUID, suspendTenantRequest: SuspendTenantRequest?): Response =
        when (
            val o = handler.suspend(
                tenantId.toString(), identity.principal.name, suspendTenantRequest?.overContent ?: false,
                suspendTenantRequest?.statement?.toStepInput(),
            )
        ) {
            is SuspendOutcome.Done -> Response.ok(TenantSuspension(o.statement?.delivery())).build()
            is HttpFailure -> httpError(o)
        }

    override fun reactivateTenant(tenantId: UUID): Response =
        when (val o = handler.reactivate(tenantId.toString(), identity.principal.name)) {
            ReactivateOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // Member invite (cr-0077): NOT Server-Owner — the tenant OWNER admits members. @Authenticated overrides the
    // class gate; the owner-membership check lives in the handler (403 not_tenant_owner). The anti-bombing guard is
    // the handler's per-(tenant,recipient) InviteMailRateLimiter, surfaced as MemberInviteOutcome.RateLimited →
    // 429 + Retry-After (cr-email-invite-send-hardening). Deliberately NOT key = IP: an owner sits behind one
    // address and would share the single per-IP window with the unauthenticated auth endpoints, so an active owner
    // could trip the login limit. RECIPIENT is a handler-enforced marker (ADR 0051) — the filters pass it through.
    @Authenticated
    @RateLimit(key = RateLimitKey.RECIPIENT)
    override fun inviteTenantMember(tenantId: UUID, memberInviteRequest: MemberInviteRequest): Response =
        when (val outcome = handler.inviteMember(identity.principal.name, tenantId.toString(), memberInviteRequest.toInput())) {
            // No bootstrapSecret on the wire: a member invite's token is minted for the invited person and
            // travels by mail only (ADR 0055 §2). `delivery` can only be SENT here — the other outcomes are 503s.
            is MemberInviteOutcome.Done -> Response.status(Response.Status.CREATED).entity(
                MemberInviteState(outcome.invite.ownerEmail, InviteStatus.decode(outcome.invite.status), outcome.invite.expiresAt.utc(), outcome.delivery),
            ).build()
            is MemberInviteOutcome.RateLimited -> rateLimited(outcome.retryAfterSeconds)
            is HttpFailure -> httpError(outcome)
        }

    // Member-invite management (cr-0082) — owner-gated tracking of pending member invites: list / re-issue link /
    // revoke. All @Authenticated (not Server-Owner); the OWNER-membership check lives in the handler (403).
    @Authenticated
    override fun listMemberInvites(tenantId: UUID): Response =
        when (val outcome = handler.listMemberInvites(identity.principal.name, tenantId.toString())) {
            is MemberInvitesOutcome.Listed -> Response.ok(
                outcome.invites.map {
                    PendingMemberInvite(UUID.fromString(it.inviteId), it.email, InviteStatus.decode(it.status), it.expiresAt.utc())
                },
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    @Authenticated
    @RateLimit(key = RateLimitKey.RECIPIENT) // same per-(tenant,recipient) mail bound as the first mint
    override fun reissueMemberInvite(tenantId: UUID, inviteId: UUID, memberInviteRequest: MemberInviteRequest): Response =
        when (val outcome = handler.reissueMemberInvite(identity.principal.name, tenantId.toString(), inviteId.toString(), memberInviteRequest.toInput())) {
            is ReissueOutcome.Done -> Response.ok(
                MemberInviteState(outcome.invite.ownerEmail, InviteStatus.decode(outcome.invite.status), outcome.invite.expiresAt.utc(), outcome.delivery),
            ).build()
            is ReissueOutcome.RateLimited -> rateLimited(outcome.retryAfterSeconds)
            is HttpFailure -> httpError(outcome)
        }

    @Authenticated
    override fun revokeMemberInvite(tenantId: UUID, inviteId: UUID): Response =
        when (val o = handler.revokeMemberInvite(identity.principal.name, tenantId.toString(), inviteId.toString())) {
            RevokeMemberInviteOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // Remove a member (cr-0091): the ACL-revoke cascade + orphan rule. @Authenticated (not Server-Owner); the
    // owner-gate + last-owner guard live in the handler (403 not_tenant_owner / 409 last_owner).
    @Authenticated
    override fun removeTenantMember(tenantId: UUID, userId: UUID, revokeLinks: Boolean?): Response =
        when (val o = handler.removeMember(identity.principal.name, tenantId.toString(), userId.toString(), revokeLinks == true)) {
            RemoveTenantMemberOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // "Make owner" (ADR 0101 decision 9): @Authenticated (not Server-Owner); membership- then owner-gated in the handler.
    @Authenticated
    override fun setTenantMemberRole(tenantId: UUID, userId: UUID, setTenantMemberRoleRequest: SetTenantMemberRoleRequest): Response =
        when (val o = handler.setMemberRole(identity.principal.name, tenantId.toString(), userId.toString(), setTenantMemberRoleRequest.role.value)) {
            SetTenantRoleOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // A member's sealed picture (ADR 0084): @Authenticated, membership-gated in the handler (a non-member: 404, ADR 0042).
    // `Cache-Control: private` - the answer is for this signed-in member, never a shared cache.
    @Authenticated
    override fun getMemberAvatar(tenantId: UUID, userId: UUID, ifNoneMatch: String?): Response =
        when (val o = avatarHandler.read(identity.principal.name, tenantId.toString(), userId.toString(), AvatarETag.parse(ifNoneMatch))) {
            is MemberAvatarOutcome.Found -> Response.ok(MemberAvatar(o.avatar.value, o.avatar.wrappedValueKey))
                .header("ETag", AvatarETag.of(o.revision)).header("Cache-Control", "private").build()
            is MemberAvatarOutcome.NotModified -> Response.notModified().header("ETag", AvatarETag.of(o.revision)).header("Cache-Control", "private").build()
            is HttpFailure -> httpError(o)
        }

    // The members' verifications of each other (ADR 0111 decisions 12-13): @Authenticated, membership-gated in the handler
    // (a non-member: 404, ADR 0042). The verifier of a put is the caller, never a field of the request.
    @Authenticated
    override fun listVerifications(
        tenantId: UUID,
        subjectUserId: UUID?,
        verifierUserId: UUID?,
        after: String?,
        limit: Int?,
    ): Response =
        when (
            val o = verificationHandler.list(
                identity.principal.name, tenantId.toString(), subjectUserId?.toString(), verifierUserId?.toString(),
                TenantVerificationHandler.parseAfter(after), (limit ?: TenantVerificationHandler.PAGE_CAP).coerceIn(1, TenantVerificationHandler.PAGE_CAP),
            )
        ) {
            is ListVerificationsOutcome.Listed -> Response.ok(
                VerificationPage(
                    o.page.rows.map {
                        MemberVerification(
                            UUID.fromString(it.verifierUserId), UUID.fromString(it.subjectUserId), it.epoch, it.sealedStatement,
                            it.wrappedValueKey, it.createdAt.utc(),
                        )
                    },
                    o.page.more,
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun putMyVerification(tenantId: UUID, subjectUserId: UUID, putVerificationRequest: PutVerificationRequest): Response =
        when (
            val o = verificationHandler.put(
                identity.principal.name, tenantId.toString(), subjectUserId.toString(),
                putVerificationRequest.epoch.validated(),
                SealedValue(putVerificationRequest.sealedStatement, putVerificationRequest.wrappedValueKey),
            )
        ) {
            PutVerificationOutcome.Stored -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    // --- Slice B (cr-0010): invite acceptance / activation. Method-level annotations override the class
    // @RolesAllowed: preview + register are @PermitAll (secret-gated), accept is @Authenticated (any user;
    // the email-match is a domain check, not the Server-Owner role).
    @PermitAll
    @RateLimit(key = RateLimitKey.IP) // unauthenticated bootstrap-secret lookup oracle — per-IP bound it
    override fun previewTenantInvite(acceptPreviewRequest: AcceptPreviewRequest): Response =
        when (val outcome = acceptHandler.preview(acceptPreviewRequest.bootstrapSecret)) {
            is PreviewOutcome.Found -> Response.ok(
                InvitePreview(
                    tenantId = UUID.fromString(outcome.tenantId),
                    tenantName = outcome.tenantName,
                    domain = outcome.domain,
                    ownerEmail = outcome.ownerEmail,
                    hasAccount = outcome.hasAccount,
                    role = InviteRole.decode(outcome.role),
                    handover = outcome.handover,
                    invite = outcome.invite?.served(outcome.heads, outcome.signerChain, outcome.successions),
                ),
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    // A member accepts an owner invite into an ACTIVE tenant (ADR 0101 decision 9): @Authenticated, any user; the
    // handler binds it to the invited address and to the tenant's membership.
    @Authenticated
    override fun acceptTenantOwnership(acceptOwnershipRequest: AcceptOwnershipRequest): Response =
        when (val o = acceptHandler.acceptOwnership(identity.principal.name, acceptOwnershipRequest.bootstrapSecret)) {
            AcceptOwnershipOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun acceptTenantInvite(acceptInviteRequest: AcceptInviteRequest): Response {
        val r = acceptInviteRequest
        // A sealed field and its value key's wrap come together, under a stated epoch (ADR 0115, the contract's rule).
        if (!profileShapeHolds(r.sealedJoinRecord, r.wrappedJoinRecordValueKey, r.sealedName, r.wrappedNameValueKey, r.sealedAvatar, r.wrappedAvatarValueKey, r.epoch)) {
            return httpError(AcceptOutcome.MissingGrant)
        }
        return when (val outcome = acceptHandler.accept(
            identity.principal.name,
            r.bootstrapSecret,
            with(r) { OwnerBirth.of(grant, grantSignature, recipientIdentityDigest) },
            with(r) { selfGrant.join(inviteProof, SealedValue.of(sealedJoinRecord, wrappedJoinRecordValueKey), signerIdentityDigest) },
            r.username,
            SealedValue.of(r.sealedName, r.wrappedNameValueKey),
            SealedValue.of(r.sealedAvatar, r.wrappedAvatarValueKey),
            r.epoch,
        )) {
            is AcceptOutcome.Activated ->
                Response.ok(TenantActivated(UUID.fromString(outcome.tenantId), TenantStatus.ACTIVE)).build()
            is HttpFailure -> httpError(outcome)
        }
    }

    @PermitAll
    @RateLimit(key = RateLimitKey.IP) // unauthenticated account creation — per-IP bound it
    override fun registerAndAcceptTenantInvite(registerAndAcceptRequest: RegisterAndAcceptRequest): Response {
        val a = registerAndAcceptRequest.account
        val account = NewAccount(
            suiteId = a.suiteId.value,
            identityPublicKey = a.identityPublicKey,
            wrappedIdentityPrivateKey = a.wrappedIdentityPrivateKey,
            authPublicKey = a.authPublicKey,
            secretKeyProofPublicKey = a.secretKeyProofPublicKey,
            wraps = a.masterKeyWraps.map { WrapData(it.method.value, it.wrappedKey, it.kdfParams) },
        )
        val r = registerAndAcceptRequest
        if (!profileShapeHolds(r.sealedJoinRecord, r.wrappedJoinRecordValueKey, r.sealedName, r.wrappedNameValueKey, r.sealedAvatar, r.wrappedAvatarValueKey, r.epoch)) {
            return httpError(RegisterAcceptOutcome.MissingGrant)
        }
        return when (val outcome = acceptHandler.registerAndAccept(
            r.bootstrapSecret, r.userId.toString(), account,
            with(r) { OwnerBirth.of(grant, grantSignature, recipientIdentityDigest) },
            with(r) { selfGrant.join(inviteProof, SealedValue.of(sealedJoinRecord, wrappedJoinRecordValueKey), signerIdentityDigest) },
            r.username, SealedValue.of(r.sealedName, r.wrappedNameValueKey), SealedValue.of(r.sealedAvatar, r.wrappedAvatarValueKey),
            r.epoch,
        )) {
            is RegisterAcceptOutcome.Activated -> Response.status(Response.Status.CREATED)
                .entity(TenantActivatedWithUser(UUID.fromString(outcome.tenantId), TenantStatus.ACTIVE, UUID.fromString(outcome.userId)))
                .build()
            is HttpFailure -> httpError(outcome)
        }
    }

    // --- Terms of service (cr-0114, ADR 0035). All @Authenticated (not Server-Owner): read/accept are
    // member-facing, update is tenant-owner-gated in the handler (403 not_tenant_owner). A non-member read/accept
    // is 404 (indistinguishable). ---
    @Authenticated
    override fun getTenantTerms(tenantId: UUID): Response =
        when (val o = termsHandler.read(identity.principal.name, tenantId.toString())) {
            is TermsReadOutcome.Ok -> Response.ok(TenantTerms(o.revision, o.accepted, o.section.toWire(), o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun updateTenantTerms(tenantId: UUID, tenantTermsUpdate: TenantTermsUpdate): Response =
        when (val o = termsHandler.update(
            identity.principal.name, tenantId.toString(), tenantTermsUpdate.terms.de, tenantTermsUpdate.terms.en,
        )) {
            is TermsUpdateOutcome.Ok -> Response.ok(TenantTerms(o.revision, o.accepted, o.section.toWire(), o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun acceptTenantTerms(tenantId: UUID, acceptTermsRequest: AcceptTermsRequest): Response =
        when (val o = termsHandler.accept(
            identity.principal.name, tenantId.toString(), acceptTermsRequest.revision, acceptTermsRequest.operatorRevision,
        )) {
            is TermsAcceptOutcome.Ok -> Response.ok(TenantTerms(o.revision, o.accepted, o.section.toWire(), o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    private fun TermsContent.toDto() = LocalizedMarkdown(de, en)

    // --- The tenant level of the capability register (ADR 0088 decisions 2, 3, 12). @Authenticated (not Server-Owner):
    // the effective set is every member's; the switches are tenant-owner-gated in the handler (403 not_tenant_owner;
    // a non-member 404). ---
    @Authenticated
    override fun getTenantCapabilities(tenantId: UUID): Response =
        when (val o = capabilitiesHandler.effective(identity.principal.name, tenantId.toString())) {
            is TenantCapabilitiesOutcome.Ok -> Response.ok(
                TenantCapabilities(
                    capabilities = o.ids.map { capabilityWire(it, o.requirements, o.services) },
                    applicationErasures = o.erasures.map { ApplicationErasure(id = it.application, dueAt = it.dueAt.utc()) },
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun listTenantCapabilitySwitches(tenantId: UUID): Response =
        when (val o = capabilitiesHandler.list(identity.principal.name, tenantId.toString())) {
            is TenantCapabilitySwitchesOutcome.Ok -> Response.ok(o.entries.map { it.toDto() }).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun setTenantCapabilitySwitch(
        tenantId: UUID, capabilityId: String, tenantCapabilitySwitchUpdate: TenantCapabilitySwitchUpdate,
    ): Response =
        when (val o = capabilitiesHandler.set(
            identity.principal.name, tenantId.toString(), capabilityId, tenantCapabilitySwitchUpdate.off.validated(),
        )) {
            is TenantCapabilitySetOutcome.Stored -> Response.ok(o.view.toDto()).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun setTenantCapabilityRequirement(
        tenantId: UUID, capabilityId: String, requirement: String, tenantCapabilityRequirementUpdate: TenantCapabilityRequirementUpdate,
    ): Response = requirementAnswer(
        capabilitiesHandler.setRequirement(
            identity.principal.name, tenantId.toString(), capabilityId, requirement, tenantCapabilityRequirementUpdate.value,
        ),
    )

    @Authenticated
    override fun clearTenantCapabilityRequirement(tenantId: UUID, capabilityId: String, requirement: String): Response =
        requirementAnswer(capabilitiesHandler.setRequirement(identity.principal.name, tenantId.toString(), capabilityId, requirement, null))

    private fun requirementAnswer(o: TenantCapabilitySetOutcome): Response = when (o) {
        is TenantCapabilitySetOutcome.Stored -> Response.ok(o.view.toDto()).build()
        is HttpFailure -> httpError(o)
    }

    private fun TenantCapabilityView.toDto() = TenantCapabilitySwitch(
        id = state.id,
        title = texts.title.toDto(), purpose = texts.purpose.toDto(), whenOff = texts.whenOff.toDto(),
        off = state.off, serverOff = state.serverOff, effective = state.effective,
        requirements = requirements.map {
            TenantCapabilityRequirement(
                name = it.def.name, kind = TenantCapabilityRequirement.Kind.valueOf(it.def.kind.name.lowercase()),
                title = it.def.title.toDto(), whenSet = it.def.whenSet.toDto(),
                value = it.value, serverValue = it.serverValue, effective = it.effective,
            )
        },
        erasureDueAt = erasureDueAt?.utc(),
    )

    private fun me.raddatz.veyq.core.platform.capabilities.CapabilityText.toDto() = CapabilityText(en = en, de = de)

    // --- Mail settings (cr-email-smtp-transport, ADR 0048/0049/0050). @Authenticated (not Server-Owner): both
    // owner-gated in the handler (403 not_tenant_owner; a non-member 404). The SMTP password is write-only —
    // supplied on update, never returned (a read exposes only passwordSet, ADR 0050). ---
    @Authenticated
    override fun getTenantMailSettings(tenantId: UUID): Response =
        when (val o = mailSettingsHandler.read(identity.principal.name, tenantId.toString())) {
            is MailSettingsReadOutcome.Ok -> Response.ok(o.settings.toDto()).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun updateTenantMailSettings(tenantId: UUID, tenantMailSettingsUpdate: TenantMailSettingsUpdate): Response =
        when (
            val o = mailSettingsHandler.update(
                callerId = identity.principal.name,
                tenantId = tenantId.toString(),
                host = tenantMailSettingsUpdate.host,
                port = tenantMailSettingsUpdate.port,
                from = tenantMailSettingsUpdate.from,
                username = tenantMailSettingsUpdate.username,
                password = tenantMailSettingsUpdate.password,
                tls = tenantMailSettingsUpdate.tls.validated(),
                enabled = tenantMailSettingsUpdate.enabled.validated(),
                subjectTemplate = tenantMailSettingsUpdate.subjectTemplate,
                bodyTemplate = tenantMailSettingsUpdate.bodyTemplate,
            )
        ) {
            is MailSettingsUpdateOutcome.Ok -> Response.ok(o.settings.toDto()).build()
            is HttpFailure -> httpError(o)
        }

    private fun MailSettingsView.toDto() = TenantMailSettings(
        tls = tls,
        enabled = enabled,
        passwordSet = passwordSet,
        configSecretState = when (configSecretState) {
            ConfigSecretKeyState.OK -> TenantMailSettings.ConfigSecretState.OK
            ConfigSecretKeyState.ABSENT -> TenantMailSettings.ConfigSecretState.ABSENT
            ConfigSecretKeyState.MISMATCH -> TenantMailSettings.ConfigSecretState.MISMATCH
        },
        host = host,
        port = port,
        from = from,
        username = username,
        subjectTemplate = subjectTemplate,
        bodyTemplate = bodyTemplate,
    )

    // --- Storage (uc.sharing.share-link-storage-limit, ADR 0073 §4). @Authenticated (not Server-Owner): both
    // owner-gated in the handler, exactly like the mail settings (403 not_tenant_owner; a non-member 404). ---
    @Authenticated
    override fun getTenantStorage(tenantId: UUID): Response =
        storageHandler.read(identity.principal.name, tenantId.toString()).toResponse()

    @Authenticated
    override fun updateTenantStorage(tenantId: UUID, tenantStorageUpdate: TenantStorageUpdate): Response =
        storageHandler.updateShareLinkLimit(identity.principal.name, tenantId.toString(), tenantStorageUpdate.shareLinkLimitBytes.validated())
            .toResponse()

    // The member's storage-fit preflight (ADR 0082): any member, yes or no. Its bound is the handler's per-member and
    // per-tenant `StorageFitRateLimiter` (a TENANT-keyed marker, handler-enforced), surfaced as 429 + Retry-After.
    @Authenticated
    @RateLimit(key = RateLimitKey.TENANT)
    override fun checkTenantStorageFit(tenantId: UUID, tenantStorageFitRequest: TenantStorageFitRequest): Response =
        when (val outcome = storageHandler.fit(identity.principal.name, tenantId.toString(), tenantStorageFitRequest.bytes.validated())) {
            is StorageFitOutcome.Ok -> Response.ok(TenantStorageFit(outcome.fits)).build()
            is StorageFitOutcome.RateLimited -> rateLimited(outcome.retryAfterSeconds)
            is HttpFailure -> httpError(outcome)
        }

    private fun TenantStorageOutcome.toResponse(): Response = when (this) {
        is TenantStorageOutcome.Ok -> Response.ok(TenantStorage(usedBytes = storage.usedBytes, quotaBytes = storage.quotaBytes, shareLinkLimitBytes = storage.shareLinkLimitBytes)).build()
        is HttpFailure -> httpError(this)
    }

    // The tenant's quota (uc.admin.tenant-storage-quota, ADR 0073 §1): Server-Owner-gated by the class-level
    // @RolesAllowed and answered only on the admin listener (`x-veyq-channel: admin`). No tenant-owner route writes it.
    // "No quota" is its own operation (inbox #304): a set without its number is a 400, never a clear.
    override fun setTenantStorageQuota(tenantId: UUID, tenantStorageQuotaUpdate: TenantStorageQuotaUpdate): Response =
        storageHandler.setQuota(tenantId.toString(), tenantStorageQuotaUpdate.quotaBytes.validated()).toResponse()

    override fun clearTenantStorageQuota(tenantId: UUID): Response =
        storageHandler.setQuota(tenantId.toString(), null).toResponse()

    private fun SetQuotaOutcome.toResponse(): Response = when (this) {
        SetQuotaOutcome.Done -> Response.noContent().build()
        is HttpFailure -> httpError(this)
    }


    private fun InviteOutcome.toResponse(): Response = when (this) {
        is InviteOutcome.Done -> Response.ok(
            TenantInviteState(invite.ownerEmail, InviteStatus.decode(invite.status), invite.expiresAt.utc(), invite.bootstrapSecret!!, delivery),
        ).build()
        is HttpFailure -> httpError(this)
    }

    /** 429 + `Retry-After` for an invite-mail rate-limit hit — same shape the AuthRateLimitFilter emits, but
     *  driven by the handler's per-(tenant,recipient) limiter (cr-email-invite-send-hardening). */
    private fun rateLimited(retryAfterSeconds: Long): Response =
        Response.fromResponse(httpError(Failures.rateLimited)).header("Retry-After", retryAfterSeconds).build()

    private fun TenantView.toSummary() = TenantSummary(
        tenantId = UUID.fromString(id),
        name = name,
        domain = domain,
        ownerEmail = ownerEmail,
        status = TenantStatus.decode(status),
        inviteStatus = inviteStatus?.let { InviteStatus.decode(it) },
        inviteExpiresAt = inviteExpiresAt?.utc(),
        storageUsedBytes = storageUsedBytes,
        storageQuotaBytes = storageQuotaBytes,
        ownerless = ownerless,
        deletionDueAt = deletionDueAt?.utc(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}

/** A member invite request as the handler takes it (ADR 0111 decisions 1-2). */
private fun MemberInviteRequest.toInput() = MemberInviteInput(
    email = email,
    inviteId = inviteId.toString(),
    invitePublicKey = invitePublicKey,
    inviteKeySignature = inviteKeySignature,
    expiresAt = expiresAt.toInstant(),
    sealedLink = sealedLink,
    epoch = epoch.validated(),
    grant = SignedGrant(wrappedGrant, grantSignature),
    recipientIdentityDigest = recipientIdentityDigest,
)

/** A MEMBER accept's parts, or null when any is missing (the handler answers it as a missing grant — a MEMBER accept
 *  without `signerIdentityDigest` among them, ADR 0111 decision 20). */
private fun InviteSelfGrant?.join(inviteProof: String?, joinRecord: SealedValue?, signerIdentityDigest: String?): MemberJoin? =
    MemberJoin.of(
        this?.wrappedGrant, this?.grantSignature, this?.epoch, this?.recipientIdentityDigest, inviteProof, joinRecord,
        signerIdentityDigest,
    )

/**
 * Whether an accept's sealed profile fields have the contract's shape (`dependentSchemas` on both accept bodies): each
 * comes with its value key's wrap, or neither does, and any of them brings the `epoch` its wrap is under (ADR 0115). A
 * `null` is the field's absence. A join record without its epoch is refused here even when the join around it is
 * incomplete — the schema states it so, and a schema-valid body is one this check passes.
 */
private fun profileShapeHolds(
    joinRecord: String?, joinRecordWrap: String?, name: String?, nameWrap: String?, avatar: String?, avatarWrap: String?,
    epoch: Int?,
): Boolean =
    SealedValue.pairs(joinRecord, joinRecordWrap) && SealedValue.pairs(name, nameWrap) && SealedValue.pairs(avatar, avatarWrap) &&
        (epoch != null || listOfNotNull(joinRecord, name, avatar).isEmpty())

/** A live MEMBER invite as its invitee reads it in the preview — null when it holds no key (none to check): its key,
 *  signer chain, anchor, carried grant and the succession statements between the two (ADR 0111 decision 19, ADR 0115
 *  decision 5). */
private fun TenantInviteEntity.served(
    heads: Map<String, String>, signerChain: List<KeyChainLink>, successions: List<ScopeSuccession>,
): ServedMemberInvite? = ServedMemberInvite(
    inviteId = UUID.fromString(id),
    invitePublicKey = invitePublicKey ?: return null,
    inviteKeySignature = inviteKeySignature ?: return null,
    expiresAt = expiresAt.atOffset(java.time.ZoneOffset.UTC),
    signerUserId = UUID.fromString(signerUserId ?: return null),
    signerIdentityPublicKey = signerIdentityPublicKey ?: return null,
    wrappedGrant = wrappedGrant ?: return null,
    grantedEpoch = grantedEpoch ?: return null,
    grantSignature = grantSignature ?: return null,
    granterUserId = UUID.fromString(granterUserId ?: return null),
    granterIdentityPublicKey = granterIdentityPublicKey ?: return null,
    signerKeyChain = signerChain.ifEmpty { return null }.map {
        KeyChainStep(
            index = it.index, identityPublicKey = it.identityPublicKey, secretKeyProofPublicKey = it.secretKeyProofPublicKey,
            createdAt = it.createdAt.atOffset(java.time.ZoneOffset.UTC), previousHead = it.previousHead,
            identitySignature = it.identitySignature, proofSignature = it.proofSignature,
        )
    },
    anchorWrappedGrant = anchorWrappedGrant ?: return null,
    anchorEpoch = anchorEpoch ?: return null,
    anchorGrantSignature = anchorGrantSignature ?: return null,
    granterKeyChainHead = heads[granterUserId] ?: return null,
    successions = successions.map { it.toWire() },
)
