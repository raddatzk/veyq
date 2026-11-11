package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The dispatch (ADR 0123's Consequences, the bridge) over a session door the test plays, as the host drives it: every
 * call and watch as JSON, every answer and update read back as the host reads it, a payload beside it. The tenant this
 * door opens is no `TenantSession`, so no shell and no Pot opens over it: the shell's, Search's and the Pot's calls are
 * refused here, and their dispatch is held by [LiveBridgeTest] over the real core (the shell and the Pot models take only
 * a `TenantSession`). The members' pictures and the readers need none: the test plays the pictures, and opens a reader
 * through the bridge's own door ([Bridge.openReader]) over a [RangeReader] of frames it holds.
 */
class DispatchTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    /** What reached the door's sign-in: the server, the email, the password, the Secret Key. */
    private val signIns = mutableListOf<List<String>>()

    private inner class Door : SessionModel.Door<Acc, String> {
        val accounts = mapOf("https://a.example" to Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example"), TenantRef("ta2", "A2", "a2.example"))))
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc {
            signIns += listOf(server, email, password, secretKey)
            if (password != "right") throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
            return accounts.getValue(server)
        }
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("no trusted device")
        override suspend fun resume(record: TrustedDevice) = error("no trusted device")
        override suspend fun trust(account: Acc, password: String, remember: Boolean) {}
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedMethod(account: Acc): TrustedDevice.Method? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
    }

    private fun bridge() = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { null }
    }

    /** The pictures the played tenant's core holds, and how often the bridge asked them read. */
    private val pictures = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val picturesLoaded = java.util.concurrent.atomic.AtomicInteger()

    /** A bridge whose session opens a tenant: no shell, no Pot, the member "u1" and [pictures]. */
    private fun bridgeWithTenant() = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { _ ->
            Tenant(shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = { picturesLoaded.incrementAndGet() })
        }
    }

    private suspend fun Bridge<*, *>.signedIn() {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        unwatch(gate.id)
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 7)

    @Test
    fun aMembersPictureIsAnsweredAsBytesBesideTheJsonAndTheLetterIsRefused() = runBlocking {
        val b = bridgeWithTenant()
        b.signedIn()
        kotlinx.coroutines.delay(100.milliseconds)
        assertEquals(1, picturesLoaded.get(), "the tenant's pictures were not read as it opened")
        assertEquals(Answer.Refused(Answer.Why.NO_PICTURE), b.ask(Call.AvatarPicture("u1")), "a picture before the core held one")
        val gif = "GIF89a".encodeToByteArray() + ByteArray(30) { it.toByte() }
        pictures.value = mapOf("u1" to png, "u2" to gif, "u3" to "not an image".encodeToByteArray())

        val (answer, bytes) = b.askWithPayload(Call.AvatarPicture("u1"))
        assertEquals(Answer.Bytes("image/png"), answer)
        assertEquals(png.toList(), bytes!!.toList())
        // the type is the core's sniff of the bytes; none where it knows none
        assertEquals(Answer.Bytes("image/gif"), b.askWithPayload(Call.AvatarPicture("u2")).first)
        assertEquals(Answer.Bytes(null), b.askWithPayload(Call.AvatarPicture("u3")).first)
        assertEquals(Answer.Refused(Answer.Why.NO_PICTURE), b.ask(Call.AvatarPicture("u9")))
        b.shutdown()
    }

    @Test
    fun aReaderIsReadBackwardsInUnevenRangesToTheSameBytesAndClosesWithItsTenant() = runBlocking {
        val frame = V1.FRAME_PLAINTEXT
        val data = ByteArray(3 * frame + 12_345) { (it * 31 + it / 7).toByte() }
        val frames = (data.indices step frame).map { data.copyOfRange(it, minOf(data.size, it + frame)) }
        fun reader() = RangeReader(data.size.toLong(), frames.size) { first, until, sink -> (first until until).forEach { sink(frames[it]) } }

        val b = bridgeWithTenant()
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.openReader(reader(), null), "a reader with no tenant open")
        b.signedIn()
        val opened = b.openReader(reader(), "video/mp4") as Answer.Reader
        assertEquals(Answer.Reader(opened.reader, data.size.toLong(), "video/mp4"), opened)

        // from the end towards the start, in ranges of uneven lengths that straddle the frames' bounds
        val lengths = listOf(1, 7, 65_537, frame + 1, 100_003, 3, frame - 1)
        val got = ArrayList<ByteArray>()
        var end = data.size.toLong()
        var i = 0
        while (end > 0) {
            val length = minOf(lengths[i++ % lengths.size].toLong(), end).toInt()
            val (answer, bytes) = b.askWithPayload(Call.ReaderRead(opened.reader, end - length, length))
            assertEquals(Answer.Bytes(null), answer)
            assertEquals(length, bytes!!.size, "the range at ${end - length}")
            got.add(0, bytes)
            end -= length
        }
        assertTrue(got.size > 10)
        assertEquals(data.toList(), got.reduce { a, x -> a + x }.toList())
        // fewer only at the end, none from the end on — an empty payload, still one
        assertEquals(5, b.askWithPayload(Call.ReaderRead(opened.reader, data.size - 5L, 4096)).second!!.size)
        assertEquals(0, b.askWithPayload(Call.ReaderRead(opened.reader, data.size.toLong(), 4096)).second!!.size)
        assertEquals(Answer.Failed("IllegalArgumentException"), b.ask(Call.ReaderRead(opened.reader, -1, 4)))

        assertEquals(Answer.Done, b.ask(Call.ReaderClose(opened.reader)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(opened.reader, 0, 4)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderClose(opened.reader)))

        // every reader of a tenant closes when the tenant does: a switch to another, and a sign-out
        val held = (b.openReader(reader(), null) as Answer.Reader).reader
        assertEquals(Answer.Bytes(null), b.askWithPayload(Call.ReaderRead(held, 0, 4)).first)
        assertEquals(Answer.Done, b.ask(Call.SessionSwitchTo("a.example#u1", "ta2")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(held, 0, 4)), "a reader outlived its tenant")
        val again = (b.openReader(reader(), null) as Answer.Reader).reader
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(again, 0, 4)), "a reader outlived the sign-out")
        b.shutdown()
    }

    @Test
    fun theWayInIsDrivenByCallsAndItsGateArrivesOnTheWatch() = runBlocking {
        val b = bridge()
        val gate = b.watching(Watched.Session)
        assertEquals(Update.Session(Gate.Starting), gate.next())

        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertEquals(Gate.SignIn(), gate.until { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn } })

        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("a.example", "p@a.example", "wrong", "A3-SECRET")))
        assertEquals(
            Gate.SignIn(error = Msg.SIGNIN_ERROR_CREDENTIALS, server = "a.example", email = "p@a.example"),
            gate.until { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn && g.error != null } },
        )
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        assertEquals(Gate.Signed("a.example#u1", "ta"), gate.until { (it as Update.Session).gate as? Gate.Signed })
        // what the person typed reaches the core unchanged, the server as typed made an origin by the core
        assertEquals(listOf("https://a.example", "p@a.example", "right", "A3-SECRET"), signIns.last())

        assertEquals(Answer.Done, b.ask(Call.SessionSwitchTo("a.example#u1", "ta2")))
        assertEquals(Gate.Signed("a.example#u1", "ta2"), gate.until { ((it as Update.Session).gate as? Gate.Signed)?.takeIf { s -> !s.switching && s.tenantId == "ta2" } })
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), b.ask(Call.SessionSwitchTo("b.example#u2", "ta")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_TENANT), b.ask(Call.SessionSwitchTo("a.example#u1", "tz")))

        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertIs<Gate.SignIn>(gate.until { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn } })
        b.shutdown()
    }

    @Test
    fun withoutATenantOpenTheShellSearchAndPotCallsAreRefusedNeverThrown() = runBlocking {
        val b = bridge()
        val ref = ObjectRef("s", "n", "EXPENSE_POT", ApplicationId.EXPENSES, "Trip")
        val refused = Answer.Refused(Answer.Why.NO_TENANT_OPEN)
        listOf(
            Call.ShellChoose(ApplicationId.EXPENSES), Call.ShellSwitchSpace("s"), Call.ShellEnter(FolderRef("f", "F")), Call.ShellFilter(null),
            Call.ShellBack, Call.ShellSelect(BarTab("expenses.entries")), Call.ShellOpen(ref), Call.ShellOpenAtTop(ref), Call.ShellSearch(true), Call.ShellRefresh, Call.HomeGroups,
            Call.SearchQuery("Tr"), Call.PotOpen(ref), Call.AvatarPicture("u1"),
        ).forEach { assertEquals(refused, b.ask(it), "$it") }
        listOf(Call.PotRefresh(9), Call.PotClose(9), Call.PotDownload(9, "/tmp/never.zip", "deleted person"), Call.PotArchiveName(9))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(it), "$it") }
        listOf(Call.ReaderRead(9, 0, 4), Call.ReaderClose(9)).forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(it), "$it") }
        assertEquals(Update.Shell(null), b.watching(Watched.Shell).next())
        assertEquals(Update.Pot(9, null), b.watching(Watched.Pot(9)).next())
        b.shutdown()
    }

    @Test
    fun aRequestThatIsNoCallIsRefusedAndOneThatIsNoWatchIsNotWatched() = runBlocking {
        val b = bridge()
        val notACall = Answer.Refused(Answer.Why.NOT_A_CALL)
        for (json in listOf("", "not json", "{}", """{"type":"Session.Nope"}""", """{"type":"Search.Query","query":"a","more":1}""", """{"type":"Search.Query"}""")) {
            assertEquals(notACall, b.ask(json), json)
        }
        assertEquals(0, b.watch("""{"type":"Nothing"}""") { error("delivered") })
        b.shutdown()
    }

    @Test
    fun anEndedWatchGetsNoMoreUpdates() = runBlocking {
        val b = bridge()
        val gate = b.watching(Watched.Session)
        gate.next()
        b.unwatch(gate.id)
        b.ask(Call.SessionStart)
        kotlinx.coroutines.delay(200.milliseconds)
        assertNull(gate.pending())
        b.shutdown()
    }

    @Test
    fun theStoreAsksTheHostAndWaitsForItsReplyFromAnotherThread() {
        val kept = HashMap<String, String>()
        lateinit var host: Host
        host = Host { token, json ->
            // the host answers later, from its own thread, as a UI loop does
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> if (r.name == "full") HostReply.Failed("disk full") else HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        val store = HostStore(host)
        assertNull(store.read("veyq.recent"))
        store.write("veyq.recent", "s\tn\t1")
        assertEquals("s\tn\t1", store.read("veyq.recent"))
        store.remove("veyq.recent")
        assertNull(store.read("veyq.recent"))
        assertFailsWith<IllegalStateException> { store.write("full", "x") }
    }

    @Test
    fun aHostThatNeverRepliesFailsTheAskInsteadOfHangingIt() {
        val silent = HostStore(Host(timeout = 200.milliseconds) { _, _ -> })
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { silent.read("veyq.recent") }
    }
}
