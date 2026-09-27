package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.TenantProfileStore
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for per-tenant member identity (cr-0083, ADR 0023): a `username` chosen at join (unique **within**
 * the tenant, `409 username_taken`), the same handle free in another tenant, the directory showing username +
 * **owner-only** email + the sealed name, and the profile op round-tripping the username + sealed name. Distinct
 * domains/emails so the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class MemberIdentitySliceTest {

    @Inject lateinit var sessions: SessionService
    @Inject lateinit var profiles: TenantProfileStore

    private fun bearer(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun twoMembersCannotShareAUsernameInOneTenant() {
        val (tenantId, _) = seedActiveTenant("id-dup.example.com", "owner@id-dup.example.com")
        seedMemberInvite(tenantId, "a@id-dup.example.com", "sa")
        seedMemberInvite(tenantId, "b@id-dup.example.com", "sb")

        // First member takes @twin.
        given().contentType(ContentType.JSON).body(memberRegisterBody("sa", "twin"))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        // Second member, same tenant, same handle → 409 username_taken (case-insensitive: TWIN == twin).
        given().contentType(ContentType.JSON).body(memberRegisterBody("sb", "TWIN"))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(409).body("code", equalTo("username_taken"))
    }

    @Test
    fun theSameUsernameIsFreeInAnotherTenant() {
        val (t1, _) = seedActiveTenant("id-a.example.com", "owner@id-a.example.com")
        val (t2, _) = seedActiveTenant("id-b.example.com", "owner@id-b.example.com")
        seedMemberInvite(t1, "m@id-a.example.com", "s1")
        seedMemberInvite(t2, "m@id-b.example.com", "s2")

        given().contentType(ContentType.JSON).body(memberRegisterBody("s1", "kevin"))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        // Same handle, different tenant → fine (uniqueness is per-tenant; email is the only global key).
        given().contentType(ContentType.JSON).body(memberRegisterBody("s2", "kevin"))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
    }

    @Test
    fun directoryShowsUsernameAndOwnerOnlyEmail() {
        val (tenantId, owner) = seedActiveTenant("id-dir.example.com", "owner@id-dir.example.com")
        seedProfile(owner, tenantId, "theowner")
        val member = seedGrantedMember(tenantId, "member@id-dir.example.com", "cGstbWVtYmVy")
        seedProfile(member, tenantId, "themember")

        // The OWNER sees usernames + emails.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.userId == '$owner' }.username", equalTo("theowner"))
            .body("find { it.userId == '$member' }.username", equalTo("themember"))
            .body("find { it.userId == '$member' }.email", equalTo("member@id-dir.example.com"))

        // A non-owner member sees usernames but NOT emails (owner-only).
        given().header("Authorization", bearer(member))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200)
            .body("find { it.userId == '$owner' }.username", equalTo("theowner"))
            .body("find { it.userId == '$owner' }.email", nullValue())
            .body("find { it.userId == '$member' }.email", nullValue())
    }

    @Test
    fun profileOpRoundTripsUsernameAndSealedName() {
        val (tenantId, owner) = seedActiveTenant("id-prof.example.com", "owner@id-prof.example.com")
        seedProfile(owner, tenantId, "before")

        // Seal a name + change the handle.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "id-prof.example.com")
            .contentType(ContentType.JSON).body("""{"username":"after","sealedName":"c2VhbGVkLW5hbWU="}""")
            .`when`().put("/api/account/profile")
            .then().statusCode(200)
            .body("username", equalTo("after")).body("sealedName", equalTo("c2VhbGVkLW5hbWU="))

        // GET reflects the update.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "id-prof.example.com")
            .`when`().get("/api/account/profile")
            .then().statusCode(200)
            .body("username", equalTo("after")).body("sealedName", equalTo("c2VhbGVkLW5hbWU="))
    }

    @Test
    fun registerAndAcceptSealsTheOptionalNameAtJoin() {
        val (tenantId, owner) = seedActiveTenant("id-join.example.com", "owner@id-join.example.com")
        seedProfile(owner, tenantId, "theowner")
        seedMemberInvite(tenantId, "named@id-join.example.com", "sname")
        seedMemberInvite(tenantId, "plain@id-join.example.com", "splain")

        // A joins WITH a sealed name at accept (cr-0106); B joins with none (username only).
        given().contentType(ContentType.JSON).body(memberRegisterBody("sname", "named", sealedName = "c2VhbGVkLW5hbWU="))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        given().contentType(ContentType.JSON).body(memberRegisterBody("splain", "plain"))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)

        // The directory carries the opaque blob for A verbatim, null for B — the server never saw a name.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200)
            .body("find { it.username == 'named' }.sealedName", equalTo("c2VhbGVkLW5hbWU="))
            .body("find { it.username == 'plain' }.sealedName", nullValue())
    }

    @Test
    fun changingToATakenUsernameIs409() {
        val (tenantId, owner) = seedActiveTenant("id-chg.example.com", "owner@id-chg.example.com")
        seedProfile(owner, tenantId, "owner-handle")
        val member = seedGrantedMember(tenantId, "member@id-chg.example.com", "cGstbQ==")
        seedProfile(member, tenantId, "member-handle")

        // The member tries to rename to the owner's handle → 409.
        given().header("Authorization", bearer(member)).header("X-Forwarded-Host", "id-chg.example.com")
            .contentType(ContentType.JSON).body("""{"username":"owner-handle"}""")
            .`when`().put("/api/account/profile")
            .then().statusCode(409).body("code", equalTo("username_taken"))
    }

    // --- helpers ---

    private fun seedActiveTenant(domain: String, ownerEmail: String): Pair<String, String> {
        val tenantId = UUID.randomUUID().toString()
        val ownerId = seedUser(ownerEmail)
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; this.domain = domain; this.ownerEmail = ownerEmail
                status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = ownerId; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.OWNER; wrappedGrant = "b3duZXItZ3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return tenantId to ownerId
    }

    private fun seedGrantedMember(tenantId: String, email: String, pubkey: String): String {
        val userId = seedUser(email, pubkey)
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = "bWVtYmVyLWdyYW50"; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    private fun seedProfile(userId: String, tenantId: String, username: String) =
        QuarkusTransaction.requiringNew().run { profiles.create(userId, tenantId, username) }

    private fun seedMemberInvite(tenantId: String, email: String, secret: String) =
        QuarkusTransaction.requiringNew().run {
            // mintMemberInvite lives on TenantStore; use a fresh instance via the entity API to avoid an extra inject.
            TenantInviteEntity().apply {
                id = UUID.randomUUID().toString(); this.tenantId = tenantId; ownerEmail = email
                tokenHash = Tokens.hash(secret); status = TenantInviteEntity.ACTIVE; role = TenantInviteEntity.MEMBER
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); createdAt = Instant.now()
            }.persist()
        }

    private fun seedUser(email: String, pubkey: String = "aWRlbnRpdHktcHVibGljLWtleQ=="): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; this.email = email; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = pubkey; wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    private fun memberRegisterBody(secret: String, username: String, sealedName: String? = null): String {
        val nameField = sealedName?.let { ""","sealedName":"$it"""" } ?: ""
        return """
            {"bootstrapSecret":"$secret","userId":"${UUID.randomUUID()}","username":"$username"$nameField,
             "account":{"suiteId":"v1",
               "identityPublicKey":"bWVtYmVyLXB1YmxpYy1rZXk=",
               "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
               "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
               "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
               "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
        """.trimIndent()
    }
}
