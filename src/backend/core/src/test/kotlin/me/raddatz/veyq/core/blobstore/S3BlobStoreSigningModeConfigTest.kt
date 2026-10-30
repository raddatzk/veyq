package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.testVeyqConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The half [S3BlobStorePresigningTest] structurally cannot prove: that `veyq.bundled-store.signing-mode` is
 * spelled the way this codebase's operators will spell it, that it **defaults to today's behaviour**, and that
 * the value survives the trip from a property map through [BundledStoreHint] and [BlobStoreProducer] into a real
 * [S3BlobStore]. (A store of the operator's own has no mode to set: it signs its public endpoint verbatim, or its
 * endpoint when it has none — [StoreSettings].)
 *
 * A constructor default proves none of that. Three separate things could each silently move the default while
 * every presigning unit test stayed green: a `@WithDefault` set to the wrong constant, a method named so that
 * SmallRye derives a *different* property path (the trap [me.raddatz.veyq.core.config.VeyqConfig.StoreType]'s
 * member documents), and a hint or producer that reads the member and then forgets to hand it over. The last is
 * the one with no other detector at all — the store would be built in the default mode on every instance, and only
 * a real Kubernetes deployment would notice.
 *
 * Pure JVM: [testVeyqConfig] builds the real mapping from an in-memory map, and presigning is local HMAC. The
 * endpoint is never connected to.
 */
class S3BlobStoreSigningModeConfigTest {

    @Test
    fun theConfiguredDefaultIsTodaysHostSwappingBehaviour() {
        // The "no existing deployment moves" claim at the only altitude that can carry it. An instance that
        // sets nothing — every compose stack in `infra/`, the `.run` config, a self-hoster's `.env` — must
        // resolve to the mode that swaps the host, and the compose recipe therefore needs no new line.
        assertEquals(SigningMode.TENANT_DOMAIN, testVeyqConfig().bundledStore().signingMode())
    }

    @Test
    fun eachModeBindsFromItsHyphenatedPropertyValue() {
        // The property path and the value spelling, both measured rather than recalled: SmallRye derives
        // `veyq.bundled-store.signing-mode` from the method path and hyphenates enum constants. A method renamed
        // to `mode()` or a value written `STORE_ORIGIN` in a `.env` is exactly the kind of thing that fails on
        // an operator's server and nowhere else.
        assertEquals(SigningMode.STORE_ORIGIN, modeFrom("store-origin"))
        assertEquals(SigningMode.INTERNAL_ENDPOINT, modeFrom("internal-endpoint"))
        assertEquals(SigningMode.TENANT_DOMAIN, modeFrom("tenant-domain"))
    }

    @Test
    fun theHintAndProducerHandTheConfiguredModeToTheStoreItBuilds() {
        // End of the wire, asserted on the signed host. `store-origin` is set, a tenant domain is passed, and
        // the URL must still carry the store's own host — which is false for every failure between the config
        // member and the constructor argument, including "the hint never passed it".
        val url = URI.create(storeWith("veyq.bundled-store.signing-mode" to "store-origin").presignPut("storage-key/0", 1L, "tenant.example.com"))
        assertEquals("store.example.net", url.host)
    }

    @Test
    fun theDefaultStillSwapsTheHostForTheTenantsDomain() {
        // The positive control on the same predicate as the case above, through the same wire. Without it,
        // "the store host was signed" is equally satisfied by a hint that has stopped resolving tenants at
        // all — the two cases differ in one property value and in nothing else.
        val url = URI.create(storeWith().presignPut("storage-key/0", 1L, "tenant.example.com"))
        assertEquals("tenant.example.com", url.host)
    }

    @Test
    fun settingTheModeToItsDefaultExplicitlyIsIndistinguishableFromNotSettingIt() {
        // "No existing deployment moves" is a claim about **absence** — the property nobody wrote — and absence
        // is the one thing a test cannot assert directly. So it is pinned as an identity instead: the instance
        // that never heard of `signing-mode` and the one that spells today's behaviour out must produce the same
        // signed URL, character for character. A default that drifted to another constant breaks this even
        // where every mode-specific case above still passes, because each of those sets its own mode.
        val implicit = storeWith().presignPut("storage-key/0", 1L, "tenant.example.com")
        val explicit = storeWith("veyq.bundled-store.signing-mode" to "tenant-domain")
            .presignPut("storage-key/0", 1L, "tenant.example.com")

        assertEquals(URI.create(implicit).host, URI.create(explicit).host)
        assertEquals("tenant.example.com", URI.create(explicit).host)
    }

    /** A real [S3BlobStore] over the bundled hint, a same-origin-shaped connection, plus whatever [extra] the case is about. */
    private fun storeWith(vararg extra: Pair<String, String>): S3BlobStore {
        val hint = BundledStoreHint(
            testVeyqConfig(
                mapOf(
                    "veyq.bundled-store.endpoint" to "http://garage.veyq.svc.cluster.local:3900",
                    "veyq.bundled-store.admin-endpoint" to "http://garage.veyq.svc.cluster.local:3903",
                    "veyq.bundled-store.admin-token" to "admin-token",
                    "veyq.bundled-store.public-endpoint" to "https://store.example.net",
                ) + extra,
            ),
        )
        return BlobStoreProducer.connect(hint.connection!!, presignTtlSeconds = 600)
    }

    private fun modeFrom(value: String): SigningMode =
        testVeyqConfig(mapOf("veyq.bundled-store.signing-mode" to value)).bundledStore().signingMode()
}
