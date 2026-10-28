package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.SignedGrant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The pure rotation decision (ADR 0045 §2), boot-free and mutation-verified. Every assertion pins an exact value —
 * a bare "not empty" would let a mutant that grants the *wrong* set pass (the assertion-strength trap ADR 0045 exists
 * to close). The completeness cases — every open tenant invite is re-granted or declared pending (ADR 0111 decision 2)
 * — mirror the slice `InvitePlaceholderSliceTest.aRotationOfTheTenantMustCarryEveryOpenInvite`, here without a Quarkus
 * boot per mutant.
 */
class RotationPolicyTest {

    private fun member(id: String, owner: Boolean = false) = RotationMember(id, if (owner) "OWNER" else "MEMBER")
    private fun grant(id: String) = RotationGrant(id, SignedGrant("g-$id", "s-$id"), "d-$id")
    private fun inviteGrant(id: String) = InviteGrant(id, SignedGrant("g-$id", "s-$id"), "d-$id")

    private fun plan(
        currentEpoch: Int = 0,
        statedEpoch: Int = currentEpoch + 1,
        members: List<RotationMember> = listOf(member("a")),
        open: Set<String> = emptySet(),
        removes: String? = null,
        grants: List<RotationGrant> = members.filter { it.userId != removes }.map { grant(it.userId) },
        inviteGrants: List<InviteGrant> = emptyList(),
        pending: Set<String> = emptySet(),
        callerGrantedEpoch: Int? = currentEpoch,
        pendingUsers: Set<String> = emptySet(),
        callerRegrantEpoch: Int? = null,
    ) = RotationPolicy.plan(
        currentEpoch, statedEpoch, members, open, grants, inviteGrants, pending, pendingUsers, removes, callerGrantedEpoch, callerRegrantEpoch,
    )

    private fun committed(d: RotationDecision): RotationPlan = (d as RotationDecision.Commit).plan

    @Test
    fun commitsTheStatedEpochWhenItIsTheNextOne() {
        assertEquals(5, committed(plan(currentEpoch = 4, statedEpoch = 5)).newEpoch, "a rotation stating the scope's next epoch advances to it")
    }

    @Test
    fun refusesARotationBuiltOnASupersededEpoch() {
        // #177: another rotation committed epoch 5 first (or the caller's grant lags), so this one — built on 4 —
        // states 5 again. Its link wraps E_4, and committing it as 6 would orphan E_5 for every member.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 5, statedEpoch = 5, grants = listOf(grant("a"))))
    }

    @Test
    fun refusesARotationThatSkipsAnEpoch() {
        // The other direction: no epoch may be skipped either, or its link would name a predecessor that never existed.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 4, statedEpoch = 6, grants = listOf(grant("a"))))
    }

    @Test
    fun staleIsDecidedBeforeCompleteness() {
        // A rotation on a superseded epoch is stale whatever it carries: the client's answer is to re-open and rebuild.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 5, statedEpoch = 5, open = setOf("inv-a")))
    }

    @Test
    fun reGrantsEveryMemberTheClientListed() {
        // A `pendingConsent` proposal is a member here like any other: it holds a grant from its proposal on (ADR 0111
        // decision 4), so the policy no longer knows the flag at all.
        val grants = listOf(grant("owner"), grant("alice"))
        val p = committed(plan(members = listOf(member("owner"), member("alice")), grants = grants))
        assertEquals(grants, p.grants, "an ordinary re-grant delivers to every listed member")
    }

    @Test
    fun dropsAGrantAddressedToANonMember() {
        val p = committed(plan(members = listOf(member("owner")), grants = listOf(grant("owner"), grant("stranger"))))
        assertEquals(listOf(grant("owner")), p.grants, "a grant with no membership to wrap onto is dropped")
    }

    // --- no open tenant invite is left behind silently (ADR 0111 decision 2) ---

    @Test
    fun refusesARotationThatLeavesAnOpenInviteBehind() {
        // The bite: two invites are open and the rotation re-grants one. The other's newcomer would open a key the
        // members already left.
        val d = plan(open = setOf("inv-a", "inv-b"), inviteGrants = listOf(inviteGrant("inv-a")))
        assertSame(RotationDecision.Incomplete, d, "an open invite neither re-granted nor declared pending refuses the rotation")
    }

    @Test
    fun commitsWhenEveryOpenInviteIsReGranted() {
        // The control: the same rotation carrying both commits and writes both re-grants.
        val p = committed(plan(open = setOf("inv-a", "inv-b"), inviteGrants = listOf(inviteGrant("inv-a"), inviteGrant("inv-b"))))
        assertEquals(listOf(inviteGrant("inv-a"), inviteGrant("inv-b")), p.inviteGrants)
    }

    @Test
    fun aDeclaredPendingInviteCountsAsCarried() {
        // An invite the rotator could not verify is declared pending on purpose, not forgotten: the rotation commits and
        // writes nothing to it — it keeps the grant it holds.
        val p = committed(plan(open = setOf("inv-a", "inv-b"), inviteGrants = listOf(inviteGrant("inv-a")), pending = setOf("inv-b")))
        assertEquals(listOf(inviteGrant("inv-a")), p.inviteGrants)
    }

    @Test
    fun everyOpenInviteMayBeDeclaredPending() {
        assertEquals(emptyList<InviteGrant>(), committed(plan(open = setOf("inv-a", "inv-b"), pending = setOf("inv-a", "inv-b"))).inviteGrants)
    }

    @Test
    fun ignoresReGrantsOfInvitesTheScopeDoesNotHold() {
        // An id that names no open invite — revoked or accepted since the rotator read the list — has nothing to be
        // written onto: the rotation commits, and only the open invite's re-grant is applied.
        val p = committed(plan(open = setOf("inv-a"), inviteGrants = listOf(inviteGrant("inv-a"), inviteGrant("inv-ghost")), pending = setOf("inv-other")))
        assertEquals(listOf(inviteGrant("inv-a")), p.inviteGrants)
    }

    @Test
    fun anUnknownIdDoesNotStandInForAMissingOne() {
        // The same count, the wrong ids: a re-grant and a pending declaration of invites the scope does not hold do not
        // carry the one it does.
        val d = plan(open = setOf("inv-a", "inv-b"), inviteGrants = listOf(inviteGrant("inv-a"), inviteGrant("inv-ghost")), pending = setOf("inv-other"))
        assertSame(RotationDecision.Incomplete, d)
    }

    @Test
    fun aScopeWithNoOpenInviteNeedsNone() {
        // A group or personal scope holds none, and neither does a tenant nobody is invited to — the rotation commits.
        assertEquals(1, committed(plan(open = emptySet())).newEpoch)
    }

    // --- a removal-rotation: the removal and its rotation are one request (ADR 0024 Decision 4) ---

    private val group = listOf(member("owner", owner = true), member("bob"))

    @Test
    fun refusesARemovalWhoseGrantsNameTheRemovedMember() {
        // The bite: the request wraps the new epoch to the very member it removes. Whatever list the server served
        // the remover, the new key must never reach them — layer 1 refuses it, nothing stored.
        val d = plan(members = group, grants = listOf(grant("owner"), grant("bob")), removes = "bob")
        assertSame(RotationDecision.RemovedMemberGranted, d)
    }

    @Test
    fun aRemovalThatGrantsOnlyTheRemainingMembersCommitsAndNamesWhomItRemoves() {
        // The control on the same predicate: without bob's grant it commits, removing bob and granting the owner.
        val p = committed(plan(members = group, grants = listOf(grant("owner")), removes = "bob"))
        assertEquals("bob", p.removes)
        assertEquals(listOf(grant("owner")), p.grants)
    }

    @Test
    fun aRotationThatRemovesNoOneNamesNoOne() {
        assertEquals(null, committed(plan(members = group, grants = listOf(grant("owner"), grant("bob")))).removes)
    }

    @Test
    fun aRemovalStillCarriesEveryOpenInvite() {
        // Removing bob from a tenant does not excuse leaving an open invite behind.
        val d = plan(members = group, open = setOf("inv-c"), grants = listOf(grant("owner")), removes = "bob")
        assertSame(RotationDecision.Incomplete, d)
        // Control: carried, it commits.
        assertEquals("bob", committed(plan(members = group, open = setOf("inv-c"), grants = listOf(grant("owner")), pending = setOf("inv-c"), removes = "bob")).removes)
    }

    @Test
    fun refusesRemovingSomeoneWhoIsNotAMember() {
        assertSame(RotationDecision.MemberNotFound, plan(members = group, removes = "stranger"))
    }

    @Test
    fun refusesRemovingTheLastOwner() {
        // The last-owner guard (ADR 0007) is unchanged by the removal moving into the rotation.
        assertSame(RotationDecision.LastOwner, plan(members = group, grants = listOf(grant("bob")), removes = "owner"))
    }

    @Test
    fun removesAnOwnerWhileAnotherOwnerRemains() {
        // The control: with a second owner, removing one commits.
        val members = listOf(member("owner", owner = true), member("co", owner = true))
        assertEquals("owner", committed(plan(members = members, grants = listOf(grant("co")), removes = "owner")).removes)
    }

    @Test
    fun aStaleRemovalIsStaleBeforeAnythingElse() {
        // The client's answer to stale is to re-open and rebuild — whatever else the request got wrong.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 3, statedEpoch = 3, members = group, grants = listOf(grant("bob")), removes = "bob"))
    }

    // --- no member is left behind silently (ADR 0111 decision 9) ------------------------------------------

    @Test
    fun refusesARotationThatLeavesAMemberOutSilently() {
        // The bite: two members, one granted, the other neither granted nor declared — a member nobody will ever deliver to.
        val d = plan(members = listOf(member("owner"), member("bob")), grants = listOf(grant("owner")))
        assertSame(RotationDecision.Incomplete, d)
    }

    @Test
    fun aMemberDeclaredPendingCountsAsCarried() {
        // The control on the same predicate: declared, it commits and writes only the grant it has.
        val p = committed(plan(members = listOf(member("owner"), member("bob")), grants = listOf(grant("owner")), pendingUsers = setOf("bob")))
        assertEquals(listOf(grant("owner")), p.grants)
    }

    @Test
    fun aPendingIdThatNamesNoMemberDoesNotStandInForOne() {
        val d = plan(members = listOf(member("owner"), member("bob")), grants = listOf(grant("owner")), pendingUsers = setOf("ghost"))
        assertSame(RotationDecision.Incomplete, d)
    }

    @Test
    fun theRemovedMemberNeedsNeitherAGrantNorADeclaration() {
        assertEquals("bob", committed(plan(members = group, grants = listOf(grant("owner")), removes = "bob")).removes)
    }

    @Test
    fun staleIsDecidedBeforeMemberCompleteness() {
        assertSame(RotationDecision.Stale, plan(currentEpoch = 5, statedEpoch = 5, members = listOf(member("a"), member("b")), grants = listOf(grant("a"))))
    }

    // --- the caller: behind, or let in by a pending re-key row (#206, ADR 0111 decision 8) -------------------

    @Test
    fun aCallerLetInByAWaitingRowCommitsTheNextEpoch() {
        assertEquals(3, committed(plan(currentEpoch = 2, callerRegrantEpoch = 2)).newEpoch)
    }

    @Test
    fun aRowSomeoneElsesCommitMovedPastIsStale() {
        // The row named epoch 2; the scope is at 3 now, so the row's purpose is met — whatever epoch the caller states.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 3, statedEpoch = 4, callerRegrantEpoch = 2))
    }

    @Test
    fun refusesACallerWhoseGrantIsBehindTheScope() {
        // The epoch stated is the next one, but the caller holds an older key: nothing to chain the new epoch from.
        assertSame(RotationDecision.Stale, plan(currentEpoch = 2, statedEpoch = 3, callerGrantedEpoch = 1))
    }

    @Test
    fun refusesACallerWhoHoldsNoGrant() {
        assertSame(RotationDecision.Stale, plan(currentEpoch = 0, statedEpoch = 1, callerGrantedEpoch = null))
    }

    @Test
    fun aCurrentGrantCommits() {
        // The control at the boundary: a grant at exactly the current epoch is not behind.
        assertEquals(3, committed(plan(currentEpoch = 2, statedEpoch = 3, callerGrantedEpoch = 2)).newEpoch)
    }
}
