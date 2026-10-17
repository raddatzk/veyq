package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.InstanceMailConfirmation
import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.security.MessageDigest
import java.time.Instant

/**
 * A moderator's address, confirmed by a code (ADR 0097 decision 5, ADR 0099 § 7): accepting the appointment sends one,
 * the moderation surface can ask for another, and typing it confirms the address the account has now — the Server
 * Owner's rule for instance mail ([InstanceMailConfirmation]: eight digits, an hour, five wrong entries), applied to a
 * moderator's own row. The code is stored hashed before the send, and the send runs after that transaction committed,
 * so a slow relay holds no connection.
 */
@ApplicationScoped
class ModeratorAddressHandler(private val mailer: InstanceNoticeMailer) {

    /** Mail a new code to [accountId]'s address — only for an accepted moderator; answers whether it went out. */
    fun sendCode(accountId: String, now: Instant = Instant.now()): Boolean {
        val code = InstanceMailConfirmation.newCode()
        val hash = Tokens.hash(code)
        val expiresAt = now.plus(InstanceMailConfirmation.CODE_TTL)
        val address = QuarkusTransaction.requiringNew().call {
            val row = ModeratorEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.accepted }
                ?: return@call null
            val email = VeyqUser.findById(accountId)?.email ?: return@call null
            row.codeHash = hash
            row.codeAddress = email
            row.codeExpiresAt = expiresAt
            row.codeAttempts = 0
            row.codeFailed = false
            email
        } ?: return false
        val sent = mailer.sendModeratorCode(address, code, expiresAt) == DeliveryResult.SENT
        if (!sent) {
            QuarkusTransaction.requiringNew().run {
                ModeratorEntity.update("codeFailed = true where accountId = ?1 and codeHash = ?2", accountId, hash)
            }
        }
        return sent
    }

    /** Enter the code: it confirms only while live and only for the address it went to, which must still be the account's. */
    fun confirm(accountId: String, code: String, now: Instant = Instant.now()): Boolean =
        QuarkusTransaction.requiringNew().call {
            val row = ModeratorEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.accepted }
                ?: return@call false
            val email = VeyqUser.findById(accountId)?.email
            val entered = Tokens.hash(code.trim()).toByteArray()
            if (ModeratorAddress.codeLive(row, email, now) && MessageDigest.isEqual(entered, row.codeHash!!.toByteArray())) {
                row.confirmedAddress = row.codeAddress
                row.confirmedAt = now
                row.codeHash = null
                row.codeAddress = null
                row.codeExpiresAt = null
                row.codeAttempts = 0
                true
            } else {
                if (row.codeHash != null) {
                    row.codeAttempts += 1
                    if (row.codeAttempts >= ModeratorAddress.MAX_ATTEMPTS) {
                        row.codeHash = null
                        row.codeAddress = null
                        row.codeExpiresAt = null
                    }
                }
                false
            }
        }

    companion object {
        val codeInvalid = httpFailure("mail_code_invalid", FailureStatus.CONFLICT)
    }
}
