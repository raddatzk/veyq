package com.veyq.core.appshell

import com.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The deployment switch (`veyq.http.hsts.enabled`, cc.security.csp): off means **absent**, on every response —
 * not an empty value and not `max-age=0`, which would be an instruction to forget, sent by a deployment that only
 * asked to stop instructing. The default-on case is [AppShellSliceTest.hstsIsOnByDefault].
 */
@QuarkusTest
@TestProfile(AppShellHstsOffSliceTest.HstsOffProfile::class)
class AppShellHstsOffSliceTest {

    class HstsOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("veyq.http.hsts.enabled" to "false")
    }

    @Test
    fun switchedOffHstsIsAbsentWhileTheOtherHeadersStay() {
        for (response in listOf(
            given().header("Accept", HTML).`when`().get("/recover"),
            given().`when`().get("/api/setup"),
        )) {
            assertNull(response.header("Strict-Transport-Security"))
            // Control: the header route still ran — the switch removed one header, not the whole hook.
            assertEquals("nosniff", response.header("X-Content-Type-Options"))
        }
    }
}
