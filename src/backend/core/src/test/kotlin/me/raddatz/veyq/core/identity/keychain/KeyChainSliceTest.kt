package me.raddatz.veyq.core.identity.keychain

import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.crypto.KeyChainStatements
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.WrapData
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
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
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * The key chain's server half (ADR 0111 decision 7, cr-secure-the-account): step 0 written at birth, the
 * read and its gate, and the head every directory read serves beside another user's key.
 *
 * The gate is shared tenancy — a membership of each on one tenant root scope — and everyone else gets exactly the `404`
 * an unknown id gets (ADR 0042), so the read enumerates nobody. The control for each refusal is the same read by a
 * co-member.
 */
@QuarkusTest
class KeyChainSliceTest {

    @Inject lateinit var accounts: AccountStore
    @Inject lateinit var tenants: TenantStore

    @Test
    fun theBirthWritesStepZero() {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            accounts.createAccount(
                email = "born-$userId@example.com", serverRole = ServerRoles.MEMBER, suiteId = "v1",
                identityPublicKey = TestFixtures.IDENTITY_PUBLIC_KEY, wrappedIdentityPrivateKey = "cHJpdg==",
                authPublicKey = "YXV0aA==", secretKeyProofPublicKey = PROOF, wraps = listOf(WrapData("PASSWORD", "d3JhcA==", "a2Rm")),
                userId = userId,
            )
        }
        val stored = QuarkusTransaction.requiringNew().call { KeyChainStepEntity.find("userId", userId).list() }
        assertEquals(1, stored.size, "one step, written with the account")
        val step = stored.single()
        assertEquals(0, step.idx)
        assertEquals(TestFixtures.IDENTITY_PUBLIC_KEY, step.identityPublicKey)
        assertEquals(PROOF, step.secretKeyProofPublicKey)
        assertEquals(null, step.identitySignature, "step 0 is unsigned")
        val genesis = KeyChainStatements.genesis(userId, TestFixtures.IDENTITY_PUBLIC_KEY, PROOF)!!
        assertEquals(b64(KeyChainStatements.head(genesis)), step.head)
    }

    @Test
    fun aCoMemberReadsTheChainAndAStrangerGetsTheUnknownUsers404() {
        val person = seedUser(secretKeyProofPublicKey = PROOF)
        val coMember = seedUser()
        val stranger = seedUser()
        val tenant = seedTenant(owner = person)
        joinRoot(coMember, tenant)
        // A stranger who shares only a GROUP-less other tenant with nobody here.
        seedTenant(owner = stranger)

        val head = b64(KeyChainStatements.head(KeyChainStatements.genesis(person, TestFixtures.IDENTITY_PUBLIC_KEY, PROOF)!!))
        read(coMember, person).then().statusCode(200)
            .body("userId", equalTo(person)).body("head", equalTo(head))
            .body("steps[0].index", equalTo(0)).body("steps[0].previousHead", nullValue())
            .body("steps[0].identityPublicKey", equalTo(TestFixtures.IDENTITY_PUBLIC_KEY))
            .body("steps[0].secretKeyProofPublicKey", equalTo(PROOF))
        read(person, person).then().statusCode(200) // the person themselves

        val strangers = read(stranger, person).then().statusCode(404).extract().asString()
        val unknown = read(stranger, UUID.randomUUID().toString()).then().statusCode(404).extract().asString()
        assertEquals(unknown, strangers, "a person sharing no tenant reads exactly as an unknown id")
        read(stranger, person).then().body("code", equalTo("not_found"))
    }

    @Test
    fun aMembershipOfAnotherScopeOfTheTenantIsNotSharedTenancy() {
        // A group of the tenant is no tenant root: only the root membership is belonging.
        val person = seedUser()
        val tenant = seedTenant(owner = person)
        val outsider = seedUser()
        val group = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply { id = group; tenantId = tenant; kind = ScopeEntity.GROUP; encryptedName = "bmFtZQ==" }.persist()
        }
        membership(outsider, tenant, group)
        read(outsider, person).then().statusCode(404)
        joinRoot(outsider, tenant)
        read(outsider, person).then().statusCode(200) // control
    }

    @Test
    fun theHeadRidesEveryDirectoryReadAndPendingMarksABehindGrant() {
        val owner = seedUser(secretKeyProofPublicKey = PROOF)
        val member = seedUser(secretKeyProofPublicKey = PROOF)
        val tenant = seedTenant(owner = owner)
        joinRoot(member, tenant)
        val ownerHead = headOf(member, owner)
        val memberHead = headOf(owner, member)

        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$tenant/members").then().statusCode(200)
            .body("find { it.userId == '$member' }.keyChainHead", equalTo(memberHead))
            .body("find { it.userId == '$member' }.pending", equalTo(false))
            .body("find { it.userId == '$owner' }.keyChainHead", equalTo(ownerHead))

        // A rotation leaves the member behind: the grant behind the current epoch IS the pending marker.
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.update("currentEpoch = 1 where id = ?1", tenant)
            MembershipEntity.update("grantedEpoch = 1 where userId = ?1 and scopeId = ?2", owner, tenant)
        }
        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$tenant/members").then().statusCode(200)
            .body("find { it.userId == '$member' }.pending", equalTo(true))
            .body("find { it.userId == '$owner' }.pending", equalTo(false))

        // The grant delivery's target carries it too.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"],"scopeId":"$tenant"}""")
            .`when`().post("/api/tasks/claim").then().statusCode(200)
            .body("grantDelivery.targetUserId", equalTo(member))
            .body("grantDelivery.keyChainHead", equalTo(memberHead))

        // An open invite's signer, in the list every member reads and in the invitee's preview.
        val key = TestInviteKey()
        val secret = "kc-${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.update("currentEpoch = 0 where id = ?1", tenant)
            tenants.mintMemberInvite(tenant, "inv-$secret@example.com", Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS), key.stored(owner))
        }
        given().header("Authorization", bearer(member)).`when`().get("/api/scopes/$tenant/open-invites").then().statusCode(200)
            .body("find { it.inviteId == '${key.inviteId}' }.signerKeyChainHead", equalTo(ownerHead))
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview").then().statusCode(200)
            // The preview serves the signer's whole chain (ADR 0111 decision 19), not its head: an
            // owner who never rotated has step 0, unsigned, installing the key they hold.
            .body("invite.signerKeyChain.size()", equalTo(1))
            .body("invite.signerKeyChain[0].index", equalTo(0))
            .body("invite.signerKeyChain[0].identityPublicKey", equalTo(TestFixtures.IDENTITY_PUBLIC_KEY))
            .body("invite.signerKeyChain[0].identitySignature", nullValue())
            .body("invite.granterKeyChainHead", equalTo(ownerHead))
    }

    @Test
    fun aGroupCandidateCarriesItsHead() {
        val owner = seedUser()
        val candidate = seedUser(secretKeyProofPublicKey = PROOF)
        val tenant = seedTenant(owner = owner)
        joinRoot(candidate, tenant)
        val username = "cand${UUID.randomUUID().toString().take(8)}"
        QuarkusTransaction.requiringNew().run {
            me.raddatz.veyq.core.identity.TenantProfileEntity().apply {
                id = UUID.randomUUID().toString(); userId = candidate; tenantId = tenant; this.username = username
            }.persist()
        }
        val group = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply { id = group; tenantId = tenant; kind = ScopeEntity.GROUP; encryptedName = "bmFtZQ==" }.persist()
        }
        membership(owner, tenant, group, MembershipEntity.OWNER)

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body("""{"username":"$username"}""")
            .`when`().post("/api/scopes/$group/members/resolve").then().statusCode(200)
            .body("userId", equalTo(candidate))
            .body("keyChainHead", equalTo(headOf(owner, candidate)))
    }

    private fun read(caller: String, person: String) =
        given().header("Authorization", bearer(caller)).`when`().get("/api/people/$person/key-chain")

    private fun headOf(caller: String, person: String): String = read(caller, person).then().statusCode(200).extract().path("head")

    private fun joinRoot(user: String, tenant: String) = membership(user, tenant, tenant)

    private fun membership(user: String, tenant: String, scope: String, role: String = MembershipEntity.MEMBER) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = user; tenantId = tenant; scopeId = scope; this.role = role
                wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
            }.persist()
        }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private companion object {
        /** A raw 32-byte Ed25519-sized proof public key, base64. */
        const val PROOF = "cHJvb2YtcHVibGljLWtleS1wcm9vZi1wdWJsaWMta2U="
    }
}
