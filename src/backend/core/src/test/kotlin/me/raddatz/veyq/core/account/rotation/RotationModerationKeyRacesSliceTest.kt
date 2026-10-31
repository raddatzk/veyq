package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.GrantIn
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ModerationOutcome
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ModerationTestKit.EpochKey
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A moderator secures their account (ADR 0060's publish, which installs a new identity and writes the epochs their own
 * client re-sealed to it, ADR 0099 § 4) while another holder delivers them an epoch they miss, sealed to the identity
 * being retired — the two writers of the moderator's grants, serialized by the current epoch's row lock both take
 * (`ModerationHandler.onIdentityRotated`, `ModerationHandler.deliver`). Each order is forced: one side held uncommitted
 * through its real in-transaction body ([RotationHandler.rotateIn], [ModerationHandler.deliver]), the other the real
 * request, seen waiting on the held one ([whileUncommitted]).
 *
 * What must hold either way: no grant stays sealed to the retired identity, and no epoch the moderator holds is lost —
 * a delivery that reads the identity after the publish is refused (`identity_changed`), and a publish that reads the
 * held epochs after the delivery is refused (`rotation_plan_changed`) until its stage carries the delivered one,
 * re-sealed. No side dies on a lock wait (a deadlock or a lock timeout surfaces as another status than the one asserted).
 */
@QuarkusTest
class RotationModerationKeyRacesSliceTest {

    @Inject lateinit var rotation: RotationHandler
    @Inject lateinit var moderation: ModerationHandler
    @Inject lateinit var mapper: ObjectMapper

    @AfterEach
    fun cleanUp() = ModerationTestKit.reset()

    @Test
    fun aPublishCommittedFirstRefusesTheDeliverySealedToTheIdentityItRetired() = raceRounds { round ->
        val w = world()
        val stageId = w.deputy.openStageId()
        w.deputy.stageModeration(stageId, mapOf(1 to RESEALED_1)).then().statusCode(204)
        val request = w.deputy.publish(stageId, emptyList()).toRequest(mapper)
        val token = TestFixtures.bearer(w.deputy.userId).removePrefix("Bearer ")
        val proof = ModerationTestKit.proof(w.owner, "putModerationDelivery", w.key2)
        val published = AtomicReference<RotationOutcome>()

        // The owner's client sealed epoch 2 to the identity its pending-delivery read served — the one being retired.
        val answer = whileUncommitted(
            change = { published.set(rotation.rotateIn(w.deputy.userId, token, request)) },
            overlapping = { w.deliverOverHttp(proof) },
        )

        assertInstanceOf(RotationOutcome.Applied::class.java, published.get(), "round $round: the publish committed")
        assertEquals(409, answer.statusCode(), "round $round: ${answer.body().asString()}")
        assertEquals("identity_changed", answer.path<String>("code"), "round $round")
        tx { assertEquals(w.deputy.next.bundle, VeyqUser.findById(w.deputy.userId)!!.identityPublicKey) }
        assertEquals(mapOf(1 to RESEALED_1), w.grantsOfDeputy(), "round $round: epoch 1 kept, nothing sealed to the retired identity")
    }

    @Test
    fun aDeliveryCommittedFirstRefusesThePublishUntilItReSealsTheDeliveredEpoch() = raceRounds { round ->
        val w = world()
        val stageId = w.deputy.openStageId()
        w.deputy.stageModeration(stageId, mapOf(1 to RESEALED_1)).then().statusCode(204)
        val publish = w.deputy.publish(stageId, emptyList())
        val token = TestFixtures.bearer(w.deputy.userId).removePrefix("Bearer ")
        val delivered = AtomicReference<ModerationOutcome<Unit>>()

        val answer = whileUncommitted(
            change = {
                delivered.set(
                    moderation.deliver(w.owner, w.deputy.userId, w.deputy.current.bundle, listOf(GrantIn(w.deputy.userId, w.deputy.current.bundle, 2, DELIVERED_2))),
                )
            },
            overlapping = { w.deputy.send(publish, token) },
        )

        assertInstanceOf(ModerationOutcome.Ok::class.java, delivered.get(), "round $round: the delivery committed")
        assertEquals(409, answer.statusCode(), "round $round: ${answer.body().asString()}")
        assertEquals("rotation_plan_changed", answer.path<String>("code"), "round $round")
        tx { assertEquals(w.deputy.current.bundle, VeyqUser.findById(w.deputy.userId)!!.identityPublicKey, "round $round: not published") }
        assertEquals(mapOf(1 to OLD_1, 2 to DELIVERED_2), w.grantsOfDeputy(), "round $round: both epochs at the identity still current")

        // The client's retry re-seals the delivered epoch too, and the same keys go up again.
        w.deputy.stageModeration(stageId, mapOf(2 to RESEALED_2)).then().statusCode(204)
        w.deputy.send(publish, token).then().statusCode(200)
        assertEquals(mapOf(1 to RESEALED_1, 2 to RESEALED_2), w.grantsOfDeputy(), "round $round: no epoch lost")
    }

    // --- the world ---

    /** One round's instance: [owner] holds epochs 1 and 2 (the current one); [deputy], an accepted moderator who can
     *  secure their account, holds epoch 1 and waits for epoch 2. */
    private inner class World(val owner: String, val deputy: TestRotation, val key2: EpochKey) {
        /** The owner's session, issued before any race: issuing one takes the account row's lock. */
        private val ownerBearer = TestFixtures.bearer(owner)

        fun deliverOverHttp(proof: Map<String, String>): Response =
            given().header("Authorization", ownerBearer).headers(proof).contentType(ContentType.JSON)
                .body("""{"identityPublicKey":"${deputy.current.bundle}","grants":[{"epoch":2,"sealedGrant":"$DELIVERED_2"}]}""")
                .`when`().put("/api/moderation/deliveries/${deputy.userId}")

        fun grantsOfDeputy(): Map<Int, String> =
            tx { ModerationGrantEntity.list("moderatorId", deputy.userId).associate { it.epoch to it.sealedGrant } }
    }

    private fun world(): World {
        ModerationTestKit.reset()
        val key1 = ModerationTestKit.epochKey()
        val key2 = ModerationTestKit.epochKey()
        val owner = seedUser()
        val keys = TestAccountKeys()
        val deputy = seedUser(identityPublicKey = keys.bundle, authPublicKey = keys.auth.publicKey, secretKeyProofPublicKey = keys.proof.publicKey)
        tx {
            for ((method, wrapped) in listOf("PASSWORD" to "b2xkLXBhc3N3b3JkLXdyYXA=", "RECOVERY" to "b2xkLXJlY292ZXJ5LXdyYXA=")) {
                MasterKeyWrapEntity().apply {
                    id = UUID.randomUUID().toString(); userId = deputy; this.method = method; wrappedKey = wrapped; kdfParams = TestRotation.KDF
                }.persist()
            }
            for ((e, k) in listOf(1 to key1, 2 to key2)) {
                ModerationEpochEntity().apply { epoch = e; kemPublicKey = k.kemPublicKey; signPublicKey = k.signPublicKey; reason = "BY_HAND" }.persist()
            }
            for (m in listOf(owner, deputy)) {
                ModeratorEntity().apply { accountId = m; appointedBy = owner; appointedAt = Instant.now(); acceptedAt = Instant.now() }.persist()
            }
            for ((m, e) in listOf(owner to 1, owner to 2, deputy to 1)) {
                ModerationGrantEntity().apply {
                    id = UUID.randomUUID().toString(); moderatorId = m; epoch = e; deliveredBy = owner
                    sealedGrant = if (m == deputy) OLD_1 else ModerationTestKit.sealedGrant()
                }.persist()
            }
        }
        return World(owner, TestRotation(deputy, keys) { TestFixtures.bearer(deputy) }, key2)
    }

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        /** The deputy's epoch 1, sealed to the identity it is about to retire. */
        const val OLD_1 = "b2xkLWdyYW50LW9uZQ=="

        /** Epoch 2 as the owner's client sealed it to the deputy's identity before the publish. */
        const val DELIVERED_2 = "ZGVsaXZlcmVkLWVwb2NoLXR3bw=="

        /** Epochs 1 and 2 as the deputy's own client re-sealed them to the identity its securing installs. */
        const val RESEALED_1 = "cmVzZWFsZWQtZXBvY2gtb25l"
        const val RESEALED_2 = "cmVzZWFsZWQtZXBvY2gtdHdv"
    }
}
