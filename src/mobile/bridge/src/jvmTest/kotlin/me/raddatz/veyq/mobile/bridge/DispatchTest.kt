package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.flow.MutableStateFlow
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
