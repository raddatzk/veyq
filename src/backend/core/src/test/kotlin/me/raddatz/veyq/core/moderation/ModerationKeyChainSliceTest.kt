package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.crypto.KeyChainStatements
import me.raddatz.veyq.core.moderation.ModerationTestKit.EpochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asOwner
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * A moderation holder anchors the moderator it delivers the key to as every other wrap is anchored
 * (cr-secure-the-account decision 9): the pending delivery serves the recipient's key-chain head beside its key, and the
 * chain read serves one accepted moderator's chain to another — the two may share no tenant, the roster is the
 * instance's. Everyone else still gets the `404` an unknown id gets (ADR 0042): a stranger, a moderator reading a
 * non-moderator, a pending appointee.
 */
@QuarkusTest
class ModerationKeyChainSliceTest {

    private lateinit var key: EpochKey
    private lateinit var deputy: String

    @BeforeEach
    fun seed() {
        ModerationTestKit.reset()
        ensureUser(OWNER, "moderation-chain-owner@example.com", "SERVER_OWNER")
        key = epochKey()
        deputy = UUID.randomUUID().toString()
        seedUser(id = deputy, email = "chain-deputy-$deputy@example.com", secretKeyProofPublicKey = PROOF)
    }

    @Test
    fun aPendingDeliveryCarriesTheModeratorsKeyChainHead() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, "chain-deputy-$deputy@example.com", deputy)
        val head = b64(KeyChainStatements.head(KeyChainStatements.genesis(deputy, IDENTITY_PUBLIC_KEY, PROOF)!!))
        asUser(OWNER).headers(proof(OWNER, "listPendingModerationDeliveries", key)).get("/api/moderation/deliveries")
            .then().statusCode(200)
            .body("deliveries[0].accountId", equalTo(deputy))
            .body("deliveries[0].keyChainHead", equalTo(head))
        // …and it is the head the chain read serves to the holder (the next test's gate).
        read(OWNER, deputy).then().statusCode(200).body("head", equalTo(head))
    }

    @Test
    fun twoAcceptedModeratorsReadEachOthersChainThoughTheyShareNoTenant() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        // Appointed, not yet accepted: no role, so still a stranger to the chain read (control on the same pair).
        asOwner(OWNER).body(mapOf("address" to "chain-deputy-$deputy@example.com")).post("/api/instance/moderation/moderators")
            .then().statusCode(200)
        read(OWNER, deputy).then().statusCode(404).body("code", equalTo("not_found"))
        read(deputy, OWNER).then().statusCode(404)

        asUser(deputy).post("/api/moderation/appointment/acceptance").then().statusCode(200)
        read(OWNER, deputy).then().statusCode(200).body("userId", equalTo(deputy))
        read(deputy, OWNER).then().statusCode(200).body("userId", equalTo(OWNER))
    }

    @Test
    fun aStrangerAndAModeratorReadingANonModeratorStillGetTheUnknownUsers404() {
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, "chain-deputy-$deputy@example.com", deputy)
        val stranger = seedUser()
        val unknown = read(stranger, UUID.randomUUID().toString()).then().statusCode(404).extract().asString()
        // A non-moderator reading a moderator: the role on one side is not enough.
        assertEquals(unknown, read(stranger, deputy).then().statusCode(404).extract().asString())
        // A moderator reading a non-moderator: neither is it from the other side.
        assertEquals(unknown, read(deputy, stranger).then().statusCode(404).extract().asString())
    }

    private fun read(caller: String, person: String) = asUser(caller).get("/api/people/$person/key-chain")

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private companion object {
        const val OWNER = "4d4e4f50-5152-4354-8556-575859505a5b"

        /** A raw 32-byte Ed25519-sized proof public key, base64. */
        const val PROOF = "cHJvb2YtcHVibGljLWtleS1wcm9vZi1wdWJsaWMta2U="
    }
}
