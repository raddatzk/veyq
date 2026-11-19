package me.raddatz.veyq.mobile.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.api.models.DeviceRegister
import me.raddatz.veyq.mobile.api.models.DeviceRegisterUpdate
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.offline.OfflineStores

/**
 * The platform's keys that never leave this device (Android Keystore, the Apple Keychain, Windows' DPAPI and Hello, the
 * Secret Service), for the two device wraps of the Master Key (ADR 0005, ADR 0123 decision 8):
 *
 *  - a **bind key** per trusted account: its HMAC over `veyq/v1/device-bind` is the DEVICE wrap's device factor, so the
 *    wrap opens with the password and this device, no Secret Key;
 *  - an **unlock key per level** ([StaySignedIn]) for the app: AES-256-GCM that seals the DEVICE_KEY wraps of the
 *    accounts the person chose to stay signed in to at that level. The [StaySignedIn.PRESENCE] key works only after the
 *    person authenticated (biometrics, the device's own code, Windows Hello); one authentication may open several within
 *    a short window, so one launch asks once. The [StaySignedIn.LOGIN] key opens without asking: the device's own
 *    sign-in is the authentication. Each level is a key of its own, so a level a seal used is the level its open uses
 *    (the record keeps it, [TrustedDevice.level]); deleting one leaves the other.
 *
 * And small secrets kept without asking the person: a remembered account's refresh token (server access, not content).
 */
interface DeviceKeys {
    /** Creates the bind key [alias], replacing one there. */
    fun createBindKey(alias: String)

    /** HMAC-SHA256 under the bind key [alias] over [message], or null when there is no such key. */
    fun bindHmac(alias: String, message: ByteArray): ByteArray?

    fun deleteBindKey(alias: String)

    /**
     * Seals [plaintext] under [level]'s unlock key (created on first use): (iv, ciphertext‖tag). At
     * [StaySignedIn.PRESENCE] it may ask the person, and throws [DeviceAuthFailed] (`cancelled`) when they do not
     * authenticate. A level this store does not offer ([staySignedIn]) throws `DeviceAuthFailed(cancelled = false)`, and
     * never falls back to another: a key sealed at a level is that level's.
     */
    suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray>

    /** Opens what [sealUnlockable] sealed at [level]. May ask the person at [StaySignedIn.PRESENCE]; throws
     *  [DeviceAuthFailed] when they do not authenticate, or when that level's key is gone (a new biometric enrolment
     *  invalidates the presence key on Android). */
    suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray

    /** Deletes [level]'s unlock key (nothing where there is none); the other level's stays. */
    fun deleteUnlockKey(level: StaySignedIn)

    fun putSecret(alias: String, value: String)
    fun secret(alias: String): String?
    fun deleteSecret(alias: String)

    /**
     * The levels staying signed in can take on this device now, asked of the store itself (never a host's own probe): what
     * the offer after a sign-in and "Manage servers" offer (ADR 0123 decision 8). Empty where
     * nothing staying signed in needs can be kept: no offer. May reach the platform (D-Bus, cryptngc, the keyguard), so
     * it is asked when an offer is made, not on every frame.
     */
    val staySignedIn: Set<StaySignedIn>
}

/**
 * A level of staying signed in (ADR 0123 decision 8, owner 2026-11-11): the person chooses
 * among the levels their device offers ([DeviceKeys.staySignedIn]), after the sign-in or later in "Manage servers". The
 * same meaning on every platform; the words a platform gives each are its own.
 */
@Serializable
enum class StaySignedIn {
    /**
     * The unlock key opens only after the person authenticated to the device, enforced by the store: Windows Hello's key,
     * Android's screen lock (biometric or device credential), Apple's `.userPresence`. "Sign in with Windows Hello" (Face
     * ID, Touch ID, fingerprint).
     */
    PRESENCE,

    /**
     * The unlock key opens without asking: the person's own sign-in to the device is the authentication (Windows: DPAPI;
     * Linux: the Secret Service, unlocked by the login; Android: a Keystore key without user authentication; Apple: a
     * Keychain item without `.userPresence`): the web's level. The plain "Stay signed in". Offered beside
     * [PRESENCE], not only where it is missing: a device with a fingerprint reader must not force anyone to use it.
     */
    LOGIN,
}

/** The person did not authenticate for the unlock key ([cancelled]), or the key no longer works. */
class DeviceAuthFailed(val cancelled: Boolean, detail: String? = null) : Exception(detail ?: if (cancelled) "cancelled" else "unlock key unusable")

/**
 * One account this device trusts (the record the web keeps in IndexedDB): which server and account, which of the two
 * wraps, the wrap's id the server answered (how the unlock finds it among the served wraps), the local alias of its
 * key, and — for a DEVICE_KEY wrap — the [level] its unlock key is ([unlockLevel]). Not secret: the email is the
 * person's own, on their own device.
 */
@Serializable
data class TrustedDevice(
    val server: String,
    val email: String,
    val userId: String,
    val method: Method,
    val wrapId: String,
    val alias: String,
    /** The level a DEVICE_KEY wrap was sealed at; null for DEVICE, and in a record kept before levels existed. */
    val level: StaySignedIn? = null,
) {
    enum class Method { DEVICE, DEVICE_KEY }

    /**
     * The level whose unlock key opens this DEVICE_KEY wrap, null for DEVICE. A record kept before the levels (ADR 0123 decision 8) is
     * [StaySignedIn.PRESENCE]: Android and Apple sealed every one behind the person's authentication.
     */
    val unlockLevel: StaySignedIn?
        get() = if (method == Method.DEVICE_KEY) level ?: StaySignedIn.PRESENCE else null

    /** Whose record this is: one per account per server, as a connection is. */
    val key: String get() = "${io.ktor.http.Url(server).host}#$userId"

    /** The alias under which a remembered account's refresh token is kept. */
    internal val refreshAlias: String get() = "$alias.refresh"
}

/**
 * This installation as the accounts see it: its device store (preferences), its keys, the label it names itself with
 * in the account's device register (the model's name), its id there, and the accounts it trusts — and [offlineRoot],
 * the directory the host names for the accounts' offline stores (ADR 0140 decision 3), null where it names none, with
 * [offlineLimit], the limit an account's store starts with on this kind of device (decision 8: 2 GB on a phone, 10 GB on
 * a desktop).
 */
class ThisDevice(
    val store: DeviceStore,
    val keys: DeviceKeys,
    val label: String,
    private val primitives: Primitives,
    val offlineRoot: String? = null,
    val offlineLimit: Long = me.raddatz.veyq.mobile.offline.AccountOffline.DESKTOP_LIMIT,
) {
    /** The accounts' offline stores on this device, where the host named their directory. */
    val offline: OfflineStores? by lazy { offlineRoot?.let { OfflineStores(it, store, primitives, offlineFiles, offlineClock) } }

    /** The offline stores' files and clock: a test's seam (a counted write, a clock it sets), set before [offline] is first read. */
    internal var offlineFiles: me.raddatz.veyq.mobile.blob.DeviceFiles = me.raddatz.veyq.mobile.blob.deviceFiles

    @OptIn(kotlin.time.ExperimentalTime::class)
    internal var offlineClock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    /**
     * A copy of this device's own wrap of account [key]'s Master Key (ADR 0140 decision 4): ciphertext the server holds
     * too, kept beside the record so an unlock opens the account with the server unreachable. Kept at each online unlock
     * and enrolment, removed with the record and when the server no longer serves the wrap.
     */
    @Serializable
    internal data class KeptWrap(val wrapId: String, val wrappedKey: String, val kdfParams: String?)

    internal fun keepWrap(key: String, wrap: KeptWrap) = store.write(WRAP + key, JSON.encodeToString(wrap))

    internal fun keptWrap(key: String): KeptWrap? = runCatching { store.read(WRAP + key)?.let { JSON.decodeFromString<KeptWrap>(it) } }.getOrNull()

    internal fun dropWrap(key: String) = store.remove(WRAP + key)

    /**
     * Server [host]'s instance salt (public: `/api/setup` answers it to anyone), kept at each online sign-in: what a
     * password-trusted account opened offline derives its login key with, to sign in again at reconnect.
     */
    internal fun keepInstanceSalt(host: String, salt: String) = store.write(SALT + host, salt)

    internal fun instanceSalt(host: String): String? = store.read(SALT + host)

    /** A random id, kept outside the key stores (the web's `veyq:device-id`): it names this device in the register. */
    val id: String
        get() = store.read(ID) ?: uuid().also { store.write(ID, it) }

    /** Every account this device trusts, the most recently used first; a DEVICE_KEY record always names its level. */
    fun trusted(): List<TrustedDevice> =
        runCatching { store.read(TRUSTED)?.let { JSON.decodeFromString<List<TrustedDevice>>(it) } }.getOrNull().orEmpty()
            .map { if (it.level == null && it.unlockLevel != null) it.copy(level = it.unlockLevel) else it }

    fun trusted(key: String): TrustedDevice? = trusted().firstOrNull { it.key == key }

    internal fun remember(record: TrustedDevice) =
        store.write(TRUSTED, JSON.encodeToString(listOf(record) + trusted().filter { it.key != record.key }))

    internal fun forget(key: String) {
        store.write(TRUSTED, JSON.encodeToString(trusted().filter { it.key != key }))
        dropWrap(key)
    }

    /**
     * Whether the person answered the offer to stay signed in for account [key] with "Not now" on this device (ADR 0123 decision 8): it
     * is asked once per account and device. Not secret: whether someone chose to stay signed in is not content.
     */
    fun declined(key: String): Boolean = key in declinedKeys()

    /** Remembers [key]'s "Not now" ([declined] true), or forgets it once a level is taken. */
    internal fun setDeclined(key: String, declined: Boolean) {
        val now = declinedKeys().filter { it != key } + if (declined) listOf(key) else emptyList()
        store.write(DECLINED, JSON.encodeToString(now))
    }

    private fun declinedKeys(): List<String> =
        runCatching { store.read(DECLINED)?.let { JSON.decodeFromString<List<String>>(it) } }.getOrNull().orEmpty()

    /** Marks [key] the most recently used, so the app opens on it next time. */
    fun touch(key: String) {
        val record = trusted(key) ?: return
        remember(record)
    }

    internal fun newAlias(): String = "veyq.device." + hex(primitives.randomBytes(16))

    private fun uuid(): String {
        val b = primitives.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private companion object {
        const val ID = "veyq.device-id"
        const val TRUSTED = "veyq.trusted-devices"
        const val DECLINED = "veyq.stay-signed-in-declined"
        const val WRAP = "veyq.offline-wrap."
        const val SALT = "veyq.instance-salt."
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The register's server calls, behind one seam so a test can play the server. */
internal interface RegisterApi {
    suspend fun get(): DeviceRegister
    /** The revision-guarded write; false on `409 stale_revision`. */
    suspend fun put(update: DeviceRegisterUpdate): Boolean
}

/**
 * The account's device register, a port of `src/web/src/features/account/deviceRegister.ts` (en.account.device-register):
 * one sealed document under the Master Key, bound to its revision, that names each device and joins it to its session
 * families and its device wraps — the join a person's other device uses to remove this one's saved key (ADR 0058). A
 * device adds itself: after every unlock (write moment 1) and after it enrols a wrap (write moment 2). Purely additive;
 * read through this device's marks like the pin store; a `409` re-reads and re-applies, bounded. Never on an unlock
 * path: a failure here is swallowed by the caller.
 */
internal class DeviceRegisterStore(
    private val suite: V1,
    private val api: RegisterApi,
    private val marks: TrustMarks,
    private val masterKey: ByteArray,
) {
    @Serializable
    data class Entry(val label: String = "", val lastSeenAt: Long = 0)

    @Serializable
    data class Plain(
        val devices: Map<String, Entry> = emptyMap(),
        val families: Map<String, String> = emptyMap(),
        val wraps: Map<String, String> = emptyMap(),
    )

    private class Opened(val revision: Long, val plain: Plain)

    private suspend fun load(): Opened {
        val served = api.get()
        val mark = marks.register()
        val blob = served.blob
        if (served.revision < mark || (blob == null && mark > 0)) throw TrustStateRefused(TrustStateRefused.Code.ROLLED_BACK)
        if (blob == null) return Opened(served.revision, Plain())
        val plain = runCatching {
            JSON.decodeFromString<Plain>(suite.open(context(served.revision), masterKey, unb64(blob)).decodeToString())
        }.getOrNull() ?: throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
        marks.raiseRegister(served.revision)
        return Opened(served.revision, plain)
    }

    /** The register as this device opens it. */
    suspend fun read(): Plain = load().plain

    /** The register re-sealed under [newMasterKey] for the revision a rotation stores it at (the web's
     *  `resealUnderNewKey(registerDocument)`): read through the marks (a refusal refuses the rotation), guarded by the
     *  served revision. */
    suspend fun resealed(newMasterKey: ByteArray): DeviceRegisterUpdate {
        val base = load()
        val stored = base.revision + 1
        val blob = b64(suite.seal(context(stored), newMasterKey, utf8(JSON.encodeToString(Plain.serializer(), base.plain))))
        return DeviceRegisterUpdate(blob, base.revision)
    }

    /** Records this device (keeping a label it has), the [familyId] its login created and the [wrapId] it holds. */
    suspend fun remember(deviceId: String, label: String, familyId: String?, wrapId: String?, now: Long) = write(load()) { p ->
        Plain(
            devices = p.devices + (deviceId to Entry(p.devices[deviceId]?.label ?: label, now)),
            families = if (familyId != null) p.families + (familyId to deviceId) else p.families,
            wraps = if (wrapId != null) p.wraps + (wrapId to deviceId) else p.wraps,
        )
    }

    /**
     * Write moment 3 (the web's `reconcileWithSessions`): against the two lists the server is authoritative for — the
     * [live] families and the [served] device wraps (null: the wrap list was not read, so no wrap row goes) — the rows
     * this device saw and the server no longer lists are pruned, and the [currentFamily] the server marked is joined to
     * this device (a resume never learns its family id at a login). The prunes are frozen from the first read, so a
     * retry after a `409` removes exactly those ids and a row another device added meanwhile stays. False when nothing
     * needed writing.
     */
    suspend fun reconcile(
        live: Collection<String>,
        served: Collection<String>?,
        deviceId: String,
        label: String,
        currentFamily: String?,
        now: Long,
    ): Boolean {
        val base = load()
        val doomedFamilies = base.plain.families.keys.filter { it !in live }.toSet()
        val doomedWraps = served?.let { s -> base.plain.wraps.keys.filter { it !in s }.toSet() }.orEmpty()
        val needsCurrent = currentFamily != null && base.plain.families[currentFamily] != deviceId
        val needsDevice = deviceId !in base.plain.devices
        if (doomedFamilies.isEmpty() && doomedWraps.isEmpty() && !needsCurrent && !needsDevice) return false
        write(base) { p ->
            Plain(
                devices = if (needsDevice || needsCurrent) p.devices + (deviceId to Entry(p.devices[deviceId]?.label ?: label, now)) else p.devices,
                families = (p.families - doomedFamilies) + listOfNotNull(currentFamily?.let { it to deviceId }),
                wraps = p.wraps - doomedWraps,
            )
        }
        return true
    }

    /**
     * Names this device [label] — its own entry only: each entry has one writer, its device (en.account.device-register),
     * so no device renames another. Last write wins: a rename is deliberate.
     */
    suspend fun rename(deviceId: String, label: String, now: Long) = write(load()) { p ->
        p.copy(devices = p.devices + (deviceId to Entry(label, p.devices[deviceId]?.lastSeenAt ?: now)))
    }

    /**
     * Renames the device family [familyId] belongs to — the web's `renameDeviceOfFamily` (ADR 0148 decision 1): every
     * session of that device at once, any device of the account, last write wins. The family is resolved on each read
     * the write retries over; a family the register cannot name, or whose device has no entry, is a no-op.
     */
    suspend fun renameOfFamily(familyId: String, label: String) {
        val base = load()
        if (base.plain.families[familyId]?.let { base.plain.devices[it] } == null) return
        write(base) { p ->
            val device = p.families[familyId]
            val prior = device?.let { p.devices[it] }
            if (device == null || prior == null) p else p.copy(devices = p.devices + (device to prior.copy(label = label)))
        }
    }

    /**
     * Takes [deviceId] out of the register with every family and wrap row naming it — called by that device itself, as
     * [rename], so each entry keeps its one writer. A port's addition: the web's register has no such write (what
     * [VeyqAccount.removeThisDevice] leaves).
     */
    suspend fun leave(deviceId: String) = write(load()) { p ->
        Plain(p.devices - deviceId, p.families.filterValues { it != deviceId }, p.wraps.filterValues { it != deviceId })
    }

    /** One revision-guarded write of [change] over [base]; a `409` re-reads and applies it again, bounded. */
    private suspend fun write(base: Opened, change: (Plain) -> Plain) {
        var current = base
        repeat(MAX_ATTEMPTS) {
            val next = evict(change(current.plain))
            val stored = current.revision + 1
            val blob = b64(suite.seal(context(stored), masterKey, utf8(JSON.encodeToString(Plain.serializer(), next))))
            // over the contract's bound only devices in use are left (evict took every orphan it could): no write
            check(blob.length <= MAX_BLOB_CHARS) { "device_register_full" }
            if (api.put(DeviceRegisterUpdate(blob, current.revision))) {
                marks.raiseRegister(stored)
                return
            }
            current = load()
        }
        throw IllegalStateException("device_register_unsettled")
    }

    /**
     * Back to [MAX_DEVICES] by evicting **orphans** only — devices no family row and no wrap row names — least recently
     * seen first, and only as many as the bound needs. A device a row still names is in use as far as the register can
     * know (rows are pruned only against the server's lists, by [reconcile]), so it is never evicted, and the register
     * may stand over the bound (en.account.device-register § Growth). An orphan has no row to take along. The web's `evict`.
     */
    private fun evict(p: Plain): Plain {
        if (p.devices.size <= MAX_DEVICES) return p
        val named = p.families.values.toSet() + p.wraps.values
        val doomed = p.devices.entries.filter { it.key !in named }.sortedBy { it.value.lastSeenAt }
            .take(p.devices.size - MAX_DEVICES).map { it.key }.toSet()
        return p.copy(devices = p.devices - doomed)
    }

    /** What the sessions list reads from the register (the web's `RegisterView`). */
    class View(
        /** familyId → its device's label; a family the register cannot name is absent. */
        val names: Map<String, String>,
        /** familyId → the served wraps of its device, never this device's: what "Sign out and remove saved key" deletes. */
        val wrapsOfFamily: Map<String, List<String>>,
        /** deviceId → (label, served wraps) of each device the register knows holding a served wrap and no live family. */
        val savedKeyDevices: Map<String, Pair<String, List<String>>>,
    )

    internal companion object {
        /**
         * The web's `readRegisterView` over [plain]: the [live] families' names, and — only where the wrap list was read
         * ([served] not null) — each family's device's served wraps and the devices with a saved key and no live family.
         * This device ([thisDevice]) is in neither wrap-bearing result: every action they feed reaches another device;
         * this one's own trust is "Stop trusting this device".
         */
        fun view(plain: Plain, live: Collection<String>, served: Collection<String>?, thisDevice: String): View {
            val wrapsOfDevice = HashMap<String, MutableList<String>>()
            if (served != null) {
                for ((wrap, device) in plain.wraps) {
                    if (wrap !in served || device == thisDevice || device !in plain.devices) continue
                    wrapsOfDevice.getOrPut(device) { mutableListOf() } += wrap
                }
            }
            val names = HashMap<String, String>()
            val withLiveFamily = HashSet<String>()
            val wrapsOfFamily = HashMap<String, List<String>>()
            for ((family, device) in plain.families) {
                if (family !in live) continue
                plain.devices[device]?.let { names[family] = it.label }
                withLiveFamily += device
                wrapsOfDevice[device]?.let { wrapsOfFamily[family] = it.toList() }
            }
            val saved = wrapsOfDevice.filterKeys { it !in withLiveFamily }
                .mapValues { (device, wraps) -> plain.devices.getValue(device).label to wraps.toList() }
            return View(names, wrapsOfFamily, saved)
        }

        const val MAX_ATTEMPTS = 5
        /** The web's `MAX_DEVICES`: beyond it orphans go; a device in use never does, so the register may stand over it. */
        const val MAX_DEVICES = 32

        /** The contract's `maxLength` on `DeviceRegisterUpdate.blob`: a longer seal is refused, `device_register_full`. */
        const val MAX_BLOB_CHARS = 65532
        val JSON = Json { ignoreUnknownKeys = true }
        fun context(revision: Long) = Context("device-register", "revision" to revision)
    }
}
