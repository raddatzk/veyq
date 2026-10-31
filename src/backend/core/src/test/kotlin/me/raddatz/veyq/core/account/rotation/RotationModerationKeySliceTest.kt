package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ModerationTestKit.EpochKey
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A holder of the instance moderation key secures their account (ADR 0099 § 4, ADR 0060 Decision 4, uc.account.rotate-keys
 * E6): the publish deletes the grants sealed to the identity it retires, and writes in their place the epochs the caller's
 * own client re-sealed to the new one and staged — or is refused whole. What carries weight is the SOLE holder: nobody
 * else holds the key to deliver it again, so before this a securing lost every report and hold sealed to it (right after
 * first-run setup the Server Owner is that holder).
 *
 * The server opens nothing: each grant is opaque here, so "still reads" is the server's half — every live epoch, a
 * pre-rotation one and the current one, served to the caller sealed as their client re-sealed it, the role and the key
 * proof passing after; the client half (the re-sealed grant opens to the same `M_n` under the new identity) is
 * `rotateKeys.test.ts`'s. Every negative has its control on the same request, anchored on status and `code`.
 */
@QuarkusTest
class RotationModerationKeySliceTest {

    private lateinit var key1: EpochKey
    private lateinit var key2: EpochKey

    @BeforeEach
    fun freshInstance() {
        ModerationTestKit.reset()
        key1 = ModerationTestKit.epochKey()
        key2 = ModerationTestKit.epochKey()
    }

    @AfterEach
    fun cleanUp() = ModerationTestKit.reset()

    @Test
    fun aSoleHolderSecuresTheirAccountAndStillHoldsAPreRotationEpochAndTheCurrentOne() {
        val rotation = holder(epochs = listOf(1, 2))
        val stageId = rotation.openStageId()
        rotation.stageModeration(stageId, mapOf(1 to RESEALED_1, 2 to RESEALED_2)).then().statusCode(204)

        rotation.send(rotation.publish(stageId, emptyList())).then().statusCode(200)

        assertEquals(rotation.next.bundle, tx { VeyqUser.findById(rotation.userId)!!.identityPublicKey })
        // What the moderation surface opens on its next unlock: both epochs, sealed to the identity just published.
        given().header("Authorization", rotation.bearer()).`when`().get("/api/moderation/grants").then().statusCode(200)
            .body("currentEpoch", equalTo(2))
            .body("epochs.find { it.epoch == 1 }.sealedGrant", equalTo(RESEALED_1))
            .body("epochs.find { it.epoch == 2 }.sealedGrant", equalTo(RESEALED_2))
        tx {
            assertEquals(
                setOf(rotation.userId), ModerationGrantEntity.list("moderatorId", rotation.userId).map { it.deliveredBy }.toSet(),
                "the holder re-sealed them itself",
            )
            assertNotNull(ModerationEpochEntity.findById(2)!!.rotationDueAt, "the old identity held the current epoch: a rotation is due")
            assertTrue(ModeratorEntity.isModerator(rotation.userId), "still an accepted moderator")
        }
        assertEquals(1L, events(ModerationEvent.IDENTITY_ROTATED, rotation.userId))
        assertEquals(2L, events(ModerationEvent.DELIVERED, rotation.userId), "each re-sealed epoch is logged as delivered")
        // The role and the proof under the current epoch's key both pass after the securing.
        given().header("Authorization", rotation.bearer())
            .headers(ModerationTestKit.proof(rotation.userId, "listModerationCases", key2))
            .`when`().get("/api/moderation/cases").then().statusCode(200)
    }

    @Test
    fun aHoldersPublishWithoutEveryEpochReSealedIsRefusedAndWritesNothing() {
        val rotation = holder(epochs = listOf(1, 2))
        val stageId = rotation.openStageId()
        val publish = rotation.publish(stageId, emptyList())

        rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertUntouched(rotation, "no epoch staged")

        rotation.stageModeration(stageId, mapOf(2 to RESEALED_2)).then().statusCode(204)
        rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertUntouched(rotation, "the pre-rotation epoch missing")

        // Control: the same publish once every held epoch is staged.
        rotation.stageModeration(stageId, mapOf(1 to RESEALED_1)).then().statusCode(204)
        rotation.send(publish).then().statusCode(200)
        assertEquals(mapOf(1 to RESEALED_1, 2 to RESEALED_2), grantsOf(rotation.userId))
    }

    @Test
    fun aDeclaredEpochIsDeletedAndTheReSealedOneKept() {
        val rotation = holder(epochs = listOf(1, 2))
        val stageId = rotation.openStageId()
        rotation.stageModeration(stageId, mapOf(1 to null, 2 to RESEALED_2)).then().statusCode(204)

        rotation.send(rotation.publish(stageId, emptyList())).then().statusCode(200)

        assertEquals(mapOf(2 to RESEALED_2), grantsOf(rotation.userId), "a grant this device could not open goes, as before")
        assertEquals(1L, events(ModerationEvent.DELIVERED, rotation.userId))
    }

    @Test
    fun aStagedEpochTheCallerNoLongerHoldsIsNotWritten() {
        val rotation = holder(epochs = listOf(1, 2))
        val stageId = rotation.openStageId()
        rotation.stageModeration(stageId, mapOf(1 to RESEALED_1, 2 to RESEALED_2)).then().statusCode(204)
        // Between the stage and the publish epoch 1's grant goes (its last case expired, say).
        tx { ModerationGrantEntity.delete("moderatorId = ?1 and epoch = ?2", rotation.userId, 1) }

        rotation.send(rotation.publish(stageId, emptyList())).then().statusCode(200)

        assertEquals(mapOf(2 to RESEALED_2), grantsOf(rotation.userId), "writing it would hand the key to an account that lost it")
    }

    @Test
    fun aNonHoldersPublishIsUnaffectedAndStagesNoEpoch() {
        holder(epochs = listOf(1, 2)) // someone else holds the key
        val rotation = account()
        val stageId = rotation.openStageId()

        rotation.stageModeration(stageId, mapOf(1 to RESEALED_1)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        rotation.send(rotation.publish(stageId, emptyList())).then().statusCode(200)
        assertEquals(rotation.next.bundle, tx { VeyqUser.findById(rotation.userId)!!.identityPublicKey })
        assertEquals(emptyMap<Int, String>(), grantsOf(rotation.userId))
        assertEquals(0L, events(ModerationEvent.IDENTITY_ROTATED, rotation.userId), "not a moderator: no event")
    }

    @Test
    fun anAcceptedModeratorWaitingForTheKeySecuresWithNothingStaged() {
        holder(epochs = listOf(1, 2))
        val rotation = account()
        tx { ModeratorEntity().apply { accountId = rotation.userId; appointedBy = rotation.userId; appointedAt = Instant.now(); acceptedAt = Instant.now() }.persist() }

        rotation.run().then().statusCode(200)

        assertEquals(emptyMap<Int, String>(), grantsOf(rotation.userId))
        assertEquals(1L, events(ModerationEvent.IDENTITY_ROTATED, rotation.userId))
        assertNull(tx { ModerationEpochEntity.findById(2)!!.rotationDueAt }, "it held no epoch: nothing was exposed")
    }

    // --- the world ---

    /** An account that can secure itself: real keys, both cold wraps, no membership (the plan is empty). */
    private fun account(): TestRotation {
        val keys = TestAccountKeys()
        val id = seedUser(identityPublicKey = keys.bundle, authPublicKey = keys.auth.publicKey, secretKeyProofPublicKey = keys.proof.publicKey)
        tx {
            for ((method, wrapped) in listOf("PASSWORD" to "b2xkLXBhc3N3b3JkLXdyYXA=", "RECOVERY" to "b2xkLXJlY292ZXJ5LXdyYXA=")) {
                MasterKeyWrapEntity().apply {
                    this.id = UUID.randomUUID().toString(); userId = id; this.method = method; wrappedKey = wrapped; kdfParams = TestRotation.KDF
                }.persist()
            }
        }
        return TestRotation(id, keys) { TestFixtures.bearer(id) }
    }

    /** An account holding [epochs] of the moderation key, alone: an accepted moderator with one grant each, sealed to the
     *  identity it is about to retire. The last of [epochs] is the current one. */
    private fun holder(epochs: List<Int>): TestRotation {
        val rotation = account()
        tx {
            for (e in epochs) {
                val key = if (e == 1) key1 else key2
                ModerationEpochEntity().apply { epoch = e; kemPublicKey = key.kemPublicKey; signPublicKey = key.signPublicKey; reason = "BY_HAND" }.persist()
            }
            ModeratorEntity().apply {
                accountId = rotation.userId; appointedBy = rotation.userId; appointedAt = Instant.now(); acceptedAt = Instant.now()
            }.persist()
            for (e in epochs) {
                ModerationGrantEntity().apply {
                    id = UUID.randomUUID().toString(); moderatorId = rotation.userId; epoch = e; sealedGrant = oldGrant(e); deliveredBy = rotation.userId
                }.persist()
            }
        }
        return rotation
    }

    private fun assertUntouched(rotation: TestRotation, why: String) {
        assertEquals(rotation.current.bundle, tx { VeyqUser.findById(rotation.userId)!!.identityPublicKey }, "$why: not published")
        assertEquals(mapOf(1 to oldGrant(1), 2 to oldGrant(2)), grantsOf(rotation.userId), "$why: the grants stay as they were")
        assertNull(tx { ModerationEpochEntity.findById(2)!!.rotationDueAt }, "$why: no rotation due")
        assertEquals(0L, events(ModerationEvent.IDENTITY_ROTATED, rotation.userId), "$why: nothing logged")
    }

    private fun grantsOf(userId: String): Map<Int, String> =
        tx { ModerationGrantEntity.list("moderatorId", userId).associate { it.epoch to it.sealedGrant } }

    private fun events(event: ModerationEvent, moderator: String): Long =
        tx { ModerationEventEntity.count("event = ?1 and moderatorId = ?2", event.name, moderator) }

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        /** M_1 and M_2 as the caller's client re-sealed them to the new identity — opaque to the server. */
        const val RESEALED_1 = "cmVzZWFsZWQtZXBvY2gtb25l"
        const val RESEALED_2 = "cmVzZWFsZWQtZXBvY2gtdHdv"

        fun oldGrant(epoch: Int) = if (epoch == 1) "b2xkLWdyYW50LW9uZQ==" else "b2xkLWdyYW50LXR3bw=="
    }
}
