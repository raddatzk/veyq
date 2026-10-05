package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `PublicShare.rootKind` (inbox #329, cr-platform-contract-split slice 5): the anonymous resolve names the kind of the
 * shared root node — a structural fact the server already holds — for every UNLOCKED role, `WRITE_ONLY` included (its
 * guest is served no node, so this is the only way its client learns which application renders the drop), and for no
 * LOCKED one (password required, no token: the challenge only). Core reads the kind off the row and interprets none of
 * it, so a kind no application claims is served as stored.
 *
 * Every assertion reads the RAW JSON body: a parsed model would answer "absent" for a key the server did send.
 * Lives in `:apps:memories` because every share creation needs an application that accepts the kind.
 */
@QuarkusTest
class ShareRootKindSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val pwSalt = "cHctc2FsdA=="
    private val pwToken = "cHctdG9rZW4="

    @Test
    fun aReadShareNamesItsRootKind() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val body = resolve(createShare(userId, memory, "READ"))
        assertEquals("READ", body["role"])
        assertEquals("MEMORY", body["rootKind"])
    }

    @Test
    fun aWriteOnlyShareNamesItsRootKindThoughItIsServedNoNode() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val body = resolve(createShare(userId, memory, "WRITE_ONLY"))
        assertEquals("WRITE_ONLY", body["role"])
        assertEquals("MEMORY", body["rootKind"])
        // still served no node: the kind is the one structural fact it gains, not the subtree
        assertEquals(emptyList<Any>(), body["nodes"])
    }

    @Test
    fun aLockedResolveOmitsTheRootKind() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        for (role in listOf("READ", "WRITE_ONLY")) {
            val shareId = createShare(userId, memory, role, password = true)
            val locked = resolve(shareId)
            // positive control: the locked body is a real PublicShare, its role is there
            assertEquals(role, locked["role"], "$role: the locked response carries its role")
            assertEquals(true, locked["passwordRequired"])
            assertFalse(locked.containsKey("rootKind"), "$role: a locked response names no kind, not even as null: $locked")
            // control: the same share unlocked names it
            assertEquals("MEMORY", resolve(shareId) { header("X-Share-Password", pwToken) }["rootKind"], "$role unlocked")
        }
    }

    /** Core interprets no kind: a share row on a node of a kind no application claims (stored directly — the create door
     *  refuses it) is served that kind as stored, for either role. Core's code never names `MEMORY`. */
    @Test
    fun anUnclaimedKindIsServedAsStored() {
        val (userId, scopeId) = seedMemberScope()
        val expense = seedNode(scopeId, "EXPENSE", parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        for (role in listOf(ShareEntity.READ, ShareEntity.WRITE_ONLY)) {
            val shareId = TestIds.shareId()
            QuarkusTransaction.requiringNew().run {
                ShareEntity().apply {
                    id = shareId; ownerAccount = userId; this.scopeId = scopeId; nodeId = expense; this.role = role
                    this.wrap = if (role == ShareEntity.READ) this@ShareRootKindSliceTest.wrap else null
                    this.sealedName = this@ShareRootKindSliceTest.sealedName; enabled = true; createdAt = Instant.now()
                }.persist()
            }
            assertEquals("EXPENSE", resolve(shareId)["rootKind"], role)
        }
    }

    /** A trashed root is served nothing of its subtree (`ShareDeletedNodeSliceTest`), and no kind either: the client
     *  then shows the unavailable state, as it did when it read the kind off the served root. Restored, it comes back. */
    @Test
    fun aTrashedRootNamesNoKind() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val read = createShare(userId, memory, "READ")
        val drop = createShare(userId, memory, "WRITE_ONLY")
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        for (shareId in listOf(read, drop)) {
            val body = resolve(shareId)
            assertTrue(body.containsKey("role"), "control: still a resolved share")
            assertFalse(body.containsKey("rootKind"), "a trashed root names no kind: $body")
        }
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"cmVzdG9yZWQtc2x1Zw=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)
        for (shareId in listOf(read, drop)) assertEquals("MEMORY", resolve(shareId)["rootKind"])
    }

    // --- helpers ---

    /** The resolve's raw JSON body as a map (status 200 asserted). */
    @Suppress("UNCHECKED_CAST")
    private fun resolve(shareId: String, spec: RequestSpecification.() -> RequestSpecification = { this }): Map<String, Any?> =
        given().spec().`when`().get("/api/shares/$shareId").then().statusCode(200)
            .extract().`as`(Map::class.java) as Map<String, Any?>

    private fun createShare(userId: String, nodeId: String, role: String, password: Boolean = false): String {
        val pw = if (!password) "" else
            ""","passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"${sha256b64(pwToken)}","sealedPassword":"c2VhbGVkLXB3""""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName"$pw}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
    }

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))
}
