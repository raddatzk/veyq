package me.raddatz.veyq.core.account.login

import me.raddatz.veyq.core.crypto.Ed25519Verifier
import me.raddatz.veyq.core.instance.InstanceStore
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.normalizeEmail
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** The challenge handed to the client: a nonce to sign plus the instance salt for key derivation. */
data class ChallengeResult(val nonce: String, val instanceSalt: String)

/**
 * Operator-blind challenge-response login (ADR 0003). The password never reaches the server; the client
 * proves possession of its password-derived auth private key by signing a server nonce, which the server
 * verifies against the stored `authPublicKey`. Challenge issuance and every failure are **non-enumerable**
 * — identical for existing and non-existing accounts.
 */
@ApplicationScoped
class LoginHandler(
    private val instanceStore: InstanceStore,
    private val sessions: SessionService,
    private val challengeStore: LoginChallengeStore,
    private val accounts: AccountStore,
    private val blocks: ModerationBlocks,
) {

    /** Issue a single-use, time-bounded challenge. A nonce is always returned; it is only stored (and
     *  thus verifiable) for an existing account, so the response never reveals whether the email exists. */
    @Transactional
    fun challenge(email: String): ChallengeResult {
        val normalized = normalizeEmail(email)
        val nonce = randomNonce()
        if (accounts.findByEmail(normalized) != null) {
            // Store in its own transaction and swallow a failure: the response must be identical (200 + nonce)
            // whether or not the account exists — a failed insert for an existing account must never surface as
            // a different status (non-enumerability).
            try {
                challengeStore.insert(normalized, nonce, Instant.now().plus(CHALLENGE_TTL_MINUTES, ChronoUnit.MINUTES))
            } catch (e: Exception) {
                // the nonce is then simply unverifiable, exactly as for an unknown account — fine, ignore.
            }
        }
        return ChallengeResult(nonce = nonce, instanceSalt = instanceStore.instanceSalt())
    }

    /** Verify a signed challenge and, on success, issue a session. Only the challenge the request names is
     *  consumed (single-use per nonce), whether or not verification succeeds; any other challenge outstanding
     *  for the same email is left alone. Returns null on any failure (→ generic 401). */
    @Transactional
    fun verify(email: String, nonce: String, signatureB64: String): IssuedSession? {
        val normalized = normalizeEmail(email)
        // Consume the named challenge atomically, per nonce: a PESSIMISTIC_WRITE lock on that one row (mirrors
        // SessionService) serializes concurrent verifies of the SAME nonce — the first locks, deletes and
        // commits; a racing request then blocks, re-reads nothing, and fails. Without the lock both requests
        // read the row before either deletes it, so a captured signature could be replayed in parallel for two
        // sessions off one challenge (audit P2f). The row must also belong to this email: a nonce named under
        // another address finds nothing and consumes nothing.
        val challenge = LoginChallengeEntity.find("nonce = ?1 and email = ?2", nonce, normalized)
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        if (challenge != null) LoginChallengeEntity.deleteById(challenge.nonce) // single-use, consumed under the lock
        // The account row, LOCKED (inbox #297): a password reset or change replaces the auth key and revokes every
        // family it can see, and a family this verify inserts is not one it can see before it commits. Under the
        // lock this reads the key the cleanup installed — a signature by the replaced password fails — or the cleanup
        // waits for this commit and revokes the family issued here.
        val user = accounts.findByEmail(normalized, lock = true)

        val ok = verifySignature(challenge, user, signatureB64)
        // A blocked sign-in (uc.account.login E5) answers as a wrong password, decided after the full verification and
        // under the account lock the block takes too (`SignInBlocks`), so the answer tells nobody the account is blocked.
        // A pending erasure answers the same (ADR 0101 decision 1), under the same lock the erasure request takes.
        return if (ok && user != null && !blocks.signInBlocked(user.id) && !AccountErasureEntity.isPending(user.id)) sessions.issue(user.id) else null
    }

    private fun verifySignature(challenge: LoginChallengeEntity?, user: VeyqUser?, signatureB64: String): Boolean {
        // Timing equalization: run a verification in every branch so a missing account / nonce is not
        // distinguishable by latency. A missing piece verifies against a static dummy key and fails.
        val pubRaw: ByteArray
        val message: ByteArray
        if (challenge != null && user != null && challenge.expiresAt.isAfter(Instant.now())) {
            pubRaw = decode(user.authPublicKey) ?: DUMMY_PUBLIC_KEY
            message = decode(challenge.nonce) ?: ByteArray(0)
        } else {
            pubRaw = DUMMY_PUBLIC_KEY
            message = DUMMY_MESSAGE
        }
        val signature = decode(signatureB64) ?: ByteArray(0)
        val verified = Ed25519Verifier.verify(pubRaw, message, signature)
        // Only a real challenge+account can succeed; the dummy branch can never verify true.
        return verified && challenge != null && user != null && challenge.expiresAt.isAfter(Instant.now())
    }

    // 32 bytes = 44 base64 chars, and the nonce is the key of a VARCHAR(64) column (`veyq_login_challenge.nonce`). A longer nonce fails
    // the insert, which `challenge` swallows for non-enumerability — so every login would 401, silently.
    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decode(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val CHALLENGE_TTL_MINUTES = 5L

        // A valid, throwaway Ed25519 public key used only to equalize the timing of failed verifications.
        private val DUMMY_PUBLIC_KEY: ByteArray = run {
            val pub = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
            pub.copyOfRange(pub.size - 32, pub.size) // strip the 12-byte X.509 SPKI prefix
        }
        private val DUMMY_MESSAGE = ByteArray(32)
    }
}
