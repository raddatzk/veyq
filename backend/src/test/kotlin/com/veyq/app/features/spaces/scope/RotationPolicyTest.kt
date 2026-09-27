package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.SignedGrant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The pure rotation decision (ADR 0045 §2), boot-free and mutation-verified. Every assertion pins an exact value —
 * a bare "not empty" would let a mutant that grants the *wrong* set pass (the assertion-strength trap ADR 0045 exists
 * to close). The security case — `never grants a pendingConsent proposal` — mirrors the slice
 * `ConsentGroupAddSliceTest.rotationNeverGrantsAPendingProposal`, but here without a Quarkus boot per mutant; the
 * completeness cases mirror `ScopesSliceTest.rotationThatLeavesAnOpenAdmissionBehindIsRefused`.
 */
class RotationPolicyTest {

    private fun member(id: String, pending: Boolean = false, owner: Boolean = false, admission: String? = null) =
        RotationMember(id, pending, if (owner) "OWNER" else "MEMBER", admission)
    private fun grant(id: String) = RotationGrant(id, SignedGrant("g-$id", "s-$id"))

    private fun plan(
        currentEpoch: Int = 0,
        statedEpoch: Int = currentEpoch + 1,
        members: List<RotationMember> = listOf(member("a")),
        open: Set<String> = emptySet(),
        grants: List<RotationGrant> = emptyList(),
        retags: List<Pair<String, String>> = emptyList(),
        dropped: Set<String> = emptySet(),
        removes: String? = null,
    ) = RotationPolicy.plan(currentEpoch, statedEpoch, members, open, grants, retags, dropped, removes)

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
        assertSame(RotationDecision.Stale, plan(currentEpoch = 5, statedEpoch = 5, open = setOf("adm-a")))
    }

    @Test
    fun reGrantsEveryNonPendingMemberTheClientListed() {
        val grants = listOf(grant("owner"), grant("alice"))
        val p = committed(plan(members = listOf(member("owner"), member("alice")), grants = grants))
        assertEquals(grants, p.grants, "an ordinary re-grant delivers to every listed key-holder")
    }

    @Test
    fun neverGrantsAPendingConsentProposalEvenWhenTheClientListsIt() {
        val p = committed(
            plan(currentEpoch = 2, members = listOf(member("owner"), member("partner", pending = true)), grants = listOf(grant("owner"), grant("partner"))),
        )
        assertEquals(listOf(grant("owner")), p.grants, "K_SC must never be wrapped onto a pendingConsent proposal (cr-0094)")
    }

    @Test
    fun dropsAGrantAddressedToANonMember() {
        val p = committed(plan(members = listOf(member("owner")), grants = listOf(grant("owner"), grant("stranger"))))
        assertEquals(listOf(grant("owner")), p.grants, "a grant with no membership to wrap onto is dropped")
    }

    // --- no open admission is left behind (ADR 0020 move 6) ---

    @Test
    fun refusesARotationThatLeavesAnOpenAdmissionUnTagged() {
        // The bite: a pending member's admission is open, and the rotation re-tags only the owner's. A worker admits
        // only a tag at its own current epoch, so the pending member could never be delivered to again.
        val d = plan(open = setOf("adm-owner", "adm-pending"), retags = listOf("adm-owner" to "t1"))
        assertSame(RotationDecision.AdmissionsIncomplete, d, "an open admission neither re-tagged nor dropped refuses the rotation")
    }

    @Test
    fun commitsWhenEveryOpenAdmissionIsReTagged() {
        // The control: the same rotation carrying both admissions commits and writes both new tags.
        val p = committed(plan(open = setOf("adm-owner", "adm-pending"), retags = listOf("adm-owner" to "t1", "adm-pending" to "t2")))
        assertEquals(mapOf("adm-owner" to "t1", "adm-pending" to "t2"), p.retags)
        assertEquals(emptySet<String>(), p.dropped)
    }

    @Test
    fun anAdmissionTheRotatorDropsCountsAsHandled() {
        // A tag that failed the rotator's check is dropped on purpose, not forgotten: the rotation commits and the
        // admission is cleared, so no client delivers to it again.
        val p = committed(plan(open = setOf("adm-owner", "adm-forged"), retags = listOf("adm-owner" to "t1"), dropped = setOf("adm-forged")))
        assertEquals(mapOf("adm-owner" to "t1"), p.retags)
        assertEquals(setOf("adm-forged"), p.dropped)
    }

    @Test
    fun aDroppedAdmissionIsNeverAlsoReTagged() {
        // Named in both lists: the conservative reading wins — dropped, so nothing ever delivers to it.
        val p = committed(plan(open = setOf("adm-x"), retags = listOf("adm-x" to "t"), dropped = setOf("adm-x")))
        assertEquals(emptyMap<String, String>(), p.retags)
        assertEquals(setOf("adm-x"), p.dropped)
    }

    @Test
    fun ignoresReTagsAndDropsOfAdmissionsTheScopeDoesNotHold() {
        // Nothing to write them onto: a tag for an unknown admission id must not be stored anywhere.
        val p = committed(plan(open = setOf("adm-a"), retags = listOf("adm-a" to "t", "adm-ghost" to "g"), dropped = setOf("adm-other")))
        assertEquals(mapOf("adm-a" to "t"), p.retags)
        assertEquals(emptySet<String>(), p.dropped)
    }

    @Test
    fun aScopeWithNoOpenAdmissionNeedsNone() {
        // A pre-admission membership carries none, so there is nothing to carry — the rotation commits.
        assertEquals(1, committed(plan(open = emptySet())).newEpoch)
    }

    // --- a removal-rotation: the removal and its rotation are one request (ADR 0024 Decision 4) ---

    private val group = listOf(member("owner", owner = true, admission = "adm-owner"), member("bob", admission = "adm-bob"))

    @Test
    fun refusesARemovalWhoseGrantsNameTheRemovedMember() {
        // The bite: the request wraps the new epoch to the very member it removes. Whatever list the server served
        // the remover, the new key must never reach them — layer 1 refuses it, nothing stored.
        val d = plan(members = group, open = setOf("adm-owner", "adm-bob"), grants = listOf(grant("owner"), grant("bob")), retags = listOf("adm-owner" to "t"), removes = "bob")
        assertSame(RotationDecision.RemovedMemberGranted, d)
    }

    @Test
    fun aRemovalThatGrantsOnlyTheRemainingMembersCommitsAndNamesWhomItRemoves() {
        // The control on the same predicate: without bob's grant it commits, removing bob and granting the owner.
        val p = committed(plan(members = group, open = setOf("adm-owner", "adm-bob"), grants = listOf(grant("owner")), retags = listOf("adm-owner" to "t"), removes = "bob"))
        assertEquals("bob", p.removes)
        assertEquals(listOf(grant("owner")), p.grants)
        assertEquals(mapOf("adm-owner" to "t"), p.retags, "the removed member's admission goes with the membership — neither required nor written")
    }

    @Test
    fun aRotationThatRemovesNoOneNamesNoOne() {
        assertEquals(null, committed(plan(members = group, open = setOf("adm-owner", "adm-bob"), retags = listOf("adm-owner" to "t", "adm-bob" to "u"))).removes)
    }

    @Test
    fun theRemovedMembersAdmissionIsNeverReTaggedEvenWhenCarried() {
        val p = committed(plan(members = group, open = setOf("adm-owner", "adm-bob"), retags = listOf("adm-owner" to "t", "adm-bob" to "u"), removes = "bob"))
        assertEquals(mapOf("adm-owner" to "t"), p.retags)
    }

    @Test
    fun aRemovalStillCarriesEveryOtherOpenAdmission() {
        // Removing bob does not excuse leaving carol's admission behind.
        val d = plan(members = group + member("carol", admission = "adm-carol"), open = setOf("adm-owner", "adm-bob", "adm-carol"), retags = listOf("adm-owner" to "t"), removes = "bob")
        assertSame(RotationDecision.AdmissionsIncomplete, d)
    }

    @Test
    fun refusesRemovingSomeoneWhoIsNotAMember() {
        assertSame(RotationDecision.MemberNotFound, plan(members = group, open = setOf("adm-owner", "adm-bob"), retags = listOf("adm-owner" to "t", "adm-bob" to "u"), removes = "stranger"))
    }

    @Test
    fun refusesRemovingTheLastOwner() {
        // The last-owner guard (ADR 0007) is unchanged by the removal moving into the rotation.
        assertSame(RotationDecision.LastOwner, plan(members = group, open = setOf("adm-owner", "adm-bob"), retags = listOf("adm-bob" to "u"), removes = "owner"))
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
}
