@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.media

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import me.raddatz.veyq.mobile.webp.WebPEncodeRGBA
import me.raddatz.veyq.mobile.webp.WebPFree

/**
 * libwebp's own lossy encoder, built from the web encoder's pin (core/webp/build_libwebp.py) for every native target
 * (ADR 0148 decision 2: Apple's ImageIO decodes WebP and writes none; Linux and Windows have no encoder of their own):
 * `WebPEncodeRGBA` at the quality the core's ladder asks (0…1, libwebp's 0…100). The bytes libwebp allocated are copied
 * out and freed before this returns.
 */
object LibwebpEncoder : WebpEncoder {
    override suspend fun encode(rgba: ByteArray, width: Int, height: Int, quality: Double): ByteArray? {
        if (width <= 0 || height <= 0 || rgba.size != width * height * 4) return null
        return rgba.usePinned { input ->
            memScoped {
                val out = alloc<CPointerVar<UByteVar>>()
                val size = WebPEncodeRGBA(
                    input.addressOf(0).reinterpret(), width, height, width * 4, (quality * 100).toFloat(), out.ptr,
                ).toLong()
                val bytes = out.value
                try {
                    if (size <= 0 || bytes == null) null else bytes.readBytes(size.convert())
                } finally {
                    if (bytes != null) WebPFree(bytes)
                }
            }
        }
    }
}

actual val coreWebpEncoder: WebpEncoder? = LibwebpEncoder
