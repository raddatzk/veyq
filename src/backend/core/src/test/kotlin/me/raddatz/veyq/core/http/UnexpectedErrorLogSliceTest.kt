package me.raddatz.veyq.core.http

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import io.vertx.ext.web.Router
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.core.MediaType
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.LogCapture
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * An unexpected error logs one ERROR line with an error id, the method, the route template, the class chain, a
 * SQLState where a database failed, and the first own frame — never the URI, the query or any exception's message
 * (ADR 0092 § 4). The capture is the root JUL logger, so Quarkus' own `HTTP Request to … failed` line
 * ([UnexpectedError]) and Hibernate's statement error are inside it too: a value absent here is absent from every
 * log line.
 *
 * Each case is red on the line it replaces — drop [UnexpectedErrorRoutes]'s registration and Quarkus' line carries the
 * URI and the message; drop `quarkus.log.category."org.hibernate.orm.jdbc.error".level` and Hibernate's WARN carries
 * the address — and each negative has its control on the same capture: the line IS written, and names the id the 500
 * carries. The probes are test-scoped (the trust-chain skill's note: outside the rate-limit posture sweep's reach).
 */
@QuarkusTest
class UnexpectedErrorLogSliceTest {

    @Test
    fun aResourceFailureIsLoggedByTemplateAndClassChainNeverByUriOrMessage() {
        lateinit var response: Response
        val records = LogCapture().use { capture ->
            response = given().queryParam("probe", QUERY_MARKER).`when`().get("$RESOURCE/$SEGMENT_MARKER")
            capture.records()
        }

        assertNothingNamed(records, ADDRESS, IP, QUERY_MARKER, SEGMENT_MARKER)
        val id = errorIdOf(response)
        val line = records.single { it.level == Level.SEVERE && id in it.text }
        assertTrue("GET $RESOURCE/{kind}" in line.text, "the line names the method and the template: <$line>")
        assertTrue(IllegalStateException::class.java.name in line.text, "the line names the class: <$line>")
        assertTrue("UnexpectedErrorProbeResource.fail" in line.text, "the line names the first own frame: <$line>")
    }

    @Test
    fun aVertxRouteFailureTakesTheSameLine() {
        lateinit var response: Response
        val records = LogCapture().use { capture ->
            response = given().queryParam("probe", QUERY_MARKER).`when`().get("$ROUTE/$SEGMENT_MARKER")
            capture.records()
        }

        assertNothingNamed(records, ADDRESS, IP, QUERY_MARKER, SEGMENT_MARKER)
        val id = errorIdOf(response)
        val line = records.single { it.level == Level.SEVERE && id in it.text }
        assertTrue("GET ${UnexpectedError.NO_TEMPLATE} failed" in line.text, "no JAX-RS template, and the path not in its place: <$line>")
        assertTrue("UnexpectedErrorProbeRoute" in line.text, "the line names the first own frame: <$line>")
    }

    @Test
    fun aUniqueViolationIsLoggedBySqlStateNeverByTheValueItCollidedOn() {
        val email = "collide-${UUID.randomUUID()}@example.org"
        QuarkusTransaction.requiringNew().run { newUser(email = email).persist() }

        lateinit var response: Response
        val records = LogCapture().use { capture ->
            response = given().contentType("text/plain").body(email).`when`().post("$RESOURCE/duplicate-user")
            capture.records()
        }

        assertNothingNamed(records, email)
        val id = errorIdOf(response)
        val line = records.single { it.level == Level.SEVERE && id in it.text }
        assertTrue(Regex("SQLState 23\\d{3}, vendor code").containsMatchIn(line.text), "the database's verdict survives as its SQLState: <$line>")
    }

    @Test
    fun theFullExceptionIsWrittenOnlyWhenItsCategoryIsRaised() {
        val detail = Logger.getLogger(UnexpectedError.DETAIL_CATEGORY)
        val before = detail.level
        detail.level = Level.FINE
        try {
            lateinit var id: String
            val records = LogCapture().use { capture ->
                id = errorIdOf(given().`when`().get("$RESOURCE/$SEGMENT_MARKER"))
                capture.records()
            }
            val full = records.single { it.logger == UnexpectedError.DETAIL_CATEGORY && id in it.text }
            assertTrue(ADDRESS in full.text, "raised, the category carries the exception's message: <$full>")
        } finally {
            detail.level = before
        }
    }

    @Test
    fun theTemplateJoinsTheClassAndMethodPaths() {
        assertEquals("/api/x/{id}", ResourceTemplateFilter.template("/api/x/", "/{id}"))
        assertEquals("/api/x", ResourceTemplateFilter.template("/api/x", null))
        assertEquals("/", ResourceTemplateFilter.template("/", ""))
    }

    /** The 500's body carries `internal_error` and the id, and the id is the one value the caller can hand on. */
    private fun errorIdOf(response: Response): String {
        response.then().statusCode(500)
        assertEquals("internal_error", response.jsonPath().getString("code"))
        val message = response.jsonPath().getString("message")
        val id = Regex("^error id ([0-9a-f-]{36})$").find(message)?.groupValues?.get(1)
        return checkNotNull(id) { "the message names the error id: <$message>" }
    }

    private fun assertNothingNamed(records: List<LogCapture.Record>, vararg values: String) {
        records.forEach { r -> values.forEach { v -> assertFalse(v in r.text, "<$v> is in a log record: <$r>") } }
    }

    companion object {
        const val RESOURCE = "/test/unexpected-error"
        const val ROUTE = "/test/unexpected-error-route"
        const val ADDRESS = "someone@example.org"
        const val IP = "203.0.113.77"
        const val QUERY_MARKER = "q-marker"
        const val SEGMENT_MARKER = "s-marker"
    }
}

/** Fails the way an unexpected error does: with a message that names a person, as a database driver's would. */
@Path(UnexpectedErrorLogSliceTest.RESOURCE)
@PermitAll
class UnexpectedErrorProbeResource {

    @GET
    @Path("/{kind}")
    fun fail(@PathParam("kind") kind: String): String =
        throw IllegalStateException("probe failed for ${UnexpectedErrorLogSliceTest.ADDRESS} from ${UnexpectedErrorLogSliceTest.IP}")

    /** A second account on an address one already holds: the unique index refuses it, as a colliding sign-up does. */
    @POST
    @Path("/duplicate-user")
    @Transactional
    @Consumes(MediaType.TEXT_PLAIN)
    fun duplicateUser(email: String): String {
        newUser(email = email).persist()
        VeyqUser.flush()
        return "unreachable"
    }
}

/** The second entry point: a Vert.x route, which no JAX-RS mapper could ever see. */
@ApplicationScoped
class UnexpectedErrorProbeRoute {
    fun register(@Observes router: Router) {
        router.get("${UnexpectedErrorLogSliceTest.ROUTE}/:kind").handler {
            throw IllegalStateException("probe failed for ${UnexpectedErrorLogSliceTest.ADDRESS} from ${UnexpectedErrorLogSliceTest.IP}")
        }
    }
}
