package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.DEVICE_LINKING
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.GROUP_SPACE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PRIVATE_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * Proves a switched-off capability is enforced at every gate core holds (uc.platform.capability-discovery,
 * ADR 0032, ADR 0088): it vanishes from the manifest AND its endpoint answers `422 capability_disabled`. Each state is
 * reached through the test seam [SwitchableCapabilities], never through config — no built instance can switch an id
 * off until inbox #443.
 *
 * **Every negative has a positive control on the same predicate**, so "the call failed" can never pass for "the gate
 * refused": a sibling or parent that stays on reaches past the gate (the `404 node_not_found` of an unknown node, a
 * `201`, a `204`), or the same call succeeds once the switch is back on. The share gates are checked before any node
 * lookup, so an unknown node is enough to tell the two apart.
 */
@QuarkusTest
@TestProfile(CapabilitySwitchesProfile::class)
class CapabilityDisabledSliceTest {

    @Inject
    lateinit var capabilities: SwitchableCapabilities

    // --- shares: one leaf per role, ANDed with the parent ------------------------------------------------------------

    @Test
    fun aSwitchedOffParentDropsEveryShareIdAndRefusesADropBoxLink() {
        val auth = bearer(seedUser())
        capabilities.with(PUBLIC_SHARES) {
            given().`when`().get("/api/setup").then().statusCode(200)
                .body("capabilities.id", not(hasItem(PUBLIC_SHARES)))
                .body("capabilities.id", not(hasItem(PUBLIC_SHARES_CONTRIBUTE)))
                .body("capabilities.id", not(hasItem(PUBLIC_SHARES_DROP_BOX)))
                .body("capabilities.id", hasItem(GROUP_SPACE)) // a sibling entry stays
            // WRITE_ONLY's own leaf is on; the AND along the path refuses it.
            createShare(auth, "WRITE_ONLY").then().statusCode(422).body("code", equalTo("capability_disabled"))
        }
        // Control, the same call with the parent back on: past the gate, to the node lookup.
        createShare(auth, "WRITE_ONLY").then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun aSwitchedOffChildRefusesItsRoleAndLeavesTheParentsRoleThrough() {
        val auth = bearer(seedUser())
        capabilities.with(PUBLIC_SHARES_CONTRIBUTE) {
            given().`when`().get("/api/setup").then().statusCode(200)
                .body("capabilities.id", not(hasItem(PUBLIC_SHARES_CONTRIBUTE)))
                .body("capabilities.id", hasItems(PUBLIC_SHARES, PUBLIC_SHARES_DROP_BOX))
            createShare(auth, "READ_WRITE").then().statusCode(422).body("code", equalTo("capability_disabled"))
            // Control: READ needs only the parent, still on → the gate passes and the unknown node 404s.
            createShare(auth, "READ").then().statusCode(404).body("code", equalTo("node_not_found"))
        }
    }

    @Test
    fun aSwitchedOffDropBoxRefusesWriteOnlyAndLeavesReadWriteThrough() {
        val auth = bearer(seedUser())
        capabilities.with(PUBLIC_SHARES_DROP_BOX) {
            createShare(auth, "WRITE_ONLY").then().statusCode(422).body("code", equalTo("capability_disabled"))
            createShare(auth, "READ_WRITE").then().statusCode(404).body("code", equalTo("node_not_found"))
        }
    }

    // --- wraps: the gate splits by method ----------------------------------------------------------------------------

    @Test
    fun passwordlessOffRefusesADeviceKeyWrapAndStillEnrolsADeviceWrap() {
        val auth = bearer(seedUser())
        capabilities.with(REMEMBER_DEVICE_PASSWORDLESS) {
            addWrap(auth, "DEVICE_KEY").then().statusCode(422).body("code", equalTo("capability_disabled"))
            addWrap(auth, "DEVICE").then().statusCode(201) // control: its parent is on
        }
    }

    @Test
    fun rememberDeviceOffRefusesBothMethods() {
        val auth = bearer(seedUser())
        capabilities.with(REMEMBER_DEVICE) {
            addWrap(auth, "DEVICE").then().statusCode(422).body("code", equalTo("capability_disabled"))
            addWrap(auth, "DEVICE_KEY").then().statusCode(422).body("code", equalTo("capability_disabled")) // the AND
        }
        addWrap(auth, "DEVICE_KEY").then().statusCode(201) // control: the same call with the parent back on
    }

    // --- group spaces: creation only (ADR 0088 decision 5) ------------------------------------------------------------

    @Test
    fun groupSpaceOffRefusesANewGroupAndKeepsAnExistingOneUsable() {
        val owner = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        seedTenant(id = tenantId, domain = domain, owner = owner)
        val existing = createGroup(owner, domain).then().statusCode(201).extract().path<String>("scopeId")

        capabilities.with(GROUP_SPACE) {
            createGroup(owner, domain).then().statusCode(422).body("code", equalTo("capability_disabled"))
            // Control, and the feature-switch rule: renaming the group that exists keeps working.
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body("""{"encryptedName":"$RENAMED","nameEpoch":0}""")
                .`when`().post("/api/scopes/$existing/name")
                .then().statusCode(204)
        }
    }

    // --- device linking: every /api/pairing endpoint -----------------------------------------------------------------

    @Test
    fun deviceLinkingOffRefusesEveryPairingEndpointAndStopsAPairingInFlight() {
        val auth = bearer(seedUser())
        val inFlight = handle()
        open(inFlight).then().statusCode(204)
        answer(inFlight).then().statusCode(204)

        capabilities.with(DEVICE_LINKING) {
            // Control: the instance still answers, and linking is independent of remembering (decision 11).
            given().`when`().get("/api/setup").then().statusCode(200)
                .body("capabilities.id", not(hasItem(DEVICE_LINKING)))
                .body("capabilities.id", hasItems(REMEMBER_DEVICE, REMEMBER_DEVICE_PASSWORDLESS))
            // One assertion over all five, so a red names every endpoint that let the call through, not only the first.
            val fresh = handle()
            val answers = mapOf(
                "open" to open(fresh), "answer" to answer(fresh), "delegate" to delegate(auth, inFlight),
                "seal" to seal(auth, inFlight), "collect" to collect(inFlight),
            ).mapValues { (_, r) -> "${r.statusCode} ${r.jsonPathOrNull("code")}" }
            assertEquals(answers.mapValues { "422 capability_disabled" }, answers)
        }

        // Control, every endpoint on the same channel with linking back on: the refusals were the gate, and they wrote
        // nothing — the channel the switch stopped carries on from where it was.
        delegate(auth, inFlight).then().statusCode(200)
        seal(auth, inFlight).then().statusCode(204)
        collect(inFlight).then().statusCode(200).body("envelope", equalTo(ENVELOPE))
        val next = handle()
        open(next).then().statusCode(204)
        answer(next).then().statusCode(204)
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private fun createShare(auth: String, role: String): Response {
        val keys = if (role == "WRITE_ONLY") {
            """"sealedDropPublicKey":"$DROP_PUBLIC_KEY","sealedDropPrivateKey":"$DROP_PRIVATE_KEY""""
        } else {
            """"wrap":"$WRAP""""
        }
        return given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${UUID.randomUUID()}","role":"$role",$keys,"sealedName":"$SEALED_NAME",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
    }

    private fun addWrap(auth: String, method: String): Response =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"method":"$method","wrappedKey":"$WRAPPED_KEY","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")

    private fun createGroup(owner: String, domain: String): Response =
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$GROUP_NAME","wrappedGrant":"$GROUP_GRANT",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/scopes/groups")

    /** The error code of a response, or null for one without a JSON body (a 204). */
    private fun Response.jsonPathOrNull(key: String): String? =
        body.asString().takeIf { it.isNotBlank() }?.let { runCatching { jsonPath().getString(key) }.getOrNull() }

    private fun handle(): String = Base64.getEncoder().encodeToString(Random.nextBytes(32))

    private fun open(handle: String): Response =
        given().contentType(ContentType.JSON).body("""{"handle":"$handle"}""").`when`().post("/api/pairing/open")

    private fun answer(handle: String): Response =
        given().contentType(ContentType.JSON).body("""{"handle":"$handle","answer":"$ANSWER"}""").`when`().post("/api/pairing/answer")

    private fun delegate(auth: String, handle: String): Response =
        given().header("Authorization", auth).contentType(ContentType.JSON).body("""{"handle":"$handle"}""")
            .`when`().post("/api/pairing/delegate")

    private fun seal(auth: String, handle: String): Response =
        given().header("Authorization", auth).contentType(ContentType.JSON).body("""{"handle":"$handle","envelope":"$ENVELOPE"}""")
            .`when`().post("/api/pairing/seal")

    private fun collect(handle: String): Response =
        given().contentType(ContentType.JSON).body("""{"handle":"$handle"}""").`when`().post("/api/pairing/collect")

    companion object {
        private const val WRAP = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
        private const val SEALED_NAME = "c2VhbGVkLW5hbWU="
        private const val WRAPPED_KEY = "ZGV2aWNlLXdyYXA="
        private const val KDF = "a2RmLXBhcmFtcw=="
        private const val GROUP_NAME = "c2VhbGVkLWdyb3VwLW5hbWU="
        private const val GROUP_GRANT = "Z3JvdXAtc2NvcGUtZ3JhbnQ="
        private const val RENAMED = "cmVuYW1lZC1uYW1l"
        private const val ANSWER = "ZXBoZW1lcmFsLXRyYW5zcG9ydC1wdWJsaWMta2V5"
        private const val ENVELOPE = "c2VhbGVkLWdyYW50LWNpcGhlcnRleHQ="
    }
}
