package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.Primitives

/**
 * [DeviceKeys] in memory, for tests only: the platforms' keys are the Keystore's and the Keychain's, which no test on
 * a JVM reaches. [authenticates] plays the person at the unlock key's prompt; [prompts] counts how often they were asked.
 */
class SoftwareDeviceKeys(private val p: Primitives) : DeviceKeys {
    val bindKeys = HashMap<String, ByteArray>()
    var unlockKey: ByteArray? = null
    val secrets = HashMap<String, String>()
    var authenticates = true
    var prompts = 0

    override fun createBindKey(alias: String) { bindKeys[alias] = p.randomBytes(64) }
    override fun bindHmac(alias: String, message: ByteArray): ByteArray? = bindKeys[alias]?.let { p.hmacSha256(it, message) }
    override fun deleteBindKey(alias: String) { bindKeys.remove(alias) }

    private fun unlock(): ByteArray {
        prompts++
        if (!authenticates) throw DeviceAuthFailed(cancelled = true)
        return unlockKey ?: p.randomBytes(32).also { unlockKey = it }
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = p.randomBytes(12)
        return iv to p.aesGcmSeal(unlock(), iv, plaintext, aad)
    }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (unlockKey == null) throw DeviceAuthFailed(cancelled = false)
        return p.aesGcmOpen(unlock(), iv, ciphertext, aad)
    }

    override fun deleteUnlockKey() { unlockKey = null }
    override fun putSecret(alias: String, value: String) { secrets[alias] = value }
    override fun secret(alias: String): String? = secrets[alias]
    override fun deleteSecret(alias: String) { secrets.remove(alias) }
}

/** A device for a test: preferences and keys in memory. */
fun testDevice(p: Primitives, keys: DeviceKeys = SoftwareDeviceKeys(p), store: DeviceStore = MemoryDeviceStore()) =
    ThisDevice(store, keys, "test device", p)
