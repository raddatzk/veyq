package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.AcceptInviteRequest
import me.raddatz.veyq.mobile.api.models.CreatePersonalScopeRequest
import me.raddatz.veyq.mobile.api.models.CreateTenantRequest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.TenantRef
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Dev seeding, not a test of the product: gives the seeded principal a second tenant on the dev instance, so the app's
 * tenant switcher has two to switch between. The seeder births one tenant with the owner it creates; a second one for
 * an owner who already exists needs that owner's own identity, which only a signed-in client holds — here, the app's
 * suite: the Server Owner creates the tenant on the admin listener, accepts it with a TENANT grant sealed to itself and
 * signed by it, and births its personal scope there (as `src/web/scripts/instanceBirth.mts` does for the first).
 *
 * Runs only with `VEYQ_SEED_TENANT=<domain>|<name>` (and `VEYQ_LIVE`, an origin of the server); the admin listener is
 * `VEYQ_ADMIN_BASE`, default `http://127.0.0.1:8090`.
 */
class DevSecondTenantTest {
    @Test
    fun seedASecondTenantForThePrincipal() = runTest(timeout = 2.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val spec = System.getenv("VEYQ_SEED_TENANT") ?: return@runTest
        val parts = spec.split('|', limit = 2)
        val domain = parts[0]
        val name = parts.getOrElse(1) { domain }
        val admin = System.getenv("VEYQ_ADMIN_BASE") ?: "http://127.0.0.1:8090"
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
        ) { block -> HttpClient(OkHttp, block) }
        if (account.tenants.any { it.domain == domain }) {
            println("seed: $domain already exists")
            return@runTest
        }
        val created = account.call { TenantsApi(admin, account.http).createTenant(CreateTenantRequest(name, domain, env.getValue("VEYQ_EMAIL"))) }
        val tenantOrigin = account.originOf(TenantRef(created.tenantId, name, domain))

        val tenantKey = BouncyCastlePrimitives.randomBytes(32)
        val birth = account.sealSelfGrant(tenantKey, created.tenantId, "TENANT", 0)
        account.call {
            TenantsApi(tenantOrigin, account.http).acceptTenantInvite(
                AcceptInviteRequest(
                    bootstrapSecret = created.bootstrapSecret,
                    username = env["VEYQ_USERNAME"] ?: "owner",
                    grant = birth.wrappedGrant,
                    grantSignature = birth.grantSignature,
                    recipientIdentityDigest = birth.recipientIdentityDigest,
                ),
            )
        }
        val personalId = UUID.randomUUID().toString()
        val personal = account.sealSelfGrant(BouncyCastlePrimitives.randomBytes(32), personalId, "PERSONAL", 0)
        account.call {
            ScopesApi(tenantOrigin, account.http).createPersonalScope(
                CreatePersonalScopeRequest(personalId, personal.wrappedGrant, personal.grantSignature, personal.recipientIdentityDigest),
            )
        }
        println("seed: $name ($domain) created and accepted, personal scope $personalId")

        val again = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
        ) { block -> HttpClient(OkHttp, block) }
        val opened = again.open(again.tenants.first { it.domain == domain })
        assertTrue(opened.spaces().map { it.kind }.containsAll(listOf("TENANT", "PERSONAL")), "the new tenant's grants did not open")
        account.signOut()
        again.signOut()
    }
}
