@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

// Kotlin/Native's own platform library: on mingw it links zlib statically (`-Wl,-Bstatic -lz`), so veyq.dll needs no
// zlib1.dll; Apple and Linux link the system's libz.
actual fun inflate(bytes: ByteArray, cap: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    return memScoped {
        val strm = alloc<z_stream>()
        memset(strm.ptr, 0, sizeOf<z_stream>().convert())
        if (inflateInit_(strm.ptr, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) return@memScoped null
        try {
            var out = ByteArray(64 * 1024)
            var size = 0
            val chunk = ByteArray(64 * 1024)
            bytes.usePinned { input ->
                chunk.usePinned { output ->
                    strm.next_in = input.addressOf(0).reinterpret()
                    strm.avail_in = bytes.size.convert()
                    while (true) {
                        strm.next_out = output.addressOf(0).reinterpret()
                        strm.avail_out = chunk.size.convert()
                        val r = platform.zlib.inflate(strm.ptr, Z_NO_FLUSH)
                        val n = chunk.size - strm.avail_out.convert<Int>()
                        if (r != Z_OK && r != Z_STREAM_END) return@memScoped null
                        if (size + n > cap) return@memScoped null
                        if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
                        chunk.copyInto(out, size, 0, n)
                        size += n
                        if (r == Z_STREAM_END) return@memScoped out.copyOf(size)
                        if (n == 0 && strm.avail_in.convert<Int>() == 0) return@memScoped null
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            inflateEnd(strm.ptr)
        }
    }
}
