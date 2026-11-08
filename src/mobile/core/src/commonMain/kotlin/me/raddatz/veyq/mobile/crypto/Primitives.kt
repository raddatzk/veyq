package me.raddatz.veyq.mobile.crypto

/**
 * The raw primitives of the v1 suite, supplied per platform (ADR 0123): BouncyCastle on the JVM and Android, the Apple
 * platforms' own on iOS and macOS. Everything built from them — the contexts, the envelopes, X-Wing's combiner, the
 * KEKs — lives once, in [V1], and is held to `contracts/crypto/vectors/v1.json` on every platform.
 *
 * SHA3-256 and SHAKE256 are not here: [V1] takes them from KotlinCrypto in common code, so no platform supplies them.
 */
interface Primitives {
    fun randomBytes(n: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** AES-256-GCM; the result is ciphertext ‖ 16-byte tag. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Throws [OpenRefused] when the tag does not verify. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray

    fun argon2id(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, length: Int): ByteArray

    fun ed25519Public(seed: ByteArray): ByteArray
    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** X25519(scalar, u); [u] null is the base point. */
    fun x25519(scalar: ByteArray, u: ByteArray?): ByteArray

    /** ML-KEM-768 `KeyGen_internal(d, z)`: the encapsulation key (1184 bytes) and an opaque decapsulation key. */
    fun mlKem768KeyGen(d: ByteArray, z: ByteArray): Pair<ByteArray, ByteArray>

    /** ML-KEM-768 encapsulation; [m] (32 bytes) makes it deterministic for the vectors, null draws it. Returns ct, ss. */
    fun mlKem768Encaps(encapsulationKey: ByteArray, m: ByteArray?): Pair<ByteArray, ByteArray>

    fun mlKem768Decaps(decapsulationKey: ByteArray, ciphertext: ByteArray): ByteArray
}

/** A ciphertext that does not open as the object it was asked for: wrong key, wrong slot, or tampered. */
class OpenRefused(val purpose: String) : Exception("open_refused: $purpose")

/** A context that does not describe an object: an unknown purpose, a missing field, a non-canonical id. */
class SealContextInvalid(detail: String) : IllegalArgumentException("seal_context_invalid: $detail")
