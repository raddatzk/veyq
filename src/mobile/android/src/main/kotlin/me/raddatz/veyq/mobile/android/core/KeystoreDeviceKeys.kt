package me.raddatz.veyq.mobile.android.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.PinFactors
import me.raddatz.veyq.mobile.session.StaySignedIn

/**
 * The device's keys in the Android Keystore (ADR 0123 decision 8), none of which ever leaves it:
 *
 *  - a bind key per password-trusted account, an HMAC-SHA256 key whose MAC over `veyq/v1/device-bind` is the DEVICE
 *    wrap's factor;
 *  - an AES-256-GCM unlock key per level for the remembered accounts (ADR 0123 decision 8): the [StaySignedIn.PRESENCE] one usable for
 *    [VALIDITY_SECONDS] after the person authenticated with a strong biometric or the device's own code, and
 *    invalidated by a new biometric enrolment (a finger someone adds later does not open what was sealed before); the
 *    [StaySignedIn.LOGIN] one without user authentication, opened by the unlocked device itself;
 *  - one AES-256-GCM key without authentication that seals the small secrets (a refresh token) kept in preferences.
 *
 * [host] is the activity a prompt is shown over, set while one is resumed.
 */
class KeystoreDeviceKeys(context: Context, private val host: () -> Activity?) : DeviceKeys {
    private val keyStore = KeyStore.getInstance(STORE).apply { load(null) }
    private val prefs = context.getSharedPreferences("veyq.device-secrets", Context.MODE_PRIVATE)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /**
     * Both levels where a secure lock screen is set, else none. The presence key cannot exist without a lock screen; the
     * login key could, but its guarantee is that the device's own sign-in authenticates, and without a lock nothing does:
     * the Master Key would open for anyone holding the phone. The password trust stays offered there, as everywhere.
     */
    override val staySignedIn: Set<StaySignedIn>
        get() = if (keyguard?.isDeviceSecure == true) setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN) else emptySet()

    private fun unlockAlias(level: StaySignedIn) = when (level) {
        StaySignedIn.PRESENCE -> UNLOCK
        StaySignedIn.LOGIN -> UNLOCK_LOGIN
    }

    /**
     * A TV's PIN factor (ADR 0160 decision 2): one HMAC-SHA256 key per account, without user authentication (a TV has no
     * lock screen to bind it to), in StrongBox where the device has it, else the TEE. Offered only where the Keystore's keys
     * are hardware-backed — measured once per process on a probe key's [KeyInfo] security level (TEE, StrongBox, or a
     * secure level the platform does not name): a software Keystore's key is exportable by whoever runs code on the device,
     * and ADR 0160 decision 1 offers the level only where the device has a non-exportable hardware key. Null: not offered.
     * The core asks this only on a TV (`ThisDevice.offersPin`).
     */
    override val pinFactors: PinFactors? by lazy { if (hardwareBacked()) KeystorePinFactors() else null }

    private inner class KeystorePinFactors : PinFactors {
        override fun create(alias: String) {
            delete(alias)
            val key = pinKey(alias)
            // a key the hardware does not hold binds nothing: never kept, so no wrap is made over it
            if (!hardware(key)) {
                delete(alias)
                throw IllegalStateException("the Keystore holds this device's PIN key in software")
            }
        }

        override fun factor(alias: String): ByteArray? {
            val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
            return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(PinFactors.LABEL)
        }

        override fun delete(alias: String) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    /** An HMAC-SHA256 key [alias] without user authentication: StrongBox first, the TEE where the device has none. */
    private fun pinKey(alias: String): SecretKey {
        fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setIsStrongBoxBacked(strongBox).build())
        }.generateKey()
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    /** Whether [key] lives in the device's hardware (the TEE or StrongBox), never in software. */
    private fun hardware(key: SecretKey): Boolean = runCatching { securityLevel(key) in HARDWARE_LEVELS }.getOrDefault(false)

    /** Where the Keystore holds [key] (`KeyProperties.SECURITY_LEVEL_*`). */
    private fun securityLevel(key: SecretKey): Int =
        (SecretKeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo).securityLevel

    /** The probe: a PIN key made and asked where it lives, then deleted; its level (or why it failed) to logcat, never a key. */
    private fun hardwareBacked(): Boolean {
        val level = runCatching {
            try {
                securityLevel(pinKey(PIN_PROBE))
            } finally {
                if (keyStore.containsAlias(PIN_PROBE)) keyStore.deleteEntry(PIN_PROBE)
            }
        }
        android.util.Log.i("veyq-trust", "PIN factor's key level: ${level.getOrNull() ?: level.exceptionOrNull()?.javaClass?.simpleName}")
        return level.getOrNull() in HARDWARE_LEVELS
    }

    override fun createBindKey(alias: String) {
        deleteBindKey(alias)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        }.generateKey()
    }

    override fun bindHmac(alias: String, message: ByteArray): ByteArray? {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(message)
    }

    override fun deleteBindKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun aesKey(alias: String, create: Boolean, authenticated: Boolean): SecretKey? {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (authenticated) {
            spec.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(VALIDITY_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(true)
        }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec.build()) }.generateKey()
        } catch (e: Exception) {
            // No secure lock screen: a key that needs the person cannot exist on this device.
            throw DeviceAuthFailed(cancelled = false, detail = "no secure lock screen: ${e.message}")
        }
    }

    /** Runs [op] with [level]'s unlock key, asking the person once (at PRESENCE) when its window has passed. */
    private suspend fun <T> withUnlockKey(level: StaySignedIn, create: Boolean, op: (SecretKey) -> T): T {
        if (create && level !in staySignedIn) throw DeviceAuthFailed(cancelled = false, detail = "$level is not offered without a secure lock screen")
        val key = aesKey(unlockAlias(level), create, authenticated = level == StaySignedIn.PRESENCE)
            ?: throw DeviceAuthFailed(cancelled = false, detail = "no $level unlock key")
        return try {
            try {
                op(key)
            } catch (e: UserNotAuthenticatedException) {
                authenticate()
                op(key)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteUnlockKey(level)
            throw DeviceAuthFailed(cancelled = false, detail = "unlock key invalidated")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        }
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray> =
        withContext(Dispatchers.Default) {
            withUnlockKey(level, create = true) { key ->
                val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(aad) }
                val sealed = cipher.doFinal(plaintext)
                cipher.iv to sealed
            }
        }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray =
        withContext(Dispatchers.Default) {
            withUnlockKey(level, create = false) { key ->
                Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(ciphertext)
            }
        }

    override fun deleteUnlockKey(level: StaySignedIn) {
        val alias = unlockAlias(level)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /** The system's prompt over the current activity: a strong biometric or the device's code. */
    private suspend fun authenticate() = withContext(Dispatchers.Main) {
        val activity = host() ?: throw DeviceAuthFailed(cancelled = true, detail = "no activity to ask over")
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.unlock_prompt_title))
                .setSubtitle(activity.getString(R.string.unlock_prompt_subtitle))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
                .authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        if (cont.isActive) cont.resumeWithException(DeviceAuthFailed(cancelled, errString.toString()))
                    }
                })
        }
    }

    override fun putSecret(alias: String, value: String) {
        val key = aesKey(SECRETS, create = true, authenticated = false)!!
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(alias.toByteArray()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(alias, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun secret(alias: String): String? {
        val stored = prefs.getString(alias, null) ?: return null
        val key = aesKey(SECRETS, create = false, authenticated = false) ?: return null
        return runCatching {
            val b = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12)); updateAAD(alias.toByteArray())
            }
            String(cipher.doFinal(b, 12, b.size - 12))
        }.getOrNull()
    }

    override fun deleteSecret(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        /** The presence key's alias, the one unlock key there was before the levels: its seals still open. */
        const val UNLOCK = "veyq.unlock"
        const val UNLOCK_LOGIN = "veyq.unlock.login"
        const val SECRETS = "veyq.secrets"
        /** The PIN factor's probe key, made and deleted at once ([pinFactors]). */
        const val PIN_PROBE = "veyq.pin.probe"

        /** The levels a key is held in hardware at: the TEE, StrongBox, or a secure one the platform does not name. */
        val HARDWARE_LEVELS = setOf(
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_STRONGBOX, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
        )

        /** One authentication opens every remembered account of one start, and nothing after. */
        const val VALIDITY_SECONDS = 15
    }
}
