package com.veyq.app.features.account.password

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.crypto.Ed25519Verifier
import com.veyq.app.shared.crypto.SecretKeyProof
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.identity.AccountStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.util.Base64

/** Outcome of a password change — applied, or one of the refusals the server itself can make. */
sealed interface PasswordChangeOutcome {
    /** Applied. The floor + auth key are rewritten, every device wrap is gone, and every session but the
     *  caller's own is revoked. */
    data object Applied : PasswordChangeOutcome

    /** The `attestationSignature` did not verify against the current `auth_public_key`. Nothing was
     *  written. */
    data object AttestationFailed : PasswordChangeOutcome, HttpFailure by httpFailure("attestation_failed", FORBIDDEN)

    /** The `secretKeyProofSignature` did not verify against the account's Secret-Key proof key. Distinct from
     *  [AttestationFailed]: "your password was wrong" and "your Emergency Kit was wrong" are different things to
     *  tell a person. Nothing was written. */
    data object SecretKeyProofFailed : PasswordChangeOutcome, HttpFailure by httpFailure("secret_key_proof_failed", FORBIDDEN)

    /** The account has no `PASSWORD` floor to rewrite — nothing was written. */
    data object Unavailable : PasswordChangeOutcome, HttpFailure by httpFailure("password_change_unavailable", CONFLICT)

    /** The caller's session was revoked by a reset, another change or a rotation this request waited for —
     *  nothing was written. */
    data object SessionEnded : PasswordChangeOutcome, HttpFailure by Failures.authenticationFailed
}

/**
 * The one mechanism behind uc.account.change-password: rewrite the `PASSWORD` floor and the auth key from a
 * live session, drop every device wrap, spare the caller's own session.
 *
 * **This mints a cold credential, so it may not be reachable without cold material** (ADR 0060 Decision 2).
 * The proof has two halves, and both are checked here: the attestation, against the stored `auth_public_key`
 * (the old password), and the Secret-Key proof, against the account's registered proof key (the Secret Key). A
 * phished or cracked password buys the first and not the second. The client additionally re-opens the current
 * `PASSWORD` wrap before it sends anything. Neither half is optional, and there is **no second entry point**:
 * someone who has forgotten their password goes through recovery (ADR 0054), which proves address control
 * instead.
 */
@ApplicationScoped
class PasswordChangeHandler(
    private val store: PasswordChangeStore,
    private val sessions: SessionService,
    private val accounts: AccountStore,
) {

    /**
     * Change the password. **One transaction, every write or none.**
     *
     * The attestation is verified against the account's **current** `auth_public_key` — proof the caller
     * still controls the password being replaced — over the **new** key's raw bytes (not a server nonce; the
     * use-case's attestation shape signs what is being installed, not a challenge). A failed verification
     * refuses before anything is written; a missing one never reaches here, the contract requires it.
     *
     * `presentedAccessToken` spares the family this request arrived on — `SessionService.revokeOtherFamilies`
     * resolves it from the token, never from the request, exactly as rotation's own spared-session mechanism
     * does. This is the one place this flow must NOT copy `RecoveryStore`'s behaviour: recovery has no session
     * to spare and revokes everything; this flow has one, and must not revoke it.
     *
     * **The account row is locked first** (`AccountStore.lock`, inbox #297), so the attestation is checked against
     * the auth key as it is after any reset or change this waited for, the caller's session is re-read under the
     * same lock, and a sign-in, wrap or delegation racing this either commits before it (and is ended by it) or
     * decides after it.
     */
    fun change(
        userId: String,
        presentedAccessToken: String?,
        newAuthPublicKey: String,
        wrappedKey: String,
        kdfParams: String,
        attestationSignature: String,
        secretKeyProofSignature: String,
    ): PasswordChangeOutcome = QuarkusTransaction.requiringNew().call<PasswordChangeOutcome> {
        val user = accounts.lock(userId)
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return@call PasswordChangeOutcome.SessionEnded
        val currentAuthPublicKey = user?.authPublicKey
        val verified = currentAuthPublicKey != null && Ed25519Verifier.verify(
            decode(currentAuthPublicKey), decode(newAuthPublicKey), decode(attestationSignature),
        )
        if (!verified) return@call PasswordChangeOutcome.AttestationFailed

        val statement = SecretKeyProof.passwordChangeStatement(
            currentAuthPublicKey = currentAuthPublicKey!!,
            authPublicKey = newAuthPublicKey,
            passwordWrap = SecretKeyProof.Wrap(wrappedKey, kdfParams),
        )
        if (!SecretKeyProof.verify(user.secretKeyProofPublicKey, statement, secretKeyProofSignature)) {
            return@call PasswordChangeOutcome.SecretKeyProofFailed
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
