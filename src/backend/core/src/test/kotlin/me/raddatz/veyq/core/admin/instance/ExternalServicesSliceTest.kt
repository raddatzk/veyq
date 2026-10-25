package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.BREACHED_PASSWORD
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.EXPENSES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.EXPENSES_EXCHANGE_RATES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.MEMORIES_MAP
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.MEMORIES_PLACES
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceEntity
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.StoredExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The external services as Server-Owner settings and register entries (uc.admin.configure-external-services, ADR 0088
 * decision 7, ADR 0089), over the full HTTP stack: the address on the wire, the entry's activity, and the policy the
 * shell serves. Every "gone" has a control: another service's address and origin stay, or the same one comes back.
 */
@QuarkusTest
class ExternalServicesSliceTest {

    @Inject
    lateinit var addresses: StoredExternalServiceAddresses

    @Inject
    lateinit var origins: ExternalServiceOrigins

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "services-owner@example.com", "SERVER_OWNER")
        restoreDefaults()
    }

    @AfterEach
    fun restore() {
        TenantCapabilitySwitchFixtures.clear()
        CapabilitySwitchFixtures.clear()
        restoreDefaults()
    }

    @Test
    fun aNewInstanceStartsWithTodaysDefaultsAndServesThem() {
        val listed = owner().`when`().get(PATH).then().statusCode(200).extract().jsonPath()
        assertEquals(listOf(BREACHED_PASSWORD, MEMORIES_PLACES, MEMORIES_MAP, EXPENSES_EXCHANGE_RATES), listed.getList<String>("id"))
        assertEquals(listOf(HIBP, NOMINATIM, TILES, RATES), listed.getList<String>("url"))
        assertEquals(listOf(false, false, true, false), listed.getList<Boolean>("takesAttribution"))
        setup().body("capabilities.find { it.id == '$MEMORIES_MAP' }.serviceUrl", equalTo(TILES))
            .body("capabilities.find { it.id == '$MEMORIES_MAP' }.serviceAttribution", equalTo(OSM_ATTRIBUTION))
            .body("capabilities.find { it.id == '$BREACHED_PASSWORD' }.serviceUrl", equalTo(HIBP))
            .body("capabilities.find { it.id == '$EXPENSES_EXCHANGE_RATES' }.serviceUrl", equalTo(RATES))
        val csp = csp()
        assertTrue("https://tile.openstreetmap.org" in directive(csp, "img-src"), csp)
        assertTrue("https://nominatim.openstreetmap.org" in directive(csp, "connect-src"), csp)
        assertTrue("https://api.pwnedpasswords.com" in directive(csp, "connect-src"), csp)
        assertTrue(FRANKFURTER in directive(csp, "connect-src"), csp)
    }

    /**
     * The exchange rates' origin (ADR 0108 decision 8) is in `connect-src` exactly while the address is set AND the entry
     * is on at the instance level — its own switch and the Expenses switch above it alike. Each "gone" beside its
     * control: both set, it is there; and another service stays.
     */
    @Test
    fun theExchangeRatesOriginIsInThePolicyOnlyWhileAddressedAndOn() {
        assertTrue(FRANKFURTER in directive(csp(), "connect-src"), "control: addressed and on")

        owner().`when`().delete("$PATH/$EXPENSES_EXCHANGE_RATES").then().statusCode(200).body("url", nullValue())
        assertFalse(FRANKFURTER in directive(csp(), "connect-src"), "no address: no origin")
        assertTrue("https://api.pwnedpasswords.com" in directive(csp(), "connect-src"), "control: another service stays")
        setup().body("capabilities.id", not(hasItem(EXPENSES_EXCHANGE_RATES)))
        put(EXPENSES_EXCHANGE_RATES, """{"url":"$RATES"}""").statusCode(200)
        assertTrue(FRANKFURTER in directive(csp(), "connect-src"), "addressed again: back")

        for (off in listOf(EXPENSES_EXCHANGE_RATES, EXPENSES)) {
            owner().contentType(ContentType.JSON).body("""{"off":true}""")
                .`when`().put("/api/instance/capabilities/$off").then().statusCode(200)
            assertFalse(FRANKFURTER in directive(csp(), "connect-src"), "$off off: no origin")
            setup().body("capabilities.id", not(hasItem(EXPENSES_EXCHANGE_RATES)))
            owner().contentType(ContentType.JSON).body("""{"off":false}""")
                .`when`().put("/api/instance/capabilities/$off").then().statusCode(200)
            assertTrue(FRANKFURTER in directive(csp(), "connect-src"), "$off on again: back")
        }

        // A mirror's origin replaces Frankfurter's, exactly.
        put(EXPENSES_EXCHANGE_RATES, """{"url":"https://rates.example.org:8443/v2/rates?date={date}"}""").statusCode(200)
        val connect = directive(csp(), "connect-src")
        assertTrue("https://rates.example.org:8443" in connect && FRANKFURTER !in connect, connect.toString())
    }

    @Test
    fun aTenantsExchangeRatesSwitchLeavesItsSetAndNotThePolicy() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenantId) }
        given().header("Authorization", bearer(member)).`when`().get("/api/tenants/$tenantId/capabilities")
            .then().statusCode(200).body("capabilities.find { it.id == '$EXPENSES_EXCHANGE_RATES' }.serviceUrl", equalTo(RATES))
        TenantCapabilitySwitchFixtures.with(tenantId, EXPENSES_EXCHANGE_RATES) {
            given().header("Authorization", bearer(member)).`when`().get("/api/tenants/$tenantId/capabilities")
                .then().statusCode(200).body("capabilities.id", not(hasItem(EXPENSES_EXCHANGE_RATES)))
            assertTrue(FRANKFURTER in directive(csp(), "connect-src"), "the policy is the instance's")
        }
    }

    @Test
    fun anUnconfiguredServiceLeavesTheManifestAndThePolicyAndAnotherStays() {
        owner().`when`().delete("$PATH/$MEMORIES_MAP").then().statusCode(200).body("url", nullValue())
        setup().body("capabilities.id", not(hasItem(MEMORIES_MAP)))
            .body("capabilities.id", hasItem(MEMORIES_PLACES)) // control: another service stays
        owner().`when`().get("/api/instance/capabilities").then().statusCode(200)
            .body("find { it.id == '$MEMORIES_MAP' }.serviceConfigured", equalTo(false))
            .body("find { it.id == '$MEMORIES_MAP' }.effective", equalTo(false))
            .body("find { it.id == '$MEMORIES_MAP' }.off", equalTo(false))
            .body("find { it.id == '$GROUP_SPACE' }.serviceConfigured", nullValue())
        val csp = csp()
        assertFalse("https://tile.openstreetmap.org" in directive(csp, "img-src"), csp)
        assertTrue("https://nominatim.openstreetmap.org" in directive(csp, "connect-src"), csp)

        // Configured again at another address: the manifest carries it and the policy allows exactly its origin.
        put(MEMORIES_MAP, """{"url":"https://tiles.example.com:8443/{z}/{x}/{y}.png","attribution":"© Example"}""").statusCode(200)
        setup().body("capabilities.find { it.id == '$MEMORIES_MAP' }.serviceUrl", equalTo("https://tiles.example.com:8443/{z}/{x}/{y}.png"))
            .body("capabilities.find { it.id == '$MEMORIES_MAP' }.serviceAttribution", equalTo("© Example"))
        assertEquals(listOf("'self'", "https://tiles.example.com:8443"), directive(csp(), "img-src"))
    }

    @Test
    fun aSwitchedOffServiceLeavesThePolicyUntilItIsBackOn() {
        owner().contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("/api/instance/capabilities/$BREACHED_PASSWORD").then().statusCode(200)
        assertFalse("https://api.pwnedpasswords.com" in directive(csp(), "connect-src"))
        assertTrue("https://nominatim.openstreetmap.org" in directive(csp(), "connect-src")) // control
        setup().body("capabilities.id", not(hasItem(BREACHED_PASSWORD)))
        owner().contentType(ContentType.JSON).body("""{"off":false}""")
            .`when`().put("/api/instance/capabilities/$BREACHED_PASSWORD").then().statusCode(200)
        assertTrue("https://api.pwnedpasswords.com" in directive(csp(), "connect-src"))
    }

    @Test
    fun aTenantsSwitchLeavesItsSetAndNotThePolicy() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenantId) }
        TenantCapabilitySwitchFixtures.with(tenantId, MEMORIES_MAP) {
            given().header("Authorization", bearer(member)).`when`().get("/api/tenants/$tenantId/capabilities")
                .then().statusCode(200).body("capabilities.id", not(hasItem(MEMORIES_MAP)))
                .body("capabilities.find { it.id == '$MEMORIES_PLACES' }.serviceUrl", equalTo(NOMINATIM))
            // The policy is the instance's (uc.admin.configure-external-services A2): computed off the request path.
            assertTrue("https://tile.openstreetmap.org" in directive(csp(), "img-src"))
        }
    }

    @Test
    fun anAddressThatIsNoHttpUrlOrATileTemplateWithoutItsPlaceholdersIsRefused() {
        put(MEMORIES_MAP, """{"url":"https://tiles.example.com/tile.png"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put(MEMORIES_PLACES, """{"url":"ftp://geo.example.com"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put(MEMORIES_PLACES, """{"url":"https://geo.example.com","attribution":"x"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put(EXPENSES_EXCHANGE_RATES, """{"url":"https://api.frankfurter.dev/v2/rates"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put(EXPENSES_EXCHANGE_RATES, """{"url":"$RATES","attribution":"x"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put("core.group-space", """{"url":"https://x.example.com"}""").statusCode(404).body("code", equalTo("service_not_found"))
        // Nothing was stored: the defaults still stand.
        assertEquals(TILES, addresses.configured().getValue(MEMORIES_MAP).url)
        assertEquals(NOMINATIM, addresses.configured().getValue(MEMORIES_PLACES).url)
        assertEquals(RATES, addresses.configured().getValue(EXPENSES_EXCHANGE_RATES).url)
        // Control: a valid address is accepted.
        put(MEMORIES_PLACES, """{"url":"https://geo.example.com"}""").statusCode(200).body("url", equalTo("https://geo.example.com"))
        put(EXPENSES_EXCHANGE_RATES, """{"url":"https://rates.example.org/v2/providers/ecb/rates?date={date}"}""").statusCode(200)
            .body("url", equalTo("https://rates.example.org/v2/providers/ecb/rates?date={date}"))
    }

    @Test
    fun onlyTheServerOwnerReachesTheServices() {
        val member = seedUser()
        givenAdminChannel().header("Authorization", bearer(member)).`when`().get(PATH).then().statusCode(403)
            .body("code", equalTo("not_server_owner"))
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun put(id: String, body: String): ValidatableResponse =
        owner().contentType(ContentType.JSON).body(body).`when`().put("$PATH/$id").then()

    private fun setup(): ValidatableResponse = given().`when`().get("/api/setup").then().statusCode(200)

    private fun csp(): String =
        given().header("Accept", "text/html").`when`().get("/").then().statusCode(200).extract().header("Content-Security-Policy")

    /** One directive's sources, without its name. */
    private fun directive(csp: String, name: String): List<String> =
        csp.split(';').map { it.trim().split(' ') }.single { it.first() == name }.drop(1)

    /** The migration's seed, written back and pushed through the caches and the policy's snapshot. */
    private fun restoreDefaults() {
        QuarkusTransaction.requiringNew().run {
            ExternalServiceEntity.deleteAll()
            listOf(
                Triple(MEMORIES_MAP, TILES, OSM_ATTRIBUTION), Triple(MEMORIES_PLACES, NOMINATIM, null), Triple(BREACHED_PASSWORD, HIBP, null),
                Triple(EXPENSES_EXCHANGE_RATES, RATES, null),
            ).forEach { (id, url, attribution) ->
                ExternalServiceEntity().apply { capabilityId = id; this.url = url; this.attribution = attribution }.persist()
            }
        }
        addresses.invalidate()
        origins.refresh()
    }

    private companion object {
        const val PATH = "/api/instance/external-services"
        const val OWNER_ID = "0c3a8f61-2f6e-4a0b-9a65-3b1f2d9c7e21"
        const val GROUP_SPACE = "core.group-space"
        const val TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
        const val NOMINATIM = "https://nominatim.openstreetmap.org"
        const val HIBP = "https://api.pwnedpasswords.com/range/"
        const val RATES = "https://api.frankfurter.dev/v2/rates?date={date}"
        const val FRANKFURTER = "https://api.frankfurter.dev"
    }
}
