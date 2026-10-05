package me.raddatz.veyq.core.account.login

import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * cr-0011 — the scheduled session purge deletes expired access/refresh tokens, stale login challenges and
 * token-less families, while keeping live tokens and (crucially) **unexpired SUPERSEDED** refresh tokens so
 * reuse-detection survives. Uses distinct ids/emails so the JVM-lived H2 mem DB does not bleed across tests.
 *
 * Drives `SessionPurge.run()`, the scheduled entry: the suite's scheduler is halted (`ScheduledJobsHaltedInTestTest`),
 * so this test is the only place it executes.
 */
@QuarkusTest
class SessionPurgeTest {

    @Inject
    lateinit var purge: SessionPurge

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun purgesExpiredRowsAndEmptyFamiliesButKeepsLiveAndSupersededTokens() {
        val now = Instant.now()
        val past = now.minus(1, ChronoUnit.DAYS)
        val future = now.plus(1, ChronoUnit.DAYS)

        QuarkusTransaction.requiringNew().run {
            ensurePurgeUser()
            // A live session: unexpired access + refresh → kept, family kept.
            family(FAM_LIVE)
            access("$FAM_LIVE-a", FAM_LIVE, future)
            refresh("$FAM_LIVE-r", FAM_LIVE, RefreshTokenEntity.CURRENT, future)
            // An expired session: both tokens expired → purged, family becomes token-less → deleted.
            family(FAM_EXPIRED)
            access("$FAM_EXPIRED-a", FAM_EXPIRED, past)
            refresh("$FAM_EXPIRED-r", FAM_EXPIRED, RefreshTokenEntity.CURRENT, past)
            // A rotated session whose superseded refresh is still UNEXPIRED → kept (reuse tripwire).
            family(FAM_SUPERSEDED)
            refresh("$FAM_SUPERSEDED-r", FAM_SUPERSEDED, RefreshTokenEntity.SUPERSEDED, future)
            // Login challenges: one expired, one live.
            challenge(CHALLENGE_EXPIRED, past)
            challenge(CHALLENGE_LIVE, future)
        }

        // The real entry, so its call order is under test too: the token deletes must run before the family
        // delete, or the expired session's family is still holding tokens when it is looked at ("empty family kept").
        purge.run()

        QuarkusTransaction.requiringNew().run {
            // Live session intact.
            assert(SessionFamilyEntity.findById(FAM_LIVE) != null) { "live family deleted" }
            assert(AccessTokenEntity.count("familyId", FAM_LIVE) == 1L) { "live access token deleted" }
            assert(RefreshTokenEntity.count("familyId", FAM_LIVE) == 1L) { "live refresh token deleted" }
            // Expired session fully gone (tokens purged → token-less family deleted).
            assert(AccessTokenEntity.count("familyId", FAM_EXPIRED) == 0L) { "expired access kept" }
            assert(RefreshTokenEntity.count("familyId", FAM_EXPIRED) == 0L) { "expired refresh kept" }
            assert(SessionFamilyEntity.findById(FAM_EXPIRED) == null) { "empty family kept" }
            // Unexpired superseded refresh kept → reuse-detection survives; family kept (has a token).
            assert(RefreshTokenEntity.count("familyId", FAM_SUPERSEDED) == 1L) { "unexpired SUPERSEDED purged" }
            assert(SessionFamilyEntity.findById(FAM_SUPERSEDED) != null) { "superseded family deleted" }
            // Challenges.
            assert(LoginChallengeEntity.count("email", CHALLENGE_EXPIRED) == 0L) { "expired challenge kept" }
            assert(LoginChallengeEntity.count("email", CHALLENGE_LIVE) == 1L) { "live challenge deleted" }
        }
    }

    @Test
    fun tokenlessFamiliesBeyondOneBatchAreAllPurgedInOneRun() {
        QuarkusTransaction.requiringNew().run {
            ensurePurgeUser()
            repeat(SessionPurge.FAMILY_BATCH + 5) { family("$FAM_BATCH-$it") }
        }

        purge.run()

        QuarkusTransaction.requiringNew().run {
            // Asserted over the whole table, not only this test's rows: other tests leave token-less families too,
            // and a run that stopped after one batch might have happened to take all of ours.
            assert(sessions.emptyFamilyIds(1).isEmpty()) { "a token-less family outlived the run" }
            assert(SessionFamilyEntity.count("id like ?1", "$FAM_BATCH-%") == 0L) { "one of this test's families was kept" }
        }
    }

    @Test
    fun theBatchDeleteChecksAgainSoAFamilyThatGainedATokenSurvives() {
        // The batch's ids come from an earlier read; a family can gain a token before the delete runs. Handed such
        // an id, the delete must skip it rather than fail the FK and take the rest of the batch down with it.
        QuarkusTransaction.requiringNew().run {
            ensurePurgeUser()
            family(FAM_RECHECK_EMPTY)
            family(FAM_RECHECK_HELD)
            refresh("$FAM_RECHECK_HELD-r", FAM_RECHECK_HELD, RefreshTokenEntity.CURRENT, Instant.now().plus(1, ChronoUnit.DAYS))
        }

        val deleted = sessions.purgeEmptyFamilies(listOf(FAM_RECHECK_EMPTY, FAM_RECHECK_HELD))

        QuarkusTransaction.requiringNew().run {
            assert(SessionFamilyEntity.findById(FAM_RECHECK_HELD) != null) { "a family holding a token was deleted" }
            assert(SessionFamilyEntity.findById(FAM_RECHECK_EMPTY) == null) { "the token-less family in the batch was kept" }
        }
        assert(deleted == 1L) { "deleted $deleted, expected 1" }
    }

    private fun ensurePurgeUser() {
        if (VeyqUser.findById(PURGE_USER) == null) newUser(id = PURGE_USER, email = "purge-user@example.com").persist()
    }

    private fun family(id: String) {
        SessionFamilyEntity().apply {
            this.id = id
            userId = PURGE_USER
            status = SessionFamilyEntity.ACTIVE
            createdAt = Instant.now()
            lastUsedAt = Instant.now()
        }.persist()
    }

    private fun access(id: String, familyId: String, expiresAt: Instant) {
        AccessTokenEntity().apply {
            this.id = id
            this.familyId = familyId
            tokenHash = "h-$id"
            createdAt = Instant.now()
            this.expiresAt = expiresAt
        }.persist()
    }

    private fun refresh(id: String, familyId: String, status: String, expiresAt: Instant) {
        RefreshTokenEntity().apply {
            this.id = id
            this.familyId = familyId
            tokenHash = "h-$id"
            generation = 1
            this.status = status
            createdAt = Instant.now()
            this.expiresAt = expiresAt
        }.persist()
    }

    private fun challenge(email: String, expiresAt: Instant) {
        LoginChallengeEntity().apply {
            this.email = email
            nonce = "n-$email"
            this.expiresAt = expiresAt
        }.persist()
    }

    companion object {
        private const val PURGE_USER = "purge-user"
        private const val FAM_LIVE = "purge-fam-live"
        private const val FAM_EXPIRED = "purge-fam-expired"
        private const val FAM_SUPERSEDED = "purge-fam-superseded"
        private const val CHALLENGE_EXPIRED = "purge-expired@example.com"
        private const val CHALLENGE_LIVE = "purge-live@example.com"
        private const val FAM_BATCH = "purge-batch"
        private const val FAM_RECHECK_EMPTY = "purge-recheck-empty"
        private const val FAM_RECHECK_HELD = "purge-recheck-held"
    }
}
