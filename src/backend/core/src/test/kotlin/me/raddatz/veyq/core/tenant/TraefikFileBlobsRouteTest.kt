package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.blobstore.StoreConnection
import me.raddatz.veyq.core.blobstore.StoreResolution
import me.raddatz.veyq.core.blobstore.StoreSecret
import me.raddatz.veyq.core.blobstore.StoreSettings
import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The two halves of "the blobs route is rendered only while the store in use is the bundled one" (ADR 0064 § the
 * bundled store is opt-in), each a pure function and so decided here without a `%test` hint or a profile:
 *
 *  - **which bucket the route serves, per resolution** — [StoreSettings.bundledRouteBucket]: the bundled store's,
 *    ready; none for a store of the operator's own (one WITH a bucket, so the negative is not a missing value), no
 *    store, an unreadable one, or the bundled one where the deployment passes no hint.
 *  - **what the sink renders with none** — no blobs router for any domain (the bootstrap pair's included), and
 *    neither the service nor the middleware only they reference; every negative is read against the same sink's
 *    render with the bucket, its positive control.
 *
 * The integrated walk through the producer and the real store setting is `TenantIngressBucketRouteTest`'s.
 */
class TraefikFileBlobsRouteTest {

    // --- which bucket, per resolution ---

    @Test
    fun onlyTheBundledStoreReadyNamesABucket() {
        assertEquals("blobs", StoreSettings.bundledRouteBucket(StoreResolution.Ready(connection(bundled = true))), "control: the bundled store, ready")
        assertNull(StoreSettings.bundledRouteBucket(StoreResolution.Ready(connection(bundled = false))), "a store of the operator's own")
        assertNull(StoreSettings.bundledRouteBucket(StoreResolution.NotSet), "no store")
        assertNull(StoreSettings.bundledRouteBucket(StoreResolution.Unavailable(emptyList(), hintMissing = true)), "bundled, but no hint")
        assertNull(StoreSettings.bundledRouteBucket(StoreResolution.Unavailable(listOf(StoreSecret.SECRET_KEY))), "an unreadable store")
    }

    // --- what the sink renders ---

    @Test
    fun withoutABucketNoDomainGetsABlobsRouterAndNothingOnlyTheyReference() {
        val without = sink(bucket = null).render(listOf(TENANT))
        val with = sink(bucket = "blobs").render(listOf(TENANT))

        // The positive control on the same sink shape: both blobs routers, their rules, the service and the middleware.
        assertEquals(listOf("veyq-app", "veyq-blobs", "$SLUG-app", "$SLUG-blobs"), routers(with), with)
        assertTrue(with.contains("rule: \"Host(`$BOOTSTRAP`) && PathPrefix(`/blobs/`)\"") &&
            with.contains("rule: \"Host(`$TENANT`) && PathPrefix(`/blobs/`)\""), with)
        assertTrue(with.contains("\n    veyq-blobs:\n      loadBalancer:\n        passHostHeader: true\n"), "control: the blobs service:\n$with")
        assertTrue(with.contains("\n  middlewares:\n    veyq-blobs-inert:\n"), "control: the inert middleware:\n$with")

        assertEquals(listOf("veyq-app", "$SLUG-app"), routers(without), "each domain keeps its app router alone:\n$without")
        assertFalse(without.contains("PathPrefix("), "no router claims a path:\n$without")
        assertFalse(without.contains("veyq-blobs"), "neither the blobs service nor the inert middleware:\n$without")
        assertFalse(without.contains("garage:3900"), "nothing points at the bundled store's upstream:\n$without")
        // Traefik rejects a present-but-empty section (TenantIngressEmptyProjectionTest), and the middleware was its
        // only entry here — the services section still has the app's and the admin's.
        assertFalse(keys(without).contains("middlewares:"), "no empty middlewares section:\n$without")
        assertTrue(keys(without).contains("services:"), "control: the services section stays:\n$without")
    }

    @Test
    fun theClientAddressMiddlewareKeepsItsSectionWithoutABucket() {
        val rendered = sink(bucket = null, clientPortHeader = "X-Client-Port").render(listOf(TENANT))
        assertTrue(rendered.contains("\n  middlewares:\n    veyq-client-addr:\n"), rendered)
        assertFalse(rendered.contains("veyq-blobs"), rendered)
    }

    @Test
    fun theBucketIsReadAtEveryRender() {
        // One sink, the store changing under it — as the producer's lambda reads the setting in use.
        var bucket: String? = null
        val sink = TraefikFileIngressSink(PATH, BOOTSTRAP, null, UPSTREAMS, { bucket })
        assertFalse(sink.render(emptyList()).contains("veyq-blobs"), "none in use")
        bucket = "media"
        assertTrue(sink.render(emptyList()).contains("PathPrefix(`/media/`)"), "the bundled store taken into use")
        bucket = null
        assertFalse(sink.render(emptyList()).contains("veyq-blobs"), "and replaced again")
    }

    private fun sink(bucket: String?, clientPortHeader: String? = null) =
        TraefikFileIngressSink(PATH, BOOTSTRAP, null, UPSTREAMS, { bucket }, clientPortHeader)

    private fun routers(rendered: String): List<String> =
        Regex("(?m)^    ([a-z0-9-]+):$").findAll(rendered.substringAfter("\n  routers:\n").substringBefore("\n  services:\n"))
            .map { it.groupValues[1] }.toList()

    private fun keys(rendered: String) = rendered.lineSequence().map { it.trim() }.toList()

    private fun connection(bundled: Boolean) = StoreConnection(
        bundled = bundled, type = if (bundled) StoreType.GARAGE else StoreType.S3, endpoint = "http://s3.invalid:9000",
        publicEndpoint = null, signingMode = SigningMode.INTERNAL_ENDPOINT, bucket = "blobs", region = "us-east-1",
        accessKey = "access", secretKey = "secret", garageAdminEndpoint = null, garageAdminToken = null,
    )

    private companion object {
        val PATH: Path = Path.of("build/test-tenant-ingress-render-only/tenants.yml")
        const val BOOTSTRAP = "veyq.example.org"
        const val TENANT = "tenant.example.com"
        const val SLUG = "veyq-tenant-tenant-example-com"
        val UPSTREAMS = TraefikFileIngressSink.Upstreams(app = "http://app:8080", blobs = "http://garage:3900", admin = "http://admin:8080")
    }
}
