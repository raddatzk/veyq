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
 * [DeviceKeys] on Windows (ADR 0129's Consequences; ADR 0123 decision 8). The person chooses the level (owner
 * 2026-11-11):
 *
 *  - **bind keys and secrets: DPAPI** for the current user, never with UI. The device store keeps the protected blobs;
 *    HMAC runs in libsodium, so a key's bytes are in memory while in use — Apple's level.
 *  - **the [StaySignedIn.PRESENCE] unlock key: Windows Hello**, offered only where it is set up. 32 random bytes,
 *    encrypted under the public half of an RSA key in the Microsoft Passport Key Storage Provider (creating that key asks
 *    for the PIN once); opening decrypts them there, which Windows Hello asks the person for, with [window] (the host's
 *    HWND) as the prompt's owner and [prompt] as its words. Windows refuses the private key's use without Hello: enforced
 *    by the key, not by a dialog. Without Hello a presence seal is refused, never weakened to DPAPI.
 *  - **the [StaySignedIn.LOGIN] unlock key: DPAPI**, always offered: no presence check, the Windows sign-in is the
 *    authentication (Linux's and the web's level).
 *
 * Each level's key is kept apart (`veyq.wincred.unlock.presence` / `.login`); an opened key is held in memory for 15 s
 * per level, so one prompt at a start opens every account remembered at PRESENCE (Android's and Apple's windows). A
 * cancelled prompt is `DeviceAuthFailed(cancelled = true)`, anything else `false` ([authFailure]).
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

    /** Whether Windows Hello is set up for this user: the presence level is offered only then (ADR 0123 decision 8). */
    val helloAvailable: Boolean get() = helloSetUp()

    /** Both where Hello is set up, else DPAPI's alone, which the Windows sign-in opens. DPAPI is always there. */
    override val staySignedIn: Set<StaySignedIn>
        get() = if (helloAvailable) setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN) else setOf(StaySignedIn.LOGIN)

    /** Whether [level]'s unlock key exists (for the probe and the tests). */
    fun hasUnlockKey(level: StaySignedIn): Boolean = store.read(unlockName(level)) != null

    private fun guard(level: StaySignedIn): UnlockGuard = when (level) {
        StaySignedIn.PRESENCE -> hello
        StaySignedIn.LOGIN -> dpapi
    }

    private val unlock = Mutex()
    private val opened = HashMap<StaySignedIn, Pair<ByteArray, TimeMark>>()

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

    /**
     * [level]'s unlock key bytes (a copy the caller clears): held ones within the window, else opened (or created). A
     * presence key is created only where Hello is set up; one created before Hello was turned off still opens through
     * Hello (which then refuses it), never through DPAPI.
     */
    private suspend fun unlockKey(level: StaySignedIn, create: Boolean): ByteArray = unlock.withLock {
        held(level)?.let { return@withLock it }
        val guard = guard(level)
        val blob = store.read(unlockName(level))
        val key = if (blob == null) {
            if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no $level unlock key")
            if (level == StaySignedIn.PRESENCE && !helloSetUp()) throw DeviceAuthFailed(cancelled = false, detail = "Windows Hello is not set up")
            val key = p.randomBytes(32)
            val sealed = withContext(Dispatchers.Default) { guard.protect(key) }
            store.write(unlockName(level), b64(sealed))
            key
        } else {
            val key = withContext(Dispatchers.Default) { guard.unprotect(unb64(blob)) }
            if (key.size != 32) throw DeviceAuthFailed(cancelled = false, detail = "unlock key of ${key.size} bytes")
            key
        }
        hold(level, key)
        key
    }

    private fun held(level: StaySignedIn): ByteArray? {
        val (key, at) = opened[level] ?: return null
        if (at.elapsedNow() >= WINDOW) {
            forgetHeld(level)
            return null
        }
        return key.copyOf()
    }

    private fun hold(level: StaySignedIn, key: ByteArray) {
        forgetHeld(level)
        opened[level] = key.copyOf() to time.markNow()
    }

    private fun forgetHeld(level: StaySignedIn) {
        opened.remove(level)?.first?.fill(0)
    }

    override fun deleteUnlockKey(level: StaySignedIn) {
        val had = store.read(unlockName(level)) != null
        store.remove(unlockName(level))
        forgetHeld(level)
        if (had && level == StaySignedIn.PRESENCE) runCatching { hello.delete() }
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
        const val UNLOCK = "veyq.wincred.unlock."

        /** Where [level]'s protected unlock key is kept in the device store. */
        fun unlockName(level: StaySignedIn) = UNLOCK + level.name.lowercase()
        // DPAPI's entropy per use, so one blob is never accepted as another's
        val BIND_ENTROPY = "veyq/wincred/bind".encodeToByteArray()
        val SECRET_ENTROPY = "veyq/wincred/secret".encodeToByteArray()
        val UNLOCK_ENTROPY = "veyq/wincred/unlock".encodeToByteArray()
    }
}

/** The status codes mapped here: a prompt the person cancelled, and the smart-card stack's word for the same. */
internal const val NTE_USER_CANCELLED = 0x80090036.toInt()
internal const val SCARD_W_CANCELLED_BY_USER = 0x8010006E.toInt()
internal const val NTE_NO_KEY = 0x8009000D.toInt()
internal const val NTE_BAD_KEYSET = 0x80090016.toInt()

/** A Windows status as the trust's failure: a cancelled prompt is `cancelled`, anything else — a missing key
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
     * without a PIN) and the Passport provider opens. `dsregcmd`'s NgcSet is no test on a local account (it said NO with a PIN set, measured 2026-11-11).
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
