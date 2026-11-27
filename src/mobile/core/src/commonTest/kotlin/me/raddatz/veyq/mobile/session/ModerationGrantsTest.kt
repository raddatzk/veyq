package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.ModerationEpochGrant
import me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDelivery
import me.raddatz.veyq.mobile.api.models.StagedModerationGrant
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moderation key as the core handles it (inbox #965), the web's `ModerationKeyStage` and `sealForServerOwner` over real
 * seals (the bundle held to the `moderation_key` vector by the conformance run, the seal to `seal.asym['moderation-grant']`):
 * a grant re-sealed to the new identity only where it opens and its published bundle is its own — else declared —, the
 * stage's delta and chunks, and the hand-over sealed to the Server Owner only for epochs held. Bitten (the CR's log).
 */
class ModerationGrantsTest {
    private val p = testPrimitives
    private val suite = V1(p)

    private fun identity() = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))

    /** Epoch [epoch]'s grant of [m] sealed to [to], its published bundle [published]'s (default: [m]'s own). */
    private fun grant(epoch: Int, m: ByteArray, to: IdentityPrivate, published: ByteArray = m): ModerationEpochGrant {
        val bundle = suite.moderationBundle(published)
        val sealed = suite.sealTo(ModerationGrants.context(epoch), suite.identityPublic(to).kem, m)
        return ModerationEpochGrant(epoch, b64(bundle.kem), b64(bundle.sign), b64(sealed))
    }

    private fun opened(epoch: Int, blob: String, by: IdentityPrivate) = suite.openAs(ModerationGrants.context(epoch), suite.xwingKey(by.kemSeed), unb64(blob))

    @Test
    fun aGrantIsResealedToTheNewIdentityOnlyWhereItOpensAndItsBundleIsItsOwn() {
        val old = identity()
        val next = identity()
        val m = p.randomBytes(32)
        val staged = ModerationGrants.resealOrDeclare(suite, suite.xwingKey(old.kemSeed), suite.identityPublic(next).kem, grant(3, m, old))
        assertEquals(3, staged.epoch)
        assertContentEquals(m, opened(3, assertNotNull(staged.sealedGrant), next), "the new identity does not open M_n")
        assertFailsWith<Exception>("bound to its epoch") { opened(4, staged.sealedGrant!!, next) }

        // the published bundle is another key's: declared, nothing re-sealed
        val disowned = grant(3, m, old, published = p.randomBytes(32))
        assertNull(ModerationGrants.resealOrDeclare(suite, suite.xwingKey(old.kemSeed), suite.identityPublic(next).kem, disowned).sealedGrant)
        // sealed to someone else: does not open, declared
        val foreign = grant(3, m, identity())
        assertNull(ModerationGrants.resealOrDeclare(suite, suite.xwingKey(old.kemSeed), suite.identityPublic(next).kem, foreign).sealedGrant)
    }

    @Test
    fun theStagePutsUpOnlyWhatMovedInChunksAndStartsOverOnANewStage() = runTest {
        val old = identity()
        val next = identity()
        val sent = mutableListOf<Pair<String, List<StagedModerationGrant>>>()
        val stage = ModerationKeyStage(suite, old, suite.identityPublic(next).kem) { id, grants -> sent += id to grants }
        val held = (1..300).map { grant(it, p.randomBytes(32), old) }
        assertTrue(stage.moved("s1", held))
        stage.put("s1", held)
        assertEquals(listOf(256, 44), sent.map { it.second.size }, "chunks of the contract's maxItems")
        assertTrue(sent.flatMap { it.second }.all { it.sealedGrant != null })
        assertTrue(!stage.moved("s1", held), "nothing moved")
        stage.put("s1", held)
        assertEquals(2, sent.size, "an unmoved epoch was staged again")

        // an epoch delivered anew meanwhile (another grant of it): only that one goes up
        val again = held.toMutableList().also { it[9] = grant(10, p.randomBytes(32), old) }
        assertTrue(stage.moved("s1", again))
        stage.put("s1", again)
        assertEquals(listOf(10), sent.last().second.map { it.epoch })

        // a stage opened afresh holds nothing: everything again
        stage.put("s2", again)
        assertEquals(300, sent.filter { it.first == "s2" }.sumOf { it.second.size })

        // a non-moderator stages nothing
        val none = mutableListOf<StagedModerationGrant>()
        ModerationKeyStage(suite, old, suite.identityPublic(next).kem) { _, g -> none.addAll(g) }.put("s3", emptyList())
        assertTrue(none.isEmpty())
    }

    @Test
    fun theHandOverSealsEachHeldEpochToTheServerOwnerAndOpensNothingOnAMismatch() {
        val me = identity()
        val owner = identity()
        val m1 = p.randomBytes(32)
        val m2 = p.randomBytes(32)
        val grants = listOf(grant(1, m1, me), grant(2, m2, me), ModerationEpochGrant(5, "", "", null))
        val opened = ModerationGrants.open(suite, suite.xwingKey(me.kemSeed), grants)
        assertEquals(setOf(1, 2), opened.keys)
        val ownerPub = b64(suite.identityPublic(owner).serialize())
        val upload = ModerationGrants.forServerOwner(suite, opened, ServerOwnerKeyDelivery("so", ownerPub, "head", listOf(2)))
        assertEquals(ownerPub, upload.identityPublicKey)
        assertEquals(listOf(2), upload.grants.map { it.epoch })
        assertContentEquals(m2, opened(2, upload.grants.single().sealedGrant, owner), "the Server Owner does not open M_2")

        val refused = assertFailsWith<ModerationGrants.DeliveryRefused> {
            ModerationGrants.forServerOwner(suite, opened, ServerOwnerKeyDelivery("so", ownerPub, "head", listOf(2, 7)))
        }
        assertEquals(ModerationGrants.DeliveryRefused.Reason.NOT_HELD, refused.reason)

        val mismatch = assertFailsWith<ModerationGrants.BundleMismatch> {
            ModerationGrants.open(suite, suite.xwingKey(me.kemSeed), listOf(grant(1, m1, me), grant(2, m2, me, published = m1)))
        }
        assertEquals(2, mismatch.epoch)
    }
}
