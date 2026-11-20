@file:JsModule("@noble/hashes/hkdf.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

external fun hkdf(hash: CHash, ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, length: Int): Uint8Array
