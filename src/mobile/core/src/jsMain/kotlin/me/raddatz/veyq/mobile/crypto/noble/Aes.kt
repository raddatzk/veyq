@file:JsModule("@noble/ciphers/aes.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

external interface Cipher {
    fun encrypt(plaintext: Uint8Array): Uint8Array

    /** Throws when the tag does not verify. */
    fun decrypt(ciphertext: Uint8Array): Uint8Array
}

/** AES-GCM; the result of `encrypt` is ciphertext ‖ 16-byte tag, as `Primitives.aesGcmSeal` returns it. */
external fun gcm(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array): Cipher
