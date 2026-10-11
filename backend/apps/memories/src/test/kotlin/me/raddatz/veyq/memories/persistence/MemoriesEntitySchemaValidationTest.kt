package me.raddatz.veyq.memories.persistence

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The same schema validation as `:core`'s `EntitySchemaValidationTest`, over this module's classpath: the core and the
 * Memories baselines, the core and the Memories entities. Production runs `schema-management.strategy=none`; here the
 * strategy is `validate`, so the boot fails when an entity and a migration disagree (a `UUID` column mapped without
 * `columnDefinition = "uuid"`, a renamed column, a missing one).
 */
@QuarkusTest
@TestProfile(MemoriesEntitySchemaValidationTest.ValidateProfile::class)
class MemoriesEntitySchemaValidationTest {

    class ValidateProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("quarkus.hibernate-orm.schema-management.strategy" to "validate")
    }

    @Inject
    lateinit var em: EntityManager

    @Test
    fun everyEntityMatchesTheSchemaFlywayBuilt() {
        assertNotNull(em)
    }
}
