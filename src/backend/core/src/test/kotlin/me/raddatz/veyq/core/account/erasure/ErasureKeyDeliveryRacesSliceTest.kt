package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.account.rotation.Publish
import me.raddatz.veyq.core.account.rotation.RotationHandler
import me.raddatz.veyq.core.account.rotation.RotationOutcome
import me.raddatz.veyq.core.account.rotation.TestAccountKeys
import me.raddatz.veyq.core.account.rotation.TestRotation
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.account.identitypins.toPinWrite
import me.raddatz.veyq.core.account.rotation.ResealedDocument
import me.raddatz.veyq.core.account.rotation.RewrittenWrap
import me.raddatz.veyq.core.account.rotation.RotationRequest
import me.raddatz.veyq.core.generated.model.RotateAccountKeysRequest
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.EpochGrantIn
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ModerationOutcome
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ServerOwnerDeliveryIn
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.newUser
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The last holder's erasure delivers the moderation key to the Server Owner (ADR 0101 decision 9) while the Server Owner
 * secures their account (ADR 0060's publish, which installs a new identity) — the overlap the erasure's lock of the
 * Server Owner's account row exists for (`AccountErasureHandler.eraseOwnIn`, its KDoc the lock order). Each order is
 * forced: one side held uncommitted through its real in-transaction body ([AccountErasureHandler.eraseOwnIn],
 * [RotationHandler.rotateIn]), the other the real request, seen waiting on the held one ([whileUncommitted]).
 *
 * What must hold either way: no grant stays sealed to an identity the Server Owner retired — the erasure that reads the
 * identity after the rotation is refused with nothing written (`identity_changed`), and a rotation that comes after the
 * delivery deletes the grants it delivered as exposed (ADR 0099 § 4) — and no side dies on a lock wait (a deadlock or
 * a lock timeout surfaces as a status other than the one asserted). A second moderator's acceptance, which takes the
 * epoch row the delivery takes, overlaps too, in both orders.
 *
 * The instance has one Server Owner; the `%test` database holds many, seeded by other classes, so each round demotes
 * every other one for its duration and [restore] gives them back.
 */
@QuarkusTest
class ErasureKeyDeliveryRacesSliceTest {

    @Inject lateinit var erasure: AccountErasureHandler
    @Inject lateinit var rotation: RotationHandler
    @Inject lateinit var moderation: ModerationHandler
    @Inject lateinit var mapper: ObjectMapper

    private val demoted = mutableSetOf<String>()
    private val mine = mutableSetOf<String>()

    @AfterEach
    fun restore() {
        ModerationTestKit.reset()
        val foreign = demoted - mine
        if (foreign.isNotEmpty()) {
            QuarkusTransaction.requiringNew().run { VeyqUser.update("serverRole = ?1 where id in ?2", ServerRoles.SERVER_OWNER, foreign.toList()) }
        }
        demoted.clear()
        mine.clear()
    }

    @Test
    fun aServerOwnersRotationCommittedFirstRefusesTheDeliverySealedToTheIdentityItRetired() = raceRounds { round ->
        val w = world()
        val publish = w.rotation.prepared()
        val request = rotationRequestOf(publish)
        val token = TestFixtures.bearer(w.serverOwner).removePrefix("Bearer ")
        val proof = w.proof()
        val rotated = AtomicReference<RotationOutcome>()

        // The holder's client sealed to the identity the preview served — the one the rotation is retiring.
        val answer = whileUncommitted(
            change = { rotated.set(rotation.rotateIn(w.serverOwner, token, request)) },
            overlapping = { w.eraseOverHttp(proof, w.deliveryJson()) },
        )

        assertInstanceOf(RotationOutcome.Applied::class.java, rotated.get(), "round $round: the rotation committed")
        assertEquals(409, answer.statusCode(), "round $round: ${answer.body().asString()}")
        assertEquals("identity_changed", answer.path<String>("code"), "round $round")
        tx {
            assertEquals(w.rotation.next.bundle, VeyqUser.findById(w.serverOwner)!!.identityPublicKey)
            assertNull(AccountErasureEntity.findById(w.holder), "round $round: nothing was requested")
            assertTrue(ModeratorEntity.isModerator(w.holder), "round $round: the holder still holds the key")
            assertEquals(1L, ModerationGrantEntity.count("moderatorId", w.holder))
            assertEquals(0L, ModerationGrantEntity.count("moderatorId", w.serverOwner), "round $round: no grant to the retired identity")
            assertFalse(ModeratorEntity.isModerator(w.serverOwner), "round $round: nor an appointment")
        }
    }

    @Test
    fun aServerOwnersRotationAfterTheDeliveryDeletesTheGrantsSealedToTheIdentityItRetired() = raceRounds { round ->
        val w = world()
        val publish = w.rotation.prepared()
        val proof = w.proof()
        val token = TestFixtures.bearer(w.serverOwner).removePrefix("Bearer ")
        val erased = AtomicReference<ErasureOutcome>()

        val answer = whileUncommitted(
            change = { erased.set(erasure.eraseOwnIn(w.holder, w.email, proof.first, proof.second, emptyList(), w.deliveryIn())) },
            overlapping = { w.rotation.send(publish, token) },
        )

        assertInstanceOf(ErasureOutcome.Pending::class.java, erased.get(), "round $round: the erasure committed")
        assertEquals(200, answer.statusCode(), "round $round: ${answer.body().asString()}")
        tx {
            assertEquals(w.rotation.next.bundle, VeyqUser.findById(w.serverOwner)!!.identityPublicKey)
            assertNotNull(AccountErasureEntity.findById(w.holder))
            assertFalse(ModeratorEntity.isModerator(w.holder))
            assertTrue(ModeratorEntity.isModerator(w.serverOwner), "round $round: the delivery reappointed the Server Owner")
            assertEquals(
                0L, ModerationGrantEntity.count("moderatorId", w.serverOwner),
                "round $round: the grants sealed to the identity the rotation retired are gone (ADR 0099 § 4)",
            )
            assertNotNull(ModerationEpochEntity.findById(1)!!.rotationDueAt)
        }
    }

    @Test
    fun aSecondModeratorsAcceptanceAndTheDeliveryMeetOnTheEpochRowInEitherOrder() {
        // The delivery held, the acceptance waiting on the epoch row it holds.
        raceRounds { round ->
            val w = world()
            val newcomer = w.pendingModerator()
            val newcomerBearer = TestFixtures.bearer(newcomer)
            val proof = w.proof()
            val erased = AtomicReference<ErasureOutcome>()
            val accepted = whileUncommitted(
                change = { erased.set(erasure.eraseOwnIn(w.holder, w.email, proof.first, proof.second, emptyList(), w.deliveryIn())) },
                overlapping = { given().header("Authorization", newcomerBearer).post("/api/moderation/appointment/acceptance") },
            )
            assertInstanceOf(ErasureOutcome.Pending::class.java, erased.get(), "round $round")
            assertEquals(200, accepted.statusCode(), "round $round: ${accepted.body().asString()}")
            w.assertHandedOver(newcomer, "round $round (delivery first)")
        }
        // The acceptance held, the erasure waiting on the epoch row behind it.
        raceRounds { round ->
            val w = world()
            val newcomer = w.pendingModerator()
            val proof = w.proof()
            val acceptance = AtomicReference<ModerationOutcome<Unit>>()
            val answer = whileUncommitted(
                change = { acceptance.set(moderation.accept(newcomer)) },
                overlapping = { w.eraseOverHttp(proof, w.deliveryJson()) },
            )
            assertInstanceOf(ModerationOutcome.Ok::class.java, acceptance.get(), "round $round")
            assertEquals(200, answer.statusCode(), "round $round: ${answer.body().asString()}")
            w.assertHandedOver(newcomer, "round $round (acceptance first)")
        }
    }

    // --- the world ---

    /** One round's instance: a Server Owner with real rotation keys, not on the roster; the holder alone holds epoch 1. */
    private inner class World(val serverOwner: String, val rotation: TestRotation, val holder: String, val kp: KeyPair) {
        val email = "u-$holder@example.com"

        /** A spent-elsewhere challenge for the holder's address and the erasure proof over it. */
        fun proof(): Pair<String, String> {
            val nonce: String = given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
                .`when`().post("/api/login/challenge").then().statusCode(200).extract().path("nonce")
            return nonce to sign(kp, AuthAttestation.eraseAccountStatement(holder, nonce)!!)
        }

        /** Epoch 1 sealed to the Server Owner's identity as the preview served it before anything raced. */
        fun deliveryIn() = ServerOwnerDeliveryIn(rotation.current.bundle, listOf(EpochGrantIn(1, GRANT)))

        fun deliveryJson() = """{"identityPublicKey":"${rotation.current.bundle}","grants":[{"epoch":1,"sealedGrant":"$GRANT"}]}"""

        /** The holder's session, issued before any race: issuing one takes the account row's lock. */
        val bearer: String = TestFixtures.bearer(holder)

        fun eraseOverHttp(proof: Pair<String, String>, delivery: String): Response =
            given().header("Authorization", bearer).contentType(ContentType.JSON)
                .body("""{"nonce":"${proof.first}","attestationSignature":"${proof.second}","lastOwnerChoices":[],"serverOwnerDelivery":$delivery}""")
                .`when`().post("/api/account/erasure")

        /** An appointment not yet accepted: the newcomer holds nothing, so the holder stays the last one. */
        fun pendingModerator(): String {
            val id = seedUser()
            QuarkusTransaction.requiringNew().run {
                ModeratorEntity().apply { accountId = id; appointedBy = serverOwner; appointedAt = Instant.now() }.persist()
            }
            return id
        }

        fun assertHandedOver(newcomer: String, round: String) = tx {
            assertNotNull(AccountErasureEntity.findById(holder), round)
            assertFalse(ModeratorEntity.isModerator(holder), round)
            assertTrue(ModeratorEntity.isModerator(serverOwner), "$round: the Server Owner is a moderator")
            assertEquals(listOf(1), ModerationGrantEntity.list("moderatorId", serverOwner).map { it.epoch }, "$round: holding epoch 1")
            assertTrue(ModeratorEntity.isModerator(newcomer), "$round: the newcomer accepted")
            assertEquals(0L, ModerationGrantEntity.count("moderatorId", newcomer), "$round: and waits for a holder to deliver")
        }
    }

    private fun world(): World {
        ModerationTestKit.reset()
        val keys = TestAccountKeys()
        val serverOwner = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser.list("serverRole", ServerRoles.SERVER_OWNER).forEach { demoted += it.id }
            VeyqUser.update("serverRole = ?1 where serverRole = ?2", ServerRoles.MEMBER, ServerRoles.SERVER_OWNER)
            newUser(
                id = serverOwner, serverRole = ServerRoles.SERVER_OWNER, identityPublicKey = keys.bundle,
                authPublicKey = keys.auth.publicKey, secretKeyProofPublicKey = keys.proof.publicKey,
            ).persist()
            for ((method, wrapped) in listOf("PASSWORD" to "b2xkLXBhc3N3b3JkLXdyYXA=", "RECOVERY" to "b2xkLXJlY292ZXJ5LXdyYXA=")) {
                MasterKeyWrapEntity().apply {
                    id = UUID.randomUUID().toString(); userId = serverOwner; this.method = method; wrappedKey = wrapped; kdfParams = TestRotation.KDF
                }.persist()
            }
        }
        mine += serverOwner
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val holder = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            newUser(id = holder, authPublicKey = b64(kp.public.encoded.takeLast(32).toByteArray())).persist()
            ModerationEpochEntity().apply { epoch = 1; kemPublicKey = "a2Vt"; signPublicKey = "c2lnbg=="; reason = "BY_HAND" }.persist()
            ModeratorEntity().apply { accountId = holder; appointedBy = serverOwner; appointedAt = Instant.now(); acceptedAt = Instant.now() }.persist()
            ModerationGrantEntity().apply {
                id = UUID.randomUUID().toString(); moderatorId = holder; epoch = 1; sealedGrant = "Zw=="; deliveredBy = holder
            }.persist()
        }
        check(QuarkusTransaction.requiringNew().call { VeyqUser.count("serverRole", ServerRoles.SERVER_OWNER) } == 1L)
        return World(serverOwner, TestRotation(serverOwner, keys) { TestFixtures.bearer(serverOwner) }, holder, kp)
    }

    /** The Server Owner's publish, its stage opened and filled before the race: only the publish overlaps. */
    private fun TestRotation.prepared(): Publish {
        val stageId = openStageId()
        val grants = plan().map { selfGrant(it) }
        if (grants.isNotEmpty()) stage(stageId, grants).then().statusCode(204)
        return publish(stageId, grants)
    }

    /** [publish] as the handler takes it — the wire body read by the generated model, mapped as `AccountRotationResource` maps it. */
    private fun rotationRequestOf(publish: Publish): RotationRequest {
        val r = mapper.readValue(publish.json(), RotateAccountKeysRequest::class.java)
        return RotationRequest(
            identityPublicKey = r.identityPublicKey,
            wrappedIdentityPrivateKey = r.wrappedIdentityPrivateKey,
            passwordWrap = RewrittenWrap(r.passwordWrap.wrappedKey, r.passwordWrap.kdfParams),
            recoveryWrap = RewrittenWrap(r.recoveryWrap.wrappedKey, r.recoveryWrap.kdfParams),
            secretKeyProofSignature = r.secretKeyProofSignature,
            pins = r.identityPins.toPinWrite(),
            register = ResealedDocument(r.deviceRegister.blob, r.deviceRegister.revision!!),
            stepIdentitySignature = r.keyChainStep.identitySignature,
            stepProofSignature = r.keyChainStep.proofSignature,
            stageId = r.stageId.toString(),
            stagedGrantsDigest = r.stagedGrantsDigest,
            unreadableScopeIds = r.unreadableScopeIds.map { it.toString() }.toSet(),
            authPublicKey = r.authPublicKey,
            attestationSignature = r.attestationSignature,
            secretKeyProofPublicKey = r.secretKeyProofPublicKey,
            secretKeyProofPossession = r.secretKeyProofPossession,
        )
    }

    private fun sign(kp: KeyPair, message: ByteArray): String =
        b64(Signature.getInstance("Ed25519").run { initSign(kp.private); update(message); sign() })

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        /** Epoch 1 as the holder's client sealed it to the Server Owner — opaque to the server. */
        const val GRANT = "Z3JhbnQtdG8tdGhlLXNlcnZlci1vd25lcg=="
    }
}
