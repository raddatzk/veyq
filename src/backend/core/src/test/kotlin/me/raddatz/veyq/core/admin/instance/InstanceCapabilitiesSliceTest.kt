package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.UNCONFIGURED_IN_TEST
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.DEVICE_LINKING
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * The Server Owner's capability switches (uc.admin.switch-capabilities, ADR 0088 decisions 5, 12 and 13) over the full
 * HTTP stack on the admin channel: the register with its catalog words, one entry's switch per write, and that a
 * write reaches the manifest, the gates and the key material at once — every "after" here is a second read in the same
 * boot, so a switch that needed a restart, or a cache the write never invalidated, is red. The H2 mem DB is JVM-shared,
 * so every case switches everything back on ([CapabilitySwitchFixtures.clear]).
 */
@QuarkusTest
class InstanceCapabilitiesSliceTest {

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "capabilities-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "capabilities-member@example.com", "MEMBER")
        CapabilitySwitchFixtures.clear()
    }

    @AfterEach
    fun forget() = CapabilitySwitchFixtures.clear()

    @Test
    fun theRegisterIsListedInCatalogOrderWithItsWordsAndEveryEntryOn() {
        val listed = owner().`when`().get(PATH).then().statusCode(200)
            .body("off", equalTo(CapabilityIds.ADVERTISED.map { false }))
            // every entry on but the music frame's, which this profile's deployment leaves unconfigured (ADR 0131)
            .body("effective", equalTo(CapabilityIds.ADVERTISED.map { it !in UNCONFIGURED_IN_TEST }))
            .extract().jsonPath()
        assertEquals(CapabilityIds.ADVERTISED, listed.getList<String>("id"))
        CapabilityIds.ADVERTISED.forEachIndexed { i, id ->
            val texts = CapabilityTexts.BY_ID.getValue(id)
            assertEquals(texts.title.en, listed.getString("[$i].title.en"), "$id title.en")
            assertEquals(texts.title.de, listed.getString("[$i].title.de"), "$id title.de")
            assertEquals(texts.purpose.en, listed.getString("[$i].purpose.en"), "$id purpose.en")
            assertEquals(texts.whenOff.de, listed.getString("[$i].whenOff.de"), "$id whenOff.de")
        }
    }

    @Test
    fun aSwitchOffLeavesTheManifestAndRefusesTheGateAndSwitchingOnRestoresBoth() {
        // Control, before: the manifest advertises it and its gate lets a pairing open.
        manifest().body("capabilities.id", hasItem(DEVICE_LINKING))
        openPairing().statusCode(204)

        put(DEVICE_LINKING, off = true).statusCode(200)
            .body("id", equalTo(DEVICE_LINKING)).body("off", equalTo(true)).body("effective", equalTo(false))
            .body("title.en", equalTo(CapabilityTexts.BY_ID.getValue(DEVICE_LINKING).title.en))
        manifest().body("capabilities.id", not(hasItem(DEVICE_LINKING)))
        openPairing().statusCode(422).body("code", equalTo("capability_disabled"))
        assertEquals(setOf(DEVICE_LINKING), storedIds())

        put(DEVICE_LINKING, off = false).statusCode(200).body("off", equalTo(false)).body("effective", equalTo(true))
        manifest().body("capabilities.id", hasItem(DEVICE_LINKING))
        openPairing().statusCode(204)
        assertEquals(emptySet<String>(), storedIds())
    }

    @Test
    fun aParentOffSwitchesItsChildrenOffAndTheirOwnSwitchesSurviveIt() {
        put(PUBLIC_SHARES_CONTRIBUTE, off = true).statusCode(200)
        put(PUBLIC_SHARES, off = true).statusCode(200)

        assertEquals(true to false, entry(PUBLIC_SHARES), "the parent switched off")
        // The drop box's own switch is on; the AND along the path takes it off.
        assertEquals(false to false, entry(PUBLIC_SHARES_DROP_BOX), "a child of a switched-off parent")
        assertEquals(true to false, entry(PUBLIC_SHARES_CONTRIBUTE), "a child switched off itself, under an off parent")
        manifest().body("capabilities.id", not(hasItem(PUBLIC_SHARES_DROP_BOX)))

        put(PUBLIC_SHARES, off = false).statusCode(200).body("effective", equalTo(true))
        assertEquals(false to true, entry(PUBLIC_SHARES_DROP_BOX), "the parent back on brings back the child that is on")
        assertEquals(true to false, entry(PUBLIC_SHARES_CONTRIBUTE), "and leaves the one switched off itself off")
        manifest().body("capabilities.id", hasItem(PUBLIC_SHARES_DROP_BOX))
            .body("capabilities.id", not(hasItem(PUBLIC_SHARES_CONTRIBUTE)))
    }

    @Test
    fun aDeviceSwitchWithholdsItsWrapsFromTheNextKeyMaterialReadAndSwitchingOnRestoresThem() {
        val member = seedUser()
        listOf("PASSWORD", "DEVICE", "DEVICE_KEY").forEach { persistWrap(member, it) }
        val auth = bearer(member)
        keyMaterial(auth).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE", "DEVICE_KEY")) // control

        put(REMEMBER_DEVICE_PASSWORDLESS, off = true).statusCode(200)
        keyMaterial(auth).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE"))

        put(REMEMBER_DEVICE, off = true).statusCode(200)
        keyMaterial(auth).body("wraps.method", containsInAnyOrder("PASSWORD"))

        put(REMEMBER_DEVICE, off = false).statusCode(200)
        put(REMEMBER_DEVICE_PASSWORDLESS, off = false).statusCode(200)
        keyMaterial(auth).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE", "DEVICE_KEY"))
    }

    @Test
    fun writingTheStateAnEntryHasChangesNothing() {
        put(PUBLIC_SHARES_DROP_BOX, off = true).statusCode(200)
        put(PUBLIC_SHARES_DROP_BOX, off = true).statusCode(200).body("off", equalTo(true))
        assertEquals(setOf(PUBLIC_SHARES_DROP_BOX), storedIds(), "a second switch-off keeps the first one's row")

        put(PUBLIC_SHARES_DROP_BOX, off = false).statusCode(200)
        put(PUBLIC_SHARES_DROP_BOX, off = false).statusCode(200).body("off", equalTo(false))
        assertEquals(emptySet<String>(), storedIds())
    }

    @Test
    fun twoSimultaneousSwitchOffsOfOneEntryBothAnswerAndStoreOneRow() {
        repeat(RACE_ROUNDS) {
            val answers = race(
                { put(DEVICE_LINKING, off = true).extract().statusCode() },
                { put(DEVICE_LINKING, off = true).extract().statusCode() },
            )
            assertEquals(listOf(200, 200), answers, "neither write may fail on the other's row")
            assertEquals(setOf(DEVICE_LINKING), storedIds())
            put(DEVICE_LINKING, off = false).statusCode(200)
        }
    }

    @Test
    fun anUnknownIdIsRefusedAndStoresNothing() {
        put("core.not-an-entry", off = true).statusCode(404).body("code", equalTo("capability_not_found"))
        put("core", off = true).statusCode(404).body("code", equalTo("capability_not_found")) // a root is no entry
        put("core.not-an-entry", off = false).statusCode(404)
        assertEquals(emptySet<String>(), storedIds())
    }

    @Test
    fun aWriteWithoutOffIsRefusedAndStoresNothing() {
        owner().contentType(ContentType.JSON).body("{}").`when`().put("$PATH/$DEVICE_LINKING")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(emptySet<String>(), storedIds())
    }

    @Test
    fun onlyTheServerOwnerReadsOrSwitches() {
        val member = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
        member.`when`().get(PATH).then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("$PATH/$DEVICE_LINKING").then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("$PATH/$DEVICE_LINKING").then().statusCode(401)
        assertEquals(emptySet<String>(), storedIds())
        manifest().body("capabilities.id", hasItem(DEVICE_LINKING))
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun put(id: String, off: Boolean): ValidatableResponse =
        owner().contentType(ContentType.JSON).body("""{"off":$off}""").`when`().put("$PATH/$id").then()

    /** An entry's (off, effective) as the list reads it. */
    private fun entry(id: String): Pair<Boolean, Boolean> {
        val listed = owner().`when`().get(PATH).then().statusCode(200).extract().jsonPath()
        val i = listed.getList<String>("id").indexOf(id)
        return listed.getBoolean("[$i].off") to listed.getBoolean("[$i].effective")
    }

    private fun manifest(): ValidatableResponse = given().`when`().get("/api/setup").then().statusCode(200)

    private fun openPairing(): ValidatableResponse =
        given().contentType(ContentType.JSON).body("""{"handle":"${Base64.getEncoder().encodeToString(Random.nextBytes(32))}"}""")
            .`when`().post("/api/pairing/open").then()

    private fun keyMaterial(auth: String): ValidatableResponse =
        given().header("Authorization", auth).`when`().get("/api/account/key-material").then().statusCode(200)

    private fun storedIds(): Set<String> =
        QuarkusTransaction.requiringNew().call { CapabilitySwitchEntity.listAll().mapTo(mutableSetOf()) { it.id } }

    private fun persistWrap(userId: String, method: String) {
        QuarkusTransaction.requiringNew().run {
            MasterKeyWrapEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.method = method
                wrappedKey = "d3JhcC0kbWV0aG9k"
                kdfParams = "a2RmLXBhcmFtcw=="
            }.persist()
        }
    }

    companion object {
        private const val PATH = "/api/instance/capabilities"
        private const val OWNER_ID = "b3e8b2c0-04a9-4898-bc38-b603c6ab00d5"
        private const val MEMBER_ID = "1ab0fef4-4c5f-458b-b8dd-09335bea9276"
    }
}
