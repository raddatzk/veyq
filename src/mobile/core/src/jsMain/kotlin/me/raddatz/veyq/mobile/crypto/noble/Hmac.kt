@file:JsModule("@noble/hashes/hmac.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

external fun hmac(hash: CHash, key: Uint8Array, message: Uint8Array): Uint8Array
