package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The repository's `VERSION` file, read from disk (tests run from `backend/`), never through the build's
 * `PlatformMajor`: the assertions below hold the served major to the file itself, so a derivation that stops
 * deriving — a hand-set number anywhere between the two — is red here (ADR 0085 decision 1).
 */
internal val VERSION_FILE_MAJOR: Int = File("../VERSION").readText().trim().toInt()

/**
 * Slice test for the capability-discovery manifest on GET /api/setup (uc.platform.capability-discovery
 * discover-manifest, ADR 0032): it advertises `version { major, build }` + the active capability set, and stays
 * pre-auth, identity-free and identical for every caller (operator-blind-safe).
 *
 * **It runs on the default profile, and that is half its point** (the leak guard of cr-capability-register-recut's
 * D3): the wired [CapabilitySwitches] must be the production [StoredCapabilitySwitches], and with no switch stored
 * every advertised id must be served. Every gate test switches through that same bean ([CapabilitySwitchFixtures]),
 * so a test seam that came back — an `@Alternative` with a `@Priority` in the test fixtures, enabled in every
 * project that consumes them — turns the first assertion red.
 */
@QuarkusTest
class CapabilityManifestSliceTest {

    @Inject
    lateinit var switches: CapabilitySwitches

    @Test
    fun theDefaultProfileWiresTheProductionSwitchesAndServesEveryAdvertisedId() {
        assertEquals(
            StoredCapabilitySwitches::class.java, ClientProxy.unwrap(switches).javaClass,
            "the default profile must get the production CapabilitySwitches, never a test seam",
        )
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("version.major", equalTo(VERSION_FILE_MAJOR))
            .body("version.build", equalTo(1))
            .body("capabilities.id", equalTo(CapabilityIds.ADVERTISED))
    }

    @Test
    fun theAdvertisedMajorIsTheVersionFilesMajor() {
        // One major (ADR 0085 decision 1): the protocol major a client gates on is the release major in VERSION —
        // `0` before V1 is advertised as `0`, never floored — and still nothing more precise than the major.
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("version.major", equalTo(VERSION_FILE_MAJOR))
            .body("version.keySet()", equalTo(setOf("major", "build")))
    }

    @Test
    fun manifestCarriesNoIdentityAndIsIdenticalForEveryCaller() {
        @Suppress("UNCHECKED_CAST")
        val anon = given().`when`().get("/api/setup").then().statusCode(200)
            .extract().`as`(Map::class.java) as Map<String, Any>

        // No per-user data on the wire: no identity/user key at the root, and each capability entry is { id }.
        assertFalse(anon.containsKey("userId"), "the pre-auth manifest must carry no identity")
        assertFalse(anon.containsKey("identity"), "the pre-auth manifest must carry no identity")
        @Suppress("UNCHECKED_CAST")
        val caps = anon["capabilities"] as List<Map<String, Any>>
        caps.forEach { assertEquals(setOf("id"), it.keys, "a manifest capability is { id } only — no metadata/identity") }

        // Identical whether or not the caller authenticates (the manifest is instance-wide, not per-user).
        val bearer = bearer(seedUser())
        @Suppress("UNCHECKED_CAST")
        val authed = given().header("Authorization", bearer).`when`().get("/api/setup").then().statusCode(200)
            .extract().`as`(Map::class.java) as Map<String, Any>
        assertEquals(anon["capabilities"], authed["capabilities"], "the manifest must be identical for every caller")
    }
}
