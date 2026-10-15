package me.raddatz.veyq.core.platform.capabilities

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.DEVICE_LINKING
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.GROUP_SPACE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance pin (ADR 0032, ADR 0088): every capability id the backend ships as a constant must be a real entry in
 * the catalog (`contracts/platform/capabilities.json`) — the same drift-proofing
 * [me.raddatz.veyq.core.tasks.CapabilityTags] gets against its registry. It stays one-directional (code ⊆ catalog):
 * a catalog id may be reserved ahead of code (ADR 0032 §6). Plus the [CapabilityRegistry] arithmetic, the AND along
 * the path, against switches handed in directly. Pure JVM — no CDI needed.
 */
class CapabilityRegistryTest {

    private val catalogIds: Set<String> =
        ObjectMapper().readTree(File("../contracts/platform/capabilities.json"))
            .path("capabilities").fieldNames().asSequence().toSet()

    private fun registry(vararg off: String) = CapabilityRegistry { off.toSet() }

    @Test
    fun everyAdvertisedIdIsARealCatalogEntry() {
        // The code's ids must all exist in the catalog (which may hold reserved-ahead ids the code does not yet
        // reference). A failure means a shipped constant drifted from the registry.
        assertTrue(catalogIds.isNotEmpty(), "the catalog was read") // an unread file would make the loop vacuous
        CapabilityIds.ADVERTISED.forEach { id ->
            assertTrue(id in catalogIds, "pinned capability id '$id' is missing from contracts/platform/capabilities.json")
        }
    }

    @Test
    fun anAdvertisedIdsParentEntryIsAdvertisedToo() {
        // The registry ANDs over the entries it knows, which are the advertised ones. A parent the catalog holds but
        // this build does not advertise would drop out of that AND, and its child would be active without it.
        CapabilityIds.ADVERTISED.forEach { id ->
            val parent = id.substringBeforeLast('.', "")
            if (parent in catalogIds) assertTrue(parent in CapabilityIds.ADVERTISED, "'$id' is advertised, its parent '$parent' is not")
        }
    }

    @Test
    fun activeIsEveryAdvertisedIdWhenNothingIsSwitchedOff() {
        val registry = registry()
        assertEquals(CapabilityIds.ADVERTISED, registry.active)
        CapabilityIds.ADVERTISED.forEach { assertTrue(registry.isActive(it), it) }
        assertFalse(registry.isActive("core.not-built"), "an id this build does not advertise is never active")
    }

    @Test
    fun aSwitchedOffParentSwitchesOffItsChildrenAndNotItsSiblings() {
        val registry = registry(PUBLIC_SHARES)
        assertFalse(registry.isActive(PUBLIC_SHARES))
        assertFalse(registry.isActive(PUBLIC_SHARES_CONTRIBUTE), "a child needs its parent")
        assertFalse(registry.isActive(PUBLIC_SHARES_DROP_BOX), "a child needs its parent")
        assertTrue(registry.isActive(GROUP_SPACE), "a sibling entry stays active")
        assertEquals(
            listOf(GROUP_SPACE, REMEMBER_DEVICE, REMEMBER_DEVICE_PASSWORDLESS, DEVICE_LINKING),
            registry.active,
            "active keeps the advertised order, minus the switched-off entry and everything below it",
        )
    }

    @Test
    fun aSwitchedOffChildLeavesItsParentAndItsSiblingActive() {
        val registry = registry(PUBLIC_SHARES_CONTRIBUTE)
        assertFalse(registry.isActive(PUBLIC_SHARES_CONTRIBUTE))
        assertTrue(registry.isActive(PUBLIC_SHARES), "the AND runs up the path, never down")
        assertTrue(registry.isActive(PUBLIC_SHARES_DROP_BOX), "a sibling child stays active")
    }

    @Test
    fun aPrefixThatIsNoEntryIsNoAncestor() {
        // `core` is a root segment, not an entry: naming it switches nothing off.
        assertEquals(CapabilityIds.ADVERTISED, registry("core").active)
    }

    @Test
    fun theSwitchesAreAskedOnEveryCall() {
        var off = emptySet<String>()
        val registry = CapabilityRegistry { off }
        assertTrue(registry.isActive(DEVICE_LINKING))
        off = setOf(DEVICE_LINKING)
        assertFalse(registry.isActive(DEVICE_LINKING), "a switch acts without a new registry (inbox #443 needs that)")
        assertFalse(DEVICE_LINKING in registry.active)
    }
}
