package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The share page's gates in the core (ADR 0150 decision 2, the web's `shareGates.ts`): the instance, the password, the
 * terms, the note, then the content — each before the next, over a link's instance the test plays ([PlayedShare]).
 */
class ShareGatesTest {
    private val p = testPrimitives

    private fun gates(share: PlayedShare, store: DeviceStore = MemoryDeviceStore(), sessions: GuestSessions = MemoryGuestSessions(), handed: String? = null) =
        ShareGates((ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), store, sessions, { "en" }, handed)

    @Test
    fun aLockedLinkAsksItsPasswordAndAWrongOneKeepsTheForm() = runTest {
        val share = PlayedShare(p, password = "open sesame")
        val g = gates(share)
        g.load()
        assertEquals(ShareGates.State.Locked(wrong = false), g.state.value)
        g.submitPassword("wrong")
        assertEquals(ShareGates.State.Locked(wrong = true), g.state.value, "a wrong password opened the link or left the form")
        g.submitPassword("open sesame")
        val ready = assertIs<ShareGates.State.Ready>(g.state.value)
        assertEquals(share.rootName, ready.name)
        assertEquals("READ", ready.role)
        // the token went as the header, the password never
        assertTrue(share.requests.none { it.body.decodeToString().contains("open sesame") || it.headers.contains("open sesame") })
        assertEquals(share.pwToken, share.requests.last { it.path == "/api/shares/${share.shareId}" }.header("X-Share-Password"))
    }

    @Test
    fun aHandedPasswordIsEnteredOnceAsTheReaderWould() = runTest {
        val share = PlayedShare(p, password = "pw")
        val g = gates(share, handed = "pw")
        g.load()
        assertIs<ShareGates.State.Ready>(g.state.value)
        val wrong = gates(share, handed = "nope")
        wrong.load()
        assertEquals(ShareGates.State.Locked(wrong = true), wrong.state.value)
    }

    @Test
    fun theTermsComeBeforeTheNoteAndTheNoteBeforeTheContent() = runTest {
        val share = PlayedShare(p, termsRevision = 2, note = """{"texts":{"en":"Be kind","de":"Sei nett"},"mustAccept":true}""")
        val store = MemoryDeviceStore()
        val g = gates(share, store)
        g.load()
        val terms = assertIs<ShareGates.State.Terms>(g.state.value, "the content (or the note) came before the terms")
        assertEquals("Space terms", terms.markdown)
        assertEquals("Operator section", terms.operatorSection)
        g.acceptNote() // no note is accepted ahead of the terms
        assertIs<ShareGates.State.Terms>(g.state.value)
        g.acceptTerms()
        assertEquals(ShareGates.State.Note("Be kind"), g.state.value, "the content came before the note")
        g.acceptNote()
        assertEquals(ShareGates.State.Ready(share.rootName, "READ", "FOLDER", ""), g.state.value)

        // the device remembers both, under names the server cannot read back to the link
        val again = gates(share, store)
        again.load()
        assertIs<ShareGates.State.Ready>(again.state.value)
    }

    @Test
    fun aNoteThatAsksNothingIsTheBannerAndGatesNothing() = runTest {
        val share = PlayedShare(p, note = """{"en":"Hello","de":"Hallo"}""")
        val g = ShareGates((ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), MemoryDeviceStore(), MemoryGuestSessions(), { "de-AT" })
        g.load()
        assertEquals(ShareGates.State.Ready(share.rootName, "READ", "FOLDER", "Hallo"), g.state.value)
    }

    @Test
    fun anInstanceTheAppDoesNotSpeakOrThatServesNoLinksIsRefusedBeforeAnyResolve() = runTest {
        val foreign = PlayedShare(p, major = 99)
        val g = gates(foreign)
        g.load()
        assertEquals(ShareGates.State.Refused(ShareInstance.Refusal.OTHER_VERSION), g.state.value)
        assertTrue(foreign.requests.none { it.path.startsWith("/api/shares/") }, "the link was resolved on an instance the app does not speak")

        val noLinks = PlayedShare(p, capabilities = emptyList())
        gates(noLinks).also { it.load() }.state.value.let { assertEquals(ShareGates.State.Refused(ShareInstance.Refusal.NO_LINKS), it) }

        val silent = PlayedShare(p).apply { setupAnswers = false }
        gates(silent).also { it.load() }.state.value.let { assertEquals(ShareGates.State.Refused(ShareInstance.Refusal.UNREACHABLE), it) }
    }

    @Test
    fun aKeyThatOpensNothingIsTheUnavailableState() = runTest {
        val share = PlayedShare(p)
        val other = PlayedShare(p)
        // the right id with another link's key
        val link = ReceivedLink.open("${share.origin}/s/${share.shareId}#k=${other.link.substringAfter("#k=")}") as ReceivedLink.Parsed.Ok
        val g = ShareGates(link.link, share.client(), MemoryDeviceStore(), MemoryGuestSessions(), { "en" })
        g.load()
        assertEquals(ShareGates.State.Unavailable(unreachable = false), g.state.value)
    }

    @Test
    fun aWriteLinksGuestIsNamedIntoTheCredentialStoreNeverTheDeviceStore() = runTest {
        val share = PlayedShare(p, role = "READ_WRITE")
        val sessions = MemoryGuestSessions()
        val store = SecretRefusingStore()
        val g = gates(share, store, sessions)
        g.load()
        assertNull(g.guest.value)
        val named = g.nameGuest("Ben")
        assertEquals("Ben", named.displayName)
        assertEquals(listOf(named.identityId), share.identities)
        // kept under a name that is no id of the server's
        val name = sessions.names.single()
        assertFalse(name.contains(share.shareId), "the session is filed under the share's id")
        assertTrue(name.startsWith("veyq-guest:"))
        assertEquals(named, GuestSessionJson.decode(sessions.read(name)))
        // a second visit on this device knows the guest; Not you? forgets it
        val again = gates(share, store, sessions)
        assertEquals(named, again.guest.value)
        again.forgetGuest()
        assertNull(again.guest.value)
        assertTrue(sessions.names.isEmpty())
        share.assertNoKeyInAnyRequest()
    }

    @Test
    fun noRequestOfAWholeGuestVisitHoldsTheKey() = runTest {
        val share = PlayedShare(p, role = "READ_WRITE", password = "pw", termsRevision = 1)
        val g = gates(share)
        g.load()
        g.submitPassword("pw")
        g.acceptTerms()
        g.nameGuest("Ada")
        g.guestNotice()
        assertTrue(share.requests.size >= 6, "the visit made fewer requests than it should: ${share.requests}")
        share.assertNoKeyInAnyRequest()
    }

    @Test
    fun closingTheLinkZeroesItsKey() = runTest {
        val share = PlayedShare(p)
        val g = gates(share)
        g.load()
        val head = assertNotNull(g.head)
        g.close()
        assertTrue(head.kShare.all { it == 0.toByte() }, "the head's K_share outlived the open link")
        assertNull(g.head)
    }

    /** A device store that refuses anything shaped like a guest's secret: a session's JSON, a contributor token. */
    private class SecretRefusingStore : DeviceStore {
        private val inner = MemoryDeviceStore()
        override fun read(key: String) = inner.read(key)
        override fun write(key: String, value: String) {
            check(!value.contains("contributorToken") && !value.contains("tok-") && !value.contains("identityId")) {
                "a guest's secret was written to the device store"
            }
            inner.write(key, value)
        }
        override fun remove(key: String) = inner.remove(key)
    }
}
