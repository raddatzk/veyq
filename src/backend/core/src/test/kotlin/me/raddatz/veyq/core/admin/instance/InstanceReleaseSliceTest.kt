package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.platform.capabilities.VERSION_FILE_MAJOR
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Slice tests for the Server Owner's read of the running release (uc.admin.identify-instance-release,
 * ADR 0057) over the full HTTP stack, on an instance that **is** stamped.
 *
 * The stamped profile is what makes the negative worth anything. `the-release-version-never-appears-pre-auth`
 * asserts `GET /api/setup` carries no release version — and on an unstamped instance that assertion is
 * satisfied by there being no value to leak in the first place. Running it beside a live value, and asserting
 * on the **specific observable** (that exact string is absent from the whole pre-auth body, while the owner
 * read returns it), is what turns it from a tautology into a check. The owner read in the first test is that
 * negative's positive control on the same predicate.
 *
 * The unstamped half — A1, absence rather than a placeholder — needs the opposite configuration and therefore
 * its own profile: [InstanceReleaseUnstampedSliceTest].
 */
@QuarkusTest
@TestProfile(InstanceReleaseSliceTest.Stamped::class)
class InstanceReleaseSliceTest {

    /**
     * A release version no other fixture could produce by accident, so
     * [releaseVersionNeverAppearsOnThePreAuthSurface]'s "this string is nowhere in the body" is about this
     * value and not about a coincidence.
     */
    class Stamped : QuarkusTestProfile {
        // The check's rhythm at lane L1's 5 s, so its back-off has ticks to skip ([ReleaseCheckStampedSliceTest]):
        // at the daily default every tick asks. The %test scheduler is halted, so no tick fires on its own.
        override fun getConfigOverrides() = mapOf(
            "veyq.release.version" to STAMPED_RELEASE,
            "veyq.release-check.every" to "5s",
        )
    }

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "release-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "release-member@example.com", "MEMBER")
    }

    @Test
    fun serverOwnerReadsTheRunningRelease() {
        givenAdminChannel().header("Authorization", ownerToken())
            .`when`().get("/api/instance/release")
            .then().statusCode(200)
            .body("release", equalTo(STAMPED_RELEASE))
    }

    @Test
    fun releaseVersionNeverAppearsOnThePreAuthSurface() {
        val body = given().`when`().get("/api/setup").then().statusCode(200).extract().asString()

        // The specific observable, not "it failed": the value the owner just read is nowhere in the pre-auth
        // response, and the version it DOES advertise is the platform's major alone (ADR 0085 decision 1) — the
        // precision of the release never goes pre-auth (ADR 0057 §4).
        assertFalse(
            body.contains(STAMPED_RELEASE),
            "the release version must not appear on the pre-auth surface — it is what maps an instance to a " +
                "list of known vulnerabilities, and the capability manifest already tells any visitor the " +
                "feature set (ADR 0057 §4). Body was: $body",
        )
        assertFalse(body.contains("\"release\""), "GET /api/setup's response shape does not grow a release field")
        given().`when`().get("/api/setup").then().statusCode(200)
            .body("version.major", equalTo(VERSION_FILE_MAJOR))
            .body("version.build", equalTo(1))
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefused() {
        givenAdminChannel().header("Authorization", memberToken())
            .`when`().get("/api/instance/release")
            .then().statusCode(403)
            .body("code", equalTo("not_server_owner"))
    }

    @Test
    fun anUnauthenticatedCallerIsRefused() {
        givenAdminChannel().`when`().get("/api/instance/release")
            .then().statusCode(401)
            .body("code", equalTo("authentication_failed"))
    }

    private fun ownerToken() = bearer(OWNER_ID)

    private fun memberToken() = bearer(MEMBER_ID)

    companion object {
        const val STAMPED_RELEASE = "77.13"
        private const val OWNER_ID = "b0b898cf-8d16-40db-a849-14a07711dfe9"
        private const val MEMBER_ID = "538b2928-3121-43c2-a0d7-41c5b0dda573"
    }
}

/**
 * A1 — a build the release pipeline never stamped says exactly that. A locally built image or a `quarkusDev`
 * run leaves `VEYQ_VERSION` unset, which `application.properties` resolves to empty; the read reports the
 * absence rather than `""` or a synthesised number, because every consumer of this value uses it to choose an
 * image to start and a fabricated version is the one an operator would try to roll back *to*.
 *
 * The override is set explicitly rather than relied upon: `veyq.release.version` binds to the ambient
 * `VEYQ_VERSION`, so a developer who happens to export it would otherwise turn this test green-or-red by
 * accident.
 */
@QuarkusTest
@TestProfile(InstanceReleaseUnstampedSliceTest.Unstamped::class)
class InstanceReleaseUnstampedSliceTest {

    class Unstamped : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("veyq.release.version" to "")
    }

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "unstamped-owner@example.com", "SERVER_OWNER")
    }

    @Test
    fun anUnstampedBuildReportsAbsenceNotANumber() {
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release")
            .then().statusCode(200)
            // The field is PRESENT and null — "no release stamp", not "the read is missing".
            .body("$", org.hamcrest.Matchers.hasKey("release"))
            .body("release", nullValue())
    }

    companion object {
        private const val OWNER_ID = "7e747709-19e0-4aac-9e73-9fd7a3603099"
    }
}
