package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.crypto.ModerationProof
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
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

    /** Turn moderation on as [ownerId] with [key] as epoch 1, sealed to [identityPublicKey]. */
    fun enable(ownerId: String, key: EpochKey, identityPublicKey: String) {
        asOwner(ownerId).body(
            mapOf(
                "kemPublicKey" to key.kemPublicKey, "signPublicKey" to key.signPublicKey,
                "identityPublicKey" to identityPublicKey, "sealedGrant" to sealedGrant(),
            ),
        ).post("/api/instance/moderation").then().statusCode(200)
    }

    /** Appoint [address] and accept as [userId]. */
    fun appointAndAccept(ownerId: String, address: String, userId: String) {
        asOwner(ownerId).body(mapOf("address" to address)).post("/api/instance/moderation/moderators").then().statusCode(200)
        asUser(userId).post("/api/moderation/appointment/acceptance").then().statusCode(200)
    }
}
