package me.raddatz.veyq.mobile.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The device's keys in the Android Keystore (ADR 0123 decision 8), none of which ever leaves it:
 *
 *  - a bind key per password-trusted account, an HMAC-SHA256 key whose MAC over `veyq/v1/device-bind` is the DEVICE
 *    wrap's factor;
 *  - one AES-256-GCM unlock key for the remembered accounts, usable for [VALIDITY_SECONDS] after the person
 *    authenticated with a strong biometric or the device's own code, and invalidated by a new biometric enrolment (a
 *    finger someone adds later does not open what was sealed before);
 *  - one AES-256-GCM key without authentication that seals the small secrets (a refresh token) kept in preferences.
 *
 * [host] is the activity a prompt is shown over, set while one is resumed.
 */
class KeystoreDeviceKeys(context: Context, private val host: () -> Activity?) : DeviceKeys {
    private val keyStore = KeyStore.getInstance(STORE).apply { load(null) }
    private val prefs = context.getSharedPreferences("veyq.device-secrets", Context.MODE_PRIVATE)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** The person's, where a secure lock screen is set (the unlock key needs one), else none: no unlock key can exist. */
    override val staySignedIn: StaySignedIn
        get() = if (keyguard?.isDeviceSecure == true) StaySignedIn.PRESENCE else StaySignedIn.NONE

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

    /** Runs [op] with the unlock key, asking the person once when its window has passed. */
    private suspend fun <T> withUnlockKey(create: Boolean, op: (SecretKey) -> T): T {
        val key = aesKey(UNLOCK, create, authenticated = true) ?: throw DeviceAuthFailed(cancelled = false, detail = "no unlock key")
        return try {
            try {
                op(key)
            } catch (e: UserNotAuthenticatedException) {
                authenticate()
                op(key)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteUnlockKey()
            throw DeviceAuthFailed(cancelled = false, detail = "unlock key invalidated")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        }
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
        withContext(Dispatchers.Default) {
            withUnlockKey(create = true) { key ->
                val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(aad) }
                val sealed = cipher.doFinal(plaintext)
                cipher.iv to sealed
            }
        }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            withUnlockKey(create = false) { key ->
                Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(ciphertext)
            }
        }

    override fun deleteUnlockKey() {
        if (keyStore.containsAlias(UNLOCK)) keyStore.deleteEntry(UNLOCK)
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
        const val UNLOCK = "veyq.unlock"
        const val SECRETS = "veyq.secrets"

        /** One authentication opens every remembered account of one start, and nothing after. */
        const val VALIDITY_SECONDS = 15
    }
}
