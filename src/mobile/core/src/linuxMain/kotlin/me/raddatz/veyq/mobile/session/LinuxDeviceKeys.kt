@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.secret.veyq_secret
import me.raddatz.veyq.mobile.secret.veyq_secret_clear
import me.raddatz.veyq.mobile.secret.veyq_secret_free
import me.raddatz.veyq.mobile.secret.veyq_secret_load
import me.raddatz.veyq.mobile.secret.veyq_secret_lookup
import me.raddatz.veyq.mobile.secret.veyq_secret_service
import me.raddatz.veyq.mobile.secret.veyq_secret_store

/**
 * [DeviceKeys] on Linux (ADR 0127's Consequences): everything is an item of the Secret Service's
 * default collection under the schema `me.raddatz.veyq.DeviceKey`, told apart by its attribute `alias` — each bind key,
 * the one unlock key and each small secret. HMAC and AES-GCM run in libsodium, so a key's bytes are in memory while in
 * use: Apple's level for the bind key.
 *
 * **The one level is [StaySignedIn.LOGIN]** (owner 2026-11-11; ADR 0123 decision 8): the Secret Service has no presence check. Its
 * collection is unlocked by the desktop sign-in, and that login session is the authentication — the web's level. So
 * nothing here prompts, [DeviceAuthFailed] is never `cancelled`, and [StaySignedIn.PRESENCE] is neither offered nor
 * kept: a seal or an open at it is refused (`DeviceAuthFailed(false)`), never made at the login level instead.
 *
 * libsecret is loaded at run time (`dlopen("libsecret-1.so.0")`, `core/secret/veyq_secret.h`), never linked. Where it is
 * absent, where no Secret Service answers on the session bus, or where the collection stays locked, creating a key or
 * keeping a secret raises `DeviceAuthFailed(false)`, a read finds nothing and a delete does nothing: the app then works
 * as without a store (ADR 0127 decision 4), and [secretServiceAvailable] says so beforehand.
 */
class LinuxDeviceKeys internal constructor(
    private val service: SecretService?,
    private val p: Primitives,
    /** Prefixed to every item's alias: empty for the app's keys; a test's own, so its items never meet another's. */
    private val namespace: String,
) : DeviceKeys {
    /** The app's keys, in the default collection of the session's Secret Service. */
    constructor() : this(SecretService.system, LibsodiumPrimitives, "")

    /**
     * Whether a Secret Service answers: libsecret loaded and a session opened with the service on the session bus (no
     * item read, so a locked collection is not asked to open). Where it is false nothing is offered after a sign-in,
     * since neither the unlock key nor the password trust's bind key could be kept. A locked collection the person does
     * not open later still fails the enrolment, which leaves the trust it had.
     */
    val secretServiceAvailable: Boolean get() = service?.answers() == true

    /** The login level (the collection opens with the desktop sign-in; no presence check, owner 2026-11-11), or none without a service. */
    override val staySignedIn: Set<StaySignedIn>
        get() = if (secretServiceAvailable) setOf(StaySignedIn.LOGIN) else emptySet()

    override fun createBindKey(alias: String) {
        val key = p.randomBytes(64)
        try {
            store(BIND + alias, "veyq device key", b64(key))
        } finally {
            key.fill(0)
        }
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val key = lookup(BIND + alias)?.let { runCatching { unb64(it) }.getOrNull() } ?: return null
        return try { p.hmacSha256(key, message) } finally { key.fill(0) }
    }

    override fun deleteBindKey(alias: String) = clear(BIND + alias)

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray> {
        loginOnly(level)
        val key = withContext(Dispatchers.Default) { unlockKey(create = true) }
        return try {
            val iv = p.randomBytes(12)
            iv to p.aesGcmSeal(key, iv, plaintext, aad)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray {
        loginOnly(level)
        val key = withContext(Dispatchers.Default) { unlockKey(create = false) }
        return try {
            p.aesGcmOpen(key, iv, ciphertext, aad)
        } catch (e: Exception) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        } finally {
            key.fill(0)
        }
    }

    /** The unlock key's bytes (the caller clears them), created on first use when [create]. */
    private fun unlockKey(create: Boolean): ByteArray {
        val service = service ?: throw DeviceAuthFailed(cancelled = false, detail = "no libsecret")
        val kept = try {
            service.lookup(namespace + UNLOCK)
        } catch (e: SecretServiceFailure) {
            throw DeviceAuthFailed(cancelled = false, detail = "unlock key not read: ${e.message}")
        }
        if (kept != null) {
            val key = runCatching { unb64(kept) }.getOrNull()
            if (key?.size != 32) throw DeviceAuthFailed(cancelled = false, detail = "unlock key unreadable")
            return key
        }
        if (!create) throw DeviceAuthFailed(cancelled = false, detail = "no unlock key")
        val key = p.randomBytes(32)
        try {
            store(UNLOCK, "veyq unlock key", b64(key))
        } catch (e: DeviceAuthFailed) {
            key.fill(0)
            throw e
        }
        return key
    }

    /** The Secret Service keeps the login level's key alone: another is refused, never served by it. */
    private fun loginOnly(level: StaySignedIn) {
        if (level != StaySignedIn.LOGIN) throw DeviceAuthFailed(cancelled = false, detail = "the Secret Service has no $level level")
    }

    override fun deleteUnlockKey(level: StaySignedIn) {
        if (level == StaySignedIn.LOGIN) clear(UNLOCK)
    }

    override fun putSecret(alias: String, value: String) = store(SECRET + alias, "veyq sign-in token", value)

    override fun secret(alias: String): String? = lookup(SECRET + alias)

    override fun deleteSecret(alias: String) = clear(SECRET + alias)

    /** Keeps [value] as the item [alias]; any failure — no libsecret, no service, a locked collection — is the trust's. */
    private fun store(alias: String, label: String, value: String) {
        val service = service ?: throw DeviceAuthFailed(cancelled = false, detail = "no libsecret")
        try {
            service.store(namespace + alias, label, value)
        } catch (e: SecretServiceFailure) {
            throw DeviceAuthFailed(cancelled = false, detail = "not kept: ${e.message}")
        }
    }

    /** The item [alias]'s value; nothing where there is none or none can be read. */
    private fun lookup(alias: String): String? = service?.let { runCatching { it.lookup(namespace + alias) }.getOrNull() }

    /** Removes the item [alias]. A delete never fails a flow (sign-out, stop trusting); one that could not is left. */
    private fun clear(alias: String) {
        service?.let { runCatching { it.clear(namespace + alias) } }
    }

    internal companion object {
        const val BIND = "bind/"
        const val SECRET = "secret/"
        const val UNLOCK = "unlock"
    }
}

/** A libsecret call that failed: its GError's message. */
internal class SecretServiceFailure(message: String) : Exception(message)

/** libsecret, loaded by its soname: the items of the schema `me.raddatz.veyq.DeviceKey` in the default collection. */
internal class SecretService private constructor(private val lib: CPointer<veyq_secret>) {
    fun answers(): Boolean = call { err -> veyq_secret_service(lib, err, ERR) } == null

    fun store(alias: String, label: String, value: String) {
        call { err -> veyq_secret_store(lib, alias, label, value, err, ERR) }?.let { throw SecretServiceFailure(it) }
    }

    fun lookup(alias: String): String? = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val err = allocArray<ByteVar>(ERR)
        if (veyq_secret_lookup(lib, alias, out.ptr, err, ERR) != 0) throw SecretServiceFailure(err.toKString())
        val value = out.value ?: return@memScoped null
        try { value.toKString() } finally { veyq_secret_free(lib, value) }
    }

    fun clear(alias: String) {
        call { err -> veyq_secret_clear(lib, alias, err, ERR) }?.let { throw SecretServiceFailure(it) }
    }

    /** Runs [block] with an error buffer: null on success, else the error's message. */
    private inline fun call(block: (CPointer<ByteVar>) -> Int): String? = memScoped {
        val err = allocArray<ByteVar>(ERR)
        if (block(err) == 0) null else err.toKString()
    }

    companion object {
        private const val ERR = 512

        /** libsecret as the system has it, loaded once; null where it is absent (or lacks a function). */
        val system: SecretService? by lazy { load(SONAME) }

        const val SONAME = "libsecret-1.so.0"

        /** libsecret under [soname], or null — the soname a test may name otherwise, to play the absent library. */
        fun load(soname: String): SecretService? = memScoped {
            val err = allocArray<ByteVar>(ERR)
            veyq_secret_load(soname, err, ERR)?.let { SecretService(it) }
        }
    }
}
