package me.raddatz.veyq.core.platform.capabilities

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.config.testVeyqConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance pin (cr-0103, ADR 0032): every capability id the backend ships as a constant must be a real entry
 * in the append-only catalog (`contracts/platform/capabilities.json`) — the same drift-proofing
 * [me.raddatz.veyq.core.tasks.CapabilityTags] gets against its registry. Plus the [CapabilityRegistry]
 * active-set arithmetic (the advertised set minus the operator's disabled list). Pure JVM — no CDI needed.
 */
class CapabilityRegistryTest {

    private val catalogIds: Set<String> =
        ObjectMapper().readTree(File("../contracts/platform/capabilities.json"))
            .path("capabilities").fieldNames().asSequence().toSet()

    @Test
    fun everyAdvertisedIdIsARealCatalogEntry() {
        // Append-only: the code's ids must all exist in the catalog (which may hold reserved-ahead ids the code
        // does not yet reference). A failure means a shipped constant drifted from the registry.
        CapabilityIds.ADVERTISED.forEach { id ->
            assertTrue(id in catalogIds, "pinned capability id '$id' is missing from contracts/platform/capabilities.json")
        }
    }

    @Test
    fun activeIsEveryAdvertisedIdWhenNothingDisabled() {
        val registry = CapabilityRegistry(testVeyqConfig())
        assertEquals(CapabilityIds.ADVERTISED, registry.active)
        assertTrue(registry.isActive(CapabilityIds.SHARING_PUBLIC_LINKS))
    }

    @Test
    fun disabledIdsAreSubtractedFromActive() {
        val registry = CapabilityRegistry(
            testVeyqConfig(
                mapOf(
                    "veyq.capabilities.disabled" to
                        listOf(CapabilityIds.SHARING_UPLOADER_LINKS, "  ", CapabilityIds.SPACES_GROUPS).joinToString(","),
                ),
            ),
        )
        assertFalse(registry.isActive(CapabilityIds.SHARING_UPLOADER_LINKS))
        assertFalse(registry.isActive(CapabilityIds.SPACES_GROUPS))
        assertTrue(registry.isActive(CapabilityIds.SHARING_PUBLIC_LINKS), "an untouched id stays active")
        assertEquals(
            listOf(CapabilityIds.SHARING_PUBLIC_LINKS, CapabilityIds.ACCOUNT_DEVICE_TRUST),
            registry.active,
            "active keeps catalog order, minus the disabled ids (blank entries ignored)",
        )
    }
}
