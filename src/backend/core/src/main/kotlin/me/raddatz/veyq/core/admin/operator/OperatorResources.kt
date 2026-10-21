package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.generated.api.InstanceOperatorApi
import me.raddatz.veyq.core.generated.api.OperatorApi
import me.raddatz.veyq.core.generated.model.InstanceOperator
import me.raddatz.veyq.core.generated.model.InstanceOperatorConfirmation
import me.raddatz.veyq.core.generated.model.InstanceOperatorUpdate
import me.raddatz.veyq.core.generated.model.OperatorDetails
import me.raddatz.veyq.core.generated.model.OperatorRecipientSuggestion
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.tenant.RequestTenant
import me.raddatz.veyq.core.terms.OperatorSections
import me.raddatz.veyq.core.terms.PrivacyNotices
import me.raddatz.veyq.core.terms.RecipientKind
import me.raddatz.veyq.core.terms.toWire
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import me.raddatz.veyq.core.generated.model.OperatorRecipient as WireRecipient
import me.raddatz.veyq.core.generated.model.OperatorRecipientKind as WireRecipientKind

/**
 * HTTP adapter for the Server Owner's operator details (uc.admin.operator-details). Implements the
 * generated [InstanceOperatorApi]; thin (ADR 0001). Every operation is admin-channel marked in the contract and gated
 * here by the class-level `@RolesAllowed(SERVER_OWNER)`: a missing token 401, a non-owner 403. The one operation with
 * something to guess, the notification code, carries its own `@RateLimit(USER)` on top of the handler's attempt limit,
 * as `confirmInstanceMail` does.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only reads and writes of one instance-wide row — the operator's published values, whose save " +
        "mails at most one code to the address " +
        "the owner typed, bounded by the SMTP timeout. There is no per-caller state to enumerate and no work an anonymous " +
        "caller could amplify; the role gate bounds the caller set to one account.",
)
class InstanceOperatorResource(
    private val details: OperatorDetailsHandler,
) : InstanceOperatorApi {

    override fun getInstanceOperator(): Response = Response.ok(details.read().toWire()).build()

    override fun setInstanceOperator(instanceOperatorUpdate: InstanceOperatorUpdate): Response =
        when (val outcome = details.set(instanceOperatorUpdate.toValues())) {
            is OperatorSetOutcome.Ok -> Response.ok(outcome.view.toWire()).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun clearInstanceOperator(): Response = Response.ok(details.clear().toWire()).build()

    @RateLimit(RateLimitKey.USER)
    override fun confirmOperatorNotification(instanceOperatorConfirmation: InstanceOperatorConfirmation): Response =
        when (val outcome = details.confirm(instanceOperatorConfirmation.code)) {
            is OperatorConfirmOutcome.Ok -> Response.ok(outcome.view.toWire()).build()
            is HttpFailure -> httpError(outcome)
        }

    private fun InstanceOperatorUpdate.toValues() = OperatorValues(
        contactEmail = contactEmail,
        contactLanguages = contactLanguages ?: emptyList(),
        statementEmail = statementEmail,
        notificationEmail = notificationEmail,
        providerName = providerName,
        providerAddress = providerAddress,
        providerEmail = providerEmail,
        providerRepresentative = providerRepresentative,
        supervisoryAuthority = supervisoryAuthority,
        dataProtectionOfficer = dataProtectionOfficer,
        recipients = recipients.orEmpty().map { PublishedRecipient(it.name, RecipientKind.valueOf(it.kind.name), it.description, it.country) },
    )

    private fun OperatorDetailsView.toWire() = InstanceOperator(
        contactEmail = values.contactEmail,
        contactLanguages = values.contactLanguages,
        statementEmail = values.statementEmail,
        notificationEmail = values.notificationEmail,
        notificationConfirmation = InstanceOperator.NotificationConfirmation.valueOf(confirmation.name),
        notificationConfirmedAt = confirmedAt?.atOffset(ZoneOffset.UTC),
        instanceMailReady = instanceMailReady,
        providerName = values.providerName,
        providerAddress = values.providerAddress,
        providerEmail = values.providerEmail,
        providerRepresentative = values.providerRepresentative,
        supervisoryAuthority = values.supervisoryAuthority,
        recipients = values.recipients.map { WireRecipient(name = it.name, kind = WireRecipientKind.valueOf(it.kind.name), description = it.description, country = it.country) },
        dataProtectionOfficer = values.dataProtectionOfficer,
        recipientSuggestions = suggestions.map {
            OperatorRecipientSuggestion(kind = OperatorRecipientSuggestion.Kind.valueOf(it.kind.name), host = it.host)
        },
    )
}

/**
 * HTTP adapter for the public read of who runs the instance (`GET /api/operator`, ADR 0093 § 11, ADR 0035 decision 7).
 * Implements the generated [OperatorApi]. Unauthenticated and instance-wide — the same answer on every host — and
 * built from [OperatorDetailsHandler.publicValues] alone, which carries no notification address: the wire model has no
 * field for it either, so it cannot leak through a mapping mistake here.
 */
@PermitAll
class OperatorResource(
    private val details: OperatorDetailsHandler,
    private val sections: OperatorSections,
    private val notices: PrivacyNotices,
    private val tenant: RequestTenant,
) : OperatorApi {

    /**
     * The instance privacy notice, public (ADR 0035 decision 8; GDPR Art. 13 applies at collection, before any
     * account): rendered for the space this host serves — its switches decide which passages run here — and for the
     * instance level on the bootstrap domain or an unknown host. Reading it records nothing and gates nothing.
     */
    @RateLimit(key = RateLimitKey.IP) // unauthenticated; read by the public notice page every surface links
    override fun getPrivacyNotice(): Response = Response.ok(notices.render(tenant.resolve()?.id).toWire()).build()

    /**
     * The operator section of the terms, public (ADR 0035 decision 6; DSA Art. 14(1) asks the terms to be publicly
     * available and easily accessible): Host-independent like the values, the guest-write record's pointer included.
     */
    @RateLimit(key = RateLimitKey.IP) // unauthenticated; read by the public terms page every login and share page links
    override fun getOperatorTerms(): Response = Response.ok(sections.render().toWire()).build()

    @RateLimit(key = RateLimitKey.IP) // unauthenticated; read by every login and share page
    override fun getOperatorDetails(): Response {
        val v = details.publicValues()
        return Response.ok(
            OperatorDetails(
                contactEmail = v.contactEmail,
                contactLanguages = v.contactLanguages,
                statementEmail = v.statementEmail,
                providerName = v.providerName,
                providerAddress = v.providerAddress,
                providerEmail = v.providerEmail,
                providerRepresentative = v.providerRepresentative,
                supervisoryAuthority = v.supervisoryAuthority,
            ),
        ).build()
    }
}
