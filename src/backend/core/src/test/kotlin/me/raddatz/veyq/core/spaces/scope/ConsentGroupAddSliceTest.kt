package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
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
 * Slice tests for the consent opt-in for group-add (uc.spaces.consent-group-add, cr-0096, ADR 0027). A GroupOwner
 * adding a consent-on member records a soft `pendingConsent` membership carrying the wrapped group name-key `K_name`
 * and — since ADR 0111 decision 4 — the grant the owner wrapped to the target's key; the group is not listed to the
 * target until they **accept** (keys unchanged) or **decline** (the row goes and the group's rotation is queued: the
 * target held the key). The invitation surfaces the wrapped name-key + the scope's canonical encryptedName it opens.
 * Operator-blind: only opaque ciphertext + a boolean + a flag are stored. Seeds use UUID-unique
 * domains/emails so the JVM-lived H2 mem DB (shared across test classes) can't bleed state.
 */
@QuarkusTest
class ConsentGroupAddSliceTest {

    @Inject
    lateinit var tasks: TasksStore

    @Inject
    lateinit var scopes: ScopesStore

    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ=" // "group-scope-grant"
    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU=" // "sealed-group-name" — the scope's canonical encryptedName
    private val sealedNameKey = "c2VhbGVkLW5hbWUta2V5" // "sealed-name-key" — asymWrap(inviteePub, K_name, "group-name-key")

    @Test
    fun consentOnAddRecordsASoftRowHoldingTheGrant() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)

        // The owner resolves the candidate → learns consent is required + gets the pubkey to seal the name to.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members/resolve")
            .then().statusCode(200)
            .body("userId", equalTo(partner))
            .body("requiresConsent", equalTo(true))
            .body("identityPublicKey", notNullValue())

        // The owner adds WITH the wrapped name-key → a soft, pending proposal that holds the owner's grant (ADR 0111
        // decision 4): the key crosses at the add, the consent gates only whether the group is listed to them.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey",${TestGrants.groupAddJson(groupId, partner)}}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201)
            .body("role", equalTo("MEMBER"))
            .body("pendingConsent", equalTo(true))
            .body("hasGrant", equalTo(null))
        QuarkusTransaction.requiringNew().run {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", partner, groupId).firstResult()!!
            assertEquals(TestGrants.WRAPPED, m.wrappedGrant, "the proposal holds the owner's grant")
            assertEquals(0, m.grantedEpoch)
            assertEquals(owner, m.granterUserId, "the proposing owner is its granter")
        }

        // Current, so nothing to deliver: a grant-delivery job is for a membership BEHIND the epoch only.
        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(0, created, "a proposal at the current epoch is no delivery target")
        assertEquals(0, deliveryJobsFor(partner, groupId), "no grant-delivery job targets the soft member")
    }

    @Test
    fun consentRequiredWithoutSealIs400() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)

        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner",${TestGrants.groupAddJson(groupId, partner)}}""") // no sealedNameKey
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(400).body("code", equalTo("consent_required"))
    }

    @Test
    fun acceptClearsTheInvitationAndKeepsTheGrant() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId, partner)

        // The invitee sees exactly one pending invitation carrying the wrapped name-key `K_name` + the scope's
        // canonical encryptedName it opens (cr-0096) + who added them.
        given().header("Authorization", bearer(partner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes/invitations")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].scopeId", equalTo(groupId))
            .body("[0].sealedNameKey", equalTo(sealedNameKey))
            .body("[0].encryptedName", equalTo(groupName))
            .body("[0].addedBy", equalTo("owner"))

        // Accept → 204; the invitation is gone, and the membership is an ordinary one holding the grant the proposal
        // already held — consenting changes nothing about keys (ADR 0111 decision 4), so nothing is delivered.
        given().header("Authorization", bearer(partner))
            .`when`().post("/api/scopes/$groupId/invitations/accept").then().statusCode(204)
        given().header("Authorization", bearer(partner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes/invitations").then().statusCode(200).body("size()", equalTo(0))
        QuarkusTransaction.requiringNew().run {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", partner, groupId).firstResult()!!
            assertEquals(false, m.pendingConsent)
            assertEquals(TestGrants.WRAPPED, m.wrappedGrant, "the accept keeps the proposal's grant")
            assertEquals(owner, m.granterUserId)
        }

        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(0, created, "an accepted member at the current epoch is no delivery target")

        // Re-accept is an idempotent no-op 204 (the flag is already clear).
        given().header("Authorization", bearer(partner))
            .`when`().post("/api/scopes/$groupId/invitations/accept").then().statusCode(204)
    }

    @Test
    fun declineDropsTheSoftRowAndQueuesTheGroupsRotation() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId, partner)

        given().header("Authorization", bearer(partner))
            .`when`().post("/api/scopes/$groupId/invitations/decline").then().statusCode(204)

        // The soft membership is gone; it held the group's key, so the group's rotation is queued (ADR 0111 decision 4)
        // — queued, not run: the epoch moves when a member's device completes it.
        QuarkusTransaction.requiringNew().run {
            assertEquals(0, MembershipEntity.count("userId = ?1 and scopeId = ?2", partner, groupId), "soft row deleted")
            assertEquals(1, JobEntity.count("scopeId = ?1 and capabilityTag = ?2", groupId, CapabilityTags.SCOPE_ROTATION), "rotation queued")
            assertEquals(0, ScopeEntity.findById(groupId)!!.currentEpoch, "the decline itself rotates nothing")
        }
        // Re-invite after a decline is allowed (no `declined` marker kept) — a fresh proposal succeeds.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey",${TestGrants.groupAddJson(groupId, partner)}}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)
    }

    @Test
    fun acceptWithNoProposalIs404() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val stranger = seedTenantMember(tenantIdOf(domain), "stranger", consent = true)

        given().header("Authorization", bearer(stranger))
            .`when`().post("/api/scopes/$groupId/invitations/accept")
            .then().statusCode(404).body("code", equalTo("invitation_not_found"))
        // Decline with no proposal is still a silent 204 (no enumeration oracle).
        given().header("Authorization", bearer(stranger))
            .`when`().post("/api/scopes/$groupId/invitations/decline").then().statusCode(204)
    }

    @Test
    fun pendingProposalIsOwnerVisibleOnly() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId, partner)
        val grantedMember = seedGroupGrantHolder(tenantIdOf(domain), groupId, "member2")

        // The group owner sees the pending proposal in the directory.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }.pendingConsent", equalTo(true))

        // An ordinary (non-owner) key-holder does NOT see the pending proposal at all.
        given().header("Authorization", bearer(grantedMember))
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }", equalTo(null))
    }

    @Test
    fun aRotationReGrantsAPendingProposal() {
        // Inverted by ADR 0111 decision 4 (cr-0095 gated the key before the wrap; the proposal now holds the grant from
        // the add on): a rotation that lists a soft `pendingConsent` proposal in its `grants` re-grants it like any
        // member — a proposal a rotation skipped would accept into a key the group already left.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = true)
        addSoft(owner, domain, groupId, partner)

        // The owner rotates and — as a full-directory re-grant would — lists both themselves and the pending partner.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$groupGrant","grants":[{"userId":"$owner","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"},{"userId":"$partner","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(partner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))

        // The proposal now holds the new epoch, granted by the rotator, and is still pending.
        QuarkusTransaction.requiringNew().run {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", partner, groupId).firstResult()!!
            assertEquals(1, m.grantedEpoch, "the rotation re-granted the pendingConsent proposal")
            assertEquals(owner, m.granterUserId)
            assertEquals(true, m.pendingConsent, "the proposal stays pending after an unrelated rotation")
        }
        // Both re-granted, so nobody is behind.
        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(0, created, "nobody is behind after a rotation that re-granted every member")
        assertEquals(0, deliveryJobsFor(partner, groupId), "no grant-delivery job targets the re-granted proposal")
    }

    @Test
    fun consentOffIsUnchangedDirectAdd() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner", consent = false)

        // Resolve reports consent not required; the add (no seal) is the ordinary direct-add, with the owner's grant.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members/resolve")
            .then().statusCode(200).body("requiresConsent", equalTo(false))
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner",${TestGrants.groupAddJson(groupId, partner)}}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201).body("pendingConsent", equalTo(false))
        assertEquals(0, QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) },
            "a direct-add member holds the current epoch: nothing to deliver")

        // A rotation that declares them pending leaves them behind — the one case grant delivery still serves.
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$groupGrant","grants":[{"userId":"$owner","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],"pendingUserIds":["$partner"]}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))
        val created = QuarkusTransaction.requiringNew().call { tasks.reconcileGrantDelivery(setOf(groupId), 5) }
        assertEquals(1, created, "a member behind the epoch is a delivery target")
        assertEquals(1, deliveryJobsFor(partner, groupId), "the behind member's delivery job")
    }

    @Test
    fun accountConsentToggleRoundTrips() {
        // The account-global preference is get/set through the caller's own profile surface (cr-0094).
        val (_, domain) = seedMember()
        val member = seedTenantMember(tenantIdOf(domain), "member", consent = false)

        given().header("Authorization", bearer(member)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(false))

        given().header("Authorization", bearer(member)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON).body("""{"requireGroupAddConsent":true}""")
            .`when`().put("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(true)).body("username", equalTo("member"))

        given().header("Authorization", bearer(member)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/account/profile")
            .then().statusCode(200).body("requireGroupAddConsent", equalTo(true))
    }

    @Test
    fun addGroupMemberUnauthenticatedIs401() {
        given().contentType(ContentType.JSON).body("""{"username":"x","sealedNameKey":"$sealedNameKey",${TestGrants.grantJson()}}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/members")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun aRoleChangeOverlappingAnAcceptKeepsTheAccept() {
        // The role change reads the membership before the invitee's accept commits and flushes after it. A full-row
        // UPDATE wrote `pendingConsent = true` back: the accepted member was a proposal again, and grant delivery
        // skips proposals, so they never got the key (inbox #224).
        raceRounds { round ->
            val (owner, scopeId, tenantId) = seedMemberScope(ScopeEntity.GROUP)
            val invitee = seedUser()
            QuarkusTransaction.requiringNew().run {
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); userId = invitee; this.tenantId = tenantId; this.scopeId = scopeId
                    role = MembershipEntity.MEMBER; pendingConsent = true; sealedNameKey = "bmFtZS1rZXk="; addedBy = owner
                    createdAt = Instant.now()
                }.persist()
            }
            whileUncommitted(
                change = { assertEquals(1, scopes.clearPendingConsent(invitee, scopeId), "round $round") },
                overlapping = { scopes.setRole(invitee, scopeId, MembershipEntity.OWNER) },
            )
            QuarkusTransaction.requiringNew().run {
                val row = MembershipEntity.find("userId = ?1 and scopeId = ?2", invitee, scopeId).firstResult()!!
                assertEquals(false, row.pendingConsent, "round $round: the role change undid the accept")
                assertNull(row.sealedNameKey, "round $round")
                // Control: the role change itself landed.
                assertEquals(MembershipEntity.OWNER, row.role, "round $round")
            }
        }
    }

    // --- helpers ---

    private fun tenantIdOf(domain: String) = domain.removePrefix("t-").removeSuffix(".example.com")

    /** Count grant-delivery jobs targeting a user's membership in a scope (resourceRef = membershipId). */
    private fun deliveryJobsFor(userId: String, scopeId: String): Long =
        QuarkusTransaction.requiringNew().call {
            val membershipId = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.id
                ?: return@call 0L
            JobEntity.count("resourceRef = ?1 and capabilityTag = ?2", membershipId, CapabilityTags.GRANT_DELIVERY)
        }

    private fun createGroupAs(userId: String, domain: String): String =
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).extract().path("scopeId")

    /** The owner proposes the consent-on "partner" (a soft pending add). */
    private fun addSoft(owner: String, domain: String, groupId: String, partner: String) {
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner","sealedNameKey":"$sealedNameKey",${TestGrants.groupAddJson(groupId, partner)}}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)
    }

    /** Seed a tenant member with a per-tenant username profile + an account-global consent preference. */
    private fun seedTenantMember(tenantId: String, username: String, consent: Boolean): String {
        val userId = seedUser(requireGroupAddConsent = consent)
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
        val userId = seedUser(requireGroupAddConsent = false)
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                this.domain = domain
                ownerEmail = "owner-$tenantId@example.com"
                status = TenantEntity.ACTIVE
            }.persist()
            ScopeEntity().apply {
                id = tenantId
                this.tenantId = tenantId
                kind = ScopeEntity.TENANT
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
            }.persist()
        }
        return userId to domain
    }
}
