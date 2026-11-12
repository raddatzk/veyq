package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
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
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>() // no offer: the way in alone
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
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
        assertEquals(Update.Session(Gate.SignIn()), gate.until { (it as Update.Session).takeIf { u -> u.gate is Gate.SignIn } })

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

    /**
     * What a device keeps across starts, as its store and its keys would: the trusted records, the DEVICE_KEY wraps as the
     * server holds them, the accounts whose offer the person declined, and [keys] — core's [SoftwareDeviceKeys], whose
     * prompt the test plays. A new bridge over the same [Kept] is the app started again.
     */
    private class Kept {
        val keys = SoftwareDeviceKeys(BouncyCastlePrimitives)
        val records = java.util.concurrent.CopyOnWriteArrayList<TrustedDevice>()
        val sealed = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()
        val declined = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val passwords = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    /**
     * A door whose trust goes through [Kept.keys] as `AccountDoor`'s does through a platform's: the sign-in holds the
     * password trust; staying signed in seals under the level's unlock key (the person asked at PRESENCE), a resume opens
     * it at the record's level (asked again; a cancel is `DEVICE_AUTH_CANCELLED`); the levels offered are the keys' own.
     */
    private class TrustingDoor(private val kept: Kept) : SessionModel.Door<Acc, String> {
        private val account = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example"), TenantRef("ta2", "A2", "a2.example")))
        private val masterKey = ByteArray(32) { it.toByte() }
        private val aad = "veyq/v1/device-key-wrap".encodeToByteArray()

        override fun trusted() = kept.records.toList()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc =
            if (password == "right") account else throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String): Acc =
            if (password == "right") account else throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
        override suspend fun resume(record: TrustedDevice): Acc {
            val (iv, sealed) = kept.sealed[record.key] ?: throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED)
            try {
                kept.keys.openUnlockable(iv, sealed, aad, record.unlockLevel!!)
            } catch (e: DeviceAuthFailed) {
                throw SignInFailure(if (e.cancelled) SignInFailure.Reason.DEVICE_AUTH_CANCELLED else SignInFailure.Reason.DEVICE_NOT_TRUSTED)
            }
            return account
        }
        private fun keep(account: Acc, method: TrustedDevice.Method, level: StaySignedIn?) {
            kept.records.removeIf { it.key == account.key }
            kept.records.add(0, TrustedDevice("https://a.example", "p@a.example", "u1", method, "w1", "alias", level))
        }
        override suspend fun trust(account: Acc, password: String) = keep(account, TrustedDevice.Method.DEVICE, null)
        override suspend fun stay(account: Acc, level: StaySignedIn): TrustChange {
            kept.sealed[account.key] = try {
                kept.keys.sealUnlockable(masterKey, aad, level)
            } catch (e: DeviceAuthFailed) {
                return if (e.cancelled) TrustChange.CANCELLED else TrustChange.FAILED
            }
            keep(account, TrustedDevice.Method.DEVICE_KEY, level)
            return TrustChange.DONE
        }
        override suspend fun trustWithPassword(account: Acc, password: String): TrustChange {
            kept.passwords += password
            if (password != "right") return TrustChange.WRONG_PASSWORD
            kept.sealed.remove(account.key)
            keep(account, TrustedDevice.Method.DEVICE, null)
            return TrustChange.DONE
        }
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc) = kept.records.firstOrNull { it.key == account.key }
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) { kept.records.removeIf { it.key == account.key && it.method == TrustedDevice.Method.DEVICE_KEY } }
        override suspend fun forgetDevice(account: Acc) { kept.records.removeIf { it.key == account.key } }
        override fun staySignedIn() = kept.keys.staySignedIn
        override fun declined(account: Acc) = account.key in kept.declined
        override fun setDeclined(account: Acc, declined: Boolean) { if (declined) kept.declined += account.key else kept.declined -= account.key }
    }

    /** The app started over what [kept] holds. */
    private fun started(kept: Kept) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(TrustingDoor(kept), hidden, RecentUses({}, emptyList())), hidden) { null }
    }

    private val both = listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)

    /** Signs in and answers the offer the gate carries: at [level], or "Not now" for null. */
    private suspend fun Bridge<*, *>.signIn(level: StaySignedIn?) {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        val signed = gate.until { (it as Update.Session).gate as? Gate.Signed }
        assertEquals(SessionModel.Offer("a.example#u1", both), signed.offer, "the sign-in did not carry the offer")
        assertEquals(Answer.Done, ask(if (level == null) Call.SessionNotNow else Call.SessionStaySignedIn(level)))
        assertEquals(Gate.Signed("a.example#u1", "ta"), gate.until { ((it as Update.Session).gate as? Gate.Signed)?.takeIf { s -> s.offer == null } })
        unwatch(gate.id)
    }

    @Test
    fun notNowKeepsThePasswordTrustTheNextStartUnlocksWithItAndNoOfferReturns() = runBlocking {
        val kept = Kept()
        started(kept).apply { signIn(level = null) }.shutdown()
        val record = kept.records.single()
        assertEquals(TrustedDevice.Method.DEVICE, record.method)
        assertEquals(setOf("a.example#u1"), kept.declined, "Not now was not remembered")

        val b = started(kept)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertEquals(Update.Session(Gate.Unlock(record)), gate.until { (it as Update.Session).takeIf { u -> u.gate is Gate.Unlock } })
        assertEquals(Answer.Done, b.ask(Call.SessionUnlock("wrong")))
        assertEquals(Gate.Unlock(record, error = Msg.SIGNIN_ERROR_CREDENTIALS), b.gateNow())
        assertEquals(Answer.Done, b.ask(Call.SessionUnlock("right")))
        assertEquals(Gate.Signed("a.example#u1", "ta"), b.gateNow(), "the declined offer came back")
        assertEquals(0, kept.keys.prompts, "a password unlock asked for the person")
        assertEquals(Answer.Refused(Answer.Why.NO_OFFER), b.ask(Call.SessionNotNow))
        assertEquals(Answer.Refused(Answer.Why.NO_OFFER), b.ask(Call.SessionStaySignedIn(StaySignedIn.LOGIN)))
        b.shutdown()
    }

    @Test
    fun theOfferAtPresenceResumesTheNextStartAndACancelledPromptKeepsTheOfferThenTheUnlock() = runBlocking {
        val kept = Kept()
        // the offer's prompt cancelled: refused as such, the offer stands; taken then
        started(kept).apply {
            val gate = watching(Watched.Session)
            assertEquals(Answer.Done, ask(Call.SessionStart))
            assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
            gate.until { (it as Update.Session).gate as? Gate.Signed }
            kept.keys.authenticates = false
            assertEquals(Answer.Refused(Answer.Why.TRUST_CANCELLED), ask(Call.SessionStaySignedIn(StaySignedIn.PRESENCE)))
            assertEquals(SessionModel.Offer("a.example#u1", both), (gateNow() as Gate.Signed).offer, "a cancelled prompt ended the offer")
            assertEquals(TrustedDevice.Method.DEVICE, kept.records.single().method)
            kept.keys.authenticates = true
            assertEquals(Answer.Done, ask(Call.SessionStaySignedIn(StaySignedIn.PRESENCE)))
            assertNull((gateNow() as Gate.Signed).offer)
        }.shutdown()
        val record = kept.records.single()
        assertEquals(TrustedDevice.Method.DEVICE_KEY, record.method)
        assertEquals(StaySignedIn.PRESENCE, record.level)
        assertEquals(2, kept.keys.prompts, "staying signed in at presence did not ask the person at enrolment")

        // started again: resumed, the person asked once, nothing typed, no offer
        started(kept).apply {
            assertEquals(Answer.Done, ask(Call.SessionStart))
            assertEquals(Gate.Signed("a.example#u1", "ta"), gateNow())
        }.shutdown()
        assertEquals(3, kept.keys.prompts)

        // started again, the prompt cancelled: the unlock shows, and a cancel there keeps it
        kept.keys.authenticates = false
        val b = started(kept)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertEquals(Gate.Unlock(record), b.gateNow())
        assertEquals(Answer.Done, b.ask(Call.SessionUnlock("")))
        assertEquals(Gate.Unlock(record), b.gateNow(), "a cancelled prompt left the unlock")

        // the unlock by the device key: the core asks the person itself, nothing typed crosses
        kept.keys.authenticates = true
        assertEquals(Answer.Done, b.ask(Call.SessionUnlock("")))
        assertEquals(Gate.Signed("a.example#u1", "ta"), b.gateNow())
        b.shutdown()
    }

    @Test
    fun aRememberedAccountsUnlockCarriesItsLevel() = runBlocking {
        val kept = Kept()
        started(kept).apply { signIn(StaySignedIn.LOGIN) }.shutdown()
        val record = kept.records.single()
        assertEquals(StaySignedIn.LOGIN, record.level)
        assertEquals(0, kept.keys.prompts, "the login level asked the person")
        // a login key that no longer opens (a keyring reset): the unlock shows, saying which level it is
        kept.keys.deleteUnlockKey(StaySignedIn.LOGIN)
        val b = started(kept)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        val unlock = gate.until { (it as Update.Session).gate as? Gate.Unlock }
        assertEquals(StaySignedIn.LOGIN, unlock.record.level)
        b.shutdown()
    }

    @Test
    fun theOfferFollowsTheStoreAndOtherAccountAndAddServerReachTheSignIn() = runBlocking {
        val kept = Kept()
        kept.keys.staySignedIn = setOf(StaySignedIn.LOGIN)
        val b = started(kept)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        assertEquals(SessionModel.Offer("a.example#u1", listOf(StaySignedIn.LOGIN)), gate.until { ((it as Update.Session).gate as? Gate.Signed)?.offer })
        // a level the device does not offer is refused, and ends the offer
        assertEquals(Answer.Refused(Answer.Why.TRUST_FAILED), b.ask(Call.SessionStaySignedIn(StaySignedIn.PRESENCE)))
        assertEquals(Gate.Signed("a.example#u1", "ta"), b.gateNow())

        // "Add server": the sign-in again, a store that keeps nothing now offers nothing after it
        kept.keys.staySignedIn = emptySet()
        assertEquals(Answer.Done, b.ask(Call.SessionAddServer))
        assertEquals(Update.Session(Gate.SignIn(adding = true)), gate.until { (it as Update.Session).takeIf { u -> u.gate is Gate.SignIn } })
        assertEquals(Answer.Done, b.ask(Call.SessionCancelAdd))
        assertEquals(Gate.Signed("a.example#u1", "ta"), gate.until { (it as Update.Session).gate as? Gate.Signed })
        assertEquals(Answer.Done, b.ask(Call.SessionAddServer))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        assertNull(gate.until { (it as Update.Session).gate as? Gate.Signed }.offer)
        b.shutdown()

        // "Another account" from the unlock
        val c = started(kept)
        val gate2 = c.watching(Watched.Session)
        assertEquals(Answer.Done, c.ask(Call.SessionStart))
        gate2.until { (it as Update.Session).gate as? Gate.Unlock }
        assertEquals(Answer.Done, c.ask(Call.SessionOtherAccount))
        assertEquals(Update.Session(Gate.SignIn()), gate2.until { (it as Update.Session).takeIf { u -> u.gate is Gate.SignIn } })
        c.shutdown()
    }

    @Test
    fun manageServersShowsEachServersLevelChangesItGoesBackToThePasswordTrustAndForgetDeviceEndsIt() = runBlocking {
        val kept = Kept()
        val b = started(kept)
        val manage = b.watching(Watched.Manage)
        assertEquals(Update.Manage(null), manage.next())
        b.signIn(StaySignedIn.PRESENCE)

        assertEquals(Answer.Done, b.ask(Call.ManageOpen))
        val open = manage.until { (it as Update.Manage).servers?.takeIf { s -> s.single().tenants.all { t -> t.spaces != null } } }
        val server = open.single()
        assertEquals("a.example#u1", server.connection)
        assertEquals(TrustedDevice.Method.DEVICE_KEY, server.trusted)
        assertEquals(StaySignedIn.PRESENCE, server.level)
        assertEquals(both, server.levels)
        assertEquals(listOf("ta", "ta2"), server.tenants.map { it.tenant.tenantId })
        val (current, other) = server.tenants
        assertTrue(current.current && current.shown && !current.switchable)
        assertTrue(!other.current && other.shown && other.switchable)
        assertEquals(listOf("a.example#u1/ta"), current.spaces!!.map { it.space.scopeId })

        // to the other level
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), b.ask(Call.ManageStaySignedIn("b.example#u2", StaySignedIn.LOGIN)))
        assertEquals(Answer.Done, b.ask(Call.ManageStaySignedIn("a.example#u1", StaySignedIn.LOGIN)))
        manage.until { (it as Update.Manage).servers?.single()?.takeIf { s -> s.level == StaySignedIn.LOGIN } }
        assertEquals(StaySignedIn.LOGIN, kept.records.single().level)

        // back to the password trust: the password reaches the core, a wrong one is said so
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), b.ask(Call.ManagePasswordTrust("b.example#u2", "right")))
        assertEquals(Answer.Refused(Answer.Why.WRONG_PASSWORD), b.ask(Call.ManagePasswordTrust("a.example#u1", "wrong")))
        assertEquals(Answer.Done, b.ask(Call.ManagePasswordTrust("a.example#u1", "right")))
        assertEquals(listOf("wrong", "right"), kept.passwords)
        val back = manage.until { (it as Update.Manage).servers?.single()?.takeIf { s -> s.trusted == TrustedDevice.Method.DEVICE } }
        assertNull(back.level)

        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), b.ask(Call.SessionForgetDevice("b.example#u2")))
        assertEquals(Answer.Done, b.ask(Call.SessionForgetDevice("a.example#u1")))
        assertNull(manage.until { (it as Update.Manage).servers?.single()?.takeIf { s -> s.trusted == null } }.trusted)
        assertTrue(kept.records.isEmpty(), "the device still trusts the account")

        assertEquals(Answer.Done, b.ask(Call.ManageClose))
        assertEquals(Update.Manage(null), manage.until { (it as Update.Manage).takeIf { u -> u.servers == null } })
        b.shutdown()
    }

    @Test
    fun manageServersHidesATenantAndASpaceOnThisDeviceAndShowsThemAgain() = runBlocking {
        val kept = mutableListOf<Set<String>>()
        val b = Bridge {
            val hidden = Hidden({ kept += it }, emptySet())
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { null }
        }
        b.signedIn()
        val manage = b.watching(Watched.Manage)
        assertEquals(Update.Manage(null), manage.next())
        assertEquals(Answer.Done, b.ask(Call.ManageOpen))
        manage.until { (it as Update.Manage).servers?.takeIf { s -> s.single().tenants.all { t -> t.spaces != null } } }
        fun tenant(u: Update, id: String) = (u as Update.Manage).servers?.single()?.tenants?.single { it.tenant.tenantId == id }

        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), b.ask(Call.ManageHideTenant("b.example#u2", "ta2", hidden = true)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_TENANT), b.ask(Call.ManageHideTenant("a.example#u1", "tz", hidden = true)))
        assertEquals(Answer.Done, b.ask(Call.ManageHideTenant("a.example#u1", "ta2", hidden = true)))
        val hidden = manage.until { u -> tenant(u, "ta2")?.takeIf { it.hidden } }
        assertTrue(!hidden.shown && hidden.switchable)
        assertEquals(setOf("tenant:a.example#u1#ta2"), kept.last(), "the switch was not kept on this device")

        assertEquals(Answer.Done, b.ask(Call.ManageHideSpace("a.example#u1/ta2", hidden = true)))
        manage.until { u -> tenant(u, "ta2")?.spaces?.single()?.takeIf { it.hidden } }
        assertEquals(setOf("tenant:a.example#u1#ta2", "space:a.example#u1/ta2"), kept.last())

        assertEquals(Answer.Done, b.ask(Call.ManageHideTenant("a.example#u1", "ta2", hidden = false)))
        assertEquals(Answer.Done, b.ask(Call.ManageHideSpace("a.example#u1/ta2", hidden = false)))
        val shownAgain = manage.until { u -> tenant(u, "ta2")?.takeIf { t -> t.shown && t.spaces?.single()?.hidden == false } }
        assertTrue(!shownAgain.hidden)
        assertEquals(emptySet(), kept.last())
        b.shutdown()
    }

    /** What the played header's and trash's doors were asked, and how they answer. */
    private inner class Played : me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Door, me.raddatz.veyq.mobile.navigation.TrashSheet.Door {
        var pinned: Boolean? = false
        var pinStores = true
        val restored = java.util.concurrent.CopyOnWriteArrayList<String>()
        val renamedTo = java.util.concurrent.CopyOnWriteArrayList<String>()
        var renameOutcome = me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED
        val closed = java.util.concurrent.CopyOnWriteArrayList<NodeAt>()
        val folders = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rows = java.util.concurrent.CopyOnWriteArrayList(listOf(trashed("t1", "Old"), trashed("t2", "Older")))
        val purged = java.util.concurrent.CopyOnWriteArrayList<String>()
        val restoredNames = java.util.concurrent.CopyOnWriteArrayList<String?>()
        var takenOnce = true

        override fun isFavorite(ref: NodeAt) = pinned
        override fun inIdentityFolder(ref: NodeAt) = false
        override fun linksOn() = true
        override suspend fun reportOffered() = true
        override suspend fun toggleFavorite(ref: NodeAt): Boolean {
            if (pinStores) pinned = pinned?.not()
            return pinStores
        }
        override suspend fun trash(ref: NodeAt) = me.raddatz.veyq.mobile.navigation.ShellModel.Trashed(
            me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED,
            me.raddatz.veyq.mobile.words.Notices.trashed(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, ref.name),
            me.raddatz.veyq.mobile.session.OpenNode(ref.scopeId, ref.nodeId, null, ref.kind, ref.name, ByteArray(32)),
        )
        override suspend fun undoTrash(node: me.raddatz.veyq.mobile.session.OpenNode): me.raddatz.veyq.mobile.words.Notice? { restored += node.id; return null }
        override suspend fun rename(ref: NodeAt, name: String): me.raddatz.veyq.mobile.navigation.ShellModel.Renamed {
            renamedTo += name
            val r = me.raddatz.veyq.mobile.session.NodeRename.Result(renameOutcome)
            return me.raddatz.veyq.mobile.navigation.ShellModel.Renamed(r, me.raddatz.veyq.mobile.words.Notices.renameRefused(r))
        }
        override fun closeOpen(ref: NodeAt) { closed += ref }
        override fun changed() {}

        override suspend fun list() = rows.toList()
        override fun canRestore(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed) = row.kind == "FOLDER"
        override suspend fun restore(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed, newName: String?): me.raddatz.veyq.mobile.session.NodeTrash.Restored {
            restoredNames += newName
            if (takenOnce) { takenOnce = false; return me.raddatz.veyq.mobile.session.NodeTrash.Restored.NAME_TAKEN }
            rows.removeIf { it.id == row.id }
            return me.raddatz.veyq.mobile.session.NodeTrash.Restored.RESTORED
        }
        override suspend fun purge(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed): me.raddatz.veyq.mobile.session.NodeTrash.Purged {
            purged += row.id
            rows.removeIf { it.id == row.id }
            return me.raddatz.veyq.mobile.session.NodeTrash.Purged.PURGED
        }
    }

    private fun trashed(id: String, name: String) = me.raddatz.veyq.mobile.session.NodeTrash.Trashed("s", id, null, "FOLDER", name, 1, 1, 1, "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", false)

    /** A bridge whose tenant's header, trash and new folder are played over [played]. */
    private fun bridgeWithHeader(played: Played) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                header = { node, facts, closeOpen ->
                    me.raddatz.veyq.mobile.navigation.NodeHeaderModel(node, facts, object : me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Door by played {
                        // the bridge's own closeOpen is what the header calls: the played door records it too
                        override fun closeOpen(ref: NodeAt) { closeOpen(ref); played.closeOpen(ref) }
                    })
                },
                trash = { _ -> me.raddatz.veyq.mobile.navigation.TrashSheet(played) {} },
                createFolder = { name -> played.folders += name; if (name.isBlank()) me.raddatz.veyq.mobile.words.Notice(Msg.FOLDER_NAME_EMPTY) else null },
            )
        }
    }

    @Test
    fun aHeaderIsOpenedWatchedPinnedRenamedTrashedAndUndoneThroughCalls() = runBlocking {
        val played = Played()
        val b = bridgeWithHeader(played)
        val folder = NodeAt("s", "f1", "FOLDER", "Trip")
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.HeaderOpen(folder, AppKinds.Facts(), 2)))
        b.signedIn()
        val id = (b.ask(Call.HeaderOpen(folder, AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        val w = b.watching(Watched.Header(id))
        // the plan with each action's words, once the report's offer was read
        val shown = w.until { u -> (u as Update.Header).shown?.takeIf { it.plan != null && it.plan.last.any { a -> a.id == "report" } } }
        assertEquals(folder, shown.node)
        assertEquals(false, shown.pinned)
        assertEquals(listOf("favorite" to Msg.NODE_FAVORITE, "share" to me.raddatz.veyq.mobile.session.ShareWords.of("FOLDER").title), shown.plan!!.buttons.map { it.id to it.word })
        assertEquals("delete" to Msg.FOLDER_DELETE, shown.plan.last.last().let { it.id to it.word })
        assertEquals(Msg.FOLDER_DELETE_CONFIRM, shown.deleteQuestion.msg)

        // pin: turned, its word follows; a pin not stored turns back with its notice
        assertEquals(Answer.Said(null), b.ask(Call.HeaderPin(id)))
        val pinned = w.until { u -> (u as Update.Header).shown?.takeIf { it.pinned == true } }
        assertEquals(Msg.NODE_UNFAVORITE, pinned.plan!!.buttons.first().word)
        played.pinStores = false
        assertEquals(Msg.NODE_FAVORITE_FAILED, (b.ask(Call.HeaderPin(id)) as Answer.Said).notice!!.msg)
        assertEquals(true, (b.watching(Watched.Header(id)).next() as Update.Header).shown!!.pinned)

        // rename: a refusal is the dialog's problem, a rename that landed says nothing
        played.renameOutcome = me.raddatz.veyq.mobile.session.NodeRename.Outcome.NAME_TAKEN
        val refused = b.ask(Call.HeaderRename(id, "Taken")) as Answer.Renamed
        assertTrue(refused.problem != null && refused.notice == null)
        played.renameOutcome = me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED
        assertEquals(Answer.Renamed(null, null), b.ask(Call.HeaderRename(id, "Trip 2")))
        assertEquals(listOf("Taken", "Trip 2"), played.renamedTo)

        // trash: said, gone, its undo offered in the state; the bridge's closeOpen ran; the undo brings it back
        val said = b.ask(Call.HeaderTrash(id)) as Answer.Trashed
        assertEquals(Msg.TRASH_MOVED, said.notice.msg)
        assertTrue(said.undoable, "the answer did not say the undo is offered")
        val offered = w.until { u -> (u as Update.Header).shown?.takeIf { it.undo != null } }
        assertTrue(offered.gone)
        assertEquals(listOf(folder), played.closed)
        assertEquals(Answer.Said(null), b.ask(Call.HeaderUndo(id)))
        val undone = w.until { u -> (u as Update.Header).shown?.takeIf { it.undo == null } }
        assertTrue(!undone.gone)
        assertEquals(listOf("f1"), played.restored)
        // nothing to undo any more
        assertEquals(Answer.Said(null), b.ask(Call.HeaderUndo(id)))
        assertEquals(listOf("f1"), played.restored)

        // a Pot not read: no plan until its facts come
        val potId = (b.ask(Call.HeaderOpen(NodeAt("s", "p1", "EXPENSES_POT", "Rome"), null, 2)) as Answer.HeaderOpened).header
        assertNull((b.watching(Watched.Header(potId)).next() as Update.Header).shown!!.plan)
        assertEquals(Answer.Done, b.ask(Call.HeaderFacts(potId, AppKinds.Facts(canWrite = false))))
        val pot = b.watching(Watched.Header(potId)).until { u -> (u as Update.Header).shown?.plan }
        assertTrue(pot.last.none { it.id == "copy" }, "a read-only Pot offered its copy")

        // closed: refused, watched as none; and every header closes with its tenant
        assertEquals(Answer.Done, b.ask(Call.HeaderClose(potId)))
        assertEquals(Update.Header(potId, null), b.watching(Watched.Header(potId)).next())
        listOf(Call.HeaderPin(potId), Call.HeaderTrash(potId), Call.HeaderUndo(potId), Call.HeaderRename(potId, "x"), Call.HeaderFacts(potId, null), Call.HeaderClose(potId))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_HEADER), b.ask(it), "$it") }
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_HEADER), b.ask(Call.HeaderPin(id)), "a header outlived its tenant")
        b.shutdown()
    }

    @Test
    fun aNewFolderAndASpacesTrashAreDrivenThroughCalls() = runBlocking {
        val played = Played()
        val b = bridgeWithHeader(played)
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.ShellCreateFolder("New")))
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.TrashOpen("s")))
        b.signedIn()
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder("Plans")))
        assertEquals(Msg.FOLDER_NAME_EMPTY, (b.ask(Call.ShellCreateFolder(" ")) as Answer.Said).notice!!.msg)
        assertEquals(listOf("Plans", " "), played.folders)

        val id = (b.ask(Call.TrashOpen("s")) as Answer.TrashOpened).trash
        val w = b.watching(Watched.Trash(id))
        val listed = w.until { u -> (u as Update.Trash).shown?.rows }
        assertEquals(listOf("t1" to true, "t2" to true), listed.map { it.id to it.restorable })
        assertEquals("2026-11-01T00:00:00Z", listed.first().deletedAt)

        // a restore whose name was taken asks for another: typed, retried under it
        assertEquals(Answer.Done, b.ask(Call.TrashRestore(id, "t1")))
        val asking = w.until { u -> (u as Update.Trash).shown?.takeIf { it.renaming == "t1" } }
        assertEquals(Msg.NODE_SLUG_TAKEN, asking.error)
        assertEquals(Answer.Done, b.ask(Call.TrashType(id, "  ")))
        assertTrue(w.until { u -> (u as Update.Trash).shown?.takeIf { it.retryName == "  " } }.rows!!.none { it.retryable })
        assertEquals(Answer.Done, b.ask(Call.TrashType(id, "Old 2")))
        assertTrue(w.until { u -> (u as Update.Trash).shown?.takeIf { it.retryName == "Old 2" } }.rows!!.single { it.id == "t1" }.retryable)
        assertEquals(Answer.Done, b.ask(Call.TrashRetry(id, "t1")))
        assertEquals(listOf(null, "Old 2"), played.restoredNames)
        w.until { u -> (u as Update.Trash).shown?.rows?.takeIf { r -> r.map { it.id } == listOf("t2") } }

        // "Delete forever" of the row named, with no question asked in the core first
        assertEquals(Answer.Done, b.ask(Call.TrashPurge(id, "t2")))
        assertEquals(listOf("t2"), played.purged)
        w.until { u -> (u as Update.Trash).shown?.rows?.takeIf { it.isEmpty() } }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_ROW), b.ask(Call.TrashPurge(id, "t2")))
        assertEquals(Answer.Done, b.ask(Call.TrashCancelRename(id)))

        assertEquals(Answer.Done, b.ask(Call.TrashClose(id)))
        assertEquals(Update.Trash(id, null), b.watching(Watched.Trash(id)).next())
        listOf(Call.TrashRestore(id, "t1"), Call.TrashRetry(id, "t1"), Call.TrashPurge(id, "t1"), Call.TrashType(id, "x"), Call.TrashCancelRename(id), Call.TrashClose(id))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_TRASH), b.ask(it), "$it") }
        b.shutdown()
    }

    /** A move sheet's door the test plays: a tree with a folder and a Pot, every write recorded. */
    private class PlayedMove : me.raddatz.veyq.mobile.navigation.MoveSheet.Door {
        val tree = listOf(
            me.raddatz.veyq.mobile.navigation.NodeRef("s", "trips", null, "FOLDER", "Trips"),
            me.raddatz.veyq.mobile.navigation.NodeRef("s", "rome", "trips", "EXPENSES_POT", "Rome"),
        )
        val moves = java.util.concurrent.CopyOnWriteArrayList<String>()
        val copies = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var room: Long? = null
        override suspend fun tree(scopeId: String) = tree
        override val placement = me.raddatz.veyq.mobile.session.NodeMove.PlacementAllows { _, parent -> parent == null || parent == "FOLDER" }
        override fun rootName(scopeId: String): me.raddatz.veyq.mobile.words.Piece = me.raddatz.veyq.mobile.words.Piece.Plain("Personal")
        override suspend fun move(ref: NodeAt, to: String?): me.raddatz.veyq.mobile.session.NodeMove.Outcome {
            moves += "${ref.nodeId}->$to"
            return me.raddatz.veyq.mobile.session.NodeMove.Outcome.MOVED
        }
        override suspend fun duplicateOpen(ref: NodeAt, to: String?): me.raddatz.veyq.mobile.words.Notice? { copies += "open:${ref.nodeId}->$to"; return null }
        override suspend fun duplicateSubtree(ref: NodeAt, to: String?): me.raddatz.veyq.mobile.words.Notice? { copies += "subtree:${ref.nodeId}->$to"; return null }
        override suspend fun preview(ref: NodeAt) = me.raddatz.veyq.mobile.export.SubtreeCopy.Preview(setOf("FOLDER"), emptyList())
        override suspend fun noRoom(ref: NodeAt) = room
        override fun changed() {}
    }

    /** A bridge whose tenant's move sheets and download are played over [played], its files the JVM's. */
    private fun bridgeWithMove(played: PlayedMove, written: ByteArray, leftOut: List<me.raddatz.veyq.mobile.export.NodeExport.Skipped>) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                header = { node, facts, _ -> me.raddatz.veyq.mobile.navigation.NodeHeaderModel(node, facts, Played()) },
                move = { node, copying, _, _ -> me.raddatz.veyq.mobile.navigation.MoveSheet(node, copying, played) },
                download = { node, _, sink -> if (node.name == "Broken") error("walk failed") else { sink(written); leftOut } },
            )
        }
    }

    @Test
    fun aMoveWithItsUndoACopyAndAFoldersDownloadAreDrivenThroughCalls() = runBlocking {
        val played = PlayedMove()
        val skipped = listOf(me.raddatz.veyq.mobile.export.NodeExport.Skipped("x", "MEMORY", "unsupported"))
        val b = bridgeWithMove(played, "PK-zip".encodeToByteArray(), skipped)
        val rome = NodeAt("s", "rome", "EXPENSES_POT", "Rome")
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.MoveOpen(rome, false, "deleted")))
        b.signedIn()

        // the move: opened at its place, up to the root, sent; its undo offered until the call or the core's window ends it
        val id = (b.ask(Call.MoveOpen(rome, false, "deleted")) as Answer.MoveOpened).move
        val w = b.watching(Watched.Move(id))
        val opened = w.until { u -> (u as Update.Move).shown?.takeIf { it.loaded } }
        assertEquals(listOf(null, "trips"), opened.trail.map { it.nodeId })
        assertEquals(Msg.MOVE_HERE_CURRENT, opened.here)
        assertTrue(!opened.canChoose)
        assertEquals(Msg.MOVE_SUBMIT, opened.submit)
        assertEquals(Answer.Done, b.ask(Call.MoveUp(id)))
        val atRoot = w.until { u -> (u as Update.Move).shown?.takeIf { it.trail.size == 1 } }
        assertEquals(listOf("trips" to null), atRoot.places.map { it.nodeId to it.blocked })
        assertTrue(atRoot.canChoose)
        val sent = b.ask(Call.MoveHere(id)) as Answer.MoveSent
        assertEquals(Msg.MOVE_MOVED, sent.done!!.msg)
        assertTrue(sent.undoable, "the answer did not say the undo is offered")
        assertEquals(Answer.Said(null), b.ask(Call.MoveUndo(id)))
        assertEquals(listOf("rome->null", "rome->trips"), played.moves)
        w.until { u -> (u as Update.Move).shown?.takeIf { it.undo == null } }
        assertEquals(Answer.Done, b.ask(Call.MoveGo(id, "trips")))

        // the copy: a Pot by its open model, a folder with what is under it; no room said with its size and nothing sent
        val copyPot = (b.ask(Call.MoveOpen(rome, true, "deleted")) as Answer.MoveOpened).move
        b.watching(Watched.Move(copyPot)).until { u -> (u as Update.Move).shown?.takeIf { it.loaded } }
        val copied = b.ask(Call.MoveHere(copyPot)) as Answer.MoveSent
        assertEquals(Msg.COPY_DONE, copied.done!!.msg)
        assertTrue(!copied.undoable)
        val trips = NodeAt("s", "trips", "FOLDER", "Trips")
        played.room = 4096
        val full = (b.ask(Call.MoveOpen(trips, true, "deleted")) as Answer.MoveOpened).move
        // the copy's losses and its storage check arrive each on its own: wait for both
        val noRoom = b.watching(Watched.Move(full)).until { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.noRoom != null && it.losses.size > 1 } }
        assertEquals(Msg.COPY_NO_ROOM, noRoom.noRoom!!.msg)
        assertEquals(listOf(Msg.COPY_LOSS_ALWAYS, Msg.COPY_LOSS_FOLDER_COVER), noRoom.losses.map { it.msg })
        assertTrue(!noRoom.canChoose)
        assertEquals(null, (b.ask(Call.MoveHere(full)) as Answer.MoveSent).done)
        assertEquals(listOf("open:rome->trips"), played.copies)

        // closed: refused, watched as none
        assertEquals(Answer.Done, b.ask(Call.MoveClose(full)))
        assertEquals(Update.Move(full, null), b.watching(Watched.Move(full)).next())
        listOf(Call.MoveGo(full, null), Call.MoveUp(full), Call.MoveHere(full), Call.MoveConfirm(full), Call.MoveCancelConfirm(full), Call.MoveUndo(full), Call.MoveClose(full))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MOVE), b.ask(it), "$it") }

        // a folder's download into the file the host named: written, what it left out said; a failed one leaves nothing
        val folder = (b.ask(Call.HeaderOpen(trips, AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        assertEquals(me.raddatz.veyq.mobile.export.NodeExport.fileName("Trips"), (b.watching(Watched.Header(folder)).next() as Update.Header).shown!!.archiveName)
        val file = java.io.File.createTempFile("veyq-download", ".zip")
        val done = b.ask(Call.HeaderDownload(folder, file.path, "deleted")) as Answer.Downloaded
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.DOWNLOAD_DONE, "Trips"), done.notice)
        assertTrue(done.written)
        assertEquals(Msg.DOWNLOAD_LEFT_OUT, done.leftOut!!.msg)
        assertEquals("PK-zip", file.readText())
        val broken = (b.ask(Call.HeaderOpen(NodeAt("s", "b", "FOLDER", "Broken"), AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        val failed = b.ask(Call.HeaderDownload(broken, file.path, "deleted")) as Answer.Downloaded
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.DOWNLOAD_FAILED, "Broken"), failed.notice)
        assertTrue(!failed.written)
        assertTrue(!file.exists(), "a download that did not finish left its file")

        // every sheet closes with its tenant
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MOVE), b.ask(Call.MoveUndo(id)), "a sheet outlived its tenant")
        b.shutdown()
    }

    /** A share sheet's door the test plays: a server's links, every write recorded. */
    private class PlayedShare : me.raddatz.veyq.mobile.navigation.ShareSheet.Door {
        val links = java.util.concurrent.CopyOnWriteArrayList<me.raddatz.veyq.mobile.session.ShareLinks.Link>()
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var passwordSent: String? = null
        @Volatile var rekeys = 0
        private var next = 0
        fun link(id: String, enabled: Boolean, at: String) = me.raddatz.veyq.mobile.session.ShareLinks.Link(
            id, "READ", enabled, true, at, null, "", false, false, "http://t/s/$id#k=key-$id", false, false, !enabled,
        )
        override val rules = me.raddatz.veyq.mobile.session.ShareLinks.Rules.UNKNOWN
        override suspend fun list() = links.sortedByDescending { it.createdAt }
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?): me.raddatz.veyq.mobile.session.ShareLinks.Minted {
            sent += "mint:$role:$label:${expiresAt != null}"
            passwordSent = password
            val id = "new${++next}"
            links += link(id, true, "2026-12-0$next")
            return me.raddatz.veyq.mobile.session.ShareLinks.Minted(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, "http://t/s/$id#k=key-$id")
        }
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?) =
            me.raddatz.veyq.mobile.depot.DropBoxes.Created(me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.FAILED).also { sent += "dropbox:$name" }
        override suspend fun edit(shareId: String, edit: me.raddatz.veyq.mobile.session.ShareLinkChanges.Edit): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
            sent += "edit:$shareId:${edit.label}"
            return me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE)
        }
        override suspend fun renew(shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
            sent += "renew:$shareId"
            links.removeIf { it.shareId == shareId }
            val id = "renewed${++next}"
            links += link(id, true, "2026-12-1$next")
            return me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE, "http://t/s/$id#k=key-$id")
        }
        override suspend fun switchOff(shareId: String): me.raddatz.veyq.mobile.session.ShareLinks.Closed {
            sent += "off:$shareId"
            turn(shareId, false)
            return me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED_REKEY_PENDING
        }
        override suspend fun reactivate(shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
            sent += "on:$shareId"
            turn(shareId, true)
            return me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE)
        }
        override suspend fun delete(shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
            sent += "delete:$shareId"
            links.removeIf { it.shareId == shareId }
            return me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE)
        }
        override fun rekeyed() { rekeys++ }
        override fun changed() {}
        private fun turn(id: String, on: Boolean) {
            val i = links.indexOfFirst { it.shareId == id }
            links[i] = link(id, on, links[i].createdAt)
        }
    }

    @Test
    fun aLinkIsMintedEditedRenewedSwitchedOffAndOnAndDeletedThroughCalls() = runBlocking {
        val played = PlayedShare()
        played.links += played.link("a", true, "2026-11-01")
        val b = Bridge {
            val hidden = Hidden({}, emptySet())
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { _ ->
                Tenant(
                    shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                    share = { node -> me.raddatz.veyq.mobile.navigation.ShareSheet(node, played, { 1_700_000_000_000L }) },
                )
            }
        }
        val photos = NodeAt("s", "f", "FOLDER", "Photos")
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.ShareOpen(photos)))
        b.signedIn()
        val id = (b.ask(Call.ShareOpen(photos)) as Answer.ShareOpened).share
        val w = b.watching(Watched.Share(id))
        val listed = w.until { u -> (u as Update.Share).shown?.takeIf { it.links != null } }
        assertEquals(Msg.SHARE_FOLDER_TITLE, listed.title)
        assertEquals(listOf("a"), listed.links!!.map { it.shareId })
        assertEquals("http://t/s/a#k=key-a", listed.links!!.single().address)

        // mint: the form's changes one call each, the password typed going in and never out, the address shown once
        assertEquals(Answer.Done, b.ask(Call.ShareAdd(id)))
        val form = w.until { u -> (u as Update.Share).shown?.form }
        assertEquals(listOf("READ", "READ_WRITE", "WRITE_ONLY"), form.roles.map { it.role })
        assertEquals(Answer.Done, b.ask(Call.ShareRole(id, "READ_WRITE")))
        assertEquals(Answer.Done, b.ask(Call.ShareLabel(id, "Family")))
        assertEquals(Answer.Done, b.ask(Call.ShareLockOn(id, true)))
        assertEquals(Answer.Done, b.ask(Call.SharePassword(id, "short")))
        val short = w.until { u -> (u as Update.Share).shown?.form?.takeIf { it.lock.tooShort } }
        assertTrue(!short.canSave)
        assertEquals(Answer.Done, b.ask(Call.SharePassword(id, "long enough now")))
        assertEquals(Answer.Done, b.ask(Call.ShareEndOn(id, true)))
        assertEquals(Answer.Done, b.ask(Call.ShareEndDay(id, form.end.today + 2)))
        w.until { u -> (u as Update.Share).shown?.form?.takeIf { it.canSave && it.end.day == form.end.today + 2 } }
        assertEquals(Answer.Said(null), b.ask(Call.ShareSave(id)))
        val minted = w.until { u -> (u as Update.Share).shown?.takeIf { it.freshAddress != null && it.links?.size == 2 } }
        assertEquals("http://t/s/new1#k=key-new1", minted.freshAddress)
        assertNull(minted.form)
        assertEquals("mint:READ_WRITE:Family:true", played.sent.single())
        assertEquals("long enough now", played.passwordSent)
        assertEquals(Answer.Done, b.ask(Call.ShareFreshSeen(id)))
        w.until { u -> (u as Update.Share).shown?.takeIf { it.freshAddress == null } }

        // edit in place
        assertEquals(Answer.Done, b.ask(Call.ShareEdit(id, "a")))
        w.until { u -> (u as Update.Share).shown?.takeIf { it.editing == "a" && it.submit == Msg.SHARE_SAVE } }
        assertEquals(Answer.Done, b.ask(Call.ShareLabel(id, "Renamed")))
        w.until { u -> (u as Update.Share).shown?.form?.takeIf { it.label == "Renamed" } }
        assertEquals(Answer.Said(null), b.ask(Call.ShareSave(id)))
        assertEquals("edit:a:Renamed", played.sent.last())

        // renew, asked first: the new address shown once, the tenant told it re-keyed
        assertEquals(Answer.Done, b.ask(Call.ShareAskRenew(id, "a")))
        w.until { u -> (u as Update.Share).shown?.takeIf { it.renewing == "a" } }
        assertEquals(Answer.Said(null), b.ask(Call.ShareRenew(id)))
        val renewed = w.until { u -> (u as Update.Share).shown?.takeIf { it.freshAddress != null && it.renewing == null } }
        assertTrue(renewed.freshAddress!!.startsWith("http://t/s/renewed"))
        assertEquals(1, played.rekeys)
        val fresh = renewed.links!!.first { it.shareId.startsWith("renewed") }.shareId

        // off, which says its re-key is still due; on again; off and deleted, asked first
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.SHARE_CLOSED_REKEYING)), b.ask(Call.ShareSwitchOff(id, fresh)))
        val off = w.until { u -> (u as Update.Share).shown?.links?.firstOrNull { it.shareId == fresh }?.takeIf { !it.canCopy } }
        assertTrue(off.canReactivate && off.canDelete)
        assertNull(off.address, "a link switched off is not copied")
        assertEquals(Answer.Said(null), b.ask(Call.ShareReactivate(id, fresh)))
        w.until { u -> (u as Update.Share).shown?.links?.firstOrNull { it.shareId == fresh }?.takeIf { it.canCopy } }
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.SHARE_CLOSED_REKEYING)), b.ask(Call.ShareSwitchOff(id, fresh)))
        assertEquals(Answer.Done, b.ask(Call.ShareAskDelete(id, fresh)))
        w.until { u -> (u as Update.Share).shown?.takeIf { it.deleting == fresh } }
        assertEquals(Answer.Said(null), b.ask(Call.ShareDelete(id)))
        w.until { u -> (u as Update.Share).shown?.takeIf { s -> s.links?.none { it.shareId == fresh } == true } }
        assertEquals(listOf("renew:a", "off:$fresh", "on:$fresh", "off:$fresh", "delete:$fresh"), played.sent.drop(2))
        assertEquals(4, played.rekeys, "each write that may close a link tells the tenant: renew, two offs, the delete")

        // closed: refused, watched as none; every sheet closes with its tenant
        assertEquals(Answer.Done, b.ask(Call.ShareClose(id)))
        assertEquals(Update.Share(id, null), b.watching(Watched.Share(id)).next())
        listOf(Call.ShareAdd(id), Call.ShareSave(id), Call.ShareRenew(id), Call.ShareSwitchOff(id, "x"), Call.ShareClose(id))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SHARE), b.ask(it), "$it") }
        val again = (b.ask(Call.ShareOpen(photos)) as Answer.ShareOpened).share
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SHARE), b.ask(Call.ShareAdd(again)), "a sheet outlived its tenant")
        b.shutdown()
    }

    @Test
    fun aReportIsFilledSentOnceAndItsPrivacyLinkIsTheCoresThroughCalls() = runBlocking {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val door = object : me.raddatz.veyq.mobile.navigation.ReportSheet.Door {
            override val origin = "http://veyq.localhost:8080/"
            override suspend fun send(scope: me.raddatz.veyq.mobile.session.NodeReport.Scope, category: String, reason: String, contact: String): me.raddatz.veyq.mobile.session.NodeReport.Sent {
                sent += "${scope.name}:$category:$reason:$contact"
                return me.raddatz.veyq.mobile.session.NodeReport.Sent(me.raddatz.veyq.mobile.session.NodeReport.Outcome.SENT, "abcd1234", "2026-11-11T10:00:00Z")
            }
        }
        val b = Bridge {
            val hidden = Hidden({}, emptySet())
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { _ ->
                Tenant(
                    shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                    report = { node -> me.raddatz.veyq.mobile.navigation.ReportSheet(node, door) },
                )
            }
        }
        val photos = NodeAt("s", "f", "FOLDER", "Photos")
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.ReportOpen(photos)))
        b.signedIn()
        val id = (b.ask(Call.ReportOpen(photos)) as Answer.ReportOpened).report
        val w = b.watching(Watched.Report(id))
        val fresh = (w.next() as Update.Report).shown!!
        assertEquals("http://veyq.localhost:8080/privacy#reporter-contact", fresh.privacyUrl)
        assertEquals(listOf("CHILD_SEXUAL_ABUSE", "TERRORIST_CONTENT", "THREAT_TO_LIFE", "ILLEGAL_CONTENT", "TERMS_BREACH"), fresh.categories.map { it.id })
        assertTrue(fresh.whole && !fresh.canSend && !fresh.sent)
        assertEquals(me.raddatz.veyq.mobile.session.NodeReport.REASON_MAX, fresh.reasonMax)

        assertEquals(Answer.Done, b.ask(Call.ReportCategory(id, "CHILD_SEXUAL_ABUSE")))
        w.until { u -> (u as Update.Report).shown?.takeIf { it.note == Msg.REPORT_CSAM_NOTE } }
        assertEquals(Answer.Done, b.ask(Call.ReportCategory(id, "ILLEGAL_CONTENT")))
        assertEquals(Answer.Done, b.ask(Call.ReportWhole(id, false)))
        assertEquals(Answer.Done, b.ask(Call.ReportReason(id, "why")))
        assertEquals(Answer.Done, b.ask(Call.ReportContact(id, "me@example.org")))
        // not yet: good faith is not confirmed, and a send then sends nothing
        assertEquals(Answer.Said(null), b.ask(Call.ReportSend(id)))
        assertTrue(sent.isEmpty())
        assertEquals(Answer.Done, b.ask(Call.ReportGoodFaith(id, true)))
        val ready = w.until { u -> (u as Update.Report).shown?.takeIf { it.canSend } }
        assertEquals(Msg.REPORT_SCOPE_NAME_HINT, ready.scopeHint)
        assertEquals(Answer.Said(null), b.ask(Call.ReportSend(id)))
        val receipt = w.until { u -> (u as Update.Report).shown?.takeIf { it.sent } }
        assertEquals("abcd1234", receipt.reference)
        assertEquals("2026-11-11T10:00:00Z", receipt.receivedAt)
        assertEquals(listOf("NAME:ILLEGAL_CONTENT:why:me@example.org"), sent.toList())
        // sent once: a second send sends nothing
        b.ask(Call.ReportSend(id))
        assertEquals(1, sent.size)

        assertEquals(Answer.Done, b.ask(Call.ReportClose(id)))
        assertEquals(Update.Report(id, null), b.watching(Watched.Report(id)).next())
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_REPORT), b.ask(Call.ReportSend(id)))
        val again = (b.ask(Call.ReportOpen(photos)) as Answer.ReportOpened).report
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_REPORT), b.ask(Call.ReportWhole(again, true)), "a sheet outlived its tenant")
        b.shutdown()
    }

    @Test
    fun aCoverIsPosedSetFromTheHostsFileFramedAnewAndRemovedThroughCalls() = runBlocking {
        val played = Played()
        var stored: me.raddatz.veyq.mobile.session.NodeCover.Crop? = null
        var picture: ByteArray? = null
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val b = Bridge {
            val hidden = Hidden({}, emptySet())
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles) { _ ->
                Tenant(
                    shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                    header = { node, facts, _ ->
                        me.raddatz.veyq.mobile.navigation.NodeHeaderModel(node, facts, object : me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Door by played {
                            override suspend fun cover(ref: NodeAt) = stored
                            override suspend fun coverPicture(ref: NodeAt) = picture
                            override suspend fun setCover(ref: NodeAt, jpeg: ByteArray, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): me.raddatz.veyq.mobile.session.NodeCover.Outcome {
                                writes += "set ${jpeg.toList()}"; stored = crop; picture = jpeg
                                return me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
                            }
                            override suspend fun recropCover(ref: NodeAt, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): me.raddatz.veyq.mobile.session.NodeCover.Outcome {
                                writes += "recrop"; stored = crop
                                return me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
                            }
                            override suspend fun removeCover(ref: NodeAt): me.raddatz.veyq.mobile.session.NodeCover.Outcome {
                                writes += "remove"; stored = null; picture = null
                                return me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
                            }
                        })
                    },
                )
            }
        }
        // the frame's math is the core's: a fresh picture centred, a pan held inside, a crop read back as its pose
        val posed = b.ask(Call.CoverPose(400.0, 300.0, 160.0, 90.0, null, null, 0.0, 0.0, 1.0)) as Answer.CoverPosed
        assertEquals(me.raddatz.veyq.mobile.session.CoverFrame(400.0, 300.0, 160.0, 90.0).centred(), posed.pose)
        // drawn so it covers the frame: 400 × 300 under 160 × 90 at zoom 1 is 160 × 120
        assertEquals(160.0, posed.width, 1e-9)
        assertEquals(120.0, posed.height, 1e-9)
        val far = b.ask(Call.CoverPose(400.0, 300.0, 160.0, 90.0, posed.pose, null, 1000.0, 0.0, 1.0)) as Answer.CoverPosed
        assertEquals(0.0, far.crop.x, "panned past the edge: held at it")
        val zoomed = b.ask(Call.CoverPose(400.0, 300.0, 160.0, 90.0, far.pose, null, 0.0, 0.0, 2.0)) as Answer.CoverPosed
        assertEquals(0.5, zoomed.crop.width, 1e-9)
        val left = me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.0, 0.5, 0.5)
        val reopened = b.ask(Call.CoverPose(400.0, 300.0, 160.0, 90.0, null, left, 0.0, 0.0, 1.0)) as Answer.CoverPosed
        assertEquals(left.width, reopened.crop.width, 1e-9)
        assertEquals(Answer.CoverFit(2048, 1536, 0.85, 85), b.ask(Call.CoverFit(4000, 3000)))

        b.signedIn()
        val folder = NodeAt("s", "f", "FOLDER", "Trip")
        val id = (b.ask(Call.HeaderOpen(folder, AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        val w = b.watching(Watched.Header(id))
        val none = w.until { u -> (u as Update.Header).shown?.plan }
        assertEquals(Msg.COVER_SET, (none.buttons + none.menu + none.last).first { it.id == "cover" }.word)
        assertEquals(Answer.Refused(Answer.Why.NO_COVER), b.ask(Call.HeaderCoverPicture(id)))

        // set from the host's file, its bytes read by the core
        val file = java.io.File.createTempFile("cover", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverSave(id, file.path, left)))
        assertEquals("set [1, 2, 3]", writes.single())
        val set = w.until { u -> (u as Update.Header).shown?.takeIf { it.cover != null } }
        assertEquals(left, set.cover)
        assertEquals(Msg.COVER_CHANGE, set.plan!!.let { it.buttons + it.menu + it.last }.first { it.id == "cover" }.word)
        val (pictureAnswer, bytes) = b.askWithPayload(Call.HeaderCoverPicture(id))
        assertIs<Answer.Bytes>(pictureAnswer)
        assertEquals(listOf<Byte>(1, 2, 3), bytes!!.toList())
        // a path that does not read: nothing written, said
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.COVER_FAILED)), b.ask(Call.HeaderCoverSave(id, file.path + ".gone", left)))
        // framed anew, its picture kept; then removed
        assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverSave(id, null, me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL)))
        w.until { u -> (u as Update.Header).shown?.takeIf { it.cover == me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL } }
        assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverRemove(id)))
        w.until { u -> (u as Update.Header).shown?.takeIf { it.cover == null } }
        assertEquals(listOf("set [1, 2, 3]", "recrop", "remove"), writes.toList())
        b.shutdown()
    }

    /**
     * A file's page's door the test plays: each file by its node's name, its content in frames of the suite's size; the
     * frames of a file named "Held…" wait at the second frame until [let] completes.
     */
    private class PlayedFiles(val files: Map<String, Pair<ByteArray, String>>) : me.raddatz.veyq.mobile.depot.FileModel.Door {
        val let = kotlinx.coroutines.CompletableDeferred<Unit>()
        private fun frames(name: String) = files.getValue(name).first.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
        override suspend fun file(node: NodeAt): me.raddatz.veyq.mobile.depot.DepotFiles.File {
            val (content, type) = files.getValue(node.name)
            val chunks = frames(node.name).size
            return me.raddatz.veyq.mobile.depot.DepotFiles.File(
                node.scopeId, node.nodeId, "blob-${node.name}", chunks, 0, null, content.size.toLong(), type, node.name, 1_700_000_000_000, null,
            )
        }
        override fun inIdentityFolder(node: NodeAt) = false
        override suspend fun read(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
            val name = file.uploadedName!!
            val frames = frames(name)
            for (i in first until minOf(until, frames.size)) {
                if (i == 1 && name.startsWith("Held")) let.await()
                sink(frames[i])
            }
        }
        override fun reader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File) = RangeReader(file.size ?: 0, file.chunkCount) { a, b, sink -> read(file, a, b, sink) }
        override suspend fun uploader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File): me.raddatz.veyq.mobile.depot.Uploaders.Uploader? = null
        override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: me.raddatz.veyq.mobile.blob.UploadSource, expectedBlobId: String?) =
            error("no replace here")
    }

    @Test
    fun aFileIsOpenedShownSavedWithItsProgressAndCancelledThroughCalls() = runBlocking {
        val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj<<>>endobj\n%%EOF\n".encodeToByteArray()
        val text = ByteArray(me.raddatz.veyq.mobile.depot.FileView.TEXT_CAP + V1.FRAME_PLAINTEXT) { ('a' + it % 26).code.toByte() }
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(3 * V1.FRAME_PLAINTEXT) { (it % 241).toByte() }
        val played = PlayedFiles(mapOf(
            "a.png" to (png to "image/png"), "notes.txt" to (text to "text/plain"), "a.pdf" to (pdf to "application/pdf"),
            "a.zip" to (zip to "application/zip"), "Held.zip" to (zip to "application/zip"), "empty.txt" to (ByteArray(0) to "text/plain"),
        ))
        val b = Bridge {
            val hidden = Hidden({}, emptySet())
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles) { _ ->
                Tenant(
                    shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {},
                    header = { node, facts, _ -> me.raddatz.veyq.mobile.navigation.NodeHeaderModel(node, facts, Played()) },
                    // a node's download as the shell makes it: the kind decides (a file itself, any other node its ZIP)
                    download = { node, _, sink ->
                        me.raddatz.veyq.mobile.navigation.ShellModel.downloadAs(
                            node.kind, own = { out -> out(zip); true }, zip = { out -> out("PK-archive".encodeToByteArray()); emptyList() }, sink = sink,
                        )
                    },
                    file = { node -> me.raddatz.veyq.mobile.depot.FileModel(node, played, shares = false) },
                )
            }
        }
        fun at(name: String) = NodeAt("s", "n-$name", "FILE", name)
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.FileOpen(at("a.png"))))
        b.signedIn()
        suspend fun open(name: String): Pair<Long, FileShown> {
            val id = (b.ask(Call.FileOpen(at(name))) as Answer.FileOpened).file
            val shown = b.watching(Watched.File(id)).until { u -> (u as Update.File).shown?.takeIf { it.view != FileShown.View.LOADING && it.headerFacts != null } }
            return id to shown
        }

        // an image: its bytes beside the answer, the core's type and edge; the header's facts and the copy's name the core's
        val (image, imageShown) = open("a.png")
        assertEquals(FileShown.View.IMAGE, imageShown.view)
        assertEquals("image/png", imageShown.mediaType)
        assertEquals(me.raddatz.veyq.mobile.depot.FileView.IMAGE_EDGE, imageShown.edge)
        assertEquals(AppKinds.Facts(canWrite = true, hasContent = true), imageShown.headerFacts)
        assertEquals("a.png", imageShown.downloadName)
        assertTrue(imageShown.facts.isNotEmpty())
        val (bytesAnswer, bytes) = b.askWithPayload(Call.FileBytes(image))
        assertEquals(Answer.Bytes("image/png"), bytesAnswer)
        assertEquals(png.toList(), bytes!!.toList(), "the bytes are not the file's")

        // a text in the state, cut at its cap and said; it has no bytes beside it
        val (notes, notesShown) = open("notes.txt")
        assertEquals(FileShown.View.TEXT, notesShown.view)
        assertEquals(me.raddatz.veyq.mobile.depot.FileView.TEXT_CAP, notesShown.text!!.length)
        assertEquals(Msg.FILE_TEXT_TRUNCATED, notesShown.truncated!!.msg)
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.FileBytes(notes)))

        // a PDF's bytes and its pages' bound; a ZIP and an empty file say why nothing shows
        val (pdfId, pdfShown) = open("a.pdf")
        assertEquals(FileShown.View.PDF, pdfShown.view)
        val (pdfAnswer, pdfBytes) = b.askWithPayload(Call.FileBytes(pdfId))
        assertEquals(Answer.Bytes("application/pdf"), pdfAnswer)
        assertEquals(pdf.toList(), pdfBytes!!.toList())
        assertEquals(Answer.ReceiptPages(30, null), b.ask(Call.FilePages(30)))
        assertEquals(Answer.ReceiptPages(1000, me.raddatz.veyq.mobile.words.Notice(Msg.FILE_PAGES_CUT, 1000, 1200)), b.ask(Call.FilePages(1200)))
        val (zipId, zipShown) = open("a.zip")
        assertEquals(FileShown.View.MESSAGE to Msg.FILE_UNSUPPORTED, zipShown.view to zipShown.words)
        assertEquals(FileShown.View.MESSAGE to Msg.FILE_EMPTY, open("empty.txt").second.let { it.view to it.words })

        // Save a copy: answered with its id once it runs (watched at once), the file itself written frame by frame
        val path = java.io.File.createTempFile("veyq-save", ".zip").apply { delete() }
        val saving = (b.ask(Call.FileSave(zipId, path.path)) as Answer.Saving).save
        assertTrue((b.watching(Watched.Save(saving)).next() as Update.Save).shown != null, "the save answered before it ran")
        val saved = b.watching(Watched.Save(saving)).until { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
        assertEquals(me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, saved.end)
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.DOWNLOAD_DONE, "a.zip"), saved.notice)
        assertEquals(zip.size.toLong(), saved.written)
        assertEquals(zip.toList(), path.readBytes().toList(), "the copy is not the file")
        assertEquals(Answer.Refused(Answer.Why.SAVE_ENDED), b.ask(Call.SaveCancel(saving)))

        // a save stood midway and cancelled: its progress said, nothing left at its path once the answer came
        val (held, _) = open("Held.zip")
        val heldPath = java.io.File.createTempFile("veyq-held", ".zip").apply { delete() }
        val heldSave = (b.ask(Call.FileSave(held, heldPath.path)) as Answer.Saving).save
        val midway = b.watching(Watched.Save(heldSave)).until { u -> (u as Update.Save).shown?.takeIf { it.written > 0 } }
        assertEquals(Msg.DOWNLOAD_SAVING, midway.notice.msg)
        assertTrue(midway.end == null && midway.fraction > 0.0 && midway.fraction < 1.0, "${midway.fraction}")
        assertEquals(Answer.Done, b.ask(Call.SaveCancel(heldSave)))
        assertTrue(!heldPath.exists(), "a cancelled save left its file")
        val cancelled = (b.watching(Watched.Save(heldSave)).next() as Update.Save).shown!!
        assertEquals(me.raddatz.veyq.mobile.depot.FileSave.End.CANCELLED, cancelled.end)
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.DOWNLOAD_CANCELLED, "Held.zip"), cancelled.notice)
        // a path that does not open: failed, said
        val nowhere = (b.ask(Call.FileSave(zipId, "/nonexistent-dir/veyq/a.zip")) as Answer.Saving).save
        assertEquals(Msg.DOWNLOAD_FAILED, b.watching(Watched.Save(nowhere)).until { u -> (u as Update.Save).shown?.takeIf { it.end != null } }.notice.msg)

        // the header's download of a FILE is the file itself, a folder's its ZIP
        val header = (b.ask(Call.HeaderOpen(at("a.zip"), AppKinds.Facts(canWrite = true, hasContent = true), 2)) as Answer.HeaderOpened).header
        val headerPath = java.io.File.createTempFile("veyq-header", ".zip")
        assertTrue((b.ask(Call.HeaderDownload(header, headerPath.path, "deleted")) as Answer.Downloaded).written)
        assertEquals(zip.toList(), headerPath.readBytes().toList(), "a file's header download wrote its ZIP")
        val folder = (b.ask(Call.HeaderOpen(NodeAt("s", "f", "FOLDER", "Trip"), AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        assertTrue((b.ask(Call.HeaderDownload(folder, headerPath.path, "deleted")) as Answer.Downloaded).written)
        assertEquals("PK-archive", headerPath.readText())
        headerPath.delete()
        path.delete()

        // closed: refused, watched as none; every page and save goes with its tenant
        assertEquals(Answer.Done, b.ask(Call.FileClose(notes)))
        assertEquals(Update.File(notes, null), b.watching(Watched.File(notes)).next())
        listOf(Call.FileBytes(notes), Call.FileSave(notes, "/tmp/x"), Call.FileClose(notes))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_FILE), b.ask(it), "$it") }
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_FILE), b.ask(Call.FileBytes(image)), "a page outlived its tenant")
        assertEquals(Update.Save(saving, null), b.watching(Watched.Save(saving)).next(), "a save outlived its tenant")
        played.let.complete(Unit)
        b.shutdown()
    }

    /**
     * A Pot the test plays (`PotModel.Opened`): Anna and Ben in euros, a dinner Anna paid (so Ben owes her) and a taxi
     * deleted whose receipts stay until [purgeAfter]; each poll, refresh and write recorded. A poll answers "moved" with a
     * lunch added, or — once [hold] is set — waits until it is cancelled, which [pollCancelled] records.
     */
    private class PlayedPot(val purgeAfter: Long, val receipts: Map<String, ByteArray> = emptyMap()) : me.raddatz.veyq.mobile.expenses.PotModel.Opened {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val ops = java.util.concurrent.CopyOnWriteArrayList<me.raddatz.veyq.mobile.expenses.PotModel.Op>()
        @Volatile var outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN
        @Volatile var hold = false
        val pollCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        @Volatile var lunch = false
        /** The settings' case: whether money fixes the currency, Cleo (settled) is among the people, an own category
         *  "Snacks" is the Pot's, and who the space's members are. */
        @Volatile var fixed = true
        @Volatile var cleo = false
        @Volatile var own = false
        @Volatile var members: List<me.raddatz.veyq.mobile.session.Person>? = null
        override val session: me.raddatz.veyq.mobile.expenses.PotSession? = null
        override fun view() = potView(purgeAfter, lunch, receipts.keys, fixed, cleo, own, members)
        override suspend fun poll(): Boolean {
            polls.incrementAndGet()
            if (hold) {
                try { kotlinx.coroutines.awaitCancellation() } finally { pollCancelled.complete(Unit) }
            }
            lunch = true
            return true
        }
        override suspend fun refresh() { refreshes.incrementAndGet() }
        override suspend fun rekeyed() {}
        private val minted = java.util.concurrent.atomic.AtomicInteger()
        /** "new" first (a new entry's record), then "new-1", "new-2", … (each receipt picked). */
        override fun newId() = minted.getAndIncrement().let { if (it == 0) "new" else "new-$it" }
        override suspend fun write(op: me.raddatz.veyq.mobile.expenses.PotModel.Op) = outcome.also { ops += op }

        /** The editor's saves (record, form, revision based on) and deletes, as they reached the Pot; a save waits for [saveGate]. */
        val saves = java.util.concurrent.CopyOnWriteArrayList<Triple<String, me.raddatz.veyq.mobile.expenses.EntryForm, Long?>>()
        val deletes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        val rates = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** The receipts each save was handed to upload, and the names the next save says it left out for want of room. */
        val uploads = java.util.concurrent.CopyOnWriteArrayList<List<me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt>>()
        @Volatile var skipped: List<String> = emptyList()
        override suspend fun openReceipt(receiptId: String): ByteArray? = receipts[receiptId]
        override suspend fun saveEntry(
            entryId: String, form: me.raddatz.veyq.mobile.expenses.EntryForm, basedOn: Long?,
            pending: List<me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt>, own: Set<String>,
        ): me.raddatz.veyq.mobile.expenses.EntryEditor.Written {
            saveGate?.await()
            saves += Triple(entryId, form, basedOn)
            uploads += pending
            return me.raddatz.veyq.mobile.expenses.EntryEditor.Written(outcome, skipped, pending.map { it.id })
        }
        override suspend fun deleteEntry(entryId: String, basedOn: Long) = outcome.also { deletes += entryId to basedOn }
        override suspend fun rateFor(currency: String, day: String) =
            me.raddatz.veyq.mobile.expenses.ExchangeRates.Filled("1.082954", "2026-11-11").also { rates += currency to day }
    }

    private fun bridgeWithPot(pot: PlayedPot, clock: java.util.concurrent.atomic.AtomicLong, opened: kotlinx.coroutines.CompletableDeferred<Unit>, files: DeviceFiles = JvmFiles) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, files) { _ ->
            Tenant(shell = null, pot = { me.raddatz.veyq.mobile.expenses.PotModel({ clock.get() }) { opened.await(); pot } }, me = "u1", pictures = pictures, loadPictures = {})
        }
    }

    @Test
    fun aPotIsPolledWhileWatchedRefreshedSettledAndRestoredThroughCalls() = runBlocking {
        val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z
        val clock = java.util.concurrent.atomic.AtomicLong(noon)
        val pot = PlayedPot(purgeAfter = noon + 86_400_000)
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        val b = bridgeWithPot(pot, clock, opened)
        b.signedIn()
        val ref = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip")
        val id = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
        val updates = b.watching(Watched.Pot(id))

        // the poll watched while the Pot still opens: it waits for the read, then ticks at once (it came back after a while)
        val live = b.watching(Watched.PotLive(id))
        kotlinx.coroutines.delay(200.milliseconds)
        assertEquals(0, pot.polls.get(), "a Pot still opening was polled")
        clock.addAndGet(10_000)
        opened.complete(Unit)
        val entries = updates.untilJson { u ->
            (u["state"] as? kotlinx.serialization.json.JsonObject)?.get("view")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("entries")?.let { it as kotlinx.serialization.json.JsonArray }?.takeIf { it.size == 2 }
        }
        assertEquals(1, pot.polls.get())
        assertTrue(entries.toString().contains("Lunch"), "the tick's change did not arrive on the Pot's watch")
        assertNull(live.pending(), "the poll's watch delivered an update of its own")
        b.unwatch(live.id)

        // a poll watched again ticks at once; dropped, its tick is cancelled: no poll outlives its watch
        pot.hold = true
        clock.addAndGet(10_000)
        val again = b.watching(Watched.PotLive(id))
        kotlinx.coroutines.withTimeout(5_000) { while (pot.polls.get() < 2) kotlinx.coroutines.delay(10) }
        b.unwatch(again.id)
        kotlinx.coroutines.withTimeout(5_000) { pot.pollCancelled.await() }

        // Refresh reads again
        assertEquals(Answer.Done, b.ask(Call.PotRefresh(id)))
        assertEquals(1, pot.refreshes.get())

        // a settle-up row recorded on the core's today; nothing to record is a refusal; a stale one says why
        val row = me.raddatz.veyq.mobile.expenses.PotView.SettleRow("Ben", "Anna", 1500, BEN, ANNA)
        assertEquals(Answer.Said(null), b.ask(Call.PotRecord(id, listOf(row))))
        val recorded = assertIs<me.raddatz.veyq.mobile.expenses.PotModel.Op.Record>(pot.ops.single())
        assertEquals("2026-11-12" to listOf(BEN to ANNA), recorded.date to recorded.rows.map { it.fromId to it.toId })
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.PEOPLE_FAILED)), b.ask(Call.PotRecord(id, emptyList())))
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.STALE
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.ENTRY_STALE)), b.ask(Call.PotRecord(id, listOf(row))))
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN

        // the deleted taxi: its history and its restore inside its window; outside it, nothing sent and said why
        val history = assertIs<Answer.EntryHistory>(b.ask(Call.PotHistory(id, TAXI)))
        assertEquals(listOf(2L, 1L), history.rows.map { it.at / 1000 }.let { listOf(2L, 1L) })
        assertEquals(2, history.rows.size)
        assertEquals(Msg.HISTORY_DELETED, (history.rows.first().line.single() as me.raddatz.veyq.mobile.words.Piece.Said).notice.msg)
        assertTrue(history.deleted && history.restorable)
        assertEquals(pot.purgeAfter, history.until)
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_ENTRY), b.ask(Call.PotHistory(id, "none")))
        val writes = pot.ops.size
        assertEquals(Answer.Said(null), b.ask(Call.PotRestore(id, TAXI)))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.Restore(TAXI, 2), pot.ops.last())
        clock.set(pot.purgeAfter + 1)
        assertEquals(false, (b.ask(Call.PotHistory(id, TAXI)) as Answer.EntryHistory).restorable)
        assertEquals(Answer.Said(me.raddatz.veyq.mobile.words.Notice(Msg.ENTRY_NOT_RESTORABLE)), b.ask(Call.PotRestore(id, TAXI)))
        assertEquals(writes + 1, pot.ops.size, "a restore outside its window was sent")

        // a closed Pot's poll ends with it
        pot.hold = false
        assertEquals(Answer.Done, b.ask(Call.PotClose(id)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(Call.PotRefresh(id)))
        b.shutdown()
    }

    @Test
    fun anEntryIsWrittenEditedAndDeletedThroughTheEditorsCalls() = runBlocking {
        val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z
        val pot = PlayedPot(purgeAfter = noon + 86_400_000)
        val opened = kotlinx.coroutines.CompletableDeferred(Unit)
        val b = bridgeWithPot(pot, java.util.concurrent.atomic.AtomicLong(noon), opened)
        b.signedIn()
        val ref = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip")
        val potId = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(potId)).untilJson { u -> (u["state"] as? kotlinx.serialization.json.JsonObject)?.get("view")?.takeIf { it is kotlinx.serialization.json.JsonObject } }
        fun change(editor: Long, c: me.raddatz.veyq.mobile.expenses.EditorChange) = Call.EditorChange(editor, c)
        suspend fun shownNow(editor: Long): EditorShown = b.watching(Watched.Editor(editor)).let { w -> (w.next() as Update.Editor).shown!!.also { b.unwatch(w.id) } }

        // a new entry: dated the core's today, paid by the first person, split equally; no refusal before a save is tried
        val id = (b.ask(Call.EditorOpen(potId, null, ",")) as Answer.EditorOpened).editor
        val w = b.watching(Watched.Editor(id))
        val first = (w.next() as Update.Editor).shown!!
        assertTrue(first.isNew && first.editable)
        assertEquals("2026-11-12" to "EUR", first.date to first.base)
        assertEquals(listOf(ANNA), first.payers.map { it.person })
        assertEquals(listOf("Anna", "Ben"), first.people.map { it.name })
        assertEquals(false, first.rest?.expanded)
        assertNull(first.refusal)

        // edits land in the order the host sent them, each at once: a field typed fast ends on its last text
        val typed = (1..400).map { "Museum".repeat(1 + it / 100).take(1 + it % 60) + it }
        for (t in typed) b.call(wire.encodeToString(Call.serializer(), change(id, me.raddatz.veyq.mobile.expenses.EditorChange.Title(t)))) { _, _ -> }
        assertEquals(typed.last(), shownNow(id).title, "an edit landed out of the order it was sent")

        // a save of a form that cannot be saved: not done, nothing written, the refusal at its field from then on
        assertEquals(Answer.EditorSaved(false, null), b.ask(Call.EditorSave(id)))
        assertTrue(pot.saves.isEmpty())
        val refused = shownNow(id).refusal!!
        assertEquals(Triple(Msg.PROBLEM_PAYERS, "payers", null), Triple(refused.words.msg, refused.at, refused.item))

        // two payers, an item split by percent, the rest by shares, another currency with its rate filled
        listOf(
            me.raddatz.veyq.mobile.expenses.EditorChange.Paid(0, "30,50"), me.raddatz.veyq.mobile.expenses.EditorChange.AddPayer,
            me.raddatz.veyq.mobile.expenses.EditorChange.Paid(1, "20"), me.raddatz.veyq.mobile.expenses.EditorChange.AddItem,
            me.raddatz.veyq.mobile.expenses.EditorChange.ItemLabel(0, "Tickets"), me.raddatz.veyq.mobile.expenses.EditorChange.ItemAmount(0, "20"),
            me.raddatz.veyq.mobile.expenses.EditorChange.Expand(0), me.raddatz.veyq.mobile.expenses.EditorChange.SplitMode(0, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT),
            me.raddatz.veyq.mobile.expenses.EditorChange.Share(0, ANNA, "70"),
            me.raddatz.veyq.mobile.expenses.EditorChange.Expand(null), me.raddatz.veyq.mobile.expenses.EditorChange.Share(null, ANNA, "2"),
            me.raddatz.veyq.mobile.expenses.EditorChange.Currency("CHF"),
        ).forEach { assertEquals(Answer.Done, b.ask(change(id, it))) }
        val filled = w.until { u -> (u as Update.Editor).shown?.takeIf { it.rateFill?.state == me.raddatz.veyq.mobile.expenses.EntryEditor.RateState.FILLED } }
        assertEquals("1,082954" to "2026-11-11", filled.rate to filled.rateFill?.day)
        assertEquals(listOf("CHF" to "2026-11-12"), pot.rates.toList())
        assertEquals(2, filled.payers.size)
        assertEquals(false to true, filled.canAddPayer to filled.canRemovePayer)
        val item = filled.items.single()
        assertEquals(me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT, item.split.mode)
        assertEquals(listOf("70", ""), item.split.people.map { it.value })
        assertEquals(Msg.ED_PERCENT_TOTAL, item.split.hint?.msg)
        assertEquals(listOf("2", "1"), filled.rest!!.people.map { it.value })
        assertEquals(5050L to "CHF", (filled.total as me.raddatz.veyq.mobile.words.Piece.Money).let { it.amount to it.currency })
        // the percent typed for Ben: the form saves, its preview in the base currency
        assertEquals(Answer.Done, b.ask(change(id, me.raddatz.veyq.mobile.expenses.EditorChange.Share(0, BEN, "30"))))
        assertEquals(2, shownNow(id).preview.size)

        // the save answers once the write ended, done: never before
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        pot.saveGate = gate
        val saving = async { b.ask(Call.EditorSave(id)) }
        kotlinx.coroutines.delay(300.milliseconds)
        assertTrue(!saving.isCompleted, "the save answered before its write ended")
        gate.complete(Unit)
        assertEquals(Answer.EditorSaved(true, null), saving.await())
        val (record, form, basedOn) = pot.saves.single()
        assertEquals(Triple("new", typed.last(), null as Long?), Triple(record, form.title, basedOn))
        assertEquals(listOf(ANNA to "30,50", BEN to "20"), form.payers.map { it.participant to it.amount })
        assertEquals("CHF" to "1,082954", form.currency to form.rate)
        pot.saveGate = null
        assertEquals(Answer.Done, b.ask(Call.EditorClose(id)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_EDITOR), b.ask(change(id, me.raddatz.veyq.mobile.expenses.EditorChange.Title("x"))))

        // an entry edited: its kind changed to a transfer
        val dinner = (b.ask(Call.EditorOpen(potId, DINNER, ".")) as Answer.EditorOpened).editor
        val d = shownNow(dinner)
        assertTrue(!d.isNew)
        assertEquals("Dinner" to "30.00", d.title to d.payers.single().amount)
        assertEquals(Answer.Done, b.ask(change(dinner, me.raddatz.veyq.mobile.expenses.EditorChange.Kind(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER))))
        val transfer = shownNow(dinner)
        assertEquals(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER to "30.00", transfer.kind to transfer.amount)
        assertNull(transfer.rest)
        // the delete: asked, cancelled (nothing written), asked and done on the revision the editor is based on
        assertEquals(Answer.EditorSaved(false, null), b.ask(Call.EditorDelete(dinner)))
        assertEquals(Answer.Done, b.ask(Call.EditorAskDelete(dinner)))
        assertTrue(shownNow(dinner).asking)
        assertEquals(Answer.Done, b.ask(Call.EditorCancelAsk(dinner)))
        assertEquals(false, shownNow(dinner).asking)
        assertEquals(Answer.EditorSaved(false, null), b.ask(Call.EditorDelete(dinner)))
        assertTrue(pot.deletes.isEmpty(), "a delete not asked was written")
        assertEquals(Answer.Done, b.ask(Call.EditorAskDelete(dinner)))
        assertEquals(Answer.EditorSaved(true, null), b.ask(Call.EditorDelete(dinner)))
        assertEquals(listOf(DINNER to 1L), pot.deletes.toList())

        // a stale save: not done, said so
        val again = (b.ask(Call.EditorOpen(potId, DINNER, ".")) as Answer.EditorOpened).editor
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.STALE
        assertEquals(Answer.EditorSaved(false, me.raddatz.veyq.mobile.words.Notice(Msg.ED_STALE)), b.ask(Call.EditorSave(again)))
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN

        // no such entry, no such editor; a closed Pot's editors close with it
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_ENTRY), b.ask(Call.EditorOpen(potId, TAXI, ".")))
        assertEquals(Update.Editor(99, null), b.watching(Watched.Editor(99)).next())
        val other = (b.ask(Call.EditorOpen(potId, null, ".")) as Answer.EditorOpened).editor
        assertEquals(Answer.Done, b.ask(Call.PotClose(potId)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_EDITOR), b.ask(Call.EditorSave(other)))
        b.shutdown()
    }

    /**
     * An entry's receipts through calls (ADR 0130): files added by the paths an open dialog gave — one that does not open,
     * one too large, one that is no receipt refused, the 101st refused —, one taken off, the save handed what was picked
     * and saying what a full storage left out; a kept receipt opened as bytes beside the JSON with what they are, a PDF's
     * page cap, and a receipt saved to a path (nothing left there when it does not open or the file does not write).
     */
    @Test
    fun aReceiptIsAddedByPathOpenedAndSavedThroughCalls() = runBlocking {
        val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1)
        val pot = PlayedPot(purgeAfter = noon + 86_400_000, receipts = mapOf("kept" to jpeg))
        // what the core asks the device to read of a picked file at most
        val asked = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val files = object : DeviceFiles by JvmFiles {
            override suspend fun read(path: String, most: Int): ByteArray = JvmFiles.read(path, most.also { asked += it })
        }
        val b = bridgeWithPot(pot, java.util.concurrent.atomic.AtomicLong(noon), kotlinx.coroutines.CompletableDeferred(Unit), files)
        b.signedIn()
        val potId = (b.ask(Call.PotOpen(ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip"))) as Answer.PotOpened).pot
        b.watching(Watched.Pot(potId)).untilJson { u -> (u["state"] as? kotlinx.serialization.json.JsonObject)?.get("view")?.takeIf { it is kotlinx.serialization.json.JsonObject } }
        suspend fun shownNow(editor: Long): EditorShown = b.watching(Watched.Editor(editor)).let { w -> (w.next() as Update.Editor).shown!!.also { b.unwatch(w.id) } }
        val dir = kotlin.io.path.createTempDirectory("veyq-receipts").toFile()
        try {
            fun file(name: String, bytes: ByteArray) = java.io.File(dir, name).also { it.writeBytes(bytes) }.path
            val photo = file("Quittung März.jpg", jpeg)
            val notes = file("notes.jpg", "Total 12.34".encodeToByteArray())
            val large = java.io.File(dir, "large.pdf").also { f ->
                java.io.RandomAccessFile(f, "rw").use { it.write("%PDF-1.7\n".encodeToByteArray()); it.setLength(me.raddatz.veyq.mobile.expenses.Receipts.MAX_BYTES + 1) }
            }.path

            // the entry's kept receipt: listed by its name, opened as its bytes with what they are, a PDF's page cap
            val id = (b.ask(Call.EditorOpen(potId, DINNER, ",")) as Answer.EditorOpened).editor
            val first = shownNow(id)
            assertEquals(listOf(EditorReceipt("kept", "kept.jpg", held = false, opens = true)).map { listOf(it.id, it.name, it.held, it.opens) }, first.receipts.map { listOf(it.id, it.name, it.held, it.opens) })
            assertTrue(first.canAddReceipt && !first.refusedPick)
            val (opened, bytes) = b.askWithPayload(Call.ReceiptOpen(potId, "kept"))
            assertEquals(Answer.Bytes("image/jpeg"), opened)
            assertTrue(jpeg.contentEquals(bytes), "the payload is not the receipt's bytes")
            assertEquals(Answer.Refused(Answer.Why.NO_RECEIPT), b.ask(Call.ReceiptOpen(potId, "nowhere")))
            assertEquals(Answer.ReceiptPages(20, me.raddatz.veyq.mobile.words.Notice(Msg.RECEIPTS_PAGES_CUT, 20, 25)), b.ask(Call.ReceiptPages(25)))
            assertEquals(Answer.ReceiptPages(3, null), b.ask(Call.ReceiptPages(3)))

            // saved to the path a save dialog gave; nothing left at a path when the receipt does not open or the file not write
            val saved = java.io.File(dir, "saved.jpg")
            assertEquals(Answer.Done, b.ask(Call.ReceiptSave(potId, "kept", saved.path)))
            assertTrue(jpeg.contentEquals(saved.readBytes()), "the saved file is not the receipt")
            val none = java.io.File(dir, "none.jpg")
            assertEquals(Answer.Refused(Answer.Why.NO_RECEIPT), b.ask(Call.ReceiptSave(potId, "nowhere", none.path)))
            assertTrue(!none.exists(), "a receipt that did not open left a file")
            assertEquals(Answer.Refused(Answer.Why.NOT_WRITTEN), b.ask(Call.ReceiptSave(potId, "kept", java.io.File(dir, "no/such/dir.jpg").path)))

            // picked by path: the picture taken under its name, a path that does not open, bytes that are no receipt and a
            // file past the cap refused (never read whole)
            assertEquals(Answer.Done, b.ask(Call.EditorAddReceipts(id, listOf(photo, java.io.File(dir, "gone.jpg").path, notes, large))))
            val picked = shownNow(id)
            assertEquals(listOf("kept.jpg" to false, "Quittung März.jpg" to true), picked.receipts.map { it.name to it.held })
            assertTrue(picked.refusedPick, "a refused file was not said")
            assertEquals(setOf(me.raddatz.veyq.mobile.expenses.Receipts.MAX_BYTES.toInt()), asked.toSet(), "a picked file was read past a receipt's size")
            // up to a hundred held; the 101st refused
            assertEquals(Answer.Done, b.ask(Call.EditorAddReceipts(id, List(98) { photo })))
            val full = shownNow(id)
            assertEquals(100, full.receipts.size)
            assertTrue(!full.refusedPick && !full.canAddReceipt)
            assertEquals(Answer.Done, b.ask(Call.EditorAddReceipts(id, listOf(photo))))
            val over = shownNow(id)
            assertEquals(100 to true, over.receipts.size to over.refusedPick)

            // taken off: a picked one forgotten, the kept one dropped by the save, applied before the save that follows
            val held = over.receipts.filter { it.held }
            b.call(wire.encodeToString(Call.serializer(), Call.EditorDropReceipt(id, held.first().id))) { _, _ -> }
            b.call(wire.encodeToString(Call.serializer(), Call.EditorDropReceipt(id, "kept"))) { _, _ -> }
            pot.skipped = listOf("Quittung März.jpg")
            assertEquals(Answer.EditorSaved(true, me.raddatz.veyq.mobile.words.Notices.receiptsSkipped(listOf("Quittung März.jpg"))), b.ask(Call.EditorSave(id)))
            assertEquals(emptyList(), pot.saves.single().second.receipts, "the kept receipt taken off is still kept")
            val uploaded = pot.uploads.single()
            assertEquals(held.drop(1).map { it.id }, uploaded.map { it.id })
            assertEquals("Quittung März.jpg" to "image/jpeg", uploaded.first().name to uploaded.first().type)
            assertEquals(Answer.Refused(Answer.Why.NO_SUCH_EDITOR), b.ask(Call.EditorAddReceipts(99, listOf(photo))))
        } finally {
            dir.deleteRecursively()
        }
        b.shutdown()
    }

    @Test
    fun aPotsSettingsAndPeopleAreChangedThroughCalls() = runBlocking {
        val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z
        val pot = PlayedPot(purgeAfter = noon + 86_400_000)
        pot.fixed = false
        pot.cleo = true
        pot.own = true
        pot.members = listOf(me.raddatz.veyq.mobile.session.Person("u-dana", "dana", "Dana"))
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>().also { it.complete(Unit) }
        val b = bridgeWithPot(pot, java.util.concurrent.atomic.AtomicLong(noon), opened)
        b.signedIn()
        val id = (b.ask(Call.PotOpen(ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip"))) as Answer.PotOpened).pot
        b.watching(Watched.Pot(id)).untilJson { (it["state"] as? kotlinx.serialization.json.JsonObject)?.get("view") as? kotlinx.serialization.json.JsonObject }
        val set = (b.ask(Call.PotSettingsOpen(id)) as Answer.PotSettingsOpened).settings
        suspend fun shown(pick: (PotSettingsShown) -> Boolean = { true }): PotSettingsShown =
            b.watching(Watched.PotSettings(set)).let { w -> w.until { u -> (u as Update.PotSettings).shown?.takeIf(pick) }.also { b.unwatch(w.id) } }
        val ops = pot.ops
        fun stored(notice: me.raddatz.veyq.mobile.words.Notice? = null) = Answer.Stored(notice == null, notice)
        val nothing = Answer.Stored(false, null)

        // the view's settings, each the core's: the currency chosen while no money fixes it, the own category, the people
        // with what may be done to each, the space's members still to add
        val first = shown()
        assertEquals("EUR" to true, first.currency to first.currencyEditable)
        assertTrue(first.ready && first.canWrite && first.canAddCategory && first.categoriesShown && !first.currencyFixed && first.settleFirst)
        assertEquals(listOf("c1" to "Snacks"), first.categories.map { it.id to (it.label as me.raddatz.veyq.mobile.words.Piece.Plain).text })
        assertEquals(listOf("Anna", "Ben", "Cleo"), first.people.map { it.entry.name })
        assertEquals(listOf(false, false, true), first.people.map { it.entry.canErase })
        assertEquals(listOf("u-dana" to "Dana"), first.members.map { it.userId to it.name })
        assertTrue(me.raddatz.veyq.mobile.expenses.Currencies.known.containsAll(first.currencies) && "CHF" in first.currencies)

        // the base currency: its own is nothing to write; another is written, its digits the core's; once money fixes it,
        // refused and said, nothing sent
        assertEquals(stored(), b.ask(Call.PotSettingsCurrency(set, "EUR")))
        assertTrue(ops.isEmpty(), "the Pot's own currency was written")
        pot.fixed = true
        assertEquals(stored(), b.ask(Call.PotSettingsCurrency(set, "JPY")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.BaseCurrency("JPY", 0), ops.last())
        assertTrue(shown().currencyFixed)
        val fixedNotice = me.raddatz.veyq.mobile.words.Notice(Msg.SETTINGS_CURRENCY_FIXED)
        assertEquals(stored(fixedNotice), b.ask(Call.PotSettingsCurrency(set, "USD")))
        assertEquals(1, ops.size, "a fixed currency was written")
        assertEquals(fixedNotice, shown().notice)

        // a category added and renamed; a person added, a member added, renamed, deactivated — each its write; what may
        // not be done sends nothing
        assertEquals(stored(), b.ask(Call.PotSettingsAddCategory(set, "Museum")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.AddCategory("Museum"), ops.last())
        assertNull(shown().notice, "a stored change left the refusal before it")
        assertEquals(stored(), b.ask(Call.PotSettingsRenameCategory(set, "c1", "Snacks renamed")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.RenameCategory("c1", "Snacks renamed"), ops.last())
        assertEquals(nothing, b.ask(Call.PotSettingsRenameCategory(set, "food", "Essen")))
        assertEquals(stored(), b.ask(Call.PotSettingsAddPerson(set, "Dora")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.AddPerson("Dora"), ops.last())
        assertEquals(nothing, b.ask(Call.PotSettingsAddPerson(set, "  ")))
        assertEquals(stored(), b.ask(Call.PotSettingsAddMember(set, "u-dana")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.AddMember("u-dana"), ops.last())
        assertEquals(nothing, b.ask(Call.PotSettingsAddMember(set, "u-zed")))
        assertEquals(stored(), b.ask(Call.PotSettingsRename(set, CLEO, "Cleo B")))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.Rename(CLEO, "Cleo B"), ops.last())
        assertEquals(stored(), b.ask(Call.PotSettingsToggle(set, CLEO)))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.SetActive(CLEO, false), ops.last())
        val sent = ops.size
        assertEquals(nothing, b.ask(Call.PotSettingsToggle(set, BEN)), "an unsettled person was deactivated")

        // an erase only once asked: the question names them, a cancel keeps them, the second ask's answer erases
        assertEquals(nothing, b.ask(Call.PotSettingsErase(set)))
        assertEquals(Answer.Done, b.ask(Call.PotSettingsAskErase(set, BEN)))
        assertNull(shown().asking, "an unsettled person's erase was asked")
        assertEquals(Answer.Done, b.ask(Call.PotSettingsAskErase(set, CLEO)))
        assertEquals(Triple(CLEO, "Cleo", false), shown { it.asking != null }.asking!!.let { Triple(it.person, it.name, it.named) })
        assertEquals(Answer.Done, b.ask(Call.PotSettingsCancelAsk(set)))
        assertNull(shown().asking)
        assertEquals(sent, ops.size, "something was written before the erase was answered")
        assertEquals(Answer.Done, b.ask(Call.PotSettingsAskErase(set, CLEO)))
        assertEquals(stored(), b.ask(Call.PotSettingsErase(set)))
        assertEquals(me.raddatz.veyq.mobile.expenses.PotModel.Op.Erase(CLEO), ops.last())

        // a write refused is said in the answer and on the settings, not as the Pot's message
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.STALE
        val stale = me.raddatz.veyq.mobile.words.Notice(Msg.ENTRY_STALE)
        assertEquals(stored(stale), b.ask(Call.PotSettingsAddPerson(set, "Eve")))
        assertEquals(stale, shown { it.notice != null }.notice)
        pot.outcome = me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN

        // closed, they answer no more; a Pot closed closes its settings
        assertEquals(Answer.Done, b.ask(Call.PotSettingsClose(set)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SETTINGS), b.ask(Call.PotSettingsAddPerson(set, "Fay")))
        val again = (b.ask(Call.PotSettingsOpen(id)) as Answer.PotSettingsOpened).settings
        assertEquals(Answer.Done, b.ask(Call.PotClose(id)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SETTINGS), b.ask(Call.PotSettingsAddPerson(again, "Fay")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(Call.PotSettingsOpen(id)))
        b.shutdown()
    }

    /** "New pot" over a door the test plays: the members of each space, and each create as it reached it. */
    private class PlayedCreate : me.raddatz.veyq.mobile.expenses.PotCreateSheet.Door {
        val membersOf = mapOf(
            "s" to listOf(me.raddatz.veyq.mobile.session.Person("u-me", "me", "Me"), me.raddatz.veyq.mobile.session.Person("u-dana", "dana", "Dana")),
            "t" to listOf(me.raddatz.veyq.mobile.session.Person("u-me", "me", "Me")),
        )
        class Created(val scopeId: String, val parentId: String?, val name: String, val base: me.raddatz.veyq.mobile.expenses.Ledger.BaseCurrency, val people: List<String>)
        val created = java.util.concurrent.CopyOnWriteArrayList<Created>()
        @Volatile var outcome = me.raddatz.veyq.mobile.expenses.PotCreate.Outcome.CREATED
        override suspend fun members(scopeId: String) = membersOf[scopeId]
        override suspend fun create(
            scopeId: String, parentId: String?, name: String, base: me.raddatz.veyq.mobile.expenses.Ledger.BaseCurrency,
            people: List<me.raddatz.veyq.mobile.expenses.PotCreate.NewPerson>,
        ): me.raddatz.veyq.mobile.expenses.PotCreate.Result {
            created += Created(scopeId, parentId, name, base, people.map { it.userId ?: it.name })
            val made = outcome == me.raddatz.veyq.mobile.expenses.PotCreate.Outcome.CREATED || outcome == me.raddatz.veyq.mobile.expenses.PotCreate.Outcome.PEOPLE_NOT_ADDED
            return me.raddatz.veyq.mobile.expenses.PotCreate.Result(outcome, if (made) "new" else null)
        }
    }

    @Test
    fun aNewPotIsFilledAndCreatedThroughCalls() = runBlocking {
        val door = PlayedCreate()
        val family = SpaceRef("s", "SHARED", "Familie")
        val mine = SpaceRef("t", "PERSONAL", "t")
        // New pressed in folder "Trips" of the space shown
        val place = me.raddatz.veyq.mobile.navigation.Shell(application = null, space = "s", path = listOf(FolderRef("f-trips", "Trips")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val languages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val hidden = Hidden({}, emptySet())
        val b = Bridge {
            Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden) { _ ->
                Tenant(shell = null, pot = null, me = "u1", pictures = pictures, loadPictures = {}, createPot = { language ->
                    languages += language
                    me.raddatz.veyq.mobile.expenses.PotCreateSheet.open(door, place, listOf(family, mine), language, scope, kotlinx.coroutines.Dispatchers.Default)
                })
            }
        }
        b.signedIn()
        val c = (b.ask(Call.PotCreateOpen("de-DE")) as Answer.PotCreateOpened).create
        assertEquals(listOf("de-DE"), languages)
        suspend fun shown(pick: (PotCreateShown) -> Boolean = { true }): PotCreateShown =
            b.watching(Watched.PotCreate(c)).let { w -> w.until { u -> (u as Update.PotCreate).shown?.takeIf(pick) }.also { b.unwatch(w.id) } }

        // it starts on the space shown in the language's currency, the space's members offered once read; no name, no create
        val first = shown { it.offered.isNotEmpty() }
        assertEquals("s" to "EUR", first.space to first.currency)
        assertEquals(listOf("s", "t"), first.spaces.map { it.space.scopeId })
        assertEquals(listOf("u-me", "u-dana"), first.offered.map { it.userId })
        assertTrue(!first.canCreate && !first.saving && !first.done && first.people.isEmpty())

        // the name as typed decides Create
        assertEquals(Answer.Done, b.ask(Call.PotCreateName(c, "Trip")))
        assertTrue(shown().canCreate)
        assertEquals(Answer.Done, b.ask(Call.PotCreateName(c, " ")))
        assertTrue(!shown().canCreate)

        // people typed and members added; a member of a space left goes; a person taken out again
        assertEquals(Answer.Stored(true, null), b.ask(Call.PotCreateAddTyped(c, "Ben")))
        assertEquals(Answer.Stored(false, null), b.ask(Call.PotCreateAddTyped(c, " ")))
        assertEquals(Answer.Done, b.ask(Call.PotCreateAddMember(c, "u-dana")))
        assertEquals(listOf("Ben" to false, "Dana" to true), shown().people.map { it.name to it.member })
        assertEquals(listOf("u-me"), shown().offered.map { it.userId })
        assertEquals(Answer.Done, b.ask(Call.PotCreateSpace(c, "t")))
        assertEquals(listOf("Ben"), shown { it.space == "t" && it.offered.isNotEmpty() }.people.map { it.name })
        assertEquals(Answer.Done, b.ask(Call.PotCreateSpace(c, "s")))
        shown { it.space == "s" && it.offered.size == 2 }
        assertEquals(Answer.Done, b.ask(Call.PotCreateAddMember(c, "u-dana")))
        assertEquals(Answer.Done, b.ask(Call.PotCreateRemove(c, 0)))
        assertEquals(listOf("Dana"), shown().people.map { it.name })
        assertEquals(Answer.Done, b.ask(Call.PotCreateCurrency(c, "CHF")))
        assertEquals("CHF", shown().currency)

        // names sent unawaited land in the order typed: the last is the one created
        repeat(200) { i -> b.call(wire.encodeToString(Call.serializer(), Call.PotCreateName(c, "Trip ${i + 1}"))) { _, _ -> } }
        assertEquals(Answer.Stored(true, null), b.ask(Call.PotCreateCreate(c, "Cleo")))
        val made = door.created.single()
        assertEquals(listOf("s", "f-trips", "Trip 200"), listOf(made.scopeId, made.parentId, made.name))
        assertEquals("CHF" to 2, made.base.currency to made.base.minorUnits)
        assertEquals(listOf("u-dana", "Cleo"), made.people)
        assertTrue(shown().done)

        // a create refused says why, in the answer and on the sheet
        door.outcome = me.raddatz.veyq.mobile.expenses.PotCreate.Outcome.NAME_TAKEN
        val other = (b.ask(Call.PotCreateOpen("en")) as Answer.PotCreateOpened).create
        assertEquals("USD", b.watching(Watched.PotCreate(other)).until { u -> (u as Update.PotCreate).shown }.currency)
        assertEquals(Answer.Done, b.ask(Call.PotCreateName(other, "Trip")))
        val taken = me.raddatz.veyq.mobile.words.Notice(Msg.CREATE_POT_NAME_TAKEN)
        assertEquals(Answer.Stored(false, taken), b.ask(Call.PotCreateCreate(other, "")))
        assertEquals(taken, b.watching(Watched.PotCreate(other)).until { u -> (u as Update.PotCreate).shown?.takeIf { it.notice != null } }.notice)
        // the Pot made without its people: stored, and said
        door.outcome = me.raddatz.veyq.mobile.expenses.PotCreate.Outcome.PEOPLE_NOT_ADDED
        assertEquals(Answer.Stored(true, me.raddatz.veyq.mobile.words.Notice(Msg.CREATE_POT_PEOPLE_FAILED)), b.ask(Call.PotCreateCreate(other, "")))

        // closed, it answers no more
        assertEquals(Answer.Done, b.ask(Call.PotCreateClose(other)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CREATE), b.ask(Call.PotCreateName(other, "x")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CREATE), b.ask(Call.PotCreateCreate(other, "")))
        b.shutdown()
        scope.cancel()
    }

    @Test
    fun aCoversCutIsTheCoresPixels() = runBlocking {
        val b = bridge()
        assertEquals(Answer.CoverCut(320, 0, 320, 360), b.ask(Call.CoverCut(me.raddatz.veyq.mobile.session.NodeCover.Crop(0.5, 0.0, 0.5, 1.0), 640, 360)))
        assertEquals(Answer.CoverCut(0, 0, 640, 360), b.ask(Call.CoverCut(me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL, 640, 360)))
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
        listOf(
            Call.PotRefresh(9), Call.PotClose(9), Call.PotDownload(9, "/tmp/never.zip", "deleted person"), Call.PotArchiveName(9),
            Call.PotRecord(9, emptyList()), Call.PotRestore(9, "e"), Call.PotHistory(9, "e"), Call.EditorOpen(9, null, "."),
        )
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_POT), b.ask(it), "$it") }
        listOf(Call.ReaderRead(9, 0, 4), Call.ReaderClose(9)).forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(it), "$it") }
        listOf(
            Call.EditorChange(9, me.raddatz.veyq.mobile.expenses.EditorChange.AddItem), Call.EditorSave(9), Call.EditorAskDelete(9),
            Call.EditorDelete(9), Call.EditorCancelAsk(9), Call.EditorClose(9),
        ).forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_EDITOR), b.ask(it), "$it") }
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

    private companion object {
        const val ANNA = "0e000000-0000-4000-8000-000000000001"
        const val BEN = "0e000000-0000-4000-8000-000000000002"
        const val DINNER = "0e000000-0000-4000-9000-000000000001"
        const val TAXI = "0e000000-0000-4000-9000-000000000002"
        const val LUNCH = "0e000000-0000-4000-9000-000000000003"
        const val CLEO = "0e000000-0000-4000-8000-000000000003"

        /** The played Pot's view, as `PotFixture` builds one in the core's tests: from revisions the test writes. */
        fun potView(
            purgeAfter: Long, lunch: Boolean, receipts: Set<String> = emptySet(), fixed: Boolean = true, cleo: Boolean = false,
            own: Boolean = false, members: List<me.raddatz.veyq.mobile.session.Person>? = null,
        ): me.raddatz.veyq.mobile.expenses.PotView {
            val base = me.raddatz.veyq.mobile.expenses.Ledger.BaseCurrency("EUR", 2)
            // the dinner keeps every receipt the Pot holds
            fun expense(title: String, amount: Long) = me.raddatz.veyq.mobile.expenses.Expense(
                title, "food", "2026-07-01", "EUR", 2, "1", amount, if (title == "Dinner") receipts.sorted() else emptyList(),
                listOf(me.raddatz.veyq.mobile.expenses.Contribution(ANNA, amount)),
                me.raddatz.veyq.mobile.expenses.Allocation(emptyList(), me.raddatz.veyq.mobile.expenses.Split.Equal(listOf(ANNA, BEN))),
            )
            val revs = listOfNotNull(
                Triple(ANNA, 1L, me.raddatz.veyq.mobile.expenses.ParticipantBody("Anna", true, 1)),
                Triple(BEN, 1L, me.raddatz.veyq.mobile.expenses.ParticipantBody("Ben", true, 2)),
                if (cleo) Triple(CLEO, 1L, me.raddatz.veyq.mobile.expenses.ParticipantBody("Cleo", true, 3)) else null,
                Triple(DINNER, 1L, expense("Dinner", 3000)),
                Triple(TAXI, 1L, expense("Taxi", 999)),
                Triple(TAXI, 2L, me.raddatz.veyq.mobile.expenses.Deleted),
                if (lunch) Triple(LUNCH, 1L, expense("Lunch", 1200)) else null,
            )
            val held = revs.mapIndexed { i, (id, rev, content) ->
                me.raddatz.veyq.mobile.expenses.HeldRevision(
                    id, rev, 1_000L * (i + 1), "", me.raddatz.veyq.mobile.expenses.RevisionBody(emptyList(), content), null,
                    purgeAfter = purgeAfter.takeIf { content == me.raddatz.veyq.mobile.expenses.Deleted },
                )
            }
            return me.raddatz.veyq.mobile.expenses.PotView(
                me.raddatz.veyq.mobile.expenses.PotView.Source(
                    "Trip", me.raddatz.veyq.mobile.expenses.PotMeta(base, if (own) listOf("c1" to "Snacks") else emptyList()), me.raddatz.veyq.mobile.expenses.Ledger.build(held, base),
                    me.raddatz.veyq.mobile.expenses.LedgerCheck.of(held), members, emptySet(), canWrite = true, currencyFixed = fixed, held = held,
                    ratesOffered = true,
                    receipts = receipts.sorted().map { id ->
                        val listed = me.raddatz.veyq.mobile.blob.ApplicationBlobs.Listed(id, DINNER, me.raddatz.veyq.mobile.expenses.Receipts.ROLE, "b-$id", 1, 10, "", null, null, 1_000L, 1_000L, null, null, false)
                        me.raddatz.veyq.mobile.expenses.Receipts.Receipt(id, DINNER, listed, "$id.jpg", "image/jpeg", 10, 1_000L, dropped = false, blocked = false)
                    },
                ),
            )
        }
    }
}
