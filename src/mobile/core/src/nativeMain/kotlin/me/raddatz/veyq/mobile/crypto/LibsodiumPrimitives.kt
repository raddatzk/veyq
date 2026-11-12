@file:OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)

package me.raddatz.veyq.mobile.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import me.raddatz.veyq.mobile.sodium.crypto_aead_aes256gcm_decrypt
import me.raddatz.veyq.mobile.sodium.crypto_aead_aes256gcm_encrypt
import me.raddatz.veyq.mobile.sodium.crypto_aead_aes256gcm_is_available
import me.raddatz.veyq.mobile.sodium.crypto_auth_hmacsha256_final
import me.raddatz.veyq.mobile.sodium.crypto_auth_hmacsha256_init
import me.raddatz.veyq.mobile.sodium.crypto_auth_hmacsha256_state
import me.raddatz.veyq.mobile.sodium.crypto_auth_hmacsha256_update
import me.raddatz.veyq.mobile.sodium.crypto_hash_sha256
import me.raddatz.veyq.mobile.sodium.crypto_kdf_hkdf_sha256_expand
import me.raddatz.veyq.mobile.sodium.crypto_kdf_hkdf_sha256_extract
import me.raddatz.veyq.mobile.sodium.crypto_kem_mlkem768_dec
import me.raddatz.veyq.mobile.sodium.crypto_kem_mlkem768_enc_deterministic
import me.raddatz.veyq.mobile.sodium.crypto_kem_mlkem768_seed_keypair
import me.raddatz.veyq.mobile.sodium.crypto_pwhash
import me.raddatz.veyq.mobile.sodium.crypto_scalarmult
import me.raddatz.veyq.mobile.sodium.crypto_scalarmult_base
import me.raddatz.veyq.mobile.sodium.crypto_sign_detached
import me.raddatz.veyq.mobile.sodium.crypto_sign_seed_keypair
import me.raddatz.veyq.mobile.sodium.crypto_sign_verify_detached
import me.raddatz.veyq.mobile.sodium.randombytes_buf
import me.raddatz.veyq.mobile.sodium.sodium_init

/**
 * The native platforms' primitives (ADR 0123; Apple and Linux, ADR 0129): libsodium 1.0.22 through cinterop, built by
 * `core/sodium/build_libsodium.py`. Its AES-256-GCM needs the CPU's AES instructions: without them (a Raspberry Pi 4)
 * the object refuses to initialise, and so the app refuses to start (ADR 0123's Consequences, the core on Linux).
 * Every function here only moves bytes in and out of one libsodium call; what they compute is pinned by the vectors.
 */
object LibsodiumPrimitives : Primitives {
    private const val ARGON2ID13 = 2 // crypto_pwhash_ALG_ARGON2ID13
    private const val MLKEM768_PK = 1184
    private const val MLKEM768_SK = 2400
    private const val MLKEM768_CT = 1088

    init {
        check(sodium_init() >= 0) { "libsodium did not initialise" }
        check(crypto_aead_aes256gcm_is_available() == 1) { "this CPU has no AES-GCM support" }
    }

    /** One pinned view of every array for a C call; an empty array is passed as a one-byte buffer with length 0. */
    private fun <R> pinAll(arrays: List<UByteArray>, f: (List<kotlinx.cinterop.CPointer<UByteVar>>) -> R): R {
        val held = arrays.map { if (it.isEmpty()) UByteArray(1) else it }
        fun go(i: Int, acc: List<kotlinx.cinterop.CPointer<UByteVar>>): R =
            if (i == held.size) f(acc) else held[i].usePinned { go(i + 1, acc + it.addressOf(0)) }
        return go(0, emptyList())
    }

    private fun ByteArray.u() = asUByteArray()

    override fun randomBytes(n: Int): ByteArray {
        val out = UByteArray(n)
        if (n > 0) pinAll(listOf(out)) { (o) -> randombytes_buf(o, n.convert()) }
        return out.asByteArray()
    }

    override fun sha256(data: ByteArray): ByteArray {
        val out = UByteArray(32)
        pinAll(listOf(out, data.u())) { (o, d) -> crypto_hash_sha256(o, d, data.size.convert()) }
        return out.asByteArray()
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = memScoped {
        val state = alloc<crypto_auth_hmacsha256_state>()
        val out = UByteArray(32)
        pinAll(listOf(out, key.u(), data.u())) { (o, k, d) ->
            crypto_auth_hmacsha256_init(state.ptr, k, key.size.convert())
            crypto_auth_hmacsha256_update(state.ptr, d, data.size.convert())
            crypto_auth_hmacsha256_final(state.ptr, o)
        }
        out.asByteArray()
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = UByteArray(32)
        val out = UByteArray(length)
        pinAll(listOf(prk, salt.u(), ikm.u(), out, info.u())) { (p, s, i, o, ctx) ->
            check(crypto_kdf_hkdf_sha256_extract(p, s, salt.size.convert(), i, ikm.size.convert()) == 0) { "HKDF extract failed" }
            check(crypto_kdf_hkdf_sha256_expand(o, length.convert(), ctx.reinterpret(), info.size.convert(), p) == 0) { "HKDF expand failed" }
        }
        return out.asByteArray()
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        val out = UByteArray(plaintext.size + 16)
        memScoped {
            val len = alloc<ULongVar>()
            pinAll(listOf(out, plaintext.u(), aad.u(), nonce.u(), key.u())) { (c, m, ad, n, k) ->
                check(crypto_aead_aes256gcm_encrypt(c, len.ptr, m, plaintext.size.convert(), ad, aad.size.convert(), null, n, k) == 0) { "AES-GCM failed" }
            }
        }
        return out.asByteArray()
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        if (ciphertextAndTag.size < 16) throw OpenRefused("aead")
        val out = UByteArray(ciphertextAndTag.size - 16)
        val ok = memScoped {
            val len = alloc<ULongVar>()
            pinAll(listOf(out, ciphertextAndTag.u(), aad.u(), nonce.u(), key.u())) { (m, c, ad, n, k) ->
                crypto_aead_aes256gcm_decrypt(m, len.ptr, null, c, ciphertextAndTag.size.convert(), ad, aad.size.convert(), n, k) == 0
            }
        }
        if (!ok) throw OpenRefused("aead")
        return out.asByteArray()
    }

    override fun argon2id(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, length: Int): ByteArray {
        require(p == 1) { "libsodium's Argon2id runs with one lane; the suite uses p=1" }
        require(salt.size == 16) { "an Argon2id salt is 16 bytes" }
        val out = UByteArray(length)
        pinAll(listOf(out, password.u(), salt.u())) { (o, pw, s) ->
            check(
                crypto_pwhash(o, length.convert(), pw.reinterpret(), password.size.convert(), s, t.convert(), (mKiB.toLong() * 1024).convert(), ARGON2ID13) == 0,
            ) { "Argon2id failed (out of memory?)" }
        }
        return out.asByteArray()
    }

    private fun signKeyPair(seed: ByteArray): Pair<UByteArray, UByteArray> {
        require(seed.size == 32) { "an Ed25519 seed is 32 bytes" }
        val pk = UByteArray(32)
        val sk = UByteArray(64)
        pinAll(listOf(pk, sk, seed.u())) { (p, s, sd) -> crypto_sign_seed_keypair(p, s, sd) }
        return pk to sk
    }

    override fun ed25519Public(seed: ByteArray): ByteArray = signKeyPair(seed).first.asByteArray()

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val (_, sk) = signKeyPair(seed)
        val sig = UByteArray(64)
        pinAll(listOf(sig, message.u(), sk)) { (s, m, k) -> crypto_sign_detached(s, null, m, message.size.convert(), k) }
        sk.fill(0u)
        return sig.asByteArray()
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return pinAll(listOf(signature.u(), message.u(), publicKey.u())) { (s, m, k) -> crypto_sign_verify_detached(s, m, message.size.convert(), k) == 0 }
    }

    override fun x25519(scalar: ByteArray, u: ByteArray?): ByteArray {
        require(scalar.size == 32) { "an X25519 scalar is 32 bytes" }
        val out = UByteArray(32)
        if (u == null) {
            pinAll(listOf(out, scalar.u())) { (q, n) -> crypto_scalarmult_base(q, n) }
        } else {
            val ok = pinAll(listOf(out, scalar.u(), u.u())) { (q, n, p) -> crypto_scalarmult(q, n, p) == 0 }
            require(ok) { "X25519 produced the all-zero point" }
        }
        return out.asByteArray()
    }

    /** The decapsulation key handed back is libsodium's expanded secret key (2400 bytes). */
    override fun mlKem768KeyGen(d: ByteArray, z: ByteArray): Pair<ByteArray, ByteArray> {
        val pk = UByteArray(MLKEM768_PK)
        val sk = UByteArray(MLKEM768_SK)
        pinAll(listOf(pk, sk, concat(d, z).u())) { (p, s, seed) -> check(crypto_kem_mlkem768_seed_keypair(p, s, seed) == 0) { "ML-KEM keygen failed" } }
        return pk.asByteArray() to sk.asByteArray()
    }

    override fun mlKem768Encaps(encapsulationKey: ByteArray, m: ByteArray?): Pair<ByteArray, ByteArray> {
        val ct = UByteArray(MLKEM768_CT)
        val ss = UByteArray(32)
        pinAll(listOf(ct, ss, encapsulationKey.u(), (m ?: randomBytes(32)).u())) { (c, s, pk, seed) ->
            check(crypto_kem_mlkem768_enc_deterministic(c, s, pk, seed) == 0) { "ML-KEM encapsulation failed (malformed key?)" }
        }
        return ct.asByteArray() to ss.asByteArray()
    }

    override fun mlKem768Decaps(decapsulationKey: ByteArray, ciphertext: ByteArray): ByteArray {
        val ss = UByteArray(32)
        pinAll(listOf(ss, ciphertext.u(), decapsulationKey.u())) { (s, c, sk) -> check(crypto_kem_mlkem768_dec(s, c, sk) == 0) { "ML-KEM decapsulation failed" } }
        return ss.asByteArray()
    }
}
