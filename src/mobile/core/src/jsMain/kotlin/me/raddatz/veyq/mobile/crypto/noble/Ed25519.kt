@file:JsModule("@noble/curves/ed25519.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

external interface Ed25519 {
    fun getPublicKey(secretKey: Uint8Array): Uint8Array
    fun sign(message: Uint8Array, secretKey: Uint8Array): Uint8Array
    fun verify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean
}

external interface X25519 {
    fun scalarMult(scalar: Uint8Array, u: Uint8Array): Uint8Array
    fun scalarMultBase(scalar: Uint8Array): Uint8Array
}

external val ed25519: Ed25519

external val x25519: X25519
