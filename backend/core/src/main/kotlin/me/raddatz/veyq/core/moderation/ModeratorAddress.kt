package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.CodeConfirmation
import me.raddatz.veyq.core.identity.ModeratorEntity
import java.time.Instant

/**
 * The rule every reader of a moderator's address confirmation shares (ADR 0097 decision 5, ADR 0099 § 7): confirmed
 * means a code confirmed the address the account has **now**, so a changed address unconfirms by itself, without a
 * write — [CodeConfirmation], the one rule every confirmed address shares.
 */
object ModeratorAddress {

    fun confirmed(row: ModeratorEntity, currentAddress: String?): Boolean =
        CodeConfirmation.confirmed(row.confirmedAt, row.confirmedAddress, currentAddress)

    /** Where the confirmation stands, as the wire enum says it (`ModeratorAppointment.confirmation`). */
    fun state(row: ModeratorEntity?, currentAddress: String?, now: Instant): State = when {
        row == null || !row.accepted -> State.NOT_SENT
        confirmed(row, currentAddress) -> State.CONFIRMED
        row.codeFailed -> State.SEND_FAILED
        codeLive(row, currentAddress, now) -> State.CODE_SENT
        row.codeHash == null && row.confirmedAt == null && row.codeExpiresAt == null -> State.NOT_SENT
        else -> State.UNCONFIRMED
    }

    /** Whether [row]'s code may still be entered, for [currentAddress]. */
    fun codeLive(row: ModeratorEntity, currentAddress: String?, now: Instant): Boolean =
        CodeConfirmation.codeLive(row.codeHash, row.codeExpiresAt, row.codeAttempts, row.codeAddress, currentAddress, now)

    /** Wrong entries that spend a code — the shared rule's ([CodeConfirmation.MAX_ATTEMPTS]). */
    const val MAX_ATTEMPTS = CodeConfirmation.MAX_ATTEMPTS

    enum class State { NOT_SENT, CODE_SENT, SEND_FAILED, UNCONFIRMED, CONFIRMED }

    /** Forget every code and confirmation (a removal, a new appointment). */
    fun clear(row: ModeratorEntity) {
        row.codeHash = null
        row.codeAddress = null
        row.codeExpiresAt = null
        row.codeAttempts = 0
        row.codeFailed = false
        row.confirmedAddress = null
        row.confirmedAt = null
    }
}
