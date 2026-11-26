package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.account.KitImportTest
import me.raddatz.veyq.mobile.account.KitImportTest.Companion.KEY_HEX
import me.raddatz.veyq.mobile.account.KitImportTest.Companion.KEY_TEXT
import me.raddatz.veyq.mobile.account.KitImportTest.Companion.TOKEN_A
import me.raddatz.veyq.mobile.account.KitImportTest.Companion.TOKEN_NO_SRV
import me.raddatz.veyq.mobile.account.KitImportTest.Companion.latin1
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.words.Msg
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The way in both apps share (ADR 0161): the start, the sign-in, the unlock, the switcher and the sign-out, over a door the test plays. */
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

        /** The Secret Key each sign-in was handed: the text typed, or "bytes:" and the hex of a key handed as bytes. */
        val keys = mutableListOf<String>()

        /** The byte keys handed, as handed: the model zeroes its copy once the sign-in returns. */
        val handed = mutableListOf<ByteArray>()

        override val primitives = testPrimitives
        override fun trusted() = records.toList()
        private fun by(server: String): Acc {
            servers += server
            refuse[server]?.let { throw SignInFailure(it) }
            return accounts.getValue(server)
        }
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc {
            keys += secretKey
            if (secretKey.isBlank()) throw SignInFailure(SignInFailure.Reason.SECRET_KEY) // as the account's sign-in reads no key
            return by(server)
        }
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc {
            keys += "bytes:" + hex(secretKey)
            handed += secretKey
            return by(server)
        }
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
        /** Each tenant shown, in order (where the background runs start). */
        val shown = mutableListOf<String>()
        override fun opened(session: String) { shown += session }
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) { signedOut += account.key }
        val retired = mutableListOf<String>()
        override suspend fun retire(account: Acc) { retired += account.key }
        override suspend fun forgetDevice(account: Acc) {}

        /** Each account's own end ([Reach.Ended]'s reason), set by a test: the reconnect that found its trust gone. */
        val endings = HashMap<String, kotlinx.coroutines.flow.MutableStateFlow<SignInFailure.Reason?>>()
        fun ending(key: String) = endings.getOrPut(key) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        override fun ends(account: Acc): kotlinx.coroutines.flow.Flow<SignInFailure.Reason> =
            ending(account.key).filterNotNull()
        override fun staySignedIn() = offered
        override fun declined(account: Acc) = account.key in declined
        override fun setDeclined(account: Acc, declined: Boolean) { if (declined) this.declined += account.key else this.declined -= account.key }

        /** The pairing relay every server shares here, and each grant a link's tail spent (server, label). */
        val pairing = PlayedServer()
        val relays = mutableListOf<String>()
        val linked = mutableListOf<Pair<String, String>>()
        override fun relay(server: String): DeviceLink.Relay = pairing.Side(server).also { relays += server }
        /** Holds a link's tail at the account's making until completed (the token's spend taking its time). */
        var linkHeld: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun linked(server: String, grant: me.raddatz.veyq.mobile.crypto.PairingGrant): Acc {
            linkHeld?.await()
            linked += server to grant.label
            return by(server)
        }
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
        // each tenant shown starts what runs in the background while it is open (the re-key's resume): the start's, the switch's
        assertEquals(listOf("b.example#u2/tb", "a.example#u1/ta2"), d.shown)
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

    /** [read]'s first non-null answer, the model's watches running on their own thread: at most two seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(200) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return null
    }

    /**
     * ADR 0154 decision 1: the shown account ended on its own (a reconnect found this device's trust gone) — the core opens
     * the next one and says the shell follows it ([SessionModel.followed]); no platform compares accounts. An account not
     * shown that ends moves nothing.
     *
     * Bite: the follow in `ended` removed → red here.
     */
    @Test
    fun a_reach_ended_for_the_shown_tenant_moves_the_shell_to_the_next_one() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        val m = model(d)
        m.start() // b is shown
        assertEquals(Gate.Signed(b.key, "tb"), m.current)
        val follows = mutableListOf<String>()
        val watch = m.watchFollowed { follows += it.session }
        assertNull(m.followed.value)
        d.ending(b.key).value = SignInFailure.Reason.DEVICE_NOT_TRUSTED
        val moved = assertNotNull(waitFor { m.followed.value }, "the shell was not told to follow the next account")
        assertEquals(a, moved.account)
        assertEquals("a.example#u1/ta", moved.session)
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
        assertNotNull(waitFor { follows.takeIf { it.isNotEmpty() } }, "a watcher of the follow heard nothing")
        assertEquals(listOf("a.example#u1/ta"), follows)
        assertTrue(b.key in d.retired)
        watch.stop()
    }

    @Test
    fun an_account_not_shown_that_ends_moves_nothing() = runTest {
        val d = door()
        d.records += record("https://a.example", "u1", TrustedDevice.Method.DEVICE_KEY)
        d.records += record("https://b.example", "u2", TrustedDevice.Method.DEVICE_KEY)
        val m = model(d)
        m.start() // b is shown
        d.ending(a.key).value = SignInFailure.Reason.DEVICE_NOT_TRUSTED
        assertNotNull(waitFor { d.retired.takeIf { a.key in it } }, "the ended account was not retired")
        assertNull(m.followed.value)
        assertEquals(Gate.Signed(b.key, "tb"), m.current)
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
        // the duplicate leaves its connection only, never the device: a sign-out would wipe the account's offline store
        // (ADR 0140 decision 11), which the connection held keeps using
        assertEquals(listOf(a.key), d.retired)
        assertEquals(emptyList(), d.signedOut, "the duplicate was signed out: the account's store would be wiped")
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
        // a tenant read only for its spaces is not shown: nothing of its own starts in the background
        assertEquals(listOf("a.example#u1/ta"), d.shown)
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
    fun stop_trusting_forgets_not_now_so_the_next_sign_in_offers_again() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        m.notNow()
        m.forgetDevice(a)
        assertTrue(!d.declined(a), "Stop trusting kept the Not now")
        m.signOut(a)
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(SessionModel.Offer(a.key, both), (m.current as Gate.Signed).offer, "the next sign-in after Stop trusting offered nothing")
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

    // ---- the Emergency Kit at the sign-in: its key held by the model alone (en.account.key-model § The Emergency Kit) ----

    private val familie = "https://familie.example.org"
    private val alex = "alex.beispiel@example.org"

    private fun kitDoor() = door().apply { accounts[familie] = a }

    /** The web's fixture kit, read from its token as a scan's or a file's read hands it on. */
    private fun found(token: String = TOKEN_A, from: KitImport.ServerFrom = KitImport.ServerFrom.TOKEN) =
        KitImport.Found(EmergencyKit.read(testPrimitives, token)!!, from)

    private fun zeroed(bytes: ByteArray) = bytes.all { it == 0.toByte() }

    @Test
    fun a_kit_read_fills_email_and_server_and_only_the_model_holds_its_key() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val file = KitImportTest.pdf(KitImportTest.info("($TOKEN_A)"))
        assertTrue(m.readKit(file, KitImportTest.PlayedPictures()))
        assertTrue(zeroed(file), "the file's bytes were left as read")
        val shown = SessionModel.KitShown(alex, familie, KitImport.ServerFrom.TOKEN)
        assertEquals(Gate.SignIn(server = familie, email = alex, kit = shown), m.current)

        // what the bridge sends and a UI draws: no form of the key, nor the token that carries it
        val json = Json.encodeToString(Gate.serializer(), m.current)
        val key = me.raddatz.veyq.mobile.crypto.unhex(KEY_HEX)
        val forms = listOf(KEY_TEXT, SecretKeyText.fold(KEY_TEXT), KEY_HEX, kotlin.io.encoding.Base64.encode(key), kotlin.io.encoding.Base64.UrlSafe.encode(key).trimEnd('='), "veyqkit:")
        for (form in forms) assertFalse(form in json, "the gate carries the kit's key ($form): $json")

        val o = m.signIn(familie, alex, "pw", "")!!
        assertEquals(a, o.account)
        assertEquals(listOf("bytes:$KEY_HEX"), d.keys, "the empty Secret Key did not sign in with the held kit's")
        assertTrue(zeroed(d.handed.single()), "the key handed to the door was left unzeroed")
    }

    @Test
    fun a_failed_sign_in_keeps_the_kit_and_one_that_succeeded_ends_it_zeroed() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val f = found()
        assertTrue(m.takeKit(f))
        d.refuse[familie] = SignInFailure.Reason.CREDENTIALS
        assertNull(m.signIn(familie, alex, "wrong", ""))
        val failed = m.current as Gate.SignIn
        assertEquals(Msg.SIGNIN_ERROR_CREDENTIALS, failed.error)
        assertEquals(SessionModel.KitShown(alex, familie, KitImport.ServerFrom.TOKEN), failed.kit, "a failed sign-in let the kit go")
        assertFalse(zeroed(f.kit.secretKey))

        d.refuse.clear()
        assertEquals(a, m.signIn(familie, alex, "pw", "")!!.account)
        assertEquals(listOf("bytes:$KEY_HEX", "bytes:$KEY_HEX"), d.keys, "the retry did not use the kit the failure kept")
        assertTrue(zeroed(f.kit.secretKey), "the held key outlived the sign-in that succeeded")

        // nothing is held for the next sign-in
        m.addServer()
        assertNull(m.signIn("b.example", "e", "pw", ""))
        assertEquals("", d.keys.last())
    }

    @Test
    fun a_typed_key_wins_over_a_held_kit_and_ends_it() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val f = found()
        m.takeKit(f)
        d.refuse[familie] = SignInFailure.Reason.SECRET_KEY
        assertNull(m.signIn(familie, alex, "pw", "typed-sk"))
        assertEquals(listOf("typed-sk"), d.keys, "the held kit's key was used over the typed one")
        assertNull((m.current as Gate.SignIn).kit)
        assertTrue(zeroed(f.kit.secretKey), "the typed key left the kit's held")
        assertNull(m.signIn(familie, alex, "pw", ""))
        assertEquals("", d.keys.last())
    }

    @Test
    fun cancel_leaving_and_the_rows_clear_end_the_held_kit() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        m.signIn(familie, alex, "pw", "sk")
        m.addServer()
        val first = found()
        m.takeKit(first)
        m.cancelAdd()
        assertIs<Gate.Signed>(m.current)
        assertTrue(zeroed(first.kit.secretKey), "Cancel left the kit's key held")
        // a sign-in now (the dev login's way) has no key to fall back on: it fails as a missing key
        assertNull(m.signIn("b.example", "e", "pw", ""))
        assertEquals("", d.keys.last())
        assertEquals(Msg.SIGNIN_ERROR_SECRET_KEY, (m.current as Gate.SignIn).error)

        val second = found(TOKEN_NO_SRV, KitImport.ServerFrom.NONE)
        m.takeKit(second, typedServer = "b.example")
        m.clearKit()
        assertEquals(Gate.SignIn(server = "b.example", email = alex), m.current, "the clear took the filled form with it")
        assertTrue(zeroed(second.kit.secretKey), "the row's clear left the kit's key held")

        val third = found()
        m.takeKit(third)
        val fourth = found()
        m.takeKit(fourth)
        assertTrue(zeroed(third.kit.secretKey), "a new kit left the one it replaced held")
        assertFalse(zeroed(fourth.kit.secretKey))
        m.otherAccount()
        assertTrue(zeroed(fourth.kit.secretKey), "a fresh sign-in left the kit's key held")
    }

    @Test
    fun a_pasted_token_is_read_as_the_kits_key_and_its_email_and_server_are_not_applied() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        assertEquals(b, m.signIn("b.example", "e", "pw", "  $TOKEN_A\n")!!.account)
        assertEquals(listOf("bytes:$KEY_HEX"), d.keys)
        assertEquals("https://b.example", d.servers.last())
        assertTrue(zeroed(d.handed.single()))
        // a token that does not read goes on as typed, and fails as a wrong Secret Key would
        m.addServer()
        val broken = TOKEN_A.dropLast(4)
        m.signIn("b.example", "e", "pw", broken)
        assertEquals(broken, d.keys.last())
    }

    @Test
    fun a_kit_from_another_server_says_so_and_one_without_a_server_keeps_the_forms() = runTest {
        val m = model(kitDoor())
        m.start()
        m.takeKit(found(), typedServer = "other.example")
        assertEquals(SessionModel.KitShown(alex, familie, KitImport.ServerFrom.TOKEN, typedServer = "https://other.example"), (m.current as Gate.SignIn).kit)
        assertEquals(familie, (m.current as Gate.SignIn).server)
        m.takeKit(found(), typedServer = "Familie.example.org/")
        assertNull((m.current as Gate.SignIn).kit!!.typedServer, "the same server typed otherwise was said to differ")
        m.otherAccount()
        m.takeKit(found())
        assertNull((m.current as Gate.SignIn).kit!!.typedServer, "an empty form was said to differ")

        m.takeKit(found(TOKEN_NO_SRV, KitImport.ServerFrom.NONE), typedServer = "mine.example")
        assertEquals(Gate.SignIn(server = "mine.example", email = alex, kit = SessionModel.KitShown(alex, null, KitImport.ServerFrom.NONE)), m.current)
    }

    @Test
    fun a_file_without_a_kit_says_so_and_keeps_the_kit_held_before() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val f = found()
        m.takeKit(f)
        val file = "not a kit".latin1()
        assertFalse(m.readKit(file, KitImportTest.PlayedPictures(picture = null)))
        assertTrue(zeroed(file))
        val now = m.current as Gate.SignIn
        assertEquals(Msg.SIGNIN_KIT_UNREADABLE, now.error)
        assertEquals(familie, now.kit?.server)
        assertFalse(zeroed(f.kit.secretKey))
        m.signIn(familie, alex, "pw", "")
        assertEquals(listOf("bytes:$KEY_HEX"), d.keys)
    }

    @Test
    fun outside_the_sign_in_no_kit_is_taken() = runTest {
        val m = model(kitDoor())
        m.start()
        m.signIn(familie, alex, "pw", "sk")
        val f = found()
        assertFalse(m.takeKit(f))
        assertTrue(zeroed(f.kit.secretKey))
        val file = KitImportTest.pdf(KitImportTest.info("($TOKEN_A)"))
        assertFalse(m.readKit(file, KitImportTest.PlayedPictures()))
        assertTrue(zeroed(file))
        assertIs<Gate.Signed>(m.current)
    }

    // ---- the kit scanned (the sign-in's "Scan Emergency Kit"): the kit's parser is the scan's accepts ----

    /** The fixture kit's token rewritten as a version-2 kit: the same email, key and server, a version the apps refuse. */
    private fun v2Token(): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val json = b64.decode(TOKEN_A.removePrefix("veyqkit:")).decodeToString()
        check(json.startsWith("{\"v\":3,"))
        return "veyqkit:" + b64.encode(json.replaceFirst("{\"v\":3,", "{\"v\":2,").encodeToByteArray())
    }

    /** A frame whose one byte names the text the played decoder reads in it. */
    private fun frame(n: Int) = QrFrame(1, 1, 1, byteArrayOf(n.toByte()))

    @Test
    fun a_scanned_kit_skips_codes_that_are_no_v3_kit_and_fills_the_form_with_its_key_held() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val texts = mapOf(1 to "https://example.org/menu", 2 to v2Token(), 3 to "veyqpair:abc", 4 to "see $TOKEN_A")
        val scan = m.scanKit(typedServer = "other.example", decode = { texts[it.luminance[0].toInt()] }, scope = backgroundScope)
        scan.camera(QrScan.Camera.RUNNING)
        for (n in 1..3) {
            assertTrue(scan.offer(frame(n)))
            runCurrent()
            assertEquals(QrScan.State.Scanning, scan.current, "frame $n's code was not skipped")
            assertEquals(Gate.SignIn(), m.current, "frame $n's code reached the form")
        }
        assertTrue(scan.offer(frame(4)))
        runCurrent()
        assertEquals(QrScan.State.Found(KitImport.ServerFrom.TOKEN), scan.current)
        val shown = SessionModel.KitShown(alex, familie, KitImport.ServerFrom.TOKEN, typedServer = "https://other.example")
        assertEquals(Gate.SignIn(server = familie, email = alex, kit = shown), m.current)
        assertFalse(scan.offer(frame(4)), "a found scan read on")

        // the held key signs in, as a file's kit's does
        assertEquals(a, m.signIn(familie, alex, "pw", "")!!.account)
        assertEquals(listOf("bytes:$KEY_HEX"), d.keys)
    }

    @Test
    fun a_kit_scanned_outside_the_sign_in_is_not_taken_and_the_scan_goes_on() = runTest {
        val m = model(kitDoor())
        m.start()
        m.signIn(familie, alex, "pw", "sk")
        val scan = m.scanKit(decode = { TOKEN_A }, scope = backgroundScope)
        scan.camera(QrScan.Camera.RUNNING)
        assertTrue(scan.offer(frame(0)))
        runCurrent()
        assertEquals(QrScan.State.Scanning, scan.current)
        assertIs<Gate.Signed>(m.current)
    }

    // ---- a link from the sign-in (uc.account.onboard-device § In the apps): the app's sign-in tail, a DEVICE_KEY offer only ----

    /** Runs *sign in with a QR code* between [m]'s sign-in and a trusted device over the door's relay, to the end. */
    private fun kotlinx.coroutines.test.TestScope.linkIn(d: Door, m: SessionModel<Acc, String>, server: String, label: String = "Tablet"): DeviceLink {
        val link = m.showForSignIn(server, backgroundScope)!!
        val trusted = DeviceLink.scanSignInCode(testPrimitives, d.pairing.Account(), scope = backgroundScope)
        runCurrent()
        trusted.scan!!.camera(QrScan.Camera.RUNNING)
        trusted.scan!!.offer(frameOf(link.matrix()!!))
        runCurrent()
        assertTrue(link.enterCode(trusted.current.code!!))
        repeat(3) { advanceTimeBy(DeviceLink.POLL_MS + 1); runCurrent() }
        assertTrue(trusted.confirm(label))
        repeat(3) { advanceTimeBy(DeviceLink.POLL_MS + 1); runCurrent() }
        return link
    }

    @Test
    fun a_link_from_the_sign_in_opens_the_shell_with_the_linked_offer_and_enrols_no_password_trust() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        val link = linkIn(d, m, "a.example")
        assertEquals(DeviceLink.Step.DONE, link.current.step)
        assertEquals(listOf("https://a.example"), d.relays, "the link did not use the form's server")
        assertEquals(listOf("https://a.example" to "Tablet"), d.linked)
        assertEquals(Gate.Signed(a.key, "ta", offer = SessionModel.Offer(a.key, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN), linked = true)), m.current)
        assertNull(d.trustedWith, "a linked device enrolled a password (DEVICE) trust")
        assertEquals(listOf("a.example#u1/ta"), d.shown)

        assertEquals(TrustChange.DONE, m.stay(StaySignedIn.LOGIN))
        assertEquals(listOf(a.key to StaySignedIn.LOGIN), d.stays)
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
    }

    /** ADR 0161's Consequences: the screen closed while the grant is spent (Back, the sheet's close) — the account still opens. */
    @Test
    fun a_link_closed_while_it_finishes_still_says_the_account_it_signed_in() = runTest {
        val d = door().apply { linkHeld = kotlinx.coroutines.CompletableDeferred() }
        val m = model(d)
        m.start()
        val link = linkIn(d, m, "a.example")
        assertEquals(DeviceLink.Step.FINISHING, link.current.step)
        val opened = async { m.signedInBy(link) }
        link.close()
        runCurrent()
        assertEquals(DeviceLink.Step.ENDED, link.current.step, "the screen's close did not end the link's state")
        assertFalse(opened.isCompleted, "the sign-in was said before its tail ended")
        d.linkHeld!!.complete(Unit)
        runCurrent()
        assertIs<Gate.Signed>(m.current)
        val o = assertNotNull(opened.await(), "a link closed in FINISHING said no sign-in although its tail signed in")
        assertEquals(a.key, o.account.key)
        assertEquals("ta", o.tenant.tenantId)
    }

    @Test
    fun a_link_closed_before_it_finishes_says_no_sign_in() = runTest {
        val d = door()
        val m = model(d)
        m.start()
        val link = m.showForSignIn("a.example", backgroundScope)!!
        runCurrent()
        link.close()
        runCurrent()
        assertNull(m.signedInBy(link))
        assertIs<Gate.SignIn>(m.current)
    }

    @Test
    fun with_no_level_a_linked_offer_still_shows_and_its_not_now_is_not_remembered() = runTest {
        val d = door().apply { offered = emptySet(); declined += a.key }
        val m = model(d)
        m.start()
        linkIn(d, m, "a.example")
        assertEquals(Gate.Signed(a.key, "ta", offer = SessionModel.Offer(a.key, emptyList(), linked = true)), m.current, "the run-only offer was not made")
        d.declined.clear()
        assertTrue(m.notNow())
        assertTrue(d.declined.isEmpty(), "a linked offer's Not now was remembered as a decline")
        assertEquals(Gate.Signed(a.key, "ta"), m.current)
    }

    @Test
    fun a_link_is_offered_only_at_the_sign_in_and_never_in_the_extension_and_keeps_a_held_kit() = runTest {
        val d = kitDoor()
        val m = model(d)
        m.start()
        val f = found()
        assertTrue(m.takeKit(f))
        val before = m.current
        val link = m.scanFromTrusted(familie, scope = backgroundScope)!!
        runCurrent()
        assertEquals(DeviceLink.Step.SCANNING, link.current.step)
        assertEquals(before, m.current, "opening a link changed the sign-in")
        assertFalse(zeroed(f.kit.secretKey), "opening a link ended the held kit")
        link.close()

        linkIn(d, m, "a.example")
        assertIs<Gate.Signed>(m.current)
        assertTrue(zeroed(f.kit.secretKey), "the held kit outlived a linked sign-in")
        assertNull(m.showForSignIn("a.example", backgroundScope), "a link opened over the shell")
        assertNull(m.scanFromTrusted("a.example", scope = backgroundScope))

        val e = model(door(), elsewhere = true)
        e.start()
        assertNull(e.showForSignIn("a.example", backgroundScope), "the share extension links a device")
    }
}
