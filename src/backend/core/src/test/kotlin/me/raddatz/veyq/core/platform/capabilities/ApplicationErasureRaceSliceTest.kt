package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Two levels switching one application at once (uc.platform.erase-switched-off-application A3 and A4, the race half of
 * ApplicationErasureSliceTest's (f)):
 * the erasure row is what the final state implies, in every order the hold forces. Each writer reads the OTHER level's
 * switch; without [ApplicationErasureStore]'s lock order (the instance row, then the tenant row) each reads the other's
 * switch as it was before the other committed, and the race ends with an application switched on in both levels and a
 * row that erases it on its day — or switched off with no row, kept for good. Found red with each level taking only its
 * own row's lock (the switch stores as they were before the erasure row existed), green with the order.
 */
@QuarkusTest
class ApplicationErasureRaceSliceTest {

    @Inject lateinit var switches: CapabilitySwitchStore
    @Inject lateinit var tenantSwitches: TenantCapabilitySwitchStore
    @Inject lateinit var tenantStore: TenantStore

    private val now: Instant = Instant.now()

    @AfterEach
    fun switchEverythingBackOn() {
        TenantCapabilitySwitchFixtures.clear()
        CapabilitySwitchFixtures.clear()
        QuarkusTransaction.requiringNew().run { ApplicationErasureEntity.deleteAll() }
    }

    /** The tenant switches on while the instance switch-on is held uncommitted: no row may stay. */
    @Test
    fun aTenantSwitchOnOverlappingAnInstanceSwitchOnLeavesNoRow() = raceRounds { round ->
        val tenant = offAtBothLevels()
        whileUncommitted(
            change = { switches.set(DEPOT, false, now) },
            overlapping = { QuarkusTransaction.requiringNew().run { tenantSwitches.set(tenant, DEPOT, false, now) } },
        )
        assertEquals(null, row(tenant), "round $round: on at both levels, and a row still erases the tenant's Depot")
    }

    /** The instance switches on while the tenant switch-on is held uncommitted: no row may stay. */
    @Test
    fun anInstanceSwitchOnOverlappingATenantSwitchOnLeavesNoRow() = raceRounds { round ->
        val tenant = offAtBothLevels()
        whileUncommitted(
            change = { tenantSwitches.set(tenant, DEPOT, false, now) },
            overlapping = { QuarkusTransaction.requiringNew().run { switches.set(DEPOT, false, now) } },
        )
        assertEquals(null, row(tenant), "round $round: on at both levels, and a row still erases the tenant's Depot")
    }

    /** A tenant is created while the instance switch-off is held uncommitted: the new tenant must get its row. */
    @Test
    fun aTenantCreatedOverlappingAnInstanceSwitchOffGetsItsRow() = raceRounds { round ->
        val tenant = whileUncommitted(
            change = { switches.set(DEPOT, true, now) },
            overlapping = {
                tenantStore.createPendingTenant(
                    "Race", "race-${UUID.randomUUID()}.example.com", "o-${UUID.randomUUID()}@example.com",
                    "h-${UUID.randomUUID()}", Instant.now().plus(Duration.ofDays(1)),
                ).first
            },
        )
        assertNotNull(row(tenant), "round $round: Depot is off for the instance, and the new tenant has no day")
        QuarkusTransaction.requiringNew().run { switches.set(DEPOT, false, now) }
    }

    private fun offAtBothLevels(): String {
        CapabilitySwitchFixtures.clear()
        val tenant = seedTenant(owner = seedUser())
        QuarkusTransaction.requiringNew().run { tenantSwitches.set(tenant, DEPOT, true, now) }
        QuarkusTransaction.requiringNew().run { switches.set(DEPOT, true, now) }
        assertNotNull(row(tenant))
        return tenant
    }

    private fun row(tenantId: String): ApplicationErasureEntity? =
        QuarkusTransaction.requiringNew().call { ApplicationErasureEntity.findById(ApplicationErasureKey(tenantId, DEPOT)) }

    private companion object {
        const val DEPOT = "depot"
    }
}
