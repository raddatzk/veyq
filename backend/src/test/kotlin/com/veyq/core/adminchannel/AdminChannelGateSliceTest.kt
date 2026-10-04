package com.veyq.core.adminchannel

import com.veyq.core.generated.AdminChannelOperations
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.util.UUID

/**
 * uc.admin.admin-channel's refusal, over the full HTTP stack (ADR 0065): a marked operation on the public port is
 * answered as an unknown route and executes nothing, while the identical request on the admin listener gets its
 * normal answer. **Every negative has its positive control on the same predicate** — the same call on the admin
 * listener — because a backend that 404'd the operation everywhere satisfies a lone negative perfectly.
 *
 * The role-gated operations are enumerated from the generated `…Api` interfaces, filtered by the generated
 * [AdminChannelOperations] set, rather than listed: an operation marked tomorrow is refused-tested tomorrow.
 * Setup's own pair lives in `SetupServerSliceTest`, which owns the one fresh-instance ordering the suite has.
 */
@QuarkusTest
class AdminChannelGateSliceTest {

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "admin-gate-owner@example.com", "SERVER_OWNER")
    }

    private fun owner() = bearer(OWNER_ID)

    /** What the public port answers for an `/api` path no resource declares — the shape a refusal must equal. */
    private fun unknownRoute(): Response = given().header("Authorization", owner()).`when`().get("/api/no-such-route")

    private fun Response.shape() = Triple(statusCode, contentType, body.asString())

    private data class Endpoint(val operationId: String, val method: String, val path: String)

    /** Every marked, role-gated operation: HTTP method + path read off the generated interface, ids filled. */
    private fun markedRoleGatedEndpoints(): List<Endpoint> =
        GENERATED_APIS.flatMap { api ->
            val root = api.getAnnotation(Path::class.java)?.value.orEmpty()
            api.methods.mapNotNull { m -> endpoint(root, m) }
        }.filter { it.operationId in AdminChannelOperations.operationIds && it.operationId != "createServerOwner" }

    private fun endpoint(root: String, m: Method): Endpoint? {
        val verb = m.annotations.firstNotNullOfOrNull { a -> a.annotationClass.java.getAnnotation(HttpMethod::class.java)?.value }
            ?: return null
        val sub = m.getAnnotation(Path::class.java)?.value.orEmpty()
        val path = ("/" + listOf(root, sub).joinToString("/") { it.trim('/') }.trim('/'))
            .replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
        return Endpoint(m.name, verb, path)
    }

    private fun call(spec: RequestSpecification, e: Endpoint): Response {
        val s = spec.header("Authorization", owner()).contentType(ContentType.JSON)
        return when (e.method) {
            "GET" -> s.`when`().get(e.path)
            "DELETE" -> s.`when`().delete(e.path)
            "POST" -> s.body("{}").`when`().post(e.path)
            "PUT" -> s.body("{}").`when`().put(e.path)
            else -> error("unexpected method ${e.method} on ${e.operationId}")
        }
    }

    @Test
    fun theEnumerationSeesEveryRoleGatedMarkedOperation() {
        // Tripwire: an enumeration that found nothing would make every loop below vacuous.
        assertEquals(AdminChannelOperations.operationIds - "createServerOwner", markedRoleGatedEndpoints().map { it.operationId }.toSet())
    }

    @Test
    fun everyRoleGatedMarkedOperationIsAnUnknownRouteOnThePublicPortAndAnswersOnTheAdminListener() {
        val refusal = unknownRoute().shape()
        assertEquals(404, refusal.first, "the unknown-route control itself: $refusal")
        for (e in markedRoleGatedEndpoints()) {
            assertEquals(refusal, call(given(), e).shape(), "${e.operationId} on the public port, with an owner session")
            // Control, same request: on the admin listener it reaches its handler (or its body validation), so it
            // must not look like the refusal. A 404 there is fine only with a body of its own (e.g. tenant_not_found).
            assertNotEquals(refusal, call(givenAdminChannel(), e).shape(), "${e.operationId} on the admin listener")
        }
    }

    @Test
    fun theRefusalIsIdenticalToAnUnknownRoute() {
        // uc `the-refusal-is-an-unknown-route` — status, content type and body, on a GET that would otherwise 200.
        val refused = given().header("Authorization", owner()).`when`().get("/api/instance/release")
        assertEquals(unknownRoute().shape(), refused.shape())
        givenAdminChannel().header("Authorization", owner()).`when`().get("/api/instance/release").then().statusCode(200)
    }

    @Test
    fun aRefusedCreateTenantCreatesNothing() {
        val domain = "refused-${UUID.randomUUID().toString().take(8)}.example.com"
        val body = """{"tenantName":"Refused","domain":"$domain","ownerEmail":"refused-owner@example.com"}"""
        given().header("Authorization", owner()).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/tenants").then().statusCode(404)
        assertFalse(listedDomains().contains(domain), "the public-port createTenant executed")
        // Control: the identical request on the admin listener creates it, so the absence above is the refusal.
        givenAdminChannel().header("Authorization", owner()).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/tenants").then().statusCode(201)
        assertTrue(listedDomains().contains(domain))
    }

    private fun listedDomains(): String =
        givenAdminChannel().header("Authorization", owner()).`when`().get("/api/tenants")
            .then().statusCode(200).extract().asString()

    @Test
    fun anAnonymousCallToARoleGatedOperationStillGetsItsOwn401() {
        // uc A1: security runs before the channel filter, so nothing executes and the answer is the old one.
        given().`when`().get("/api/instance/release").then().statusCode(401)
        givenAdminChannel().`when`().get("/api/instance/release").then().statusCode(401)
    }

    @Test
    fun theSharedOperationsCarryNoMarkerAndAnswerIdenticallyOnBothChannels() {
        // uc A4 / `shared-operations-answer-on-both-channels`.
        val shared = setOf("getSetupStatus", "requestLoginChallenge", "verifyLogin", "refreshSession", "logout")
        assertEquals(emptySet<String>(), shared intersect AdminChannelOperations.operationIds)

        assertEquals(given().get("/api/setup").shape(), givenAdminChannel().get("/api/setup").shape())
        val challenge = """{"email":"admin-gate-owner@example.com"}"""
        for (spec in listOf(given(), givenAdminChannel())) {
            spec.contentType(ContentType.JSON).body(challenge).post("/api/login/challenge").then().statusCode(200)
        }
        val verify = """{"email":"admin-gate-owner@example.com","signature":"AAAA"}"""
        assertEquals(
            given().contentType(ContentType.JSON).body(verify).post("/api/login/verify").statusCode,
            givenAdminChannel().contentType(ContentType.JSON).body(verify).post("/api/login/verify").statusCode,
        )
        assertEquals(
            given().contentType(ContentType.JSON).post("/api/token/refresh").shape(),
            givenAdminChannel().contentType(ContentType.JSON).post("/api/token/refresh").shape(),
        )
        assertEquals(given().post("/api/logout").statusCode, givenAdminChannel().post("/api/logout").statusCode)
    }

    private companion object {
        const val OWNER_ID = "admin-gate-owner"

        /** The generated interfaces the marked operations live on — asserted complete by the tripwire above. */
        val GENERATED_APIS = listOf(
            com.veyq.core.generated.api.TenantsApi::class.java,
            com.veyq.core.generated.api.InstanceApi::class.java,
            com.veyq.core.generated.api.SetupApi::class.java,
        )
    }
}
