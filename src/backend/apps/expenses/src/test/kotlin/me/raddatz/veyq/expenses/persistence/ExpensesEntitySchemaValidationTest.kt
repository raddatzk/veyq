package me.raddatz.veyq.expenses.persistence

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The same schema validation as `:core`'s `EntitySchemaValidationTest`, over this module's classpath: core's migrations
 * and Expenses' (`veyq_expenses_revision`), core's entities and `PotRevisionEntity`. Production runs
 * `schema-management.strategy=none`; here it is `validate`, so the boot fails when the entity and the migration disagree
 * (a `UUID` column mapped without `columnDefinition = "uuid"`, a renamed column, a missing one) — what a `%dev` start
 * would report as an error per column.
 */
@QuarkusTest
@TestProfile(ExpensesEntitySchemaValidationTest.ValidateProfile::class)
class ExpensesEntitySchemaValidationTest {

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
