@file:JsModule("@noble/hashes/utils.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

/** The platform's CSPRNG (`crypto.getRandomValues`), as the web's `randomBytes` draws. */
external fun randomBytes(bytesLength: Int): Uint8Array
