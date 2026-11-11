@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.wincred.veyq_console_window
import me.raddatz.veyq.mobile.wincred.veyq_dpapi
import me.raddatz.veyq.mobile.wincred.veyq_dpapi_free
import me.raddatz.veyq.mobile.wincred.veyq_ngc_create_key
import me.raddatz.veyq.mobile.wincred.veyq_ngc_crypt
import me.raddatz.veyq.mobile.wincred.veyq_ngc_default_key_length
import me.raddatz.veyq.mobile.wincred.veyq_ngc_delete_key
import me.raddatz.veyq.mobile.wincred.veyq_ngc_free
import me.raddatz.veyq.mobile.wincred.veyq_ngc_open_key
import me.raddatz.veyq.mobile.wincred.veyq_ngc_open_provider
import me.raddatz.veyq.mobile.wincred.veyq_ngc_require_gesture
import me.raddatz.veyq.mobile.wincred.veyq_ngc_ui
import me.raddatz.veyq.mobile.wincred.veyq_user_sid
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [DeviceKeys] on Windows (the CR device-trust-on-linux-and-windows, D1). "Stay signed in" uses the strongest the device
 * has (owner 2026-11-11):
 *
 *  - **bind keys and secrets: DPAPI** for the current user, never with UI. The device store keeps the protected blobs;
 *    HMAC runs in libsodium, so a key's bytes are in memory while in use — Apple's level.
 *  - **the unlock key: Windows Hello where it is set up.** 32 random bytes, encrypted under the public half of an RSA key
 *    in the Microsoft Passport Key Storage Provider (no prompt); opening decrypts them there, which Windows Hello asks
 *    the person for, with [window] (the host's HWND) as the prompt's owner and [prompt] as its words. Windows refuses
 *    the private key's use without Hello: enforced by the key, not by a dialog.
 *  - **where Hello is not set up, the unlock key is DPAPI's** too: no presence check, the Windows sign-in is the
 *    authentication (Linux's and the web's level). [helloAvailable] says which a new key would take, for the switch's
 *    label. Which one a key took is kept beside it (`hello` / `dpapi`), so an open takes the path its seal took; a
 *    Hello set up later applies from the next enrolment.
 *
 * An opened unlock key is held in memory for 15 s, so one prompt at a start opens every remembered account (Android's
 * and Apple's windows). A cancelled prompt is `DeviceAuthFailed(cancelled = true)`, anything else `false` ([authFailure]).
 */
class WindowsDeviceKeys internal constructor(
    private val store: DeviceStore,
    private val p: Primitives,
    private val hello: UnlockGuard,
    private val dpapi: UnlockGuard,
    private val helloSetUp: () -> Boolean,
    private val time: TimeSource,
) : DeviceKeys {
    /** The app's keys: [window] the host's top-level HWND (0: the prompt has no owner), [prompt] the prompt's words. */
    constructor(store: DeviceStore, window: Long, prompt: String) : this(store, window, prompt, UNLOCK_KEY_NAME)

    internal constructor(store: DeviceStore, window: Long, prompt: String, helloKeyName: String) : this(
        store, LibsodiumPrimitives, PassportGuard(helloKeyName, window), DpapiGuard(UNLOCK_ENTROPY), Hello::available,
        TimeSource.Monotonic,
    ) {
        this.prompt = prompt
    }

    /** The words Windows Hello's prompt shows (its "Use Context"); the host may change them with the language. */
    var prompt: String = ""
        set(value) {
            field = value
            (hello as? PassportGuard)?.prompt = value
        }

    /** Whether a new unlock key would be Windows Hello's: Hello is set up for this user (the switch's label, D5). */
    val helloAvailable: Boolean get() = helloSetUp()

    /** Hello's where it is set up, else DPAPI's, which the Windows sign-in opens (Q2). DPAPI is always there. */
    override val staySignedIn: StaySignedIn
        get() = if (helloAvailable) StaySignedIn.PRESENCE else StaySignedIn.LOGIN

    /** The path the present unlock key took (`hello`, `dpapi`), or null when there is none. */
    val unlockPath: String? get() = store.read(UNLOCK_PATH)

    private val unlock = Mutex()
    private var opened: ByteArray? = null
    private var openedAt: TimeMark? = null

    override fun createBindKey(alias: String) {
        val key = p.randomBytes(64)
        try {
            store.write(BIND + alias, b64(Dpapi.protect(key, BIND_ENTROPY)))
        } finally {
            key.fill(0)
        }
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val blob = store.read(BIND + alias) ?: return null
        val key = runCatching { Dpapi.unprotect(unb64(blob), BIND_ENTROPY) }.getOrNull() ?: return null
        return try { p.hmacSha256(key, message) } finally { key.fill(0) }
    }

    override fun deleteBindKey(alias: String) = store.remove(BIND + alias)

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

    /** The unlock key's bytes (a copy the caller clears): held ones within the window, else opened (or created). */
    private suspend fun unlockKey(create: Boolean): ByteArray = unlock.withLock {
        held()?.let { return@withLock it }
        val blob = store.read(UNLOCK)
        val path = store.read(UNLOCK_PATH)
        val key = if (blob == null || path == null) {
            if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no unlock key")
            val guard = if (helloSetUp()) hello else dpapi
            val key = p.randomBytes(32)
            val sealed = withContext(Dispatchers.Default) { guard.protect(key) }
            store.write(UNLOCK, b64(sealed))
            store.write(UNLOCK_PATH, guard.path)
            key
        } else {
            val guard = when (path) {
                hello.path -> hello
                dpapi.path -> dpapi
                else -> throw DeviceAuthFailed(cancelled = false, detail = "unknown unlock path $path")
            }
            val key = withContext(Dispatchers.Default) { guard.unprotect(unb64(blob)) }
            if (key.size != 32) throw DeviceAuthFailed(cancelled = false, detail = "unlock key of ${key.size} bytes")
            key
        }
        hold(key)
        key
    }

    private fun held(): ByteArray? {
        val key = opened ?: return null
        if (openedAt?.let { it.elapsedNow() < WINDOW } != true) {
            forgetHeld()
            return null
        }
        return key.copyOf()
    }

    private fun hold(key: ByteArray) {
        forgetHeld()
        opened = key.copyOf()
        openedAt = time.markNow()
    }

    private fun forgetHeld() {
        opened?.fill(0)
        opened = null
        openedAt = null
    }

    override fun deleteUnlockKey() {
        val path = store.read(UNLOCK_PATH)
        store.remove(UNLOCK)
        store.remove(UNLOCK_PATH)
        forgetHeld()
        if (path == hello.path) runCatching { hello.delete() }
    }

    override fun putSecret(alias: String, value: String) =
        store.write(SECRET + alias, b64(Dpapi.protect(value.encodeToByteArray(), SECRET_ENTROPY)))

    override fun secret(alias: String): String? {
        val blob = store.read(SECRET + alias) ?: return null
        return runCatching { Dpapi.unprotect(unb64(blob), SECRET_ENTROPY).decodeToString() }.getOrNull()
    }

    override fun deleteSecret(alias: String) = store.remove(SECRET + alias)

    internal companion object {
        /** How long an opened unlock key is held: one prompt per start (Android's and Apple's 15 s). */
        val WINDOW = 15.seconds
        /** The app's Hello key, below `<SID>//veyq//` in the Passport provider. */
        const val UNLOCK_KEY_NAME = "unlock"
        const val BIND = "veyq.wincred.bind."
        const val SECRET = "veyq.wincred.secret."
        const val UNLOCK = "veyq.wincred.unlock"
        const val UNLOCK_PATH = "veyq.wincred.unlock.path"
        // DPAPI's entropy per use, so one blob is never accepted as another's
        val BIND_ENTROPY = "veyq/wincred/bind".encodeToByteArray()
        val SECRET_ENTROPY = "veyq/wincred/secret".encodeToByteArray()
        val UNLOCK_ENTROPY = "veyq/wincred/unlock".encodeToByteArray()
    }
}

/** The status codes D1 maps: a prompt the person cancelled, and the smart-card stack's word for the same. */
internal const val NTE_USER_CANCELLED = 0x80090036.toInt()
internal const val SCARD_W_CANCELLED_BY_USER = 0x8010006E.toInt()
internal const val NTE_NO_KEY = 0x8009000D.toInt()
internal const val NTE_BAD_KEYSET = 0x80090016.toInt()

/** A Windows status as the trust's failure (D1): a cancelled prompt is `cancelled`, anything else — a missing key
 *  included — is not. */
internal fun authFailure(status: Int, what: String): DeviceAuthFailed =
    DeviceAuthFailed(
        cancelled = status == NTE_USER_CANCELLED || status == SCARD_W_CANCELLED_BY_USER,
        detail = "$what: 0x${status.toUInt().toString(16).padStart(8, '0')}",
    )

/** One way the unlock key's bytes are kept: [protect] once at creation, [unprotect] (which may ask) at each open. Both
 *  throw [DeviceAuthFailed]. */
internal interface UnlockGuard {
    val path: String
    fun protect(key: ByteArray): ByteArray
    fun unprotect(blob: ByteArray): ByteArray
    fun delete()
}

internal class DpapiGuard(private val entropy: ByteArray) : UnlockGuard {
    override val path = "dpapi"
    override fun protect(key: ByteArray): ByteArray =
        try { Dpapi.protect(key, entropy) } catch (e: WindowsStatus) { throw authFailure(e.status, "DPAPI protect") }
    override fun unprotect(blob: ByteArray): ByteArray =
        try { Dpapi.unprotect(blob, entropy) } catch (e: WindowsStatus) { throw authFailure(e.status, "DPAPI unprotect") }
    override fun delete() {}
}

/**
 * The unlock key behind Windows Hello: an RSA-2048 key `<SID>//veyq//<name>` in the Passport provider, created with
 * every use asking (NgcCacheType AUTH_MANDATORY). The 32 bytes are encrypted under its public half (PKCS#1 v1.5, the
 * padding the provider offers) and decrypted by it, which Windows Hello prompts for, owned by [window].
 */
internal class PassportGuard(private val name: String, private val window: Long) : UnlockGuard {
    override val path = "hello"
    var prompt: String = ""

    private val keyName: String by lazy { "${Hello.sid() ?: throw DeviceAuthFailed(false, "no user SID")}//veyq//$name" }

    override fun protect(key: ByteArray): ByteArray = Ngc.withProvider { provider ->
        memScoped {
            val handle = alloc<ULongVar>()
            val created = veyq_ngc_create_key(provider, keyName.wcstr, window.toULong(), prompt.wcstr, handle.ptr)
            if (created != 0) throw authFailure(created, "Passport key creation")
            try { Ngc.crypt(handle.value, decrypt = false, key) } finally { veyq_ngc_free(handle.value) }
        }
    }

    override fun unprotect(blob: ByteArray): ByteArray = Ngc.withProvider { provider ->
        memScoped {
            val handle = alloc<ULongVar>()
            val status = veyq_ngc_open_key(provider, keyName.wcstr, 0, handle.ptr)
            if (status != 0) throw authFailure(status, "Passport key open")
            try {
                val ui = veyq_ngc_ui(handle.value, window.toULong(), prompt.wcstr)
                if (ui != 0) throw authFailure(ui, "Passport prompt owner")
                veyq_ngc_require_gesture(handle.value) // where refused, the key's AUTH_MANDATORY still asks
                Ngc.crypt(handle.value, decrypt = true, blob)
            } finally {
                veyq_ngc_free(handle.value)
            }
        }
    }

    override fun delete() {
        Ngc.withProvider { provider ->
            memScoped {
                val handle = alloc<ULongVar>()
                if (veyq_ngc_open_key(provider, keyName.wcstr, 1, handle.ptr) == 0) veyq_ngc_delete_key(handle.value)
            }
        }
    }
}

/** Windows Hello for the current user: whether it is set up, and the user's SID that names its keys. */
internal object Hello {
    /**
     * Set up: Hello has a default decryption key for this user (cryptngc's `NgcGetDefaultDecryptionKeyName`, none
     * without a PIN) and the Passport provider opens. `dsregcmd`'s NgcSet is no test on a local account (the CR's R6).
     */
    fun available(): Boolean = runCatching {
        val sid = sid() ?: return false
        val length = memScoped {
            val n = alloc<IntVar>()
            if (veyq_ngc_default_key_length(sid.wcstr, n.ptr) != 0) return false
            n.value
        }
        length > 0 && Ngc.withProvider { true }
    }.getOrDefault(false)

    /** The current user's SID (`S-1-5-21-…`), or null. */
    fun sid(): String? = memScoped {
        val buf = allocArray<UShortVar>(SID_CAPACITY)
        val n = alloc<IntVar>()
        if (veyq_user_sid(buf, SID_CAPACITY, n.ptr) != 0) null else buf.toKStringFromUtf16()
    }

    /** The console's window, for a console process's prompt (the Hello probe); 0 where there is none. */
    fun consoleWindow(): Long = veyq_console_window().toLong()

    private const val SID_CAPACITY = 256
}

private object Ngc {
    fun <T> withProvider(block: (ULong) -> T): T = memScoped {
        val provider = alloc<ULongVar>()
        val status = veyq_ngc_open_provider(provider.ptr)
        if (status != 0) throw authFailure(status, "Passport provider")
        try { block(provider.value) } finally { veyq_ngc_free(provider.value) }
    }

    fun crypt(key: ULong, decrypt: Boolean, input: ByteArray): ByteArray = memScoped {
        val out = allocArray<UByteVar>(CAPACITY)
        val n = alloc<UIntVar>()
        val status = input.usePinned { pin ->
            veyq_ngc_crypt(key, if (decrypt) 1 else 0, pin.bytes(input.size), input.size.toUInt(), out, CAPACITY.toUInt(), n.ptr)
        }
        if (status != 0) throw authFailure(status, if (decrypt) "Passport decrypt" else "Passport encrypt")
        val bytes = out.reinterpret<ByteVar>().readBytes(n.value.toInt())
        for (i in 0 until CAPACITY) out[i] = 0u
        bytes
    }

    private const val CAPACITY = 512 // RSA-2048's 256 bytes, with room
}

/** A failed Windows call's status (HRESULT, SECURITY_STATUS or HRESULT_FROM_WIN32). */
internal class WindowsStatus(val status: Int, what: String) : Exception("$what: 0x${status.toUInt().toString(16)}")

/** DPAPI for the current user, never with UI; [entropy] binds a blob to its use. Throws [WindowsStatus]. */
internal object Dpapi {
    fun protect(data: ByteArray, entropy: ByteArray): ByteArray = call(1, data, entropy, "CryptProtectData")
    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray = call(0, blob, entropy, "CryptUnprotectData")

    private fun call(protect: Int, data: ByteArray, entropy: ByteArray, what: String): ByteArray = memScoped {
        val out = alloc<CPointerVar<UByteVar>>()
        val n = alloc<UIntVar>()
        val status = data.usePinned { d ->
            entropy.usePinned { e ->
                veyq_dpapi(protect, d.bytes(data.size), data.size.toUInt(), e.bytes(entropy.size), entropy.size.toUInt(), out.ptr, n.ptr)
            }
        }
        if (status != 0) throw WindowsStatus(status, what)
        val pointer = out.value ?: throw WindowsStatus(0, "$what gave no bytes")
        try { pointer.reinterpret<ByteVar>().readBytes(n.value.toInt()) } finally { veyq_dpapi_free(pointer, n.value) }
    }
}

/** A pinned array's first byte, or null for an empty one (the C side reads no byte of a zero length). */
private fun Pinned<ByteArray>.bytes(size: Int): CPointer<UByteVar>? = if (size == 0) null else addressOf(0).reinterpret()
