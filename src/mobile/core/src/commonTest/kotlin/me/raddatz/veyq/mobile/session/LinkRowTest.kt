package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A link's row in the share sheet both apps drew (the web's `FolderShareDialog`, `DropBoxShareDialog`). */
class LinkRowTest {
    private val words = ShareWords.of("FOLDER")

    private fun link(
        enabled: Boolean = true, mine: Boolean = true, url: String? = "https://x/s/1#k=a", label: String = "",
        passwordRequired: Boolean = false, reissueRequired: Boolean = false, stopped: Boolean = false, deletable: Boolean = false,
        role: String = ShareLinks.READ,
    ) = ShareLinks.Link("s", role, enabled, mine, "2026-11-10", null, label, false, passwordRequired, url, reissueRequired, stopped, deletable)

    @Test
    fun an_own_open_link_is_copied_edited_renewed_and_switched_off() {
        val r = LinkRow(link(), dropBox = false, words)
        assertTrue(r.canCopy && r.canEdit && r.canRenew && r.canSwitchOff)
        assertFalse(r.canReactivate || r.canDelete)
        // one whose address this device cannot rebuild is neither copied nor edited
        val lost = LinkRow(link(url = null), dropBox = false, words)
        assertFalse(lost.canCopy || lost.canEdit)
        // another member's open link: none of it but the copy
        val theirs = LinkRow(link(mine = false), dropBox = false, words)
        assertTrue(theirs.canCopy)
        assertFalse(theirs.canEdit || theirs.canRenew || theirs.canSwitchOff)
    }

    @Test
    fun a_switched_off_link_is_renewed_switched_on_unless_closed_for_good_and_deleted_where_the_server_lets_it_go() {
        val off = LinkRow(link(enabled = false, mine = false, deletable = true), dropBox = false, words)
        assertTrue(off.canRenew && off.canReactivate && off.canDelete)
        assertFalse(off.canCopy || off.canSwitchOff || off.canEdit)
        assertFalse(LinkRow(link(enabled = false, reissueRequired = true), dropBox = false, words).canReactivate)
        assertFalse(LinkRow(link(enabled = false), dropBox = false, words).canDelete)
        // a drop box's links are never edited or renewed
        val box = LinkRow(link(), dropBox = true, words)
        assertFalse(box.canEdit || box.canRenew)
        assertTrue(box.canSwitchOff)
    }

    @Test
    fun the_title_is_the_label_else_the_role_and_the_badges_say_the_rest() {
        assertEquals(Piece.Said(Notice(Msg.SHARE_ROLE_VIEWER)), LinkRow(link(), false, words).title)
        val r = LinkRow(link(label = "Grandma", passwordRequired = true, enabled = false, mine = false, stopped = true), false, words)
        assertEquals(Piece.Plain("Grandma"), r.title)
        assertEquals(listOf(Msg.SHARE_ROLE_VIEWER, Msg.SHARE_PASSWORD_BADGE, Msg.SHARE_SWITCHED_OFF, Msg.SHARE_BY_MEMBER, Msg.SHARE_STOPPED), r.badges)
        assertEquals(emptyList(), LinkRow(link(), false, words).badges)
    }

    @Test
    fun add_files_only_on_a_folder_makes_a_drop_box_and_a_drop_boxs_link_is_upload_only_and_unnamed() {
        val folder = LinkRow.mint(ShareLinks.WRITE_ONLY, "Photos", onDropBox = false)
        assertTrue(folder.makesDropBox)
        assertEquals("Photos", folder.label)
        val box = LinkRow.mint(ShareLinks.READ, "Ignored", onDropBox = true)
        assertFalse(box.makesDropBox)
        assertEquals(ShareLinks.WRITE_ONLY to "", box.role to box.label)
        val plain = LinkRow.mint(ShareLinks.READ_WRITE, "Family", onDropBox = false)
        assertEquals(Triple(false, ShareLinks.READ_WRITE, "Family"), Triple(plain.makesDropBox, plain.role, plain.label))
    }

    @Test
    fun switching_off_says_nothing_once_closed() {
        assertNull(Notices.linkClosed(ShareLinks.Closed.CLOSED))
        assertEquals(Notice(Msg.SHARE_CLOSED_REKEYING), Notices.linkClosed(ShareLinks.Closed.CLOSED_REKEY_PENDING))
        assertEquals(Notice(Msg.SHARE_FAILED), Notices.linkClosed(ShareLinks.Closed.FAILED))
    }
}
