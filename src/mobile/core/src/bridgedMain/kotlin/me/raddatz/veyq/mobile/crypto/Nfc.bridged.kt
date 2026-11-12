@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import me.raddatz.veyq.mobile.utf8proc.UTF8PROC_COMPAT
import me.raddatz.veyq.mobile.utf8proc.UTF8PROC_COMPOSE
import me.raddatz.veyq.mobile.utf8proc.UTF8PROC_DECOMPOSE
import me.raddatz.veyq.mobile.utf8proc.UTF8PROC_STABLE
import me.raddatz.veyq.mobile.utf8proc.utf8proc_map
import platform.posix.free

// Neither Linux's libc nor Windows' has the normalization forms: utf8proc's (ADR 0123's Consequences, the core on Linux;
// ADR 0129 decision 5), built by core/utf8proc/build_utf8proc.py and held to Unicode's NormalizationTest.txt by
// NormalizationTest. The options are
// exactly those of utf8proc's own `utf8proc_NFC` / `utf8proc_NFKD`, but with the length given: those two read a
// NUL-terminated string and so would end a name at an embedded U+0000, which a name may carry (ArchiveNameTest).
actual fun nfc(s: String): String = normalize(s, UTF8PROC_STABLE or UTF8PROC_COMPOSE)

actual fun nfkd(s: String): String = normalize(s, UTF8PROC_STABLE or UTF8PROC_DECOMPOSE or UTF8PROC_COMPAT)

private fun normalize(s: String, options: UInt): String {
    if (s.isEmpty()) return s
    val bytes = s.encodeToByteArray()
    return memScoped {
        val out = alloc<CPointerVar<UByteVar>>()
        val n = bytes.usePinned { utf8proc_map(it.addressOf(0).reinterpret(), bytes.size.toLong(), out.ptr, options) }
        val result = out.value
        try {
            check(n >= 0 && result != null) { "utf8proc could not normalize (error $n)" }
            result.reinterpret<ByteVar>().readBytes(n.toInt()).decodeToString()
        } finally {
            free(result)
        }
    }
}
