package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.adminchannel.givenAdminChannel
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for Server-Owner tenant administration (uc.admin.create-tenant Slice A, cr-0009): create a
 * PENDING tenant, reject a duplicate domain, list, resend/reassign (soft-supersede the old invite), revoke,
 * the Server-Owner gate (401/403), and the scheduled invite purge. Each test uses a distinct domain so the
 * JVM-lived H2 mem DB does not bleed state across tests.
 *
 * The `…ReturnsTheRawSecret…` tests are the out-of-band handoff (cr-0080): create/resend/reassign hand the
 * Server Owner the raw bootstrap secret, and the proof that it is the *minted* one — not merely a non-null
 * string — is the invitee's side of the link: it previews to this tenant and its accept activates it.
 */
@QuarkusTest
class TenantsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var store: TenantStore

    @BeforeEach
    fun seed() {
        seedUser(OWNER_ID, "owner@example.com", "SERVER_OWNER")
        seedUser(MEMBER_ID, "member@example.com", "MEMBER")
    }

    @Test
    fun createReturns201Pending() {
        createTenant(ownerToken(), domain = "fam-create.example.com")
            .then().statusCode(201)
            .body("status", equalTo("PENDING"))
            .body("tenantId", notNullValue())
            .body("inviteExpiresAt", notNullValue())
            // cr-email-invite-send-hardening: a PENDING tenant has no mail settings, so the bootstrap invite always
            // reports the copy-link fallback (there is no SMTP path to reach on create/resend/reassign).
            .body("delivery", equalTo("FALLBACK_COPYLINK"))
    }

    @Test
    fun duplicateDomainIs409() {
        createTenant(ownerToken(), domain = "dup.example.com").then().statusCode(201)
        createTenant(ownerToken(), domain = "dup.example.com").then().statusCode(409)
            .body("code", equalTo("domain_taken"))
    }

    @Test
    fun listIncludesCreatedTenantWithActiveInvite() {
        val id = createTenant(ownerToken(), domain = "fam-list.example.com")
            .then().statusCode(201).extract().path<String>("tenantId")
        givenAdminChannel().header("Authorization", "Bearer ${ownerToken()}").`when`().get("/api/tenants")
            .then().statusCode(200)
            .body("find { it.tenantId == '$id' }.status", equalTo("PENDING"))
            .body("find { it.tenantId == '$id' }.inviteStatus", equalTo("ACTIVE"))
    }

    @Test
    fun resendSupersedesOldInviteAndKeepsOneActive() {
        val id = createTenant(ownerToken(), domain = "fam-resend.example.com")
            .then().statusCode(201).extract().path<String>("tenantId")
        resend(ownerToken(), id).then().statusCode(200).body("inviteStatus", equalTo("ACTIVE"))
        assert(inviteCount(id, TenantInviteEntity.ACTIVE) == 1L)
        assert(inviteCount(id, TenantInviteEntity.SUPERSEDED) == 1L)
    }

    @Test
    fun reassignRebindsEmailAndSupersedes() {
        val id = createTenant(ownerToken(), domain = "fam-reassign.example.com", email = "first@example.com")
            .then().statusCode(201).extract().path<String>("tenantId")
        reassign(ownerToken(), id, "second@example.com")
            .then().statusCode(200)
            .body("ownerEmail", equalTo("second@example.com"))
            .body("inviteStatus", equalTo("ACTIVE"))
        assert(store.findTenant(id)?.ownerEmail == "second@example.com")
        assert(inviteCount(id, TenantInviteEntity.SUPERSEDED) == 1L)
    }

    @Test
    fun createReturnsTheRawSecretThatActivatesTheTenant() {
        val created = createTenant(ownerToken(), domain = "fam-handoff-create.example.com", email = "handoff-create@example.com")
            .then().statusCode(201).extract()
        val id = created.path<String>("tenantId")
        val secret = created.path<String>("bootstrapSecret")
        preview(secret).then().statusCode(200)
            .body("tenantId", equalTo(id))
            .body("ownerEmail", equalTo("handoff-create@example.com"))
        accept(secret, invitedEmail = "handoff-create@example.com").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assert(store.findTenant(id)?.status == TenantEntity.ACTIVE)
    }

    @Test
    fun resendReturnsTheRawSecretThatActivatesTheTenant() {
        val created = createTenant(ownerToken(), domain = "fam-handoff-resend.example.com", email = "handoff-resend@example.com")
            .then().statusCode(201).extract()
        val id = created.path<String>("tenantId")
        val first = created.path<String>("bootstrapSecret")
        val fresh = resend(ownerToken(), id).then().statusCode(200).extract().path<String>("bootstrapSecret")
        assert(fresh != first)
        // The link the Server Owner handed out before the resend is dead; only the fresh one works.
        preview(first).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        preview(fresh).then().statusCode(200).body("tenantId", equalTo(id))
        accept(fresh, invitedEmail = "handoff-resend@example.com").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assert(store.findTenant(id)?.status == TenantEntity.ACTIVE)
    }

    @Test
    fun reassignReturnsTheRawSecretThatActivatesTheTenant() {
        val created = createTenant(ownerToken(), domain = "fam-handoff-reassign.example.com", email = "handoff-first@example.com")
            .then().statusCode(201).extract()
        val id = created.path<String>("tenantId")
        val first = created.path<String>("bootstrapSecret")
        val fresh = reassign(ownerToken(), id, "handoff-second@example.com")
            .then().statusCode(200).extract().path<String>("bootstrapSecret")
        preview(first).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        // The fresh secret is bound to the re-bound email: it previews for, and is accepted by, the new owner.
        preview(fresh).then().statusCode(200)
            .body("tenantId", equalTo(id))
            .body("ownerEmail", equalTo("handoff-second@example.com"))
        accept(fresh, invitedEmail = "handoff-second@example.com").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assert(store.findTenant(id)?.status == TenantEntity.ACTIVE)
    }

    @Test
    fun revokePendingTenantDeletesIt() {
        val id = createTenant(ownerToken(), domain = "fam-revoke.example.com")
            .then().statusCode(201).extract().path<String>("tenantId")
        givenAdminChannel().header("Authorization", "Bearer ${ownerToken()}").`when`().delete("/api/tenants/$id")
            .then().statusCode(204)
        assert(store.findTenant(id) == null)
        resend(ownerToken(), id).then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun nonOwnerIsForbidden() {
        createTenant(memberToken(), domain = "fam-forbidden.example.com")
            .then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().header("Authorization", "Bearer ${memberToken()}").`when`().get("/api/tenants")
            .then().statusCode(403).body("code", equalTo("not_server_owner"))
    }

    @Test
    fun unauthenticatedIs401() {
        givenAdminChannel().contentType(ContentType.JSON)
            .body("""{"tenantName":"X","domain":"fam-401.example.com","ownerEmail":"x@example.com"}""")
            .`when`().post("/api/tenants")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun resendOnUnknownTenantIs404() {
        resend(ownerToken(), UUID.randomUUID().toString())
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun invalidDomainIs400() {
        createTenant(ownerToken(), domain = "NOT a domain")
            .then().statusCode(400)
    }

    // Boundary validation runs with a valid Server-Owner token (auth passes first, then @Valid → 400). The
    // anonymous side of these now uniformly 401s before validation (cr-0012 eager security), so the malformed-
    // body 400 contract lives here, where a real session exists.
    @Test
    fun blankNameIs400() {
        createTenant(ownerToken(), name = "   ", domain = "fam-blank-name.example.com")
            .then().statusCode(400)
    }

    @Test
    fun malformedOwnerEmailIs400() {
        givenAdminChannel().header("Authorization", "Bearer ${ownerToken()}").contentType(ContentType.JSON)
            .body("""{"tenantName":"Fam","domain":"fam-bad-email.example.com","ownerEmail":"not-an-email"}""")
            .`when`().post("/api/tenants")
            .then().statusCode(400)
    }

    @Test
    fun purgeExpiresActiveAndDeletesTerminalInvites() {
        // A pending tenant to anchor invites; then insert an overdue ACTIVE and an old SUPERSEDED invite.
        val id = createTenant(ownerToken(), domain = "fam-purge.example.com")
            .then().statusCode(201).extract().path<String>("tenantId")
        val past = Instant.now().minus(60, ChronoUnit.DAYS)
        QuarkusTransaction.requiringNew().run {
            insertInvite(id, "overdue@example.com", TenantInviteEntity.ACTIVE, expiresAt = past)
            insertInvite(id, "old@example.com", TenantInviteEntity.SUPERSEDED, expiresAt = past)
        }
        // Expire flips the overdue ACTIVE → EXPIRED; purge then deletes terminal rows past the cutoff.
        assert(store.expireOverdueInvites(Instant.now()) >= 1L)
        val deleted = store.purgeTerminalInvites(Instant.now())
        assert(deleted >= 2L) // the seeded SUPERSEDED + the just-EXPIRED overdue one
        assert(inviteCount(id, TenantInviteEntity.SUPERSEDED) == 0L)
        assert(inviteCount(id, TenantInviteEntity.EXPIRED) == 0L)
    }

    // --- helpers ---

    private fun ownerToken() = sessions.issue(OWNER_ID).accessToken
    private fun memberToken() = sessions.issue(MEMBER_ID).accessToken

    private fun createTenant(token: String, name: String = "Fam", domain: String, email: String = "owner-x@example.com") =
        givenAdminChannel().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body("""{"tenantName":"$name","domain":"$domain","ownerEmail":"$email"}""")
            .`when`().post("/api/tenants")

    private fun resend(token: String, tenantId: String) =
        givenAdminChannel().header("Authorization", "Bearer $token").`when`().post("/api/tenants/$tenantId/invite/resend")

    private fun reassign(token: String, tenantId: String, ownerEmail: String) =
        givenAdminChannel().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body("""{"ownerEmail":"$ownerEmail"}""")
            .`when`().post("/api/tenants/$tenantId/invite/reassign")

    // The invitee's side of the link (TenantAcceptSliceTest owns its own criteria): public preview, and the
    // existing-account accept by a user seeded with the invited email.
    private fun preview(secret: String) =
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview")

    private fun accept(secret: String, invitedEmail: String): Response {
        val userId = UUID.randomUUID().toString()
        seedUser(userId, invitedEmail, "MEMBER")
        return given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret","username":"handoff","grant":"AQ=="}""")
            .`when`().post("/api/tenants/accept")
    }

    private fun inviteCount(tenantId: String, status: String): Long =
        QuarkusTransaction.requiringNew().call {
            TenantInviteEntity.count("tenantId = ?1 and status = ?2", tenantId, status)
        }

    private fun insertInvite(tenantId: String, email: String, status: String, expiresAt: Instant) {
        TenantInviteEntity().apply {
            id = UUID.randomUUID().toString()
            this.tenantId = tenantId
            ownerEmail = email
            tokenHash = UUID.randomUUID().toString().replace("-", "")
            this.status = status
            this.expiresAt = expiresAt
            createdAt = Instant.now()
        }.persist()
    }

    private fun seedUser(userId: String, email: String, role: String) {
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(userId) == null) {
                VeyqUser().apply {
                    id = userId
                    this.email = email
                    serverRole = role
                    suiteId = "v1"
                    identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                    wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                    authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                    createdAt = Instant.now()
                }.persist()
            }
        }
    }

    companion object {
        private const val OWNER_ID = "tenant-owner-user"
        private const val MEMBER_ID = "tenant-member-user"
    }
}
