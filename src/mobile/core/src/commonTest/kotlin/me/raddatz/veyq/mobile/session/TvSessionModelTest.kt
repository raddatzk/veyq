package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A TV's way in (ADR 0159 decision 4, ADR 0160): it starts at its picker and resumes nothing, opens one account at a time
 * behind that account's own PIN, counts a wrong PIN as the server answers it, drops an account whose wrap is gone, and
 * locks back to the picker — over a door the test plays. Beside the PIN, staying signed in without one (owner
 * 2026-11-26): offered where the store keeps the login level, listed on the picker and opened there without a PIN, locked
 * back to the picker like any other.
 */
class TvSessionModelTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Door(override val form: DeviceForm = DeviceForm.TV) : SessionModel.Door<Acc, String> {
        val records = mutableListOf<TrustedDevice>()
        val pins = HashMap<String, String>() // record key -> PIN
        val failures = HashMap<String, Int>()
        val resumed = mutableListOf<String>()
        val retired = mutableListOf<String>()
        val pinsSet = mutableListOf<Pair<String, String>>()
        val stays = mutableListOf<Pair<String, StaySignedIn>>()
        var hardware = true

        /** The levels the TV's store keeps: the login level, staying without a PIN (owner 2026-11-26). */
        var keeps = setOf(StaySignedIn.LOGIN)

        fun pinRecord(host: String, user: String, pin: String, label: String? = null) =
            TrustedDevice("https://$host", "$user@x", user, TrustedDevice.Method.DEVICE_PIN, "w-$user", "alias-$user", label = label)
                .also { records += it; pins[it.key] = pin }

        /** An account this TV keeps without a PIN: a DEVICE_KEY record at the login level. */
        fun keptRecord(host: String, user: String, label: String? = null) =
            TrustedDevice("https://$host", "$user@x", user, TrustedDevice.Method.DEVICE_KEY, "w-$user", "alias-$user", StaySignedIn.LOGIN, label = label)
                .also { records += it }

        private fun acc(record: TrustedDevice) = Acc(record.key, listOf(TenantRef(record.userId, "t-" + record.userId, record.userId)))

        /** The pairing relay a TV's QR sign-in runs over, and the account its tail makes. */
        val pairing = PlayedServer()
        override fun relay(server: String): DeviceLink.Relay = pairing.Side(server)
        override suspend fun linked(server: String, grant: me.raddatz.veyq.mobile.crypto.PairingGrant): Acc =
            Acc("${io.ktor.http.Url(server).host}#tv", listOf(TenantRef("tv", "t-tv", "tv")))

        override val primitives = testPrimitives
        override fun trusted() = records.toList()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc = error("a TV signs in by QR only")
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("a TV signs in by QR only")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String): Acc = error("no password trust on a TV")
        /** A kept session refused (the wrap removed elsewhere) forgets the record, as `VeyqAccount.resume` does. */
        val refused = mutableSetOf<String>()
        override suspend fun resume(record: TrustedDevice): Acc {
            if (record.key in refused) {
                records.removeAll { it.key == record.key }
                throw SignInFailure(SignInFailure.Reason.SESSION_ENDED, "refresh 401")
            }
            resumed += record.key
            return acc(record)
        }
        override suspend fun trust(account: Acc, password: String) = error("no password trust on a TV")
        override suspend fun stay(account: Acc, level: StaySignedIn): TrustChange {
            stays += account.key to level
            val (host, user) = account.key.split('#')
            records.removeAll { it.key == account.key }
            keptRecord(host, user)
            return TrustChange.DONE
        }
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = records.firstOrNull { it.key == account.key }
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun retire(account: Acc) { retired += account.key }
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = keeps
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
        override fun offersPin() = hardware

        /** The server's count, played: ten wrong in a row and the wrap — so the record — is gone. */
        override suspend fun unlockWithPin(record: TrustedDevice, pin: String): Acc {
            if (records.none { it.key == record.key }) throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED)
            if (pins[record.key] == pin) { failures[record.key] = 0; return acc(record) }
            val n = (failures[record.key] ?: 0) + 1
            failures[record.key] = n
            if (n >= 10) {
                records.removeAll { it.key == record.key }
                throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "PIN wrap gone")
            }
            throw PinRefused(10 - n)
        }

        override suspend fun stayWithPin(account: Acc, pin: String): TrustChange { pinsSet += account.key to pin; return TrustChange.DONE }
    }

    private fun model(door: Door) = SessionModel(door, Hidden({}, emptySet()), RecentUses({}, emptyList()))

    @Test
    fun aTvStartsAtItsPickerAndResumesNothing() = runTest {
        val door = Door()
        door.pinRecord("a.example", "anna", "111111", label = "Anna")
        door.pinRecord("a.example", "ben", "222222")
        val m = model(door)
        assertNull(m.start())
        val picker = assertIs<Gate.Picker>(m.current)
        assertEquals(listOf("Anna", "ben@x"), picker.tiles.map { it.label })
        assertTrue(door.resumed.isEmpty(), "a TV resumes no account at start")
        assertTrue(m.accounts.isEmpty())
    }

    @Test
    fun aPickedAccountOpensOnlyWithItsOwnPin() = runTest {
        val door = Door()
        val anna = door.pinRecord("a.example", "anna", "111111")
        val ben = door.pinRecord("a.example", "ben", "222222")
        val m = model(door)
        m.start()
        assertNull(m.pick(anna.key))
        assertTrue(assertIs<Gate.Unlock>(m.current).asksPin)
        // Ben's PIN does not open Anna's account: no PIN is shared between accounts (ADR 0160 decision 1)
        assertNull(m.unlockWithPin("222222"))
        val wrong = assertIs<Gate.Unlock>(m.current)
        assertEquals(Msg.TV_PIN_WRONG, wrong.error)
        assertEquals(9, wrong.attemptsLeft)
        assertTrue(m.accounts.isEmpty())
        val opened = assertNotNull(m.unlockWithPin("111111"))
        assertEquals(anna.key, opened.account.key)
        assertEquals(listOf(anna.key), m.accounts.map { it.key })
        assertFalse(ben.key in m.accounts.map { it.key })
    }

    @Test
    fun aSecondAccountOpensOnlyAfterTheFirstClosed() = runTest {
        val door = Door()
        val anna = door.pinRecord("a.example", "anna", "111111")
        val ben = door.pinRecord("a.example", "ben", "222222")
        val m = model(door)
        m.start()
        assertNull(m.pick(anna.key))
        assertNotNull(m.unlockWithPin("111111"))
        m.lock()
        assertIs<Gate.Picker>(m.current)
        assertEquals(listOf(anna.key), door.retired, "the lock closes the open account")
        assertTrue(m.accounts.isEmpty())
        assertNull(m.pick(ben.key))
        assertNotNull(m.unlockWithPin("222222"))
        assertEquals(listOf(ben.key), m.accounts.map { it.key }, "one account at a time")
    }

    @Test
    fun theTenthWrongPinRemovesTheAccountFromThePicker() = runTest {
        val door = Door()
        val anna = door.pinRecord("a.example", "anna", "111111")
        door.pinRecord("a.example", "ben", "222222")
        val m = model(door)
        m.start()
        assertNull(m.pick(anna.key))
        repeat(9) { n ->
            assertNull(m.unlockWithPin("000000"))
            assertEquals(9 - n, assertIs<Gate.Unlock>(m.current).attemptsLeft)
        }
        assertNull(m.unlockWithPin("000000"))
        val picker = assertIs<Gate.Picker>(m.current)
        assertEquals(Msg.TV_PIN_GONE, picker.error)
        assertEquals(listOf("ben@x"), picker.tiles.map { it.label }, "only that account left this TV")
        assertTrue(m.accounts.isEmpty())
    }

    @Test
    fun theLastAccountGoneLeavesTheQrSignIn() = runTest {
        val door = Door()
        val anna = door.pinRecord("a.example", "anna", "111111")
        val m = model(door)
        m.start()
        assertNull(m.pick(anna.key))
        repeat(10) { m.unlockWithPin("000000") }
        assertIs<Gate.SignIn>(m.current)
    }

    @Test
    fun aPhoneNeitherShowsAPickerNorLocks() = runTest {
        val door = Door(form = DeviceForm.PHONE)
        val anna = door.pinRecord("a.example", "anna", "111111")
        door.records[0] = anna.copy(method = TrustedDevice.Method.DEVICE_KEY, level = StaySignedIn.LOGIN)
        val m = model(door)
        assertNotNull(m.start())
        assertEquals(listOf(anna.key), door.resumed)
        m.lock()
        assertIs<Gate.Signed>(m.current)
        assertNull(m.pick(anna.key))
    }

    // ---- staying signed in without a PIN (owner 2026-11-26, ADR 0160 § Staying signed in without a PIN) ----

    /** A TV's *Sign in with a QR code* run to its end against a phone over the door's relay. */
    private fun kotlinx.coroutines.test.TestScope.linkIn(d: Door, m: SessionModel<Acc, String>) {
        val link = m.showForSignIn("a.example", backgroundScope)!!
        val phone = DeviceLink.scanSignInCode(testPrimitives, d.pairing.Account(), scope = backgroundScope)
        runCurrent()
        phone.scan!!.camera(me.raddatz.veyq.mobile.qr.QrScan.Camera.RUNNING)
        phone.scan!!.offer(frameOf(link.matrix()!!))
        runCurrent()
        assertTrue(link.enterCode(phone.current.code!!))
        repeat(3) { advanceTimeBy(DeviceLink.POLL_MS + 1); runCurrent() }
        assertTrue(phone.confirm("TV"))
        repeat(3) { advanceTimeBy(DeviceLink.POLL_MS + 1); runCurrent() }
    }

    @Test
    fun theOfferHasThePinAndBesideItStayingWithoutOne() = runTest {
        val door = Door()
        val m = model(door)
        m.start()
        linkIn(door, m)
        val offer = assertNotNull(assertIs<Gate.Signed>(m.current).offer)
        assertTrue(offer.pin, "the PIN is still offered")
        assertEquals(listOf(StaySignedIn.LOGIN), offer.levels, "staying without a PIN is offered beside it")
        assertEquals(TrustChange.DONE, m.stay(StaySignedIn.LOGIN))
        assertEquals(listOf("a.example#tv" to StaySignedIn.LOGIN), door.stays)
        assertEquals(TrustedDevice.Method.DEVICE_KEY, door.records.single().method, "the choice kept a wrap")
        assertNull(assertIs<Gate.Signed>(m.current).offer, "the offer ended")
        assertTrue(door.pinsSet.isEmpty())
    }

    @Test
    fun aStoreWithoutTheLoginLevelOffersNoWayWithoutAPin() = runTest {
        val door = Door().apply { keeps = emptySet() }
        val m = model(door)
        m.start()
        linkIn(door, m)
        val offer = assertNotNull(assertIs<Gate.Signed>(m.current).offer)
        assertTrue(offer.levels.isEmpty())
        assertEquals(TrustChange.FAILED, m.stay(StaySignedIn.LOGIN), "a level the store does not keep is never enrolled")
        assertTrue(door.stays.isEmpty())
    }

    @Test
    fun theSkipStillKeepsNothing() = runTest {
        val door = Door()
        val m = model(door)
        m.start()
        linkIn(door, m)
        assertTrue(m.notNow())
        assertTrue(door.records.isEmpty(), "Don't keep me signed in kept a record")
        assertTrue(door.stays.isEmpty() && door.pinsSet.isEmpty())
        m.lock()
        assertIs<Gate.SignIn>(m.current, "with nothing kept the next way in is the QR sign-in")
    }

    @Test
    fun anAccountKeptWithoutAPinIsOnThePickerAndOpensWithoutOne() = runTest {
        val door = Door()
        val anna = door.pinRecord("a.example", "anna", "111111", label = "Anna")
        val ben = door.keptRecord("a.example", "ben", label = "Ben")
        val m = model(door)
        assertNull(m.start())
        assertEquals(listOf("Anna", "Ben"), assertIs<Gate.Picker>(m.current).tiles.map { it.label })
        assertTrue(door.resumed.isEmpty(), "a TV resumes no account at start, kept without a PIN or not")
        val opened = assertNotNull(m.pick(ben.key), "an account kept without a PIN opens at its pick")
        assertEquals(ben.key, opened.account.key)
        assertEquals(listOf(ben.key), door.resumed)
        assertIs<Gate.Signed>(m.current)
        // Anna's still asks her PIN, and her unlock leaves Ben closed: one account at a time
        m.lock()
        assertNull(m.pick(anna.key))
        assertTrue(assertIs<Gate.Unlock>(m.current).asksPin)
        assertNotNull(m.unlockWithPin("111111"))
        assertEquals(listOf(anna.key), m.accounts.map { it.key })
    }

    @Test
    fun theIdleLockReturnsAnAccountKeptWithoutAPinToThePicker() = runTest {
        val door = Door()
        val ben = door.keptRecord("a.example", "ben")
        val m = model(door)
        m.start()
        var now = 0L
        val idle = IdleLock { now }
        assertNotNull(m.pick(ben.key))
        idle.opened()
        now += IdleLock.IDLE_MS
        assertTrue(idle.due())
        m.lock()
        assertEquals(listOf("ben@x"), assertIs<Gate.Picker>(m.current).tiles.map { it.label })
        assertTrue(m.accounts.isEmpty(), "the lock closed the account kept without a PIN")
        assertEquals(listOf(ben.key), door.retired)
        assertNotNull(m.pick(ben.key), "picked again, it opens again")
    }

    @Test
    fun aKeptSessionGoneLeavesThePickerWithoutIt() = runTest {
        val door = Door()
        door.pinRecord("a.example", "anna", "111111")
        val ben = door.keptRecord("a.example", "ben")
        door.refused += ben.key
        val m = model(door)
        m.start()
        assertNull(m.pick(ben.key))
        val picker = assertIs<Gate.Picker>(m.current)
        assertEquals(Msg.TV_PIN_GONE, picker.error)
        assertEquals(listOf("anna@x"), picker.tiles.map { it.label })
        assertTrue(m.accounts.isEmpty())
    }

    @Test
    fun aTvSignInOffersOnlyItsQrCode() {
        assertEquals(listOf(SignInEntry.LINK_SHOW), SignInPlan.tvEntries(adding = false))
        assertEquals(listOf(SignInEntry.LINK_SHOW, SignInEntry.CANCEL), SignInPlan.tvEntries(adding = true))
    }
}
