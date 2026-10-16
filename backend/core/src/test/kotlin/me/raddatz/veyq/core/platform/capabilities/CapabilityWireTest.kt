package me.raddatz.veyq.core.platform.capabilities

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [capabilityWire] puts every catalog requirement on its own entry and on no other (ADR 0088 decision 8). It names each
 * property by hand, so a requirement added to the catalog and the contract but not to the function would be stored,
 * enforced and never told to a client: this reads the generated list and is red on the one dropped. Pure JVM.
 */
class CapabilityWireTest {

    private val json = ObjectMapper()

    @Test
    fun everyRequirementIsCarriedOnItsEntryAndOnNoOther() {
        assertTrue(CapabilityRequirements.ALL.isNotEmpty())
        CapabilityRequirements.ALL.forEach { def ->
            val value = if (def.kind == RequirementKind.FLAG) 1 else 9
            val expected: Any = if (def.kind == RequirementKind.FLAG) true else 9
            val own = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(capabilityWire(def.capabilityId, mapOf(def.name to value)))
            assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected), own.path(def.name), "${def.name} on ${def.capabilityId}")
            CapabilityIds.ADVERTISED.filter { it != def.capabilityId }.forEach { other ->
                val elsewhere = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(capabilityWire(other, mapOf(def.name to value)))
                assertTrue(elsewhere.path(def.name).isNull || elsewhere.path(def.name).isMissingNode, "${def.name} leaked onto $other")
            }
        }
    }
}
