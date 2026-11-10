@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.crypto

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** libsodium against the v1 vectors on every libsodium target — Apple's and Linux's — through one test. */
class LibsodiumConformanceTest {
    private val vectors: String = readFile(getenv("VEYQ_VECTORS")?.toKString() ?: error("VEYQ_VECTORS is not set"))

    @Test
    fun libsodiumMeetsTheV1Vectors() {
        val c = Conformance(LibsodiumPrimitives, vectors)
        val failures = c.run(argonAtProductionCost = true)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertTrue(c.checked > 40, "only ${c.checked} cases ran")
        println("libsodium conformance: ${c.checked} cases")
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
