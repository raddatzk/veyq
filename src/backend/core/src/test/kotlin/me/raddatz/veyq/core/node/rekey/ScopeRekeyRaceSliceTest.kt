package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.account.profile.AccountProfileHandler
import me.raddatz.veyq.core.account.profile.ProfileOutcome
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.spaces.scope.RotationDecision
import me.raddatz.veyq.core.spaces.scope.RotationGrant
import me.raddatz.veyq.core.spaces.scope.ScopeSuccession
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.TestScopeRekey
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A scope-level write racing a rotation's commit (ADR 0115 decisions 2-3), both orders forced: the commit swaps in the
 * staged profiles under the tenant scope's row lock, and a profile write decides its stated epoch under the same lock.
 * Held first, the commit lands and the write that waited on it reads the new epoch — `stale_epoch`, nothing written
 * under the replaced key. Held first, the write lands and the commit that waited on it finds the row changed since it
 * was staged — `rekey_parts_stale`, nothing rotated. Neither order leaves a name under the old key after the rotation.
 */
@QuarkusTest
class ScopeRekeyRaceSliceTest {

    @Inject
    lateinit var scopes: ScopesStore

    @Inject
    lateinit var profiles: AccountProfileHandler

    @Inject
    lateinit var profileRows: TenantProfileStore

    @Test
    fun aProfileWriteThatWaitsOnTheCommitIsStaleAndWritesNothing() = raceRounds { round ->
        val (owner, tenantId) = tenant()
        val rekeyId = TestScopeRekey.stageAll(tenantId)
        val outcome = whileUncommitted(
            change = { assertTrue(rotate(tenantId, owner, rekeyId) is RotationDecision.Commit, "round $round") },
            overlapping = { profiles.update(owner, tenantId, null, "bGF0ZS1uYW1l", 0, null) },
        )
        assertEquals(ProfileOutcome.StaleEpoch, outcome, "round $round: a name sealed under the replaced key landed")
        assertEquals(NAME, nameOf(owner, tenantId), "round $round: the commit's re-sealed name stands")
        assertEquals(1, TestGrants.epochOf(tenantId), "round $round")
    }

    @Test
    fun aRotationThatWaitsOnAProfileWriteFindsItsStageStaleAndRotatesNothing() = raceRounds { round ->
        val (owner, tenantId) = tenant()
        val rekeyId = TestScopeRekey.stageAll(tenantId)
        val decision = whileUncommitted(
            // The handler's write in the held transaction: its epoch decided under the tenant scope's lock, then the row.
            change = {
                assertTrue(profileRows.atCurrentEpoch(tenantId, 0), "round $round")
                profileRows.update(owner, tenantId, null, "ZWFybHktbmFtZQ==")
            },
            overlapping = { rotate(tenantId, owner, rekeyId) },
        )
        assertEquals(RotationDecision.PartsStale, decision, "round $round: a stage of the replaced name was swapped in")
        assertEquals("ZWFybHktbmFtZQ==", nameOf(owner, tenantId), "round $round: the write stands")
        assertEquals(0, TestGrants.epochOf(tenantId), "round $round: nothing rotated")
        // Control: re-staged against what the row holds now, the same rotation commits.
        assertTrue(rotate(tenantId, owner, TestScopeRekey.stageAll(tenantId)) is RotationDecision.Commit, "round $round")
    }

    /** A tenant whose owner holds a sealed name, at epoch 0. */
    private fun tenant(): Pair<String, String> {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        QuarkusTransaction.requiringNew().run {
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString(); userId = owner; this.tenantId = tenantId; username = "o${owner.take(8)}"
                sealedName = NAME
            }.persist()
        }
        return owner to tenantId
    }

    private fun rotate(tenantId: String, owner: String, rekeyId: String): RotationDecision = scopes.rotate(
        tenantId, 1, ScopeSuccession(1, TestScopeRekey.SUCCESSION_PUBLIC_KEY, TestGrants.SIGNATURE), rekeyId, owner,
        listOf(RotationGrant(owner, SignedGrant("bmV3", TestGrants.SIGNATURE), TestGrants.digestOf(owner))),
    )

    private fun nameOf(userId: String, tenantId: String): String? = QuarkusTransaction.requiringNew().call {
        TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!.sealedName
    }

    private companion object {
        const val NAME = "bmFtZQ=="
    }
}
