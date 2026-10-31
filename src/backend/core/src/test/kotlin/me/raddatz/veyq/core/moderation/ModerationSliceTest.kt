package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit.EpochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asOwner
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.moderation.ModerationTestKit.sealedGrant
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The roster, the key, the role and the proof over the full HTTP stack (ADR 0099 §§ 1–5, uc.moderation.appoint-moderators,
 * uc.moderation.serve-as-moderator). Every refusal has its positive control on the same predicate: the same session
 * with a valid proof, the same mint with the missing grant, the same challenge used once.
 */
@QuarkusTest
class ModerationSliceTest {

    @Inject
    lateinit var handler: ModerationHandler

    private lateinit var key: EpochKey
    private lateinit var deputy: String
    private lateinit var deputyAddress: String

    @BeforeEach
    fun seed() {
        ModerationTestKit.reset()
        ensureUser(OWNER, "moderation-owner@example.com", "SERVER_OWNER")
        key = epochKey()
        deputy = UUID.randomUUID().toString()
        deputyAddress = "deputy-$deputy@example.com"
        seedUser(id = deputy, email = deputyAddress)
    }

    // ── turning on, appointing ───────────────────────────────────────────────────────────────────

    @Test
    fun moderationIsOffUntilTheServerOwnerMintsEpochOne() {
        given().get("/api/moderation/key").then().statusCode(404).body("code", equalTo("moderation_off"))
        asOwner(OWNER).get("/api/instance/moderation").then().statusCode(200).body("enabled", equalTo(false))
            .body("serverOwner.identityPublicKey", equalTo(IDENTITY_PUBLIC_KEY))
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        given().get("/api/moderation/key").then().statusCode(200)
            .body("epoch", equalTo(1)).body("signPublicKey", equalTo(key.signPublicKey))
        asOwner(OWNER).get("/api/instance/moderation").then().statusCode(200)
            .body("enabled", equalTo(true)).body("currentEpoch", equalTo(1))
            .body("moderators", hasSize<Any>(1))
            .body("moderators[0].accountId", equalTo(OWNER)).body("moderators[0].state", equalTo("ACCEPTED"))
            .body("moderators[0].holdsCurrentEpoch", equalTo(true))
        // A second epoch 1 is refused: only a mint makes the next epoch.
        val again = QuarkusTransaction.requiringNew().call {
            handler.enable(OWNER, epochKey().kemPublicKey, key.signPublicKey, IDENTITY_PUBLIC_KEY, sealedGrant())
        }
        assertEquals(ModerationOutcome.Refused(ModerationFailures.moderationEnabled), again)
    }

    @Test
    fun epochOneSealedToAnotherIdentityIsRefusedAndStoresNothing() {
        val other = QuarkusTransaction.requiringNew().call {
            handler.enable(OWNER, key.kemPublicKey, key.signPublicKey, "b3RoZXItaWRlbnRpdHk=", sealedGrant())
        }
        assertEquals(ModerationOutcome.Refused(ModerationFailures.identityChanged), other)
        given().get("/api/moderation/key").then().statusCode(404)
    }

    @Test
    fun anUnknownAddressIsRefusedAndAPendingAppointmentIsIdempotent() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asOwner(OWNER).body(mapOf("address" to "nobody-${UUID.randomUUID()}@example.com"))
            .post("/api/instance/moderation/moderators").then().statusCode(404).body("code", equalTo("account_not_found"))
        asOwner(OWNER).get("/api/instance/moderation").then().body("moderators", hasSize<Any>(1))
        repeat(2) {
            asOwner(OWNER).body(mapOf("address" to deputyAddress.uppercase())).post("/api/instance/moderation/moderators")
                .then().statusCode(200).body("moderators", hasSize<Any>(2))
                .body("moderators.find { it.accountId == '$deputy' }.state", equalTo("PENDING"))
                .body("moderators.find { it.accountId == '$deputy' }.identityPublicKey", nullValue())
        }
        assertEquals(1L, events(ModerationEvent.APPOINTED, deputy))
        asUser(deputy).get("/api/moderation/appointment").then().statusCode(200).body("state", equalTo("PENDING"))
    }

    @Test
    fun appointingBeforeModerationIsOnIsRefused() {
        asOwner(OWNER).body(mapOf("address" to deputyAddress)).post("/api/instance/moderation/moderators")
            .then().statusCode(409).body("code", equalTo("moderation_off"))
    }

    @Test
    fun aMemberIsNotTheServerOwnerOnTheRoster() {
        asOwner(deputy).get("/api/instance/moderation").then().statusCode(403).body("code", equalTo("not_server_owner"))
    }

    // ── role and proof ───────────────────────────────────────────────────────────────────────────

    @Test
    fun acceptingGrantsTheRoleAndAPendingAppointeeHasNone() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asOwner(OWNER).body(mapOf("address" to deputyAddress)).post("/api/instance/moderation/moderators").then().statusCode(200)
        asUser(deputy).get("/api/moderation/grants").then().statusCode(403).body("code", equalTo("not_moderator"))
        asUser(deputy).post("/api/moderation/appointment/acceptance").then().statusCode(200).body("state", equalTo("ACCEPTED"))
            .body("awaitingKey", equalTo(true))
        asUser(deputy).get("/api/moderation/grants").then().statusCode(200)
            .body("currentEpoch", equalTo(1)).body("epochs[0].sealedGrant", nullValue())
        assertEquals(1L, events(ModerationEvent.ACCEPTED, deputy))
        asUser(UUID.randomUUID().toString().also { seedUser(id = it) }).post("/api/moderation/appointment/acceptance")
            .then().statusCode(404).body("code", equalTo("not_appointed"))
    }

    @Test
    fun aStolenSessionWithoutTheKeyIsRefusedEveryKeyProvedOperationAndTheSameSessionWithItPasses() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        // No proof at all, a proof by another key, then the control: the same session with the real key.
        asUser(OWNER).get("/api/moderation/deliveries").then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", epochKey())).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        asUser(OWNER).delete("/api/moderation/appointment").then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        asUser(OWNER).contentType(ContentType.JSON).body(deliveryBody(1)).put("/api/moderation/deliveries/$deputy")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(200).body("deliveries", hasSize<Any>(0))
    }

    @Test
    fun aProofForAnotherOperationIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asUser(OWNER).headers(proof(OWNER, "stepDownAsModerator", key)).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        // And the other way round: a proof for the harmless read does not step anyone down.
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).delete("/api/moderation/appointment")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(200)
    }

    @Test
    fun aReplayedChallengeIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        val headers = proof(OWNER, "listPendingModerationDeliveries", key)
        asUser(OWNER).headers(headers).get("/api/moderation/deliveries").then().statusCode(200)
        asUser(OWNER).headers(headers).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
    }

    @Test
    fun anotherAccountsChallengeOrAnExpiredOneIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, deputyAddress, deputy)
        // The deputy's challenge, signed with the right key, presented by the owner: bound to the deputy.
        val theirs = proof(deputy, "listPendingModerationDeliveries", key)
        asUser(OWNER).headers(theirs).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        val stale = proof(OWNER, "listPendingModerationDeliveries", key)
        QuarkusTransaction.requiringNew().run {
            ModerationChallengeEntity.update("expiresAt = ?1", Instant.now().minus(1, ChronoUnit.SECONDS))
        }
        asUser(OWNER).headers(stale).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
    }

    @Test
    fun aRemovedModeratorsSessionHasNoRoleAndTheSameProofIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, deputyAddress, deputy)
        deliverAsOwner(deputy, 1)
        asUser(deputy).headers(proof(deputy, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(200)
        val prepared = proof(deputy, "listPendingModerationDeliveries", key)
        asOwner(OWNER).delete("/api/instance/moderation/moderators/$deputy").then().statusCode(200)
            .body("moderators", hasSize<Any>(1)).body("rotationDueSince", notNullValue())
        asUser(deputy).headers(prepared).get("/api/moderation/deliveries")
            .then().statusCode(403).body("code", equalTo("not_moderator"))
        asUser(deputy).post("/api/moderation/challenge").then().statusCode(403).body("code", equalTo("not_moderator"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { ModerationGrantEntity.count("moderatorId", deputy) })
    }

    // ── mint ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMintMissingOneModeratorIsRefusedAndTheCompleteOnePasses() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, deputyAddress, deputy)
        val next = epochKey()
        asOwner(OWNER).body(mintBody(next, listOf(OWNER))).post("/api/instance/moderation/epochs")
            .then().statusCode(409).body("code", equalTo("moderation_grants_incomplete"))
        asOwner(OWNER).body(mintBody(next, listOf(OWNER, deputy, deputy))).post("/api/instance/moderation/epochs")
            .then().statusCode(409).body("code", equalTo("moderation_grants_incomplete"))
        given().get("/api/moderation/key").then().body("epoch", equalTo(1))
        asOwner(OWNER).body(mintBody(next, listOf(OWNER, deputy))).post("/api/instance/moderation/epochs")
            .then().statusCode(200).body("currentEpoch", equalTo(2)).body("rotationDueSince", nullValue())
        given().get("/api/moderation/key").then().body("epoch", equalTo(2)).body("signPublicKey", equalTo(next.signPublicKey))
        // Only the current epoch proves: the old key is refused, the new one passes.
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(403)
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", next)).get("/api/moderation/deliveries")
            .then().statusCode(200)
    }

    @Test
    fun aMintForAnyEpochButTheNextIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asOwner(OWNER).body(mintBody(epochKey(), listOf(OWNER), epoch = 3)).post("/api/instance/moderation/epochs")
            .then().statusCode(409).body("code", equalTo("moderation_epoch_stale"))
        // Below the contract's minimum (an absent epoch binds as 0) is the boundary's 400, before any lookup.
        for (below in listOf(0, 1)) {
            asOwner(OWNER).body(mintBody(epochKey(), listOf(OWNER), epoch = below)).post("/api/instance/moderation/epochs")
                .then().statusCode(400)
        }
        asOwner(OWNER).body(mintBody(epochKey(), listOf(OWNER), epoch = 2)).post("/api/instance/moderation/epochs")
            .then().statusCode(200).body("currentEpoch", equalTo(2))
    }

    @Test
    fun aMintSealedToAStaleIdentityIsRefused() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        val body = mintBody(epochKey(), listOf(OWNER), identity = "c3RhbGUtaWRlbnRpdHk=")
        asOwner(OWNER).body(body).post("/api/instance/moderation/epochs").then().statusCode(409).body("code", equalTo("identity_changed"))
    }

    // ── delivery ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aHolderDeliversOnlyWhatItHoldsOnlyToAnAcceptedModeratorAndOnce() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asOwner(OWNER).body(mapOf("address" to deputyAddress)).post("/api/instance/moderation/moderators").then().statusCode(200)
        // Pending: not a recipient.
        deliver(OWNER, deputy, 1).then().statusCode(404).body("code", equalTo("not_moderator"))
        asUser(deputy).post("/api/moderation/appointment/acceptance").then().statusCode(200)
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(200).body("deliveries[0].accountId", equalTo(deputy))
            .body("deliveries[0].epochs", containsInAnyOrder(1))
        // An epoch the holder does not hold, and a stale identity, are refused.
        deliver(OWNER, deputy, 2).then().statusCode(409).body("code", equalTo("not_holder"))
        deliver(OWNER, deputy, 1, identity = "c3RhbGUtaWRlbnRpdHk=").then().statusCode(409).body("code", equalTo("identity_changed"))
        deliver(OWNER, deputy, 1).then().statusCode(204)
        deliver(OWNER, deputy, 1).then().statusCode(204)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationGrantEntity.count("moderatorId", deputy) })
        assertEquals(1L, events(ModerationEvent.DELIVERED, deputy))
        asUser(deputy).get("/api/moderation/grants").then().body("epochs[0].sealedGrant", notNullValue())
        asUser(deputy).get("/api/moderation/appointment").then().body("awaitingKey", equalTo(false))
        // A moderator who holds nothing delivers nothing.
        val third = UUID.randomUUID().toString().also { seedUser(id = it, email = "third-$it@example.com") }
        appointAndAccept(OWNER, "third-$third@example.com", third)
        asUser(third).contentType(ContentType.JSON).headers(proof(third, "putModerationDelivery", key))
            .body(deliveryBody(1)).put("/api/moderation/deliveries/$deputy")
            .then().statusCode(409).body("code", equalTo("not_holder"))
    }

    // ── leaving ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theLastHolderCannotLeaveAndTheServerOwnerMayStepDownWhileAnotherHolds() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        asUser(OWNER).headers(proof(OWNER, "stepDownAsModerator", key)).delete("/api/moderation/appointment")
            .then().statusCode(409).body("code", equalTo("moderation_last_holder"))
        asOwner(OWNER).delete("/api/instance/moderation/moderators/$OWNER").then().statusCode(409)
            .body("code", equalTo("moderation_last_holder"))
        appointAndAccept(OWNER, deputyAddress, deputy)
        // Accepted but not holding: still the last holder is the owner.
        asUser(OWNER).headers(proof(OWNER, "stepDownAsModerator", key)).delete("/api/moderation/appointment")
            .then().statusCode(409)
        deliverAsOwner(deputy, 1)
        asUser(OWNER).headers(proof(OWNER, "stepDownAsModerator", key)).delete("/api/moderation/appointment")
            .then().statusCode(204)
        asOwner(OWNER).get("/api/instance/moderation").then().body("moderators", hasSize<Any>(1))
            .body("rotationDueSince", notNullValue())
        assertEquals(1L, events(ModerationEvent.STEPPED_DOWN, OWNER))
    }

    @Test
    fun anIdentityRotationDeletesTheGrantsAndMakesTheDeliveryPendingAgain() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, deputyAddress, deputy)
        deliverAsOwner(deputy, 1)
        QuarkusTransaction.requiringNew().run { check(handler.onIdentityRotated(deputy, mapOf(1 to null))) }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { ModerationGrantEntity.count("moderatorId", deputy) })
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().body("deliveries[0].accountId", equalTo(deputy))
        asOwner(OWNER).get("/api/instance/moderation").then().body("rotationDueSince", notNullValue())
        assertEquals(1L, events(ModerationEvent.IDENTITY_ROTATED, deputy))
        assertNotNull(QuarkusTransaction.requiringNew().call { ModeratorEntity.findById(deputy)?.acceptedAt })
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun deliverAsOwner(recipient: String, epoch: Int) = deliver(OWNER, recipient, epoch).then().statusCode(204)

    private fun deliver(holder: String, recipient: String, epoch: Int, identity: String = IDENTITY_PUBLIC_KEY) =
        asUser(holder).contentType(ContentType.JSON).headers(proof(holder, "putModerationDelivery", key))
            .body(deliveryBody(epoch, identity)).put("/api/moderation/deliveries/$recipient")

    private fun deliveryBody(epoch: Int, identity: String = IDENTITY_PUBLIC_KEY) = mapOf(
        "identityPublicKey" to identity,
        "grants" to listOf(mapOf("epoch" to epoch, "sealedGrant" to sealedGrant())),
    )

    private fun mintBody(k: EpochKey, to: List<String>, identity: String = IDENTITY_PUBLIC_KEY, epoch: Int = 2) = mapOf(
        "epoch" to epoch, "kemPublicKey" to k.kemPublicKey, "signPublicKey" to k.signPublicKey, "reason" to "REMOVAL",
        "grants" to to.map { mapOf("accountId" to it, "identityPublicKey" to identity, "sealedGrant" to sealedGrant()) },
    )

    private fun events(event: ModerationEvent, moderator: String): Long = QuarkusTransaction.requiringNew().call {
        ModerationEventEntity.count("event = ?1 and moderatorId = ?2", event.name, moderator)
    }

    private companion object {
        const val OWNER = "0d0e0f10-1112-4314-8516-171819101a1b"
    }
}
