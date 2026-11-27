package me.raddatz.veyq.mobile.session.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core's capability tags are the registry's (`contracts/tasks/tags/v1.json`, the drift-proofing its note asks of every
 * surface): each the registry's value, and each the first 4 bytes of SHA-256 over `veyq/v1/task-capability/<name>`.
 */
class TaskTagsTest {
    @Test
    fun theTagsAreTheRegistrys() {
        val root = File(System.getProperty("veyq.repoRoot"))
        val tags = Json.parseToJsonElement(root.resolve("contracts/tasks/tags/v1.json").readText()).jsonObject.getValue("tags").jsonObject
        val ours = mapOf("grant-delivery" to CapabilityTags.GRANT_DELIVERY, "scope-rotation" to CapabilityTags.SCOPE_ROTATION)
        assertEquals(ours.keys, tags.keys, "a tag the registry adds is ported or named here")
        for ((name, tag) in ours) {
            assertEquals(tags.getValue(name).jsonObject.getValue("tag").jsonPrimitive.content, tag, name)
            val derived = MessageDigest.getInstance("SHA-256").digest("veyq/v1/task-capability/$name".toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            assertEquals(derived, tag, name)
        }
    }
}
