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
import platform.posix.memcpy

/**
 * The device's trust marks and records in UserDefaults: no secret (`TrustMarks`), kept across launches, gone with the app.
 * On iOS they are the app group's ([defaults] its suite), which the share extension reads too (ADR 0126).
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

    /** The members' pictures of [tenant] after syncing them with its directory (`MemberAvatars`), by user id. */
    @Throws(Exception::class)
    suspend fun syncAvatars(tenant: TenantSession): Map<String, NSData> {
        tenant.avatars.sync(tenant.people.values)
        return tenant.avatars.pictures().mapValues { it.value.toNSData() }
    }

    /** A file picked as a receipt, to be uploaded when its entry is saved; [id] a fresh UUID. */
    fun pendingReceipt(id: String, data: NSData, type: String, name: String?, modifiedAt: Long?): me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt =
        me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt(id, data.toByteArray(), type, name, modifiedAt)

    /** What the bytes of an opened receipt are (an image, a PDF, a download only). */
    fun receiptKind(data: NSData): me.raddatz.veyq.mobile.expenses.Receipts.Kind = me.raddatz.veyq.mobile.expenses.Receipts.kindOf(data.toByteArray())
}
