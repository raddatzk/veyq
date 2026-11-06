package me.raddatz.veyq.core.appshell

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.CSP
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.SERVED_POLICY
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.SHARE_EMBEDDING
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.ShareEmbeddingHosts
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `connect-src https:` exactly while `core.share-embedding` is effective (ADR 0121 decision 2): for the tenant whose
 * domain serves the page, and for a host that claims no tenant at the instance level — on, off by the Server Owner, off by
 * the tenant owner, and after the tenant's domain moved. Every switch goes through its real endpoint, so the snapshot's
 * refresh after the handler's commit is what is proved, not a fixture's. Each case compares the whole policy, so nothing
 * but the one source moves: the policy with it is [SERVED_POLICY], the one without it [WITHOUT].
 */
@QuarkusTest
class AppShellShareEmbeddingSliceTest {

    @Inject
    lateinit var hosts: ShareEmbeddingHosts

    @AfterEach
    fun switchEverythingBackOn() {
        CapabilitySwitchFixtures.clear()
        TenantCapabilitySwitchFixtures.clear()
        hosts.refresh()
    }

    @Test
    fun onByDefaultOnATenantsDomainAndOnAHostThatClaimsNone() {
        val tenant = tenant()
        assertEquals(SERVED_POLICY, policyOn(tenant.domain), "a tenant's domain")
        assertEquals(SERVED_POLICY, policyOn(null), "a host that claims no tenant")
        assertEquals(SERVED_POLICY, policyOn("unknown-${UUID.randomUUID()}.example.com"), "an unknown host")
        assertNull(given().header("X-Forwarded-Host", tenant.domain).`when`().get("/api/setup").header(CSP), "the API carries no policy")
    }

    @Test
    fun offByTheServerOwnerTakesItFromEveryHost() {
        val tenant = tenant()
        instanceSwitch(off = true)
        assertEquals(WITHOUT, policyOn(tenant.domain), "a tenant's domain")
        assertEquals(WITHOUT, policyOn(null), "a host that claims no tenant")
        // The tenant owner cannot widen what the Server Owner narrowed: its own switch is on and stays without effect.
        tenantSwitch(tenant, off = false)
        assertEquals(WITHOUT, policyOn(tenant.domain), "a tenant whose own switch is on")
        instanceSwitch(off = false)
        assertEquals(SERVED_POLICY, policyOn(tenant.domain), "control: back on")
    }

    @Test
    fun offByATenantOwnerTakesItFromThatTenantsDomainAlone() {
        val tenant = tenant()
        val other = tenant()
        tenantSwitch(tenant, off = true)
        assertEquals(WITHOUT, policyOn(tenant.domain), "the tenant that switched it off")
        assertEquals(SERVED_POLICY, policyOn(other.domain), "another tenant")
        assertEquals(SERVED_POLICY, policyOn(null), "a host that claims no tenant: the instance level")
        tenantSwitch(tenant, off = false)
        assertEquals(SERVED_POLICY, policyOn(tenant.domain), "control: back on")
    }

    @Test
    fun aTenantThatSwitchedItOffKeepsItOffOnTheDomainItMovedTo() {
        val tenant = tenant()
        tenantSwitch(tenant, off = true)
        val moved = "moved-${UUID.randomUUID()}.example.com"
        givenAdminChannel().header("Authorization", bearer(seedUser(serverRole = "SERVER_OWNER"))).contentType(ContentType.JSON)
            .body("""{"domain":"$moved"}""").`when`().post("/api/tenants/${tenant.id}/change-domain").then().statusCode(204)
        assertEquals(WITHOUT, policyOn(moved), "the tenant's new domain")
        assertEquals(SERVED_POLICY, policyOn(tenant.domain), "its old domain claims no tenant now: the instance level")
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private data class Tenant(val id: String, val domain: String, val owner: String)

    private fun tenant(): Tenant {
        val owner = seedUser()
        val id = UUID.randomUUID().toString()
        val domain = "embed-$id.example.com"
        seedTenant(id = id, domain = domain, owner = owner)
        hosts.refresh() // what `TenantHandler.create` does through `reproject`; seeded rows bypass it
        return Tenant(id, domain, owner)
    }

    private fun policyOn(host: String?): String {
        val spec = given().header("Accept", HTML)
        if (host != null) spec.header("X-Forwarded-Host", host)
        return spec.`when`().get("/recover").then().statusCode(200).extract().header(CSP)
    }

    private fun instanceSwitch(off: Boolean) {
        givenAdminChannel().header("Authorization", bearer(seedUser(serverRole = "SERVER_OWNER"))).contentType(ContentType.JSON)
            .body("""{"off":$off}""").`when`().put("/api/instance/capabilities/$SHARE_EMBEDDING").then().statusCode(200)
    }

    private fun tenantSwitch(tenant: Tenant, off: Boolean) {
        given().header("Authorization", bearer(tenant.owner)).contentType(ContentType.JSON).body("""{"off":$off}""")
            .`when`().put("/api/tenants/${tenant.id}/capability-switches/$SHARE_EMBEDDING").then().statusCode(200)
    }

    private companion object {
        /** [SERVED_POLICY] without `https:` — the policy as before ADR 0121. */
        val WITHOUT: String = SERVED_POLICY.replace(" https:;", ";")
    }
}
