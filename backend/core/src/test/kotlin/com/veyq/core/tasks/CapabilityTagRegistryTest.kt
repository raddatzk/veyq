package com.veyq.core.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance pin (cr-0076): the opaque tag the backend ships must equal the public registry
 * (`contracts/tasks/tags/v1.json`), the same drift-proofing the crypto vectors get. If someone re-derives or
 * re-assigns a tag, this fails before a worker and the server silently stop matching.
 */
class CapabilityTagRegistryTest {

    @Test
    fun grantDeliveryTagMatchesTheRegistry() {
        val registry = File("../contracts/tasks/tags/v1.json")
        val root = ObjectMapper().readTree(registry)
        val fromRegistry = root.path("tags").path("grant-delivery").path("tag").asText()
        assertEquals(fromRegistry, CapabilityTags.GRANT_DELIVERY, "shipped tag drifted from the registry")
    }

    @Test
    fun scopeRotationTagMatchesTheRegistry() {
        val registry = File("../contracts/tasks/tags/v1.json")
        val root = ObjectMapper().readTree(registry)
        val fromRegistry = root.path("tags").path("scope-rotation").path("tag").asText()
        assertEquals(fromRegistry, CapabilityTags.SCOPE_ROTATION, "shipped tag drifted from the registry")
    }
}
