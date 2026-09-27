package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.TestIds
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.features.tasks.CapabilityTags
import com.veyq.app.features.tasks.JobEntity
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.shared.identity.TenantProfileEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the consent opt-in for group-add (uc.spaces.consent-group-add, cr-0096, ADR 0027). The
 * gate is **consent-first, never wrap-then-soft**: a GroupOwner adding a consent-on member records a soft
 * `pendingConsent` membership that grant-delivery **skips** (so `K_SC` is gated **before** the wrap) carrying
 * the wrapped group name-key `K_name`; the invitee **accepts** (→ ordinary grant-delivery) or **declines**
 * (silent drop, no rotation). The invitation surfaces the wrapped name-key + the scope's canonical encryptedName
 * it opens. Operator-blind: only opaque ciphertext + a boolean + a flag are stored. Seeds use UUID-unique
 * domains/emails so the JVM-lived H2 mem DB (shared across test classes) can't bleed state.
 */
@QuarkusTest
class ConsentGroupAddSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var tasks: TasksStore

    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ=" // "group-scope-grant"
    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU=" // "sealed-group-name" — the scope's canonical encryptedName
    private val sealedNameKey = "c2VhbGVkLW5hbWUta2V5" // "sealed-name-key" — asymWrap(inviteePub, K_name, "group-name-key")

    @Test
    fun consentOnAddRecordsSoftRowAndGrantDeliverySkipsIt() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)

        // The owner resolves the candidate → learns consent is required + gets the pubkey to seal the name to.
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members/resolve")
            .then().statusCode(200)
            .body("userId", equalTo(partner))
            .body("requiresConsent", equalTo(true))
            .body("identityPublicKey", notNullValue())

        // The owner adds WITH the wrapped name-key → a soft, pending, grant-less proposal.
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey"}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201)
            .body("role", equalTo("MEMBER"))
            .body("hasGrant", equalTo(false))
            .body("pendingConsent", equalTo(true))

        // grant-delivery must NOT originate a job for the soft member — K_SC is gated before the wrap.
        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(0, created, "no grant-delivery job for a pendingConsent proposal")
        assertEquals(0, deliveryJobsFor(partner, groupId), "no grant-delivery job targets the soft member")
    }

    @Test
    fun consentRequiredWithoutSealIs400() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        seedTenantMember(tenantIdOf(domain), "partner", consent = true)

        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""") // no sealedNameKey
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(400).body("code", equalTo("consent_required"))
    }

    @Test
    fun acceptFlipsToDeliverableAndClearsTheInvitation() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId)

        // The invitee sees exactly one pending invitation carrying the wrapped name-key `K_name` + the scope's
        // canonical encryptedName it opens (cr-0096) + who added them.
        given().header("Authorization", "Bearer ${token(partner)}").header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes/invitations")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].scopeId", equalTo(groupId))
            .body("[0].sealedNameKey", equalTo(sealedNameKey))
            .body("[0].encryptedName", equalTo(groupName))
            .body("[0].addedBy", equalTo("owner"))

        // Accept → 204; the invitation is gone and grant-delivery now originates a job (grant-less, not pending).
        given().header("Authorization", "Bearer ${token(partner)}")
            .`when`().post("/api/scopes/$groupId/invitations/accept").then().statusCode(204)
        given().header("Authorization", "Bearer ${token(partner)}").header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes/invitations").then().statusCode(200).body("size()", equalTo(0))

        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(1, created, "accept makes the member a delivery target")
        assertEquals(1, deliveryJobsFor(partner, groupId), "a grant-delivery job now targets the accepted member")

        // Re-accept is an idempotent no-op 204 (the flag is already clear).
        given().header("Authorization", "Bearer ${token(partner)}")
            .`when`().post("/api/scopes/$groupId/invitations/accept").then().statusCode(204)
    }

    @Test
    fun declineDropsTheSoftRowWithNoTraceAndNoRotation() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId)

        given().header("Authorization", "Bearer ${token(partner)}")
            .`when`().post("/api/scopes/$groupId/invitations/decline").then().statusCode(204)

        // The soft membership is gone (nothing was wrapped) and the scope did NOT rotate (epoch untouched).
        QuarkusTransaction.requiringNew().run {
            assertEquals(0, MembershipEntity.count("userId = ?1 and scopeId = ?2", partner, groupId), "soft row deleted")
            assertEquals(0, ScopeEntity.findById(groupId)!!.currentEpoch, "decline never rotates")
        }
        // Re-invite after a decline is allowed (no `declined` marker kept) — a fresh proposal succeeds.
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey"}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)
    }

    @Test
    fun acceptWithNoProposalIs404() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val stranger = seedTenantMember(tenantIdOf(domain), "stranger", consent = true)

        given().header("Authorization", "Bearer ${token(stranger)}")
            .`when`().post("/api/scopes/$groupId/invitations/accept")
            .then().statusCode(404).body("code", equalTo("invitation_not_found"))
        // Decline with no proposal is still a silent 204 (no enumeration oracle).
        given().header("Authorization", "Bearer ${token(stranger)}")
            .`when`().post("/api/scopes/$groupId/invitations/decline").then().statusCode(204)
    }

    @Test
    fun pendingProposalIsOwnerVisibleOnly() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId)
        val grantedMember = seedGroupGrantHolder(tenantIdOf(domain), groupId, "member2")

        // The group owner sees the pending proposal in the directory.
        given().header("Authorization", "Bearer ${token(owner)}")
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }.pendingConsent", equalTo(true))

        // An ordinary (non-owner) key-holder does NOT see the pending proposal at all.
        given().header("Authorization", "Bearer ${token(grantedMember)}")
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }", equalTo(null))
    }

    @Test
    fun rotationNeverGrantsAPendingProposal() {
        // Regression (cr-0095): the consent gate must hold on the ROTATION path too, not just grant-delivery. A
        // scope-key rotation that lists a soft `pendingConsent` proposal in its `grants` (the honest client re-grants
        // the whole owner-visible directory, which includes pending rows) must NOT wrap `K_SC` onto it — else an
        // ordinary member-removal rotation silently delivers the key to someone who never accepted.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId)

        // The owner rotates and — as a full-directory re-grant would — lists both themselves and the pending partner.
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$groupGrant","grants":[{"userId":"$owner","wrappedGrant":"$groupGrant"},{"userId":"$partner","wrappedGrant":"$groupGrant"}]}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))

        // The soft membership stayed grant-less + pending — `K_SC` was never wrapped to it by the rotation.
        QuarkusTransaction.requiringNew().run {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", partner, groupId).firstResult()!!
            assertNull(m.wrappedGrant, "rotation must not wrap K_SC onto a pendingConsent proposal")
            assertEquals(true, m.pendingConsent, "the proposal stays pending after an unrelated rotation")
        }
        // And it is still gated for grant-delivery (skipped) — the owner re-granted themselves, so nobody is behind.
        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(0, created, "a still-pending proposal is not a delivery target after a rotation")
        assertEquals(0, deliveryJobsFor(partner, groupId), "no grant-delivery job targets the still-soft member")
    }

    @Test
    fun consentOffIsUnchangedDirectAdd() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = false)

        // Resolve reports consent not required; the add (no seal) is the ordinary grant-less direct-add.
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members/resolve")
            .then().statusCode(200).body("requiresConsent", equalTo(false))
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201).body("hasGrant", equalTo(false)).body("pendingConsent", equalTo(false))

        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(1, created, "a direct-add member is an ordinary delivery target")
        assertEquals(1, deliveryJobsFor(partner, groupId), "direct-add originates a grant-delivery job")
    }

    @Test
    fun accountConsentToggleRoundTrips() {
        // The account-global preference is get/set through the caller's own profile surface (cr-0094).
        val (_, domain) = seedMember()
        val member = seedTenantMember(tenantIdOf(domain), "member", consent = false)

        given().header("Authorization", "Bearer ${token(member)}").header("X-Forwarded-Host", domain)
            .`when`().get("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(false))

        given().header("Authorization", "Bearer ${token(member)}").header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON).body("""{"requireGroupAddConsent":true}""")
            .`when`().put("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(true)).body("username", equalTo("member"))

        given().header("Authorization", "Bearer ${token(member)}").header("X-Forwarded-Host", domain)
            .`when`().get("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(true))
    }

    @Test
    fun addGroupMemberUnauthenticatedIs401() {
        given().contentType(ContentType.JSON).body("""{"username":"x","sealedNameKey":"$sealedNameKey"}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/members")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun token(userId: String) = sessions.issue(userId).accessToken

    private fun tenantIdOf(domain: String) = domain.removePrefix("t-").removeSuffix(".example.com")

    /** Count grant-delivery jobs targeting a user's membership in a scope (resourceRef = membershipId). */
    private fun deliveryJobsFor(userId: String, scopeId: String): Long =
        QuarkusTransaction.requiringNew().call {
            val membershipId = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.id
                ?: return@call 0L
            JobEntity.count("resourceRef = ?1 and capabilityTag = ?2", membershipId, CapabilityTags.GRANT_DELIVERY)
        }

    private fun createGroupAs(userId: String, domain: String): String =
        given().header("Authorization", "Bearer ${token(userId)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).extract().path("scopeId")

    /** The owner proposes the consent-on "partner" (a soft pending add). */
    private fun addSoft(owner: String, domain: String, groupId: String) {
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey"}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)
    }

    /** Seed a tenant member with a per-tenant username profile + an account-global consent preference. */
    private fun seedTenantMember(tenantId: String, username: String, consent: Boolean): String {
        val userId = seedUser(consent)
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant"
                createdAt = Instant.now()
            }.persist()
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                this.username = username
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    /** Seed a user who already holds a GROUP grant (role=MEMBER) — a non-owner key-holder for directory-visibility tests. */
    private fun seedGroupGrantHolder(tenantId: String, groupId: String, username: String): String {
        val userId = seedTenantMember(tenantId, username, consent = false)
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = groupId
                role = MembershipEntity.MEMBER
                wrappedGrant = "Z3JvdXAtbWVtYmVyLWdyYW50" // "group-member-grant"
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    /** Seed a user + an ACTIVE tenant + the owner's TENANT membership + the owner's per-tenant profile ("owner"). */
    private fun seedMember(): Pair<String, String> {
        val userId = seedUser(consent = false)
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                this.domain = domain
                ownerEmail = "owner-$tenantId@example.com"
                status = TenantEntity.ACTIVE
                createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId
                this.tenantId = tenantId
                kind = ScopeEntity.TENANT
                createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.OWNER
                wrappedGrant = "dGVuYW50LWdyYW50" // "tenant-grant"
                createdAt = Instant.now()
            }.persist()
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                username = "owner"
                createdAt = Instant.now()
            }.persist()
        }
        return userId to domain
    }

    private fun seedUser(consent: Boolean): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId
                email = "u-$userId@example.com"
                serverRole = "MEMBER"
                suiteId = "v1"
                identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                requireGroupAddConsent = consent
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
