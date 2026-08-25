package com.veyq.app.features.account.password

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.crypto.Ed25519Verifier
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.util.Base64

/** Outcome of a password change — applied, or the one of two refusals the server itself can make. */
sealed interface PasswordChangeOutcome {
    /** Applied. The floor + auth key are rewritten, every device wrap is gone, and every session but the
     *  caller's own is revoked. */
    data object Applied : PasswordChangeOutcome

    /** An `attestationSignature` was present but did not verify against the current `auth_public_key`.
     *  Nothing was written. */
    data object AttestationFailed : PasswordChangeOutcome, HttpFailure by httpFailure("attestation_failed", FORBIDDEN)

    /** The account has no `PASSWORD` floor to rewrite — nothing was written. */
    data object Unavailable : PasswordChangeOutcome, HttpFailure by httpFailure("password_change_unavailable", CONFLICT)
}

/**
 * The one mechanism behind both entry points of uc.account.change-password: a deliberate change (the caller
 * still knows the current password) and a forgotten-password change reached only from an already-unlocked
 * session. They differ in exactly one thing — whether `attestationSignature` is present — and share every write.
 *
 * **What is deliberately not here: the gate.** Exactly as ADR 0060 Decision 2 for rotation, the server holds no
 * evidence of *which* wrap opened the caller's vault, so it cannot tell "deliberate" from "forgotten" itself,
 * and must not try. The client decides which form to show by reaching this endpoint only from an unlocked
 * session (ADR 0026's usual tier); the server's only role is verifying the attestation *when one is presented*.
 */
@ApplicationScoped
class PasswordChangeHandler(
    private val store: PasswordChangeStore,
    private val sessions: SessionService,
) {

    /**
     * Change the password. **One transaction, every write or none.**
     *
     * The attestation, when present, is verified against the account's **current** `auth_public_key` — proof
     * the caller still controls the password being replaced — over the **new** key's raw bytes (not a server
     * nonce; the use-case's attestation shape signs what is being installed, not a challenge). A failed
     * verification refuses before anything is written.
     *
     * `presentedAccessToken` spares the family this request arrived on — `SessionService.revokeOtherFamilies`
     * resolves it from the token, never from the request, exactly as rotation's own spared-session mechanism
     * does. This is the one place this flow must NOT copy `RecoveryStore`'s behaviour: recovery has no session
     * to spare and revokes everything; this flow has one, and must not revoke it.
     */
    fun change(
        userId: String,
        presentedAccessToken: String?,
        newAuthPublicKey: String,
        wrappedKey: String,
        kdfParams: String,
        attestationSignature: String?,
    ): PasswordChangeOutcome = QuarkusTransaction.requiringNew().call<PasswordChangeOutcome> {
        if (attestationSignature != null) {
            val currentAuthPublicKey = VeyqUser.findById(userId)?.authPublicKey
            val verified = currentAuthPublicKey != null && Ed25519Verifier.verify(
                decode(currentAuthPublicKey), decode(newAuthPublicKey), decode(attestationSignature),
            )
            if (!verified) return@call PasswordChangeOutcome.AttestationFailed
        }

        val applied = store.applyChange(userId, wrappedKey, kdfParams, newAuthPublicKey)
        if (!applied) return@call PasswordChangeOutcome.Unavailable

        presentedAccessToken?.let { sessions.revokeOtherFamilies(userId, it) }
        PasswordChangeOutcome.Applied
    }

    private fun decode(b64: String): ByteArray = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        ByteArray(0)
    }
}
