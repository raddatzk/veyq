package com.veyq.app.shared.appshell

import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.testing.TestFixtures.seedTenant
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The app shell over a real request (ADR 0069), against the fixture shell in `src/test/resources/META-INF/resources`
 * — an `index.html` with no `<meta>`, one asset, `sw.js` and an emitted policy. That fixture is on the **shared**
 * test classpath, so every `@QuarkusTest` boots with a shell; nothing else in the suite asks for HTML, and the
 * headers it adds change no API answer. The no-shell branch is [AppShellAbsentSliceTest].
 *
 * **Every negative carries a positive control on the same predicate.** A shell that 404'd wholesale, or a
 * fallback that never ran, satisfies each negative here perfectly; the control is what tells "refused for the
 * stated reason" apart from "refused".
 *
 * The store here is the in-memory one, so the policy is the fixture's verbatim. What each signing mode appends
 * is [AppShellTest]; that the appended origin reaches a real response is [AppShellStoreOriginSliceTest].
 */
@QuarkusTest
class AppShellSliceTest {

    @Test
    fun aDeepLinkLoadedAsHtmlGetsTheShell() {
        // The live defect this CR closes: a mailed recovery link, an invite or a share link, loaded directly.
        for (path in listOf("/recover", "/s/x", "/tenant/accept")) {
            html(path).then().statusCode(200).body(containsString(FIXTURE_MARKER))
                .header("Content-Type", containsString("text/html"))
        }
    }

    @Test
    fun aHeadForADeepLinkIsAnsweredLikeItsGet() {
        val get = html("/recover")
        val head = given().header("Accept", HTML).`when`().head("/recover")
        assertEquals(200, head.statusCode)
        assertEquals(get.header("Content-Length"), head.header("Content-Length"))
        head.then().body(emptyString())
    }

    @Test
    fun aRequestThatDoesNotAskForHtmlIsNotAPage() {
        given().header("Accept", "application/json").`when`().get("/recover").then().statusCode(404)
        html("/recover").then().statusCode(200) // control: the same path, asking for HTML
    }

    @Test
    fun aMissingChunkStaysA404InsteadOfHtmlParsedAsScript() {
        given().header("Accept", "*/*").`when`().get("/assets/missing-x.js").then().statusCode(404)
        // Control: the fixture's real asset, same headers — so the 404 above is "missing", not "assets are broken".
        given().header("Accept", "*/*").`when`().get("/assets/app-fixture.js").then().statusCode(200)
    }

    @Test
    fun anUnknownApiPathIsNeverTheShell() {
        html("/api/no-such-endpoint").then().statusCode(404).body(not(containsString(FIXTURE_MARKER)))
        html("/api/setup").then().statusCode(200) // control: the API answers under the same Accept
    }

    @Test
    fun theServiceWorkersNamespaceIsNeverTheShell() {
        html("/__media/x").then().statusCode(404).body(not(containsString(FIXTURE_MARKER)))
        html("/recover").then().statusCode(200) // control: identical headers, a path outside it
    }

    @Test
    fun theShellRevalidatesAndTheHashedChunksStayImmutable() {
        for (path in listOf("/", "/index.html", "/recover")) {
            assertEquals("no-cache", html(path).header("Cache-Control"), path)
        }
        assertEquals("no-cache", given().`when`().get("/sw.js").header("Cache-Control"), "/sw.js")
        // Control: the split is real, not "everything is no-cache".
        val asset = given().`when`().get("/assets/app-fixture.js").header("Cache-Control")
        assertTrue(asset.contains("immutable"), "asset Cache-Control was $asset")
    }

    @Test
    fun theShellsResponsesCarryThePolicyAndTheApiDoesNot() {
        for (path in listOf("/", "/recover")) assertEquals(FIXTURE_POLICY, html(path).header(CSP), path)
        // A worker takes its policy from its own script's response, which is the whole point of this one.
        assertEquals(FIXTURE_POLICY, given().`when`().get("/sw.js").header(CSP), "/sw.js")
        assertNull(given().`when`().get("/api/setup").header(CSP), "the API is not the shell")
    }

    @Test
    fun thePlainHeadersGoOnTheShellAndOnTheApi() {
        for (response in listOf(html("/recover"), given().`when`().get("/api/setup"), html("/api/no-such-endpoint"))) {
            assertEquals("nosniff", response.header("X-Content-Type-Options"))
            assertEquals("strict-origin-when-cross-origin", response.header("Referrer-Policy"))
        }
    }

    @Test
    fun hstsIsOnByDefault() {
        // 180 days. Switched off by AppShellHstsOffSliceTest, which is this case's control.
        assertEquals("max-age=15552000", html("/recover").header("Strict-Transport-Security"))
        assertEquals("max-age=15552000", given().`when`().get("/api/setup").header("Strict-Transport-Security"))
    }

    @Test
    fun aSuspendedTenantsDomainGetsTheShellWhileItsApiRefuses() {
        // `/` is a real file, answered before JAX-RS; `/recover` is the fallback, answered after it — so a
        // pre-matching JAX-RS filter reaches the second and not the first. Both must load the same client, which
        // is what lets it show the suspension (ADR 0066: what `TenantSuspensionFilter` refuses is the API).
        // Control: the API on the same Host is refused by that filter, so the 200s are not a Host nobody resolved.
        val suspended = seedTenantIn(TenantEntity.SUSPENDED)
        for (path in listOf("/", "/recover")) {
            given().header("Accept", HTML).header("X-Forwarded-Host", suspended).`when`().get(path)
                .then().statusCode(200).body(containsString(FIXTURE_MARKER))
        }
        given().header("X-Forwarded-Host", suspended).`when`().get("/api/scopes")
            .then().statusCode(403).body("code", equalTo("tenant_suspended"))
    }

    /** A bare tenant row whose only job is to claim a domain; returns that domain. */
    private fun seedTenantIn(status: String): String {
        val domain = "shell-${UUID.randomUUID()}.example.com"
        seedTenant(domain = domain, status = status, tenantScope = false)
        return domain
    }

    private fun html(path: String): Response = given().header("Accept", HTML).`when`().get(path)

    companion object {
        /** What a browser sends on a navigation. */
        const val HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val CSP = "Content-Security-Policy"
        const val FIXTURE_MARKER = "app-shell-fixture"

        /** `src/test/resources/META-INF/resources/content-security-policy.txt`, without its trailing newline. */
        const val FIXTURE_POLICY =
            "default-src 'self'; frame-ancestors 'none'; connect-src 'self' https://api.pwnedpasswords.com; form-action 'self'"
    }
}
