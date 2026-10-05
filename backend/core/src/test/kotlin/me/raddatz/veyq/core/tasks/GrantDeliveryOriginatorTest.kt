package me.raddatz.veyq.core.tasks

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.GRANT
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The scheduled grant-delivery backstop, through its real entry `GrantDeliveryOriginator.run()` (ADR 0021,
 * cr-0076, cr-0093). Its own logic is the scope selection in `TasksStore.reconcileAllGrantDelivery`: grant-less
 * scopes ∪ rotated scopes. Each membership below is behind (or not) for exactly one reason:
 *
 * - Scope A (epoch 0): M1 grant-less; M2 grant-less but `pendingConsent`; M3 granted; M6 grant-less with a PENDING
 *   job already. A is selected only because it holds grant-less members.
 * - Scope B (epoch 2): M4 granted at epoch 1 (stale); M5 granted at epoch 2. B holds **no** grant-less member, so
 *   it is selected only because it was rotated — which is what makes M4 the witness for the `rotated` half.
 * - Scope C: MF, a grant-less member no assertion reads before the restore. It stands in for another test's
 *   leftover row: `run()` walks the whole shared database, so it originates jobs far outside this fixture.
 *
 * Every count is by `resourceRef`, never `run()`'s return value, because that value includes other tests' rows.
 *
 * **The restore is the guarantee** (changes/README.md § a test that mutates shared state writes its restore in
 * the same edit): every grant-delivery job that did not exist before this test is deleted in a `finally`, whatever
 * membership it belongs to, so no later test can see one. The last assertions prove it ran: MF had a job before
 * the restore and has none after, and no grant-delivery job exists outside the snapshot.
 */
@QuarkusTest
class GrantDeliveryOriginatorTest {

    @Inject
    lateinit var originator: GrantDeliveryOriginator

    @Test
    fun runOriginatesOneJobPerBehindMembershipAcrossGrantlessAndRotatedScopesAndIsIdempotent() {
        val before = grantDeliveryJobIds()
        lateinit var mf: String
        try {
            val tenant = seedTenant(tenantScope = false)
            val a = scope(tenant, epoch = 0)
            val b = scope(tenant, epoch = 2)
            val c = scope(tenant, epoch = 0)
            val m1 = membership(tenant, a, grantedEpoch = null)
            val m2 = membership(tenant, a, grantedEpoch = null, pendingConsent = true)
            val m3 = membership(tenant, a, grantedEpoch = 0)
            val m6 = membership(tenant, a, grantedEpoch = null)
            pendingJob(a, m6)
            val m4 = membership(tenant, b, grantedEpoch = 1)
            val m5 = membership(tenant, b, grantedEpoch = 2)
            mf = membership(tenant, c, grantedEpoch = null)

            repeat(2) { round ->
                originator.run()
                val after = if (round == 0) "after run()" else "after a second run()"
                assertEquals(1L, pendingJobs(m1), "M1 (grant-less) has exactly one job $after")
                assertEquals(1L, pendingJobs(m4), "M4 (stale in a rotated scope) has exactly one job $after")
                assertEquals(0L, pendingJobs(m2), "M2 (pendingConsent) has none $after")
                assertEquals(0L, pendingJobs(m3), "M3 (granted at the current epoch) has none $after")
                assertEquals(0L, pendingJobs(m5), "M5 (granted at the rotated epoch) has none $after")
                assertEquals(1L, pendingJobs(m6), "M6 (already has a live job) still has exactly one $after")
            }
            assertEquals(1L, pendingJobs(mf), "MF has a job before the restore, so the restore check below is not vacuous")
        } finally {
            QuarkusTransaction.requiringNew().run {
                JobEntity.delete("capabilityTag = ?1 and id not in ?2", CapabilityTags.GRANT_DELIVERY, before.ifEmpty { setOf("") })
            }
        }
        assertEquals(0L, pendingJobs(mf), "the restore removed the job originated for a row outside this fixture")
        assertTrue((grantDeliveryJobIds() - before).isEmpty(), "no grant-delivery job outlives this test")
    }

    private fun scope(tenantId: String, epoch: Int): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                this.id = id; this.tenantId = tenantId; kind = ScopeEntity.GROUP; currentEpoch = epoch; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** A membership for a fresh account; [grantedEpoch] null means grant-less. Returns the membership id. */
    private fun membership(tenantId: String, scopeId: String, grantedEpoch: Int?, pendingConsent: Boolean = false): String {
        val userId = seedUser()
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                this.id = id; this.userId = userId; this.tenantId = tenantId; this.scopeId = scopeId
                role = MembershipEntity.MEMBER
                wrappedGrant = if (grantedEpoch == null) null else GRANT
                this.grantedEpoch = grantedEpoch ?: 0
                this.pendingConsent = pendingConsent
                createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun pendingJob(scopeId: String, membershipId: String) {
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; capabilityTag = CapabilityTags.GRANT_DELIVERY
                resourceRef = membershipId; status = JobEntity.PENDING; attempts = 0; maxAttempts = 5; createdAt = Instant.now()
            }.persist()
        }
    }

    private fun pendingJobs(membershipId: String): Long = QuarkusTransaction.requiringNew().call {
        JobEntity.count(
            "resourceRef = ?1 and capabilityTag = ?2 and status = ?3",
            membershipId, CapabilityTags.GRANT_DELIVERY, JobEntity.PENDING,
        )
    }

    private fun grantDeliveryJobIds(): Set<String> = QuarkusTransaction.requiringNew().call {
        JobEntity.find("capabilityTag", CapabilityTags.GRANT_DELIVERY).list().map { it.id }.toSet()
    }
}
