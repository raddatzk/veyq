package com.veyq.app.shared.membership

import com.veyq.app.features.admin.tenant.TenantStore
import com.veyq.app.features.tasks.CapabilityTags
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Every path that writes a grant records the key its granter signed it with (ADR 0028 § What is still open,
 * inbox #233). The reads serve that copy, so a writer that forgets it serves no granter key at all and the
 * recipient refuses the grant as unsigned.
 *
 * One leg per writer, each through the writer's real HTTP path, each granter holding an identity key no other
 * account in the suite holds — so a leg cannot pass on a key some other row happens to carry. The legs are the five
 * writers `scripts/validate_grant_writer_arity.py` derives from the stores and pins by name: a sixth writer turns
 * that guard red until it is named there, and it owes a leg here.
 */
@QuarkusTest
class GrantWriterSnapshotSliceTest {

    @Inject
    lateinit var tenants: TenantStore

    @Test
    fun aPersonalScopeBirthRecordsItsOwnersKey() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val scopeId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","wrappedGrant":"$GRANT",${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(owner, scopeId))
    }

    @Test
    fun aGroupBirthRecordsItsCreatorsKey() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val scopeId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","encryptedName":"bmFtZQ==","wrappedGrant":"$GRANT",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/scopes/groups").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(owner, scopeId))
    }

    @Test
    fun aTenantsColdBirthRecordsItsOwnersKey() {
        val (owner, key) = granter()
        val secret = "snapshot-$owner"
        val tenantId = QuarkusTransaction.requiringNew().call {
            tenants.createPendingTenant(
                "Fam", "snap-$owner.example.com", "u-$owner@example.com", Tokens.hash(secret),
                Instant.now().plus(1, ChronoUnit.DAYS),
            ).first
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"snapowner","grant":"$GRANT",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)

        assertEquals(owner to key, snapshotOf(owner, tenantId))
    }

    @Test
    fun aRotationRecordsTheRotatorsKeyOnEveryGrantItWrites() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId; scopeId = tenantId
                role = MembershipEntity.MEMBER; wrappedGrant = GRANT; createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[
                   {"userId":"$owner","wrappedGrant":"$GRANT",${TestAdmission.SIGNATURE_JSON}},
                   {"userId":"$member","wrappedGrant":"$GRANT",${TestAdmission.SIGNATURE_JSON}}],
                   ${TestAdmission.openJson(tenantId)}}""",
            )
            .`when`().post("/api/scopes/$tenantId/epochs").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(member, tenantId), "the foreign re-grant")
        assertEquals(owner to key, snapshotOf(owner, tenantId), "the rotator's own re-grant")
    }

    @Test
    fun aGrantDeliveryRecordsTheWorkersKey() {
        val (worker, key) = granter()
        val tenantId = seedTenant(owner = worker)
        val target = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = target; this.tenantId = tenantId; scopeId = tenantId
                role = MembershipEntity.MEMBER; wrappedGrant = null; createdAt = Instant.now()
            }.persist()
        }
        val jobId = given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim").then().statusCode(200).extract().path<String>("jobId")
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"$GRANT","epoch":0,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)

        assertEquals(worker to key, snapshotOf(target, tenantId))
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** An account whose identity key is its own — no other row in the JVM-wide H2 database carries it. Its email is
     *  [seedUser]'s default, `u-<id>@example.com`, which a tenant invite can name. */
    private fun granter(): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val key = Base64.getEncoder().encodeToString("identity-of-$id".toByteArray())
        return seedUser(id = id, identityPublicKey = key) to key
    }

    /** The granter and granter key recorded on [userId]'s grant in [scopeId]. */
    private fun snapshotOf(userId: String, scopeId: String): Pair<String?, String?> = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()!!
            .let { it.granterUserId to it.granterIdentityPublicKey }
    }

    private companion object {
        const val GRANT = "Z3JhbnQtYnktYS1yZWFsLXdyaXRlcg=="
    }
}
