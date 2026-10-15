package me.raddatz.veyq.core.platform.capabilities

import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * The test seam that switches capability entries off (ADR 0088). Production cannot reach it: no built instance can
 * switch an id off until the Admin API switches land (inbox #443, see [CapabilitySwitches]), and nothing outside the
 * test source sets may change that.
 *
 * **A plain `@Alternative`, never `@Priority`.** With a priority it would be enabled in every test of every project
 * that consumes these fixtures, and every default-profile test would run against a mutable switch set.
 * [CapabilitySwitchesProfile] enables it per test class, and `CapabilityManifestSliceTest` proves the default profile
 * still wires [NoCapabilitySwitchedOff].
 *
 * **Shared state, restored in the same edit.** One instance lives per Quarkus boot, and every class naming the profile
 * shares that boot, so a test that switches something off restores it itself — use [with], which does.
 */
@Alternative
@ApplicationScoped
class SwitchableCapabilities : CapabilitySwitches {
    @Volatile
    private var off: Set<String> = emptySet()

    override fun switchedOff(): Set<String> = off

    /** Runs [block] with exactly [ids] switched off, then switches everything back on, even when [block] throws. */
    fun <T> with(vararg ids: String, block: () -> T): T {
        check(off.isEmpty()) { "a previous test left $off switched off" }
        off = ids.toSet()
        try {
            return block()
        } finally {
            off = emptySet()
        }
    }
}

/** The `@TestProfile` that enables [SwitchableCapabilities]. Every class that names it shares one Quarkus boot. */
class CapabilitySwitchesProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(SwitchableCapabilities::class.java)
}
