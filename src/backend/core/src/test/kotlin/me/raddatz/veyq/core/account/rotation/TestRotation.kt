package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.crypto.GrantSignature
import me.raddatz.veyq.core.crypto.KeyChainStatements
import me.raddatz.veyq.core.crypto.RotationStageDigest
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.membership.TestGrants
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.util.Base64

/** An Ed25519 key a test signs with — a fresh one, or one rebuilt from a known seed and its public half. */
class TestEd25519 private constructor(private val private: PrivateKey, val raw: ByteArray) {
    /** The raw 32-byte public key, standard base64 — what the server stores. */
    val publicKey: String = b64(raw)

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run { initSign(private); update(message); sign() }

    fun signB64(message: ByteArray?): String = b64(sign(requireNotNull(message) { "a fixture built a malformed statement" }))

    companion object {
        fun fresh(): TestEd25519 {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            return TestEd25519(pair.private, pair.public.encoded.takeLast(32).toByteArray())
        }

        fun of(pair: java.security.KeyPair) = TestEd25519(pair.private, pair.public.encoded.takeLast(32).toByteArray())

        fun of(seed: ByteArray, publicRaw: ByteArray) = TestEd25519(
            KeyFactory.getInstance("Ed25519").generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)), publicRaw,
        )
    }
}

/**
 * Everything an account's rotation signs with: its identity (an Ed25519 key in a bundle `lp(kem) || lp(sign)` with a
 * stand-in KEM half — the server never encapsulates), its Secret-Key proof key and its auth key. The server only ever
 * verifies, so any Ed25519 keys play the part.
 */
class TestAccountKeys(
    val identity: TestEd25519 = TestEd25519.fresh(),
    val proof: TestEd25519 = TestEd25519.fresh(),
    val auth: TestEd25519 = TestEd25519.fresh(),
) {
    val bundle: String = b64(lp(ByteArray(1216) { 7 }) + lp(identity.raw))
}

/** One self-grant as the stage takes it. */
data class StagedGrant(
    val scopeId: String,
    val epoch: Int,
    val wrappedGrant: String,
    val grantSignature: String,
    val recipientIdentityDigest: String,
) {
    fun json() = """{"scopeId":"$scopeId","epoch":$epoch,"wrappedGrant":"$wrappedGrant","grantSignature":"$grantSignature",""" +
        """"recipientIdentityDigest":"$recipientIdentityDigest"}"""
}

/** A publish request's every field — a test changes one with `copy` and sends [json]. */
data class Publish(
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val passwordWrap: String,
    val recoveryWrap: String,
    val kdf: String,
    val secretKeyProofSignature: String,
    val pinsRevision: Long,
    val pinsReplaceAll: Boolean,
    val registerRevision: Long,
    val stepIdentitySignature: String,
    val stepProofSignature: String,
    val stageId: String,
    val stagedGrantsDigest: String,
    val unreadableScopeIds: List<String>,
    val authPublicKey: String,
    val attestationSignature: String,
    val secretKeyProofPublicKey: String,
    val secretKeyProofPossession: String,
) {
    fun json() = """
        {"identityPublicKey":"$identityPublicKey","wrappedIdentityPrivateKey":"$wrappedIdentityPrivateKey",
         "passwordWrap":{"wrappedKey":"$passwordWrap","kdfParams":"$kdf"},
         "recoveryWrap":{"wrappedKey":"$recoveryWrap","kdfParams":"$kdf"},
         "secretKeyProofSignature":"$secretKeyProofSignature",
         "identityPins":{"revision":$pinsRevision,"head":"${TestRotation.NEW_PINS}","put":[{"pinId":"${TestRotation.NEW_PIN_ID}","entry":"${TestRotation.NEW_ENTRY}"}],"remove":[],"replaceAll":$pinsReplaceAll},
         "deviceRegister":{"blob":"${TestRotation.NEW_REGISTER}","revision":$registerRevision},
         "keyChainStep":{"identitySignature":"$stepIdentitySignature","proofSignature":"$stepProofSignature"},
         "stageId":"$stageId","stagedGrantsDigest":"$stagedGrantsDigest",
         "unreadableScopeIds":[${unreadableScopeIds.joinToString(",") { "\"$it\"" }}],
         "authPublicKey":"$authPublicKey","attestationSignature":"$attestationSignature",
         "secretKeyProofPublicKey":"$secretKeyProofPublicKey","secretKeyProofPossession":"$secretKeyProofPossession"}
    """.trimIndent()
}

/**
 * A rotation of [userId] from [current] to [next], driven through the real routes the way a client drives it
 * (cr-secure-the-account): read the plan, open a stage declaring [next]'s identity, stage a self-grant per scope signed
 * by it, then publish with the key-chain step and every proof over the statements the server rebuilds. Each signature
 * is real (the server verifies them), each ciphertext a stand-in (it opens none).
 */
class TestRotation(
    val userId: String,
    val current: TestAccountKeys,
    val next: TestAccountKeys = TestAccountKeys(),
    val bearer: () -> String,
) {
    /** One scope of the plan: what a self-grant states. */
    data class PlanScope(val scopeId: String, val kind: String, val currentEpoch: Int)

    fun plan(): List<PlanScope> {
        val r = given().header("Authorization", bearer()).`when`().get("/api/account/rotation/plan").then().statusCode(200).extract()
        val ids: List<String> = r.path("scopeId")
        val kinds: List<String> = r.path("kind")
        val epochs: List<Int> = r.path("currentEpoch")
        return ids.indices.map { PlanScope(ids[it], kinds[it], epochs[it]) }
    }

    fun openStage(identityPublicKey: String = next.bundle): Response =
        given().header("Authorization", bearer()).contentType(ContentType.JSON)
            .body("""{"identityPublicKey":"$identityPublicKey"}""").`when`().post("/api/account/rotation/stage")

    fun openStageId(): String = openStage().then().statusCode(201).extract().path("stageId")

    /** A self-grant of [scope] at [epoch] (its current one), signed by [signer] (the new identity) and naming [recipient]. */
    fun selfGrant(
        scope: PlanScope, epoch: Int = scope.currentEpoch, wrapped: String = SELF_GRANT,
        signer: TestEd25519 = next.identity, recipient: String = next.bundle,
    ): StagedGrant {
        val stmt = GrantSignature.statement(scope.scopeId, scope.kind, epoch.toLong(), userId, recipient, wrapped)
        return StagedGrant(scope.scopeId, epoch, wrapped, signer.signB64(stmt), TestGrants.digest(recipient))
    }

    fun stage(stageId: String, grants: List<StagedGrant>): Response =
        given().header("Authorization", bearer()).contentType(ContentType.JSON)
            .body("""{"grants":[${grants.joinToString(",") { it.json() }}]}""")
            .`when`().put("/api/account/rotation/stage/$stageId/grants")

    /** The account's chain head and newest index, read from the server. */
    fun head(): Pair<ByteArray, Int> {
        val r = given().header("Authorization", bearer()).`when`().get("/api/people/$userId/key-chain").then().statusCode(200).extract()
        val steps: List<Int> = r.path("steps.index")
        return Base64.getDecoder().decode(r.path<String>("head")) to steps.max()
    }

    /** The step statement the server rebuilds: from the stored head to [next]'s keys. */
    fun stepStatement(previous: Pair<ByteArray, Int> = head()): ByteArray = requireNotNull(
        KeyChainStatements.step(
            userId, previous.second + 1L, previous.first, current.bundle, current.proof.publicKey, next.bundle, next.proof.publicKey,
        ),
    )

    /** A publish of [stageId] naming [staged] (and [unreadable]), every signature valid against the account as seeded. */
    fun publish(
        stageId: String, staged: List<StagedGrant>, unreadable: List<String> = emptyList(),
        step: ByteArray = stepStatement(),
    ): Publish {
        val digest = requireNotNull(RotationStageDigest.of(staged.map { RotationStageDigest.Entry(it.scopeId, it.epoch.toLong(), it.wrappedGrant) }))
        val statement = statement(step, stageId, digest)
        return Publish(
            identityPublicKey = next.bundle, wrappedIdentityPrivateKey = NEW_PRIV, passwordWrap = NEW_PW_WRAP,
            recoveryWrap = NEW_REC_WRAP, kdf = KDF, secretKeyProofSignature = current.proof.signB64(statement),
            pinsRevision = 0, pinsReplaceAll = true, registerRevision = 0,
            stepIdentitySignature = current.identity.signB64(step), stepProofSignature = current.proof.signB64(step),
            stageId = stageId, stagedGrantsDigest = b64(digest), unreadableScopeIds = unreadable,
            authPublicKey = next.auth.publicKey, attestationSignature = b64(current.auth.sign(next.auth.raw)),
            secretKeyProofPublicKey = next.proof.publicKey, secretKeyProofPossession = next.proof.signB64(statement),
        )
    }

    /** The rotation proof statement for [step], [stageId] and [digest] — what both proof keys sign. */
    fun statement(step: ByteArray, stageId: String, digest: ByteArray): ByteArray = requireNotNull(
        SecretKeyProof.rotationStatement(
            currentIdentityPublicKey = current.bundle, identityPublicKey = next.bundle, wrappedIdentityPrivateKey = NEW_PRIV,
            passwordWrap = SecretKeyProof.Wrap(NEW_PW_WRAP, KDF), recoveryWrap = SecretKeyProof.Wrap(NEW_REC_WRAP, KDF),
            keyChainStepStatement = step, currentAuthPublicKey = current.auth.publicKey, authPublicKey = next.auth.publicKey,
            secretKeyProofPublicKey = next.proof.publicKey, stageId = stageId, stagedGrantsDigest = digest,
        ),
    )

    fun send(publish: Publish, accessToken: String? = null): Response =
        given().header("Authorization", accessToken?.let { "Bearer $it" } ?: bearer()).contentType(ContentType.JSON)
            .body(publish.json()).`when`().post("/api/account/rotation")

    /** The whole ceremony's server half: plan, stage every scope at its current epoch, publish. */
    fun run(accessToken: String? = null, unreadable: Set<String> = emptySet()): Response {
        val stageId = openStageId()
        val grants = plan().filter { it.scopeId !in unreadable }.map { selfGrant(it) }
        grants.chunked(256).forEach { stage(stageId, it).then().statusCode(204) }
        return send(publish(stageId, grants, unreadable.toList()), accessToken)
    }

    companion object {
        const val KDF = "a2RmLXBhcmFtcw=="
        const val NEW_PRIV = "bmV3LXdyYXBwZWQtcHJpdg=="
        const val NEW_PW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val NEW_REC_WRAP = "bmV3LXJlY292ZXJ5LXdyYXA="
        const val NEW_PINS = "bmV3LXBpbnM="
        const val NEW_PIN_ID = "0202020202020202020202020202020202020202020202020202020202020202"
        /** A sealed pin is exactly 221 bytes, 296 base64 characters (`IdentityPinEntry.entry`); any other length is a 400. */
        val NEW_ENTRY: String = Base64.getEncoder().encodeToString(ByteArray(221) { 0x6e })
        const val NEW_REGISTER = "bmV3LXJlZ2lzdGVy"
        const val SELF_GRANT = "c2VsZi1ncmFudC10by10aGUtbmV3LWlkZW50aXR5"
    }
}

private fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b
private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
