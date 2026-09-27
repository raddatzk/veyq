package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.ConfigSecretKeyGuard
import com.veyq.app.shared.crypto.ConfigSecretKeyState
import com.veyq.app.shared.crypto.ConfigSecretSeal
import com.veyq.app.shared.http.FailureStatus
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** A tenant's mail settings **as they leave the server** — the SMTP password redacted to [passwordSet] (ADR 0050).
 *  [configSecretState] is the server's config-secret KEK state so the owner sees honestly whether a stored password
 *  can actually be used (ADR 0050). */
data class MailSettingsView(
    val host: String?,
    val port: Int?,
    val from: String?,
    val username: String?,
    val tls: Boolean,
    val enabled: Boolean,
    val passwordSet: Boolean,
    val subjectTemplate: String?,
    val bodyTemplate: String?,
    val configSecretState: ConfigSecretKeyState,
)

/** Result of an owner reading a tenant's mail settings. */
sealed interface MailSettingsReadOutcome {
    data class Ok(val settings: MailSettingsView) : MailSettingsReadOutcome
    data object Suspended : MailSettingsReadOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : MailSettingsReadOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or the caller is not a member (indistinguishable)
    data object NotOwner : MailSettingsReadOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/** Result of an owner setting/replacing a tenant's mail settings. */
sealed interface MailSettingsUpdateOutcome {
    data class Ok(val settings: MailSettingsView) : MailSettingsUpdateOutcome
    data object Suspended : MailSettingsUpdateOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : MailSettingsUpdateOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MailSettingsUpdateOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    // A subject/body template referencing a {{tag}} outside the closed set (ADR 0049) — rejected, never stored.
    data object InvalidTemplate : MailSettingsUpdateOutcome, HttpFailure by httpFailure("invalid_mail_template", FailureStatus.BAD_REQUEST)
    // A password was supplied but no config-secret KEK could be loaded to seal it (ADR 0050) — we refuse rather than store it in the clear.
    data object SealUnavailable : MailSettingsUpdateOutcome, HttpFailure by httpFailure("mail_secret_unavailable", FailureStatus.SERVICE_UNAVAILABLE)
}

/**
 * Per-tenant mail settings (uc.admin.tenant-mail-settings, ADR 0048/0049/0050). The tenant **owner** configures
 * its own SMTP + the invite template as runtime settings — the same owner-gated CRUD shape as
 * [TenantTermsHandler] (membership-gate → `findTenant` → `isOwner` → store). Two invariants live here:
 *  - **The SMTP password is never stored, returned or logged in plaintext** (ADR 0050): a supplied password is
 *    sealed via [ConfigSecretSeal]; an omitted one keeps the stored ciphertext; a read exposes only `passwordSet`.
 *  - **The invite template interpolates only the closed variable set** (ADR 0049): a template referencing any
 *    other `{{tag}}` is rejected at write time and never stored, so veyq's machinery can never inject a sealed
 *    field into a mail. What the owner *types* is a consented, operator-visible disclosure (out of scope here).
 * Mail settings are owner-only, so **read is owner-gated too** (unlike terms, which members read).
 */
@ApplicationScoped
class TenantMailSettingsHandler(
    private val store: TenantStore,
    private val seal: ConfigSecretSeal,
    private val keyState: ConfigSecretKeyGuard,
    private val renderer: InviteMailRenderer,
    private val access: ScopeAccess,
) {

    /** Read the tenant's mail settings — owner-gated (a non-owner member is 403, a non-member 404). Redacts the
     *  password to `passwordSet`. A tenant that configured nothing reads back the empty default. Carries the
     *  server's config-secret KEK state so the owner sees whether a stored password can actually be used. */
    fun read(callerId: String, tenantId: String): MailSettingsReadOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(MailSettingsReadOutcome.NotFound, MailSettingsReadOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return MailSettingsReadOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MailSettingsReadOutcome.NotOwner
        val state = keyState.state()
        return MailSettingsReadOutcome.Ok(store.mailSettingsFor(tenantId)?.toView(state) ?: emptyView(state))
    }

    /**
     * The **owner** sets/replaces the mail settings. Membership-gated first (a non-member gets the same 404 as a
     * bogus tenant id, ADR 0042), then owner-gated. Validates the templates against the closed variable set, seals
     * a supplied password (keeping the stored one when omitted), and stores the row. The password is never
     * returned or logged.
     */
    @Transactional
    fun update(
        callerId: String,
        tenantId: String,
        host: String?,
        port: Int?,
        from: String?,
        username: String?,
        password: String?,
        tls: Boolean,
        enabled: Boolean,
        subjectTemplate: String?,
        bodyTemplate: String?,
    ): MailSettingsUpdateOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(MailSettingsUpdateOutcome.NotFound, MailSettingsUpdateOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return MailSettingsUpdateOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MailSettingsUpdateOutcome.NotOwner

        val subject = subjectTemplate.blankToNull()
        val body = bodyTemplate.blankToNull()
        if (renderer.unknownTags(subject).isNotEmpty() || renderer.unknownTags(body).isNotEmpty()) {
            return MailSettingsUpdateOutcome.InvalidTemplate
        }

        // Password: seal a freshly-supplied one (needs the config-secret KEK); keep the stored ciphertext when omitted.
        val existing = store.mailSettingsFor(tenantId)
        val supplied = password.blankToNull()
        val ciphertext: String?
        val nonce: String?
        if (supplied != null) {
            if (!seal.configured) return MailSettingsUpdateOutcome.SealUnavailable
            val sealed = seal.seal(supplied)
            ciphertext = sealed.ciphertext
            nonce = sealed.nonce
        } else {
            ciphertext = existing?.passwordCiphertext
            nonce = existing?.passwordNonce
        }

        val row = MailSettingsRow(
            host = host.blankToNull(),
            port = port,
            from = from.blankToNull(),
            username = username.blankToNull(),
            passwordCiphertext = ciphertext,
            passwordNonce = nonce,
            tls = tls,
            enabled = enabled,
            subjectTemplate = subject,
            bodyTemplate = body,
        )
        store.saveMailSettings(tenantId, row)
        // A freshly-sealed password was sealed under the CURRENT key: advance the stored fingerprint so a prior
        // MISMATCH clears to OK (the recovery path). A no-op when the fingerprint already matches.
        if (supplied != null) keyState.recordReseal()
        return MailSettingsUpdateOutcome.Ok(row.toView(keyState.state()))
    }

    private fun MailSettingsRow.toView(state: ConfigSecretKeyState) = MailSettingsView(
        host = host, port = port, from = from, username = username,
        tls = tls, enabled = enabled, passwordSet = passwordSet,
        subjectTemplate = subjectTemplate, bodyTemplate = bodyTemplate,
        configSecretState = state,
    )

    /** What a tenant that configured nothing reads back: transport unset, sending off, no password, defaults. */
    private fun emptyView(state: ConfigSecretKeyState) = MailSettingsView(
        host = null, port = null, from = null, username = null,
        tls = true, enabled = false, passwordSet = false,
        subjectTemplate = null, bodyTemplate = null,
        configSecretState = state,
    )

    private fun String?.blankToNull(): String? = this?.takeUnless { it.isBlank() }
}
