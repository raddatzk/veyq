package com.veyq.app.features.account.login

import com.veyq.app.shared.crypto.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A freshly issued session: the family id plus the opaque token pair handed to the client. */
data class IssuedSession(val familyId: String, val accessToken: String, val refreshToken: String)

/** A rotated token pair. */
data class RotatedTokens(val accessToken: String, val refreshToken: String)

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

    /** Rotate a refresh token. Returns null (→ 401) on any failure; a superseded-token replay revokes
     *  the family as a side effect. */
    @Transactional
    fun refresh(presented: String): RotatedTokens? {
        // Lock the row so two concurrent refreshes of the SAME token serialize: the second blocks until
        // the first commits, then sees status = SUPERSEDED and takes the reuse-detection path instead of
        // minting a second token pair from one refresh (ADR 0004).
        val row = RefreshTokenEntity.find("tokenHash", Tokens.hash(presented))
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() ?: return null
        val family = SessionFamilyEntity.findById(row.familyId) ?: return null
        if (family.status != SessionFamilyEntity.ACTIVE) return null

        if (row.status == RefreshTokenEntity.SUPERSEDED) {
            family.status = SessionFamilyEntity.REVOKED // reuse detected → kill the whole family
            return null
        }
        if (row.expiresAt.isBefore(Instant.now())) return null

        val now = Instant.now()
        row.status = RefreshTokenEntity.SUPERSEDED
        family.lastUsedAt = now
        val (access, refresh) = mint(family.id, row.generation + 1, now)
        return RotatedTokens(access, refresh)
    }

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
     * Revoke **every** session family of one account (cr-account-recovery, ADR 0054).
     *
     * A password reset must not leave a live session behind: whoever prompted the reset may be holding one. This
     * runs inside the caller's reset transaction, so sessions die with the wrap replacement or not at all.
     * Returns how many families were revoked.
     */
    @Transactional
    fun revokeAllForUser(userId: String): Long = SessionFamilyEntity.update(
        "status = ?1 where userId = ?2 and status = ?3",
        SessionFamilyEntity.REVOKED, userId, SessionFamilyEntity.ACTIVE,
    ).toLong()

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
