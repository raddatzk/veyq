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
 * The [CapabilityRegistry] arithmetic, the AND along the path, against switches handed in directly (ADR 0088), and the
 * one check on the register's generation: [CapabilityIds] and [CapabilityTexts] are built from the catalog
 * (`contracts/platform/capabilities.json`, buildSrc's `GenerateCapabilityRegister`), so nothing here pins an id by
 * hand — the test reads the catalog and asks that the build wired the generator to it: every entry, in its order, each
 * with its words. Pure JVM — no CDI needed.
 */
class CapabilityRegistryTest {

    private val catalog = ObjectMapper().readTree(File("../contracts/platform/capabilities.json")).path("capabilities")

    private val catalogIds: List<String> = catalog.fieldNames().asSequence().toList()

    private fun registry(vararg off: String) = CapabilityRegistry { off.toSet() }

    @Test
    fun theRegisterIsEveryCatalogEntryInCatalogOrderWithItsWords() {
        // Every catalog entry is built (validate_capabilities.py), so every one is advertised, in the catalog's order —
        // the manifest's and the console's. A red here is a generator that read another file or dropped an entry.
        assertTrue(catalogIds.isNotEmpty(), "the catalog was read") // an unread file would make the comparison vacuous
        assertEquals(catalogIds, CapabilityIds.ADVERTISED)
        assertEquals(catalogIds.toSet(), CapabilityTexts.BY_ID.keys)
        catalogIds.forEach { id ->
            val texts = CapabilityTexts.BY_ID.getValue(id)
            assertEquals(catalog.path(id).path("whenOff").path("de").asText(), texts.whenOff.de, "$id: whenOff.de")
            assertEquals(catalog.path(id).path("title").path("en").asText(), texts.title.en, "$id: title.en")
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
        assertFalse(registry.isActive(DEVICE_LINKING), "a switch acts without a new registry, so without a restart")
        assertFalse(DEVICE_LINKING in registry.active)
    }
}
