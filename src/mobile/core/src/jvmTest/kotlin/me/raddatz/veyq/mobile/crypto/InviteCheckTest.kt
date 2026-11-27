package me.raddatz.veyq.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The invitee's checks (ADR 0111 decisions 1 and 19), the port of `src/packages/crypto/src/inviteCheck.test.ts`: [Invites.checkInvite]
 * against a served invite built from the vectors' owner (`#invite_key.owner`, whose chain's step 0 is `#invite_link`'s
 * signer head). Each refusal has its negatives — each passing every check before its own — beside a positive control on
 * the same predicate, so a check that refused everything would be red as surely as one that refused nothing.
 */
class InviteCheckTest {
    private val p = BouncyCastlePrimitives
    private val suite = V1(p)
    private val trust = Trust(suite)
    private val invites = Invites(suite)
    private val proofs = AccountProofs(p)
    private val v = Json.parseToJsonElement(File(System.getProperty("veyq.vectors")!!).readText()).jsonObject
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val k = v.o("invite_key")
    private val tenant = k.o("context").s("tenantId")
    private val inviteId = k.o("context").s("inviteId")
    private val expires = k.o("context")["expiresAt"]!!.jsonPrimitive.long
    private val owner = k.o("owner").s("user_id")
    private val other = "c0ffee00-0000-4000-8000-0000000000e0"
    private val seed = unhex(k.s("seed"))
    private val inviteKey = invites.inviteKeyFromSeed(seed)
    private val inviteBundle = inviteKey.pub.serialize()

    private class Keys(val bundle: ByteArray, val sign: ByteArray, val proofPub: ByteArray, val proofSeed: ByteArray)

    private fun freshKeys(): Keys {
        val id = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val proofSeed = proofs.secretKeyProofSeed(p.randomBytes(32))
        return Keys(suite.identityPublic(id).serialize(), id.signSeed, p.ed25519Public(proofSeed), proofSeed)
    }

    private val born = Keys(
        unhex(k.o("owner").s("bundle")), unhex(v.o("grant_signature").s("sign_seed")),
        unhex(v.o("secret_key_proof").o("key").s("public_key")), unhex(v.o("secret_key_proof").o("key").s("private_key")),
    )

    private fun genesis(keys: Keys) = Trust.KeyChainLink(0, null, keys.bundle, keys.proofPub, null, null)
    private fun headOf0(userId: String, keys: Keys) = trust.keyChainHead(trust.keyChainGenesisStatement(userId, Trust.KeyChainKeys(keys.bundle, keys.proofPub)))

    private fun stepTo(userId: String, index: Long, previousHead: ByteArray, retired: Keys, installed: Keys, proofBy: ByteArray = retired.proofSeed): Pair<Trust.KeyChainLink, ByteArray> {
        val stmt = trust.keyChainStepStatement(userId, index, previousHead, Trust.KeyChainKeys(retired.bundle, retired.proofPub), Trust.KeyChainKeys(installed.bundle, installed.proofPub))
        val link = Trust.KeyChainLink(index, previousHead, installed.bundle, installed.proofPub, p.ed25519Sign(retired.sign, stmt), p.ed25519Sign(proofBy, stmt))
        return link to trust.keyChainHead(stmt)
    }

    private val head0 = headOf0(owner, born)
    private val secured = freshKeys()
    private val step1 = stepTo(owner, 1, head0, born, secured)

    private class Grant(val wrapped: ByteArray, val signature: ByteArray)

    private fun grantTo(signKey: ByteArray, epoch: Long, key: ByteArray, wrapped: ByteArray? = null): Grant {
        val w = wrapped ?: suite.sealTo(Context("scope-grant", "scopeId" to tenant, "epoch" to epoch), inviteKey.pub.kem, key)
        return Grant(w, p.ed25519Sign(signKey, suite.grantStatement(tenant, "TENANT", epoch, inviteId, inviteBundle, w)))
    }

    private fun succession(epoch: Long, newer: ByteArray, older: ByteArray, scopeId: String = tenant): Trust.SuccessionLink {
        val pub = trust.successionKeyPair(newer).first
        return Trust.SuccessionLink(epoch, pub, p.ed25519Sign(trust.successionKeyPair(older).second, trust.scopeSuccessionStatement(scopeId, epoch, pub)))
    }

    private fun signInvite(signKey: ByteArray) = invites.signInviteKey(signKey, tenant, inviteId, inviteBundle, expires)

    private val e0 = p.randomBytes(32)
    private val e1 = p.randomBytes(32)
    private val e2 = p.randomBytes(32)
    private val payload = Invites.LinkPayload(seed, head0, owner)

    private val anchor0 = grantTo(born.sign, 0, e0)
    private val fresh = Invites.ServedInvite(
        tenant, inviteId, inviteBundle, unhex(k.s("signature")), expires, owner, born.bundle, listOf(genesis(born)),
        anchor0.wrapped, 0, anchor0.signature, anchor0.wrapped, 0, anchor0.signature, born.bundle, emptyList(),
    )

    private val g = freshKeys()
    private val anchorBySecured = grantTo(secured.sign, 0, e0)
    private val carried2 = grantTo(g.sign, 2, e2)
    private val rotated = fresh.copy(
        inviteKeySignature = signInvite(secured.sign),
        signerIdentityPublicKey = secured.bundle,
        signerKeyChain = listOf(genesis(born), step1.first),
        anchorWrappedGrant = anchorBySecured.wrapped, anchorGrantSignature = anchorBySecured.signature,
        wrappedGrant = carried2.wrapped, grantedEpoch = 2, grantSignature = carried2.signature, granterIdentityPublicKey = g.bundle,
        successions = listOf(succession(1, e1, e0), succession(2, e2, e1)),
    )

    private fun failure(s: Invites.ServedInvite, pl: Invites.LinkPayload = payload): Invites.CheckFailure? = try {
        invites.checkInvite(pl, s)
        null
    } catch (e: Invites.InviteCheckFailed) {
        e.reason
    }

    private fun flip(b: ByteArray, i: Int = 0) = b.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }

    @Test
    fun theControlsPass() {
        val a = invites.checkInvite(payload, fresh)
        assertContentEquals(e0, a.epochKey)
        assertEquals(0, a.grantedEpoch)
        assertContentEquals(head0, a.signerHead)
        assertEquals(0, a.signerIndex)
        assertContentEquals(born.bundle, a.signerIdentityPublicKey)
        val b = invites.checkInvite(payload, rotated)
        assertContentEquals(e2, b.epochKey)
        assertEquals(2, b.grantedEpoch)
        assertContentEquals(step1.second, b.signerHead)
        assertEquals(1, b.signerIndex)
        assertContentEquals(secured.bundle, b.signerIdentityPublicKey)
        assertNull(failure(rotated.copy(successions = rotated.successions.reversed())))
    }

    @Test
    fun keyAndSignerMismatches() {
        assertEquals(Invites.CheckFailure.KEY_MISMATCH, failure(fresh.copy(invitePublicKey = invites.inviteKeyFromSeed(ByteArray(32) { 9 }).pub.serialize())))
        assertEquals(Invites.CheckFailure.SIGNER_MISMATCH, failure(fresh.copy(signerUserId = other)))
    }

    @Test
    fun signerChainMismatches() {
        val operator = freshKeys()
        val a = grantTo(born.sign, 0, e0)
        for ((name, served) in listOf(
            "the retired key after the owner secured the account" to rotated.copy(
                signerIdentityPublicKey = born.bundle, inviteKeySignature = unhex(k.s("signature")), anchorWrappedGrant = a.wrapped, anchorGrantSignature = a.signature,
            ),
            "an operator's chain" to fresh.copy(signerIdentityPublicKey = operator.bundle, signerKeyChain = listOf(genesis(operator)), inviteKeySignature = signInvite(operator.sign)),
            "a step the retired identity signed alone" to rotated.copy(signerKeyChain = listOf(genesis(born), stepTo(owner, 1, head0, born, secured, operator.proofSeed).first)),
            "the chain extended by an operator's step" to rotated.copy(
                signerIdentityPublicKey = operator.bundle, signerKeyChain = listOf(genesis(born), step1.first, stepTo(owner, 2, step1.second, operator, operator).first),
                inviteKeySignature = signInvite(operator.sign),
            ),
            "no chain" to fresh.copy(signerKeyChain = emptyList()),
        )) assertEquals(Invites.CheckFailure.SIGNER_CHAIN_MISMATCH, failure(served), name)
        assertEquals(Invites.CheckFailure.SIGNER_CHAIN_MISMATCH, failure(fresh, Invites.LinkPayload(seed, headOf0(other, born), owner)))
    }

    @Test
    fun badSignatures() {
        for (served in listOf(
            fresh.copy(inviteKeySignature = flip(unhex(k.s("signature")))),
            fresh.copy(expiresAt = expires + 60),
            rotated.copy(inviteKeySignature = unhex(k.s("signature"))),
        )) assertEquals(Invites.CheckFailure.BAD_SIGNATURE, failure(served))
    }

    @Test
    fun invalidAnchors() {
        val operator = freshKeys()
        val op = grantTo(operator.sign, 0, e0)
        for (served in listOf(
            fresh.copy(anchorGrantSignature = flip(anchor0.signature, 3)),
            rotated.copy(anchorWrappedGrant = anchor0.wrapped, anchorGrantSignature = anchor0.signature),
            rotated.copy(anchorEpoch = 1),
            fresh.copy(anchorWrappedGrant = op.wrapped, anchorGrantSignature = op.signature),
        )) assertEquals(Invites.CheckFailure.ANCHOR_INVALID, failure(served))
    }

    @Test
    fun unlinkedEpochs() {
        val operator = freshKeys()
        val x = p.randomBytes(32)
        val y = p.randomBytes(32)
        val c1 = grantTo(operator.sign, 1, x)
        val a2 = grantTo(secured.sign, 2, e2)
        val c1g = grantTo(g.sign, 1, e1)
        val c0 = grantTo(operator.sign, 0, x)
        val junk = grantTo(born.sign, 0, e0, p.randomBytes(1200))
        for ((name, served) in listOf(
            "the operator's key under a fabricated granter" to fresh.copy(wrappedGrant = c1.wrapped, grantedEpoch = 1, grantSignature = c1.signature, granterIdentityPublicKey = operator.bundle, successions = listOf(succession(1, x, y))),
            "a gap" to rotated.copy(successions = listOf(succession(2, e2, e1))),
            "a statement for another epoch" to rotated.copy(successions = listOf(succession(2, e2, e1), Trust.SuccessionLink(3, succession(1, e1, e0).successionPublicKey, succession(1, e1, e0).successionSignature))),
            "a statement twice" to rotated.copy(successions = listOf(succession(1, e1, e0), succession(1, e1, e0), succession(2, e2, e1))),
            "a statement for another scope" to rotated.copy(successions = listOf(succession(1, e1, e0, other), succession(2, e2, e1))),
            "a path that skips the anchor" to rotated.copy(successions = listOf(succession(1, e1, y), succession(2, e2, e1))),
            "statements ending at another key" to rotated.copy(successions = listOf(succession(1, e1, e0), succession(2, x, e1))),
            "a carried signature not the granter's" to rotated.copy(granterIdentityPublicKey = operator.bundle),
            "a carried grant below the anchor" to rotated.copy(anchorWrappedGrant = a2.wrapped, anchorEpoch = 2, anchorGrantSignature = a2.signature, wrappedGrant = c1g.wrapped, grantedEpoch = 1, grantSignature = c1g.signature),
            "at the anchor's epoch, not the anchor" to fresh.copy(wrappedGrant = c0.wrapped, grantSignature = c0.signature, granterIdentityPublicKey = operator.bundle),
            "an anchor that does not open" to fresh.copy(anchorWrappedGrant = junk.wrapped, anchorGrantSignature = junk.signature, wrappedGrant = junk.wrapped, grantSignature = junk.signature),
        )) assertEquals(Invites.CheckFailure.EPOCH_UNLINKED, failure(served), name)
    }
}
