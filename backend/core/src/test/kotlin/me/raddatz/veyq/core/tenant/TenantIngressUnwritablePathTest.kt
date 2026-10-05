package me.raddatz.veyq.core.tenant

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * **An instance whose ingress projection cannot be written must still serve.** The projector is a routing
 * convenience; `/api/setup` is how every client — and the admin surface's first-run setup — reaches the
 * instance at all, so the first must never be able to take down the second.
 *
 * This is not a hypothetical. Measured 2026-09-08 on a first-ever `docker compose up` of the production
 * stack: `/etc/traefik/dynamic` did not exist in the app image, so Docker seeded its named volume as
 * `root:root`, the app runs as uid 10001, and `TenantIngressProjector.writeAtomically` threw
 *
 *     java.nio.file.AccessDeniedException: /etc/traefik/dynamic/.tenants.yml<random>.tmp
 *
 * from `Files.createTempFile` — which sat one line **above** the `try` that exists precisely to swallow it.
 * Quarkus failed the startup event and the container restart-looped, so the documented production stack
 * never served a request on the one path every new operator takes.
 *
 * **Two independent halves, and the first is why this is its own class.** The unwritable directory is
 * configured before the application boots, so `onStartup` runs against it: if the projector still propagates,
 * this `@QuarkusTest` cannot start at all — the production symptom, reproduced.
 * [regenerateDoesNotPropagate] then covers the runtime path a tenant mutation takes, which degrades for the
 * same reason but through a different caller. Neither half implies the other: a fix that caught only around
 * the write would pass the second and fail the first.
 *
 * **Reading the result of that first half: do NOT trust the word PASS.** When the boot fails, Gradle attributes
 * the `Failed to start quarkus` error to whichever method the extension was constructing, and writes the
 * methods that never ran into the same XML as ordinary `<testcase>` elements with **no failure child** — so a
 * report of "3 tests, 1 failure" is really "3 tests, none of them executed". Measured against the pre-fix
 * projector, where two of these three read as passing. The signals that do distinguish are the task's exit code
 * and the per-method **durations**: a method that ran has one (0.024 / 0.169 / 0.007 s here), one that did not
 * shows `0.0` with an empty body. Anyone re-biting this class by reverting `writeAtomically` should check those,
 * not the status column.
 *
 * The ownership defect itself is fixed in the `Dockerfile` and held by
 * `scripts/validate_app_volume_ownership.py` — but only for the compose deployment that guard can read. ADR
 * 0064's Helm chart brings its own volume semantics, which is why degrading is worth asserting on its own.
 */
@QuarkusTest
@TestProfile(TenantIngressUnwritablePathTest.ProjectingIntoAnUnwritableDirectory::class)
// `chmod 0500` is not a permission boundary for root, and Windows has no POSIX mode at all — in both the
// directory stays writable and the test would assert nothing. Skipped rather than silently vacuous.
@DisabledOnOs(OS.WINDOWS)
class TenantIngressUnwritablePathTest {

    class ProjectingIntoAnUnwritableDirectory : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            // Runs before the application boots, which is what puts `onStartup` under test.
            Files.createDirectories(UNWRITABLE_DIR)
            Files.setPosixFilePermissions(UNWRITABLE_DIR, PosixFilePermissions.fromString("r-x------"))
            return mapOf(
                "veyq.tenant.ingress.dynamic-config-path" to UNWRITABLE_DIR.resolve("tenants.yml").toString(),
                "veyq.tenant.ingress.bootstrap-domain" to "bootstrap.example.com",
            )
        }
    }

    @Inject
    lateinit var projector: TenantIngressProjector

    @Test
    fun theInstanceStillServesItsUnauthenticatedSetupProbe() {
        // Reaching this line already proves the startup half — the class could not have booted otherwise. The
        // request is what makes the claim the operator cares about: not merely "the JVM came up" but "the
        // endpoint every client starts at answers", which is also this stack's compose healthcheck.
        given().get("/api/setup").then().statusCode(200)
    }

    @Test
    fun regenerateDoesNotPropagate() {
        // The runtime path: a tenant mutation calls this after its own transaction has committed. Throwing here
        // would fail an operation whose row is already written — the projection would take down the very
        // create-tenant call it exists to serve.
        assertDoesNotThrow { projector.regenerate() }
    }

    @Test
    fun theFailedProjectionLeavesNoTemporaryFileBehind() {
        // The cleanup in the catch runs in the state that got us there, so `deleteIfExists` can throw too. If it
        // escaped, the degrade would be undone by its own tidying — and this assertion would never be reached.
        projector.regenerate()

        assertFalse(
            Files.list(UNWRITABLE_DIR).use { it.findAny().isPresent },
            "nothing was creatable in this directory, so nothing may be left in it either",
        )
    }

    companion object {
        /** Under `build/`, so `gradlew clean` reclaims it; the mode is set by the profile, above. */
        val UNWRITABLE_DIR: Path = Path.of("build/test-tenant-ingress-unwritable")
    }
}
