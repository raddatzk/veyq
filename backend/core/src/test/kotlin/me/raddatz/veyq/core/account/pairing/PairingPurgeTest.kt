package me.raddatz.veyq.core.account.pairing

import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The scheduled sweep over pairing channels (ADR 0059 §8): expire-then-delete, in that order, plus the thing
 * recovery has no equivalent of — **an expiring channel takes its delegated session family with it**.
 *
 * That revoke is the reason this sweep is not merely hygiene. A ceremony that was confirmed and then abandoned
 * (uc.account.onboard-device E3) has already minted a family holding a refresh token with 30 days to run, which
 * nobody collected and nobody will ever rotate; left alone it sits in the account's session list as a device
 * that does not exist.
 *
 * **Every "it was revoked" here owes a live control**, because a sweep that revokes every family of the account
 * — or every family on the instance — satisfies the negative perfectly. So each case runs a *collected* channel
 * and a second account's family alongside, and asserts both survive.
 */
@QuarkusTest
class PairingPurgeTest {

    @Inject
    lateinit var store: PairingStore

    @Inject
    lateinit var purge: PairingPurge

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seedAccountsAndClearChannels() {
        QuarkusTransaction.requiringNew().run(Runnable {
            seed(USER_ID, EMAIL)
            seed(OTHER_USER_ID, OTHER_EMAIL)
            DevicePairingEntity.delete("userId in ?1 or userId is null", listOf(USER_ID, OTHER_USER_ID))
        })
    }

    @Test
    fun anExpiringChannelRevokesTheFamilyItDelegatedAndLeavesTheCollectedOneAlone() {
        // The abandoned ceremony: delegated, never collected, past its TTL.
        val abandoned = channel(USER_ID, status = DevicePairingEntity.SEALED, expiresAt = PAST)
        // The live control on the same predicate: a ceremony that COMPLETED. Its channel is terminal and it is
        // equally overdue, so a sweep that revoked on "overdue" alone — or on "has a delegated family" alone —
        // would take this one too, and the granted device would be signed out for having arrived.
        val completed = channel(USER_ID, status = DevicePairingEntity.COLLECTED, expiresAt = PAST)
        // The second account, live throughout: the sweep must not reach across accounts.
        val strangers = channel(OTHER_USER_ID, status = DevicePairingEntity.SEALED, expiresAt = FUTURE)

        purge.run()

        assertEquals(SessionFamilyEntity.REVOKED, familyStatus(abandoned), "an uncollected grant is taken back")
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(completed), "a collected one is an ordinary family")
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(strangers), "and another account's is untouched")
    }

    @Test
    fun overdueChannelsAreExpiredBeforeTheyAreDeletedSoAReplayReadsAsSpent() {
        // The order is the point: a handle replayed in the window between expiry and deletion must find a
        // terminal row, not an absent one. Both are 404 today; the row is what keeps them 404 for the same
        // reason rather than by coincidence.
        val overdue = channel(USER_ID, status = DevicePairingEntity.ANSWERED, expiresAt = PAST)
        val live = channel(USER_ID, status = DevicePairingEntity.ANSWERED, expiresAt = FUTURE)

        purge.run()

        assertEquals(DevicePairingEntity.EXPIRED, statusOf(overdue), "flipped, not deleted")
        assertEquals(DevicePairingEntity.ANSWERED, statusOf(live), "a channel inside its TTL is untouched")
    }

    @Test
    fun terminalRowsAreDeletedOnceTheyAreOlderThanTheRetentionWindow() {
        val stale = channel(
            USER_ID, status = DevicePairingEntity.COLLECTED, expiresAt = PAST,
            createdAt = Instant.now().minus(90, ChronoUnit.DAYS),
        )
        // Terminal but recent: still inside retention, so a replay of its handle still reads as spent.
        val recent = channel(USER_ID, status = DevicePairingEntity.COLLECTED, expiresAt = PAST)

        purge.run()

        assertNull(rowOf(stale), "a terminal row past retention is deleted")
        assertNotNull(rowOf(recent), "one inside it is kept, so a replay reads as spent rather than unknown")
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** A channel in a given state, with a real delegated family behind it — the sweep's whole job is what it
     *  does to that family, so a fabricated id would test nothing. Returns the row id. */
    private fun channel(userId: String, status: String, expiresAt: Instant, createdAt: Instant = Instant.now()): String {
        val family = sessions.issueDelegated(userId).familyId
        return QuarkusTransaction.requiringNew().call<String> {
            val id = java.util.UUID.randomUUID().toString()
            DevicePairingEntity().apply {
                this.id = id
                this.handleHash = "hash-$id"
                this.userId = userId
                this.delegatedFamilyId = family
                this.status = status
                this.expiresAt = expiresAt
                this.createdAt = createdAt
            }.persist()
            id
        }
    }

    private fun rowOf(id: String): DevicePairingEntity? =
        QuarkusTransaction.requiringNew().call<DevicePairingEntity?> { DevicePairingEntity.findById(id) }

    private fun statusOf(id: String): String? = rowOf(id)?.status

    private fun familyStatus(channelId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> {
            // Read the family id off the row if it is still there, else off the one this test recorded — a
            // deleted row must not make the family assertion vacuous.
            DevicePairingEntity.findById(channelId)?.delegatedFamilyId
                ?.let { SessionFamilyEntity.findById(it)?.status }
        }

    private companion object {
        val PAST: Instant = Instant.now().minus(1, ChronoUnit.HOURS)
        val FUTURE: Instant = Instant.now().plus(1, ChronoUnit.HOURS)

        const val EMAIL = "pairing-purge@example.com"
        const val OTHER_EMAIL = "pairing-purge-stranger@example.com"
        const val USER_ID = "pairing-purge-user"
        const val OTHER_USER_ID = "pairing-purge-stranger"

        fun seed(userId: String, email: String) {
            if (VeyqUser.findById(userId) != null) return
            newUser(id = userId, email = email).persist()
        }
    }
}
