package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.depot.FileRights
import me.raddatz.veyq.mobile.memories.MemoryRights
import me.raddatz.veyq.mobile.session.DeviceForm
import me.raddatz.veyq.mobile.session.IdleLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The TV form in the core's plans (ADR 0159 decisions 2-3): a TV views and never authors, and takes nothing away — no
 * header or row action, no Memory write, mark, download or trash, no file download, replace, copy or send — decided once
 * in the core, as rights remove functions; and its idle lock (ADR 0160 decision 6).
 */
class TvFormPlansTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    /** Every kind a page is registered for in this build: core's two and each application's. */
    private val kinds = listOf("FOLDER", "SHARE_BOOKMARK", "MEMORY", "CHAPTER", "FILE", "EXPENSES_POT") // a drop box has its own page, no header

    private fun everything(kind: String, viewOnly: Boolean, online: Boolean = true) = NodeHeader.rights(
        kind, favoriteKnown = true, shareable = true, linksOn = true, rename = true, takesCover = true, coverOffered = true,
        copy = true, download = true, report = true, inGuestFolder = false, offline = true, online = online, offlineOn = true,
        viewOnly = viewOnly,
    )

    @Test
    fun aTvHeaderOrRowOffersNothingOnAnyKind() {
        for (kind in kinds) {
            assertNotNull(AppKinds.header(kind), "$kind has a page in this build")
            val plan = NodeHeader.plan(everything(kind, viewOnly = true), AppKinds.headerOrder(kind), maxButtons = 9)
            assertFalse(plan.hasMenu, "$kind: a TV's header has no menu")
            assertEquals(emptyList(), plan.buttons, "$kind: a TV's header has no button")
            assertEquals(LevelRow.Plan(emptyList(), emptyList()), LevelRow.plan(everything(kind, viewOnly = true)), "$kind: a TV's row has no menu")
        }
    }

    @Test
    fun theSameRightsOnAPhoneStillOfferEverything() {
        // the bite's other side: the TV's answer is what empties the plan, not the inputs
        val plan = NodeHeader.plan(everything("FOLDER", viewOnly = false), AppKinds.headerOrder("FOLDER"), maxButtons = 9)
        assertTrue(HeaderAction.DOWNLOAD in plan.buttons + plan.menu + plan.last)
        assertTrue(HeaderAction.DELETE in plan.last)
    }

    @Test
    fun aTvTakesNothingAwayThatOfflineReadingWouldKeep() {
        // offline keeps the download and "Available offline"; a TV keeps neither
        val offline = everything("FOLDER", viewOnly = false, online = false)
        assertTrue(offline.download && offline.offline)
        val tv = everything("FOLDER", viewOnly = true, online = false)
        assertFalse(tv.download || tv.offline || tv.report)
    }

    @Test
    fun aMemoryOnATvOnlyReads() {
        for (kind in listOf("MEMORY", "CHAPTER")) {
            val r = MemoryRights.of(MemoryRights.Access.Member(viewOnly = true), kind)
            assertTrue(r.navigate && r.filterFacts)
            assertEquals(kind == "MEMORY", r.participants)
            assertEquals(
                MemoryRights.Rights(navigate = true, play = true, filterFacts = true, participants = kind == "MEMORY"), r,
                "$kind: nothing but reading on a TV",
            )
        }
        assertFalse(MemoryRights.of(MemoryRights.Access.Member(viewOnly = true), "FOLDER").createMemory, "no new Memory on a TV")
        assertTrue(MemoryRights.of(MemoryRights.Access.Member(), "MEMORY").download, "a phone's member still downloads")
    }

    @Test
    fun aFileOnATvIsShownAndNeverTakenAway() {
        val tv = FileRights.of(FileRights.Viewer.MEMBER, inIdentityFolder = false, hasContent = true, shares = true, viewOnly = true)
        assertEquals(FileRights.Rights(download = false, replace = false, copy = false, send = false), tv)
        val phone = FileRights.of(FileRights.Viewer.MEMBER, inIdentityFolder = false, hasContent = true, shares = true)
        assertTrue(phone.download && phone.replace && phone.send)
    }

    @Test
    fun onlyATvViews() {
        assertEquals(listOf(DeviceForm.TV), DeviceForm.entries.filter { it.viewOnly })
    }

    @Test
    fun theIdleLockWaitsFifteenMinutesWhileNothingPlays() {
        var now = 0L
        val lock = IdleLock { now }
        now += IdleLock.IDLE_MS - 1
        assertFalse(lock.due())
        now += 1
        assertTrue(lock.due(), "15 minutes without input lock")
        lock.input()
        assertFalse(lock.due())
        lock.playing(true)
        now += 3 * IdleLock.IDLE_MS
        assertFalse(lock.due(), "nothing locks while something plays")
        lock.playing(false)
        now += IdleLock.IDLE_MS - 1
        assertFalse(lock.due(), "the 15 minutes start when the playing ends")
        now += 1
        assertTrue(lock.due())
    }

    @Test
    fun theIdleLockLocksOnEveryReturnFromTheBackground() {
        var now = 0L
        val lock = IdleLock { now }
        assertFalse(lock.foreground(), "a first foreground without leaving does not lock")
        lock.background()
        now += 1000
        assertTrue(lock.foreground(), "a return from the background locks")
        assertFalse(lock.foreground())
    }
}
