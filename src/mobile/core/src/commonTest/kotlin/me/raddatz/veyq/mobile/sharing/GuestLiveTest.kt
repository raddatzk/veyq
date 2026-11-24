package me.raddatz.veyq.mobile.sharing

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A guest's pages follow the link live (inbox #1017; the web's `useLevelLive` over `shareGates.poll`, ADR 0081): each tick
 * re-resolves the link conditionally on the tag of what is open — a `304` costs nothing and changes nothing —, a moved link
 * opens the page on screen anew over the new tree, a link gone or re-locked flips the gates as a load would, and a failure
 * that says nothing about the link backs off without taking the page down. Over a link's instance the test plays
 * ([PlayedShare]), its requests in the test's virtual time.
 */
class GuestLiveTest {
    private val p = testPrimitives

    /** Core's own door alone: a folder of the link is its level. */
    private val folders: (String, String) -> GuestDoor? = { kind, _ -> if (kind == "FOLDER") GuestLevel.door else null }

    private fun TestScope.view(share: PlayedShare, handed: String? = null): GuestView = GuestView.over(
        (ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(StandardTestDispatcher(testScheduler)),
        MemoryDeviceStore(), MemoryGuestSessions(), handedPassword = handed, doors = folders, clock = { testScheduler.currentTime },
    )

    private fun PlayedShare.resolves() = requests.count { it.path == "/api/shares/$shareId" }

    @Test
    fun anUnmovedLinkIsA304AndThePageStaysAsItWas() = runTest {
        val share = PlayedShare(p)
        share.add(share.uuid(), share.rootId, share.rootKey, "FOLDER", "Anna")
        val v = view(share)
        v.load()
        val before = assertIs<GuestPage.Level>(v.page.value)

        assertEquals(LivePoll.Tick.DONE, v.tick())
        val asked = share.requests.last { it.path == "/api/shares/${share.shareId}" }
        assertNotNull(asked.header("If-None-Match"), "the poll asked unconditionally: every tick would read and open the whole tree")
        assertEquals(1, share.notModified, "the link served what is open, yet the server was not asked for a 304")
        assertSame(before, v.page.value, "a 304 replaced the page")
        assertIs<ShareGates.State.Ready>(v.gates.state.value)
        v.close()
    }

    @Test
    fun aMovedLinkOpensThePageOnScreenAnewWhereItStands() = runTest {
        val share = PlayedShare(p)
        val annaId = share.uuid()
        val anna = share.add(annaId, share.rootId, share.rootKey, "FOLDER", "Anna")
        val v = view(share)
        v.load()
        v.open(annaId)
        val before = assertIs<GuestPage.Level>(v.page.value)
        assertTrue(before.model.rows.isEmpty())

        // another guest (or the curator) adds a folder below the one on screen
        share.add(share.uuid(), annaId, anna, "FOLDER", "Ben")
        assertEquals(LivePoll.Tick.DONE, v.tick())
        val after = assertIs<GuestPage.Level>(v.page.value)
        assertNotSame(before, after, "the moved link left the page as it was")
        assertEquals(listOf("Ben"), after.model.rows.map { it.name }, "the new folder did not show")
        assertEquals(listOf(share.rootId, annaId), v.stack.value.map { it.nodeId }, "the guest was moved off the folder it had open")
        assertIs<ShareGates.State.Ready>(v.gates.state.value)

        // the folder on screen taken out of the link: the deepest one left
        share.children.removeAll { it.toString().contains(annaId) }
        v.tick()
        assertEquals(listOf(share.rootId), v.stack.value.map { it.nodeId })
        v.close()
    }

    @Test
    fun aLinkGoneOrRelockedFlipsTheGatesAsALoadWould() = runTest {
        val gone = PlayedShare(p)
        val v = view(gone)
        v.load()
        gone.revoked = true
        assertEquals(LivePoll.Tick.DONE, v.tick())
        assertEquals(ShareGates.State.Unavailable(unreachable = false), v.gates.state.value, "a revoked link's 404 left the page standing")
        v.close()

        // the curator changed the password: the old token is refused, the form asks; the new one opens the link as it is now
        val locked = PlayedShare(p, password = "old")
        val w = view(locked, handed = "old")
        w.load()
        assertIs<ShareGates.State.Ready>(w.gates.state.value)
        locked.password = "new"
        w.tick()
        assertEquals(ShareGates.State.Locked(wrong = true), w.gates.state.value, "a changed password left the page standing")
        val annaId = locked.uuid()
        locked.add(annaId, locked.rootId, locked.rootKey, "FOLDER", "Anna")
        w.gates.submitPassword("new")
        w.enter()
        assertIs<ShareGates.State.Ready>(w.gates.state.value)
        assertSame(w.gates.head, w.access?.head, "the page still stands over the head before the password changed")
        assertEquals(listOf("Anna"), assertIs<GuestPage.Level>(w.page.value).model.rows.map { it.name })
        w.close()
    }

    @Test
    fun aFailureThatSaysNothingOfTheLinkBacksOffAndEndsWithTheView() = runTest {
        val share = PlayedShare(p)
        var failing = false
        share.routes += { _, rec -> if (failing && rec.path == "/api/shares/${share.shareId}") respond("", HttpStatusCode.ServiceUnavailable) else null }
        val v = view(share)
        v.load()
        val page = v.page.value
        failing = true
        assertFailsWith<Exception>("a 503 counted as a tick that read the link") { v.tick() }
        assertIs<ShareGates.State.Ready>(v.gates.state.value, "a 503 took the page down")
        assertSame(page, v.page.value)

        // live: the first tick after 4–6 s, then 10, 20, 40 s ± 20 % — three asks in 59 s, not a dozen
        val start = share.resolves()
        backgroundScope.launch { v.live() }
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(3, share.resolves() - start, "a failing server was asked at the plain interval")

        // the server back: the first success returns to the 5 s cadence (the pending wait at most 48 s)
        failing = false
        advanceTimeBy(48_000 + 60_000)
        runCurrent()
        val recovered = share.resolves() - start
        assertTrue(recovered >= 3 + 1 + 9, "the poll did not return to its interval after the server came back ($recovered)")
        assertTrue(share.notModified >= 9, "the recovered poll read the link in full each time")

        // the view closed: nothing is asked any more
        v.close()
        val closed = share.resolves()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(closed, share.resolves(), "the poll outlived its view")
    }
}
