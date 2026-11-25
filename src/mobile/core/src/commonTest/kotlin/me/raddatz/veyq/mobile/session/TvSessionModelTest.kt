package me.raddatz.veyq.mobile.session

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
 * locks back to the picker — over a door the test plays.
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
        var hardware = true

        fun pinRecord(host: String, user: String, pin: String, label: String? = null) =
            TrustedDevice("https://$host", "$user@x", user, TrustedDevice.Method.DEVICE_PIN, "w-$user", "alias-$user", label = label)
                .also { records += it; pins[it.key] = pin }

        private fun acc(record: TrustedDevice) = Acc(record.key, listOf(TenantRef(record.userId, "t-" + record.userId, record.userId)))

        override val primitives = testPrimitives
        override fun trusted() = records.toList()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc = error("a TV signs in by QR only")
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("a TV signs in by QR only")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String): Acc = error("no password trust on a TV")
        override suspend fun resume(record: TrustedDevice): Acc { resumed += record.key; return acc(record) }
        override suspend fun trust(account: Acc, password: String) = error("no password trust on a TV")
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = records.firstOrNull { it.key == account.key }
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun retire(account: Acc) { retired += account.key }
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
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
        assertTrue(m.pick(anna.key))
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
        m.pick(anna.key)
        assertNotNull(m.unlockWithPin("111111"))
        m.lock()
        assertIs<Gate.Picker>(m.current)
        assertEquals(listOf(anna.key), door.retired, "the lock closes the open account")
        assertTrue(m.accounts.isEmpty())
        m.pick(ben.key)
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
        m.pick(anna.key)
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
        m.pick(anna.key)
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
        assertFalse(m.pick(anna.key))
    }

    @Test
    fun aTvSignInOffersOnlyItsQrCode() {
        assertEquals(listOf(SignInEntry.LINK_SHOW), SignInPlan.tvEntries(adding = false))
        assertEquals(listOf(SignInEntry.LINK_SHOW, SignInEntry.CANCEL), SignInPlan.tvEntries(adding = true))
    }
}
