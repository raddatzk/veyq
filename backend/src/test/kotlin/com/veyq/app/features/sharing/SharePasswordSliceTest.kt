package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for password-protected shares (uc.sharing.share-password, cr-0118, ADR 0037) — an operator-blind
 * ACCESS-CONTROL gate: a locked resolve (no token) hides everything but the challenge; a valid `X-Share-Password`
 * token unlocks the full share; a wrong token is 403; the blob GET needs the token too; set/change/remove via
 * `updateShare` keeps the same link; the manage list carries the `sealed_password` reveal copy (never the viewer).
 * The server stores only the verifier (`SHA-256(pwToken)`) + salt + the sealed copy — never the raw password.
 */
@QuarkusTest
class SharePasswordSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val pwSalt = "cHctc2FsdA==" // "pw-salt"
    private val pwToken = "cHctdG9rZW4=" // "pw-token" — what the guest presents in X-Share-Password
    private val pwVerifier = sha256b64(pwToken) // SHA-256(decode(pwToken)) — what the server stores + checks
    private val wrongToken = "d3JvbmctdG9rZW4=" // "wrong-token"
    private val sealedPassword = "c2VhbGVkLXB3" // the plaintext password sealed under K_share (opaque here)

    @Test
    fun createThenLockedResolveHidesTheContent() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedMemory(scopeId))

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
        val shareId = createWithPassword(userId, seedMemory(scopeId))

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
        val shareId = createWithPassword(userId, seedMemory(scopeId))

        // the gate runs before the blob lookup: no token → 404 (non-enumerable), wrong token → 403
        given().`when`().get("/api/shares/$shareId/blobs/${UUID.randomUUID()}").then().statusCode(404)
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId/blobs/${UUID.randomUUID()}")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    @Test
    fun setChangeRemoveViaUpdateShareKeepsTheLink() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
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
        val shareId = createWithPassword(userId, seedMemory(scopeId))

        // the owner's manage list carries the reveal copy + the lock flag (the PublicShare resolve has no such
        // field at all — the reveal is structurally management-only)
        given().header("Authorization", token(userId)).`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.passwordRequired", equalTo(true))
            .body("find { it.shareId == '$shareId' }.sealedPassword", equalTo(sealedPassword))
    }

    // --- helpers ---

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))

    private fun patch(userId: String, shareId: String, body: String) =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body(body).`when`().patch("/api/shares/$shareId").then().statusCode(204)

    private fun createWithPassword(userId: String, nodeId: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"$pwVerifier","sealedPassword":"$sealedPassword"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun createPlain(userId: String, nodeId: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun seedMemory(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
