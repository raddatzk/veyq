@file:OptIn(kotlin.time.ExperimentalTime::class)

package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.DeviceRegister
import me.raddatz.veyq.mobile.api.models.DeviceRegisterUpdate
import me.raddatz.veyq.mobile.api.models.SessionFamily
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account's Settings page (`cr-app-account-settings` D1) over a server the test plays: the families, the device
 * wraps, the address, the capability and the device register — the register sealed and opened by the real suite, so
 * the names a row shows are the ones another device sealed.
 */
class AccountSettingsTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val masterKey = p.randomBytes(32)

    private data class Acc(override val key: String) : Connection {
        override val label: String get() = key
        override val tenants: List<TenantRef> get() = emptyList()
    }

    /** The register on the played server: a blob under a revision, guarded as the server guards it. */
    private class Register : RegisterApi {
        var revision = 0L
        var blob: String? = null

        /** Runs once before the next write and makes it a `409`: another device wrote first. */
        var before: (suspend () -> Unit)? = null
        override suspend fun get() = DeviceRegister(revision, blob)
        override suspend fun put(update: DeviceRegisterUpdate): Boolean {
            before?.let { before = null; it(); return false }
            if (update.revision != revision) return false
            revision += 1
            blob = update.blob
            return true
        }
    }

    private inner class Server : AccountSettings.Door {
        val register = Register()
        val families = mutableListOf<SessionFamily>()
        val wraps = mutableListOf<String>()
        var capabilities = setOf("core.device-linking")
        var mine: String? = null
        val calls = mutableListOf<String>()
        var failRevoke = false
        var failRemove = false
        var failList = false
        private val marks = TrustMarks(MemoryDeviceStore(), p, masterKey)

        override val deviceId = "dev-here"
        override val fallbackLabel = "Pixel"
        override fun myWrapId() = mine
        override suspend fun email() = "p@a.example" to true
        override suspend fun families(): List<SessionFamily> = if (failList) throw IllegalStateException("down") else families.toList()
        override suspend fun revoke(family: String) {
            calls += "revoke $family"
            if (failRevoke) throw IllegalStateException("down")
            families.removeAll { it.id == family }
        }
        override suspend fun revokeOthers() {
            calls += "revokeOthers"
            if (failRevoke) throw IllegalStateException("down")
            families.removeAll { it.current != true }
        }
        override suspend fun savedKeys() = wraps.toList()
        override suspend fun removeSavedKey(wrap: String) {
            calls += "remove $wrap"
            if (failRemove) throw IllegalStateException("down")
            wraps.remove(wrap)
        }
        override suspend fun capabilities() = capabilities
        override fun register() = DeviceRegisterStore(suite, register, marks, masterKey)

        /** Another device's own write moment 1: its name, its family, its wrap. */
        suspend fun another(device: String, label: String, family: String?, wrap: String?) {
            DeviceRegisterStore(suite, register, TrustMarks(MemoryDeviceStore(), p, masterKey), masterKey).remember(device, label, family, wrap, 1)
        }

        fun family(id: String, current: Boolean = false) {
            families += SessionFamily(id, "2026-11-01T10:00:00Z", "2026-11-12T09:30:00Z", current)
        }
    }

    private class Here : AccountSettings.Here<Acc, String> {
        var trust = AccountSettings.Trust(TrustedDevice.Method.DEVICE, null, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN))
        val did = mutableListOf<String>()
        override fun trust() = trust
        override suspend fun stay(level: StaySignedIn): TrustChange {
            did += "stay $level"
            trust = trust.copy(method = TrustedDevice.Method.DEVICE_KEY, level = level)
            return TrustChange.DONE
        }
        override suspend fun trustWithPassword(password: String): TrustChange { did += "password"; return TrustChange.DONE }
        override suspend fun stopTrusting() { did += "stop"; trust = trust.copy(method = null, level = null) }
        override suspend fun signOut(): SessionModel.SignedOut<Acc, String> { did += "signOut"; return SessionModel.SignedOut(true, null) }
    }

    /** This device signed in with family `f-here` (a resume: the register does not know its family yet) and a wrap `w-here`. */
    private suspend fun setUp(): Triple<Server, Here, AccountSettings<Acc, String>> {
        val s = Server()
        val h = Here()
        s.another(s.deviceId, "Pixel", null, "w-here")
        s.mine = "w-here"
        s.wraps += "w-here"
        s.family("f-here", current = true)
        s.another("dev-laptop", "Work laptop", "f-laptop", "w-laptop")
        s.family("f-laptop")
        s.wraps += "w-laptop"
        s.family("f-unknown") // a login the register never named
        return Triple(s, h, AccountSettings(s, h, "typed@a.example") { 2 })
    }

    @Test
    fun theListIsTheServersNamedFromTheRegisterThisDeviceFirst() = runTest {
        val (s, _, m) = setUp()
        assertEquals("typed@a.example", m.current.email)
        m.refresh()
        val st = m.current
        assertEquals("p@a.example", st.email)
        assertEquals(true, st.emailConfirmed)
        assertEquals(listOf("f-here", "f-laptop", "f-unknown"), st.families!!.map { it.id })
        val (here, laptop, unknown) = st.families!!
        assertTrue(here.current)
        assertEquals("Pixel", here.label)
        assertEquals("Work laptop", laptop.label)
        assertNull(unknown.label, "a family the register does not know is named")
        assertEquals(kotlin.time.Instant.parse("2026-11-01T10:00:00Z").toEpochMilliseconds(), unknown.createdAt)
        assertEquals(kotlin.time.Instant.parse("2026-11-12T09:30:00Z").toEpochMilliseconds(), unknown.lastUsedAt)
        assertEquals(AccountSettings.Device("Pixel", "f-here", Here().trust), st.device)
        assertTrue(st.hasOthers)
        assertNull(st.refusal)
        // the server's current marker joined this device's family in the register (write moment 3)
        assertEquals(s.deviceId, s.register().read().families["f-here"])
    }

    @Test
    fun addADeviceIsOfferedOnlyWithTheCapability() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        assertTrue(m.current.addDevice)
        s.capabilities = setOf("core.remember-device")
        m.refresh()
        assertFalse(m.current.addDevice)
    }

    @Test
    fun aRenameIsThisDevicesOwnEntryOnly() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        m.rename("  Kevin's phone ")
        val plain = s.register().read()
        assertEquals("Kevin's phone", plain.devices[s.deviceId]?.label)
        assertEquals("Work laptop", plain.devices["dev-laptop"]?.label)
        assertEquals("Kevin's phone", m.current.device.label)
        assertEquals("Kevin's phone", m.current.families!!.first { it.current }.label)
        val revision = s.register.revision
        m.rename("   ")
        assertEquals(revision, s.register.revision, "a blank name was written")
    }

    @Test
    fun anotherFamilyIsRevokedAndAGoneOneDropsOutWithoutAWord() = runTest {
        val (s, h, m) = setUp()
        m.refresh()
        assertNull(m.signOut("f-laptop"))
        assertEquals(listOf("f-here", "f-unknown"), m.current.families!!.map { it.id })
        assertEquals(emptyList(), h.did)
        // E1: a family another device ended meanwhile — the server answers its revoke as a known one's
        s.families.removeAll { it.id == "f-unknown" }
        m.signOut("f-unknown")
        assertEquals(listOf("f-here"), m.current.families!!.map { it.id })
        assertNull(m.current.refusal)
        assertFalse(m.current.working)
    }

    @Test
    fun thisDevicesOwnFamilyIsASignOutHere() = runTest {
        val (s, h, m) = setUp()
        m.refresh()
        val out = assertNotNull(m.signOut("f-here"))
        assertTrue(out.wasCurrent)
        assertEquals(listOf("revoke f-here"), s.calls)
        assertEquals(listOf("signOut"), h.did)
    }

    @Test
    fun signingOutEveryOtherKeepsThisOne() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        m.signOutOthers()
        assertEquals(listOf("f-here"), m.current.families!!.map { it.id })
        assertTrue(s.families.single().current == true, "this device's own session was ended")
        assertFalse(m.current.hasOthers)
    }

    @Test
    fun aFailedRevokeIsSaidAndTouchesNoKey() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        s.failRevoke = true
        m.signOutAndRemoveKey("f-laptop")
        assertEquals(Msg.ACCOUNT_SETTINGS_REVOKE_FAILED, m.current.refusal)
        assertEquals(listOf("revoke f-laptop"), s.calls)
        assertTrue("w-laptop" in s.wraps)
        m.signOutOthers()
        assertEquals(Msg.ACCOUNT_SETTINGS_REVOKE_FAILED, m.current.refusal)
        assertEquals(3, m.current.families!!.size)
    }

    @Test
    fun theEscalationRevokesFirstThenRemovesTheKey() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        val rows = m.current.families!!.associateBy { it.id }
        assertTrue(rows.getValue("f-laptop").removesKey)
        assertFalse(rows.getValue("f-here").removesKey, "this device's own key is offered for removal")
        assertFalse(rows.getValue("f-unknown").removesKey, "a family joined to no saved key offers its removal")
        m.signOutAndRemoveKey("f-laptop")
        assertEquals(listOf("revoke f-laptop", "remove w-laptop"), s.calls)
        assertEquals(listOf("w-here"), s.wraps)
        assertEquals(emptyList(), m.current.savedKeyDevices)
        assertNull(m.current.refusal)
        // not offered for this device's own family: nothing happens
        m.signOutAndRemoveKey("f-here")
        assertEquals(2, s.calls.size)
    }

    @Test
    fun aHalfDoneEscalationIsToldAndTheDeviceStaysForTheRetry() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        s.failRemove = true
        m.signOutAndRemoveKey("f-laptop")
        assertEquals(Msg.ACCOUNT_SETTINGS_ESCALATE_HALF_FAILED, m.current.refusal)
        assertEquals(listOf("f-here", "f-unknown"), m.current.families!!.map { it.id })
        assertEquals(listOf(AccountSettings.SavedKeyDevice("dev-laptop", "Work laptop")), m.current.savedKeyDevices)
        s.failRemove = false
        m.removeSavedKey("dev-laptop")
        assertEquals(listOf("w-here"), s.wraps)
        assertEquals(emptyList(), m.current.savedKeyDevices)
        assertNull(m.current.refusal)
    }

    @Test
    fun everyOtherSavedKeyIsEveryServedOneButThisDevicesOwn() = runTest {
        val (s, _, m) = setUp()
        s.wraps += "w-nobody-knows" // a wrap the register joins to no device
        m.refresh()
        assertTrue(m.current.otherSavedKeys)
        m.removeEveryOtherSavedKey()
        assertEquals(listOf("w-here"), s.wraps)
        assertFalse(m.current.otherSavedKeys)
        assertEquals(3, m.current.families!!.size, "removing saved keys ended a session")
    }

    @Test
    fun aRefusedRegisterLeavesEveryRowListedAndActing() = runTest {
        val (s, _, m) = setUp()
        m.refresh()
        s.register.revision = 0 // served older than this device has seen
        s.register.blob = null
        m.refresh()
        assertEquals(Msg.ACCOUNT_SETTINGS_REGISTER_ROLLED_BACK, m.current.registerRefusal)
        assertEquals(3, m.current.families!!.size)
        assertTrue(m.current.families!!.all { it.label == null && !it.removesKey })
        m.signOut("f-laptop")
        assertEquals(2, m.current.families!!.size)
    }

    @Test
    fun aListThatDoesNotReadIsSaid() = runTest {
        val (s, _, m) = setUp()
        s.failList = true
        m.refresh()
        assertEquals(Msg.ACCOUNT_SETTINGS_LOAD_FAILED, m.current.refusal)
        assertNull(m.current.families)
        s.failList = false
        m.refresh()
        assertNull(m.current.refusal)
        assertEquals(3, m.current.families!!.size)
    }

    @Test
    fun theTrustIsManageServersAndChangesThroughIt() = runTest {
        val (_, h, m) = setUp()
        m.refresh()
        assertEquals(TrustChange.DONE, m.stay(StaySignedIn.LOGIN))
        assertEquals(AccountSettings.Trust(TrustedDevice.Method.DEVICE_KEY, StaySignedIn.LOGIN, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)), m.current.device.trust)
        m.stopTrusting()
        assertNull(m.current.device.trust.method)
        assertEquals(TrustChange.DONE, m.trustWithPassword("pw"))
        m.signOutHere()
        assertEquals(listOf("stay LOGIN", "stop", "password", "signOut"), h.did)
    }

    @Test
    fun aReconcileRetriedAfterAConflictPrunesOnlyWhatItSaw() = runTest {
        val s = Server()
        s.another(s.deviceId, "Pixel", "f-old", "w-old")
        val store = s.register()
        // another device signs in between this device's read and its write
        s.register.before = { s.another("dev-new", "Tablet", "f-new", "w-new") }
        assertTrue(store.reconcile(live = listOf("f-here"), served = listOf("w-here"), deviceId = s.deviceId, label = "Pixel", currentFamily = "f-here", now = 5))
        val plain = store.read()
        assertEquals(mapOf("f-here" to s.deviceId, "f-new" to "dev-new"), plain.families)
        assertEquals(mapOf("w-new" to "dev-new"), plain.wraps)
        assertFalse(store.reconcile(listOf("f-here", "f-new"), null, s.deviceId, "Pixel", "f-here", 6), "nothing to reconcile wrote")
    }
}
