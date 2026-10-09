package me.raddatz.veyq.core.account.pairing

import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.security.SecureRandom
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A delegation and a channel's expiry (inbox #321, ADR 0059 §8): a family a channel delegated is either handed over or
 * revoked when the channel expires — never left live with its refresh token only in the delegating device's memory.
 *
 * Two halves of one window. The delegation's claim decides the channel's TTL again at the claim ([PairingStore.claimDelegation]),
 * since its liveness read came before the account lock; and the sweep reads the overdue channels under their lock
 * ([PairingStore.expireOverdue]), so a delegation it overlaps has bound its family by the time the sweep reads it.
 */
@QuarkusTest
class PairingExpiryRaceSliceTest {

    @Inject lateinit var store: PairingStore
    @Inject lateinit var pairings: PairingHandler
    @Inject lateinit var purge: PairingPurge
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var accounts: AccountStore

    /** A channel that expired after the delegation's liveness read and before its claim: the claim takes nothing. */
    @Test
    fun aClaimPastTheChannelsTtlTakesNothing() {
        val userId = seedUser()
        val id = answeredChannel()
        val expiresAt = tx { DevicePairingEntity.findById(id)!!.expiresAt }
        assertFalse(store.claimDelegation(id, userId, now = expiresAt.plusMillis(1)), "a claim past the TTL")
        assertEquals(null, tx { DevicePairingEntity.findById(id)!!.userId }, "nothing claimed")
        assertTrue(store.claimDelegation(id, userId, now = expiresAt.minusMillis(1)), "control: inside the TTL it claims")
    }

    /**
     * The sweep reaches a channel while a delegation on it is uncommitted — claimed inside its TTL, the family minted
     * and bound, its commit held. The sweep's read waits for that commit and revokes the family with the channel.
     * Read plainly, it saw no family, flipped the channel after the commit and revoked nothing.
     */
    @Test
    fun aSweepOverlappingADelegationRevokesTheFamilyItMinted() {
        repeat(RACE_ROUNDS) { round ->
            val userId = seedUser()
            val id = answeredChannel()
            // The sweep's clock just past the TTL — not further: a clock past the retention would delete the row too.
            val overdue = tx { DevicePairingEntity.findById(id)!!.expiresAt }.plusSeconds(1)
            var family = ""
            whileUncommitted(
                change = {
                    // `PairingHandler.delegateLocked`'s writes, held as its store calls: `delegate` opens its own
                    // transaction and would commit inside the hold.
                    accounts.lock(userId)
                    check(store.claimDelegation(id, userId)) { "round $round: the held claim" }
                    family = sessions.issueDelegated(userId).familyId
                    store.bindDelegation(id, family)
                },
                overlapping = { purge.sweep(overdue) },
            )
            assertEquals(DevicePairingEntity.EXPIRED, tx { DevicePairingEntity.findById(id)!!.status }, "round $round")
            assertEquals(SessionFamilyEntity.REVOKED, tx { SessionFamilyEntity.findById(family)!!.status }, "round $round: a delegated family outlived its channel")
        }
    }

    // --- helpers ---

    /** A channel a new device opened and answered — the state a trusted device delegates from. Returns its row id. */
    private fun answeredChannel(): String {
        val handle = Base64.getEncoder().encodeToString(ByteArray(32).also { RNG.nextBytes(it) })
        pairings.open(handle)
        check(pairings.answer(handle, "YW5zd2Vy")) { "answer" }
        return checkNotNull(store.findByHandleHash(Tokens.hash(handle))).id
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        val RNG = SecureRandom()
    }
}
