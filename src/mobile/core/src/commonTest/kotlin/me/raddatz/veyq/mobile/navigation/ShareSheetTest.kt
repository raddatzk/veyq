package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A node's share sheet both apps drew (ADR 0127 decision 2, the header CR's D1 and R4): mint, edit, renew, switch off and
 * on, delete — each over a server the test plays (its links, as `ShareLinks` and `ShareLinkChanges` read and change them).
 */
class ShareSheetTest {
    private val pot = NodeAt("s", "pot", "EXPENSES_POT", "Trip")
    private val folder = NodeAt("s", "f", NodeRef.FOLDER, "Photos")
    private val now = 1_700_000_000_000L

    private class Server(override val rules: ShareLinks.Rules = ShareLinks.Rules.UNKNOWN) : ShareSheet.Door {
        val links = mutableListOf<ShareLinks.Link>()
        val sent = mutableListOf<String>()
        var minted: Triple<String, String, String?>? = null
        var passwordSent: String? = null
        var edited: ShareLinkChanges.Edit? = null
        var mintAnswer: ShareLinks.Outcome = ShareLinks.Outcome.CREATED
        var rekeys = 0
        var changes = 0
        private var next = 0

        fun link(id: String, enabled: Boolean = true, mine: Boolean = true, label: String = "", deletable: Boolean = !enabled, at: String = "2026-11-0${links.size + 1}T10:00:00Z") =
            ShareLinks.Link(id, ShareLinks.READ, enabled, mine, at, null, label, false, false, "https://t/s/$id#k=key$id", false, false, deletable)
                .also { links += it }

        override suspend fun list() = links.sortedByDescending { it.createdAt }
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?): ShareLinks.Minted {
            sent += "mint"
            minted = Triple(role, label, expiresAt)
            passwordSent = password
            if (mintAnswer != ShareLinks.Outcome.CREATED) return ShareLinks.Minted(mintAnswer)
            val id = "new${++next}"
            links += ShareLinks.Link(id, role, true, true, "2026-12-0$next", expiresAt, label, false, password.isNotEmpty(), "https://t/s/$id#k=fresh", false, false, false)
            return ShareLinks.Minted(ShareLinks.Outcome.CREATED, "https://t/s/$id#k=fresh")
        }
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?): DropBoxes.Created {
            sent += "dropbox:$name"
            return DropBoxes.Created(DropBoxes.Outcome.CREATED, "box", "https://t/s/box#k=drop")
        }
        override suspend fun edit(shareId: String, edit: ShareLinkChanges.Edit): ShareLinkChanges.Changed {
            sent += "edit:$shareId"
            edited = edit
            val i = links.indexOfFirst { it.shareId == shareId }
            val l = links[i]
            links[i] = ShareLinks.Link(l.shareId, edit.role, l.enabled, l.mine, l.createdAt, edit.expiresAt, edit.label, l.hasNote,
                if (edit.password == null) l.passwordRequired else edit.password.isNotEmpty(), l.url, false, false, l.deletable)
            return ShareLinkChanges.Changed(ShareLinkChanges.Outcome.DONE)
        }
        override suspend fun renew(shareId: String): ShareLinkChanges.Changed {
            sent += "renew:$shareId"
            val old = links.first { it.shareId == shareId }
            links.remove(old)
            val id = "renewed${++next}"
            links += ShareLinks.Link(id, old.role, true, true, "2026-12-1$next", old.expiresAt, old.label, false, old.passwordRequired, "https://t/s/$id#k=renewed", false, false, false)
            return ShareLinkChanges.Changed(ShareLinkChanges.Outcome.DONE, "https://t/s/$id#k=renewed")
        }
        override suspend fun switchOff(shareId: String): ShareLinks.Closed {
            sent += "off:$shareId"
            turn(shareId, false)
            return ShareLinks.Closed.CLOSED
        }
        override suspend fun reactivate(shareId: String): ShareLinkChanges.Changed {
            sent += "on:$shareId"
            turn(shareId, true)
            return ShareLinkChanges.Changed(ShareLinkChanges.Outcome.DONE)
        }
        override suspend fun delete(shareId: String): ShareLinkChanges.Changed {
            sent += "delete:$shareId"
            links.removeAll { it.shareId == shareId }
            return ShareLinkChanges.Changed(ShareLinkChanges.Outcome.DONE)
        }
        override fun rekeyed() { rekeys++ }
        override fun changed() { changes++ }

        private fun turn(id: String, on: Boolean) {
            val i = links.indexOfFirst { it.shareId == id }
            val l = links[i]
            links[i] = ShareLinks.Link(l.shareId, l.role, on, l.mine, l.createdAt, l.expiresAt, l.label, l.hasNote, l.passwordRequired, l.url, false, false, !on)
        }
    }

    private suspend fun sheet(node: NodeAt, server: Server) = ShareSheet(node, server, { now }).also { it.load() }

    @Test
    fun a_link_is_minted_through_the_form_shown_once_and_listed_and_a_refusal_keeps_the_form() = runTest {
        val server = Server()
        server.link("a")
        val s = sheet(pot, server)
        assertEquals(listOf("a"), s.current.links?.map { it.shareId })
        s.addLink()
        val form = assertNotNull(s.current.form)
        assertEquals(listOf(ShareLinks.READ, ShareLinks.READ_WRITE), form.roles, "a Pot offers no \"Add files only\"")
        assertEquals(LocalDays.dayOf(now), form.today)
        s.role(ShareLinks.READ_WRITE)
        s.label("Family")
        s.endOn(true)
        s.endDay(form.today + 3)
        // refused by the tenant's rules: the form stays with why, nothing listed
        server.mintAnswer = ShareLinks.Outcome.PASSWORD_REQUIRED
        val refused = s.save()
        assertEquals(Notice(Msg.SHARE_REQUIREMENT_REFUSED), refused.problem)
        assertNotNull(refused.form)
        assertNull(refused.fresh)
        // sent again: minted, the form closed, the link shown once and listed
        server.mintAnswer = ShareLinks.Outcome.CREATED
        val done = s.save()
        assertNull(done.form)
        assertNull(done.problem)
        assertEquals("https://t/s/new1#k=fresh", done.fresh)
        assertEquals(Triple(ShareLinks.READ_WRITE, "Family", LocalDays.endOf(form.today + 3)), server.minted)
        assertEquals("", server.passwordSent)
        assertEquals(listOf("new1", "a"), done.links?.map { it.shareId }, "the links are read again after the mint")
        s.freshSeen()
        assertNull(s.current.fresh)
        assertEquals(0, server.rekeys, "a mint closes nothing")
    }

    @Test
    fun add_files_only_on_a_folder_makes_a_drop_box_not_a_link() = runTest {
        val server = Server()
        val s = sheet(folder, server)
        s.addLink()
        assertEquals(ShareLinks.WRITE_ONLY, s.current.form?.roles?.last())
        s.role(ShareLinks.WRITE_ONLY)
        assertFalse(s.current.form!!.canSave, "a drop box needs its name")
        s.label("Uploads")
        val done = s.save()
        assertEquals(listOf("dropbox:Uploads"), server.sent)
        assertEquals("https://t/s/box#k=drop", done.fresh)
        assertEquals(1, server.changes, "a node was made: the lists are read again")
    }

    @Test
    fun an_own_open_link_is_edited_in_place_its_password_kept_when_left_blank() = runTest {
        val server = Server()
        server.link("a", label = "Old")
        server.link("b", mine = false)
        val s = sheet(pot, server)
        s.edit("b")
        assertNull(s.current.form, "another member's link is not edited")
        s.edit("a")
        assertEquals("a", s.current.editing)
        assertEquals("Old", s.current.form?.label)
        assertEquals(Msg.SHARE_SAVE, s.submit)
        s.label("New")
        val done = s.save()
        assertEquals(listOf("edit:a"), server.sent)
        assertEquals(ShareLinkChanges.Edit("New", null, null, ShareLinks.READ).let { listOf(it.label, it.password, it.expiresAt, it.role) },
            server.edited!!.let { listOf(it.label, it.password, it.expiresAt, it.role) })
        assertNull(done.form)
        assertNull(done.editing)
        assertNull(done.fresh, "an edit keeps the address: nothing to show once")
        assertEquals("New", done.links?.first { it.shareId == "a" }?.label)
        assertEquals(0, server.rekeys)
    }

    @Test
    fun a_renew_is_asked_first_shows_the_new_address_once_and_tells_the_tenant_it_re_keyed() = runTest {
        val server = Server()
        server.link("a")
        val s = sheet(pot, server)
        s.askRenew("a")
        assertEquals("a", s.current.renewing)
        s.cancelAsk()
        assertNull(s.current.renewing)
        assertEquals(s.current, s.renew(), "nothing asked: nothing sent")
        assertEquals(emptyList(), server.sent)
        s.askRenew("a")
        val done = s.renew()
        assertEquals(listOf("renew:a"), server.sent)
        assertNull(done.renewing)
        assertNull(done.busy)
        assertEquals("https://t/s/renewed1#k=renewed", done.fresh)
        assertEquals(listOf("renewed1"), done.links?.map { it.shareId }, "the old link is gone, the new one listed")
        assertEquals(1, server.rekeys, "the old link closed: the tenant's open models read again")
    }

    @Test
    fun a_link_switched_off_re_keys_and_switched_on_again_keeps_its_address() = runTest {
        val server = Server()
        server.link("a")
        val s = sheet(pot, server)
        assertEquals(s.current, s.reactivate("a"), "an open link is not switched on")
        val off = s.switchOff("a")
        assertEquals(listOf("off:a"), server.sent)
        assertFalse(off.links!!.single().enabled)
        assertNull(off.notice)
        assertEquals(1, server.rekeys)
        assertTrue(s.row("a")!!.canReactivate)
        val on = s.reactivate("a")
        assertEquals(listOf("off:a", "on:a"), server.sent)
        assertTrue(on.links!!.single().enabled)
        assertEquals("https://t/s/a#k=keya", on.links!!.single().url, "its old address")
        assertEquals(1, server.rekeys, "switching on re-keys nothing")
    }

    @Test
    fun only_a_switched_off_link_is_deleted_asked_first() = runTest {
        val server = Server()
        server.link("open")
        server.link("off", enabled = false)
        val s = sheet(pot, server)
        s.askDelete("open")
        assertNull(s.current.deleting, "an open link is switched off first")
        s.askDelete("off")
        assertEquals("off", s.current.deleting)
        val done = s.delete()
        assertEquals(listOf("delete:off"), server.sent)
        assertEquals(listOf("open"), done.links?.map { it.shareId })
        assertNull(done.deleting)
        assertEquals(1, server.rekeys)
    }

    @Test
    fun a_local_day_ends_at_its_last_millisecond() {
        assertEquals(Triple(1970, 1, 1), LocalDays.civil(0))
        assertEquals(Triple(2026, 11, 11), LocalDays.civil(20_768))
        assertEquals(Triple(2000, 2, 29), LocalDays.civil(11_016))
        for (day in listOf(20_768L, 20_905L, 21_122L)) { // a day, the days of the change to summer time and back (Europe, 2027)
            val end = kotlin.time.Instant.parse(LocalDays.endOf(day)).toEpochMilliseconds()
            assertEquals(day, LocalDays.dayOf(end))
            assertEquals(day + 1, LocalDays.dayOf(end + 1))
        }
        assertEquals(20_768L, LocalDays.dayOf(LocalDays.endOf(20_768)))
    }
}
