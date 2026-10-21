package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.smallrye.config.WithDefault
import io.quarkus.arc.ClientProxy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The ingress projection (ADR 0063): a tenant's domain is only *created* by the API, so something has to make it
 * **route**, and that something is a full regenerate of the reverse proxy's router set from the `tenant` table.
 *
 * Two different things are proved here and they fail for different reasons, which is why they are not merged:
 *
 *  - **The predicate** — [suspendedTenantsAreExcluded], [theBootstrapDomainIsRoutedOnceFromConfiguration]. Who
 *    belongs in the file. The bootstrap domain is the case a wrong predicate gets wrong both ways: it must be
 *    there with no tenant row at all (it is routed from configuration, and Traefik runs no other provider), and a
 *    row carrying it must not add a second pair, because two routers of one name is how Traefik drops both.
 *  - **The triggers** — [everyRoutabilityChangingOperationReprojects]. *When* the file is rewritten, driven
 *    through the real HTTP endpoints rather than by calling the projector, because the defect being guarded
 *    against is a `TenantHandler` method that forgot to call it. Comment out any one of the five
 *    `reproject()` calls and this test goes red on that operation's assertion — which is the whole point: each
 *    site being individually correct is not the property, *all five being present* is.
 *
 * The file is read back and parsed for `Host(...)` rules rather than compared to an expected string: the
 * assertion is about which domains route, not about YAML formatting, and a whole-file comparison would go red on
 * every cosmetic change while staying blind to a router pointing at the wrong service.
 */
@QuarkusTest
@TestProfile(TenantIngressProjectorTest.Projecting::class)
class TenantIngressProjectorTest {

    /**
     * The only configuration that turns the projector on. A `%test` run has neither value by default — which is
     * itself asserted, from the opposite direction, by [TenantIngressNoConfigTest].
     */
    class Projecting : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.dynamic-config-path" to CONFIG_PATH,
            "veyq.tenant.ingress.bootstrap-domain" to BOOTSTRAP_DOMAIN,
        )
    }

    @Inject
    lateinit var projector: TenantIngressProjector

    @Inject
    lateinit var sessions: SessionService

    /** The configured bucket — the default here — whose path every blobs router must route (ADR 0013 §4). */
    @Inject
    lateinit var config: VeyqConfig

    /** The sink the producer built from this profile's configuration — its defaults are the ones under test. */
    @Inject
    lateinit var sink: TenantIngressSink

    // --- the predicate: who belongs in the file ---

    @Test
    fun suspendedTenantsAreExcluded() {
        // PENDING routes: the invited owner has to be able to *reach* the accept link at all. SUSPENDED does not
        // (ADR 0066). Both halves in one fixture so "excludes SUSPENDED" cannot be satisfied by a projector that
        // simply emitted nothing.
        val pending = seedTenantIn(TenantEntity.PENDING)
        val active = seedTenantIn(TenantEntity.ACTIVE)
        val suspended = seedTenantIn(TenantEntity.SUSPENDED)

        projector.regenerate()

        val hosts = projectedHosts()
        assertTrue(hosts.contains(pending), "a PENDING tenant must route — its owner has to reach the accept link")
        assertTrue(hosts.contains(active), "an ACTIVE tenant must route")
        assertFalse(hosts.contains(suspended), "a SUSPENDED tenant must not route")
    }

    @Test
    fun theBootstrapPairIsRenderedWithNoTenantRowAtAll() {
        // Traefik runs the file provider only, so the domain the operator opens first — before any tenant exists —
        // routes from this file or from nowhere. `render` with no tenant domains is the "no row" state, which the
        // shared %test database cannot reach through the table (see TraefikFileIngressSink.render).
        val file = configuredSink().render(emptyList())

        assertEquals(listOf("veyq-app", "veyq-blobs"), routerNames(file), "exactly the bootstrap pair:\n$file")
        assertTrue(file.contains("rule: \"Host(`$BOOTSTRAP_DOMAIN`)\""), "the bootstrap app router")
        assertTrue(file.contains("rule: \"Host(`$BOOTSTRAP_DOMAIN`) && PathPrefix(`${blobsPrefix()}`)\""), "the bootstrap blobs router")
    }

    @Test
    fun theBootstrapDomainIsRoutedOnceFromConfiguration() {
        // The deduplication no other case can catch. This row is ACTIVE and well-formed; it must not add a pair,
        // because the bootstrap pair is already rendered from configuration and a second router on the same Host
        // makes Traefik drop *both* ("Router defined multiple times"). The other tenant is the positive control:
        // the file is being written and does contain tenant routers.
        val other = seedTenantIn(TenantEntity.ACTIVE)
        seedTenantIn(TenantEntity.ACTIVE, domain = BOOTSTRAP_DOMAIN)

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        val names = routerNames(file)
        assertTrue(projectedHosts().contains(other), "control: an ordinary tenant is projected")
        assertEquals(names.distinct(), names, "every router name appears once:\n$file")
        assertEquals(2, HOST_RULE.findAll(file).count { it.groupValues[1] == BOOTSTRAP_DOMAIN }, "one pair for the bootstrap domain")
        assertTrue(names.containsAll(listOf("veyq-app", "veyq-blobs")), "…and it is the configured pair")
    }

    @Test
    fun theFileDefinesEveryServiceItsRoutersReference() {
        // Traefik runs no Docker provider, so a router naming `<x>@docker` — or any service this file does not
        // define — is a router Traefik rejects, with its route. The services point at the configured upstreams.
        seedTenantIn(TenantEntity.ACTIVE)
        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        assertFalse(file.contains("@"), "no provider suffix anywhere — every name resolves within this file:\n$file")
        val services = file.substringAfter("\n  services:\n").substringBefore("\n  middlewares:\n")
        assertEquals(listOf("veyq-app", "veyq-blobs", "veyq-admin"), SECTION_KEY.findAll(services).map { it.groupValues[1] }.toList())
        val referenced = Regex("service: (\\S+)").findAll(file).map { it.groupValues[1] }.toSet()
        assertTrue(referenced.isNotEmpty() && services.let { s -> referenced.all { "    $it:\n" in s } }, "referenced $referenced")
        assertTrue(services.substringAfter("    veyq-blobs:\n").substringBefore("    veyq-admin:").contains("passHostHeader: true"),
            "SigV4 signs the host, so the store must see the one the browser sent (ADR 0013 §4)")
        assertEquals(listOf("http://app:8080", "http://garage:3900", "http://admin:8080"), serviceUrls(file))
    }

    @Test
    fun noServiceEverPointsAtTheAdminListener() {
        // ADR 0065: the Server-Owner operations answer only on the backend's admin listener, and a public router
        // in front of it would put them on the internet. %test binds that listener to a random port, so the port
        // asserted against is the production one — read off the config mapping's own default, not retyped here.
        val adminPort = VeyqConfig.AdminChannel::class.java.getMethod("port").getAnnotation(WithDefault::class.java).value.toInt()
        val urls = serviceUrls(configuredSink().render(emptyList()))

        assertEquals(3, urls.size, "control: the services are read")
        urls.forEach { assertNotEquals(adminPort, java.net.URI(it).port, "a rendered service names the admin listener: $it") }
    }

    @Test
    fun theAdminRouterAppearsOnlyWithAnAdminDomain() {
        val without = configuredSink().render(emptyList())
        val with = traefikSink(adminDomain = "admin.example.com").render(emptyList())

        assertFalse(routerNames(without).contains("veyq-admin"), "no admin domain, no admin router:\n$without")
        assertTrue(routerNames(with).contains("veyq-admin"), "an admin domain gets its router:\n$with")
        val admin = with.substringAfter("    veyq-admin:\n").substringBefore("\n  services:")
        assertTrue(admin.contains("rule: \"Host(`admin.example.com`)\"") && admin.contains("service: veyq-admin"), admin)
        assertTrue(admin.contains("certResolver: letsencrypt"), "its own certificate")
    }

    @Test
    fun aTenantGetsBothItsAppAndItsSameOriginBlobsRouter() {
        // ADR 0013 §4's same-origin blob path is per-domain or it is nothing: a tenant with only an app router
        // would serve its media from the bootstrap origin and lose the no-CORS property for every tenant but the
        // first. Asserted on the rule text, so a blobs router that dropped the prefix or the priority is caught.
        val domain = seedTenantIn(TenantEntity.ACTIVE)

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        assertTrue(file.contains("rule: \"Host(`$domain`)\""), "the app router")
        assertTrue(file.contains("rule: \"Host(`$domain`) && PathPrefix(`${blobsPrefix()}`)\""), "the same-origin blobs router")
        val slug = "veyq-tenant-${domain.replace('.', '-')}"
        val app = routerBlock(file, "$slug-app")
        assertTrue(app.contains("service: veyq-app") && app.contains("priority: 1\n"), app)
        val blobs = routerBlock(file, "$slug-blobs")
        assertTrue(blobs.contains("service: veyq-blobs") && blobs.contains("priority: 100\n"), blobs)
        listOf(app, blobs).forEach {
            assertTrue(it.contains("entryPoints: [websecure]") && it.contains("certResolver: letsencrypt"), it)
        }
    }

    @Test
    fun everyBlobsRouterMarksStoreResponsesInert() {
        // ADR 0013 §6: where the store shares the app's origin — the bootstrap domain and every projected tenant
        // domain — the proxy sets `nosniff` + a `sandbox` CSP on store responses, a belt under the signer's
        // overrides. The middleware is DEFINED in the same file its routers reference it from, so no router can
        // name one Traefik has not loaded. Asserted per blobs router block; the control is the block count.
        seedTenantIn(TenantEntity.ACTIVE)

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        val blobs = routerNames(file).filter { it.endsWith("-blobs") }
        assertTrue(blobs.contains("veyq-blobs") && blobs.size >= 2, "control: the bootstrap and a tenant blobs router: $blobs")
        blobs.forEach { assertTrue(routerBlock(file, it).contains("middlewares: [veyq-blobs-inert]"), "$it carries the middleware") }
        val inert = file.substringAfter("\n  middlewares:\n    veyq-blobs-inert:\n")
        assertTrue(inert.contains("contentTypeNosniff: true"), "the middleware is defined here:\n$file")
        assertTrue(inert.contains("contentSecurityPolicy: \"sandbox; default-src 'none'\""), inert)
    }

    @Test
    fun regenerateIsAFullRewriteNotAPatch() {
        // The invariant the whole design rests on (ADR 0063: "never an incremental patch"). A tenant that is gone
        // from the table must be gone from the file after any later regenerate — including one triggered by a
        // completely unrelated tenant, which is exactly what an incremental "add one router" would get wrong.
        // PENDING, because that is the state a tenant can actually be deleted from (revoke) — an ACTIVE one owns
        // a TENANT scope row that references it, and deleting the tenant under it violates the foreign key.
        val doomed = seedTenantIn(TenantEntity.PENDING)
        projector.regenerate()
        assertTrue(projectedHosts().contains(doomed))

        QuarkusTransaction.requiringNew().run { TenantEntity.delete("domain", doomed) }
        seedTenantIn(TenantEntity.ACTIVE)
        projector.regenerate()

        assertFalse(projectedHosts().contains(doomed), "a deleted tenant survives only an incremental projection")
    }

    @Test
    fun aCorruptedOrWipedFileHealsOnTheNextRegenerate() {
        // The self-healing property (ADR 0063) — a wiped volume, a fresh Traefik container, an operator's edit.
        // Nothing "detects" the damage: the next full regenerate simply overwrites whatever is there.
        val domain = seedTenantIn(TenantEntity.ACTIVE)
        projector.regenerate()
        Files.writeString(Path.of(CONFIG_PATH), "this is not the configuration you are looking for\n")

        projector.regenerate()

        assertTrue(projectedHosts().contains(domain))
        assertFalse(Files.readString(Path.of(CONFIG_PATH)).contains("not the configuration"))
    }

    @Test
    fun aRowThatSkippedTheBoundaryIsNeverProjected() {
        // `CreateTenantRequest`/`ChangeDomainRequest` reject this shape, so the only way such a row exists is
        // the way this test makes one: written straight through Panache, past the boundary — a row that
        // predates the validation, or arrives by a path that skips it. That is exactly the case the projector's
        // re-validation exists for, and until this test it had never been executed: the projection is rendered
        // as `rule: "Host(`$domain`)"`, so a backtick closes the matcher and everything after it becomes
        // Traefik configuration nobody wrote.
        //
        // The well-formed tenant beside it is the control: the file IS being written, and the malformed row is
        // the one thing missing from it — without that, a projector that emitted nothing at all would pass.
        val sound = seedTenantIn(TenantEntity.ACTIVE)
        val injected = seedTenantIn(TenantEntity.PENDING, domain = "evil`) || Host(`stolen.example.com")

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        assertTrue(projectedHosts().contains(sound), "control: an ordinary tenant is projected")
        assertFalse(projectedHosts().contains(injected), "a row that skipped the boundary must not become a router")
        assertFalse(file.contains("stolen.example.com"), "…and no fragment of it may reach the file at all")
    }

    // --- the triggers: when the file is rewritten ---

    @Test
    fun everyRoutabilityChangingOperationReprojects() {
        // Driven through the HTTP API, not the projector, because the defect this guards is a handler that never
        // calls it. Each step asserts the *file* — the tenant row being right is not the property.
        val token = ownerToken()
        val domain = "trigger-${UUID.randomUUID()}.example.com"
        val moved = "moved-${UUID.randomUUID()}.example.com"

        // 1 — create
        val tenantId = givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"tenantName":"Fam","domain":"$domain","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then().statusCode(201).extract().path<String>("tenantId")
        assertTrue(projectedHosts().contains(domain), "create must reproject")

        // 2 — change-domain (from PENDING)
        givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"domain":"$moved"}""")
            .`when`().post("/api/tenants/$tenantId/change-domain").then().statusCode(204)
        assertTrue(projectedHosts().contains(moved), "change-domain must reproject the new domain")
        assertFalse(projectedHosts().contains(domain), "…and the old domain must stop resolving")

        // 3 — suspend (from ACTIVE, so activate the row first — acceptance itself is not a trigger, both
        // PENDING and ACTIVE route, which is the projector's predicate stated from the other side)
        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where id = ?2", TenantEntity.ACTIVE, tenantId)
        }
        givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .`when`().post("/api/tenants/$tenantId/suspend").then().statusCode(200)
        assertFalse(projectedHosts().contains(moved), "suspend must reproject without the tenant")

        // 4 — reactivate
        givenAdminChannel().header("Authorization", "Bearer $token")
            .`when`().post("/api/tenants/$tenantId/reactivate").then().statusCode(204)
        assertTrue(projectedHosts().contains(moved), "reactivate must put the routers back")

        // 5 — revoke (PENDING-only, so wind the row back; the revoke itself is the trigger under test)
        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where id = ?2", TenantEntity.PENDING, tenantId)
        }
        givenAdminChannel().header("Authorization", "Bearer $token")
            .`when`().delete("/api/tenants/$tenantId").then().statusCode(204)
        assertFalse(projectedHosts().contains(moved), "revoke must reproject without the tenant")
    }

    @Test
    fun aRefusedOperationDoesNotChangeTheProjection() {
        // The negative control for the triggers above: reprojecting is tied to the transition actually happening,
        // not to the endpoint being called. A 409 leaves the table alone, so it must leave the file alone too.
        val token = ownerToken()
        val domain = "refused-${UUID.randomUUID()}.example.com"
        val tenantId = givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"tenantName":"Fam","domain":"$domain","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then().statusCode(201).extract().path<String>("tenantId")

        // PENDING cannot be suspended (409) — and the tenant must still route afterwards.
        givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .`when`().post("/api/tenants/$tenantId/suspend").then().statusCode(409)
            .body("code", equalTo("tenant_not_active"))
        assertTrue(projectedHosts().contains(domain))
    }

    // --- helpers ---

    private fun configuredSink() = ClientProxy.unwrap(sink) as TraefikFileIngressSink

    /** A sink rendering this profile's shape with one knob turned — for a configuration no profile here sets. */
    private fun traefikSink(adminDomain: String? = null) = TraefikFileIngressSink(
        Path.of("build/test-tenant-ingress-render-only/tenants.yml"), BOOTSTRAP_DOMAIN, adminDomain,
        TraefikFileIngressSink.Upstreams(app = "http://app:8080", blobs = "http://garage:3900", admin = "http://admin:8080"),
        bucket = config.bundledStore().bucket(),
    )

    private fun blobsPrefix() = "/${config.bundledStore().bucket()}/"

    /** The router names, in file order — duplicates kept, since a duplicate is what some tests look for. */
    private fun routerNames(file: String): List<String> =
        if ("\n  routers:\n" !in file) emptyList()
        else SECTION_KEY.findAll(file.substringAfter("\n  routers:\n").substringBefore("\n  services:\n")).map { it.groupValues[1] }.toList()

    private fun routerBlock(file: String, name: String): String =
        file.substringAfter("\n    $name:\n").split(Regex("\n    (?=\\S)|\n  (?=\\S)")).first()

    private fun serviceUrls(file: String): List<String> =
        Regex("- url: \"([^\"]+)\"").findAll(file).map { it.groupValues[1] }.toList()

    /** Every `Host(...)` the generated file routes, read back from disk. */
    private fun projectedHosts(): List<String> =
        HOST_RULE.findAll(Files.readString(Path.of(CONFIG_PATH))).map { it.groupValues[1] }.distinct().toList()

    private fun ownerToken(): String = sessions.issue(seedUser(serverRole = "SERVER_OWNER")).accessToken

    /** A tenant row in [status]; returns its domain. Distinct per call so the JVM-lived mem DB cannot bleed. */
    private fun seedTenantIn(status: String, domain: String? = null): String {
        val host = domain ?: "p-${UUID.randomUUID()}.example.com"
        seedTenant(domain = host, status = status, tenantScope = status == TenantEntity.ACTIVE)
        return host
    }

    companion object {
        /** Under `build/`, so a run leaves nothing behind a `./gradlew clean` does not remove. */
        const val CONFIG_PATH = "build/test-tenant-ingress/tenants.yml"
        const val BOOTSTRAP_DOMAIN = "bootstrap.example.com"
        val HOST_RULE = Regex("Host\\(`([^`]+)`\\)")
        /** A key at the depth of a router, service or middleware name. */
        val SECTION_KEY = Regex("(?m)^    ([a-z0-9-]+):$")
    }
}
