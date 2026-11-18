package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.MemberVerification
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.Verifications
import me.raddatz.veyq.mobile.crypto.b64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `countVerifications`' rules (ADR 0111 decisions 12–14, ADR 0148 decision 3) over real seals: a row counts only when it
 * opens at its slot, names the subject's current head, and verifies under the verifier's current key at the verifier's
 * current head — this account at its own head, anyone else anchored in the tenant.
 */
class VerificationCountTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val verifications = Verifications(suite)
    private val tenantId = "c0ffee00-0000-4000-8000-0000000000f1"
    private val tenantKey = p.randomBytes(32)
    private val epoch = 2L

    private inner class Who(val userId: String) {
        val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val public = suite.identityPublic(identity).serialize()
        val head = p.randomBytes(32)
        fun member() = ScopeMember(userId, userId.takeLast(4), 0, ScopeMember.Role.MEMBER, b64(public), b64(head), false)
    }

    private val me = Who("c0ffee00-0000-4000-8000-00000000000a")
    private val ann = Who("c0ffee00-0000-4000-8000-00000000000b")
    private val bob = Who("c0ffee00-0000-4000-8000-00000000000c")
    private val members = listOf(me, ann, bob).associate { it.userId to it.member() }

    /** [verifier]'s verification of [subject] at [subjectHead], signed by [signSeed] naming [verifierHead]. */
    private fun row(
        verifier: Who, subject: Who, subjectHead: ByteArray = subject.head, verifierHead: ByteArray = verifier.head,
        signSeed: ByteArray = verifier.identity.signSeed, rowSubject: Who = subject,
    ): MemberVerification {
        val a = Verifications.Address(tenantId, verifier.userId, subject.userId)
        val signed = verifications.sign(a, subjectHead, verifierHead, signSeed)
        val stored = Verifications.Address(tenantId, verifier.userId, rowSubject.userId)
        val sealed = JoinRecords(suite).sealValueField(tenantId, epoch, tenantKey, verifications.context(stored, 1), verifications.encode(signed))
        return MemberVerification(verifier.userId, rowSubject.userId, 1, b64(sealed.sealed), b64(sealed.wrappedValueKey), "2026-11-18T10:00:00Z")
    }

    private suspend fun count(rows: List<MemberVerification>, anchoredVerifiers: Set<String> = setOf(ann.userId, bob.userId)) =
        VerificationCount(suite, tenantId, me.userId, me.public).count(
            rows, listOf(me, ann, bob).associate { it.userId to b64(it.head) }, members, tenantKey, epoch, b64(me.head),
        ) { id, _ -> id in anchoredVerifiers }

    @Test
    fun aVerificationOfTheCurrentHeadCountsAndMineCountsAtMyHead() = runTest {
        assertEquals(mapOf(bob.userId to listOf(ann.userId, me.userId)), count(listOf(row(ann, bob), row(me, bob))))
    }

    @Test
    fun aRotatedSubjectDropsEveryVerificationByItself() = runTest {
        assertEquals(emptyMap(), count(listOf(row(ann, bob, subjectHead = p.randomBytes(32)))))
    }

    @Test
    fun aVerifierAtAnotherHeadOrWithARetiredKeyDoesNotCount() = runTest {
        assertEquals(emptyMap(), count(listOf(row(ann, bob, verifierHead = p.randomBytes(32)))))
        // the thief's case of decision 13: a statement by a key that is not the verifier's current one
        assertEquals(emptyMap(), count(listOf(row(ann, bob, signSeed = p.randomBytes(32)))))
    }

    @Test
    fun aVerifierThisTenantDoesNotAnchorDoesNotCount() = runTest {
        assertEquals(emptyMap(), count(listOf(row(ann, bob)), anchoredVerifiers = emptySet()))
    }

    @Test
    fun aRowMovedToAnotherSubjectNeitherOpensNorCounts() = runTest {
        // ann's verification of bob, stored as her verification of "me": its seal is bound to bob's slot, its statement to bob
        assertTrue(count(listOf(row(ann, bob, rowSubject = me))).isEmpty())
        // and the same row stored where it belongs counts — the refusal above is the slot's, not the row's
        assertEquals(mapOf(bob.userId to listOf(ann.userId)), count(listOf(row(ann, bob))))
    }
}
