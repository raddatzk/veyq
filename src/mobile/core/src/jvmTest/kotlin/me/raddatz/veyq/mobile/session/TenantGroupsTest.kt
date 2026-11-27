package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A group's page offers the web's `GroupMembersPanel` acts and no other (uc.spaces.manage-group-members): an owner's row
 * is demoted, a member's promoted or removed (by the rotation), a pending proposal withdrawn; a member's page offers
 * none. Bitten by offering *Remove* on every row of an owner's page (red).
 */
class TenantGroupsTest {
    private fun m(id: String, owner: Boolean = false, consent: Boolean = false) = ScopeMember(
        id, "u-$id", 0, if (owner) ScopeMember.Role.OWNER else ScopeMember.Role.MEMBER, "pk", "head", false, pendingConsent = consent,
    )

    private val members = listOf(m("me", owner = true), m("co", owner = true), m("sam"), m("ask", consent = true))

    @Test
    fun anOwnersPageOffersTheWebsActs() {
        val rows = TenantGroups.assemble(members, owner = true, me = "me") { null }.associateBy { it.userId }
        fun acts(id: String) = rows.getValue(id).let { listOf(it.promote, it.demote, it.remove, it.rescind) }
        assertEquals(listOf(false, true, false, false), acts("me"), "the caller is never removed by the rotation")
        assertEquals(listOf(false, true, false, false), acts("co"), "an owner is demoted, not removed")
        assertEquals(listOf(true, false, true, false), acts("sam"))
        assertEquals(listOf(false, false, false, true), acts("ask"), "a proposal is withdrawn, never rotated out")
        assertEquals(Msg.MEMBERS_STATUS_PENDING_CONSENT, rows.getValue("ask").statusWord)
        assertEquals("@u-sam", rows.getValue("sam").title)
    }

    @Test
    fun aMembersPageOffersNone() {
        val rows = TenantGroups.assemble(members, owner = false, me = "sam") { if (it.userId == "co") "Co Owner" else null }
        assertTrue(rows.none { it.promote || it.demote || it.remove || it.rescind })
        assertEquals("Co Owner", rows.single { it.userId == "co" }.title)
    }
}
