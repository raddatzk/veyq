package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant

/** Where the confirmation of ADR 0097 decision 5 stands, as the wire enum says it. */
enum class InstanceMailConfirmationState { NOT_CONFIGURED, CODE_SENT, SEND_FAILED, UNCONFIRMED, CONFIRMED }

/** The instance mail settings as they leave the server — the password redacted to [passwordSet] (ADR 0050). */
data class InstanceMailSettingsView(
    val host: String?,
    val port: Int?,
    val from: String?,
    val username: String?,
    val tls: Boolean,
    val passwordSet: Boolean,
    val configSecretState: ConfigSecretKeyState,
    val address: String,
    val confirmation: InstanceMailConfirmationState,
    val confirmedAt: Instant?,
)

sealed interface InstanceMailUpdateOutcome {
    data class Ok(val view: InstanceMailSettingsView) : InstanceMailUpdateOutcome

    /** A host without a from address: the instance has no address of its own to fall back on. Nothing is stored. */
    data object Invalid : InstanceMailUpdateOutcome, HttpFailure by Failures.invalidRequest

    /** A password was supplied but no config-secret KEK can seal it (ADR 0050); refused rather than stored in the clear. */
    data object SealUnavailable : InstanceMailUpdateOutcome, HttpFailure by httpFailure("mail_secret_unavailable", FailureStatus.SERVICE_UNAVAILABLE)
}

sealed interface InstanceMailConfirmOutcome {
    data class Ok(val view: InstanceMailSettingsView) : InstanceMailConfirmOutcome

    /** No code outstanding, expired, spent by wrong entries, sent to another address, or not it. Nothing is confirmed. */
    data object CodeInvalid : InstanceMailConfirmOutcome, HttpFailure by httpFailure("mail_code_invalid", FailureStatus.CONFLICT)
}

/**
 * The Server Owner's instance-mail confirmation, read by the console, the code entry and the mailer alike, so the
 * console never says "confirmed" about settings the mailer would refuse to send with. The rule itself is
 * [CodeConfirmation]'s, shared with every other confirmed address; this object only says which address is current —
 * the one the Server Owner's account has **now** — so a changed account address unconfirms by itself, without a write,
 * and a save starts over (it clears what a code confirmed).
 */
object InstanceMailConfirmation {
    val CODE_TTL: Duration get() = CodeConfirmation.CODE_TTL

    const val MAX_ATTEMPTS = CodeConfirmation.MAX_ATTEMPTS

    fun newCode(): String = CodeConfirmation.newCode()

    fun state(row: InstanceMailRow?, ownerAddress: String?, now: Instant): InstanceMailConfirmationState = when {
        row == null || row.transport.host == null -> InstanceMailConfirmationState.NOT_CONFIGURED
        CodeConfirmation.confirmed(row.confirmedAt, row.confirmedAddress, ownerAddress) -> InstanceMailConfirmationState.CONFIRMED
        row.testMailFailed -> InstanceMailConfirmationState.SEND_FAILED
        codeLive(row, ownerAddress, now) -> InstanceMailConfirmationState.CODE_SENT
        else -> InstanceMailConfirmationState.UNCONFIRMED
    }

    /** Whether [row]'s code may still be entered, for [ownerAddress]. */
    fun codeLive(row: InstanceMailRow, ownerAddress: String?, now: Instant): Boolean =
        CodeConfirmation.codeLive(row.codeHash, row.codeExpiresAt, row.codeAttempts, row.codeAddress, ownerAddress, now)

    /** The current address of the account [userId], or null when there is none. */
    fun addressOf(userId: String?): String? =
        userId?.let { id -> QuarkusTransaction.joiningExisting().call { VeyqUser.findById(id)?.email } }
}

/**
 * The instance mail settings (ADR 0097 decisions 3 and 5, uc.admin.learn-of-new-releases): what
 * `/api/instance/mail-settings` and its `confirmation` do. The same transport fields as a tenant's, sealed the same
 * way ([ConfigSecretSeal], with the sealing key's fingerprint) — and nothing shared with a tenant's settings: this
 * reads and writes its own row only, and nothing here is ever a tenant's default or fallback (ADR 0048 §4).
 *
 * The password is never stored, returned or logged in plaintext: a supplied one is sealed, an omitted one keeps the
 * stored ciphertext, a read exposes `passwordSet` only.
 */
@ApplicationScoped
class InstanceMailSettingsHandler(
    private val store: InstanceMailSettingsStore,
    private val seal: ConfigSecretSeal,
    private val keyState: ConfigSecretKeyGuard,
    private val mailer: InstanceNoticeMailer,
) {

    fun read(callerId: String, now: Instant = Instant.now()): InstanceMailSettingsView = view(store.find(), callerId, now)

    fun update(
        callerId: String,
        host: String?,
        port: Int?,
        from: String?,
        username: String?,
        password: String?,
        tls: Boolean,
        now: Instant = Instant.now(),
    ): InstanceMailUpdateOutcome {
        val cleanHost = host.blankToNull()
        val cleanFrom = from.blankToNull()
        if (cleanHost != null && cleanFrom == null) return InstanceMailUpdateOutcome.Invalid

        val existing = store.find()?.transport
        val supplied = password.blankToNull()
        val sealed = if (supplied != null) {
            val current = keyState.currentFingerprint()
            if (!seal.configured || current == null) return InstanceMailUpdateOutcome.SealUnavailable
            val s = seal.seal(supplied)
            Triple(s.ciphertext, s.nonce, current)
        } else {
            Triple(existing?.passwordCiphertext, existing?.passwordNonce, existing?.passwordFingerprint)
        }
        val transport = InstanceMailTransport(
            host = cleanHost, port = port, from = cleanFrom, username = username.blankToNull(),
            passwordCiphertext = sealed.first, passwordNonce = sealed.second, passwordFingerprint = sealed.third,
            tls = tls,
        )
        if (cleanHost == null) {
            // Cleared: no instance mail, nothing to confirm, nothing sent.
            store.save(transport, callerId, codeHash = null, codeAddress = null, codeExpiresAt = null)
            return InstanceMailUpdateOutcome.Ok(view(store.find(), callerId, now))
        }
        // Every save asks again (decision 5): a new code for the address the caller's account has now, stored hashed
        // before the send, and the send after the save's own transaction committed — a slow relay holds no connection.
        val address = InstanceMailConfirmation.addressOf(callerId)
        val code = InstanceMailConfirmation.newCode()
        val hash = Tokens.hash(code)
        val expiresAt = now.plus(InstanceMailConfirmation.CODE_TTL)
        store.save(transport, callerId, codeHash = hash, codeAddress = address, codeExpiresAt = expiresAt)
        val sent = address != null && mailer.sendCode(transport, address, code, expiresAt) == DeliveryResult.SENT
        if (!sent) store.markTestMailFailed(hash)
        return InstanceMailUpdateOutcome.Ok(view(store.find(), callerId, now))
    }

    /**
     * Enter the code the test mail carried. It confirms only while it is live (unexpired, not spent by wrong entries)
     * and only for the address it was sent to, which must still be the account's; a wrong entry counts against it.
     */
    fun confirm(callerId: String, code: String, now: Instant = Instant.now()): InstanceMailConfirmOutcome {
        val confirmed = store.enterCode(now, InstanceMailConfirmation.MAX_ATTEMPTS) { row ->
            val owner = InstanceMailConfirmation.addressOf(row.ownerUserId)
            row.codeAddress.takeIf { InstanceMailConfirmation.codeLive(row, owner, now) && CodeConfirmation.matches(code, row.codeHash) }
        }
        return if (confirmed) InstanceMailConfirmOutcome.Ok(view(store.find(), callerId, now)) else InstanceMailConfirmOutcome.CodeInvalid
    }

    private fun view(row: InstanceMailRow?, callerId: String, now: Instant): InstanceMailSettingsView {
        val owner = InstanceMailConfirmation.addressOf(row?.ownerUserId) ?: InstanceMailConfirmation.addressOf(callerId)
        val t = row?.transport
        return InstanceMailSettingsView(
            host = t?.host, port = t?.port, from = t?.from, username = t?.username, tls = t?.tls ?: true,
            passwordSet = t?.passwordSet == true,
            configSecretState = if (t?.passwordSet == true) keyState.stateOf(t.passwordFingerprint) else keyState.readState(),
            address = owner ?: "",
            confirmation = InstanceMailConfirmation.state(row, owner, now),
            confirmedAt = row?.confirmedAt.takeIf { InstanceMailConfirmation.state(row, owner, now) == InstanceMailConfirmationState.CONFIRMED },
        )
    }

    private fun String?.blankToNull(): String? = this?.trim()?.takeUnless { it.isEmpty() }
}
