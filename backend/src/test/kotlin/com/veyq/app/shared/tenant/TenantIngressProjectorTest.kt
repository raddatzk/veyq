package com.veyq.app.shared.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The ingress projection (ADR 0063): a tenant's domain is only *created* by the API, so something has to make it
 * **route**, and that something is a full regenerate of the reverse proxy's router set from the `tenant` table.
 *
 * Two different things are proved here and they fail for different reasons, which is why they are not merged:
 *
 *  - **The predicate** — [suspendedTenantsAreExcluded], [theBootstrapDomainIsNeverProjected]. Who belongs in the
 *    file. The bootstrap-domain exclusion is the one a wrong predicate passes every other case of: that domain
 *    satisfies `status != SUSPENDED` exactly like any other tenant and is nevertheless the one row that must not
 *    appear, because `infra/docker-compose.prod.yml` already routes it with static labels and two routers on one
 *    Host is how Traefik drops both.
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

    // --- the predicate: who belongs in the file ---

    @Test
    fun suspendedTenantsAreExcluded() {
        // PENDING routes: the invited owner has to be able to *reach* the accept link at all. SUSPENDED does not
        // (ADR 0066). Both halves in one fixture so "excludes SUSPENDED" cannot be satisfied by a projector that
        // simply emitted nothing.
        val pending = seedTenant(TenantEntity.PENDING)
        val active = seedTenant(TenantEntity.ACTIVE)
        val suspended = seedTenant(TenantEntity.SUSPENDED)

        projector.regenerate()

        val hosts = projectedHosts()
        assertTrue(hosts.contains(pending), "a PENDING tenant must route — its owner has to reach the accept link")
        assertTrue(hosts.contains(active), "an ACTIVE tenant must route")
        assertFalse(hosts.contains(suspended), "a SUSPENDED tenant must not route")
    }

    @Test
    fun theBootstrapDomainIsNeverProjected() {
        // The exclusion no other case can catch. This row is ACTIVE and well-formed; it is skipped purely because
        // the compose file already carries a static router for it, and a second router on the same Host makes
        // Traefik drop *both* ("Router defined multiple times"). The other tenant is the positive control: the
        // file is being written and does contain routers — the bootstrap domain is the one thing missing from it.
        val other = seedTenant(TenantEntity.ACTIVE)
        seedTenant(TenantEntity.ACTIVE, domain = BOOTSTRAP_DOMAIN)

        projector.regenerate()

        val hosts = projectedHosts()
        assertTrue(hosts.contains(other), "control: an ordinary tenant is projected")
        assertFalse(hosts.contains(BOOTSTRAP_DOMAIN), "the bootstrap domain is statically routed and must not be projected twice")
    }

    @Test
    fun aTenantGetsBothItsAppAndItsSameOriginBlobsRouter() {
        // ADR 0013 §4's same-origin blob path is per-domain or it is nothing: a tenant with only an app router
        // would serve its media from the bootstrap origin and lose the no-CORS property for every tenant but the
        // first. Asserted on the rule text, so a blobs router that dropped the prefix or the priority is caught.
        val domain = seedTenant(TenantEntity.ACTIVE)

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        assertTrue(file.contains("rule: \"Host(`$domain`)\""), "the app router")
        assertTrue(file.contains("rule: \"Host(`$domain`) && PathPrefix(`/veyq`)\""), "the same-origin blobs router")
        assertTrue(file.contains("service: veyq-app@docker"), "routers reference the compose file's own services")
        assertTrue(file.contains("service: veyq-blobs@docker"))
    }

    @Test
    fun regenerateIsAFullRewriteNotAPatch() {
        // The invariant the whole design rests on (ADR 0063: "never an incremental patch"). A tenant that is gone
        // from the table must be gone from the file after any later regenerate — including one triggered by a
        // completely unrelated tenant, which is exactly what an incremental "add one router" would get wrong.
        // PENDING, because that is the state a tenant can actually be deleted from (revoke) — an ACTIVE one owns
        // a TENANT scope row that references it, and deleting the tenant under it violates the foreign key.
        val doomed = seedTenant(TenantEntity.PENDING)
        projector.regenerate()
        assertTrue(projectedHosts().contains(doomed))

        QuarkusTransaction.requiringNew().run { TenantEntity.delete("domain", doomed) }
        seedTenant(TenantEntity.ACTIVE)
        projector.regenerate()

        assertFalse(projectedHosts().contains(doomed), "a deleted tenant survives only an incremental projection")
    }

    @Test
    fun aCorruptedOrWipedFileHealsOnTheNextRegenerate() {
        // The self-healing property (ADR 0063) — a wiped volume, a fresh Traefik container, an operator's edit.
        // Nothing "detects" the damage: the next full regenerate simply overwrites whatever is there.
        val domain = seedTenant(TenantEntity.ACTIVE)
        projector.regenerate()
        Files.writeString(Path.of(CONFIG_PATH), "this is not the configuration you are looking for\n")

        projector.regenerate()

        assertTrue(projectedHosts().contains(domain))
        assertFalse(Files.readString(Path.of(CONFIG_PATH)).contains("not the configuration"))
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
        val tenantId = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"tenantName":"Fam","domain":"$domain","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then().statusCode(201).extract().path<String>("tenantId")
        assertTrue(projectedHosts().contains(domain), "create must reproject")

        // 2 — change-domain (from PENDING)
        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"domain":"$moved"}""")
            .`when`().post("/api/tenants/$tenantId/change-domain").then().statusCode(204)
        assertTrue(projectedHosts().contains(moved), "change-domain must reproject the new domain")
        assertFalse(projectedHosts().contains(domain), "…and the old domain must stop resolving")

        // 3 — suspend (from ACTIVE, so activate the row first — acceptance itself is not a trigger, both
        // PENDING and ACTIVE route, which is the projector's predicate stated from the other side)
        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where id = ?2", TenantEntity.ACTIVE, tenantId)
        }
        given().header("Authorization", "Bearer $token")
            .`when`().post("/api/tenants/$tenantId/suspend").then().statusCode(204)
        assertFalse(projectedHosts().contains(moved), "suspend must reproject without the tenant")

        // 4 — reactivate
        given().header("Authorization", "Bearer $token")
            .`when`().post("/api/tenants/$tenantId/reactivate").then().statusCode(204)
        assertTrue(projectedHosts().contains(moved), "reactivate must put the routers back")

        // 5 — revoke (PENDING-only, so wind the row back; the revoke itself is the trigger under test)
        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where id = ?2", TenantEntity.PENDING, tenantId)
        }
        given().header("Authorization", "Bearer $token")
            .`when`().delete("/api/tenants/$tenantId").then().statusCode(204)
        assertFalse(projectedHosts().contains(moved), "revoke must reproject without the tenant")
    }

    @Test
    fun aRefusedOperationDoesNotChangeTheProjection() {
        // The negative control for the triggers above: reprojecting is tied to the transition actually happening,
        // not to the endpoint being called. A 409 leaves the table alone, so it must leave the file alone too.
        val token = ownerToken()
        val domain = "refused-${UUID.randomUUID()}.example.com"
        val tenantId = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"tenantName":"Fam","domain":"$domain","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then().statusCode(201).extract().path<String>("tenantId")

        // PENDING cannot be suspended (409) — and the tenant must still route afterwards.
        given().header("Authorization", "Bearer $token")
            .`when`().post("/api/tenants/$tenantId/suspend").then().statusCode(409)
            .body("code", equalTo("tenant_not_active"))
        assertTrue(projectedHosts().contains(domain))
    }

    // --- helpers ---

    /** Every `Host(...)` the generated file routes, read back from disk. */
    private fun projectedHosts(): List<String> =
        HOST_RULE.findAll(Files.readString(Path.of(CONFIG_PATH))).map { it.groupValues[1] }.distinct().toList()

    private fun ownerToken(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId
                email = "ingress-owner-$userId@example.com"
                serverRole = "SERVER_OWNER"
                suiteId = "v1"
                identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                createdAt = Instant.now()
            }.persist()
        }
        return sessions.issue(userId).accessToken
    }

    /** A tenant row in [status]; returns its domain. Distinct per call so the JVM-lived mem DB cannot bleed. */
    private fun seedTenant(status: String, domain: String? = null): String {
        val tenantId = UUID.randomUUID().toString()
        val host = domain ?: "p-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                this.domain = host
                ownerEmail = "owner-$tenantId@example.com"
                this.status = status
                createdAt = Instant.now()
            }.persist()
            if (status == TenantEntity.ACTIVE) {
                ScopeEntity().apply {
                    id = tenantId
                    this.tenantId = tenantId
                    kind = ScopeEntity.TENANT
                    createdAt = Instant.now()
                }.persist()
            }
        }
        return host
    }

    companion object {
        /** Under `build/`, so a run leaves nothing behind a `./gradlew clean` does not remove. */
        const val CONFIG_PATH = "build/test-tenant-ingress/tenants.yml"
        const val BOOTSTRAP_DOMAIN = "bootstrap.example.com"
        val HOST_RULE = Regex("Host\\(`([^`]+)`\\)")
    }
}
