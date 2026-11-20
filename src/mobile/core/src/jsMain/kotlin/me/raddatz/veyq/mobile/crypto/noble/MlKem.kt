@file:JsModule("@noble/post-quantum/ml-kem.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

external interface KemKeys {
    val publicKey: Uint8Array
    val secretKey: Uint8Array
}

external interface Encapsulated {
    val cipherText: Uint8Array
    val sharedSecret: Uint8Array
}

external interface MlKem {
    /** `KeyGen_internal(d, z)` over the 64-byte seed `d ‖ z`. */
    fun keygen(seed: Uint8Array): KemKeys

    /** Deterministic with a 32-byte [msg] (the vectors' `m`); undefined draws it. */
    fun encapsulate(publicKey: Uint8Array, msg: Uint8Array?): Encapsulated
    fun decapsulate(cipherText: Uint8Array, secretKey: Uint8Array): Uint8Array
}

external val ml_kem768: MlKem
