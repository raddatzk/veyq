package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * The dispatch (CR D6) over a session door the test plays, as the host drives it: every call and watch as JSON, every
 * answer and update read back as the host reads it. The tenant this door opens is no `TenantSession`, so no shell opens
 * over it: the shell's, Search's and the Pot's calls are refused here, and their dispatch is held by
 * [LiveBridgeTest] over the real core (the shell and the Pot models take only a `TenantSession`).
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
            Call.SearchQuery("Tr"), Call.PotOpen(ref),
        ).forEach { assertEquals(refused, b.ask(it), "$it") }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(Call.PotRefresh(9)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(Call.PotClose(9)))
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
