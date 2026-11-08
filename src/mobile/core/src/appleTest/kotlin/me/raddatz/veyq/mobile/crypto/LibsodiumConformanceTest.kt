@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.raddatz.veyq.mobile.crypto

import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibsodiumConformanceTest {
    private val vectors: String = run {
        val path = getenv("VEYQ_VECTORS")?.toKString() ?: error("VEYQ_VECTORS is not set")
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("cannot read $path")
    }

    @Test
    fun libsodiumMeetsTheV1Vectors() {
        val c = Conformance(LibsodiumPrimitives, vectors)
        val failures = c.run(argonAtProductionCost = true)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertTrue(c.checked > 40, "only ${c.checked} cases ran")
    }

    /** The bite, as on the JVM: an AEAD off by one byte must turn the suite red. */
    @Test
    fun aWrongPrimitiveIsCaught() {
        val broken = object : Primitives by LibsodiumPrimitives {
            override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray) =
                LibsodiumPrimitives.aesGcmSeal(key, nonce, plaintext, aad).also { it[0] = (it[0] + 1).toByte() }
        }
        val failures = Conformance(broken, vectors).run(argonAtProductionCost = false)
        assertTrue(failures.any { it.startsWith("aes256gcm seal") }, "a broken AEAD went unnoticed")
    }
}
