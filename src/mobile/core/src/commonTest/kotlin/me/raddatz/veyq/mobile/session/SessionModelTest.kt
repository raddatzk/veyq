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
        var trustedWith: String? = null
        var offered = setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)
        val declined = HashSet<String>()
        val stays = mutableListOf<Pair<String, StaySignedIn>>()
        var stayAnswer = TrustChange.DONE
        val passwords = mutableListOf<Pair<String, String>>()
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
        override suspend fun trust(account: Acc, password: String) {
            trustedWith = account.key
            records.removeAll { it.key == account.key }
            records.add(0, record(account, TrustedDevice.Method.DEVICE, null))
        }
        override suspend fun stay(account: Acc, level: StaySignedIn): TrustChange {
            stays += account.key to level
            if (stayAnswer == TrustChange.DONE) {
                records.removeAll { it.key == account.key }
                records.add(0, record(account, TrustedDevice.Method.DEVICE_KEY, level))
            }
            return stayAnswer
        }
        override suspend fun trustWithPassword(account: Acc, password: String): TrustChange {
            passwords += account.key to password
            if (password != "pw") return TrustChange.WRONG_PASSWORD
            trust(account, password)
            return TrustChange.DONE
        }
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = records.firstOrNull { it.key == account.key }
        private fun record(account: Acc, method: TrustedDevice.Method, level: StaySignedIn?) =
            TrustedDevice("https://" + account.key.substringBefore('#'), "p@x", account.key.substringAfter('#'), method, "w", "alias", level)
        override suspend fun open(account: Acc, tenant: TenantRef): String {
            if (tenant.tenantId in unopenable) throw SignInFailure(SignInFailure.Reason.UNREACHABLE)
            return "${account.key}/${tenant.tenantId}"
        }
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) { signedOut += account.key }
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = offered
        override fun declined(account: Acc) = account.key in declined
        override fun setDeclined(account: Acc, declined: Boolean) { if (declined) this.declined += account.key else this.declined -= account.key }
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
        // a password unlock is a sign-in: the offer follows it as it follows the full one
        assertEquals(Gate.Signed(a.key, "ta", offer = SessionModel.Offer(a.key, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN))), m.current)
    }

    @Test
    fun a_sign_in_reaches_the_typed_server_trusts_the_device_and_says_why_it_failed() = runTest {
        val d = door()
        d.accounts["http://veyq.localhost:8080"] = a
        val m = model(d)
        m.start()
        val o = m.signIn("veyq.localhost:8080/", "e", "pw", "sk")!!
        assertEquals("http://veyq.localhost:8080", d.servers.last())
        assertEquals(a.key, d.trustedWith)
        assertEquals("a.example#u1/ta", o.session)

        m.addServer()
        d.refuse["https://b.example"] = SignInFailure.Reason.SECRET_KEY
        assertNull(m.signIn("b.example", "e", "pw", "sk"))
        assertEquals(Gate.SignIn(error = Msg.SIGNIN_ERROR_SECRET_KEY, adding = true, server = "b.example", email = "e"), m.current)
        m.cancelAdd()
        assertEquals(Gate.Signed(a.key, "ta", offer = SessionModel.Offer(a.key, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN))), m.current)

        d.refuse.clear()
        d.accounts["https://none.example"] = Acc("none.example#u9", emptyList())
        assertNull(m.signIn("none.example", "e", "pw", "sk"))
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
        m.signIn("a.example", "e", "pw", "sk")
        d.accounts["https://a.example"] = a.copy()
        m.addServer()
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(1, m.accounts.size)
        assertEquals(listOf(a.key), d.signedOut)
    }

    @Test
    fun manage_servers_lists_each_tenant_with_its_spaces_once_they_read() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
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

    private val both = listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)

    @Test
    fun the_offer_follows_a_sign_in_only_where_the_store_offers_a_level_and_the_account_was_not_asked() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(Gate.Signed(a.key, "ta", offer = SessionModel.Offer(a.key, both)), m.current)
        assertEquals(TrustedDevice.Method.DEVICE, d.trustedBy(a)!!.method, "the sign-in did not hold the password trust")

        // the levels in their order, only those the store offers
        val login = door().apply { offered = setOf(StaySignedIn.LOGIN) }
        model(login).apply { start(); signIn("a.example", "e", "pw", "sk") }.let {
            assertEquals(SessionModel.Offer(a.key, listOf(StaySignedIn.LOGIN)), (it.current as Gate.Signed).offer)
        }
        // a store that keeps nothing offers nothing
        val none = door().apply { offered = emptySet() }
        model(none).apply { start(); signIn("a.example", "e", "pw", "sk") }.let { assertNull((it.current as Gate.Signed).offer) }
        // an account whose offer the person declined here is not asked again
        val asked = door().apply { declined += a.key }
        model(asked).apply { start(); signIn("a.example", "e", "pw", "sk") }.let { assertNull((it.current as Gate.Signed).offer) }
        // the share extension never offers
        val ext = door().apply { records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE) }
        model(ext, elsewhere = true).apply { start(); unlock("pw") }.let { assertNull((it.current as Gate.Signed).offer) }
        // an account staying signed in already is not offered (its resume is no sign-in either)
        val staying = door().apply { records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY) }
        model(staying).apply { start() }.let { assertNull((it.current as Gate.Signed).offer) }
    }

    @Test
    fun each_answer_ends_the_offer_and_a_level_taken_is_enrolled_for_the_offers_account() = runTest {
        for (level in StaySignedIn.entries) {
            val d = door()
            val m = model(d)
            m.start()
            m.signIn("a.example", "e", "pw", "sk")
            assertEquals(TrustChange.DONE, m.stay(level))
            assertEquals(listOf(a.key to level), d.stays)
            assertEquals(Gate.Signed(a.key, "ta"), m.current, "the offer outlived its answer")
            assertEquals(level, d.trustedBy(a)!!.unlockLevel)
            assertNull(m.stay(level), "an offer answered twice")
        }
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        m.notNow()
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
        assertEquals(emptyList(), d.stays, "Not now enrolled a level")
        assertEquals(TrustedDevice.Method.DEVICE, d.trustedBy(a)!!.method, "Not now changed the password trust")
        assertTrue(d.declined(a))
    }

    @Test
    fun not_now_is_remembered_and_the_next_sign_in_offers_nothing_until_a_level_is_taken_in_manage() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        m.notNow()
        m.signOut(a)
        m.signIn("a.example", "e", "pw", "sk")
        assertNull((m.current as Gate.Signed).offer, "the declined offer came back at the next sign-in")
        // "Manage servers" stays the way to it, and a level taken there forgets the "Not now"
        assertEquals(TrustChange.DONE, m.stay(a, StaySignedIn.LOGIN))
        assertTrue(!d.declined(a), "a level taken kept the Not now")
    }

    @Test
    fun a_cancelled_prompt_keeps_the_offer_and_a_failed_enrolment_ends_it_unasked_without_signing_out() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        d.stayAnswer = TrustChange.CANCELLED
        assertEquals(TrustChange.CANCELLED, m.stay(StaySignedIn.PRESENCE))
        assertEquals(SessionModel.Offer(a.key, both), (m.current as Gate.Signed).offer, "a cancelled prompt ended the offer")
        assertEquals(TrustedDevice.Method.DEVICE, d.trustedBy(a)!!.method)
        d.stayAnswer = TrustChange.FAILED
        assertEquals(TrustChange.FAILED, m.stay(StaySignedIn.PRESENCE))
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
        assertEquals(listOf(a), m.accounts, "a failed enrolment signed out")
        assertTrue(!d.declined(a), "a failed enrolment counted as Not now")
    }

    @Test
    fun a_level_the_device_does_not_offer_is_refused_without_asking_and_the_level_held_is_done() = runTest {
        val d = door().apply { offered = setOf(StaySignedIn.LOGIN) }
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(TrustChange.FAILED, m.stay(a, StaySignedIn.PRESENCE))
        assertEquals(emptyList(), d.stays)
        assertEquals(TrustChange.DONE, m.stay(a, StaySignedIn.LOGIN))
        assertEquals(TrustChange.DONE, m.stay(a, StaySignedIn.LOGIN))
        assertEquals(1, d.stays.size, "the level held was enrolled again")
    }

    @Test
    fun the_offer_stands_over_a_switch_and_goes_with_its_account() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        m.switchTo(a, a.tenants[1])
        assertEquals(Gate.Signed(a.key, "ta2", offer = SessionModel.Offer(a.key, both)), m.current)
        m.addServer()
        d.declined += b.key // b's own offer would replace a's; keep it out of the way
        m.signIn("b.example", "e", "pw", "sk")
        assertNull((m.current as Gate.Signed).offer, "a sign-in that offers nothing kept another account's offer")
        m.switchTo(a, a.tenants[0])
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(SessionModel.Offer(a.key, both), (m.current as Gate.Signed).offer)
        m.signOut(a)
        assertNull((m.current as Gate.Signed).offer, "the offer outlived its account's sign-out")
    }

    @Test
    fun manage_servers_says_each_servers_level_and_changes_it_or_goes_back_to_the_password_trust() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        m.stay(StaySignedIn.PRESENCE)
        m.managed().single().let {
            assertEquals(TrustedDevice.Method.DEVICE_KEY, it.trusted)
            assertEquals(StaySignedIn.PRESENCE, it.level)
            assertEquals(both, it.levels)
        }
        assertEquals(TrustChange.DONE, m.stay(a, StaySignedIn.LOGIN))
        assertEquals(StaySignedIn.LOGIN, m.managed().single().level)
        assertEquals(TrustChange.WRONG_PASSWORD, m.trustWithPassword(a, "wrong"))
        assertEquals(TrustedDevice.Method.DEVICE_KEY, d.trustedBy(a)!!.method, "a wrong password changed the trust")
        assertEquals(TrustChange.DONE, m.trustWithPassword(a, "pw"))
        m.managed().single().let {
            assertEquals(TrustedDevice.Method.DEVICE, it.trusted)
            assertNull(it.level)
        }
        // already password-trusted: nothing to do, nothing asked
        val asked = d.passwords.size
        assertEquals(TrustChange.DONE, m.trustWithPassword(a, "whatever"))
        assertEquals(asked, d.passwords.size)
    }
}
