package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.GROUP_SPACE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.ADD_CONSENT
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_MAX_DAYS
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_PASSWORD
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.PASSWORD_MAX_DAYS
import me.raddatz.veyq.core.sharing.GuestPaths
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.MemberScope
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The requirements on capability entries (uc.admin.capability-requirements, ADR 0088 decision 8) over the full HTTP
 * stack on the default profile's production stores: what each refuses that is new, what it does to what exists, both
 * levels' API, and the strictest of the two in effect.
 *
 * **Every negative has a positive control on the same predicate**: the same request that meets the requirement, the
 * same link in another tenant, or the same link once fixed or once the requirement is cleared. A requirement that
 * refused everything, or every tenant, would pass each negative alone and fail its control.
 */
@QuarkusTest
class CapabilityRequirementsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @AfterEach
    fun clearEverything() {
        CapabilityRequirementFixtures.clear()
    }

    // --- links: a password -------------------------------------------------------------------------------------------

    @Test
    fun aRequiredPasswordRefusesANewLinkWithoutOneAndAcceptsOneWithIt() {
        val w = linkWorld()
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_PASSWORD to 1)) {
            createShare(w).then().statusCode(422).body("code", equalTo("link_password_required"))
            createShare(w, password = true).then().statusCode(201) // control: the same link with a password
        }
    }

    @Test
    fun aRequiredPasswordStopsAnExistingLinkWithoutOneAtOnceAndAnEditThatGivesOneServesItAgain() {
        val w = linkWorld()
        val withPassword = linkWorld(passwordLink = true)
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_PASSWORD to 1)) {
            assertEquals(emptyList<String>(), GuestPaths.refusedLikeAnUnknownLink(w.link, resolve, "a link without a password"))
            // Control: a link that has one is served (its locked challenge), so the gate refuses by the rule alone.
            assertEquals(emptyList<String>(), GuestPaths.served(withPassword.link, resolve, "a link with a password"))
            // Removing a password is refused; giving one fixes the link without renewing it.
            update(w, """{"passwordRequired":false}""").then().statusCode(422).body("code", equalTo("link_password_required"))
            update(w, """{"passwordRequired":true,"pwSalt":"$PW_SALT","pwVerifier":"$PW_VERIFIER","sealedPassword":"$SEALED_PW"}""")
                .then().statusCode(204)
            assertEquals(emptyList<String>(), GuestPaths.served(w.link, resolve, "the link once it has a password"))
        }
    }

    // --- links: a maximum lifetime, per tenant ----------------------------------------------------------------------

    @Test
    fun aTenantsMaximumLifetimeRefusesALinkBeyondItInThatTenantOnly() {
        val a = linkWorld()
        val b = linkWorld()
        CapabilityRequirementFixtures.with(tenantId = a.space.tenantId, tenant = mapOf(LINK_MAX_DAYS to 7)) {
            createShare(a).then().statusCode(422).body("code", equalTo("link_expiry_required")) // no end date at all
            createShare(a, expiresIn = Duration.ofDays(30)).then().statusCode(422).body("code", equalTo("link_expiry_required"))
            createShare(a, expiresIn = Duration.ofDays(3)).then().statusCode(201) // control: within it
            createShare(b, expiresIn = Duration.ofDays(30)).then().statusCode(201) // control: another tenant
            // What exists: a's link has no end date and stops; b's is served.
            assertEquals(emptyList<String>(), GuestPaths.refusedLikeAnUnknownLink(a.link, resolve, "tenant A's link, no end date"))
            assertEquals(emptyList<String>(), GuestPaths.served(b.link, resolve, "tenant B's link"))
            // An edit that gives it an end date within 7 days of when it was made serves it again.
            update(a, """{"expiresAt":"${Instant.now().plus(Duration.ofDays(2))}"}""").then().statusCode(204)
            assertEquals(emptyList<String>(), GuestPaths.served(a.link, resolve, "tenant A's link, fixed"))
        }
    }

    @Test
    fun clearingARequirementServesTheLinkItStoppedAgain() {
        val w = linkWorld()
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_MAX_DAYS to 7)) {
            assertEquals(emptyList<String>(), GuestPaths.refusedLikeAnUnknownLink(w.link, resolve, "no end date, rule set"))
        }
        assertEquals(emptyList<String>(), GuestPaths.served(w.link, resolve, "no end date, rule cleared"))
    }

    // --- groups: consent ---------------------------------------------------------------------------------------------

    @Test
    fun aTenantsConsentRequirementOverridesAMembersNoAndLeavesAnotherTenantDirect() {
        val a = groupWorld()
        val b = groupWorld()
        CapabilityRequirementFixtures.with(tenantId = a.tenantId, tenant = mapOf(ADD_CONSENT to 1)) {
            resolveMember(a).then().statusCode(200).body("requiresConsent", equalTo(true))
            addMember(a).then().statusCode(400).body("code", equalTo("consent_required"))
            // Controls: the same member's own "no" holds in another tenant, where the add is direct.
            resolveMember(b).then().statusCode(200).body("requiresConsent", equalTo(false))
            addMember(b).then().statusCode(201).body("pendingConsent", equalTo(false))
        }
    }

    // --- sessions: the password again after N days -----------------------------------------------------------------

    @Test
    fun aSessionOlderThanTheLimitIsNotRenewedAndAYoungOneIs() {
        val user = seedUser()
        val old = QuarkusTransaction.requiringNew().call { sessions.issue(user) }
        val young = QuarkusTransaction.requiringNew().call { sessions.issue(user) }
        QuarkusTransaction.requiringNew().run {
            SessionFamilyEntity.findById(old.familyId)!!.createdAt = Instant.now().minus(Duration.ofDays(10))
        }
        CapabilityRequirementFixtures.with(instance = mapOf(PASSWORD_MAX_DAYS to 7)) {
            refresh(old.refreshToken).then().statusCode(401)
            refresh(young.refreshToken).then().statusCode(200) // control: a family inside the limit
            // Refused, not revoked: the family is still active.
            assertEquals(SessionFamilyEntity.ACTIVE, QuarkusTransaction.requiringNew().call { SessionFamilyEntity.findById(old.familyId)!!.status })
        }
        refresh(old.refreshToken).then().statusCode(200) // control: the same token once the rule is cleared
    }

    // --- the register: both levels, the strictest in effect, on the wire -------------------------------------------

    @Test
    fun theServerOwnerSetsAndClearsARequirementAndEveryClientReadsItWithItsEntry() {
        ensureUser(OWNER_ID, "requirements-owner@example.com", "SERVER_OWNER")
        owner().contentType(ContentType.JSON).body("""{"value":14}""")
            .`when`().put("/api/instance/capabilities/$PUBLIC_SHARES/requirements/$LINK_MAX_DAYS")
            .then().statusCode(200).body("requirements.find { it.name == '$LINK_MAX_DAYS' }.value", equalTo(14))
            .body("requirements.find { it.name == '$LINK_MAX_DAYS' }.kind", equalTo("days"))
        given().`when`().get("/api/setup").then().statusCode(200)
            .body("capabilities.find { it.id == '$PUBLIC_SHARES' }.linkMaxDays", equalTo(14))
            .body("capabilities.find { it.id == '$GROUP_SPACE' }.linkMaxDays", nullValue()) // only on its own entry
        owner().`when`().delete("/api/instance/capabilities/$PUBLIC_SHARES/requirements/$LINK_MAX_DAYS")
            .then().statusCode(200).body("requirements.find { it.name == '$LINK_MAX_DAYS' }.value", nullValue())
        given().`when`().get("/api/setup").then().statusCode(200)
            .body("capabilities.find { it.id == '$PUBLIC_SHARES' }.linkMaxDays", nullValue())
        // Refusals: a requirement of another entry, a flag set to anything but 1, a day count out of range.
        owner().contentType(ContentType.JSON).body("""{"value":1}""")
            .`when`().put("/api/instance/capabilities/$GROUP_SPACE/requirements/$LINK_PASSWORD")
            .then().statusCode(404).body("code", equalTo("requirement_not_found"))
        owner().contentType(ContentType.JSON).body("""{"value":2}""")
            .`when`().put("/api/instance/capabilities/$PUBLIC_SHARES/requirements/$LINK_PASSWORD")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        owner().contentType(ContentType.JSON).body("""{"value":0}""")
            .`when`().put("/api/instance/capabilities/$PUBLIC_SHARES/requirements/$LINK_MAX_DAYS")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { CapabilityRequirementEntity.count() })
    }

    @Test
    fun aTenantTightensButALooserValueIsNotInEffectAndAnInstanceOnlyRequirementIsRefused() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenantId) }
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_MAX_DAYS to 30)) {
            tenantPut(owner, tenantId, PUBLIC_SHARES, LINK_MAX_DAYS, 60).then().statusCode(200)
                .body("requirements.find { it.name == '$LINK_MAX_DAYS' }.value", equalTo(60))
                .body("requirements.find { it.name == '$LINK_MAX_DAYS' }.serverValue", equalTo(30))
                .body("requirements.find { it.name == '$LINK_MAX_DAYS' }.effective", equalTo(30))
            tenantPut(owner, tenantId, PUBLIC_SHARES, LINK_PASSWORD, 1).then().statusCode(200)
            given().header("Authorization", bearer(member)).`when`().get("/api/tenants/$tenantId/capabilities")
                .then().statusCode(200)
                .body("capabilities.find { it.id == '$PUBLIC_SHARES' }.linkMaxDays", equalTo(30))
                .body("capabilities.find { it.id == '$PUBLIC_SHARES' }.linkPassword", equalTo(true))
            tenantPut(owner, tenantId, REMEMBER_DEVICE, PASSWORD_MAX_DAYS, 7).then().statusCode(404)
                .body("code", equalTo("requirement_not_found"))
            tenantPut(member, tenantId, PUBLIC_SHARES, LINK_PASSWORD, 1).then().statusCode(403).body("code", equalTo("not_tenant_owner"))
            given().header("Authorization", bearer(owner))
                .`when`().delete("/api/tenants/$tenantId/capability-switches/$PUBLIC_SHARES/requirements/$LINK_PASSWORD")
                .then().statusCode(200).body("requirements.find { it.name == '$LINK_PASSWORD' }.effective", nullValue())
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private val resolve = listOf(Path("resolveShare") { id -> given().`when`().get("/api/shares/$id") })

    private data class LinkWorld(val space: MemberScope, val folder: String, val link: String)

    private fun linkWorld(passwordLink: Boolean = false): LinkWorld {
        val space = seedMemberScope()
        val folder = seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(space.userId, space.scopeId, folder, ShareEntity.READ).shareId
        if (passwordLink) {
            QuarkusTransaction.requiringNew().run {
                ShareEntity.findById(link)!!.apply { passwordRequired = true; pwSalt = PW_SALT; pwVerifier = PW_VERIFIER }
            }
        }
        return LinkWorld(space, folder, link)
    }

    private fun createShare(w: LinkWorld, password: Boolean = false, expiresIn: Duration? = null): Response {
        val pw = if (password) ""","passwordRequired":true,"pwSalt":"$PW_SALT","pwVerifier":"$PW_VERIFIER","sealedPassword":"$SEALED_PW"""" else ""
        val expiry = expiresIn?.let { ""","expiresAt":"${Instant.now().plus(it)}"""" } ?: ""
        return given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${w.folder}","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}$pw$expiry}""")
            .`when`().post("/api/shares")
    }

    private fun update(w: LinkWorld, body: String): Response =
        given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/shares/${w.link}")

    private data class GroupWorld(val owner: String, val domain: String, val tenantId: String, val groupId: String, val partner: String)

    /** A tenant with a group its owner created and a member "partner" whose own setting says no consent needed. */
    private fun groupWorld(): GroupWorld {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val domain = "t-$tenantId.example.com"
        val groupId = given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${me.raddatz.veyq.core.TestIds.uuid()}","encryptedName":"c2VhbGVk","wrappedGrant":"Z3JhbnQ=",${me.raddatz.veyq.core.membership.TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${me.raddatz.veyq.core.membership.TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups").then().statusCode(201).extract().path<String>("scopeId")
        val partner = seedUser(requireGroupAddConsent = false)
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(partner, tenantId)
            me.raddatz.veyq.core.identity.TenantProfileEntity().apply {
                id = java.util.UUID.randomUUID().toString(); userId = partner; this.tenantId = tenantId; username = "partner"
            }.persist()
        }
        return GroupWorld(owner, domain, tenantId, groupId, partner)
    }

    private fun resolveMember(g: GroupWorld): Response =
        given().header("Authorization", bearer(g.owner)).header("X-Forwarded-Host", g.domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""").`when`().post("/api/scopes/${g.groupId}/members/resolve")

    private fun addMember(g: GroupWorld): Response =
        given().header("Authorization", bearer(g.owner)).header("X-Forwarded-Host", g.domain).contentType(ContentType.JSON)
            .body("""{"username":"partner",${me.raddatz.veyq.core.membership.TestGrants.groupAddJson(g.groupId, g.partner)}}""")
            .`when`().post("/api/scopes/${g.groupId}/members")

    private fun refresh(token: String): Response =
        given().contentType(ContentType.JSON).body("""{"refreshToken":"$token"}""").`when`().post("/api/token/refresh")

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun tenantPut(caller: String, tenantId: String, capabilityId: String, name: String, value: Int): Response =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body("""{"value":$value}""")
            .`when`().put("/api/tenants/$tenantId/capability-switches/$capabilityId/requirements/$name")

    companion object {
        private const val OWNER_ID = "6d1f3a52-3b0e-4c41-9c35-7f3a0e2c9b11"
        private const val PW_SALT = "cHctc2FsdA=="
        private const val PW_VERIFIER = "cHctdmVyaWZpZXI="
        private const val SEALED_PW = "c2VhbGVkLXB3"
    }
}
