package com.veyq.app.features.account.login

import com.veyq.app.shared.crypto.Tokens
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A freshly issued session: the family id plus the opaque token pair handed to the client. */
data class IssuedSession(val familyId: String, val accessToken: String, val refreshToken: String)

/**
 * A rotated token pair, plus the family it belongs to. The family id is carried because **adoption** answers
 * with it (`POST /api/token/adopt`, ADR 0004 § Transport): a device onboarded by a grant has no login to learn
 * its family from, and without it the device↔family join in its Master-Key-sealed register points at nothing,
 * so it could never appear named in uc.account.sessions. Rotation already holds the family row, so this costs
 * no extra read — and `/api/token/refresh` deliberately does **not** put it on the wire: refresh answers a
 * client that has known its family since login.
 */
data class RotatedTokens(val accessToken: String, val refreshToken: String, val familyId: String)

/**
 * A family minted for a device being linked (ADR 0059 §2): the id the granting channel records so an abandoned
 * ceremony can revoke it, and the refresh token that is sealed to the new device. **No access token** — see
 * [SessionService.issueDelegated].
 */
data class DelegatedSession(val familyId: String, val refreshToken: String)

/**
 * One active session family as the session list sees it (uc.account.sessions). Deliberately **nameless**: a
 * family carries no name, user-agent or address, and none may be added — a per-family name blob would let the
 * operator match a device across logins and re-link families that are otherwise unlinkable (ADR 0004). Names
 * live only in the client's Master-Key-sealed device register and are joined in on-device.
 */
data class SessionFamilyView(
    val id: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val current: Boolean,
)

/**
 * Session families and their opaque, rotating access + refresh tokens (ADR 0004). The server stores only
 * token hashes; refresh rotates the pair and a replayed superseded refresh token revokes the whole
 * family (reuse detection). DoS-safe by construction: a family is only revoked when a presented token's
 * **hash matches** a stored superseded row, which cannot be forged without the token itself.
 */
@ApplicationScoped
class SessionService {

    /** Issue a fresh nameless session family for a user. */
    @Transactional
    fun issue(userId: String): IssuedSession {
        val now = Instant.now()
        val family = SessionFamilyEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            status = SessionFamilyEntity.ACTIVE
            createdAt = now
            lastUsedAt = now
        }
        family.persist()
        val (access, refresh) = mint(family.id, generation = 1, now)
        return IssuedSession(family.id, access, refresh)
    }

    /**
     * Mint one further family for an account that is **already** authenticated, and hand back only its refresh
     * token — the device-linking grant (ADR 0059 §2), and ADR 0004's second origin for a family beside the login.
     *
     * It exists because a device let in by linking never learns the password, and veyq's auth keypair is derived
     * from the password (ADR 0003): that device cannot obtain a session by itself, however much key material it
     * is handed.
     *
     * Deliberately **not** a general `issueFor(userId)`, and deliberately narrower than [issue]:
     * - it is reachable only through the pairing handler, against a live channel the caller delegated, so what
     *   this adds to the server is *"a device-linking grant"* and not *"any authenticated caller may mint a
     *   session"*;
     * - it returns **no access token**. The delegating device rotates nothing — the granted device turns this
     *   refresh token into its own pair — and an access token for a family that is not the caller's own is
     *   something no caller has a use for.
     *
     * The family itself is ordinary in every respect ADR 0004 names: it is listed, it rotates, it is revocable.
     */
    @Transactional
    fun issueDelegated(userId: String): DelegatedSession {
        val issued = issue(userId)
        return DelegatedSession(issued.familyId, issued.refreshToken)
    }

    /**
     * Rotate a refresh token. Returns null (→ 401) on any failure; presenting a superseded token revokes the
     * family unless it is exactly one generation behind (ADR 0004 § the one-generation grace).
     *
     * **Not `@Transactional` — the transaction is opened below, per attempt.** A deadlock arrives as a
     * rollback of the whole transaction, so a retry has to be able to start a new one; an interceptor-managed
     * transaction around this method could only ever be retried by its caller. There is no outer transaction
     * to join here in any case: the only callers are the two `TokenResource` endpoints.
     */
    fun refresh(presented: String): RotatedTokens? =
        withDeadlockRetry { QuarkusTransaction.requiringNew().call<RotatedTokens?> { rotate(presented) } }

    private fun rotate(presented: String): RotatedTokens? {
        // Lock the row so two concurrent refreshes of the SAME token serialize: the second blocks until the
        // first commits and then reads its status, rather than minting a second pair from one refresh.
        val row = RefreshTokenEntity.find("tokenHash", Tokens.hash(presented))
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() ?: return null
        val family = SessionFamilyEntity.findById(row.familyId) ?: return null
        if (family.status != SessionFamilyEntity.ACTIVE) return null

        val now = Instant.now()
        val generation: Int
        if (row.status == RefreshTokenEntity.SUPERSEDED) {
            // How far behind the family's live generation is this? One means the caller never received its
            // successor — a dropped response, or a sibling call that lost the race — and it rotates. Zero
            // means it IS a successor that an earlier grace already replaced, and two or more is an ordinary
            // replay: both are clone signals (ADR 0004).
            val current = currentTokenOf(family.id)
            if (current == null || current.generation - row.generation != 1) {
                family.status = SessionFamilyEntity.REVOKED
                return null
            }
            if (row.expiresAt.isBefore(now)) return null
            // Re-mint under the ORPHAN'S OWN number, never a higher one. Incrementing here would push every
            // further presentation of this same stale token to distance two, so the third concurrent caller
            // would revoke the family the first two were spared — the rule would fail the case it exists for.
            current.status = RefreshTokenEntity.SUPERSEDED
            generation = current.generation
        } else {
            if (row.expiresAt.isBefore(now)) return null
            row.status = RefreshTokenEntity.SUPERSEDED
            generation = row.generation + 1
        }

        family.lastUsedAt = now
        val (access, refresh) = mint(family.id, generation, now)
        return RotatedTokens(access, refresh, family.id)
    }

    /** The family's live refresh token — the one row per family whose status is CURRENT (see [mint]). */
    private fun currentTokenOf(familyId: String): RefreshTokenEntity? =
        RefreshTokenEntity.find("familyId = ?1 and status = ?2", familyId, RefreshTokenEntity.CURRENT)
            .firstResult()

    /**
     * Resolve the user behind a bearer access token, or null if the token is unknown, expired, or its
     * family is not ACTIVE. This is the **authenticated-read guard** every authed endpoint resolves its
     * caller through — the counterpart to the write-side reuse-detection above. Only a token's hash is
     * matched, so it cannot be forged from a database dump.
     */
    @Transactional
    fun resolveUserId(accessToken: String): String? {
        val row = AccessTokenEntity.find("tokenHash", Tokens.hash(accessToken)).firstResult() ?: return null
        if (row.expiresAt.isBefore(Instant.now())) return null
        val family = SessionFamilyEntity.findById(row.familyId) ?: return null
        if (family.status != SessionFamilyEntity.ACTIVE) return null
        return family.userId
    }

    /** Revoke the family of the presented access token. Idempotent — a missing/invalid token is a no-op. */
    @Transactional
    fun revokeByAccessToken(accessToken: String) {
        val row = AccessTokenEntity.find("tokenHash", Tokens.hash(accessToken)).firstResult() ?: return
        SessionFamilyEntity.findById(row.familyId)?.let { it.status = SessionFamilyEntity.REVOKED }
    }

    /**
     * Revoke **every** session family of one account (cr-account-recovery, ADR 0054), optionally sparing one.
     *
     * Two callers, one statement. A password reset must not leave a live session behind — whoever prompted the
     * reset may be holding one — so recovery passes no exception and takes them all; it runs inside the caller's
     * reset transaction, so sessions die with the wrap replacement or not at all. "Sign out everywhere else"
     * (uc.account.sessions) passes the family the request arrived on, because a sign-out-everywhere that also
     * ejects the person securing the account is a footgun, not a feature.
     *
     * Returns how many families were revoked.
     */
    @Transactional
    fun revokeAllForUser(userId: String, exceptFamilyId: String? = null): Long =
        // Two statements, not one with `id <> ?`: in SQL every comparison against NULL is unknown, so a single
        // parameterized `id <> :except` with a null parameter would match no row and revoke nothing at all.
        if (exceptFamilyId == null) {
            SessionFamilyEntity.update(
                "status = ?1 where userId = ?2 and status = ?3",
                SessionFamilyEntity.REVOKED, userId, SessionFamilyEntity.ACTIVE,
            ).toLong()
        } else {
            SessionFamilyEntity.update(
                "status = ?1 where userId = ?2 and status = ?3 and id <> ?4",
                SessionFamilyEntity.REVOKED, userId, SessionFamilyEntity.ACTIVE, exceptFamilyId,
            ).toLong()
        }

    /**
     * "Sign out everywhere else" — revoke every ACTIVE family of the account except the one the presented
     * access token belongs to (uc.account.sessions).
     *
     * The spared family is the one the **server** resolves from the token, never one the request named. If the
     * token resolves to no family this revokes **nothing**: falling through to [revokeAllForUser]'s no-exception
     * form would sign the caller out of the device it is holding, which is the single outcome this endpoint
     * exists to avoid — so the trap is closed here rather than left to each caller to remember.
     */
    @Transactional
    fun revokeOtherFamilies(userId: String, presentedAccessToken: String): Long {
        val current = familyIdOf(presentedAccessToken) ?: return 0
        return revokeAllForUser(userId, current)
    }

    /**
     * Revoke one family **of the caller's own account** — the remote sign-out (uc.account.sessions).
     *
     * Scoped by `userId` in the statement itself rather than by a read-then-check, so a foreign id cannot be
     * revoked and cannot be told apart from an unknown one: both update zero rows. Idempotent, and returns
     * nothing on purpose — the endpoint above it answers 204 for every id, and a count handed up here is the
     * first thing a later change would leak into that response.
     */
    @Transactional
    fun revokeFamilyForUser(userId: String, familyId: String) {
        SessionFamilyEntity.update(
            "status = ?1 where id = ?2 and userId = ?3 and status = ?4",
            SessionFamilyEntity.REVOKED, familyId, userId, SessionFamilyEntity.ACTIVE,
        )
    }

    /**
     * Every ACTIVE family of one account, most recently used first, with the one the presented access token
     * belongs to marked `current`. The marker is the server's own knowledge of which family the request arrived
     * on — self-knowledge about the caller, not a new fact about the account — and it is what keeps the client's
     * "this device" and "sign out everywhere else" honest against each other.
     */
    @Transactional
    fun listActiveFamilies(userId: String, presentedAccessToken: String?): List<SessionFamilyView> {
        val currentFamilyId = presentedAccessToken?.let { familyIdOf(it) }
        return SessionFamilyEntity
            .find("userId = ?1 and status = ?2", userId, SessionFamilyEntity.ACTIVE)
            .list()
            .sortedByDescending { it.lastUsedAt }
            .map { SessionFamilyView(it.id, it.createdAt, it.lastUsedAt, it.id == currentFamilyId) }
    }

    /** The family an access token belongs to, or null if the token is unknown. */
    private fun familyIdOf(accessToken: String): String? =
        AccessTokenEntity.find("tokenHash", Tokens.hash(accessToken)).firstResult()?.familyId

    // --- scheduled purge (see SessionPurge, cr-0011) ---
    // Physically remove rows that are no longer needed. Matching strictly by `expiresAt < now` preserves the
    // reuse-detection window: a SUPERSEDED refresh token stays until it expires (a replay within the refresh
    // TTL still revokes its family — SessionService.refresh checks SUPERSEDED before expiry); once expired, a
    // replay is rejected anyway, so deleting it is safe defense-in-depth.

    /** Delete expired access tokens. Returns how many. */
    @Transactional
    fun purgeExpiredAccessTokens(now: Instant): Long = AccessTokenEntity.delete("expiresAt < ?1", now)

    /** Delete expired refresh tokens (both CURRENT and SUPERSEDED — the reuse window is the TTL). */
    @Transactional
    fun purgeExpiredRefreshTokens(now: Instant): Long = RefreshTokenEntity.delete("expiresAt < ?1", now)

    /** Delete expired (or stale, single-use) login challenges. */
    @Transactional
    fun purgeExpiredChallenges(now: Instant): Long = LoginChallengeEntity.delete("expiresAt < ?1", now)

    /** Delete session families with no remaining tokens — cleans REVOKED (logout / reuse-revoked) and
     *  abandoned-but-ACTIVE families whose tokens have all been purged. Run AFTER the token purges (FK). */
    @Transactional
    fun purgeEmptyFamilies(): Long = SessionFamilyEntity.delete(
        "id not in (select a.familyId from AccessTokenEntity a) " +
            "and id not in (select r.familyId from RefreshTokenEntity r)",
    )

    private fun mint(familyId: String, generation: Int, now: Instant): Pair<String, String> {
        val access = Tokens.random()
        AccessTokenEntity().apply {
            id = UUID.randomUUID().toString()
            this.familyId = familyId
            tokenHash = Tokens.hash(access)
            createdAt = now
            expiresAt = now.plus(ACCESS_TTL_MINUTES, ChronoUnit.MINUTES)
        }.persist()

        val refresh = Tokens.random()
        RefreshTokenEntity().apply {
            id = UUID.randomUUID().toString()
            this.familyId = familyId
            tokenHash = Tokens.hash(refresh)
            this.generation = generation
            status = RefreshTokenEntity.CURRENT
            createdAt = now
            expiresAt = now.plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS)
        }.persist()

        return access to refresh
    }

    companion object {
        const val ACCESS_TTL_MINUTES = 15L
        const val REFRESH_TTL_DAYS = 30L
    }
}
