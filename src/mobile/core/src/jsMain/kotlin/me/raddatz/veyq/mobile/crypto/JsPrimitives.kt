package me.raddatz.veyq.mobile.crypto

import me.raddatz.veyq.mobile.crypto.noble.ArgonOpts
import me.raddatz.veyq.mobile.crypto.noble.argon2id as nobleArgon2id
import me.raddatz.veyq.mobile.crypto.noble.ed25519
import me.raddatz.veyq.mobile.crypto.noble.gcm
import me.raddatz.veyq.mobile.crypto.noble.hkdf
import me.raddatz.veyq.mobile.crypto.noble.hmac
import me.raddatz.veyq.mobile.crypto.noble.ml_kem768
import me.raddatz.veyq.mobile.crypto.noble.randomBytes as nobleRandomBytes
import me.raddatz.veyq.mobile.crypto.noble.sha256 as nobleSha256
import me.raddatz.veyq.mobile.crypto.noble.x25519 as nobleX25519
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * The web's primitives (ADR 0155 decision 3): the very noble libraries (`@noble/ciphers`, `hashes`, `curves`, `post-quantum`) the web's own suite calls (`@veyq/crypto`'s
 * `primitives.ts` and `v1.ts`), resolved from the pnpm lockfile, so one implementation of each ships in the web and the
 * suite's conformance runs over it (`@veyq/core-js`'s check). The bytes are shared, never copied: a Kotlin `ByteArray`
 * is an `Int8Array`, viewed as the `Uint8Array` noble takes, and noble's result viewed back.
 */
object JsPrimitives : Primitives {
    override fun randomBytes(n: Int): ByteArray = nobleRandomBytes(n).bytes()

    override fun sha256(data: ByteArray): ByteArray = nobleSha256.asDynamic()(data.u8()).unsafeCast<Uint8Array>().bytes()

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmac(nobleSha256, key.u8(), data.u8()).bytes()

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdf(nobleSha256, ikm.u8(), salt.u8(), info.u8(), length).bytes()

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        return gcm(key.u8(), nonce.u8(), aad.u8()).encrypt(plaintext.u8()).bytes()
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        return try {
            gcm(key.u8(), nonce.u8(), aad.u8()).decrypt(ciphertextAndTag.u8()).bytes()
        } catch (e: Throwable) {
            throw OpenRefused("aead")
        }
    }

    override fun argon2id(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, length: Int): ByteArray {
        val opts = js("{}").unsafeCast<ArgonOpts>().apply {
            this.t = t
            this.m = mKiB
            this.p = p
            this.dkLen = length
        }
        return nobleArgon2id(password.u8(), salt.u8(), opts).bytes()
    }

    override fun ed25519Public(seed: ByteArray): ByteArray = ed25519.getPublicKey(seed.u8()).bytes()

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray = ed25519.sign(message.u8(), seed.u8()).bytes()

    /** A malformed key or signature is a no, as BouncyCastle's and libsodium's are, never a throw. */
    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        ed25519.verify(signature.u8(), message.u8(), publicKey.u8())
    } catch (e: Throwable) {
        false
    }

    /** noble refuses the all-zero result (a low-order point), as BouncyCastle's `calculateAgreement` does. */
    override fun x25519(scalar: ByteArray, u: ByteArray?): ByteArray =
        (if (u == null) nobleX25519.scalarMultBase(scalar.u8()) else nobleX25519.scalarMult(scalar.u8(), u.u8())).bytes()

    /** The decapsulation key handed back is noble's expanded one (2400 bytes); opaque to [V1] on every platform. */
    override fun mlKem768KeyGen(d: ByteArray, z: ByteArray): Pair<ByteArray, ByteArray> {
        val keys = ml_kem768.keygen(concat(d, z).u8())
        return keys.publicKey.bytes() to keys.secretKey.bytes()
    }

    override fun mlKem768Encaps(encapsulationKey: ByteArray, m: ByteArray?): Pair<ByteArray, ByteArray> {
        val e = ml_kem768.encapsulate(encapsulationKey.u8(), m?.u8() ?: undefined)
        return e.cipherText.bytes() to e.sharedSecret.bytes()
    }

    override fun mlKem768Decaps(decapsulationKey: ByteArray, ciphertext: ByteArray): ByteArray =
        ml_kem768.decapsulate(ciphertext.u8(), decapsulationKey.u8()).bytes()
}

private fun ByteArray.u8(): Uint8Array {
    val a = unsafeCast<Int8Array>()
    return Uint8Array(a.buffer, a.byteOffset, a.length)
}

private fun Uint8Array.bytes(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()
