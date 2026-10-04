package com.veyq.core.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies Ed25519 signatures using the JDK provider — the only auth crypto the operator-blind server
 * runs (no content crypto; ADR 0003). The client registers a raw 32-byte Ed25519 public key; login
 * proves possession of the matching private key by signing a server nonce.
 */
object Ed25519Verifier {

    // Fixed X.509 SubjectPublicKeyInfo prefix for an Ed25519 public key (OID 1.3.101.112); the raw
    // 32-byte key is appended to build a key the JDK KeyFactory accepts.
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** True iff [signature] is a valid Ed25519 signature over [message] for the raw [publicKeyRaw]. Any
     *  malformed input (wrong key length, bad signature bytes) yields false — never an exception. */
    fun verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            if (publicKeyRaw.size != 32) {
                false
            } else {
                val key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(SPKI_PREFIX + publicKeyRaw))
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update(message)
                    verify(signature)
                }
            }
        } catch (e: Exception) {
            false
        }
}
