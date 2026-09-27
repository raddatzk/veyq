package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.TenantProfileEntity
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for tenant activation — invite acceptance (uc.admin.create-tenant Slice B, cr-0010): both
 * paths to ACTIVE (register-and-accept for a new owner, sign-in accept for an existing one), the preview,
 * reuse-detection on a consumed secret, email mismatch, email-already-registered, unknown and expired
 * invites. Each test uses a distinct domain + email so the JVM-lived H2 mem DB does not bleed state.
 */
@QuarkusTest
class TenantAcceptSliceTest {

    @Inject
    lateinit var store: TenantStore

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun registerAndAcceptActivatesTenant() {
        val secret = "reg-secret-1"
        val id = seedPendingTenant("reg.example.com", "newowner@example.com", secret)
        given().contentType(ContentType.JSON).body(registerBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .body("userId", notNullValue())
        assert(store.findTenant(id)?.status == TenantEntity.ACTIVE)
        assert(ownerMembershipCount(id) == 1L)
        assert(userRole("newowner@example.com") == "MEMBER")
        // The invite-gated birth registers the Secret-Key proof key too (ADR 0060 Decision 2) — a tenant owner
        // born without it could never rotate, change their password or recover.
        assertEquals(
            "c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5",
            QuarkusTransaction.requiringNew().call<String?> {
                VeyqUser.find("email", "newowner@example.com").firstResult()?.secretKeyProofPublicKey
            },
        )
    }

    @Test
    fun existingUserAcceptActivatesTenant() {
        val secret = "acc-secret-1"
        val id = seedPendingTenant("acc.example.com", "owner2@example.com", secret)
        val userId = seedUser("owner2@example.com")
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret","username":"accowner","grant":"AQ=="}""")
            .`when`().post("/api/tenants/accept")
            .then().statusCode(200).body("status", equalTo("ACTIVE"))
        assert(store.findTenant(id)?.status == TenantEntity.ACTIVE)
        assert(ownerMembershipCount(id) == 1L)
    }

    @Test
    fun existingOwnerAcceptSealsTheOptionalNameAtJoin() {
        // cr-0106: the existing-account OWNER accept threads the optional sealedName onto the fresh profile row.
        val secret = "acc-name-secret"
        val id = seedPendingTenant("accname.example.com", "owner-name@example.com", secret)
        val userId = seedUser("owner-name@example.com")
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"named","grant":"AQ==","sealedName":"c2VhbGVkLW5hbWU="}""")
            .`when`().post("/api/tenants/accept")
            .then().statusCode(200).body("status", equalTo("ACTIVE"))
        // The server stored the opaque sealed blob verbatim on the profile row (never a plaintext name).
        assert(profileSealedName(id, userId) == "c2VhbGVkLW5hbWU=")
        // And a plain accept (no name) leaves it null — a grant-less join or an owner who skipped the field.
        val secret2 = "acc-noname-secret"
        val id2 = seedPendingTenant("accnoname.example.com", "owner-noname@example.com", secret2)
        val userId2 = seedUser("owner-noname@example.com")
        given().header("Authorization", "Bearer ${sessions.issue(userId2).accessToken}")
            .contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret2","username":"noname","grant":"AQ=="}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
        assert(profileSealedName(id2, userId2) == null)
    }

    @Test
    fun previewReturnsTenantAndAccountFlag() {
        val secret = "prev-secret-1"
        seedPendingTenant("prev.example.com", "prevowner@example.com", secret)
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview")
            .then().statusCode(200)
            .body("domain", equalTo("prev.example.com"))
            .body("ownerEmail", equalTo("prevowner@example.com"))
            .body("hasAccount", equalTo(false))
    }

    @Test
    fun replayOfConsumedSecretIs410() {
        val secret = "replay-secret-1"
        seedPendingTenant("replay.example.com", "replay@example.com", secret)
        given().contentType(ContentType.JSON).body(registerBody(secret))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        // Same secret again — the invite is now CONSUMED → reuse detected.
        given().contentType(ContentType.JSON).body(registerBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    @Test
    fun emailMismatchIsForbidden() {
        val secret = "mm-secret-1"
        seedPendingTenant("mm.example.com", "intended@example.com", secret)
        val otherId = seedUser("other@example.com")
        given().header("Authorization", "Bearer ${sessions.issue(otherId).accessToken}")
            .contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret","username":"accowner","grant":"AQ=="}""")
            .`when`().post("/api/tenants/accept")
            .then().statusCode(403).body("code", equalTo("not_invited_owner"))
    }

    @Test
    fun registerWhenEmailTakenIs409() {
        val secret = "taken-secret-1"
        seedPendingTenant("taken.example.com", "taken@example.com", secret)
        seedUser("taken@example.com")
        given().contentType(ContentType.JSON).body(registerBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(409).body("code", equalTo("email_taken"))
    }

    @Test
    fun unknownSecretIs404() {
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"never-issued-token"}""")
            .`when`().post("/api/tenants/accept/preview")
            .then().statusCode(404).body("code", equalTo("invite_not_found"))
    }

    @Test
    fun expiredInviteIs410() {
        val secret = "expired-secret-1"
        seedPendingTenant("expired.example.com", "expired@example.com", secret, ttl = -1)
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    @Test
    fun unauthenticatedAcceptIs401() {
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"x","username":"anyone","grant":"AQ=="}""")
            .`when`().post("/api/tenants/accept")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun seedPendingTenant(domain: String, ownerEmail: String, secret: String, ttl: Long = 7): String =
        QuarkusTransaction.requiringNew().call {
            store.createPendingTenant("Fam", domain, ownerEmail, Tokens.hash(secret), Instant.now().plus(ttl, ChronoUnit.DAYS)).first
        }

    private fun seedUser(email: String, role: String = "MEMBER"): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
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
        return userId
    }

    private fun ownerMembershipCount(tenantId: String): Long =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.count("tenantId = ?1 and role = ?2", tenantId, MembershipEntity.OWNER)
        }

    private fun userRole(email: String): String? =
        QuarkusTransaction.requiringNew().call { VeyqUser.find("email", email).firstResult()?.serverRole }

    private fun profileSealedName(tenantId: String, userId: String): String? =
        QuarkusTransaction.requiringNew().call {
            TenantProfileEntity.find("tenantId = ?1 and userId = ?2", tenantId, userId).firstResult()?.sealedName
        }

    private fun registerBody(secret: String) = """
        {"bootstrapSecret":"$secret","userId":"${UUID.randomUUID()}","username":"newowner",
         "account":{"suiteId":"v1",
           "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"},
         "grant":"AQ=="}
    """.trimIndent()
}
