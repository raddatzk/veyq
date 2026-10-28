package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.admin.tenant.MemberInviteKey
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Grant material for backend tests (ADR 0028 Decision 6, ADR 0111). The server never opens a grant or checks a grant's
 * signature — it stores and relays them — so any well-formed base64 stands in for the ciphertext and the signature;
 * what a genuine one is, is `packages/crypto`'s to pin. What the server DOES check is real here: the key a grant names
 * (`recipientIdentityDigest`, SHA-256 of the stored bundle) and a member invite's accept proof (an Ed25519 signature
 * by the invite key, [TestInviteKey]).
 */
object TestGrants {
    /** 64 bytes of base64 — the length of a real Ed25519 signature. */
    const val SIGNATURE = "c2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2k="

    /** A grant's signature as a JSON member. */
    const val SIGNATURE_JSON = """"grantSignature":"$SIGNATURE""""

    /** A stand-in grant ciphertext. */
    const val WRAPPED = "AQ=="

    /** A stand-in sealed join record. */
    const val JOIN_RECORD = "c2VhbGVkLWpvaW4tcmVjb3Jk"

    /** SHA-256 of a DECODED identity bundle, base64 — what a grant's `recipientIdentityDigest` must be. */
    fun digest(identityPublicKeyB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(identityPublicKeyB64)))

    /** An OWNER accept's birth grant as JSON members (no braces): the first grant, its signature, and the digest of the
     *  identity it is wrapped to (ADR 0111 decision 10) — [identityPublicKeyB64], the fixture's by default (every
     *  `seedUser` account holds it). */
    fun ownerBirthJson(identityPublicKeyB64: String = TestFixtures.IDENTITY_PUBLIC_KEY, grant: String = "AQ=="): String =
        """"grant":"$grant",$SIGNATURE_JSON,"recipientIdentityDigest":"${digest(identityPublicKeyB64)}""""

    /** The digest of [TestFixtures.IDENTITY_PUBLIC_KEY] — every `seedUser` account's current key unless it says otherwise:
     *  what a scope birth by such an account names (ADR 0111 decision 10). */
    val FIXTURE_DIGEST: String get() = digest(TestFixtures.IDENTITY_PUBLIC_KEY)

    /** The digest of [userId]'s current identity key. */
    fun digestOf(userId: String): String =
        digest(QuarkusTransaction.requiringNew().call { VeyqUser.findById(userId)!!.identityPublicKey })

    /** The scope's current epoch — a grant written at a stated epoch must be stated at it. */
    fun epochOf(scopeId: String): Int = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.currentEpoch }

    /** A grant's fields as JSON members (no braces), stated at [epoch] and naming [recipientIdentityDigest] — for a
     *  request whose scope or recipient does not exist (a gate answers before either is read). */
    fun grantJson(epoch: Int = 0, recipientIdentityDigest: String = digest(WRAPPED)): String =
        """"epoch":$epoch,"wrappedGrant":"$WRAPPED",$SIGNATURE_JSON,"recipientIdentityDigest":"$recipientIdentityDigest""""

    /** A group add's grant fields as JSON members (no braces): of [scopeId]'s current epoch, addressed to [targetUserId]. */
    fun groupAddJson(scopeId: String, targetUserId: String): String = grantJson(epochOf(scopeId), digestOf(targetUserId))

    /** The open member invites of [scopeId] (a TENANT), all declared pending, as the `pendingInviteIds` JSON member a
     *  rotation of it must carry (ADR 0111 decision 2) — read at call time, so it names exactly what the server holds
     *  open. Empty for any other scope. */
    fun openInvitesJson(scopeId: String): String =
        QuarkusTransaction.requiringNew().call {
            TenantInviteEntity.find(
                "tenantId = ?1 and role = ?2 and status = ?3 and expiresAt > ?4",
                scopeId, TenantInviteEntity.MEMBER, TenantInviteEntity.ACTIVE, Instant.now(),
            ).list().map { it.id }
        }.joinToString(",", prefix = "\"pendingInviteIds\":[", postfix = "]") { "\"$it\"" }

    /** Every member of [scopeId] but [covered] (the granted and the removed), declared pending, as the `pendingUserIds`
     *  JSON member (no braces) a rotation that grants only [covered] must carry (ADR 0111 decision 9) — read at call
     *  time, so it names exactly the memberships the server holds. */
    fun othersPendingJson(scopeId: String, vararg covered: String?): String =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("scopeId", scopeId).list().map { it.userId } }
            .filter { it !in covered }.distinct()
            .joinToString(",", prefix = "\"pendingUserIds\":[", postfix = "]") { "\"$it\"" }

    /** A member invite request's fields as JSON members (no braces), for [email], keyed by [key], its grant of
     *  [epoch] (default: the tenant scope's current one, read now), expiring at the server's lifetime (7 days,
     *  `veyq.tenant.invite.ttl`). */
    fun memberInviteJson(
        tenantId: String, email: String, key: TestInviteKey = TestInviteKey(), epoch: Int? = null,
        signerSeed: ByteArray = TestFixtures.IDENTITY_SIGN_SEED,
    ): String {
        val expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        return """"email":"$email","inviteId":"${key.inviteId}","invitePublicKey":"${key.bundle}",""" +
            """"inviteKeySignature":"${inviteKeySignature(signerSeed, tenantId, key, expiresAt)}",""" +
            """"expiresAt":"$expiresAt","sealedLink":"$SEALED_LINK",""" +
            """"epoch":${epoch ?: epochOf(tenantId)},"wrappedGrant":"$WRAPPED",$SIGNATURE_JSON,"recipientIdentityDigest":"${digest(key.bundle)}""""
    }

    /** The inviting owner's real `invite-key` signature by the Ed25519 [signerSeed] — the server checks it under the
     *  caller's stored identity key ([TestFixtures.IDENTITY_PUBLIC_KEY] holds [TestFixtures.IDENTITY_SIGN_SEED]'s). */
    fun inviteKeySignature(signerSeed: ByteArray, tenantId: String, key: TestInviteKey, expiresAt: Instant): String {
        val stmt = InviteAcceptProof.inviteKeyStatement(tenantId, key.inviteId, key.bundle, expiresAt.epochSecond)!!
        val priv = java.security.KeyFactory.getInstance("Ed25519")
            .generatePrivate(java.security.spec.EdECPrivateKeySpec(java.security.spec.NamedParameterSpec.ED25519, signerSeed))
        return Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(priv); update(stmt); sign() })
    }

    /** A stand-in sealed link part (standard base64, as the request carries it). */
    const val SEALED_LINK = "c2VhbGVkLWxpbms+Pz8/"

    /** A MEMBER accept's join fields as JSON members (no braces): a self-grant at [epoch] addressed to
     *  [identityPublicKeyB64], the invite key's proof for [userId], a stand-in join record, and the digest of the signer
     *  key the record names ([signerIdentityDigest]; by default the invite's stored signer key, read at call time —
     *  what an honest client checked, cr-trust-anchors-survive-rotation decision 7). */
    fun memberJoinJson(
        key: TestInviteKey, inviteId: String, userId: String, identityPublicKeyB64: String, epoch: Int = 0,
        signerIdentityDigest: String = storedSignerDigest(inviteId),
    ): String =
        """"selfGrant":{"wrappedGrant":"$WRAPPED",$SIGNATURE_JSON,"epoch":$epoch,"recipientIdentityDigest":"${digest(identityPublicKeyB64)}"},""" +
            """"inviteProof":"${key.proof(inviteId, userId, identityPublicKeyB64)}","sealedJoinRecord":"$JOIN_RECORD",""" +
            """"signerIdentityDigest":"$signerIdentityDigest""""

    /** The digest of [inviteId]'s stored signer key — of a stand-in when the invite holds none (ended, or no invite), so
     *  a request about such an invite stays well-formed and meets the refusal its test is about. */
    fun storedSignerDigest(inviteId: String): String =
        digest(QuarkusTransaction.requiringNew().call<String?> { TenantInviteEntity.findById(inviteId)?.signerIdentityPublicKey } ?: WRAPPED)
}

/**
 * A real invite key for tests: an Ed25519 keypair (the JDK's), its public half in an identity-shaped bundle
 * `lp(kem) || lp(sign)` with a stand-in KEM half, and the proof it signs at accept. [inviteId] is the client-minted id
 * the invite is stored under.
 */
class TestInviteKey(val inviteId: String = UUID.randomUUID().toString()) {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val signRaw: ByteArray = pair.public.encoded.takeLast(32).toByteArray()

    /** The bundle as the wire carries it (standard base64). */
    val bundle: String = Base64.getEncoder().encodeToString(lp(ByteArray(1216) { 7 }) + lp(signRaw))

    /** The invite key's `invite-accept` signature for [userId] and its identity bundle, base64. */
    fun proof(inviteId: String, userId: String, identityPublicKeyB64: String): String {
        val stmt = InviteAcceptProof.statement(inviteId, userId, identityPublicKeyB64)!!
        return Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(pair.private); update(stmt); sign() })
    }

    /** The store's view of an invite under this key, signed by [signerUserId] (key [signerIdentityPublicKey]), its
     *  grant at [epoch] — for seeding an invite through `TenantStore.mintMemberInvite`. */
    fun stored(signerUserId: String, signerIdentityPublicKey: String = "c2lnbmVyLWtleQ==", epoch: Int = 0) = MemberInviteKey(
        inviteId = inviteId,
        invitePublicKey = bundle,
        inviteKeySignature = TestGrants.SIGNATURE,
        signerUserId = signerUserId,
        signerIdentityPublicKey = signerIdentityPublicKey,
        epoch = epoch,
        grant = SignedGrant(TestGrants.WRAPPED, TestGrants.SIGNATURE),
    )

    private fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b
}
