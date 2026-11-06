package me.raddatz.veyq.core.sharing

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.http.CrossOrigin
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_EMBEDDABLE
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The guest share API answers any origin while `core.public-shares.embeddable` is effective (ADR 0121 decision 3,
 * [ShareCrossOrigin]): one case per path class — the resolve, the blob gate, a tree write, a report, `/api/setup`,
 * `/api/moderation/key` — with the switch on, off by the Server Owner and off by the link's tenant; a curator's operation
 * on a link and a member's path as the same-origin controls; `Access-Control-Allow-Credentials` asserted absent on every
 * answer read here; and the id the gate does not serve answered exactly as an id no link has, so the CORS headers are no
 * existence oracle.
 *
 * The requests are malformed or unauthorised on purpose where that is cheapest (an empty body, no password): a refusal
 * must carry the headers too, or a browser could not read why it was refused.
 */
@QuarkusTest
class ShareCrossOriginSliceTest {

    @AfterEach
    fun switchEverythingBackOn() {
        CapabilitySwitchFixtures.clear()
        TenantCapabilitySwitchFixtures.clear()
    }

    /** One path class: how its operation is sent, to [shareId]'s link where the path names one. */
    private class PathClass(val name: String, val method: String, val path: (shareId: String) -> String, val body: String? = null)

    private val shareClasses = listOf(
        PathClass("resolve", "GET", { "/api/shares/$it" }),
        PathClass("blob gate", "GET", { "/api/shares/$it/blobs/${UUID.randomUUID()}" }),
        PathClass("tree write", "POST", { "/api/shares/$it/nodes" }, body = "{}"),
        PathClass("report", "POST", { "/api/shares/$it/reports" }, body = "{}"),
    )
    private val instanceClasses = listOf(
        PathClass("setup", "GET", { "/api/setup" }),
        PathClass("moderation key", "GET", { "/api/moderation/key" }),
    )

    @Test
    fun everyPathClassAnswersAnyOriginWhileTheSwitchIsOn() {
        val link = link()
        for (c in shareClasses + instanceClasses) {
            val preflight = preflight(c.path(link.shareId), c.method)
            assertEquals(204, preflight.statusCode, "${c.name}: the preflight")
            assertEquals("*", preflight.header(CrossOrigin.ALLOW_ORIGIN), "${c.name}: the preflight's origin")
            assertTrue(preflight.header(CrossOrigin.ALLOW_METHODS).split(", ").contains(c.method), "${c.name}: ${preflight.header(CrossOrigin.ALLOW_METHODS)}")
            assertEquals(
                "X-Share-Password, X-Contributor-Token, X-Share-Write, If-None-Match, Content-Type",
                preflight.header(CrossOrigin.ALLOW_HEADERS_HEADER), "${c.name}: never Authorization",
            )
            assertEquals("600", preflight.header(CrossOrigin.MAX_AGE), c.name)
            assertNoCredentials(preflight, "${c.name}: the preflight")

            val answer = send(c, link.shareId)
            assertCrossOrigin(answer, "${c.name}: the answer (${answer.statusCode})")
        }
    }

    @Test
    fun theServerOwnersSwitchMakesEveryPathClassSameOriginAgain() {
        val link = link()
        for (switch in listOf(PUBLIC_SHARES_EMBEDDABLE, PUBLIC_SHARES)) {
            CapabilitySwitchFixtures.with(switch) {
                for (c in shareClasses + instanceClasses) {
                    val preflight = preflight(c.path(link.shareId), c.method)
                    assertEquals(405, preflight.statusCode, "${c.name} with $switch off: a preflight is an undeclared method again")
                    assertSameOrigin(preflight, "${c.name} with $switch off: the preflight")
                    assertSameOrigin(send(c, link.shareId), "${c.name} with $switch off: the answer")
                }
            }
        }
        // Control: back on, the same requests are cross-origin again.
        assertCrossOrigin(send(shareClasses.first(), link.shareId), "resolve, switched back on")
    }

    @Test
    fun theTenantsSwitchReachesItsServedLinksAndNothingElse() {
        val link = link()
        val other = link()
        TenantCapabilitySwitchFixtures.with(link.tenantId, PUBLIC_SHARES_EMBEDDABLE) {
            for (c in shareClasses) {
                assertEquals(405, preflight(c.path(link.shareId), c.method).statusCode, "${c.name}: the tenant's link, preflight")
                assertSameOrigin(send(c, link.shareId), "${c.name}: the tenant's link")
                // Another tenant's link and the instance-level paths keep answering any origin.
                assertCrossOrigin(send(c, other.shareId), "${c.name}: another tenant's link")
            }
            for (c in instanceClasses) assertCrossOrigin(send(c, link.shareId), "${c.name}: instance level, not the tenant's")
        }
    }

    @Test
    fun aLinkTheGateDoesNotServeGetsExactlyTheUnknownIdsAnswer() {
        // The tenant switched embedding off: a link it SERVES answers same-origin. A revoked one of the same tenant must
        // not — that would tell a caller the id exists — so it gets the instance level, as an id no link has.
        val served = link()
        val revoked = link(tenantOf = served)
        QuarkusTransaction.requiringNew().run { ShareEntity.findById(revoked.shareId)!!.enabled = false }
        val unknown = TestIds.shareId()
        TenantCapabilitySwitchFixtures.with(served.tenantId, PUBLIC_SHARES_EMBEDDABLE) {
            for (c in shareClasses) {
                val ofUnknown = send(c, unknown)
                val ofRevoked = send(c, revoked.shareId)
                assertEquals(corsOf(ofUnknown), corsOf(ofRevoked), "${c.name}: a revoked link's CORS headers")
                assertEquals(ofUnknown.statusCode, ofRevoked.statusCode, "${c.name}: a revoked link's status")
                assertEquals(ofUnknown.body.asString(), ofRevoked.body.asString(), "${c.name}: a revoked link's body")
                assertEquals(
                    preflight(c.path(unknown), c.method).statusCode, preflight(c.path(revoked.shareId), c.method).statusCode,
                    "${c.name}: a revoked link's preflight",
                )
                assertCrossOrigin(ofUnknown, "${c.name}: an unknown id, at the instance level")
                // Control: the served link of the same tenant is where the tenant's switch shows.
                assertSameOrigin(send(c, served.shareId), "${c.name}: the tenant's served link")
            }
        }
    }

    @Test
    fun aCuratorsOperationOnALinkAndAMembersPathStaySameOrigin() {
        val link = link()
        // A curator's operations under the same `/api/shares/{shareId}` take a bearer: never offered to another origin.
        for (curator in listOf("/api/shares/${link.shareId}/revoke", "/api/shares/${link.shareId}/reissue")) {
            assertEquals(405, preflight(curator, "POST").statusCode, "$curator: no preflight")
            assertSameOrigin(
                given().header("Authorization", bearer(link.ownerId)).header("Origin", ORIGIN).`when`().post(curator),
                "$curator: the answer",
            )
        }
        // The link's own path mixes the guest's GET with the curator's PATCH and DELETE: only the GET is offered.
        assertEquals("GET", preflight("/api/shares/${link.shareId}", "DELETE").header(CrossOrigin.ALLOW_METHODS))
        assertSameOrigin(
            given().header("Authorization", bearer(link.ownerId)).header("Origin", ORIGIN).`when`().delete("/api/shares/${TestIds.shareId()}"),
            "DELETE of a link",
        )
        // A member's path: nothing changes there.
        assertEquals(405, preflight("/api/scopes", "GET").statusCode, "a member's path: no preflight")
        assertSameOrigin(given().header("Origin", ORIGIN).`when`().get("/api/scopes"), "a member's path")
        // Control: the same link's guest read, in the same state, is cross-origin.
        assertCrossOrigin(given().header("Origin", ORIGIN).`when`().get("/api/shares/${link.shareId}"), "control: the guest read")
    }

    @Test
    fun anOptionsThatIsNoPreflightIsStillAnUndeclaredMethod() {
        // Without `Access-Control-Request-Method` an OPTIONS is not CORS: `UndeclaredMethodFilter`'s 405, as before.
        val link = link()
        for (path in listOf("/api/shares/${link.shareId}", "/api/setup")) {
            val answer = given().header("Origin", ORIGIN).request("OPTIONS", path)
            assertEquals(405, answer.statusCode, path)
            assertNull(answer.header(CrossOrigin.ALLOW_METHODS), path)
        }
        assertEquals(204, preflight("/api/setup", "GET").statusCode, "control: the same path, as a preflight")
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private data class Link(val shareId: String, val ownerId: String, val tenantId: String, val scopeId: String)

    /** A READ_WRITE link on a folder of a fresh tenant's space — or, with [tenantOf], of that link's space. */
    private fun link(tenantOf: Link? = null): Link {
        if (tenantOf != null) {
            val folder = seedNode(tenantOf.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val shareId = GuestTreeShare.seed(tenantOf.ownerId, tenantOf.scopeId, folder).shareId
            return tenantOf.copy(shareId = shareId)
        }
        val space = seedMemberScope()
        val folder = seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Link(GuestTreeShare.seed(space.userId, space.scopeId, folder).shareId, space.userId, space.tenantId, space.scopeId)
    }

    private fun preflight(path: String, method: String): Response =
        given().header("Origin", ORIGIN).header(CrossOrigin.REQUEST_METHOD, method)
            .header("Access-Control-Request-Headers", "x-share-password,content-type")
            .request("OPTIONS", path)

    private fun send(c: PathClass, shareId: String): Response {
        val spec = given().header("Origin", ORIGIN)
        if (c.body != null) spec.contentType(ContentType.JSON).body(c.body)
        return spec.request(c.method, c.path(shareId))
    }

    private fun corsOf(r: Response): Map<String, String?> =
        listOf(CrossOrigin.ALLOW_ORIGIN, CrossOrigin.EXPOSE_HEADERS, CORP, ACAC).associateWith { r.header(it) }

    private fun assertCrossOrigin(r: Response, what: String) {
        assertEquals("*", r.header(CrossOrigin.ALLOW_ORIGIN), "$what: Access-Control-Allow-Origin")
        assertEquals("ETag", r.header(CrossOrigin.EXPOSE_HEADERS), "$what: Access-Control-Expose-Headers")
        assertEquals("cross-origin", r.header(CORP), "$what: CORP")
        assertNoCredentials(r, what)
    }

    private fun assertSameOrigin(r: Response, what: String) {
        assertNull(r.header(CrossOrigin.ALLOW_ORIGIN), "$what: no Access-Control-Allow-Origin")
        assertNull(r.header(CrossOrigin.ALLOW_METHODS), "$what: no Access-Control-Allow-Methods")
        assertEquals("same-origin", r.header(CORP), "$what: CORP")
        assertNoCredentials(r, what)
    }

    private fun assertNoCredentials(r: Response, what: String) = assertNull(r.header(ACAC), "$what: never Access-Control-Allow-Credentials")

    private companion object {
        const val ORIGIN = "https://other-instance.example"
        const val CORP = "Cross-Origin-Resource-Policy"
        const val ACAC = "Access-Control-Allow-Credentials"
    }
}
