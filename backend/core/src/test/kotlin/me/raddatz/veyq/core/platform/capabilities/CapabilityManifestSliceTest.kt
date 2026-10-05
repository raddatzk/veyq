package com.veyq.core.platform.capabilities

import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Slice test for the capability-discovery manifest on GET /api/setup (uc.platform.capability-discovery
 * discover-manifest, cr-0103, ADR 0032): it advertises `version { major, build }` + the active capability set,
 * and stays pre-auth, identity-free and identical for every caller (operator-blind-safe). Runs on the default
 * profile (nothing disabled ⇒ every seeded capability is active).
 */
@QuarkusTest
class CapabilityManifestSliceTest {

    @Test
    fun advertisesVersionAndTheActiveCapabilitySet() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("version.major", equalTo(1))
            .body("version.build", equalTo(1))
            .body("capabilities.id", hasItem("sharing.public-links"))
            .body("capabilities.id", hasItem("sharing.uploader-links"))
            .body("capabilities.id", hasItem("spaces.groups"))
            .body("capabilities.id", hasItem("account.device-trust"))
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
