package me.raddatz.veyq.core.admin.tenant

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for the member profile picture (uc.spaces.set-profile-picture, ADR 0084): set / overwrite / clear and the
 * revision each bumps, the tenant-gated read with its `ETag` / `304`, the directory that carries the revision and never
 * the bytes, the removal that clears the picture and keeps the handle, and the owner join that seals it at accept.
 * Distinct domains/emails per test so the JVM-lived H2 mem DB cannot bleed state.
 */
@QuarkusTest
class MemberAvatarSliceTest {

    @Inject lateinit var profiles: TenantProfileStore

    @Inject lateinit var store: TenantStore

    private val full = "A".repeat(AVATAR_LEN)

    @Test
    fun theContractLengthIsTheSealedConstantsBase64Length() {
        // A field envelope (ADR 0093 § 9): 1 (type) + 16 (salt) + 12 (nonce) + 16 KiB pad + 16 (tag) = 16429 bytes; base64
        // of that is 4 * ceil(16429 / 3) chars. Not the tenant icon's 21884 any more: the icon is a plain seal, no salt.
        assertEquals(21908, 4 * Math.ceilDiv(1 + 16 + 12 + 16384 + 16, 3))
        assertEquals(21908, AVATAR_LEN)
        assertTrue(AVATAR_LEN % 4 == 0 && AVATAR_LEN <= 65532, "inside the TEXT column")
    }

    @Test
    fun setThenReadRoundTripsWithAnETagAndPrivateCaching() {
        val t = world("av-rt")
        put(t, t.member, "c2VhbGVkLWF2YXRhcg==").then().statusCode(204)

        // another member reads it: the opaque blob verbatim, ETag = revision, never a shared cache
        read(t, t.owner, t.member).then().statusCode(200)
            .body("sealedAvatar", equalTo("c2VhbGVkLWF2YXRhcg=="))
            .header("ETag", "\"1\"").header("Cache-Control", "private")
        // and the profile read shows the revision the directory will carry
        given().header("Authorization", bearer(t.member)).header("X-Forwarded-Host", t.domain)
            .`when`().get("/api/account/profile").then().statusCode(200).body("avatarRevision", equalTo(1))
    }

    @Test
    fun anOverwriteBumpsTheRevisionAndReplacesTheBlob() {
        val t = world("av-over")
        put(t, t.member, "Zmlyc3Q=").then().statusCode(204)
        put(t, t.member, "c2Vjb25k").then().statusCode(204)
        read(t, t.owner, t.member).then().statusCode(200).body("sealedAvatar", equalTo("c2Vjb25k")).header("ETag", "\"2\"")
    }

    @Test
    fun aMatchingIfNoneMatchIs304WithNoBodyAndAStaleOneIs200() {
        val t = world("av-304")
        put(t, t.member, "Zmlyc3Q=").then().statusCode(204)
        read(t, t.owner, t.member, ifNoneMatch = "\"1\"").then().statusCode(304).header("ETag", "\"1\"")
            .body(equalTo(""))
        read(t, t.owner, t.member, ifNoneMatch = "W/\"1\"").then().statusCode(304)
        put(t, t.member, "c2Vjb25k").then().statusCode(204)
        // the cached revision is stale now, an unparsable one is no match: both a plain 200
        read(t, t.owner, t.member, ifNoneMatch = "\"1\"").then().statusCode(200).header("ETag", "\"2\"")
        read(t, t.owner, t.member, ifNoneMatch = "garbage").then().statusCode(200)
    }

    @Test
    fun clearRemovesThePictureBumpsAndIsIdempotent() {
        val t = world("av-clr")
        put(t, t.member, "Zmlyc3Q=").then().statusCode(204)
        delete(t, t.member).then().statusCode(204)
        read(t, t.owner, t.member).then().statusCode(404).body("code", equalTo("avatar_not_found"))
        val afterClear = stored(t.tenantId, t.member)
        assertNull(afterClear.sealedAvatar)
        assertEquals(2, afterClear.avatarRevision, "a clear bumps like a set")
        given().header("Authorization", bearer(t.member)).header("X-Forwarded-Host", t.domain)
            .`when`().get("/api/account/profile").then().statusCode(200).body("avatarRevision", equalTo(0))
        // the directory reads 0 again too (not the counter a clear left behind)
        given().header("Authorization", bearer(t.owner))
            .`when`().get("/api/scopes/${t.tenantId}/members").then().statusCode(200)
            .body("find { it.userId == '${t.member}' }.avatarRevision", equalTo(0))
        // idempotent: clearing again (or clearing what was never set) is a 204
        delete(t, t.member).then().statusCode(204)
        delete(t, t.owner).then().statusCode(204)
        // a set after a clear never reuses a revision a client may hold
        put(t, t.member, "bmV3").then().statusCode(204)
        read(t, t.owner, t.member).then().statusCode(200).header("ETag", "\"4\"")
    }

    @Test
    fun aMemberWithNoPictureIs404AvatarNotFound() {
        val t = world("av-none")
        read(t, t.owner, t.member).then().statusCode(404).body("code", equalTo("avatar_not_found"))
        read(t, t.owner, UUID.randomUUID().toString()).then().statusCode(404).body("code", equalTo("avatar_not_found"))
    }

    @Test
    fun aNonMemberOfTheTenantGetsTheMembershipNotFoundWhetherOrNotAPictureExists() {
        val t = world("av-gate")
        put(t, t.member, "Zmlyc3Q=").then().statusCode(204)
        val stranger = seedUser(email = "stranger@av-gate.example.com")
        // the picture exists, yet a stranger learns exactly what an invented tenant answers (ADR 0042)
        read(t, stranger, t.member).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        given().header("Authorization", bearer(stranger))
            .`when`().get("/api/tenants/${UUID.randomUUID()}/members/${t.member}/avatar")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
        given().`when`().get("/api/tenants/${t.tenantId}/members/${t.member}/avatar").then().statusCode(401)
    }

    @Test
    fun aMembersPictureOfOneTenantIsNotReadableThroughAnother() {
        val a = world("av-xa")
        val b = world("av-xb")
        put(a, a.member, "Zmlyc3Q=").then().statusCode(204)
        // b's owner is no member of a, and a's member has no picture in b
        read(a, b.owner, a.member).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        read(b, b.owner, a.member).then().statusCode(404).body("code", equalTo("avatar_not_found"))
    }

    @Test
    fun theDirectoryCarriesTheRevisionAndNeverTheBytes() {
        val t = world("av-dir")
        val marker = "ZGlyZWN0b3J5LW1hcmtlcg=="
        put(t, t.member, marker).then().statusCode(204)
        val body = given().header("Authorization", bearer(t.owner))
            .`when`().get("/api/scopes/${t.tenantId}/members")
            .then().statusCode(200)
            .body("find { it.userId == '${t.member}' }.avatarRevision", equalTo(1))
            .body("find { it.userId == '${t.owner}' }.avatarRevision", equalTo(0))
            .body(not(containsString("sealedAvatar")))
            .body(not(containsString(marker)))
            .extract().asString()
        assertFalse(body.contains(marker), "the picture's bytes never ride the directory")
    }

    @Test
    fun removingAMemberClearsThePictureAndKeepsTheHandle() {
        val t = world("av-rm")
        put(t, t.member, "Zmlyc3Q=").then().statusCode(204)
        val before = stored(t.tenantId, t.member).avatarRevision

        given().header("Authorization", bearer(t.owner))
            .`when`().delete("/api/tenants/${t.tenantId}/members/${t.member}").then().statusCode(204)

        val after = stored(t.tenantId, t.member)
        assertNull(after.sealedAvatar, "the picture goes with the access")
        assertTrue(after.avatarRevision > before, "and the revision moves so a cached copy is revalidated")
        assertEquals("av-rm-member", after.username, "the handle outlives the removal (old Memories keep a name)")
        read(t, t.owner, t.member).then().statusCode(404).body("code", equalTo("avatar_not_found"))
    }

    @Test
    fun aRemovalOfAMemberWithNoPictureLeavesNothingToServe() {
        val t = world("av-rm0")
        given().header("Authorization", bearer(t.owner))
            .`when`().delete("/api/tenants/${t.tenantId}/members/${t.member}").then().statusCode(204)
        assertNull(stored(t.tenantId, t.member).sealedAvatar)
    }

    @Test
    fun overlappingSetsEachBumpTheRevisionOnce() {
        val t = world("av-race")
        val n = 8
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        try {
            (1..n).map { pool.submit { put(t, t.member, "QUJDRA==").then().statusCode(204) } }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(n, stored(t.tenantId, t.member).avatarRevision, "the counter moves in the database: no lost bump")
    }

    @Test
    fun theLengthBoundIsEnforcedAtTheWire() {
        val t = world("av-len")
        put(t, t.member, full).then().statusCode(204)
        put(t, t.member, full + "AAAA").then().statusCode(400).body("code", equalTo("invalid_request"))
        put(t, t.member, "").then().statusCode(400).body("code", equalTo("invalid_request"))
        put(t, t.member, "not base64!!").then().statusCode(400).body("code", equalTo("invalid_request"))
        given().header("Authorization", bearer(t.member)).header("X-Forwarded-Host", t.domain)
            .contentType(ContentType.JSON).body("{}").`when`().put("/api/account/profile/avatar").then().statusCode(400)
        // the refused bodies changed nothing
        assertEquals(1, stored(t.tenantId, t.member).avatarRevision)
    }

    @Test
    fun aCallerWithNoProfileInTheHostTenantIs404AndAnUnknownHostToo() {
        val t = world("av-np")
        val nobody = seedUser(email = "nobody@av-np.example.com")
        put(t, nobody, "Zmlyc3Q=").then().statusCode(404).body("code", equalTo("profile_not_found"))
        delete(t, nobody).then().statusCode(404).body("code", equalTo("profile_not_found"))
        given().header("Authorization", bearer(t.member)).header("X-Forwarded-Host", "no-such-tenant.example.com")
            .contentType(ContentType.JSON).body("""{"sealedAvatar":"Zmlyc3Q=","epoch":0}""")
            .`when`().put("/api/account/profile/avatar").then().statusCode(404).body("code", equalTo("profile_not_found"))
        given().contentType(ContentType.JSON).body("""{"sealedAvatar":"Zmlyc3Q=","epoch":0}""")
            .`when`().put("/api/account/profile/avatar").then().statusCode(401)
    }

    @Test
    fun theOwnerJoinSealsThePictureAtAcceptWithRevisionOne() {
        val secret = "av-acc-secret"
        val id = pendingTenant("av-acc.example.com", "owner@av-acc.example.com", secret)
        val userId = seedUser(email = "owner@av-acc.example.com")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"avowner",${TestGrants.ownerBirthJson()},"sealedAvatar":"c2VhbGVkLWF2YXRhcg=="}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
        val p = stored(id, userId)
        assertEquals("c2VhbGVkLWF2YXRhcg==", p.sealedAvatar)
        assertEquals(1, p.avatarRevision)
        read(Tenant(id, "av-acc.example.com", userId, userId), userId, userId).then().statusCode(200).header("ETag", "\"1\"")

        // an accept without one leaves none
        val secret2 = "av-acc-secret2"
        val id2 = pendingTenant("av-acc2.example.com", "owner2@av-acc.example.com", secret2)
        val user2 = seedUser(email = "owner2@av-acc.example.com")
        given().header("Authorization", bearer(user2)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret2","username":"avowner2",${TestGrants.ownerBirthJson()}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
        assertNull(stored(id2, user2).sealedAvatar)
        assertEquals(0, stored(id2, user2).avatarRevision)
    }

    @Test
    fun registerAndAcceptSealsThePictureOnBothPaths() {
        val ownerSecret = "av-reg-o"
        val id = pendingTenant("av-reg.example.com", "owner@av-reg.example.com", ownerSecret)
        val ownerId = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON).body(registerBody(ownerSecret, ownerId, "regowner", owner = true, avatar = "c2VhbGVkLWF2YXRhcg=="))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        assertEquals("c2VhbGVkLWF2YXRhcg==", stored(id, ownerId).sealedAvatar)
        assertEquals(1, stored(id, ownerId).avatarRevision)

        // a MEMBER invitee holds K_tenant at accept too — it opened its invite's grant (ADR 0111 decision 3) — so a
        // picture on its body is stored like the owner's
        val key = TestInviteKey()
        QuarkusTransaction.requiringNew().run {
            store.mintMemberInvite(id, "m@av-reg.example.com", Tokens.hash("av-reg-m"), Instant.now().plus(7, ChronoUnit.DAYS), key.stored(ownerId))
        }
        val memberId = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON).body(registerBody("av-reg-m", memberId, "regmember", owner = false, avatar = "c2VhbGVkLWF2YXRhcg==", key = key))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        assertEquals("c2VhbGVkLWF2YXRhcg==", stored(id, memberId).sealedAvatar)
        assertEquals(1, stored(id, memberId).avatarRevision)
    }

    @Test
    fun anOversizeOrMalformedAvatarOnAnAcceptBodyIs400AndSpendsNothing() {
        val secret = "av-bad-secret"
        pendingTenant("av-bad.example.com", "owner@av-bad.example.com", secret)
        val userId = seedUser(email = "owner@av-bad.example.com")
        for (bad in listOf(full + "AAAA", "not base64!!")) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"bootstrapSecret":"$secret","username":"badowner",${TestGrants.ownerBirthJson()},"sealedAvatar":"$bad"}""")
                .`when`().post("/api/tenants/accept").then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        // the invite survived the refusals: a clean accept still works
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"badowner",${TestGrants.ownerBirthJson()}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
    }

    // --- helpers ---

    private data class Tenant(val tenantId: String, val domain: String, val owner: String, val member: String)

    /** An active tenant with an owner and one accepted, granted member, both with profiles. */
    private fun world(prefix: String): Tenant {
        val domain = "$prefix.example.com"
        val owner = seedUser(email = "owner@$domain")
        val tenantId = seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = owner)
        val member = seedUser(email = "member@$domain")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = "bWVtYmVyLWdyYW50"; createdAt = Instant.now()
            }.persist()
            profiles.create(owner, tenantId, "$prefix-owner")
            profiles.create(member, tenantId, "$prefix-member")
        }
        return Tenant(tenantId, domain, owner, member)
    }

    private fun put(t: Tenant, caller: String, sealed: String) =
        given().header("Authorization", bearer(caller)).header("X-Forwarded-Host", t.domain)
            .contentType(ContentType.JSON).body("""{"sealedAvatar":"$sealed","epoch":0}""")
            .`when`().put("/api/account/profile/avatar")

    private fun delete(t: Tenant, caller: String) =
        given().header("Authorization", bearer(caller)).header("X-Forwarded-Host", t.domain)
            .`when`().delete("/api/account/profile/avatar")

    private fun read(t: Tenant, caller: String, member: String, ifNoneMatch: String? = null) =
        given().header("Authorization", bearer(caller)).apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .`when`().get("/api/tenants/${t.tenantId}/members/$member/avatar")

    private fun stored(tenantId: String, userId: String): TenantProfileEntity =
        QuarkusTransaction.requiringNew().call<TenantProfileEntity> {
            val p = TenantProfileEntity.find("tenantId = ?1 and userId = ?2", tenantId, userId).firstResult()
            assertNotNull(p, "profile row of $userId")
            p!!
        }

    private fun pendingTenant(domain: String, ownerEmail: String, secret: String): String =
        QuarkusTransaction.requiringNew().call {
            store.createPendingTenant("Fam", domain, ownerEmail, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS)).first
        }

    private fun registerBody(secret: String, userId: String, username: String, owner: Boolean, avatar: String, key: TestInviteKey? = null): String {
        val grant = if (owner) """${TestGrants.ownerBirthJson("aWRlbnRpdHktcHVibGljLWtleQ==")},"""
        else key?.let { "${TestGrants.memberJoinJson(it, it.inviteId, userId, "aWRlbnRpdHktcHVibGljLWtleQ==")}," } ?: ""
        return """
            {"bootstrapSecret":"$secret","userId":"$userId","username":"$username",$grant"sealedAvatar":"$avatar",
             "account":{"suiteId":"v1",
               "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
               "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
               "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
               "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
               "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
        """.trimIndent()
    }

    private companion object {
        /** The exact base64 length of the sealed 16 KiB pad - ADR 0084, the contract's `maxLength`. */
        const val AVATAR_LEN = 21908
    }
}
