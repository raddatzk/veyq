package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An HTTP method the contract does not declare for a path (inbox #494, found by the API fuzzing): `405` with an
 * `Allow` that lists exactly the path's declared methods and the contract's `ErrorResponse{code: method_not_allowed}`
 * — `OPTIONS` and `TRACE` included, which the API answers like any other method it does not declare
 * ([UndeclaredMethodFilter]). Each refusal has its control on the same path: the declared method still reaches its
 * handler, so a backend that answered 405 everywhere would be red here.
 */
@QuarkusTest
class UndeclaredMethodSliceTest {

    private val space = seedMemberScope()
    private val auth = bearer(space.userId)

    private fun send(method: String, path: String, spec: RequestSpecification = given()): Response =
        spec.header("Authorization", auth).request(method, path)

    private fun assertRefused(answer: Response, allow: String, what: String) {
        assertEquals(405, answer.statusCode, "$what: status")
        assertEquals(allow, answer.header("Allow"), "$what: Allow")
        assertEquals("application/json", answer.contentType.substringBefore(';'), "$what: content type")
        assertEquals("method_not_allowed", answer.jsonPath().getString("code"), "$what: code")
    }

    private fun Response.shape() = Triple(statusCode, contentType, body.asString())

    @Test
    fun aMethodThePathDoesNotDeclareIs405WithThePathsOwnAllow() {
        // `/api/account/wraps/{id}` declares DELETE alone; its resource class also serves POST on `/api/account/wraps`,
        // which Quarkus' own answer advertised (the class's methods, not the path's).
        val path = "/api/account/wraps/${UUID.randomUUID()}"
        for (method in listOf("PATCH", "PUT", "POST", "GET", "QUERY")) assertRefused(send(method, path), "DELETE", "$method $path")
        assertEquals(204, send("DELETE", path).statusCode, "the declared method still reaches its handler")
    }

    @Test
    fun aPathWithSeveralMethodsListsThemAll() {
        val path = "/api/scopes/${space.scopeId}/members/${UUID.randomUUID()}"
        assertRefused(send("GET", path), "DELETE, PATCH", "GET $path")
    }

    @Test
    fun aLiteralPathIsNotAnsweredByItsTemplatedSibling() {
        // `/api/scopes/invitations` declares GET; `/api/scopes/{scopeId}` declares DELETE. A PUT or a DELETE on the
        // literal path was routed to the template, whose `scopeId` is no UUID: a 404 where the contract says 405.
        for (method in listOf("PUT", "DELETE", "POST")) {
            assertRefused(send(method, "/api/scopes/invitations"), "GET", "$method /api/scopes/invitations")
        }
        assertRefused(send("DELETE", "/api/sessions/revoke-others"), "POST", "DELETE /api/sessions/revoke-others")
        // The literal path's own method reaches its handler, which answers the test host's missing tenant.
        assertEquals("tenant_not_found", send("GET", "/api/scopes/invitations").jsonPath().getString("code"), "the literal path's own method")
        // And a templated segment that is no UUID still names nothing (IdBoundarySliceTest): 404, not 405.
        assertEquals(404, send("DELETE", "/api/scopes/not-a-uuid").statusCode, "a declared method on a malformed id")
    }

    @Test
    fun optionsIsAnUndeclaredMethodToo() {
        // No CORS (same origin), and no operation declares OPTIONS: it is refused like any other undeclared method,
        // with the same Allow, instead of Quarkus' 200 that listed the resource class's methods.
        assertRefused(send("OPTIONS", "/api/account/wraps/${UUID.randomUUID()}"), "DELETE", "OPTIONS on a one-method path")
        assertRefused(send("OPTIONS", "/api/setup"), "GET", "OPTIONS /api/setup")
        assertRefused(given().request("OPTIONS", "/api/setup"), "GET", "OPTIONS anonymously")
    }

    @Test
    fun traceIsRefused() {
        assertRefused(send("TRACE", "/api/setup"), "GET", "TRACE /api/setup")
        // The fuzz run's finding, anonymously and on the admin listener where its operations answer.
        assertRefused(givenAdminChannel().request("TRACE", "/api/instance/backup-retention"), "GET, PUT", "TRACE anonymously")
        // The answer echoes nothing of the request — no header, no body — which is what TRACE would have done.
        val traced = given().header("X-Echo-Probe", "secret-probe").request("TRACE", "/api/setup")
        assertEquals(false, traced.body.asString().contains("secret-probe"), "TRACE echoed the request")
    }

    @Test
    fun headIsAnsweredWhereGetIsDeclaredAndRefusedWhereItIsNot() {
        assertEquals(200, given().request("HEAD", "/api/setup").statusCode, "HEAD on a GET path is the GET's head")
        val head = send("HEAD", "/api/account/wraps/${UUID.randomUUID()}")
        assertEquals(405, head.statusCode, "HEAD on a path without GET")
        assertEquals("DELETE", head.header("Allow"))
    }

    @Test
    fun anUnknownPathIsStillAnUnknownRouteForEveryMethod() {
        val unknown = given().get("/api/no-such-route").shape()
        assertEquals(404, unknown.first, "the control itself")
        for (method in listOf("TRACE", "OPTIONS", "PUT", "DELETE")) {
            val answer = given().request(method, "/api/no-such-route")
            assertEquals(unknown, answer.shape(), "$method on an unknown /api path")
            assertNull(answer.header("Allow"), "$method on an unknown /api path carries no Allow")
        }
        // A path that is one segment longer than a declared one is no path either.
        assertEquals(404, send("TRACE", "/api/setup/extra").statusCode)
    }

    @Test
    fun theAppShellsPathsAreNotTheApis() {
        // The filter answers `/api` only: a deep link still loads the shell, and an undeclared method on it stays
        // Vert.x' own refusal of a method its GET/HEAD routes do not take — never the API's JSON body.
        val shell = given().header("Accept", "text/html").get("/some/deep/link")
        assertEquals(200, shell.statusCode, "the SPA fallback")
        assertEquals("text/html", shell.contentType.substringBefore(';'))
        val traced = given().header("Accept", "text/html").request("TRACE", "/some/deep/link")
        assertEquals(405, traced.statusCode)
        assertEquals("", traced.body.asString(), "no ErrorResponse outside the API")
    }

    @Test
    fun theAdminChannelsPathsAreUnknownRoutesOffItAndRefusedWithAllowOnIt() {
        // `/api/instance/release` is a Server-Owner path (ADR 0065): on the public port every method of it — declared
        // or not — answers as a route that does not exist, the admin channel's refusal; on the admin listener an
        // undeclared method is the ordinary 405.
        val unknown = given().header("Authorization", auth).get("/api/no-such-route").shape()
        for (method in listOf("PUT", "OPTIONS", "TRACE")) {
            assertEquals(unknown, send(method, "/api/instance/release").shape(), "$method on the public port")
            assertRefused(send(method, "/api/instance/release", givenAdminChannel()), "GET", "$method on the admin listener")
        }
    }
}
