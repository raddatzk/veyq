package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
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
import java.time.Instant

/**
 * Two levels switching one application at once (uc.platform.erase-switched-off-application A3, the race half of
 * ApplicationErasureSliceTest's (f)), in a tenant that holds a node of it (a row exists only there, ADR 0088 decision 5;
 * a tenant's creation makes none, so it races nothing):
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

    private fun offAtBothLevels(): String {
        CapabilitySwitchFixtures.clear()
        val tenant = seedTenant(owner = seedUser())
        // A row exists only where the tenant holds a node of the application (ADR 0088 decision 5).
        seedNode(tenant, CoreTestApplicationKinds.of(DEPOT), wrappedContentKey = CONTENT_KEY)
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
