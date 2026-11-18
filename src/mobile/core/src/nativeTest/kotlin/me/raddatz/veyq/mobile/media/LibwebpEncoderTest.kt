@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.media

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.session.ProfileModel
import me.raddatz.veyq.mobile.webp.WebPDecodeRGBAInto
import me.raddatz.veyq.mobile.webp.WebPGetInfo
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * libwebp's encoder on every native target (ADR 0148 decision 2): what it writes is a still WebP of the picture's size
 * that libwebp's decoder reads back close to the pixels it was given, smaller as the quality steps down, and the core's
 * ladder brings even a noisy picture under the pad's budget.
 */
class LibwebpEncoderTest {
    private val edge = ProfileModel.EDGE

    /** A smooth gradient with a little noise, opaque: what a cropped photo is like. */
    private fun photo(noise: Int, seed: Int = 7): ByteArray {
        val r = Random(seed)
        return ByteArray(edge * edge * 4) { i ->
            val px = i / 4
            val x = px % edge
            val y = px / edge
            when (i % 4) {
                0 -> (x + r.nextInt(noise + 1)).coerceAtMost(255)
                1 -> (y + r.nextInt(noise + 1)).coerceAtMost(255)
                2 -> ((x + y) / 2 + r.nextInt(noise + 1)).coerceAtMost(255)
                else -> 255
            }.toByte()
        }
    }

    private fun decode(webp: ByteArray): ByteArray? = webp.usePinned { input ->
        val data = input.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
        val ok = memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            WebPGetInfo(data, webp.size.convert(), w.ptr, h.ptr) != 0 && w.value == edge && h.value == edge
        }
        if (!ok) return@usePinned null
        val out = ByteArray(edge * edge * 4)
        out.usePinned { o -> WebPDecodeRGBAInto(data, webp.size.convert(), o.addressOf(0).reinterpret(), out.size.convert(), edge * 4) } ?: return@usePinned null
        out
    }

    @Test
    fun aPictureEncodesToAStillWebpThatReadsBackCloseToItsPixels() = runTest {
        val rgba = photo(noise = 8)
        val webp = assertNotNull(LibwebpEncoder.encode(rgba, edge, edge, 0.9))
        assertEquals(Sniff.Image.WEBP, Sniff.image(webp))
        val back = assertNotNull(decode(webp), "libwebp's decoder did not read what its encoder wrote")
        val meanError = rgba.indices.sumOf { abs((rgba[it].toInt() and 0xff) - (back[it].toInt() and 0xff)) }.toDouble() / rgba.size
        assertTrue(meanError < 6.0, "mean error $meanError")
    }

    // The web's ladder (`spaceIconImage.ts`) with the web's budget: a photo's crop fits within its steps; pure noise —
    // the adversarial case — fits at none, and is refused as on the web (`icon_encode_too_large`), never sealed oversized.
    @Test
    fun aLowerQualityIsSmallerAndAPhotosCropFitsWithinTheLadder() = runTest {
        for ((name, noise) in listOf("photo-like" to 24, "pure noise" to 255)) {
            val sizes = ProfileModel.QUALITY_STEPS.map { q -> q to assertNotNull(LibwebpEncoder.encode(photo(noise), edge, edge, q)).size }
            println("veyq-measure: libwebp ${edge}px $name picture, bytes by quality $sizes (budget ${ProfileModel.BUDGET})")
            assertTrue(sizes.first().second > sizes.last().second, "the quality does not reach the size: $sizes")
            if (noise < 255) assertTrue(sizes.any { it.second <= ProfileModel.BUDGET }, "no step of the ladder fits a photo's crop: $sizes")
        }
    }

    @Test
    fun pixelsThatAreNotTheirSizeAreRefused() = runTest {
        assertNull(LibwebpEncoder.encode(ByteArray(10), edge, edge, 0.9))
        assertNull(LibwebpEncoder.encode(ByteArray(0), 0, 0, 0.9))
    }

    @Test
    fun theCoreHoldsLibwebpOnANativeTarget() {
        assertTrue(coreWebpEncoder === LibwebpEncoder)
    }
}
