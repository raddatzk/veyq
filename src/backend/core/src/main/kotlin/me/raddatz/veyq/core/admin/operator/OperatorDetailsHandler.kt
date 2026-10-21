package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.admin.instance.CodeConfirmation
import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.terms.RecipientCountries
import me.raddatz.veyq.core.terms.RecipientKind
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** Where the notification address's confirmation stands, as the wire enum says it. */
enum class NotificationConfirmationState { NOT_SET, CODE_SENT, SEND_FAILED, UNCONFIRMED, CONFIRMED }

/** The operator details as the Server Owner reads them on the admin channel. */
data class OperatorDetailsView(
    val values: OperatorValues,
    val confirmation: NotificationConfirmationState,
    val confirmedAt: Instant?,
    /** Whether a code can be mailed at all: instance mail configured and the Server Owner's own address confirmed. */
    val instanceMailReady: Boolean,
    /** The recipients the instance knows of by its own configuration, for the console to offer ([RecipientSuggestions]). */
    val suggestions: List<RecipientSuggestion> = emptyList(),
)

sealed interface OperatorSetOutcome {
    data class Ok(val view: OperatorDetailsView) : OperatorSetOutcome

    /**
     * A recipient whose description does not fit its kind ([RecipientKind.accepts]): an OTHER one without one, or any
     * other kind with one; or whose country is a code of the right shape the JDK does not know ([RecipientCountries]).
     * The contract cannot say either field by field, so the handler does; nothing is stored.
     */
    data object RecipientMalformed : OperatorSetOutcome, HttpFailure by httpFailure("invalid_request", FailureStatus.BAD_REQUEST)
}

sealed interface OperatorConfirmOutcome {
    data class Ok(val view: OperatorDetailsView) : OperatorConfirmOutcome

    /** No code outstanding, expired, spent, sent to an address that is no longer the notification address, or not it. */
    data object CodeInvalid : OperatorConfirmOutcome, HttpFailure by httpFailure("operator_code_invalid", FailureStatus.CONFLICT)
}

/**
 * The instance contact, the provider identification and the supervisory authority (ADR 0093 § 11, ADR 0035
 * decisions 6–7, uc.admin.operator-details): what `/api/instance/operator` and its `notification/confirmation` do, and
 * what the public `GET /api/operator` reads.
 *
 * **Nothing is required and nothing warns** (ADR 0093 § 14): every value may stay empty, and the fixed texts render an
 * empty one as "not stated by this operator"; the contract bounds each value and refuses a malformed one before this runs.
 *
 * **The notification address is confirmed by a mailed code** (ADR 0097 decision 5, owner 2026-10-17), under the rule
 * every confirmed address shares ([CodeConfirmation]): confirmed means a code confirmed the address stored NOW, so a
 * save that changes it unconfirms without touching the confirmation, and the old address saved again is confirmed
 * again. A save that leaves the address set and unconfirmed mints a code for it — only while instance mail is ready
 * ([InstanceNoticeMailer.ready]); the code is stored hashed before the send, and the send runs after the save's own
 * transaction committed, so a slow relay holds no connection.
 *
 * **The notification address never leaves the admin channel**: [publicValues] is the one read the public operation
 * serves, and it carries no field for it.
 */
@ApplicationScoped
class OperatorDetailsHandler(
    private val store: OperatorDetailsStore,
    private val mailer: InstanceNoticeMailer,
    private val suggestions: RecipientSuggestions,
) {

    fun read(now: Instant = Instant.now()): OperatorDetailsView =
        view(QuarkusTransaction.joiningExisting().call { store.find() }, now)

    /** The values `GET /api/operator` serves: the published ones only. */
    fun publicValues(): OperatorValues =
        (QuarkusTransaction.joiningExisting().call { store.find() }?.values ?: OperatorValues()).copy(notificationEmail = null)

    /**
     * A full replace of the values (a value omitted is cleared); then a code for an unconfirmed notification address.
     * Refused whole, storing nothing, where a recipient's description does not fit its kind or its country is no code
     * the JDK knows.
     */
    fun set(values: OperatorValues, now: Instant = Instant.now()): OperatorSetOutcome {
        val clean = values.cleaned()
        if (clean.recipients.any { !it.kind.accepts(it.description) || !RecipientCountries.known(it.country) }) {
            return OperatorSetOutcome.RecipientMalformed
        }
        val ready = mailer.ready(now)
        val minted = QuarkusTransaction.requiringNew().call {
            val e = store.lockedRow()
            e.contactEmail = clean.contactEmail
            e.contactLanguages = clean.contactLanguages.takeIf { it.isNotEmpty() }?.joinToString(",")
            e.statementEmail = clean.statementEmail
            e.notificationEmail = clean.notificationEmail
            e.providerName = clean.providerName
            e.providerAddress = clean.providerAddress
            e.providerEmail = clean.providerEmail
            e.providerRepresentative = clean.providerRepresentative
            e.supervisoryAuthority = clean.supervisoryAuthority
            e.dataProtectionOfficer = clean.dataProtectionOfficer
            store.replaceRecipients(clean.recipients)
            // A failed send describes the last save's mail; this save is a new attempt, or none.
            e.codeFailed = false
            val address = clean.notificationEmail
            if (address == null || CodeConfirmation.confirmed(e.confirmedAt, e.confirmedAddress, address) || !ready) {
                return@call null
            }
            val code = CodeConfirmation.newCode()
            e.codeHash = Tokens.hash(code)
            e.codeAddress = address
            e.codeExpiresAt = now.plus(CodeConfirmation.CODE_TTL)
            e.codeAttempts = 0
            Minted(address, code, e.codeHash!!, e.codeExpiresAt!!)
        }
        if (minted != null && mailer.sendOperatorCode(minted.address, minted.code, minted.expiresAt, now) != DeliveryResult.SENT) {
            QuarkusTransaction.requiringNew().run { store.markCodeFailed(minted.hash) }
        }
        return OperatorSetOutcome.Ok(read(now))
    }

    /** Clear every value, the notification address with its code and confirmation included. */
    fun clear(now: Instant = Instant.now()): OperatorDetailsView {
        QuarkusTransaction.requiringNew().run {
            val e = store.lockedRow()
            e.contactEmail = null
            e.contactLanguages = null
            e.statementEmail = null
            e.notificationEmail = null
            e.providerName = null
            e.providerAddress = null
            e.providerEmail = null
            e.providerRepresentative = null
            e.supervisoryAuthority = null
            e.dataProtectionOfficer = null
            store.replaceRecipients(emptyList())
            e.codeHash = null
            e.codeAddress = null
            e.codeExpiresAt = null
            e.codeAttempts = 0
            e.codeFailed = false
            e.confirmedAddress = null
            e.confirmedAt = null
        }
        return read(now)
    }

    /**
     * Enter the code: it confirms only while live and only for the address it went to, which must still be the
     * notification address; a wrong entry counts against it and spends it at [CodeConfirmation.MAX_ATTEMPTS].
     */
    fun confirm(code: String, now: Instant = Instant.now()): OperatorConfirmOutcome {
        val confirmed = QuarkusTransaction.requiringNew().call {
            val e = store.lockedRow()
            val live = CodeConfirmation.codeLive(e.codeHash, e.codeExpiresAt, e.codeAttempts, e.codeAddress, e.notificationEmail, now)
            if (live && CodeConfirmation.matches(code, e.codeHash)) {
                e.confirmedAddress = e.codeAddress
                e.confirmedAt = now
                e.codeHash = null
                e.codeAddress = null
                e.codeExpiresAt = null
                e.codeAttempts = 0
                true
            } else {
                if (e.codeHash != null) {
                    e.codeAttempts += 1
                    if (e.codeAttempts >= CodeConfirmation.MAX_ATTEMPTS) {
                        e.codeHash = null
                        e.codeAddress = null
                        e.codeExpiresAt = null
                    }
                }
                false
            }
        }
        return if (confirmed) OperatorConfirmOutcome.Ok(read(now)) else OperatorConfirmOutcome.CodeInvalid
    }

    private fun view(row: OperatorRow?, now: Instant): OperatorDetailsView {
        val address = row?.values?.notificationEmail
        val state = when {
            row == null || address == null -> NotificationConfirmationState.NOT_SET
            CodeConfirmation.confirmed(row.confirmedAt, row.confirmedAddress, address) -> NotificationConfirmationState.CONFIRMED
            CodeConfirmation.codeLive(row.codeHash, row.codeExpiresAt, row.codeAttempts, row.codeAddress, address, now) ->
                NotificationConfirmationState.CODE_SENT
            row.codeFailed -> NotificationConfirmationState.SEND_FAILED
            else -> NotificationConfirmationState.UNCONFIRMED
        }
        return OperatorDetailsView(
            values = row?.values ?: OperatorValues(),
            confirmation = state,
            confirmedAt = row?.confirmedAt.takeIf { state == NotificationConfirmationState.CONFIRMED },
            instanceMailReady = mailer.ready(now),
            suggestions = suggestions.list(),
        )
    }

    private class Minted(val address: String, val code: String, val hash: String, val expiresAt: Instant)

    private fun OperatorValues.cleaned() = OperatorValues(
        contactEmail = contactEmail.blankToNull(),
        contactLanguages = contactLanguages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        statementEmail = statementEmail.blankToNull(),
        notificationEmail = notificationEmail.blankToNull(),
        providerName = providerName.blankToNull(),
        providerAddress = providerAddress.blankToNull(),
        providerEmail = providerEmail.blankToNull(),
        providerRepresentative = providerRepresentative.blankToNull(),
        supervisoryAuthority = supervisoryAuthority.blankToNull(),
        dataProtectionOfficer = dataProtectionOfficer.blankToNull(),
        // The contract refuses a blank name (`OperatorRecipient`'s @NotBlank) and a country that is no two capitals (its
        // @Pattern); a blank description is none.
        recipients = recipients.map {
            PublishedRecipient(it.name.trim(), it.kind, it.description.blankToNull(), it.country.blankToNull())
        },
    )

    private fun String?.blankToNull(): String? = this?.trim()?.takeUnless { it.isEmpty() }
}
