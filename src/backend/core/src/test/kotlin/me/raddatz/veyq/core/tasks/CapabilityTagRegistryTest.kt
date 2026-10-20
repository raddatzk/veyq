package me.raddatz.veyq.core.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Conformance pin (cr-0076): the opaque tag the backend ships must equal the public registry
 * (`contracts/tasks/tags/v1.json`), the same drift-proofing the crypto vectors get. If someone re-derives or
 * re-assigns a tag, this fails before a worker and the server silently stop matching.
 */
class CapabilityTagRegistryTest {

    @Test
    fun grantDeliveryTagMatchesTheRegistry() {
        val registry = File("../../contracts/tasks/tags/v1.json")
        val root = ObjectMapper().readTree(registry)
        val fromRegistry = root.path("tags").path("grant-delivery").path("tag").asText()
        assertEquals(fromRegistry, CapabilityTags.GRANT_DELIVERY, "shipped tag drifted from the registry")
    }

    @Test
    fun scopeRotationTagMatchesTheRegistry() {
        val registry = File("../../contracts/tasks/tags/v1.json")
        val root = ObjectMapper().readTree(registry)
        val fromRegistry = root.path("tags").path("scope-rotation").path("tag").asText()
        assertEquals(fromRegistry, CapabilityTags.SCOPE_ROTATION, "shipped tag drifted from the registry")
    }

    /**
     * The tags a client may not enqueue are stated three times: the registry's `originator` (`server-structural`,
     * `server-event`), the front door's [CapabilityTags.SERVER_ORIGINATED], and the contract's `not: { enum }` on
     * `EnqueueRequest.capabilityTag` (inbox #507), which is what makes a generated request the schema admits one the
     * server accepts. A registry tag added for the server, missing from either, fails here.
     */
    @Test
    fun theServerOriginatedTagsAreTheContractsAndTheFrontDoorsAlike() {
        val registry = ObjectMapper().readTree(File("../../contracts/tasks/tags/v1.json")).path("tags")
        val fromRegistry = registry.fields().asSequence()
            .filter { (_, tag) -> tag.path("originator").asText().startsWith("server-") }
            .map { (_, tag) -> tag.path("tag").asText() }
            .toSet()
        @Suppress("UNCHECKED_CAST")
        val schemas = (Yaml().load<Map<String, Any?>>(File("../../contracts/api/tasks.openapi.yaml").readText())["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val tag = ((schemas["EnqueueRequest"] as Map<String, Any?>)["properties"] as Map<String, Any?>)["capabilityTag"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val refused = ((tag["not"] as Map<String, Any?>)["enum"] as List<String>).toSet()
        assertEquals(fromRegistry, refused, "the contract's refused tags drifted from the registry")
        assertEquals(fromRegistry, CapabilityTags.SERVER_ORIGINATED, "the front door's refused tags drifted from the registry")
    }
}
