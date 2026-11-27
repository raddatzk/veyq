package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.Primitives
import platform.Foundation.NSData
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The device's trust marks and records in UserDefaults: no secret (`TrustMarks`), kept across launches, gone with the app.
 * On iOS they are the app group's ([defaults] its suite), which the share extension reads too (ADR 0138).
 */
class UserDefaultsStore(private val defaults: NSUserDefaults) : DeviceStore {
    override fun read(key: String): String? = defaults.stringForKey(key)
    override fun write(key: String, value: String) = defaults.setObject(value, key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/**
 * What a Keychain read came to: the item's bytes, or none — [cancelled] when the person declined the prompt, [locked] when
 * the Keychain refused the read because the device is locked (`errSecInteractionNotAllowed`: an item readable only while
 * unlocked, read by a run in the background, ADR 0153 decision 8). A locked item is there: never taken for a missing one.
 */
class KeychainRead(val data: NSData?, val cancelled: Boolean, val locked: Boolean)

/**
 * The Keychain as the Swift app reaches it (`apple/Veyq/Core/KeychainStore.swift`): generic-password items on this device
 * only. A [protected] item is readable only after the person authenticated (`.userPresence`: Face ID, Touch ID or the
 * device's passcode); reading one asks them and blocks the calling thread, never the main one. A [passcode] item asks
 * nothing but exists only while the device has a passcode (`WhenPasscodeSetThisDeviceOnly`): removing the passcode
 * removes it.
 */
interface KeychainBridge {
    fun store(account: String, data: NSData, protected: Boolean, passcode: Boolean): Boolean
    fun load(account: String, prompt: String, protected: Boolean): KeychainRead
    fun remove(account: String)

    /**
     * Whether this device keeps the PIN level's hardware keys (ADR 0160 decision 2): a Secure Enclave the app may create
     * keys in — an Apple TV's (`SecureEnclave.isAvailable`); never the tvOS Simulator's, which has none, nor the iOS or
     * macOS app's, which keep no PIN (their bridge answers false and the three calls below are never made). The defaults
     * are a Kotlin implementer's (a test's fake); Swift sees an Objective-C protocol and implements all four.
     */
    fun pinKeysAvailable(): Boolean = false

    /**
     * A Secure Enclave P-256 key-agreement key created under [account] (replacing one there), its sealed representation
     * kept `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; false where it could not be made.
     */
    fun createPinKey(account: String): Boolean = false

    /**
     * The raw ECDH agreement (32 bytes, the shared point's x) of the key [account] with the PIN level's fixed public point
     * — a point whose discrete log nobody knows, derived by hash-to-curve (`Veyq/TV/TvKeychain.swift` holds it and
     * recomputes it); null where there is no such key (a reinstall, another Apple TV user).
     */
    fun pinAgreement(account: String): NSData? = null

    fun removePinKey(account: String) {}
}

/**
 * The PIN level's hardware factor over a Secure Enclave ([KeychainBridge.pinAgreement]): `F = HKDF-SHA256(ECDH(key, P),
 * salt = none, info = [PinFactors.LABEL], 32 bytes)` (ADR 0160 decision 2). The enclave computes no HMAC, so the
 * agreement with a fixed point is its fixed-input function; the HKDF is the suite's, as every derivation of the core.
 */
internal class ApplePinFactors(private val keychain: KeychainBridge, private val p: Primitives) : PinFactors {
    override fun create(alias: String) {
        keychain.removePinKey(alias)
        check(keychain.createPinKey(alias)) { "PIN key not created" }
    }

    override fun factor(alias: String): ByteArray? {
        val agreed = keychain.pinAgreement(alias)?.toByteArray() ?: return null
        return try { p.hkdfSha256(agreed, ByteArray(0), PinFactors.LABEL, 32) } finally { agreed.fill(0) }
    }

    override fun delete(alias: String) = keychain.removePinKey(alias)
}

/**
 * [DeviceKeys] over the Keychain (ADR 0123 decision 8). The Keychain holds the key bytes and asks the person; the suite
 * does the cryptography: a bind key is 64 random bytes whose HMAC is the DEVICE factor, an unlock key 32 random bytes
 * under which the DEVICE_KEY wraps are AES-256-GCM sealed — one per level: the [StaySignedIn.PRESENCE] item behind
 * `.userPresence`, the [StaySignedIn.LOGIN] item without it but bound to the passcode (this device only, read without
 * asking while the device is unlocked).
 *
 * On a TV ([tv]), which has no passcode, the [StaySignedIn.LOGIN] item is bound to nothing: staying signed in without a
 * PIN (owner 2026-11-26, ADR 0160), the TV's Keychain keeping it this device only and never in a backup
 * (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `Veyq/TV/TvKeychain.swift`).
 */
class AppleDeviceKeys(
    private val keychain: KeychainBridge,
    private val prompt: String,
    private val p: Primitives = LibsodiumPrimitives,
    private val tv: Boolean = false,
) : DeviceKeys {
    override fun createBindKey(alias: String) {
        keychain.remove(alias)
        check(keychain.store(alias, p.randomBytes(64).toNSData(), protected = false, passcode = false)) { "bind key not stored" }
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val key = read(alias).data?.toByteArray() ?: return null
        return try { p.hmacSha256(key, message) } finally { key.fill(0) }
    }

    override fun deleteBindKey(alias: String) = keychain.remove(alias)

    /** The PIN level's factor where the bridge has a Secure Enclave (an Apple TV), else none: the level is not offered. */
    override val pinFactors: PinFactors? = if (keychain.pinKeysAvailable()) ApplePinFactors(keychain, p) else null

    private fun unlockAccount(level: StaySignedIn) = when (level) {
        StaySignedIn.PRESENCE -> UNLOCK
        StaySignedIn.LOGIN -> UNLOCK_LOGIN
    }

    private suspend fun unlockKey(level: StaySignedIn, create: Boolean): ByteArray = withContext(Dispatchers.Default) {
        val protected = level == StaySignedIn.PRESENCE
        val read = keychain.load(unlockAccount(level), prompt, protected)
        read.data?.toByteArray()?.let { return@withContext it }
        // the device locked (the login item is readable only while unlocked): the key is there, closed now — never
        // replaced by a new one, which would orphan every wrap sealed under it
        if (read.locked) throw DeviceAuthFailed(cancelled = false, detail = "the device is locked")
        if (read.cancelled) throw DeviceAuthFailed(cancelled = true)
        if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no $level unlock key")
        if (level !in staySignedIn) throw DeviceAuthFailed(cancelled = false, detail = "$level is not offered without a passcode")
        val key = p.randomBytes(32)
        // the login item asks nothing, but is bound to the passcode: without one, no device sign-in authenticates — but on
        // a TV, which has none, where it is the deliberate choice to stay without a PIN
        if (!keychain.store(unlockAccount(level), key.toNSData(), protected, passcode = !protected && !tv)) {
            throw DeviceAuthFailed(cancelled = false, detail = "the $level unlock key was not stored")
        }
        key
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray> {
        val key = unlockKey(level, create = true)
        return try {
            val iv = p.randomBytes(12)
            iv to p.aesGcmSeal(key, iv, plaintext, aad)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray {
        val key = unlockKey(level, create = false)
        return try {
            p.aesGcmOpen(key, iv, ciphertext, aad)
        } catch (e: Exception) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        } finally {
            key.fill(0)
        }
    }

    override fun deleteUnlockKey(level: StaySignedIn) = keychain.remove(unlockAccount(level))

    override fun putSecret(alias: String, value: String) {
        keychain.remove(alias)
        keychain.store(alias, value.encodeToByteArray().toNSData(), protected = false, passcode = false)
    }

    override fun secret(alias: String): String? = read(alias).data?.toByteArray()?.decodeToString()

    /**
     * An unprotected item ([createBindKey]'s, [putSecret]'s: readable after the first unlock since a restart), read; a
     * Keychain still locked since the restart throws [DeviceAuthFailed] rather than answer "none", which the resume takes
     * for a trust that ended and forgets the record (a run in the background before the first unlock, ADR 0153 decision 8).
     */
    private fun read(alias: String): KeychainRead =
        keychain.load(alias, prompt, protected = false).also { if (it.locked) throw DeviceAuthFailed(cancelled = false, detail = "the device is locked") }

    override fun deleteSecret(alias: String) = keychain.remove(alias)

    /**
     * Both levels where the device has a passcode, else none. `.userPresence` needs one; the login item could exist
     * without, but its guarantee is that the device's own sign-in authenticates, and without a passcode nothing does.
     * A TV has none ([deviceOwnerAuthenticationAvailable]): there the login level alone, staying signed in without a PIN,
     * which the TV offers only beside its PIN and after a warning (owner 2026-11-26, ADR 0160).
     */
    override val staySignedIn: Set<StaySignedIn>
        get() = when {
            tv -> setOf(StaySignedIn.LOGIN)
            deviceOwnerAuthenticationAvailable() -> setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)
            else -> emptySet()
        }

    private companion object {
        /** The presence item, the one unlock key there was before the levels: its seals still open. */
        const val UNLOCK = "veyq.unlock"
        const val UNLOCK_LOGIN = "veyq.unlock.login"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

/**
 * A session that keeps nothing on disk (`src/mobile/CLAUDE.md` § Nothing on disk the app did not choose). The default
 * configuration Ktor's Darwin engine starts from writes every cacheable response — API answers and blob ciphertext — to
 * `Library/Caches/<bundle id>/Cache.db` and `fsCachedData` (measured 2026-11-13 on an iOS simulator), keeps cookies in
 * the shared `HTTPCookieStorage` (`Library/Cookies`) and credentials in the shared `URLCredentialStorage`. The core
 * needs none of them: the session token travels in a header (ADR 0004), and the `outside` client promises no cookie.
 */
internal fun NSURLSessionConfiguration.keepNothingOnDisk() {
    setURLCache(null)
    setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
    setHTTPCookieStorage(null)
    setHTTPShouldSetCookies(false)
    setHTTPCookieAcceptPolicy(NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever)
    setURLCredentialStorage(null)
}

/**
 * The configuration every core session runs on: an **ephemeral** one ([keepNothingOnDisk] on top). Clearing the
 * stores of the default configuration is not enough — CFNetwork still keeps a per-app storage session on disk,
 * `Library/HTTPStorages/<bundle id>/httpstorages.sqlite`, whose `alt_services` table records every host that answers
 * with `Alt-Svc` (measured 2026-11-13). An ephemeral configuration's storage session lives in memory only, so nothing
 * of the session reaches disk; the stores it would keep in memory are cleared as well.
 */
internal fun ephemeralNoDiskConfiguration(): NSURLSessionConfiguration =
    NSURLSessionConfiguration.ephemeralSessionConfiguration.apply { keepNothingOnDisk() }

/**
 * Ktor's Darwin engine (URLSession) over an ephemeral session that keeps nothing on disk ([ephemeralNoDiskConfiguration]).
 * Ktor builds its own session from `defaultSessionConfiguration` and only lets that be mutated, so the session is made
 * here and handed over with Ktor's own [KtorNSURLSessionDelegate], which carries the engine's responses; closing the
 * client invalidates it as it does Ktor's own. A nil delegate queue gives the session its own serial queue.
 */
fun appleHttpClient(block: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    engine {
        val delegate = KtorNSURLSessionDelegate()
        usePreconfiguredSession(
            NSURLSession.sessionWithConfiguration(ephemeralNoDiskConfiguration(), delegate, delegateQueue = null),
            delegate,
        )
    }
    block()
}

/** The Apple app's door into its accounts: libsodium's primitives and the platform's HTTP client (URLSession). */
object AppleSessions {
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> appleHttpClient(block) }

    /**
     * This installation: [defaults] for its records and marks (the app group's on iOS), the Keychain for its keys, and
     * [offlineRoot] the directory the app names for the accounts' offline stores (ADR 0140 decision 3; null: none, as the
     * share extension), each starting at [offlineLimit] (`AccountOffline.PHONE_LIMIT` on iOS, `DESKTOP_LIMIT` on macOS);
     * [backgroundRuns] where the app syncs on the system's schedule (ADR 0153 decision 8: the iOS app, never a Mac).
     */
    fun device(
        keychain: KeychainBridge,
        label: String,
        prompt: String,
        defaults: NSUserDefaults,
        offlineRoot: String?,
        offlineLimit: Long,
        backgroundRuns: Boolean,
    ): ThisDevice =
        ThisDevice(UserDefaultsStore(defaults), AppleDeviceKeys(keychain, prompt), label, LibsodiumPrimitives, offlineRoot, offlineLimit, backgroundRuns)

    /**
     * This installation on a TV (ADR 0159): [DeviceForm.TV], no offline store and nothing run in the background (decision
     * 7), its keys in [keychain] — whose Secure Enclave, where it has one, holds the PIN level's factors (ADR 0160).
     */
    fun tvDevice(keychain: KeychainBridge, label: String, prompt: String, defaults: NSUserDefaults): ThisDevice =
        ThisDevice(
            UserDefaultsStore(defaults), AppleDeviceKeys(keychain, prompt, tv = true), label, LibsodiumPrimitives,
            offlineRoot = null, backgroundRuns = false, form = DeviceForm.TV,
        )

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String, device: ThisDevice): VeyqAccount =
        VeyqAccount.signIn(LibsodiumPrimitives, server, email, password, secretKey, device, client)

    /** The way in (core's `SessionModel`) over libsodium and URLSession; [signInElsewhere] in the share extension. */
    fun sessionModel(
        device: ThisDevice,
        hidden: me.raddatz.veyq.mobile.navigation.Hidden,
        signInElsewhere: Boolean,
    ): SessionModel<VeyqAccount, TenantSession> =
        SessionModel.accounts(LibsodiumPrimitives, device, client, hidden, signInElsewhere, language = ::appLanguage)

    /**
     * The language the app's words are shown in (the bundle's localization chosen for this person, the per-app language
     * included): the terms gate's and Settings' Terms page's ([MemberTerms.TermsShown]).
     */
    fun appLanguage(): String =
        (platform.Foundation.NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String)?.takeIf { it.isNotEmpty() } ?: "en"

    /** The cover of node [nodeId] as the tenant's trees hold it, as the platform's data (core's `NodeCover`), or null. */
    @Throws(Exception::class)
    suspend fun openCover(tenant: TenantSession, nodeId: String): NSData? = NodeCover.openNode(tenant, nodeId)?.toNSData()

    /** Sets [jpeg] (the device's cover JPEG) as the cover of node [nodeId] in [scopeId], framed by [crop] (`NodeCover.set`). */
    @Throws(Exception::class)
    suspend fun setCover(tenant: TenantSession, scopeId: String, nodeId: String, jpeg: NSData, crop: NodeCover.Crop): NodeCover.Outcome =
        NodeCover.set(tenant, scopeId, nodeId, jpeg.toByteArray(), crop)

    /** A cover opened here for Swift: its image's bytes and its crop. */
    class FramedCover(val data: NSData, val crop: NodeCover.Crop)

    /** The cover of node [nodeId] with its crop (core's `NodeCover.openFramed`), or null. */
    @Throws(Exception::class)
    suspend fun openFramedCover(tenant: TenantSession, nodeId: String): FramedCover? =
        NodeCover.openFramed(tenant, nodeId)?.let { FramedCover(it.bytes.toNSData(), it.crop) }

    /** The members' pictures [tenant] shows now (core's `AvatarsModel`, which decides when they are read), by user id. */
    fun pictures(tenant: TenantSession): Map<String, NSData> = tenant.pictures.state.value.mapValues { it.value.toNSData() }
}
