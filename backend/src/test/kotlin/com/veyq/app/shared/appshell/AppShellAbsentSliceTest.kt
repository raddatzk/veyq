package com.veyq.app.shared.appshell

import com.veyq.app.shared.appshell.AppShellSliceTest.Companion.HTML
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **No shell on the classpath** — dev, where Vite serves the app, and the jar the CI e2e run builds (ADR 0069
 * Decision 3): the backend serves the API with the plain headers, and nothing falls back to anything.
 *
 * The fixture shell stays on the classpath (it is shared), so Quarkus' static handler still serves its files
 * here; what this profile removes is the shell's *reading* of them, which is the branch under test.
 */
@QuarkusTest
@TestProfile(AppShellAbsentSliceTest.NoShellProfile::class)
class AppShellAbsentSliceTest {

    class NoShellProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(NoAppShellFiles::class.java)
    }

    @Alternative
    @ApplicationScoped
    class NoAppShellFiles : AppShellFiles() {
        override fun read(name: String): ByteArray? = null
    }

    @Test
    fun withoutAShellADeepLinkIsA404AndCarriesNoPolicy() {
        val response = given().header("Accept", HTML).`when`().get("/recover")
        assertEquals(404, response.statusCode)
        assertNull(response.header("Content-Security-Policy"))
        // The plain headers are the API's, and do not depend on a shell.
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        assertEquals("strict-origin-when-cross-origin", response.header("Referrer-Policy"))
    }

    @Test
    fun theApiStillCarriesThePlainHeaders() {
        // Control: the booted app answers, so the 404 above is the missing fallback and not a dead app.
        val response = given().`when`().get("/api/setup")
        assertEquals(200, response.statusCode)
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        assertEquals("max-age=15552000", response.header("Strict-Transport-Security"))
    }
}
