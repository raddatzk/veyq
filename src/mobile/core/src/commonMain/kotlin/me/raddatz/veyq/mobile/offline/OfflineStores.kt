package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * This device's offline stores, one per account (ADR 0140 decisions 2, 3 and 11), under [root] — the directory the host
 * names at start (Android's `noBackupFilesDir/offline`, Apple's `Application Support/offline`, Windows'
 * `%LOCALAPPDATA%\veyq\offline`, Linux's `$XDG_DATA_HOME/veyq/offline`); its parent exists.
 *
 * Each account's directory is a random id kept in the [DeviceStore] beside the trusted record, so a [wipe] needs no key
 * and works on a locked account. A wipe marks the id first, removes every file, then the directory, and removes the id
 * last: a wipe a crash interrupted is finished by [finishWipes] at the next start.
 */
class OfflineStores internal constructor(
    val root: String,
    private val store: DeviceStore,
    private val primitives: Primitives,
    private val files: DeviceFiles,
    private val clock: () -> Long,
) {
    constructor(root: String, store: DeviceStore, primitives: Primitives) :
        this(root, store, primitives, deviceFiles, { kotlin.time.Clock.System.now().toEpochMilliseconds() })

    @Serializable
    internal data class Record(val id: String, val wiping: Boolean = false)

    /**
     * [account]'s store (a [me.raddatz.veyq.mobile.session.TrustedDevice.key]) opened under [masterKey]: its header's
     * `K_off` unwrapped under `HKDF(masterKey, "veyq/v1/offline-store")` and its manifest read. A store whose header does
     * not open under this Master Key (a rotated one, another account's) is emptied and started anew — it is a copy of
     * what the server holds, never the only one. The first open makes the directory and the key.
     */
    fun open(account: String, masterKey: ByteArray): OfflineStore {
        var record = records()[account]
        if (record?.wiping == true) { wipe(account); record = null }
        if (record == null) {
            record = Record(hex(primitives.randomBytes(16)))
            write(records() + (account to record))
        }
        val dir = directory(record.id)
        check(files.createDirectory(root) && files.createDirectory(dir)) { "cannot make the offline store's directory" }
        val wrapKey = primitives.hkdfSha256(masterKey, ByteArray(0), WRAP_INFO, 32)
        try {
            val header = "$dir${files.separator}${OfflineStore.HEADER}"
            val aad = concat(HEADER_AAD, lp(account.encodeToByteArray()))
            var key = files.read(header)?.let { openHeader(it, wrapKey, aad) }
            if (key == null) {
                // no header, or one this Master Key does not open: whatever the directory holds is no longer readable
                for (n in files.list(dir).orEmpty()) files.remove("$dir${files.separator}$n")
                key = primitives.randomBytes(32)
                val nonce = primitives.randomBytes(OfflineStore.NONCE)
                val sealed = concat(byteArrayOf(HEADER_VERSION.toByte()), nonce, primitives.aesGcmSeal(wrapKey, nonce, key, aad))
                files.write("$header${OfflineStore.BESIDE}", sealed)
                files.rename("$header${OfflineStore.BESIDE}", header)
            }
            return OfflineStore(files, primitives, dir, account, key, clock).also { it.load() }
        } finally {
            wrapKey.fill(0)
        }
    }

    /** The steps of a [wipe], for a test to stop it where a crash would. */
    internal enum class WipeStep { MARKED, FILES, DIRECTORY }

    /** Removes [account]'s store: every file, then its directory, then its id. Nothing where it has none. */
    fun wipe(account: String) = wipe(account) {}

    internal fun wipe(account: String, interrupt: (WipeStep) -> Unit) {
        val record = records()[account] ?: return
        if (!record.wiping) write(records() + (account to record.copy(wiping = true)))
        interrupt(WipeStep.MARKED)
        val dir = directory(record.id)
        for (n in files.list(dir).orEmpty()) files.remove("$dir${files.separator}$n")
        interrupt(WipeStep.FILES)
        files.removeDirectory(dir)
        interrupt(WipeStep.DIRECTORY)
        write(records() - account)
    }

    /** Finishes every wipe a crash interrupted: at each start, before an account opens. */
    fun finishWipes() {
        for ((account, r) in records()) if (r.wiping) wipe(account)
    }

    /** Whether [account] has a store on this device. */
    fun has(account: String): Boolean = records()[account]?.wiping == false

    private fun directory(id: String) = "$root${files.separator}$id"

    private fun openHeader(bytes: ByteArray, wrapKey: ByteArray, aad: ByteArray): ByteArray? {
        if (bytes.size < 1 + OfflineStore.NONCE + OfflineStore.TAG || bytes[0].toInt() != HEADER_VERSION) return null
        return runCatching {
            primitives.aesGcmOpen(wrapKey, bytes.copyOfRange(1, 1 + OfflineStore.NONCE), bytes.copyOfRange(1 + OfflineStore.NONCE, bytes.size), aad)
        }.getOrNull()?.takeIf { it.size == 32 }
    }

    internal fun records(): Map<String, Record> =
        runCatching { store.read(KEY)?.let { JSON.decodeFromString<Map<String, Record>>(it) } }.getOrNull().orEmpty()

    private fun write(records: Map<String, Record>) {
        if (records.isEmpty()) store.remove(KEY) else store.write(KEY, JSON.encodeToString(records))
    }

    internal companion object {
        const val KEY = "veyq.offline-stores"
        const val HEADER_VERSION = 1
        val WRAP_INFO = "veyq/v1/offline-store".encodeToByteArray()
        val HEADER_AAD = "veyq/offline/v1/header".encodeToByteArray()
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
