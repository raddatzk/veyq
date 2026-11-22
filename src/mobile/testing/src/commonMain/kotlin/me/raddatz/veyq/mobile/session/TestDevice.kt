package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.Primitives

/**
 * [DeviceKeys] in memory, for tests only: the platforms' keys are the Keystore's, the Keychain's, DPAPI's and Hello's,
 * which no test on a JVM reaches. It passes [DeviceKeysContract] as every store does, at both levels. Its extras, for
 * the flows' own tests: [authenticates] plays the person at the presence key's prompt; [prompts] counts how often they
 * were asked (the LOGIN key never asks); [staySignedIn] is what it offers. It lives in `src/testDevice`, compiled into
 * core's common tests and :bridge's JVM tests alike.
 */
class SoftwareDeviceKeys(private val p: Primitives) : DeviceKeys {
    private val bindKeys = HashMap<String, ByteArray>()
    private val unlockKeys = HashMap<StaySignedIn, ByteArray>()
    private val secrets = HashMap<String, String>()
    var authenticates = true
    var prompts = 0

    /** What this fake offers; a test sets another to see the offer follow it. A level outside it is refused. */
    override var staySignedIn: Set<StaySignedIn> = setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)

    override fun createBindKey(alias: String) { bindKeys[alias] = p.randomBytes(64) }
    override fun bindHmac(alias: String, message: ByteArray): ByteArray? = bindKeys[alias]?.let { p.hmacSha256(it, message) }
    override fun deleteBindKey(alias: String) { bindKeys.remove(alias) }

    private fun unlock(level: StaySignedIn): ByteArray {
        if (level == StaySignedIn.PRESENCE) {
            prompts++
            if (!authenticates) throw DeviceAuthFailed(cancelled = true)
        }
        return unlockKeys.getOrPut(level) { p.randomBytes(32) }
    }

    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray> {
        if (level !in staySignedIn) throw DeviceAuthFailed(cancelled = false, detail = "$level is not offered")
        val iv = p.randomBytes(12)
        return iv to p.aesGcmSeal(unlock(level), iv, plaintext, aad)
    }

    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray {
        if (level !in unlockKeys) throw DeviceAuthFailed(cancelled = false)
        return try {
            p.aesGcmOpen(unlock(level), iv, ciphertext, aad)
        } catch (e: DeviceAuthFailed) {
            throw e
        } catch (e: Exception) {
            throw DeviceAuthFailed(cancelled = false, detail = "does not open")
        }
    }

    override fun deleteUnlockKey(level: StaySignedIn) { unlockKeys.remove(level) }

    /** Whether [level]'s unlock key exists: for a flow's test that an unused one is gone. */
    fun hasUnlockKey(level: StaySignedIn): Boolean = level in unlockKeys

    override fun putSecret(alias: String, value: String) { secrets[alias] = value }
    override fun secret(alias: String): String? = secrets[alias]
    override fun deleteSecret(alias: String) { secrets.remove(alias) }
}

/** A device for a test: preferences and keys in memory. */
fun testDevice(p: Primitives, keys: DeviceKeys = SoftwareDeviceKeys(p), store: DeviceStore = MemoryDeviceStore()) =
    ThisDevice(store, keys, "test device", p)
