package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.crypto.Tokens
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * The one rule by which a mailed code confirms an address (ADR 0097 decision 5): the Server Owner's instance mail
 * ([InstanceMailConfirmation]), a moderator's address (`ModeratorAddress`) and the instance contact's notification
 * address (`OperatorDetailsHandler`) all read it, so none of them can drift into a laxer copy.
 *
 * **Confirmed means a code confirmed the address that is current NOW.** Each holder passes the address it would mail
 * to today — the account's, or the stored notification address — so a changed address unconfirms by itself, without a
 * write, and the old address confirmed again is confirmed again. A code is live only for the address it was sent to,
 * for [CODE_TTL], and until [MAX_ATTEMPTS] wrong entries spent it. Codes are stored hashed ([Tokens.hash]).
 */
object CodeConfirmation {
    /** How long a code may be entered. Long enough to fetch a mail, short because nothing waits on it. */
    val CODE_TTL: Duration = Duration.ofHours(1)

    /** Wrong entries that spend a code. With eight digits and the per-caller rate limit, guessing is not a strategy. */
    const val MAX_ATTEMPTS = 5

    private val rng = SecureRandom()

    /** A fresh code: eight digits, short enough to type from a phone's mail into a console. Stored only hashed. */
    fun newCode(): String = "%08d".format(rng.nextInt(100_000_000))

    /** Whether a code confirmed [currentAddress] — the address the holder would mail to now. */
    fun confirmed(confirmedAt: Instant?, confirmedAddress: String?, currentAddress: String?): Boolean =
        confirmedAt != null && sameAddress(confirmedAddress, currentAddress)

    /** Whether an outstanding code may still be entered, for [currentAddress]. */
    fun codeLive(
        codeHash: String?,
        codeExpiresAt: Instant?,
        codeAttempts: Int,
        codeAddress: String?,
        currentAddress: String?,
        now: Instant,
    ): Boolean =
        codeHash != null && codeExpiresAt?.isAfter(now) == true && codeAttempts < MAX_ATTEMPTS &&
            sameAddress(codeAddress, currentAddress)

    /** Whether [entered] (as typed) is the code stored as [codeHash], compared in constant time. */
    fun matches(entered: String, codeHash: String?): Boolean =
        codeHash != null && MessageDigest.isEqual(Tokens.hash(entered.trim()).toByteArray(), codeHash.toByteArray())

    /** Mail addresses compare case-insensitively, as the account's own lookup does. */
    fun sameAddress(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)
}
