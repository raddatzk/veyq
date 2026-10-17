package me.raddatz.veyq.core.admin.setup

import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_GUEST_WRITE_RECORD
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SetupServerSliceTest {

    private fun ownerBody(
        email: String = "kevin@example.com",
        authPublicKey: String = "ZWQyNTUxOS1hdXRoLXB1Yg==",
        kdfParams: String = "AAAAAwAAgAAAAAABAAAAIHNhbHQtZXhhbXBsZS0xNmJ5dGVz",
        kemPublicKey: String = KEM_PUBLIC_KEY,
        operatingMode: String? = "ORGANISATION",
    ) = """
        {"email":"$email","suiteId":"v1",
         "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
         "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
         "authPublicKey":"$authPublicKey",
         "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"$kdfParams"}],
         "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5",
         "moderationEpoch":{"kemPublicKey":"$kemPublicKey","signPublicKey":"$SIGN_PUBLIC_KEY","sealedGrant":"c2VhbGVkLWdyYW50"}
         ${modeField(operatingMode)}}
    """.trimIndent()

    /** The `operatingMode` member, or nothing — a request without one is what [rejectsASetupWithoutAnOperatingMode] sends. */
    private fun modeField(mode: String?): String = if (mode == null) "" else ",\"operatingMode\":\"$mode\""

    @Test
    @Order(1)
    fun setupRequiredInitially() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(true))
            .body("suites[0]", equalTo("v1"))
    }


    /**
     * uc.admin.admin-channel `setup-answers-only-on-the-admin-channel`: on a fresh instance the public port answers
     * setup as an unknown route and creates nothing — the first caller after `up -d` who is not the operator finds
     * no door. Its positive control on the same predicate is [createsOwner], the identical body on the admin
     * listener answering 201; that it can is itself the proof nothing was created here.
     */
    @Test
    @Order(2)
    fun setupIsAnUnknownRouteOnThePublicPort() {
        given().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(404)
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(true))
    }

    @Test
    @Order(3)
    fun rejectsNonBase64Key() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(authPublicKey = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    /** A moderation epoch that is no public bundle refuses the whole setup: no owner, no epoch, still not set up. */
    @Test
    @Order(3)
    fun rejectsAMalformedModerationEpoch() {
        ModerationTestKit.reset()
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(kemPublicKey = "c2hvcnQ="))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        given().`when`().get("/api/setup").then().body("setupRequired", equalTo(true))
        given().`when`().get("/api/moderation/key").then().statusCode(404)
    }

    /**
     * uc.admin.setup-server `setup-stores-the-operating-mode`: no instance completes setup without a mode (ADR 0093
     * § 14) — a request without one is refused at the boundary and creates nothing. Its positive control is
     * [createsOwner], the same body with a mode, answering 201 and storing it.
     */
    @Test
    @Order(3)
    fun rejectsASetupWithoutAnOperatingMode() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(operatingMode = null))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(operatingMode = "HOUSEHOLD"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        given().`when`().get("/api/setup").then().body("setupRequired", equalTo(true))
    }

    @Test
    @Order(4)
    fun createsOwner() {
        ModerationTestKit.reset()
        QuarkusTransaction.requiringNew().run { OperatorDetailsEntity.deleteAll() }
        given().`when`().get("/api/moderation/key").then().statusCode(404)
        val userId: String = givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(operatingMode = "PRIVATE"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(201)
            .body("userId", notNullValue())
            .extract().path("userId")
        // Rotation, change-password and recovery all verify against this key (ADR 0060 Decision 2); an owner
        // born without it could do none of the three.
        assertEquals(
            "c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5",
            QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(userId)?.secretKeyProofPublicKey },
        )
        // Setup minted epoch 1 (ADR 0099 § 3): reporting is on from the first day — the key a report seals to is
        // published at once — with exactly one grant, the Server Owner's, who is its first, accepted moderator.
        given().`when`().get("/api/moderation/key").then().statusCode(200)
            .body("epoch", equalTo(1)).body("kemPublicKey", equalTo(KEM_PUBLIC_KEY)).body("signPublicKey", equalTo(SIGN_PUBLIC_KEY))
        QuarkusTransaction.requiringNew().run {
            assertEquals(listOf(userId to 1), ModerationGrantEntity.listAll().map { it.moderatorId to it.epoch })
            assertEquals(true, ModeratorEntity.findById(userId)?.accepted)
            // The mode the Server Owner chose is stored with the owner, in the same transaction (ADR 0093 § 14).
            assertEquals("PRIVATE", OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.operatingMode)
            // …and its preset set with it (ADR 0088 decision 14): the guest-write record is off in private (ADR 0093 § 14).
            assertEquals(listOf(PUBLIC_SHARES_GUEST_WRITE_RECORD), CapabilitySwitchEntity.listAll().map { it.id })
        }
        // The switch row is shared H2 state: every later class starts with every entry on (CapabilitySwitchFixtures).
        CapabilitySwitchFixtures.clear()
    }

    @Test
    @Order(5)
    fun setupNoLongerRequired() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(false))
    }

    @Test
    @Order(6)
    fun rejectsSecondOwner() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(409)
            .body("code", equalTo("setup_already_complete"))
    }

    @Test
    @Order(7)
    fun rejectsInvalidEmail() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(email = "not-an-email"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    // Boundary validation runs before the single-owner check (proven by Order 7 returning 400 after setup
    // completed), so these negative cases stay 400 regardless of test order.

    @Test
    @Order(8)
    fun rejectsNonBase64KdfParams() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(kdfParams = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(9)
    fun rejectsOversizedKdfParams() {
        // > 256 chars of valid base64 alphabet → trips @Size(max = 256) (kdfParams added in phase 3a)
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(kdfParams = "A".repeat(260)))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }


    @Test
    @Order(11)
    fun rejectsUnknownSuiteId() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody().replace("\"suiteId\":\"v1\"", "\"suiteId\":\"v2\""))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(12)
    fun rejectsEmptyMasterKeyWraps() {
        // minItems: 1 (the PASSWORD floor) — an owner with no wraps must not be accepted.
        givenAdminChannel().contentType(ContentType.JSON)
            .body(ownerBody().replace(Regex("\"masterKeyWraps\":\\[.*?\\]"), "\"masterKeyWraps\":[]"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(13)
    fun rejectsNonBase64WrappedKeyInsideWrap() {
        // cascaded (@Valid) validation reaches the wrap's own fields, not just the top-level keys.
        givenAdminChannel().contentType(ContentType.JSON)
            .body(ownerBody().replace("d3JhcHBlZC1tYXN0ZXIta2V5", "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(14)
    fun rejectsMissingAuthPublicKey() {
        val body = """
            {"email":"kevin@example.com","suiteId":"v1",
             "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
             "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
             "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5"}]}
        """.trimIndent()
        givenAdminChannel().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(15)
    fun rejectsMissingSecretKeyProofPublicKey() {
        // Required, not optional: an account born without its proof key could never rotate, change its password
        // or recover (ADR 0054 § Consequences), so it is refused at birth rather than discovered at the worst time.
        val body = ownerBody().replace(Regex(""",\s*"secretKeyProofPublicKey":"[^"]*""""), "")
        check("secretKeyProofPublicKey" !in body) { "the fixture must actually drop the field" }
        givenAdminChannel().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    // --- a create's loser re-asks the replay question (developer-styleguide §12, the insert half) ---

    /**
     * A double-submitted setup: both requests read "not set up" before either commits, and the loser used to die on
     * the setup row's primary key (or the owner email's unique index) with a 500. It must answer what a replay gets.
     * The class's ordering has completed setup by now, so each round takes the setup row away and the last create
     * puts it back.
     */
    @Test
    @Order(16)
    fun concurrentSetupsCreateOneOwnerAndTheLoserAnswersAsAReplay() {
        repeat(RACE_ROUNDS) { round ->
            clearSetupRow()
            val email = "race-$round-${UUID.randomUUID()}@example.com"
            val answers = race({ createOwner(email) }, { createOwner(email) })
            assertWinnerAndLoser(201, 409 to "setup_already_complete", answers, "round $round")
            assertEquals(1L, setupRows(), "round $round: one setup row, got answers $answers")
        }
        // Control: a fresh setup still succeeds, so a site that refuses everything cannot pass. It leaves the
        // instance set up, as the class found it.
        clearSetupRow()
        assertEquals(201 to null, createOwner("race-control-${UUID.randomUUID()}@example.com"))
    }

    /**
     * The re-ask answers "taken" only when the replay question says so: a commit that fails on a DIFFERENT unique
     * index — here the owner email, taken by [createsOwner], while setup is not complete — stays a 500, the answer a
     * bug should have, and writes nothing.
     */
    @Test
    @Order(17)
    fun aCommitFailureTheReplayQuestionDoesNotExplainStaysAServerError() {
        clearSetupRow()
        try {
            assertEquals(500, createOwner("kevin@example.com").first)
            assertEquals(0L, setupRows(), "the failed create wrote no setup row")
        } finally {
            assertEquals(201 to null, createOwner("restore-${UUID.randomUUID()}@example.com"))
        }
    }

    private fun createOwner(email: String): Pair<Int, String?> =
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(email = email))
            .`when`().post("/api/setup/owner").then().extract().answer()

    /** Take setup back as if it never ran — and with it the epoch 1 it minted, which a second setup mints again. */
    private fun clearSetupRow() {
        QuarkusTransaction.requiringNew().run { ServerSetup.deleteAll() }
        ModerationTestKit.reset()
    }

    private fun setupRows(): Long = QuarkusTransaction.requiringNew().call { ServerSetup.count() }

    private companion object {
        /** A public bundle of the right sizes (1216 + 32 bytes): the server stores and serves it, never uses it. */
        val KEM_PUBLIC_KEY: String = java.util.Base64.getEncoder().encodeToString(ByteArray(1216) { (it % 251).toByte() })
        val SIGN_PUBLIC_KEY: String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    }
}
