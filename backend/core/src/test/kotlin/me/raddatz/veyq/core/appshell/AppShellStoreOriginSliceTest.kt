package me.raddatz.veyq.core.appshell

import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.blobstore.BlobStore
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The store origin reaching a **real response**: the wiring from `veyq.blob-store.*` through `BlobStoreProducer`
 * and the real `S3BlobStore` into the shell's header. [AppShellTest] proves each mode's origin against the signer
 * in isolation; this is the one place both meet in a booted app, in the mode that needs it (ADR 0064's
 * Kubernetes shape, where a `<meta>`-only policy blocks every store fetch).
 *
 * No network: presigning is local HMAC (ADR 0013 §4), and the `.invalid` internal endpoint fails fast should
 * anything connect.
 */
@QuarkusTest
@TestProfile(AppShellStoreOriginSliceTest.StoreOriginProfile::class)
class AppShellStoreOriginSliceTest {

    class StoreOriginProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "veyq.blob-store.endpoint" to "http://app-shell-store.invalid:3900",
            "veyq.blob-store.public-endpoint" to "https://store.example.net",
            "veyq.blob-store.signing-mode" to "store-origin",
            "veyq.blob-store.access-key" to "access",
            "veyq.blob-store.secret-key" to "secret",
        )
    }

    @Inject
    lateinit var blobStore: BlobStore

    @Test
    fun thePolicyAllowsTheOriginOfAUrlThisAppSigns() {
        // The expected origin is taken from a URL the running signer produced, not from the profile above — so a
        // shell that read `public-endpoint` itself would pass only for as long as the two happened to agree.
        val signed = URI.create(blobStore.presignPut("storage-key/0", 1L, "tenant.example.com")!!)
        val origin = "${signed.scheme}://${signed.host}"
        assertEquals("https://store.example.net", origin, "control: the signer is the real one, in store-origin mode")

        val csp = given().header("Accept", HTML).`when`().get("/recover").then().statusCode(200)
            .extract().header("Content-Security-Policy")
        assertEquals(
            "default-src 'self'; frame-ancestors 'none'; connect-src 'self' https://api.pwnedpasswords.com $origin; " +
                "form-action 'self'",
            csp,
        )
    }
}
