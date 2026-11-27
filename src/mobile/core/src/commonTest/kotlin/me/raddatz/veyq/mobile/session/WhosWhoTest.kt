package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.session.WhosWho.Companion.Check
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Who's who*'s rules (inbox #967; the web's `peopleOverview.test.ts`, case for case where it is the assembly's): who is
 * listed, the one state each shows — an anomaly never verified —, new, rotated, replaced, and the order.
 */
class WhosWhoTest {
    private val self = "c0ffee00-0000-4000-8000-00000000000a"
    private val ann = "c0ffee00-0000-4000-8000-00000000000b"
    private val bob = "c0ffee00-0000-4000-8000-00000000000c"
    private val cid = "c0ffee00-0000-4000-8000-00000000000d"
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun member(userId: String, username: String, head: String = "h-$username", pendingConsent: Boolean? = null) =
        ScopeMember(userId, username, 0, ScopeMember.Role.MEMBER, "k-$username", head, false, pendingConsent = pendingConsent)

    private fun pin(firstSeenDaysAgo: Long, replaced: Boolean = false) = Trust.Pin("f", "h", 1, now - firstSeenDaysAgo * day, replaced)

    private val members = listOf(member(self, "me"), member(ann, "ann"), member(bob, "bob"))
    private val names = mapOf(self to "Me Self", ann to "Ann Arbor", bob to "Bob Brown")

    private fun assemble(
        members: List<ScopeMember> = this.members,
        checks: Map<String, Check> = mapOf(ann to Check.ANCHORED, bob to Check.ANCHORED),
        pins: Map<String, Trust.Pin> = mapOf(ann to pin(30), bob to pin(30)),
        counted: Map<String, List<String>> = emptyMap(),
        rotations: Map<String, Long?> = emptyMap(),
        names: Map<String, String> = this.names,
    ) = WhosWho.assemble(self, members, checks, pins, counted, rotations, names, now)

    private fun WhosWho.row(userId: String) = people.single { it.userId == userId }

    @Test
    fun everyoneButThisAccountAndAPendingProposalIsListed() {
        val w = assemble(members = members + member(cid, "cid", pendingConsent = true))
        assertEquals(setOf(ann, bob), w.people.map { it.userId }.toSet())
        assertNull(w.said)
    }

    @Test
    fun nobodyElseIsSaid() {
        val w = assemble(members = listOf(member(self, "me")))
        assertEquals(Msg.WHOS_WHO_EMPTY, w.said)
        assertTrue(w.people.isEmpty())
    }

    /** Load-bearing: an anomaly takes no count, whatever rows count for it (the count fed every member, as the web's bite). */
    @Test
    fun anAnomalyIsNeverVerifiedNotEvenWithACountingVerificationOfItsServedKey() {
        val w = assemble(
            checks = mapOf(ann to Check.ANOMALY, bob to Check.ANCHORED),
            pins = mapOf(ann to pin(1, replaced = true), bob to pin(30)),
            counted = mapOf(ann to listOf(self, bob), bob to listOf(ann)),
            rotations = mapOf(ann to now - day),
        )
        val a = w.row(ann)
        assertEquals(WhosWho.State.CANT_CONFIRM, a.state)
        assertTrue(a.verifiedBy.isEmpty())
        assertFalse(a.verifiedByMe)
        assertFalse(a.isNew, "never new for an anomaly")
        assertNull(a.rotatedAt, "never rotated for an anomaly")
        assertFalse(a.replaced)
        assertEquals(Msg.TRUST_ANOMALY, a.stateWord)
        assertEquals(Msg.TRUST_ANOMALY_NOTE, a.note)
        assertTrue(a.compare)
        // control: the anchored member's count stands
        assertEquals(WhosWho.State.VERIFIED, w.row(bob).state)
        assertEquals(listOf("Ann Arbor"), w.row(bob).verifiedBy)
    }

    @Test
    fun aCheckThatFailedForARequestShowsNoStateNeverAFalseAnomaly() {
        val w = assemble(checks = mapOf(ann to Check.UNCHECKED, bob to Check.ANCHORED), counted = mapOf(ann to listOf(bob)))
        val a = w.row(ann)
        assertEquals(WhosWho.State.UNCHECKED, a.state)
        assertNull(a.stateWord)
        assertNull(a.note)
        assertTrue(a.verifiedBy.isEmpty())
    }

    @Test
    fun thisAccountsOwnVerificationIsYouAndTakesTheCompareAway() {
        val w = assemble(counted = mapOf(ann to listOf(bob, self)))
        val a = w.row(ann)
        assertEquals(WhosWho.State.VERIFIED, a.state)
        assertTrue(a.verifiedByMe)
        assertEquals(listOf("Bob Brown"), a.verifiedBy, "the others by name, this account as `verifiedByMe`")
        assertFalse(a.compare)
        // control: not verified, the compare offered
        assertEquals(WhosWho.State.NOT_VERIFIED, w.row(bob).state)
        assertTrue(w.row(bob).compare)
        assertEquals(Msg.SAFETY_STATE_UNVERIFIED, w.row(bob).stateWord)
    }

    @Test
    fun newWhileFirstSeenUnderTwoWeeksAgo() {
        val w = assemble(pins = mapOf(ann to pin(13), bob to pin(14)))
        assertTrue(w.row(ann).isNew)
        assertFalse(w.row(bob).isNew, "two weeks and more is not new")
    }

    @Test
    fun aRotationShowsWhileUnderTwoWeeksOld() {
        val w = assemble(rotations = mapOf(ann to now - 13 * day, bob to now - 14 * day))
        assertEquals(now - 13 * day, w.row(ann).rotatedAt)
        assertNull(w.row(bob).rotatedAt)
    }

    @Test
    fun aReplacedPinIsSaid() {
        val w = assemble(pins = mapOf(ann to pin(30, replaced = true), bob to pin(30)))
        assertTrue(w.row(ann).replaced)
        assertFalse(w.row(bob).replaced)
    }

    @Test
    fun anomaliesFirstThenByNameAsAPersonReadsIt() {
        val w = assemble(
            members = members + member(cid, "cid"),
            checks = mapOf(ann to Check.ANCHORED, bob to Check.ANOMALY, cid to Check.ANCHORED),
            names = names + (cid to "ämy Zed"),
        )
        assertEquals(listOf(bob, cid, ann), w.people.map { it.userId }, "the anomaly, then ämy before Ann (case and accents aside)")
        assertEquals("@ann", w.row(ann).subtitle)
    }

    @Test
    fun aMemberWithoutARealNameIsNamedByTheUsername() {
        val w = assemble(names = emptyMap())
        assertEquals("ann", w.row(ann).name)
    }

    @Test
    fun aRefusedPinStoreIsSaidNeverReadAsNobody() {
        assertEquals(Msg.VERIFY_TRUST_ROLLED_BACK, WhosWho.refused(TrustStateRefused.Code.ROLLED_BACK).said)
        assertEquals(Msg.VERIFY_TRUST_UNREADABLE, WhosWho.refused(TrustStateRefused.Code.UNREADABLE).said)
        assertTrue(WhosWho.FAILED.retry)
        assertFalse(WhosWho.UNAVAILABLE.retry)
    }
}
