package me.raddatz.veyq.core.appshell

import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig
import io.restassured.config.RestAssuredConfig
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI

/**
 * The store origin reaching a **real response**: the wiring from the store setting (here: the bundled hint, chosen) through
 * `SwitchingBlobStore` and the real `S3BlobStore` into the shell's header. [AppShellTest] proves each mode's origin against the signer
 * in isolation; this is the one place both meet in a booted app, in the mode that needs it (ADR 0064's
 * Kubernetes shape, where a `<meta>`-only policy blocks every store fetch).
 *
 * No network: presigning is local HMAC (ADR 0013 §4), and the `.invalid` internal endpoint fails fast should
 * anything connect.
 */
@QuarkusTest
@TestProfile(AppShellStoreOriginSliceTest.StoreOriginProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppShellStoreOriginSliceTest {

    class StoreOriginProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "veyq.bundled-store.endpoint" to "http://app-shell-store.invalid:3900",
            "veyq.bundled-store.admin-endpoint" to "http://app-shell-store.invalid:3903",
            "veyq.bundled-store.admin-token" to "admin-token",
            "veyq.bundled-store.public-endpoint" to "https://store.example.net",
            "veyq.bundled-store.signing-mode" to "store-origin",
        )
    }

    @BeforeAll
    fun chooseTheBundledStore() = StoreSettingFixtures.chooseBundled()

    @AfterAll
    fun forgetTheStore() = StoreSettingFixtures.clear()

    @Inject
    lateinit var blobStore: BlobStore

    /**
     * The shell reads the store origin on the IO thread, where Quarkus refuses a database read
     * (`BlockingOperationNotAllowedException`, the response never written). So a change of the store setting must leave
     * its resolution warm, not merely forgotten: the Server Owner's save, the adoption at boot and a check's promotion
     * all change it, and the next request is as likely to be a page as an API call (bitten: with the cache only
     * forgotten, each page here hung until the client gave up).
     */
    @Test
    fun aPageAnswersRightAfterTheStoreSettingChanged() {
        val page = { given().config(SHORT).header("Accept", HTML).`when`().get("/recover").then().statusCode(200).extract().header("Content-Security-Policy") }
        StoreSettingFixtures.clear()
        assertFalse(page().contains("https://store.example.net"), "no store: no store origin in the policy")
        StoreSettingFixtures.chooseBundled()
        assertTrue(page().contains("https://store.example.net"), "the bundled store again, its origin back at once")
    }

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
            "default-src 'self'; frame-ancestors 'none'; img-src 'self' https://tile.openstreetmap.org; " +
                // `https:` last: `core.share-embedding` is on by default (ADR 0121, AppShellShareEmbeddingSliceTest).
                "connect-src 'self' $origin https://nominatim.openstreetmap.org https://api.pwnedpasswords.com https://api.frankfurter.dev https:; " +
                "form-action 'self'",
            csp,
        )
    }

    private companion object {
        /** Long enough for a loaded machine, short of the minutes a hung response would hold the suite. */
        val SHORT: RestAssuredConfig = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig().setParam("http.socket.timeout", 15_000))
    }
}
