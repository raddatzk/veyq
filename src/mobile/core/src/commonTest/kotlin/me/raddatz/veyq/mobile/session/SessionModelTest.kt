package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The way in both apps share (ADR 0127): the start, the sign-in, the unlock, the switcher and the sign-out, over a door the test plays. */
class SessionModelTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Door : SessionModel.Door<Acc, String> {
        val records = mutableListOf<TrustedDevice>()
        val accounts = HashMap<String, Acc>() // by server
        val refuse = HashMap<String, SignInFailure.Reason>() // by server, or by record key
        var trustedWith: Pair<String, Boolean>? = null
        val signedOut = mutableListOf<String>()
        val unopenable = HashSet<String>() // tenant ids
        val servers = mutableListOf<String>()

        override fun trusted() = records.toList()
        private fun by(server: String): Acc {
            servers += server
            refuse[server]?.let { throw SignInFailure(it) }
            return accounts.getValue(server)
        }
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = by(server)
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = refuse[record.key]?.let { throw SignInFailure(it) } ?: by(record.server)
        override suspend fun resume(record: TrustedDevice) = refuse[record.key]?.let { throw SignInFailure(it) } ?: by(record.server)
        override suspend fun trust(account: Acc, password: String, remember: Boolean) { trustedWith = account.key to remember }
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedMethod(account: Acc): TrustedDevice.Method? = records.firstOrNull { it.key == account.key }?.method
        override suspend fun open(account: Acc, tenant: TenantRef): String {
            if (tenant.tenantId in unopenable) throw SignInFailure(SignInFailure.Reason.UNREACHABLE)
            return "${account.key}/${tenant.tenantId}"
        }
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) { signedOut += account.key }
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = StaySignedIn.PRESENCE
    }

    private val a = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example"), TenantRef("ta2", "A2", "a2.example")))
    private val b = Acc("b.example#u2", listOf(TenantRef("tb", "B", "b.example")))

    private fun record(server: String, userId: String, method: TrustedDevice.Method) =
        TrustedDevice(server, "p@$server", userId, method, "w", "alias")

    private val hidden = Hidden({}, emptySet())
    private var recentKept: List<UseKey>? = null
    private val recent = RecentUses({ recentKept = it }, listOf(UseKey("s", "n", 1)))

    private fun door() = Door().apply {
        accounts["https://a.example"] = a
        accounts["https://b.example"] = b
    }

    private fun model(door: Door, elsewhere: Boolean = false) = SessionModel(door, hidden, recent, elsewhere)

    @Test
    fun with_no_trusted_account_the_start_is_the_sign_in_and_in_the_extension_the_app() = runTest {
        val m = model(door())
        assertNull(m.start())
        assertEquals(Gate.SignIn(), m.current)
        val e = model(door(), elsewhere = true)
        e.start()
        assertEquals(Gate.NeedsApp, e.current)
    }

    @Test
    fun every_remembered_account_resumes_and_a_declined_prompt_stops_the_start_at_its_unlock() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        val m = model(d)
        val o = m.start()!!
        assertEquals(listOf(a, b), m.accounts)
        assertEquals("b.example#u2/tb", o.session) // the last one added is current
        assertEquals(Gate.Signed(b.key, "tb"), m.current)
        assertNull(m.start()) // once per process

        val d2 = door()
        val first = record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d2.records += first
        d2.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        d2.refuse[first.key] = SignInFailure.Reason.DEVICE_AUTH_CANCELLED
        val m2 = model(d2)
        assertNull(m2.start())
        assertEquals(Gate.Unlock(first), m2.current)
        assertEquals(emptyList(), m2.accounts) // nothing more was asked
    }

    @Test
    fun a_password_trusted_account_asks_for_its_password_at_the_start() = runTest {
        val d = door()
        val r = record("https://a.example", "u1", TrustedDevice.Method.DEVICE)
        d.records += r
        val m = model(d)
        assertNull(m.start())
        assertEquals(Gate.Unlock(r), m.current)
        val o = m.unlock("pw")!!
        assertEquals(a, o.account)
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
    }

    @Test
    fun a_sign_in_reaches_the_typed_server_trusts_the_device_and_says_why_it_failed() = runTest {
        val d = door()
        d.accounts["http://veyq.localhost:8080"] = a
        val m = model(d)
        m.start()
        val o = m.signIn("veyq.localhost:8080/", "e", "pw", "sk", remember = true)!!
        assertEquals("http://veyq.localhost:8080", d.servers.last())
        assertEquals(a.key to true, d.trustedWith)
        assertEquals("a.example#u1/ta", o.session)

        m.addServer()
        d.refuse["https://b.example"] = SignInFailure.Reason.SECRET_KEY
        assertNull(m.signIn("b.example", "e", "pw", "sk", remember = false))
        assertEquals(Gate.SignIn(error = Msg.SIGNIN_ERROR_SECRET_KEY, adding = true, server = "b.example", email = "e"), m.current)
        m.cancelAdd()
        assertEquals(Gate.Signed(a.key, "ta"), m.current)

        d.refuse.clear()
        d.accounts["https://none.example"] = Acc("none.example#u9", emptyList())
        assertNull(m.signIn("none.example", "e", "pw", "sk", remember = false))
        assertEquals(Msg.SIGNIN_ERROR_NO_TENANT, (m.current as Gate.SignIn).error)
    }

    @Test
    fun an_unlock_whose_trust_is_gone_leads_to_the_full_sign_in_a_declined_one_stays_and_a_wrong_password_says_so() = runTest {
        val d = door()
        val r = record("https://a.example", "u1", TrustedDevice.Method.DEVICE)
        d.records += r
        val m = model(d)
        m.start()
        d.refuse[r.key] = SignInFailure.Reason.CREDENTIALS
        assertNull(m.unlock("wrong"))
        assertEquals(Gate.Unlock(r, error = Msg.SIGNIN_ERROR_CREDENTIALS), m.current)
        d.refuse[r.key] = SignInFailure.Reason.DEVICE_AUTH_CANCELLED
        m.unlock("pw")
        assertEquals(Gate.Unlock(r), m.current)
        d.refuse[r.key] = SignInFailure.Reason.DEVICE_NOT_TRUSTED
        m.unlock("pw")
        assertEquals(Gate.SignIn(error = Msg.SIGNIN_ERROR_DEVICE_NOT_TRUSTED, server = r.server, email = r.email), m.current)
    }

    @Test
    fun the_dev_unlock_waits_for_the_unlock_screen() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE)
        val m = model(d)
        assertNull(m.devUnlock("pw"))
        assertEquals(a, m.start()!!.account)
    }

    @Test
    fun a_switch_opens_the_other_tenant_and_one_that_does_not_open_leaves_the_shell_where_it_was() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        val m = model(d)
        m.start()
        assertEquals("a.example#u1/ta2", m.switchTo(a, a.tenants[1])!!.session)
        assertEquals(Gate.Signed(a.key, "ta2"), m.current)
        d.unopenable += "tb"
        assertNull(m.switchTo(b, b.tenants[0]))
        assertEquals(Gate.Signed(a.key, "ta2"), m.current)
        assertTrue(m.managed().single { it.account == a }.tenants.single { it.tenant.tenantId == "ta2" }.current)
    }

    @Test
    fun a_sign_out_opens_the_next_server_and_the_last_one_forgets_what_the_device_held() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        val m = model(d)
        m.start() // b is current
        hidden.setSpaceHidden("s1", true)
        val other = m.signOut(a)
        assertEquals(false, other.wasCurrent)
        assertEquals(Gate.Signed(b.key, "tb"), m.current)
        assertTrue(hidden.isSpaceHidden("s1"))

        d.records.clear()
        val last = m.signOut(b)
        assertTrue(last.wasCurrent)
        assertNull(last.next)
        assertEquals(Gate.SignIn(), m.current)
        assertEquals(listOf(a.key, b.key), d.signedOut)
        assertTrue(!hidden.isSpaceHidden("s1"))
        assertEquals(emptyList(), recentKept)
    }

    @Test
    fun a_second_sign_in_to_a_held_account_closes_the_duplicate() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk", remember = false)
        d.accounts["https://a.example"] = a.copy()
        m.addServer()
        m.signIn("a.example", "e", "pw", "sk", remember = false)
        assertEquals(1, m.accounts.size)
        assertEquals(listOf(a.key), d.signedOut)
    }

    @Test
    fun manage_servers_lists_each_tenant_with_its_spaces_once_they_read() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk", remember = false)
        val listed = m.managed(mapOf(m.spacesKey(a, a.tenants[0]) to listOf(SpaceRef("s1", "PERSONAL", "Mine"))))
        val t = listed.single().tenants
        assertEquals(1, t[0].spaces!!.size)
        assertNull(t[1].spaces)
        assertTrue(t[0].current)
        var seen = 0
        m.loadSpaces { seen++ }
        assertEquals(2, seen)
        assertEquals(2, m.managed().single().tenants.count { it.spaces != null })
    }

    @Test
    fun a_typed_server_gets_its_scheme() {
        assertEquals("https://family.example", SessionModel.origin(" family.example/ "))
        assertEquals("http://veyq.localhost:8080", SessionModel.origin("veyq.localhost:8080"))
        assertEquals("http://localhost", SessionModel.origin("localhost"))
        assertEquals("http://x.example", SessionModel.origin("http://x.example"))
    }
}
