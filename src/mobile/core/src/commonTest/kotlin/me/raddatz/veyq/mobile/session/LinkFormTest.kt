package me.raddatz.veyq.mobile.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The new-link form's rules both apps wrote (the web's `FolderShareDialog`). */
class LinkFormTest {
    private val free = ShareLinks.Rules(on = true, contribute = true, passwordRequired = false, maxDays = null, dropBox = true)
    private val strict = ShareLinks.Rules(on = true, contribute = false, passwordRequired = true, maxDays = 7, dropBox = true)
    private val today = 20_000L

    private fun link(passwordRequired: Boolean, role: String = ShareLinks.READ_WRITE) =
        ShareLinks.Link(
            "s", role, enabled = true, mine = true, createdAt = "x", expiresAt = null, label = "Old", hasNote = false,
            passwordRequired = passwordRequired, url = null, reissueRequired = false, stopped = false, deletable = true,
        )

    private fun new(rules: ShareLinks.Rules = free, writeOnly: Boolean = false, uploadOffered: Boolean = false, uploadOnly: Boolean = false) =
        LinkForm.start(rules, null, writeOnly, uploadOffered, uploadOnly, today, null)

    @Test
    fun a_new_link_opens_view_only_with_the_switches_a_rule_holds() {
        val f = new()
        assertEquals(ShareLinks.READ, f.role)
        assertFalse(f.withPassword)
        assertFalse(f.withEnd)
        assertEquals(today + LinkForm.DEFAULT_DAYS, f.endDay)
        assertEquals(listOf(ShareLinks.READ, ShareLinks.READ_WRITE), f.roles)

        val s = new(strict)
        assertTrue(s.withPassword)
        assertTrue(s.withEnd)
        assertEquals(today + 6, s.lastDay)
        assertEquals(today + 6, s.endDay)
        assertEquals(listOf(ShareLinks.READ), s.roles) // no contributing where the tenant forbids it
        assertTrue(s.withPasswordOn(false).withPassword, "a required password stays on")
        assertTrue(s.withEndOn(false).withEnd, "a required end stays on")
        assertEquals(today + 6, s.withEndDay(today + 40).endDay, "no later than the rule's last day")
        assertEquals(today, s.withEndDay(today - 3).endDay, "no earlier than today")
    }

    @Test
    fun a_fixed_role_offers_none_and_a_drop_box_needs_a_name() {
        assertTrue(new(writeOnly = true).roles.isEmpty())
        assertEquals(ShareLinks.WRITE_ONLY, new(uploadOnly = true).role)
        val folder = new(uploadOffered = true)
        assertEquals(listOf(ShareLinks.READ, ShareLinks.READ_WRITE, ShareLinks.WRITE_ONLY), folder.roles)
        val upload = folder.withRole(ShareLinks.WRITE_ONLY)
        assertFalse(upload.canSave, "Add files only makes a drop box: it needs its name")
        assertTrue(upload.withLabel("Holiday photos").canSave)
        assertTrue(new(writeOnly = true).canSave, "a drop box's own link has no name")
        assertEquals(ShareLinks.READ, new().withRole(ShareLinks.WRITE_ONLY).role, "a role not offered is not taken")
    }

    @Test
    fun the_password_is_long_enough_kept_or_removed() {
        val on = new().withPasswordOn(true)
        assertFalse(on.canSave)
        assertTrue(on.withPassword("short").passwordTooShort)
        assertFalse(on.withPassword("  short   ").canSave, "spaces do not count")
        assertTrue(on.withPassword("long enough!").canSave)
        assertEquals("long enough!", on.withPassword("long enough!").submittedPassword)
        assertEquals("", new().submittedPassword, "a new link without one sends none")

        val edit = LinkForm.start(free, link(passwordRequired = true), false, false, false, today, null)
        assertTrue(edit.withPassword)
        assertTrue(edit.canSave, "a blank field keeps the old password")
        assertNull(edit.submittedPassword)
        assertEquals("", edit.withPasswordOn(false).submittedPassword, "switched off: removed")
        val plain = LinkForm.start(free, link(passwordRequired = false), false, false, false, today, null)
        assertNull(plain.submittedPassword, "an edit leaves a link without one as it is")
        assertEquals("Old", plain.label)
        assertEquals(ShareLinks.READ_WRITE, plain.role)
    }

    @Test
    fun an_edited_end_opens_on_its_day_and_an_end_is_sent_only_when_on() {
        val edit = LinkForm.start(free, link(passwordRequired = false), false, false, false, today, today + 3)
        assertTrue(edit.withEnd)
        assertEquals(today + 3, edit.submittedEndDay)
        assertNull(edit.withEndOn(false).submittedEndDay)
    }

    @Test
    fun the_words_follow_the_kind() {
        assertEquals(ShareWords.of(PotSessionKind.POT).contributorHint, ShareWords.of(PotSessionKind.POT).hint(ShareLinks.READ_WRITE))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.DROPBOX_UPLOAD_HINT, ShareWords.of("FOLDER").hint(ShareLinks.WRITE_ONLY))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.SHARE_ROLE_CONTRIBUTOR, ShareWords.of(PotSessionKind.POT).contributor)
    }

    private object PotSessionKind {
        val POT = me.raddatz.veyq.mobile.expenses.PotSession.KIND
    }
}
