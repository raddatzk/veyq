package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.sharing.GuestView
import me.raddatz.veyq.mobile.sharing.MemoryGuestSessions
import me.raddatz.veyq.mobile.sharing.PlayedGuestContent
import me.raddatz.veyq.mobile.sharing.PlayedShare
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A received link through the bridge (ADR 0150 decision 7): *Open a link* signed out, the gates, the guest's pages and its
 * name, *Back* that asks and lets go — and **no update of the whole visit carries the link or its key**: every update's
 * JSON is scanned for the key's canaries (base64url, base64, hex) and for the link itself, as the host would receive it.
 */
class ReceivedBridgeTest {
    private val p = BouncyCastlePrimitives

    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key
    }

    /** A sign-in door that signs nothing in: the link is opened signed out. */
    private class Door : SessionModel.Door<Acc, String> {
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc = throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("none")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("none")
        override suspend fun resume(record: TrustedDevice) = error("none")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = null
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = ""
        override suspend fun spaces(session: String) = emptyList<me.raddatz.veyq.mobile.navigation.SpaceRef>()
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    private fun bridge(share: PlayedShare) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(
            SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden,
            received = { link, queue, password ->
                GuestView.open(p, link, { block -> HttpClient(MockEngine(share.handler), block) }, MemoryDeviceStore(), MemoryGuestSessions(), { "en" }, handedPassword = password, queue = queue)
            },
        ) { null }
    }

    @Test
    fun aWholeGuestVisitThroughTheBridgeSendsNoUpdateWithTheLinkOrItsKey() = runBlocking {
        val share = PlayedShare(p, role = "READ_WRITE", password = "pw", termsRevision = 1)
        val content = PlayedGuestContent(share)
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "bytes".encodeToByteArray())
        val b = bridge(share)
        val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen("  ${share.link}  ")))
        val w = b.watching(Watched.Received(opened.received))
        val all = mutableListOf<String>()
        suspend fun until(pick: (ReceivedShown) -> Boolean): ReceivedShown = w.untilJson { j ->
            all += j.toString()
            val u = wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received
            u.shown?.takeIf(pick)
        }
        assertEquals(ReceivedGate.LOCKED, until { it.gate == ReceivedGate.LOCKED }.gate)
        b.ask(Call.ReceivedPassword(opened.received, "wrong"))
        assertTrue(until { it.askedAgain }.askedAgain)
        b.ask(Call.ReceivedPassword(opened.received, "pw"))
        val terms = until { it.gate == ReceivedGate.TERMS }
        assertEquals("Space terms", terms.terms)
        b.ask(Call.ReceivedAcceptTerms(opened.received))
        val ready = until { it.gate == ReceivedGate.READY && it.page != null }
        assertEquals(share.rootName, ready.rootName)
        assertEquals(ReceivedPageKind.LEVEL, ready.page!!.kind)
        assertEquals(listOf("Trip"), ready.page!!.rows.map { it.name })
        assertFalse(ready.keepOffered, "Keep offered signed out")
        b.ask(Call.ReceivedName(opened.received, "Ben"))
        assertEquals("Ben", until { it.guestName != null }.guestName)
        b.ask(Call.ReceivedOpenNode(opened.received, memory))
        val gallery = until { it.page?.kind == ReceivedPageKind.GALLERY }
        assertNotNull(gallery.page!!.gallery, "the gallery was not registered for the Gallery.* calls")
        b.ask(Call.ReceivedBack(opened.received))
        until { it.page?.kind == ReceivedPageKind.LEVEL }
        b.ask(Call.ReceivedBack(opened.received))
        assertTrue(until { it.leaving }.leaving, "Back at the root left without asking")
        b.ask(Call.ReceivedStay(opened.received))
        assertEquals(Answer.Done, b.ask(Call.ReceivedLeave(opened.received)))
        w.untilJson { j -> all += j.toString(); (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).takeIf { it.shown == null } }
        assertIs<Answer.Refused>(b.ask(Call.ReceivedBack(opened.received)))

        val key = share.kShare
        val canaries = listOf(b64url(key), b64(key), b64(key).trimEnd('='), hex(key), share.link, share.shareId)
        assertTrue(all.size >= 6, "too few updates: ${all.size}")
        for (json in all) for (c in canaries) if (json.contains(c)) fail("an update carried the link or its key: $json")
        share.assertNoKeyInAnyRequest()
        b.shutdown()
    }

    /**
     * The guest-write record's notice (ADR 0093 § 10, inbox #1018): a writing link's update carries the days its instance
     * keeps a writer's address, port, time and `Origin`, and where its privacy notice says more — the host shows them
     * before any write. A link that does not write carries none.
     */
    @Test
    fun aWritingLinkCarriesItsRecordDaysAndItsInstancesPrivacyNoticeAReadingOneNone() = runBlocking {
        suspend fun ready(role: String, days: Int?): ReceivedShown {
            val share = PlayedShare(p, role = role)
            days?.let { share.extraResolve["guestWriteRecordDays"] = kotlinx.serialization.json.JsonPrimitive(it) }
            val b = bridge(share)
            val opened = assertIs<Answer.ReceivedOpened>(b.ask(Call.ReceivedOpen(share.link)))
            val shown = b.watching(Watched.Received(opened.received)).untilJson { j ->
                (wire.decodeFromJsonElement(Update.serializer(), j) as Update.Received).shown?.takeIf { it.gate == ReceivedGate.READY }
            }
            for (c in listOf(b64url(share.kShare), share.shareId)) assertFalse(wire.encodeToString(ReceivedShown.serializer(), shown).contains(c))
            b.shutdown()
            return shown
        }
        val writing = ready("READ_WRITE", 30)
        assertEquals(30, writing.recordDays)
        assertEquals("https://photos.example.org/privacy#guest-write-record", writing.privacy)
        assertNull(ready("READ", null).recordDays)
    }

    @Test
    fun aTextThatIsNoLinkIsAnsweredWithWhyAndAReviewWithTheBrowser() = runBlocking {
        val share = PlayedShare(p)
        val b = bridge(share)
        assertEquals(Answer.ReceivedProblem(Msg.LINK_PROBLEM_NOT_A_URL, false), b.ask(Call.ReceivedOpen("hello")))
        assertEquals(Answer.ReceivedProblem(Msg.LINK_PROBLEM_MODERATION, true), b.ask(Call.ReceivedOpen("${share.origin}/s/${share.shareId}#moderation")))
        assertNull(share.requests.firstOrNull(), "a refused text reached a server")
        b.shutdown()
    }
}
