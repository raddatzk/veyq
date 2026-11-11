package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.Primitives
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.posix.memcpy

/**
 * The device's trust marks and records in UserDefaults: no secret (`TrustMarks`), kept across launches, gone with the app.
 * On iOS they are the app group's ([defaults] its suite), which the share extension reads too (ADR 0129).
 */
class UserDefaultsStore(private val defaults: NSUserDefaults) : DeviceStore {
    override fun read(key: String): String? = defaults.stringForKey(key)
    override fun write(key: String, value: String) = defaults.setObject(value, key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/** What a Keychain read came to: the item's bytes, or none — [cancelled] when the person declined the prompt. */
class KeychainRead(val data: NSData?, val cancelled: Boolean)

/**
 * The Keychain as the Swift app reaches it (`apple/Veyq/KeychainStore.swift`): generic-password items on this device
 * only. A [protected] item is readable only after the person authenticated (`.userPresence`: Face ID, Touch ID or the
 * device's passcode); reading one asks them and blocks the calling thread, never the main one. A [passcode] item asks
 * nothing but exists only while the device has a passcode (`WhenPasscodeSetThisDeviceOnly`): removing the passcode
 * removes it.
 */
interface KeychainBridge {
    fun store(account: String, data: NSData, protected: Boolean, passcode: Boolean): Boolean
    fun load(account: String, prompt: String, protected: Boolean): KeychainRead
    fun remove(account: String)
}

/**
 * [DeviceKeys] over the Keychain (ADR 0123 decision 8). The Keychain holds the key bytes and asks the person; the suite
 * does the cryptography: a bind key is 64 random bytes whose HMAC is the DEVICE factor, an unlock key 32 random bytes
 * under which the DEVICE_KEY wraps are AES-256-GCM sealed — one per level: the [StaySignedIn.PRESENCE] item behind
 * `.userPresence`, the [StaySignedIn.LOGIN] item without it but bound to the passcode (this device only, read without
 * asking while the device is unlocked).
 */
class AppleDeviceKeys(private val keychain: KeychainBridge, private val prompt: String, private val p: Primitives = LibsodiumPrimitives) : DeviceKeys {
    override fun createBindKey(alias: String) {
        keychain.remove(alias)
        check(keychain.store(alias, p.randomBytes(64).toNSData(), protected = false, passcode = false)) { "bind key not stored" }
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val key = keychain.load(alias, prompt, protected = false).data?.toByteArray() ?: return null
        return try { p.hmacSha256(key, message) } finally { key.fill(0) }
    }

    override fun deleteBindKey(alias: String) = keychain.remove(alias)

    private fun unlockAccount(level: StaySignedIn) = when (level) {
        StaySignedIn.PRESENCE -> UNLOCK
        StaySignedIn.LOGIN -> UNLOCK_LOGIN
    }

    private suspend fun unlockKey(level: StaySignedIn, create: Boolean): ByteArray = withContext(Dispatchers.Default) {
        val protected = level == StaySignedIn.PRESENCE
        val read = keychain.load(unlockAccount(level), prompt, protected)
        read.data?.toByteArray()?.let { return@withContext it }
        if (read.cancelled) throw DeviceAuthFailed(cancelled = true)
        if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no $level unlock key")
        if (level !in staySignedIn) throw DeviceAuthFailed(cancelled = false, detail = "$level is not offered without a passcode")
        val key = p.randomBytes(32)
        // the login item asks nothing, but is bound to the passcode: without one, no device sign-in authenticates
        if (!keychain.store(unlockAccount(level), key.toNSData(), protected, passcode = !protected)) {
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

    override fun secret(alias: String): String? = keychain.load(alias, prompt, protected = false).data?.toByteArray()?.decodeToString()

    override fun deleteSecret(alias: String) = keychain.remove(alias)

    /**
     * Both levels where the device has a passcode, else none. `.userPresence` needs one; the login item could exist
     * without, but its guarantee is that the device's own sign-in authenticates, and without a passcode nothing does.
     */
    @OptIn(ExperimentalForeignApi::class)
    override val staySignedIn: Set<StaySignedIn>
        get() = if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN) else emptySet()

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

/** The Apple app's door into its accounts: libsodium's primitives and the platform's HTTP client (URLSession). */
object AppleSessions {
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(Darwin, block) }

    /** This installation: [defaults] for its records and marks (the app group's on iOS), the Keychain for its keys. */
    fun device(keychain: KeychainBridge, label: String, prompt: String, defaults: NSUserDefaults): ThisDevice =
        ThisDevice(UserDefaultsStore(defaults), AppleDeviceKeys(keychain, prompt), label, LibsodiumPrimitives)

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String, device: ThisDevice): VeyqAccount =
        VeyqAccount.signIn(LibsodiumPrimitives, server, email, password, secretKey, device, client)

    /** The way in (core's `SessionModel`) over libsodium and URLSession; [signInElsewhere] in the share extension. */
    fun sessionModel(
        device: ThisDevice,
        hidden: me.raddatz.veyq.mobile.navigation.Hidden,
        recent: me.raddatz.veyq.mobile.navigation.RecentUses,
        signInElsewhere: Boolean,
    ): SessionModel<VeyqAccount, TenantSession> = SessionModel.accounts(LibsodiumPrimitives, device, client, hidden, recent, signInElsewhere)

    /** An Expenses Pot of [tenant], opened and read; null when the tenant holds no such open Pot. */
    @Throws(Exception::class)
    suspend fun openPot(tenant: TenantSession, scopeId: String, nodeId: String): me.raddatz.veyq.mobile.expenses.PotSession? =
        me.raddatz.veyq.mobile.expenses.PotSession.open(tenant, scopeId, nodeId)

    /** A receipt of [pot] opened to its bytes, as Foundation data; null when blocked or unreadable. */
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

    @Throws(Exception::class)
    suspend fun openReceipt(pot: me.raddatz.veyq.mobile.expenses.PotSession, receiptId: String): NSData? =
        pot.openReceipt(receiptId)?.toNSData()

    /** The members' pictures [tenant] shows now (core's `AvatarsModel`, which decides when they are read), by user id. */
    fun pictures(tenant: TenantSession): Map<String, NSData> = tenant.pictures.state.value.mapValues { it.value.toNSData() }

    /** A file picked as a receipt, to be uploaded when its entry is saved; [id] a fresh UUID. */
    fun pendingReceipt(id: String, data: NSData, type: String, name: String?, modifiedAt: Long?): me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt =
        me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt(id, data.toByteArray(), type, name, modifiedAt)

    /** A file picked as a receipt for core's entry editor to judge ([data] nil: it did not read). */
    fun picked(data: NSData?, type: String, name: String?, modifiedAt: Long?): me.raddatz.veyq.mobile.expenses.EntryEditor.Picked =
        me.raddatz.veyq.mobile.expenses.EntryEditor.Picked(data?.toByteArray(), type, name, modifiedAt)

    /** What the bytes of an opened receipt are (an image, a PDF, a download only). */
    fun receiptKind(data: NSData): me.raddatz.veyq.mobile.expenses.Receipts.Kind = me.raddatz.veyq.mobile.expenses.Receipts.kindOf(data.toByteArray())
}
