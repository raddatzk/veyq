package com.veyq.app.features.admin.tenant

import com.veyq.app.generated.api.TenantsApi
import com.veyq.app.generated.model.AcceptInviteRequest
import com.veyq.app.generated.model.AcceptPreviewRequest
import com.veyq.app.generated.model.AcceptTermsRequest
import com.veyq.app.generated.model.ChangeDomainRequest
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.InviteRole
import com.veyq.app.generated.model.InviteStatus
import com.veyq.app.generated.model.LocalizedMarkdown
import com.veyq.app.generated.model.MemberInviteRequest
import com.veyq.app.generated.model.MemberInviteState
import com.veyq.app.generated.model.PendingMemberInvite
import com.veyq.app.generated.model.ReassignOwnerRequest
import com.veyq.app.generated.model.InvitePreview
import com.veyq.app.generated.model.RegisterAndAcceptRequest
import com.veyq.app.generated.model.TenantMailSettings
import com.veyq.app.generated.model.TenantMailSettingsUpdate
import com.veyq.app.generated.model.TenantActivated
import com.veyq.app.generated.model.TenantActivatedWithUser
import com.veyq.app.generated.model.TenantCreated
import com.veyq.app.generated.model.TenantInviteState
import com.veyq.app.generated.model.TenantStatus
import com.veyq.app.generated.model.TenantStorage
import com.veyq.app.generated.model.TenantStorageQuotaUpdate
import com.veyq.app.generated.model.TenantStorageUpdate
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.generated.model.TenantTerms
import com.veyq.app.generated.model.TenantTermsUpdate
import com.veyq.app.shared.membership.Admission
import com.veyq.app.shared.crypto.ConfigSecretKeyState
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.identity.ServerRoles.SERVER_OWNER
import com.veyq.app.shared.identity.WrapData
import com.veyq.app.shared.ratelimit.RateLimit
import com.veyq.app.shared.ratelimit.RateLimitExempt
import com.veyq.app.shared.ratelimit.RateLimitKey
import com.veyq.app.shared.validation.validated
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

    // Suspend / reactivate (uc.admin.suspend-tenant, ADR 0066): Server-Owner-gated by the class-level
    // @RolesAllowed, exactly like revokeTenant. Note these two are themselves subject to TenantSuspensionFilter,
    // which guards the tenant's own domain: reaching them **there** is refused like any other request, which is the
    // deliberate reading of the use-case's E1 — Server-Owner status is global, it is not a per-tenant exemption.
    // The Server Owner reactivates from the server-admin surface (ADR 0065), which is not that domain.
    override fun suspendTenant(tenantId: UUID): Response =
        when (val o = handler.suspend(tenantId.toString())) {
            SuspendOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun reactivateTenant(tenantId: UUID): Response =
        when (val o = handler.reactivate(tenantId.toString())) {
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
        when (val outcome = handler.inviteMember(
            identity.principal.name, tenantId.toString(), memberInviteRequest.email,
            Admission(memberInviteRequest.admissionId.toString(), memberInviteRequest.admissionEpoch.validated(), memberInviteRequest.admissionTag),
        )) {
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
                    PendingMemberInvite(
                        UUID.fromString(it.inviteId), it.email, InviteStatus.decode(it.status), it.expiresAt.utc(),
                        admissionId = it.admission?.let { a -> UUID.fromString(a.id) },
                        admissionEpoch = it.admission?.epoch,
                        admissionTag = it.admission?.tag,
                    )
                },
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    @Authenticated
    @RateLimit(key = RateLimitKey.RECIPIENT) // same per-(tenant,recipient) mail bound as the first mint
    override fun reissueMemberInvite(tenantId: UUID, inviteId: UUID): Response =
        when (val outcome = handler.reissueMemberInvite(identity.principal.name, tenantId.toString(), inviteId.toString())) {
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
                ),
            ).build()
            is HttpFailure -> httpError(outcome)
        }

    @Authenticated
    override fun acceptTenantInvite(acceptInviteRequest: AcceptInviteRequest): Response =
        when (val outcome = acceptHandler.accept(
            identity.principal.name,
            acceptInviteRequest.bootstrapSecret,
            with(acceptInviteRequest) { OwnerBirth.of(grant, grantSignature, admissionId, admissionEpoch, admissionTag) },
            acceptInviteRequest.username,
            acceptInviteRequest.sealedName,
        )) {
            is AcceptOutcome.Activated ->
                Response.ok(TenantActivated(UUID.fromString(outcome.tenantId), TenantStatus.ACTIVE)).build()
            is HttpFailure -> httpError(outcome)
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
        return when (val outcome = acceptHandler.registerAndAccept(
            registerAndAcceptRequest.bootstrapSecret, registerAndAcceptRequest.userId.toString(), account,
            with(registerAndAcceptRequest) { OwnerBirth.of(grant, grantSignature, admissionId, admissionEpoch, admissionTag) },
            registerAndAcceptRequest.username, registerAndAcceptRequest.sealedName,
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
            is TermsReadOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun updateTenantTerms(tenantId: UUID, tenantTermsUpdate: TenantTermsUpdate): Response =
        when (val o = termsHandler.update(
            identity.principal.name, tenantId.toString(), tenantTermsUpdate.terms.de, tenantTermsUpdate.terms.en,
        )) {
            is TermsUpdateOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    @Authenticated
    override fun acceptTenantTerms(tenantId: UUID, acceptTermsRequest: AcceptTermsRequest): Response =
        when (val o = termsHandler.accept(identity.principal.name, tenantId.toString(), acceptTermsRequest.version)) {
            is TermsAcceptOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            is HttpFailure -> httpError(o)
        }

    private fun TermsContent.toDto() = LocalizedMarkdown(de, en)

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

    private fun TenantStorageOutcome.toResponse(): Response = when (this) {
        is TenantStorageOutcome.Ok -> Response.ok(TenantStorage(storage.usedBytes, storage.quotaBytes, storage.shareLinkLimitBytes)).build()
        is HttpFailure -> httpError(this)
    }

    // The tenant's quota (uc.admin.tenant-storage-quota, ADR 0073 §1): Server-Owner-gated by the class-level
    // @RolesAllowed and answered only on the admin listener (`x-veyq-channel: admin`). No tenant-owner route writes it.
    override fun setTenantStorageQuota(tenantId: UUID, tenantStorageQuotaUpdate: TenantStorageQuotaUpdate): Response =
        when (val o = storageHandler.setQuota(tenantId.toString(), tenantStorageQuotaUpdate.quotaBytes)) {
            SetQuotaOutcome.Done -> Response.noContent().build()
            is HttpFailure -> httpError(o)
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
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
