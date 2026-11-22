package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                noteWaiting(store, account, 0)
                key = primitives.randomBytes(32)
                writeHeader(dir, account, wrapKey, key)
            }
            return OfflineStore(files, primitives, dir, account, key, clock).also { it.load() }
        } finally {
            wrapKey.fill(0)
        }
    }

    /** [key] (a store's `K_off`) sealed under [wrapKey] as [dir]'s header, written beside it and renamed over it. */
    private fun writeHeader(dir: String, account: String, wrapKey: ByteArray, key: ByteArray) {
        val header = "$dir${files.separator}${OfflineStore.HEADER}"
        val aad = concat(HEADER_AAD, lp(account.encodeToByteArray()))
        val nonce = primitives.randomBytes(OfflineStore.NONCE)
        val sealed = concat(byteArrayOf(HEADER_VERSION.toByte()), nonce, primitives.aesGcmSeal(wrapKey, nonce, key, aad))
        files.write("$header${OfflineStore.BESIDE}", sealed)
        files.rename("$header${OfflineStore.BESIDE}", header)
    }

    /**
     * [account]'s store [from] carried to a Master Key rotation's [newMasterKey] (ADR 0153 decision 2, this device holding
     * both keys): a fresh `K_off`, the entries [carry] picks — the outbox — opened and sealed again under it in the same
     * directory, and the header rewritten under the new key, which is the commit. Everything else stays under the old
     * `K_off` and goes with the next open's sweep ([OfflineStore.sweep]), as ADR 0140 decision 2 wipes it: content kept from
     * before a rotation is never readable under the new key, nor the new content under the old one (ADR 0148's reason
     * not to re-wrap the old `K_off`). [from] is closed after. A crash before the header leaves the old store, whose
     * header the new Master Key does not open: it is wiped at the next open, as before.
     */
    internal suspend fun rekey(account: String, from: OfflineStore, newMasterKey: ByteArray, carry: (EntryKey) -> Boolean) {
        val record = records()[account]?.takeIf { !it.wiping } ?: throw IllegalStateException("no store to carry")
        val dir = directory(record.id)
        check(from.directory == dir) { "another store" }
        val key = primitives.randomBytes(32)
        val wrapKey = primitives.hkdfSha256(newMasterKey, ByteArray(0), WRAP_INFO, 32)
        val target = OfflineStore(files, primitives, dir, account, key.copyOf(), clock)
        try {
            from.carryInto(target, carry)
            writeHeader(dir, account, wrapKey, key)
        } finally {
            target.close()
            key.fill(0)
            wrapKey.fill(0)
        }
    }

    /** [open] as an account's reads use it ([AccountOffline]), its limit [defaultLimit] until the person sets one. */
    fun openFor(account: String, masterKey: ByteArray, server: String, defaultLimit: Long): AccountOffline =
        AccountOffline(open(account, masterKey), files, null, store, account, server, defaultLimit, clock)

    private class Shared(val offline: AccountOffline, var holders: Int)

    private val sharing = Mutex()
    private val shared = HashMap<String, Shared>()

    /**
     * [openFor] once per account in this process: a second connection of the same account (a sign-in while one is held,
     * until it retires) and the sync behind it use the store the first one opened — one manifest in memory, one writer —,
     * never a second instance over the same directory. Each holder's [AccountOffline.close] ends its use; the last one
     * closes the store. A wipe closes it whoever holds it ([AccountOffline.closeForWipe]).
     */
    suspend fun openShared(account: String, masterKey: ByteArray, server: String, defaultLimit: Long): AccountOffline = sharing.withLock {
        shared[account]?.let { it.holders++; return@withLock it.offline }
        lateinit var made: AccountOffline
        made = AccountOffline(open(account, masterKey), files, { holder, all -> released(account, holder, all) }, store, account, server, defaultLimit, clock)
        shared[account] = Shared(made, 1)
        made
    }

    /** One holder of [account]'s shared store is done with it ([all]: every holder, a wipe's): true where the store closes. */
    private suspend fun released(account: String, holder: AccountOffline, all: Boolean): Boolean = sharing.withLock {
        val s = shared[account]?.takeIf { it.offline === holder } ?: return@withLock true
        s.holders--
        if (s.holders > 0 && !all) return@withLock false
        shared.remove(account)
        true
    }

    /** The steps of a [wipe], for a test to stop it where a crash would. */
    @PlatformApi
    @HiddenFromObjC
    enum class WipeStep { MARKED, FILES, DIRECTORY }

    /** Removes [account]'s store: every file, then its directory, then its id. Nothing where it has none. */
    fun wipe(account: String) = wipe(account) {}

    @PlatformApi
    @HiddenFromObjC
    fun wipe(account: String, interrupt: (WipeStep) -> Unit) {
        val record = records()[account] ?: return
        if (!record.wiping) write(records() + (account to record.copy(wiping = true)))
        interrupt(WipeStep.MARKED)
        val dir = directory(record.id)
        for (n in files.list(dir).orEmpty()) files.remove("$dir${files.separator}$n")
        interrupt(WipeStep.FILES)
        files.removeDirectory(dir)
        interrupt(WipeStep.DIRECTORY)
        noteWaiting(store, account, 0)
        write(records() - account)
    }

    /** Finishes every wipe a crash interrupted: at each start, before an account opens. */
    fun finishWipes() {
        for ((account, r) in records()) if (r.wiping) wipe(account)
    }

    /** Whether [account] has a store on this device. */
    fun has(account: String): Boolean = records()[account]?.wiping == false

    /**
     * How many writes made offline [account]'s store holds, as its outbox last noted them (ADR 0153 decision 2): what the
     * unlock asks about before its *Stop trusting this device* (inbox #1052), where no Master Key opens the store to count.
     * A count only, beside the trusted record in the device store, never what a write is; 0 without a store.
     */
    fun waiting(account: String): Int = if (!has(account)) 0 else store.read(WAITING + account)?.toIntOrNull() ?: 0

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

    @PlatformApi
    @HiddenFromObjC
    companion object {
        /** The stores over [files] and [clock] of the caller's own (a test's volume, its clock): the constructor, opened. */
        fun of(root: String, store: DeviceStore, primitives: Primitives, files: DeviceFiles, clock: () -> Long) =
            OfflineStores(root, store, primitives, files, clock)

        const val KEY = "veyq.offline-stores"
        private const val WAITING = "veyq.offline-waiting."

        /** [count] writes noted as waiting in [account]'s store ([waiting]); none noted at 0. */
        internal fun noteWaiting(store: DeviceStore, account: String, count: Int) {
            if (count > 0) store.write(WAITING + account, count.toString()) else store.remove(WAITING + account)
        }
        /** The header's file name, the one a store's directory names in the clear (a test's). */
        const val HEADER_NAME = OfflineStore.HEADER
        const val HEADER_VERSION = 1
        val WRAP_INFO = "veyq/v1/offline-store".encodeToByteArray()
        val HEADER_AAD = "veyq/offline/v1/header".encodeToByteArray()
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
