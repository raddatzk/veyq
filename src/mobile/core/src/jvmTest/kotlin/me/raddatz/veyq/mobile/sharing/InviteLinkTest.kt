package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.session.TenantInvites
import me.raddatz.veyq.mobile.session.TenantJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An invite link reaches its join through *Open a link* and a shared text (ADR 0150 § S; ADR 0111, ADR 0129), and the
 * invite's small rules ported from the web: the label's form (`normalizeInviteLabel`) and the address check (`isMailbox`).
 * **Load-bearing:** a pasted invite is offered as an invite, never as a guest link, and its text leaves the field once
 * handed on (it holds the code); a link on another path is the share check's.
 */
class InviteLinkTest {
    private val link = "https://family.example.org/tenant/accept#tok.sealed.R0SE1N7AKE"

    @Test
    fun anInviteIsReadByItsPathAndItsFragment() {
        val i = assertNotNull(ReceivedLink.inviteOf("  $link "))
        assertEquals("https://family.example.org", i.origin)
        assertEquals("tok.sealed.R0SE1N7AKE", i.fragment)
        assertEquals("InviteLink(https://family.example.org)", i.toString(), "the fragment is never printed")
        assertNotNull(ReceivedLink.inviteOf("http://tw-1.localhost:8081/tenant/accept#tok"), "a development host over http")
        for (no in listOf(
            "http://family.example.org/tenant/accept#tok", "https://family.example.org/tenant/accept", "https://family.example.org/tenant/accept#",
            "https://u:p@family.example.org/tenant/accept#tok", "https://family.example.org/tenant/accepted#tok", "https://family.example.org/s/abc#k=x", "not a link",
        )) assertNull(ReceivedLink.inviteOf(no), no)
    }

    @Test
    fun openALinkHandsAnInviteToItsJoinAndForgetsTheText() {
        val o = OpenLink { _, _ -> error("an invite opens no guest view") }
        o.text(link)
        assertTrue(o.state.value.canOpen && o.state.value.invite)
        assertNull(o.open(), "no guest view for an invite")
        assertEquals("https://family.example.org", assertNotNull(o.invite()).origin)
        assertEquals("", o.state.value.text, "the field holds the code: emptied once handed on")
        assertNull(o.invite(), "handed on once")
        o.text("https://family.example.org/tenant/other#tok")
        assertFalse(o.state.value.invite)
        o.text("You are invited to “Family”. Open this link to join — it works once, until Nov 30:\n$link")
        assertTrue(o.state.value.invite, "the invite dialog's whole message pasted")
        o.received("Join us: $link.")
        assertTrue(o.state.value.invite, "a shared text with one invite in it")
    }

    @Test
    fun aLabelIsTrimmedAndCutAt80CodePoints() {
        assertNull(TenantInvites.normalizeLabel("   "))
        assertNull(TenantInvites.normalizeLabel(null))
        assertEquals("Oma", TenantInvites.normalizeLabel("  Oma "))
        assertEquals(80, TenantInvites.normalizeLabel("x".repeat(100))!!.length)
        val emoji = TenantInvites.normalizeLabel("😀".repeat(81))!!
        assertEquals(160, emoji.length, "80 code points, each a surrogate pair")
    }

    @Test
    fun anAddressIsTheWebsMailbox() {
        for (yes in listOf("a@b.c", "sam.lee+x@veyq.localhost", "\"q\"@x", "a@[1.2.3.4]")) assertTrue(TenantJoin.isMailbox(yes), yes)
        for (no in listOf("", "a", "@b.c", "a@", "a b@c.d", "a@b..c", "a@-b.c", "ä@b.c")) assertFalse(TenantJoin.isMailbox(no), no)
    }
}
