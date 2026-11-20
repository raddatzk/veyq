@file:JsModule("@noble/hashes/argon2.js")

package me.raddatz.veyq.mobile.crypto.noble

import org.khronos.webgl.Uint8Array

/** noble's `ArgonOpts`: `t` passes, `m` KiB, `p` lanes, `dkLen` output bytes. */
external interface ArgonOpts {
    var t: Int
    var m: Int
    var p: Int
    var dkLen: Int
}

external fun argon2id(password: Uint8Array, salt: Uint8Array, opts: ArgonOpts): Uint8Array
