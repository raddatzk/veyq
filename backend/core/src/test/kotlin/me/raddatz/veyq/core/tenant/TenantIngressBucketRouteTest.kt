package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * **A bucket other than the default routes with configuration alone** (ADR 0013 §4). Presigned URLs are
 * path-style, so an object's path is `/<bucket>/<key>`, and the blobs router must claim exactly that prefix. A
 * router that spells the bucket itself serves the default and 404s every object of any other — silently, since
 * nothing reports a route that matches nothing.
 *
 * **Why this class exists beside [TenantIngressProjectorTest].** Every test there runs on the default bucket, so a
 * sink that hard-codes the default's prefix passes all of them: the literal and the setting agree. Only a
 * non-default bucket tells "derived from the setting" apart from "happens to equal it", and it is set here through
 * configuration — the producer, not the test, hands it to the sink — so a producer that forgot to pass it is
 * caught as well as a sink that ignores it.
 *
 * The rule is compared whole, not searched for the bucket's name: `/restored-media` without the trailing slash
 * would also contain it, and that is the prefix that claims every app path merely beginning with the name.
 */
@QuarkusTest
@TestProfile(TenantIngressBucketRouteTest.NonDefaultBucket::class)
class TenantIngressBucketRouteTest {

    class NonDefaultBucket : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.dynamic-config-path" to CONFIG_PATH,
            "veyq.tenant.ingress.bootstrap-domain" to BOOTSTRAP_DOMAIN,
            "veyq.blob-store.bucket" to BUCKET,
        )
    }

    @Inject
    lateinit var projector: TenantIngressProjector

    @Test
    fun everyBlobsRouterRoutesTheConfiguredBucketAndNothingElse() {
        val tenant = "b-${UUID.randomUUID()}.example.com"
        seedTenant(domain = tenant)

        projector.regenerate()

        val file = Files.readString(Path.of(CONFIG_PATH))
        val blobs = BLOBS_ROUTER.findAll(file).associate { it.groupValues[1] to it.groupValues[2] }
        // Control: both kinds of blobs router are present — the bootstrap domain's, rendered from configuration,
        // and a projected tenant's. Without it, "every blobs router carries the prefix" holds of an empty set.
        val tenantRouter = "veyq-tenant-${tenant.replace('.', '-')}-blobs"
        assertTrue(blobs.keys.containsAll(listOf("veyq-blobs", tenantRouter)), "the bootstrap and the tenant blobs router:\n$file")
        blobs.forEach { (name, rule) ->
            val domain = if (name == "veyq-blobs") BOOTSTRAP_DOMAIN else HOST.find(rule)!!.groupValues[1]
            assertEquals("Host(`$domain`) && PathPrefix(`/$BUCKET/`)", rule, "$name routes the configured bucket's path")
        }
        assertEquals(listOf("/$BUCKET/"), PATH_PREFIX.findAll(file).map { it.groupValues[1] }.distinct().toList(),
            "no router anywhere in the file claims any other path:\n$file")
    }

    companion object {
        /** Its own file, so this profile's boot-time regenerate never races the default-bucket tests' reads. */
        const val CONFIG_PATH = "build/test-tenant-ingress-bucket/tenants.yml"
        const val BOOTSTRAP_DOMAIN = "bucket-bootstrap.example.com"
        /** Not the default, and valid under S3's naming rules — a name a restore onto another store might carry. */
        const val BUCKET = "restored-media"
        val BLOBS_ROUTER = Regex("(?m)^    ([a-z0-9-]+-blobs):\\n      rule: \"([^\"]+)\"")
        val HOST = Regex("Host\\(`([^`]+)`\\)")
        val PATH_PREFIX = Regex("PathPrefix\\(`([^`]*)`\\)")
    }
}
