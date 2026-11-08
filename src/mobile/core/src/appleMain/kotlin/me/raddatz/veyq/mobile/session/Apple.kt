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
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.TenantRef
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.posix.memcpy

/** The device's trust marks and records in UserDefaults: no secret (`TrustMarks`), kept across launches, gone with the app. */
object UserDefaultsStore : DeviceStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    override fun read(key: String): String? = defaults.stringForKey(key)
    override fun write(key: String, value: String) = defaults.setObject(value, key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/** What a Keychain read came to: the item's bytes, or none — [cancelled] when the person declined the prompt. */
class KeychainRead(val data: NSData?, val cancelled: Boolean)

/**
 * The Keychain as the Swift app reaches it (`apple/Veyq/KeychainStore.swift`): generic-password items on this device
 * only. A [protected] item is readable only after the person authenticated (`.userPresence`: Face ID, Touch ID or the
 * device's passcode); reading one asks them and blocks the calling thread, never the main one.
 */
interface KeychainBridge {
    fun store(account: String, data: NSData, protected: Boolean): Boolean
    fun load(account: String, prompt: String, protected: Boolean): KeychainRead
    fun remove(account: String)
}

/**
 * [DeviceKeys] over the Keychain (ADR 0123 decision 8). The Keychain holds the key bytes and asks the person; the suite
 * does the cryptography: a bind key is 64 random bytes whose HMAC is the DEVICE factor, the unlock key 32 random bytes
 * behind `.userPresence` under which the DEVICE_KEY wraps are AES-256-GCM sealed.
 */
class AppleDeviceKeys(private val keychain: KeychainBridge, private val prompt: String, private val p: Primitives = LibsodiumPrimitives) : DeviceKeys {
    override fun createBindKey(alias: String) {
        keychain.remove(alias)
        check(keychain.store(alias, p.randomBytes(64).toNSData(), protected = false)) { "bind key not stored" }
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val key = keychain.load(alias, prompt, protected = false).data?.toByteArray() ?: return null
        return try { p.hmacSha256(key, message) } finally { key.fill(0) }
    }

    override fun deleteBindKey(alias: String) = keychain.remove(alias)

    private suspend fun unlockKey(create: Boolean): ByteArray = withContext(Dispatchers.Default) {
        val read = keychain.load(UNLOCK, prompt, protected = true)
        read.data?.toByteArray()?.let { return@withContext it }
        if (read.cancelled) throw DeviceAuthFailed(cancelled = true)
        if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no unlock key")
        val key = p.randomBytes(32)
        if (!keychain.store(UNLOCK, key.toNSData(), protected = true)) {
            throw DeviceAuthFailed(cancelled = false, detail = "no passcode set: a key that needs the person cannot exist")
        }
        key
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val key = unlockKey(create = true)
        return try {
            val iv = p.randomBytes(12)
            iv to p.aesGcmSeal(key, iv, plaintext, aad)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val key = unlockKey(create = false)
        return try {
            p.aesGcmOpen(key, iv, ciphertext, aad)
        } catch (e: Exception) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        } finally {
            key.fill(0)
        }
    }

    override fun deleteUnlockKey() = keychain.remove(UNLOCK)

    override fun putSecret(alias: String, value: String) {
        keychain.remove(alias)
        keychain.store(alias, value.encodeToByteArray().toNSData(), protected = false)
    }

    override fun secret(alias: String): String? = keychain.load(alias, prompt, protected = false).data?.toByteArray()?.decodeToString()

    override fun deleteSecret(alias: String) = keychain.remove(alias)

    private companion object {
        const val UNLOCK = "veyq.unlock"
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

    /** This installation: UserDefaults for its records and marks, the Keychain for its keys. */
    fun device(keychain: KeychainBridge, label: String, prompt: String): ThisDevice =
        ThisDevice(UserDefaultsStore, AppleDeviceKeys(keychain, prompt), label, LibsodiumPrimitives)

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String, device: ThisDevice): VeyqAccount =
        VeyqAccount.signIn(LibsodiumPrimitives, server, email, password, secretKey, device, client)

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun unlockOnDevice(record: TrustedDevice, password: String, device: ThisDevice): VeyqAccount =
        VeyqAccount.unlockOnDevice(LibsodiumPrimitives, record, password, device, client)

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun resume(record: TrustedDevice, device: ThisDevice): VeyqAccount =
        VeyqAccount.resume(LibsodiumPrimitives, record, device, client)

    /**
     * Trusts this device with [account] after a sign-in: staying signed in when [remember] (declined at the prompt, the
     * password trust instead), else the password trust. Never fails the sign-in: false when no trust was enrolled.
     */
    @Throws(kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun trust(account: VeyqAccount, password: String, remember: Boolean): Boolean = runCatching {
        if (!remember) account.trustThisDevice(password, stayingSignedIn = false)
        else try {
            account.trustThisDevice(null, stayingSignedIn = true)
        } catch (e: DeviceAuthFailed) {
            account.trustThisDevice(password, stayingSignedIn = false)
        }
    }.map { it != VeyqAccount.TrustOutcome.NOT_OFFERED }.getOrDefault(false)

    /** The servers the app is signed in to, typed for Swift (a generic class is awkward across the boundary). */
    fun connections(): Connections<VeyqAccount> = Connections()

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession = account.open(tenant)

    /** An Expenses Pot of [tenant], opened and read; null when the tenant holds no such open Pot. */
    @Throws(Exception::class)
    suspend fun openPot(tenant: TenantSession, scopeId: String, nodeId: String): me.raddatz.veyq.mobile.expenses.PotSession? =
        me.raddatz.veyq.mobile.expenses.PotSession.open(tenant, scopeId, nodeId)

    /** A receipt of [pot] opened to its bytes, as Foundation data; null when blocked or unreadable. */
    @Throws(Exception::class)
    suspend fun openReceipt(pot: me.raddatz.veyq.mobile.expenses.PotSession, receiptId: String): NSData? =
        pot.openReceipt(receiptId)?.toNSData()

    /** A file picked as a receipt, to be uploaded when its entry is saved; [id] a fresh UUID. */
    fun pendingReceipt(id: String, data: NSData, type: String, name: String?, modifiedAt: Long?): me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt =
        me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt(id, data.toByteArray(), type, name, modifiedAt)

    /** What the bytes of an opened receipt are (an image, a PDF, a download only). */
    fun receiptKind(data: NSData): me.raddatz.veyq.mobile.expenses.Receipts.Kind = me.raddatz.veyq.mobile.expenses.Receipts.kindOf(data.toByteArray())
}
