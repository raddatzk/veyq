package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.ShareMediaRead
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * Slice tests for password-protected shares (uc.sharing.share-password, cr-0118, ADR 0037) — an operator-blind
 * ACCESS-CONTROL gate: a locked resolve (no token) hides everything but the challenge; a valid `X-Share-Password`
 * token unlocks the full share; a wrong token is 403; the blob GET needs the token too; set/change/remove via
 * `updateShare` keeps the same link; the manage list carries the `sealed_password` reveal copy (never the viewer).
 * The server stores only the verifier (`SHA-256(pwToken)`) + salt + the sealed copy — never the raw password.
 */
@QuarkusTest
class SharePasswordSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val pwSalt = "cHctc2FsdA==" // "pw-salt"
    private val pwToken = "cHctdG9rZW4=" // "pw-token" — what the guest presents in X-Share-Password
    private val pwVerifier = sha256b64(pwToken) // SHA-256(decode(pwToken)) — what the server stores + checks
    private val wrongToken = "d3JvbmctdG9rZW4=" // "wrong-token"
    private val sealedPassword = "c2VhbGVkLXB3" // the plaintext password sealed under K_share (opaque here)
    private val shareKey = "cmVjb3ZlcmFibGUta2V5" // K_share under K_SC(epoch), opaque here

    @Test
    fun createThenLockedResolveHidesTheContent() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        // no token → LOCKED: only the challenge crosses; wrap/name are omitted (nothing is readable)
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("passwordRequired", equalTo(true))
            .body("pwSalt", equalTo(pwSalt))
            .body("wrap", nullValue())
            .body("sealedName", nullValue())
    }

    @Test
    fun validTokenUnlocksAndWrongTokenIs403() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        // a valid token → the full share (the same wrap)
        given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$shareId")
            .then().statusCode(200).body("wrap", equalTo(wrap)).body("passwordRequired", equalTo(true))

        // a wrong token → 403 (distinct from the non-enumerable 404 of a bad id)
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    @Test
    fun blobGetRequiresThePasswordToken() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        // the gate runs before the blob lookup: no token → 404 (non-enumerable), wrong token → 403
        given().`when`().get("/api/shares/$shareId/blobs/${UUID.randomUUID()}").then().statusCode(404)
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId/blobs/${UUID.randomUUID()}")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    @Test
    fun theMediaReadRequiresThePasswordToken() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        // Memories' listShareMedia passes the same gate: no token → 404 (it serves no locked shell — the challenge is
        // the resolve's), a wrong token → 403, a valid one → the (empty) listing
        ShareMediaRead.of(shareId).then().statusCode(404).body("code", equalTo("share_not_found"))
        ShareMediaRead.of(shareId, wrongToken).then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        ShareMediaRead.of(shareId, pwToken).then().statusCode(200).body("media.size()", equalTo(0)).body("identities.size()", equalTo(0))
    }

    @Test
    fun setChangeRemoveViaUpdateShareKeepsTheLink() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        // start with a PLAIN share (no password) — the same link will gain / change / lose the password
        val shareId = createPlain(userId, memory)
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("passwordRequired", equalTo(false))

        // ADD a password in place → the resolve is now locked (same shareId, no new link)
        patch(userId, shareId, """{"passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"$pwVerifier","sealedPassword":"$sealedPassword"}""")
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("wrap", nullValue()).body("pwSalt", equalTo(pwSalt))
        given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$shareId").then().statusCode(200).body("wrap", equalTo(wrap))

        // CHANGE the password → the old token stops working, the new one unlocks
        val newSalt = "bmV3LXNhbHQ="; val newToken = "bmV3LXRva2Vu"; val newVerifier = sha256b64(newToken)
        patch(userId, shareId, """{"passwordRequired":true,"pwSalt":"$newSalt","pwVerifier":"$newVerifier","sealedPassword":"$sealedPassword"}""")
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("pwSalt", equalTo(newSalt))
        given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$shareId").then().statusCode(403)
        given().header("X-Share-Password", newToken).`when`().get("/api/shares/$shareId").then().statusCode(200).body("wrap", equalTo(wrap))

        // REMOVE the password → the resolve is open again, same link
        patch(userId, shareId, """{"passwordRequired":false}""")
        given().`when`().get("/api/shares/$shareId").then().statusCode(200)
            .body("passwordRequired", equalTo(false)).body("wrap", equalTo(wrap))
    }

    @Test
    fun manageListCarriesSealedPasswordForReveal() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        // the owner's manage list carries the reveal copy + the lock flag (the PublicShare resolve has no such
        // field at all — the reveal is structurally management-only)
        given().header("Authorization", bearer(userId)).`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.passwordRequired", equalTo(true))
            .body("find { it.shareId == '$shareId' }.sealedPassword", equalTo(sealedPassword))
            // …and the recovery copy it is opened through, with the scope epoch it is sealed under (ADR 0036 point 4)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", equalTo(shareKey))
            .body("find { it.shareId == '$shareId' }.sealedShareKeyEpoch", equalTo(0))
    }

    // --- helpers ---

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))

    private fun patch(userId: String, shareId: String, body: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(body).`when`().patch("/api/shares/$shareId").then().statusCode(204)

    private fun createWithPassword(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName","passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"$pwVerifier","sealedPassword":"$sealedPassword","sealedShareKey":"$shareKey","sealedShareKeyEpoch":0}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun createPlain(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
