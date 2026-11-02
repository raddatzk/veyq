package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.testing.TestScopeRekey
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ADR 0111 decision 10 on every grant writer that lacked it (cr-secure-the-account): a grant names the key it
 * is wrapped to (`recipientIdentityDigest`), and the server refuses one that is not the recipient's CURRENT identity
 * with `409 recipient_key_not_current`, nothing written — so no grant to an account's retired identity can be written,
 * whichever writer a stale client uses. Each case: the account's identity was replaced (its retired key is [RETIRED]),
 * a grant naming the retired key is refused, and the same grant naming the current key passes (the control).
 *
 * The four writers ADR 0111 decisions 1-5 added (member invite, its accept, a tenant rotation's invite re-grant, a group
 * add) carry their own legs in their own suites; the staged self-grant's is `RotationSliceTest`'s.
 */
@QuarkusTest
class RecipientKeySliceTest {

    @Inject lateinit var tenants: TenantStore

    @Test
    fun aPersonalSpaceBornToTheRetiredIdentityIsRefused() {
        val (user, tenant) = rotatedMemberOf(seedTenant())
        createPersonal(user, tenant, TestGrants.digest(RETIRED)).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(0L, scopesOf(user, ScopeEntity.PERSONAL))
        createPersonal(user, tenant, TestGrants.digestOf(user)).then().statusCode(201)
    }

    @Test
    fun aGroupBornToTheRetiredIdentityIsRefused() {
        val (user, tenant) = rotatedMemberOf(seedTenant())
        createGroup(user, tenant, TestGrants.digest(RETIRED)).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(0L, scopesOf(user, ScopeEntity.GROUP))
        createGroup(user, tenant, TestGrants.digestOf(user)).then().statusCode(201)
    }

    @Test
    fun aRotationGrantToTheRetiredIdentityIsRefused() {
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val (member, _) = rotatedMemberOf(tenant)

        rotate(owner, tenant, mapOf(owner to TestGrants.digestOf(owner), member to TestGrants.digest(RETIRED)))
            .then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(0, TestGrants.epochOf(tenant))
        rotate(owner, tenant, mapOf(owner to TestGrants.digestOf(owner), member to TestGrants.digestOf(member))).then().statusCode(201)
    }

    @Test
    fun aRotationThatLeavesAMemberOutSilentlyIsRefused() {
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val other = seedUser().also { joinRoot(it, tenant) } // a second member every case below grants
        val member = seedUser().also { joinRoot(it, tenant) }
        val grants = mapOf(owner to TestGrants.digestOf(owner), other to TestGrants.digestOf(other))

        rotate(owner, tenant, grants).then().statusCode(409).body("code", equalTo("rotation_incomplete"))
        assertEquals(0, TestGrants.epochOf(tenant))
        rotate(owner, tenant, grants, pending = listOf(member)).then().statusCode(201) // control: declared pending
        assertEquals(0, grantedEpochOf(member, tenant), "a declared member stays behind: its grant is the pending marker")
    }

    @Test
    fun aDeliveredGrantToTheRetiredIdentityIsRefusedAndReleased() {
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val (target, _) = rotatedMemberOf(tenant)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.update("currentEpoch = 1 where id = ?1", tenant)
            MembershipEntity.update("grantedEpoch = 1 where userId = ?1 and scopeId = ?2", owner, tenant)
        }
        val jobId = claimDelivery(owner, tenant)

        complete(owner, jobId, TestGrants.digest(RETIRED)).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(0, grantedEpochOf(target, tenant), "nothing written")
        assertEquals(JobEntity.PENDING to 0, jobState(jobId), "released without spending an attempt")

        complete(owner, claimDelivery(owner, tenant), TestGrants.digestOf(target)).then().statusCode(204)
        assertEquals(1, grantedEpochOf(target, tenant))
    }

    @Test
    fun anOwnerBirthToAnotherIdentityIsRefused() {
        val email = "birth-${UUID.randomUUID()}@example.com"
        val secret = "birth-${UUID.randomUUID()}"
        val tenant = pendingTenant(email, secret)
        val (owner, _) = rotated(seedUser(email = email))

        ownerAccept(owner, secret, TestGrants.digest(RETIRED)).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(TenantEntity.PENDING, statusOf(tenant))
        ownerAccept(owner, secret, TestGrants.digestOf(owner)).then().statusCode(200)
        assertEquals(TenantEntity.ACTIVE, statusOf(tenant))
    }

    @Test
    fun aRegisteredOwnerBirthToAnotherIdentityIsRefused() {
        val secret = "reg-${UUID.randomUUID()}"
        val tenant = pendingTenant("reg-${UUID.randomUUID()}@example.com", secret)

        register(secret, TestGrants.digest(RETIRED)).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(TenantEntity.PENDING, statusOf(tenant))
        register(secret, TestGrants.digest(BORN)).then().statusCode(201)
        assertEquals(TenantEntity.ACTIVE, statusOf(tenant))
    }

    // --- fixtures ---

    /** A fresh account whose identity was replaced: it was born under [RETIRED] and holds [CURRENT] now. */
    private fun rotated(userId: String): Pair<String, String> {
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(userId)!!.identityPublicKey = CURRENT }
        return userId to CURRENT
    }

    private fun rotatedMemberOf(tenant: String): Pair<String, String> {
        val (user, _) = rotated(seedUser(identityPublicKey = RETIRED))
        joinRoot(user, tenant)
        return user to tenant
    }

    private fun joinRoot(user: String, tenant: String) = QuarkusTransaction.requiringNew().run {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); userId = user; tenantId = tenant; scopeId = tenant
            role = MembershipEntity.MEMBER; wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
        }.persist()
    }

    private fun createPersonal(user: String, tenant: String, digest: String) =
        given().header("Authorization", bearer(user)).header("X-Forwarded-Host", "t-$tenant.example.com").contentType(ContentType.JSON)
            .body("""{"scopeId":"${UUID.randomUUID()}","wrappedGrant":"AQ==",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$digest"}""")
            .`when`().post("/api/scopes")

    private fun createGroup(user: String, tenant: String, digest: String) =
        given().header("Authorization", bearer(user)).header("X-Forwarded-Host", "t-$tenant.example.com").contentType(ContentType.JSON)
            .body(
                """{"scopeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==","wrappedNameValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=","wrappedGrant":"AQ==",${TestGrants.SIGNATURE_JSON},""" +
                    """"recipientIdentityDigest":"$digest"}""",
            )
            .`when`().post("/api/scopes/groups")

    private fun rotate(caller: String, scope: String, grants: Map<String, String>, pending: List<String> = emptyList()) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scope) + 1},${TestScopeRekey.rotationJson(scope)},"grants":[${
                    grants.entries.joinToString(",") { (u, d) ->
                        """{"userId":"$u","wrappedGrant":"Z3JhbnQ=",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$d"}"""
                    }
                }],"pendingUserIds":[${pending.joinToString(",") { "\"$it\"" }}],${TestGrants.openInvitesJson(scope)}}""",
            )
            .`when`().post("/api/scopes/$scope/epochs")

    private fun claimDelivery(worker: String, scope: String): String =
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"],"scopeId":"$scope"}""")
            .`when`().post("/api/tasks/claim").then().statusCode(200).extract().path("jobId")

    private fun complete(worker: String, jobId: String, digest: String) =
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"Z3JhbnQ=","epoch":1,${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$digest"}""")
            .`when`().post("/api/tasks/$jobId/complete")

    private fun pendingTenant(ownerEmail: String, secret: String): String = QuarkusTransaction.requiringNew().call {
        tenants.createPendingTenant("Fam", "p-${UUID.randomUUID()}.example.com", ownerEmail, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS)).first
    }

    private fun ownerAccept(user: String, secret: String, digest: String) =
        given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"owner","grant":"AQ==",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$digest"}""")
            .`when`().post("/api/tenants/accept")

    private fun register(secret: String, digest: String) =
        given().contentType(ContentType.JSON)
            .body(
                """{"bootstrapSecret":"$secret","userId":"${UUID.randomUUID()}","username":"owner",
                   "account":{"suiteId":"v1","identityPublicKey":"$BORN","wrappedIdentityPrivateKey":"d3JhcHBlZA==",
                     "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
                     "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
                     "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"},
                   "grant":"AQ==",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$digest"}""",
            )
            .`when`().post("/api/tenants/accept/register")

    private fun memberIds(scope: String): List<String> =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("scopeId", scope).list().map { it.userId } }

    private fun grantedEpochOf(user: String, scope: String): Int = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", user, scope).firstResult()!!.grantedEpoch
    }

    private fun jobState(jobId: String): Pair<String, Int> =
        QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.let { it.status to it.attempts } }

    private fun scopesOf(user: String, kind: String): Long = QuarkusTransaction.requiringNew().call {
        MembershipEntity.count("userId = ?1 and scopeId in (select s.id from ScopeEntity s where s.kind = ?2)", user, kind)
    }

    private fun statusOf(tenant: String): String? = QuarkusTransaction.requiringNew().call<String?> { TenantEntity.findById(tenant)?.status }

    private companion object {
        const val RETIRED = "cmV0aXJlZC1pZGVudGl0eS1idW5kbGU="
        const val CURRENT = "Y3VycmVudC1pZGVudGl0eS1idW5kbGU="
        const val BORN = "Ym9ybi1pZGVudGl0eS1idW5kbGU="
    }
}
