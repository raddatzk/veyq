package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The id boundary of the `UUID` columns (inbox #387): every id column is MariaDB's native `UUID` type, which takes a
 * canonical UUID and nothing else — an `INSERT` of another string is error 1292, a `SELECT` by one silently matches
 * nothing — so a malformed id a client sends must be refused at the contract, never reach a query, and never be a 500.
 * One test per kind of entry: a path id, a body id (client-minted, ADR 0072), a parent reference, an id in a list, and
 * a path id on an operation that otherwise answers 204 for every id (`revokeSessionFamily`, `deleteAccountWrap`).
 * The second half is case: a UUID prints lower-case canonical and is parsed case-insensitively, so the normalisation
 * point is the generated `java.util.UUID` — an upper-case id is the same id, stored and compared in canonical form.
 *
 * This test runs on H2, which is stricter than MariaDB about a bad string (it fails the statement where MariaDB warns
 * on a `SELECT`), so a 500 here is a gap on either engine; `scripts/validate_id_contract_format.py` holds the contract
 * side — every id-named request input says `format: uuid` — and these tests hold that the refusal reaches the wire.
 */
@QuarkusTest
class IdBoundarySliceTest {

    private val space = seedMemberScope()
    private val auth = bearer(space.userId)

    private fun send(method: String, path: String, body: String? = null): Response {
        val spec = given().header("Authorization", auth).contentType(ContentType.JSON)
        if (body != null) spec.body(body)
        return spec.request(method, path)
    }

    private fun folder(nodeId: String, parentId: String? = null, slug: String = UUID.randomUUID().toString().replace("-", "")): String =
        """{"nodeId":"$nodeId","parentId":${parentId?.let { "\"$it\"" }},"kind":"FOLDER","encryptedName":"YQ==","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"a21lbQ=="}"""

    // --- a path id ----------------------------------------------------------------------------------------------

    @Test
    fun aPathIdThatIsNoUuidIsNeverAServerError() {
        val garbage = listOf("not-a-uuid", "12345", "%27%20OR%201%3D1", "0123456789abcdef0123456789abcdef0123", "-".repeat(36))
        for (bad in garbage) {
            val calls = listOf(
                "DELETE" to "/api/scopes/$bad/nodes/${UUID.randomUUID()}",
                "GET" to "/api/scopes/$bad/nodes",
                "DELETE" to "/api/scopes/${space.scopeId}/nodes/$bad",
                "GET" to "/api/scopes/$bad/members",
                "PATCH" to "/api/scopes/${space.scopeId}/members/$bad",
            )
            for ((method, path) in calls) {
                val status = send(method, path, if (method == "PATCH") """{"role":"MEMBER"}""" else null).statusCode
                assertEquals(404, status, "$method $path: a path segment that is no UUID matches no route")
            }
        }
    }

    @Test
    fun aMalformedIdOnAnOperationThatAnswers204ForEveryIdIsRefusedToo() {
        // `revokeSessionFamily` and `deleteAccountWrap` were `string` paths (a 204 for anything): UUID columns now,
        // so the contract says `format: uuid` and a segment that is none is a refusal, not a query.
        for (path in listOf("/api/sessions/not-a-uuid", "/api/account/wraps/not-a-uuid")) {
            val status = send("DELETE", path).statusCode
            assertEquals(404, status, "DELETE $path")
        }
        // The control: a well-formed id nobody owns stays the idempotent 204 (no enumeration).
        assertEquals(204, send("DELETE", "/api/sessions/${UUID.randomUUID()}").statusCode)
        assertEquals(204, send("DELETE", "/api/account/wraps/${UUID.randomUUID()}").statusCode)
    }

    // --- a body id: client-minted ids (ADR 0072), a parent reference, a list ------------------------------------

    @Test
    fun aBodyIdThatIsNoUuidIsInvalidRequest() {
        val path = "/api/scopes/${space.scopeId}/nodes"
        for (bad in listOf("not-a-uuid", "", "12345", "0123456789abcdef0123456789abcdef0123")) {
            assertEquals(400, send("POST", path, folder(bad)).statusCode, "nodeId `$bad`")
            // An empty parentId is Jackson's null (the scope root), the one spelling that is no refusal.
            if (bad.isNotEmpty()) assertEquals(400, send("POST", path, folder(UUID.randomUUID().toString(), parentId = bad)).statusCode, "parentId `$bad`")
        }
        assertEquals(201, send("POST", path, folder(UUID.randomUUID().toString())).statusCode, "the control: a UUID creates")
    }

    @Test
    fun aClientMintedBlobAndOwnerIdThatIsNoUuidIsInvalidRequest() {
        val path = "/api/scopes/${space.scopeId}/blob-uploads-batch"
        fun item(blobId: String, ownerId: String) =
            """{"items":[{"blobId":"$blobId","ownerId":"$ownerId","role":"FILE_CONTENT","chunkCount":1,"byteSize":10,"wrappedBlobKey":"YQ=="}]}"""
        assertEquals(400, send("POST", path, item("not-a-uuid", UUID.randomUUID().toString())).statusCode, "blobId")
        assertEquals(400, send("POST", path, item(UUID.randomUUID().toString(), "not-a-uuid")).statusCode, "ownerId")
    }

    @Test
    fun anIdInAListThatIsNoUuidIsInvalidRequest() {
        val response = send("POST", "/api/scopes/${space.scopeId}/blob-downloads-batch", """{"items":[{"blobId":"not-a-uuid"}]}""")
        assertEquals(400, response.statusCode, response.asString())
    }

    // --- case ---------------------------------------------------------------------------------------------------

    @Test
    fun anUpperCaseIdIsTheSameIdInCanonicalForm() {
        val id = UUID.randomUUID().toString()
        val upper = id.uppercase()
        val base = "/api/scopes/${space.scopeId}/nodes"
        assertEquals(201, send("POST", base, folder(upper)).statusCode, "an upper-case client-minted id creates")
        assertEquals(listOf(id), send("GET", base).jsonPath().getList<String>("id"), "the list prints the canonical form")
        // The same id in the other case is the same id: taken, not a second row (the primary key agrees).
        val again = send("POST", base, folder(id))
        assertEquals(409, again.statusCode, again.asString())
        assertEquals("id_taken", again.jsonPath().getString("code"))
        // An upper-case parent reference finds the lower-case parent.
        val child = UUID.randomUUID().toString()
        assertEquals(201, send("POST", base, folder(child, parentId = upper)).statusCode)
        assertEquals(setOf(id, child), send("GET", base).jsonPath().getList<String>("id").toSet())
        // An upper-case path id addresses the same node.
        assertEquals(204, send("DELETE", "$base/${child.uppercase()}").statusCode)
        assertEquals(listOf(id), send("GET", base).jsonPath().getList<String>("id"))
    }

    @Test
    fun aScopeIdInEitherCaseIsTheSameScope() {
        val path = "/api/scopes/%s/nodes"
        val lower = send("POST", path.format(space.scopeId), folder(UUID.randomUUID().toString())).statusCode
        val upper = send("POST", path.format(space.scopeId.uppercase()), folder(UUID.randomUUID().toString())).statusCode
        assertEquals(201, lower)
        assertEquals(201, upper)
    }
}
