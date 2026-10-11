package me.raddatz.veyq.core.persistence

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Hibernate's own schema validation, run against what Flyway built: every entity column must match the column the
 * baselines declare. Production runs `schema-management.strategy=none`, so only a dev boot validates (Quarkus'
 * `validate-in-dev-mode`) and a drift between an entity and a migration — for instance a `UUID` column (inbox #387)
 * mapped as a `String` with `length = 36` — would stay an ERROR in somebody's IntelliJ log. Here the strategy is
 * `validate` for this one profile, so the boot itself fails on a drift: on H2 (the default) and, in
 * `scripts/run_backend_races_on_mariadb.py`, on the production engine, whose column type names differ from H2's.
 * An id attribute is `@Column(columnDefinition = "uuid")`, never `length = 36`: the validator compares that name with
 * the database's type name.
 */
@QuarkusTest
@TestProfile(EntitySchemaValidationTest.ValidateProfile::class)
class EntitySchemaValidationTest {

    class ValidateProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("quarkus.hibernate-orm.schema-management.strategy" to "validate")
    }

    @Inject
    lateinit var em: EntityManager

    @Test
    fun everyEntityMatchesTheSchemaFlywayBuilt() {
        // The boot already validated (a mismatch fails it); reaching here with a live EntityManager is the proof.
        assertNotNull(em)
    }
}
