package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.crypto.ModerationProof
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/**
 * What a moderation test needs to stand in for the clients (ADR 0099): an epoch's key as a real Ed25519 keypair (its
 * private half signs `moderation-proof` exactly as `packages/crypto` does — the statement bytes are [ModerationProof]'s,
 * pinned to the vector both stacks read), a public bundle whose X-Wing half is opaque bytes of the right size (the
 * server stores and serves it, never uses it), and grants that are opaque ciphertext to the server.
 */
object ModerationTestKit {
    private val rng = SecureRandom()
    private val b64 = Base64.getEncoder()

    /** One epoch's signing key, and the raw public half as the wire carries it. */
    class EpochKey(val pair: KeyPair) {
        val signPublicKey: String = b64.encodeToString(pair.public.encoded.takeLast(32).toByteArray())
        val kemPublicKey: String = b64.encodeToString(ByteArray(1216).also(rng::nextBytes))

        fun sign(statement: ByteArray): String =
            b64.encodeToString(Signature.getInstance("Ed25519").run { initSign(pair.private); update(statement); sign() })
    }

    fun epochKey(): EpochKey = EpochKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())

    /** An opaque stand-in for a sealed grant (the server never opens one). */
    fun sealedGrant(): String = b64.encodeToString(ByteArray(1181).also(rng::nextBytes))

    /** Empty every moderation table: the state is instance-wide, and the `%test` database lives as long as the JVM. */
    fun reset() = QuarkusTransaction.requiringNew().run {
        ModerationEventEntity.deleteAll()
        ModerationChallengeEntity.deleteAll()
        ModerationGrantEntity.deleteAll()
        ModerationEpochEntity.deleteAll()
        ModeratorEntity.deleteAll()
    }

    /** A challenge for [userId], signed for [operation] (and [caseId]) with [key]: the two proof headers. */
    fun proof(userId: String, operation: String, key: EpochKey, caseId: String? = null): Map<String, String> {
        val challenge = challenge(userId)
        val statement = ModerationProof.statement(Base64.getDecoder().decode(challenge), operation, caseId)!!
        return mapOf(
            ModerationProofGate.CHALLENGE_HEADER to challenge,
            ModerationProofGate.SIGNATURE_HEADER to key.sign(statement),
        )
    }

    fun challenge(userId: String): String =
        given().header("Authorization", bearer(userId)).`when`().post("/api/moderation/challenge")
            .then().statusCode(200).extract().path("challenge")

    fun asUser(userId: String): RequestSpecification = given().header("Authorization", bearer(userId))

    fun asOwner(ownerId: String): RequestSpecification =
        givenAdminChannel().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)

    /**
     * Epoch 1 with [key], sealed to [identityPublicKey], [ownerId] its first moderator — what first-run setup does
     * (`SetupStore.persistOwner`); these tests' owners are seeded, not set up, so they mint it through the same call.
     */
    fun enable(ownerId: String, key: EpochKey, identityPublicKey: String) {
        val outcome = QuarkusTransaction.requiringNew().call {
            Arc.container().instance(ModerationHandler::class.java).get()
                .enable(ownerId, key.kemPublicKey, key.signPublicKey, identityPublicKey, sealedGrant())
        }
        check(outcome is ModerationOutcome.Ok) { "epoch 1 not minted: $outcome" }
    }

    /** Appoint [address] and accept as [userId]. */
    fun appointAndAccept(ownerId: String, address: String, userId: String) {
        asOwner(ownerId).body(mapOf("address" to address)).post("/api/instance/moderation/moderators").then().statusCode(200)
        asUser(userId).post("/api/moderation/appointment/acceptance").then().statusCode(200)
    }
}
