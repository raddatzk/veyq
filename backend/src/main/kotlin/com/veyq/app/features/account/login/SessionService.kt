package com.veyq.app.features.account.login

import com.veyq.app.shared.auth.AccessTokenResolver
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.AccountStore
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
class SessionService(private val accounts: AccountStore) : AccessTokenResolver {

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
     * **No retry.** A deadlock here would be a lock-order defect, and it must surface as one: the family's row lock
     * is taken first on every path, so two rotations queue on it and never hold a token row the other waits for
     * (measured on MariaDB 11.4, inbox #262: 0 deadlocks in 1 600 family-first refreshes). A retry used to sit
     * here, and with it a reversed lock order deadlocked and was retried into a green run (the test log named a
     * deadlock 24 times and no test failed); without it, `LoginSliceTest`'s fork race answers a `500` and reds.
     */
    @Transactional
    fun refresh(presented: String): RotatedTokens? = rotate(presented)

    /**
     * One rotation. **The family's row lock comes first**, before any token row is read (ADR 0004 § the
     * one-generation grace). The distance below reads two rows — the presented token and the family's `CURRENT` —
     * and a lock on the presented row alone serializes only presentations of that same token: an orphan and the
     * current token lock different rows, so both measured a `CURRENT` the other was replacing and the chain forked.
     * Every rotation and every revoke of one family write the family row, so locking it first queues them all on
     * one row: the distance is decided against committed state, and a revoke lands either before this reads the
     * family (→ 401) or after this commits (the family ends `REVOKED`), never under it. Family first, token second,
     * on every path; nothing locks a token row and then its family. Reversed, the orphan and the current token each
     * hold a token row while waiting for the family and deadlock — `LoginSliceTest`'s fork race then reds on the
     * `500` (see [refresh]).
     *
     * Under that lock the managed writes below (`family.status`, `family.lastUsedAt`, `current.status`) are safe even
     * where two writers change the same column: no other writer of the family can interleave with them.
     */
    private fun rotate(presented: String): RotatedTokens? {
        val hash = Tokens.hash(presented)
        // The family id by a SCALAR query, not by loading the token entity: a token row loaded before the lock would
        // stay in this persistence context, and the locked read below would hand back that copy with the status it
        // had before the lock.
        val familyId = RefreshTokenEntity.getEntityManager()
            .createQuery("select r.familyId from RefreshTokenEntity r where r.tokenHash = ?1", String::class.java)
            .setParameter(1, hash).resultList.firstOrNull() ?: return null
        val family = SessionFamilyEntity.findById(familyId, LockModeType.PESSIMISTIC_WRITE) ?: return null
        if (family.status != SessionFamilyEntity.ACTIVE) return null
        // The token row is still locked, second; the family lock already queues two refreshes of this same token.
        val row = RefreshTokenEntity.find("tokenHash", hash)
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() ?: return null

        val now = Instant.now()
        val generation: Int
        if (row.status == RefreshTokenEntity.SUPERSEDED) {
            // How far behind the family's live generation is this? One means the caller never received its
            // successor — a dropped response, or a sibling call that lost the race — and it rotates. Zero
            // means it IS a successor that an earlier grace already replaced, and two or more is an ordinary
            // replay: both are clone signals (ADR 0004).
            // A LOCKING read, though the family lock already orders every writer of this row (inbox #302). The loser
            // of a same-token race decides after the winner committed, and must see the winner's successor here; a
            // plain read at MariaDB's REPEATABLE READ saw the snapshot the family-id query fixed BEFORE the lock wait,
            // found the presented row itself as CURRENT and revoked the family (800 of 800 races). READ COMMITTED
            // (ADR 0078) closes that on its own; a locking read reads the latest committed row at ANY isolation, so
            // this decision no longer rests on a connection setting. It costs no lock this path would not take: the
            // grace writes this very row below.
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

    /** The family's live refresh token — the one row per family whose status is CURRENT (see [mint]) — locked. */
    private fun currentTokenOf(familyId: String): RefreshTokenEntity? =
        RefreshTokenEntity.find("familyId = ?1 and status = ?2", familyId, RefreshTokenEntity.CURRENT)
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()

    /**
     * Resolve the user behind a bearer access token, or null if the token is unknown, expired, or its
     * family is not ACTIVE. This is the **authenticated-read guard** every authed endpoint resolves its
     * caller through — the counterpart to the write-side reuse-detection above. Only a token's hash is
     * matched, so it cannot be forged from a database dump.
     */
    @Transactional
    override fun resolveUserId(accessToken: String): String? {
        val row = AccessTokenEntity.find("tokenHash", Tokens.hash(accessToken)).firstResult() ?: return null
        if (row.expiresAt.isBefore(Instant.now())) return null
        val family = SessionFamilyEntity.findById(row.familyId) ?: return null
        if (family.status != SessionFamilyEntity.ACTIVE) return null
        return family.userId
    }

    /**
     * Whether [accessToken] still names a live session of [userId], read now — not when the request was
     * authenticated. A writer that must not outlive the reset, password change or rotation it raced asks this under
     * the account lock (`AccountStore.lock`, inbox #297): the request was let in before the cleanup committed, and
     * only this read, after the lock wait, sees that its session has since been revoked. A missing token is no live
     * session.
     */
    @Transactional
    fun isLiveSessionOf(userId: String, accessToken: String?): Boolean =
        accessToken != null && resolveUserId(accessToken) == userId

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
     *
     * **The account row is locked first** (`AccountStore.lock`, inbox #316), as by every other cleanup that ends an
     * account's other devices. The revoke below enumerates ACTIVE families, and a family a pairing delegation is
     * minting is not one it can see before that commits: without the lock a device this signs out could delegate
     * while it ran, and the delegated session outlived the sign-out that ended the device that made it. Under the
     * lock the delegation either committed first (its family is revoked here) or re-reads its session after this
     * commits (and is refused). A password change and a rotation call this already holding the lock.
     */
    @Transactional
    fun revokeOtherFamilies(userId: String, presentedAccessToken: String): Long {
        accounts.lock(userId)
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
     * Every family of one account that can still refresh — ACTIVE, with a CURRENT refresh token that has not
     * expired — most recently used first, with the one the presented access token belongs to marked `current`.
     * The marker is the server's own knowledge of which family the request arrived on — self-knowledge about the
     * caller, not a new fact about the account — and it is what keeps the client's "this device" and "sign out
     * everywhere else" honest against each other.
     *
     * The status column alone is not enough, because a family has no expired status: one whose refresh token
     * has lapsed is already ended (refresh expiry means a full re-login, ADR 0004), yet stays an ACTIVE row until
     * [SessionPurge] deletes it. Listing on status would leave the list's truth to a purge cadence the operator
     * configures, where [rotate] and [resolveUserId] check expiry at read time — so this does too, on the same
     * boundary as [rotate] (a token expiring exactly now is still live).
     */
    @Transactional
    fun listActiveFamilies(userId: String, presentedAccessToken: String?): List<SessionFamilyView> {
        val currentFamilyId = presentedAccessToken?.let { familyIdOf(it) }
        return SessionFamilyEntity
            .find(
                "userId = ?1 and status = ?2 and id in " +
                    "(select r.familyId from RefreshTokenEntity r where r.status = ?3 and r.expiresAt >= ?4)",
                userId, SessionFamilyEntity.ACTIVE, RefreshTokenEntity.CURRENT, Instant.now(),
            )
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

    /** Up to [limit] ids of session families with no remaining tokens — REVOKED (logout / reuse-revoked) and
     *  abandoned-but-ACTIVE families whose tokens have all been purged. A plain read, so it locks nothing; run it
     *  AFTER the token purges, and hand the ids to [purgeEmptyFamilies]. */
    @Transactional
    fun emptyFamilyIds(limit: Int): List<String> =
        SessionFamilyEntity.find("from SessionFamilyEntity f where $NO_TOKEN").page(0, limit).list().map { it.id }

    /** Delete those of [ids] that still have no token. The predicate is checked again here, by primary key, so a
     *  family that gained a token since [emptyFamilyIds] read it survives instead of failing the FK. */
    @Transactional
    fun purgeEmptyFamilies(ids: List<String>): Long =
        if (ids.isEmpty()) 0L else SessionFamilyEntity.delete("from SessionFamilyEntity f where f.id in ?1 and $NO_TOKEN", ids)

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

        /** "This family holds no token". `NOT EXISTS`, not `NOT IN (subquery)`: a `NOT IN` whose subquery ever
         *  yields a NULL deletes nothing, silently, and on H2 it pays for every token row — 16 000 families against
         *  ~150 000 access tokens ran into the 60 s transaction timeout, where this form took 11–18 ms
         *  (cr-session-purge-cost-on-mariadb). MariaDB plans both as the same per-family FK probe. */
        private const val NO_TOKEN =
            "not exists (select 1 from AccessTokenEntity a where a.familyId = f.id) " +
                "and not exists (select 1 from RefreshTokenEntity r where r.familyId = f.id)"
    }
}
