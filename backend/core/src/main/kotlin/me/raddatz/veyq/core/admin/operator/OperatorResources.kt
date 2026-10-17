package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.generated.api.InstanceOperatorApi
import me.raddatz.veyq.core.generated.api.OperatorApi
import me.raddatz.veyq.core.generated.model.InstanceCapabilityText
import me.raddatz.veyq.core.generated.model.InstanceOperatingMode
import me.raddatz.veyq.core.generated.model.InstanceOperatingModePresetEntry
import me.raddatz.veyq.core.generated.model.InstanceOperatingModeUpdate
import me.raddatz.veyq.core.generated.model.InstanceOperator
import me.raddatz.veyq.core.generated.model.InstanceOperatorConfirmation
import me.raddatz.veyq.core.generated.model.InstanceOperatorUpdate
import me.raddatz.veyq.core.generated.model.OperatorDetails
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import me.raddatz.veyq.core.generated.model.OperatingMode as WireOperatingMode

/**
 * HTTP adapter for the Server Owner's operating mode and operator details (uc.admin.operator-details). Implements the
 * generated [InstanceOperatorApi]; thin (ADR 0001). Every operation is admin-channel marked in the contract and gated
 * here by the class-level `@RolesAllowed(SERVER_OWNER)`: a missing token 401, a non-owner 403. The one operation with
 * something to guess, the notification code, carries its own `@RateLimit(USER)` on top of the handler's attempt limit,
 * as `confirmInstanceMail` does.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only reads and writes of one instance-wide row — the operating mode with its preset (a fixed " +
        "register's few switches) and the operator's published values, whose save mails at most one code to the address " +
        "the owner typed, bounded by the SMTP timeout. There is no per-caller state to enumerate and no work an anonymous " +
        "caller could amplify; the role gate bounds the caller set to one account.",
)
class InstanceOperatorResource(
    private val details: OperatorDetailsHandler,
    private val modes: OperatingModeHandler,
) : InstanceOperatorApi {

    override fun getInstanceOperator(): Response = Response.ok(details.read().toWire()).build()

    override fun setInstanceOperator(instanceOperatorUpdate: InstanceOperatorUpdate): Response =
        Response.ok(details.set(instanceOperatorUpdate.toValues()).toWire()).build()

    override fun clearInstanceOperator(): Response = Response.ok(details.clear().toWire()).build()

    @RateLimit(RateLimitKey.USER)
    override fun confirmOperatorNotification(instanceOperatorConfirmation: InstanceOperatorConfirmation): Response =
        when (val outcome = details.confirm(instanceOperatorConfirmation.code)) {
            is OperatorConfirmOutcome.Ok -> Response.ok(outcome.view.toWire()).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun getInstanceOperatingMode(): Response = Response.ok(modes.read().toWire()).build()

    override fun setInstanceOperatingMode(instanceOperatingModeUpdate: InstanceOperatingModeUpdate): Response =
        Response.ok(modes.set(OperatingMode.valueOf(instanceOperatingModeUpdate.mode.value)).toWire()).build()

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
    )

    private fun OperatorDetailsView.toWire() = InstanceOperator(
        operatingMode = WireOperatingMode.valueOf(mode.name),
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
    )

    private fun OperatingModeView.toWire() = InstanceOperatingMode(
        mode = WireOperatingMode.valueOf(mode.name),
        chosen = chosen,
        preset = preset.map {
            InstanceOperatingModePresetEntry(
                id = it.id,
                title = InstanceCapabilityText(en = it.texts.title.en, de = it.texts.title.de),
                onInPrivate = it.preset.onInPrivate,
                onInOrganisation = it.preset.onInOrganisation,
                off = it.off,
            )
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
class OperatorResource(private val details: OperatorDetailsHandler) : OperatorApi {

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
