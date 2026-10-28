package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.GROUP_SPACE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchEntity
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.GuestPaths
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.MemberScope
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The tenant level of the capability register (uc.admin.tenant-switch-capabilities, ADR 0088 decisions 2, 3, 5 and
 * 12), over the full HTTP stack on the default profile's production switches.
 *
 * **Every negative has a control on the same predicate, in another tenant**: a tenant's switch must refuse that
 * tenant's content and reach no other tenant's, so the same call against a second tenant is answered past the gate.
 * A switch that reached every tenant (an instance switch in disguise) would pass every negative alone and fail every
 * control.
 */
@QuarkusTest
class TenantCapabilitySwitchesSliceTest {

    @AfterEach
    fun switchEverythingBackOn() {
        TenantCapabilitySwitchFixtures.clear()
        CapabilitySwitchFixtures.clear()
    }

    // --- the gates, per tenant ----------------------------------------------------------------------------------------

    @Test
    fun aTenantShareSwitchStopsThatTenantsExistingLinksAtOnceAndNoOtherTenants() {
        val a = linkWorld()
        val b = linkWorld()
        val paths = listOf(Path("resolveShare") { id -> given().`when`().get("/api/shares/$id") })
        assertTrue(GuestPaths.served(a.link, paths, "tenant A, nothing off").isEmpty())
        val (refusedA, servedB) = TenantCapabilitySwitchFixtures.with(a.space.tenantId, PUBLIC_SHARES) {
            GuestPaths.refusedLikeAnUnknownLink(a.link, paths, "tenant A's link, public links off in A") to
                GuestPaths.served(b.link, paths, "tenant B's link, public links off in A")
        }
        assertEquals(emptyList<String>(), refusedA)
        assertEquals(emptyList<String>(), servedB)
        // Nothing was written onto the link: switched back on, it serves again.
        assertTrue(GuestPaths.served(a.link, paths, "tenant A, back on").isEmpty())
    }

    @Test
    fun aTenantShareSwitchRefusesANewLinkInThatTenantAndNotInAnother() {
        val a = linkWorld()
        val b = linkWorld()
        TenantCapabilitySwitchFixtures.with(a.space.tenantId, PUBLIC_SHARES_CONTRIBUTE) {
            createShare(a, "READ_WRITE").then().statusCode(422).body("code", equalTo("capability_disabled"))
            // Controls: READ needs only the parent, on in A; and READ_WRITE in B is past the gate.
            createShare(a, "READ").then().statusCode(201)
            createShare(b, "READ_WRITE").then().statusCode(201)
        }
    }

    @Test
    fun aTenantGroupSwitchRefusesANewGroupInThatTenantAndNotInAnother() {
        val (ownerA, domainA, tenantA) = groupWorld()
        val (ownerB, domainB, _) = groupWorld()
        TenantCapabilitySwitchFixtures.with(tenantA, GROUP_SPACE) {
            createGroup(ownerA, domainA).then().statusCode(422).body("code", equalTo("capability_disabled"))
            createGroup(ownerB, domainB).then().statusCode(201)
        }
        createGroup(ownerA, domainA).then().statusCode(201) // control: the same call with the switch back on
    }

    // --- the effective set and the owner's register -----------------------------------------------------------------

    @Test
    fun theOwnerSwitchesAnEntryOffAndEveryMemberReadsTheNarrowedSet() {
        val t = ownedTenant()
        given().header("Authorization", bearer(t.member)).`when`().get("/api/tenants/${t.id}/capabilities")
            .then().statusCode(200).body("capabilities.id", equalTo(CapabilityIds.ADVERTISED))

        setSwitch(t.owner, t.id, PUBLIC_SHARES, true).then().statusCode(200)
            .body("id", equalTo(PUBLIC_SHARES)).body("off", equalTo(true))
            .body("serverOff", equalTo(false)).body("effective", equalTo(false))
            .body("title.de", equalTo("Öffentliche Links"))

        given().header("Authorization", bearer(t.member)).`when`().get("/api/tenants/${t.id}/capabilities")
            .then().statusCode(200)
            .body("capabilities.id", not(hasItem(PUBLIC_SHARES)))
            .body("capabilities.id", not(hasItem(PUBLIC_SHARES_DROP_BOX))) // the AND along the path
            .body("capabilities.id", hasItems(GROUP_SPACE, REMEMBER_DEVICE))
        // The pre-auth manifest is the instance level alone: the tenant's switch is not in it.
        given().`when`().get("/api/setup").then().statusCode(200).body("capabilities.id", hasItem(PUBLIC_SHARES))

        val register = given().header("Authorization", bearer(t.owner)).`when`().get("/api/tenants/${t.id}/capability-switches")
            .then().statusCode(200).extract().jsonPath()
        assertEquals(CapabilityIds.ADVERTISED.filter { it in CapabilityIds.TENANT_SWITCHABLE }, register.getList<String>("id"))
        val ids = register.getList<String>("id")
        // Only the switched entry is off; it and its two children are not effective, every other entry is.
        assertEquals(ids.map { it == PUBLIC_SHARES }, register.getList<Boolean>("off"))
        assertEquals(ids.map { !it.startsWith(PUBLIC_SHARES) }, register.getList<Boolean>("effective"))

        setSwitch(t.owner, t.id, PUBLIC_SHARES, false).then().statusCode(200).body("effective", equalTo(true))
        setSwitch(t.owner, t.id, PUBLIC_SHARES, false).then().statusCode(200) // the state it has: no change, same answer
        assertEquals(0L, QuarkusTransaction.requiringNew().call { TenantCapabilitySwitchEntity.count() })
    }

    @Test
    fun theServerOwnersSwitchWinsAndTheTenantsOwnSwitchSurvivesIt() {
        val t = ownedTenant()
        CapabilitySwitchFixtures.with(GROUP_SPACE) {
            setSwitch(t.owner, t.id, GROUP_SPACE, false).then().statusCode(200)
                .body("off", equalTo(false)).body("serverOff", equalTo(true)).body("effective", equalTo(false))
            given().header("Authorization", bearer(t.member)).`when`().get("/api/tenants/${t.id}/capabilities")
                .then().statusCode(200).body("capabilities.id", not(hasItem(GROUP_SPACE)))
        }
        given().header("Authorization", bearer(t.member)).`when`().get("/api/tenants/${t.id}/capabilities")
            .then().statusCode(200).body("capabilities.id", hasItem(GROUP_SPACE)) // control: the instance back on
    }

    @Test
    fun anInstanceOnlyOrUnknownEntryIsRefusedAndNothingIsStored() {
        val t = ownedTenant()
        setSwitch(t.owner, t.id, REMEMBER_DEVICE, true).then().statusCode(404).body("code", equalTo("capability_not_found"))
        setSwitch(t.owner, t.id, "core.no-such-entry", true).then().statusCode(404).body("code", equalTo("capability_not_found"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { TenantCapabilitySwitchEntity.count() })
        setSwitch(t.owner, t.id, GROUP_SPACE, false).then().statusCode(200) // control: a tenant entry is accepted
    }

    @Test
    fun aMemberReadsTheSetButNotTheSwitchesAndAnOutsiderNeither() {
        val t = ownedTenant()
        val outsider = seedUser()
        given().header("Authorization", bearer(t.member)).`when`().get("/api/tenants/${t.id}/capability-switches")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        setSwitch(t.member, t.id, PUBLIC_SHARES, true).then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", bearer(outsider)).`when`().get("/api/tenants/${t.id}/capabilities")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
        setSwitch(outsider, t.id, PUBLIC_SHARES, true).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { TenantCapabilitySwitchEntity.count() })
    }

    @Test
    fun aMissingOffIsRefused() {
        val t = ownedTenant()
        given().header("Authorization", bearer(t.owner)).contentType(ContentType.JSON).body("{}")
            .`when`().put("/api/tenants/${t.id}/capability-switches/$PUBLIC_SHARES")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private data class LinkWorld(val space: MemberScope, val folder: String, val link: String)

    /** A fresh tenant with a member's space, a folder in it and an existing READ link on the folder. */
    private fun linkWorld(): LinkWorld {
        val space = seedMemberScope()
        val folder = seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return LinkWorld(space, folder, GuestTreeShare.seed(space.userId, space.scopeId, folder, ShareEntity.READ).shareId)
    }

    private fun createShare(w: LinkWorld, role: String): Response =
        given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${w.folder}","role":"$role","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")

    private data class GroupWorld(val owner: String, val domain: String, val tenantId: String)

    private fun groupWorld(): GroupWorld {
        val owner = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        seedTenant(id = tenantId, domain = domain, owner = owner)
        return GroupWorld(owner, domain, tenantId)
    }

    private fun createGroup(owner: String, domain: String): Response =
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"c2VhbGVk","wrappedGrant":"Z3JhbnQ=",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups")

    private data class OwnedTenant(val id: String, val owner: String, val member: String)

    private fun ownedTenant(): OwnedTenant {
        val owner = seedUser()
        val id = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, id) }
        return OwnedTenant(id, owner, member)
    }

    private fun setSwitch(caller: String, tenantId: String, id: String, off: Boolean): Response =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body("""{"off":$off}""")
            .`when`().put("/api/tenants/$tenantId/capability-switches/$id")
}
