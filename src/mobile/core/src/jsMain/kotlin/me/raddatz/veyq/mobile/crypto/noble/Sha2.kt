@file:JsModule("@noble/hashes/sha2.js")

package me.raddatz.veyq.mobile.crypto.noble

/** A noble hash (`CHash`): callable on bytes (`JsPrimitives.sha256`), and handed whole to [hmac] and [hkdf]. */
external interface CHash

external val sha256: CHash
