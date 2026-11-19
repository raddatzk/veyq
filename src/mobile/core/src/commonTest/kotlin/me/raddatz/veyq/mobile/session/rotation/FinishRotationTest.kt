package me.raddatz.veyq.mobile.session.rotation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.api.memories.models.OwnItemFavoritesEntry
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.ItemMarks
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The finish of an account rotation (`finishRotation`, `commitRotation`, the scope-level re-key) against a server it plays
 * ([PlayedRotation]), the web's `finishRotation.test.ts` / `scopes.test.ts` / `scopeRekey.test.ts` cases the CR names:
 * every member re-granted and the succession verifiable, an anomaly declared pending, an open invite carried, a stale
 * epoch rebuilt once and then refused, a stale stage restaged, a wrap that does not open staged as unopenable; the finish
 * single-flight, run at the unlock, and its 45 s loop stopped at the close.
 */
class FinishRotationTest {
    private val p = testPrimitives
    private val trust = Trust(me.raddatz.veyq.mobile.crypto.V1(p))

    /** The account resumed and its unlock pass over (an account that never rotated: one states read). */
    private suspend fun opened(server: PlayedRotation): VeyqAccount {
        val account = server.resume()
        realTime { while (server.count("states") < 1) delay(5) }
        account.finishRotation() // joins the unlock's pass, or runs one more over nothing
        return account
    }

    /** [block] in real time on the default dispatcher (the account's background is no test dispatcher). */
    private suspend fun <T> realTime(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    @Test
    fun aWaitingScopeIsCommittedWithEveryAnchoredMemberReGrantedAndTheSuccessionSigned() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.waiting(account)

        val states = realTime { account.finishRotation() }

        assertEquals(listOf(RotationScopeState.State.secured), states.map { it.state })
        val request = server.rotations.single()
        assertEquals(server.epoch + 1, request.epoch)
        // the new key, as the account's own re-grant carries it
        val mine = request.grants.single { it.userId == server.me.userId }
        val newKey = server.suite.openAs(Context("scope-grant", "scopeId" to server.tenantId, "epoch" to 4L), server.me.kem, unb64(mine.wrappedGrant))
        // B (anchored on first use) holds the same key, the grant signed by the account
        val toB = request.grants.single { it.userId == server.b.userId }
        assertContentEquals(newKey, server.suite.openAs(Context("scope-grant", "scopeId" to server.tenantId, "epoch" to 4L), server.b.kem, unb64(toB.wrappedGrant)))
        val statement = server.suite.grantStatement(server.tenantId, "TENANT", 4, server.b.userId, server.b.bundle, unb64(toB.wrappedGrant))
        assertTrue(p.ed25519Verify(p.ed25519Public(server.me.identity.signSeed), statement, unb64(toB.grantSignature)))
        assertEquals(b64(p.sha256(server.b.bundle)), toB.recipientIdentityDigest)
        // C, a member with no join record, is declared pending — never skipped, never granted
        assertEquals(listOf(server.c.userId), request.pendingUserIds)
        assertEquals(setOf(server.me.userId, server.b.userId), request.grants.map { it.userId }.toSet())
        // the succession: the new key's succession key, signed by E_3's
        val (newPublic, newSeed) = trust.successionKeyPair(newKey)
        newSeed.fill(0)
        assertEquals(b64(newPublic), request.successionPublicKey)
        val (oldPublic, oldSeed) = trust.successionKeyPair(server.k3)
        oldSeed.fill(0)
        assertTrue(trust.verifySuccession(oldPublic, server.tenantId, 4, newPublic, unb64(request.successionSignature)))
        assertTrue(trust.verifySuccessionPath(server.tenantId, 3, oldPublic, listOf(Trust.SuccessionLink(4, newPublic, unb64(request.successionSignature))), 4, newPublic))
    }

    @Test
    fun aTenantsOpenInviteIsCarriedAndOneItsOwnerDidNotSignIsDeclared() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.waiting(account)

        realTime { account.finishRotation() }

        val request = server.rotations.single()
        val carried = request.inviteGrants.orEmpty().single()
        assertEquals(server.vouchedId, carried.inviteId)
        val mine = request.grants.single { it.userId == server.me.userId }
        val newKey = server.suite.openAs(Context("scope-grant", "scopeId" to server.tenantId, "epoch" to 4L), server.me.kem, unb64(mine.wrappedGrant))
        assertContentEquals(newKey, server.suite.openAs(Context("scope-grant", "scopeId" to server.tenantId, "epoch" to 4L), server.vouched.kem, unb64(carried.wrappedGrant)))
        assertEquals(listOf(server.unvouchedId), request.pendingInviteIds)
    }

    @Test
    fun theScopeLevelMoveReWrapsEveryValueKeyAndStagesOneThatDoesNotOpenAsUnopenable() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.waiting(account)

        realTime { account.finishRotation() }

        val request = server.rotations.single()
        val newKey = server.suite.openAs(
            Context("scope-grant", "scopeId" to server.tenantId, "epoch" to 4L), server.me.kem, unb64(request.grants.single { it.userId == server.me.userId }.wrappedGrant),
        )
        val (rekeyId, stage) = server.stages.single()
        assertEquals(request.rekeyId, rekeyId)
        assertEquals(server.epoch, stage.fromEpoch)
        val values = ValueKeys(server.suite)
        // B's name key moved: the same K_v under the new key, beside the wrap it replaces
        val b = stage.members.orEmpty().single { it.userId == server.b.userId }.name!!
        assertContentEquals(server.bNameKey, values.unwrap(newKey, 4, server.nameCtx(server.b.userId), unb64(b.wrappedValueKey)))
        // C's did not open under E_3: staged — not dropped — as a wrap nobody opens, of the same length
        val c = stage.members.orEmpty().single { it.userId == server.c.userId }.name
        assertNotNull(c, "the wrap that did not open was dropped")
        assertTrue(runCatching { values.unwrap(newKey, 4, server.nameCtx(server.c.userId), unb64(c.wrappedValueKey)) }.isFailure)
        assertEquals(unb64(c.replacedValueKey).size, unb64(c.wrappedValueKey).size)
        assertContentEquals(server.iconKey, values.unwrap(newKey, 4, server.iconCtx, unb64(stage.icon!!.wrappedValueKey)))
        // the trashed root entry re-sealed at the next placement revision, its key unchanged, no slug token
        val root = stage.rootNodes.orEmpty().single()
        assertEquals("Trip", server.suite.openField(server.rootAt(3), newKey, unb64(root.encryptedName)).decodeToString())
        assertContentEquals(server.nodeKey, server.suite.open(server.keyAt(3), newKey, unb64(root.wrappedContentKey)))
        assertNull(root.slugToken)
        // Memories' item mark, through its participant, under the same re-key id
        val (markRekey, marks) = server.markStages.single()
        assertEquals(request.rekeyId, markRekey)
        assertContentEquals(server.markKey, values.unwrap(newKey, 4, server.markCtx, unb64(marks.marks.orEmpty().single().wrappedValueKey)))
    }

    @Test
    fun aStaleEpochIsRebuiltOnceFromTheMembersReadAgainAndTheSecondRefusalStands() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.waiting(account)
        // a server that refuses every attempt (succeeding only from the sixth): a bound, never a loop
        repeat(5) { server.rotateAnswers.addLast(409 to "stale_epoch") }
        val membersBefore = server.count("members")

        realTime { account.finishRotation() }

        assertEquals(2, server.rotations.size, "the rebuild is bounded to one")
        // the rebuild read the members again (the finish's read, then the rebuild's), from a fresh key
        assertEquals(membersBefore + 2 + 2, server.count("members"))
        assertFalse(server.rotations[0].successionPublicKey == server.rotations[1].successionPublicKey, "the key was reused")
    }

    @Test
    fun aRekeyPartsStaleCommitIsStagedAgainUnderTheSameKeyAndId() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.waiting(account)
        server.rotateAnswers.addLast(409 to "rekey_parts_stale")

        val states = realTime { account.finishRotation() }

        assertEquals(listOf(RotationScopeState.State.secured), states.map { it.state })
        assertEquals(2, server.rotations.size)
        assertEquals(server.rotations[0], server.rotations[1], "the restage sent another commit")
        assertEquals(listOf(server.rotations[0].rekeyId, server.rotations[0].rekeyId), server.stages.map { it.first })
        assertEquals(2, server.markStages.size)
    }

    @Test
    fun theFinishRunsAtTheUnlockAndIsSingleFlight() = runTest {
        val server = PlayedRotation(p)
        // the unlock's pass, unasked
        val account = server.resume()
        realTime { while (server.count("states") < 1) delay(5) }
        account.finishRotation()
        val before = server.count("states")

        val gate = CompletableDeferred<Unit>()
        server.statesGate = gate
        val first = async(Dispatchers.Default) { account.finishRotation() }
        realTime { while (server.count("states") < before + 1) delay(5) }
        val second = async(Dispatchers.Default) { account.finishRotation() }
        delay(50)
        gate.complete(Unit)
        realTime { first.await(); second.await() }
        assertEquals(before + 1, server.count("states"), "a second pass raced the first")
    }

    @Test
    fun theLoopStopsAtTheClose() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        assertTrue(account.rotations.looping)
        account.retire()
        assertFalse(account.rotations.looping)
        assertFailsWith<Exception> { account.finishRotation() }
    }

    @Test
    fun aMasterKeyRotationReTagsOnlyTheAccountsOwnMarksOfTheScopesNamed() = runTest {
        val server = PlayedRotation(p)
        val account = opened(server)
        server.myScopes = listOf(server.tenantScope(account))
        val suite = server.suite
        val marks = ItemMarks(suite)
        val values = ValueKeys(suite)
        val oldMk = p.randomBytes(32)
        val newMk = p.randomBytes(32)
        val ids = listOf(server.uuid(), server.uuid())
        fun row(scopeId: String, nodeId: String, tagKey: ByteArray): OwnItemFavoritesEntry {
            val ctx = Context("item-favorites", "scopeId" to scopeId, "nodeId" to nodeId)
            val kv = p.randomBytes(32)
            val plain = marks.encode(marks.selfTag(tagKey, scopeId, nodeId, ids), ids)
            return OwnItemFavoritesEntry(scopeId, nodeId, b64(suite.seal(ctx, kv, plain)), b64(values.wrap(server.k3, 3, ctx, kv)))
        }
        val mine = server.uuid()
        val notMine = server.uuid()
        server.ownMarks = listOf(
            row(server.tenantId, mine, marks.selfKey(oldMk)),
            // a co-member's set the listing slipped in: its tag does not verify under the old key, so it is not re-tagged
            row(server.tenantId, notMine, marks.selfKey(p.randomBytes(32))),
            // a scope the rotation did not name
            row(server.uuid(), server.uuid(), marks.selfKey(oldMk)),
        )

        realTime { me.raddatz.veyq.mobile.AppKinds.masterKeyRotated(account, oldMk, newMk, listOf(server.tenantId)) }

        val (scopeId, nodeId, put) = server.markPuts.single()
        assertEquals(server.tenantId to mine, scopeId to nodeId)
        assertEquals(3, put.epoch)
        val ctx = Context("item-favorites", "scopeId" to scopeId, "nodeId" to nodeId)
        val kv = values.unwrap(server.k3, 3, ctx, unb64(put.wrappedValueKey))
        val (tag, opened) = assertNotNull(marks.decode(suite.open(ctx, kv, unb64(put.ciphertext))))
        assertEquals(ids.sorted(), opened)
        assertTrue(marks.selfTagVerifies(marks.selfKey(newMk), scopeId, nodeId, opened, tag))
        assertFalse(marks.selfTagVerifies(marks.selfKey(oldMk), scopeId, nodeId, opened, tag))
    }

    @Test
    fun theLoopPassesEvery45SecondsWhileOnlineAndNotAfterItsScopeEnds() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        var online = true
        var passes = 0
        val finisher = RotationFinisher(scope, { online }) { passes++; emptyList() }
        finisher.start()
        runCurrent()
        assertEquals(1, passes, "no pass at the start")
        advanceTimeBy(44.seconds)
        runCurrent()
        assertEquals(1, passes)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, passes)
        online = false
        advanceTimeBy(45.seconds)
        runCurrent()
        assertEquals(2, passes, "a pass ran offline")
        online = true
        job.cancel() // the account's close
        advanceTimeBy(135.seconds)
        runCurrent()
        assertEquals(2, passes, "the loop outlived its account")
        assertFalse(finisher.looping)
    }
}
