package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.ModerationTestKit.EpochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asOwner
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.moderation.ModerationTestKit.sealedGrant
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The roster's one lock — the current epoch's row, taken first by every roster and key write (`ModerationHandler`) —
 * under overlap. Run on MariaDB by `scripts/run_backend_races_on_mariadb.py`, where READ COMMITTED shows what H2 hides.
 */
@QuarkusTest
class ModerationRaceSliceTest {

    @Inject
    lateinit var handler: ModerationHandler

    /** Two holders deliver the same epoch at once: both answer 204 and exactly one grant exists (no unique-index 500). */
    @Test
    fun twoConcurrentDeliveriesOfOneEpochLeaveOneGrant() = repeat(RACE_ROUNDS) { round ->
        val (owner, deputy, key) = world()
        val statuses = race(
            { deliver(owner, deputy, key) },
            { deliver(owner, deputy, key) },
        )
        assertEquals(listOf(204, 204), statuses, "round $round")
        assertEquals(1L, grants(deputy), "round $round")
    }

    /**
     * A removal that overlaps an uncommitted delivery to the same moderator: the removal waits for the delivery's lock
     * and then deletes the grant it wrote — never a grant left behind for an account the roster dropped.
     */
    @Test
    fun aRemovalOverlappingADeliveryLeavesTheRemovedModeratorNoGrant() = raceRounds { round ->
        val (owner, deputy, _) = world()
        whileUncommitted(
            change = { handler.deliver(owner, deputy, IDENTITY_PUBLIC_KEY, listOf(GrantIn(deputy, IDENTITY_PUBLIC_KEY, 1, sealedGrant()))) },
            overlapping = { asOwner(owner).delete("/api/instance/moderation/moderators/$deputy").then().statusCode(200) },
        )
        assertEquals(0L, grants(deputy), "round $round")
    }

    private fun world(): Triple<String, String, EpochKey> {
        ModerationTestKit.reset()
        val owner = UUID.randomUUID().toString().also { ensureUser(it, "race-owner-$it@example.com", "SERVER_OWNER") }
        val key = epochKey()
        enable(owner, key, IDENTITY_PUBLIC_KEY)
        val deputy = UUID.randomUUID().toString().also { seedUser(id = it, email = "race-$it@example.com") }
        appointAndAccept(owner, "race-$deputy@example.com", deputy)
        return Triple(owner, deputy, key)
    }

    private fun deliver(holder: String, recipient: String, key: EpochKey): Int =
        asUser(holder).contentType(ContentType.JSON).headers(proof(holder, "putModerationDelivery", key))
            .body(
                mapOf(
                    "identityPublicKey" to IDENTITY_PUBLIC_KEY,
                    "grants" to listOf(mapOf("epoch" to 1, "sealedGrant" to sealedGrant())),
                ),
            ).put("/api/moderation/deliveries/$recipient").then().extract().statusCode()

    private fun grants(moderator: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationGrantEntity.count("moderatorId", moderator) }
}
