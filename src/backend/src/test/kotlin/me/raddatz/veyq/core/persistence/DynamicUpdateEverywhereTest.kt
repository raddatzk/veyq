package me.raddatz.veyq.core.persistence

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every entity flushes only the columns its transaction changed (developer-styleguide §12). Without
 * `@DynamicUpdate`, Hibernate writes every column of a managed entity it flushes, so a write writes back every value
 * it only read, and a column another transaction changed in between is silently reverted. Measured before this was
 * the default, 5 of 5 each: a terms edit lifted a tenant suspension, a Memory rename wiped a WRITE_ONLY link's drop
 * private key, a consent toggle rolled back a password change's new auth key (inbox #224).
 *
 * The trap is the default, not any one site: each of those entities got its second writer somewhere else, and nobody
 * re-read every managed write of the row. So this reads Hibernate's own mapping — what it will actually do at flush,
 * for an entity in any package — and names each entity that would write whole rows.
 */
@QuarkusTest
class DynamicUpdateEverywhereTest {

    @Inject
    lateinit var emf: EntityManagerFactory

    @Test
    fun everyEntityFlushesOnlyWhatItChanged() {
        val persisters = buildList { emf.unwrap(SessionFactoryImplementor::class.java).mappingMetamodel.forEachEntityDescriptor { add(it) } }
        // Tells "every entity is dynamic" apart from "no entity was read": the persistence unit has dozens.
        assertTrue(persisters.size >= 30, "read only ${persisters.size} entities — is this the application's persistence unit?")
        val wholeRow = persisters.filterNot { it.isDynamicUpdate }.map { it.entityName }.sorted()
        assertEquals(
            emptyList<String>(), wholeRow,
            "these entities write every column on flush — annotate each with @org.hibernate.annotations.DynamicUpdate",
        )
    }
}
