package me.raddatz.veyq.mobile.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConformanceTest {
    private val vectors = File(System.getProperty("veyq.vectors") ?: error("veyq.vectors is not set")).readText()

    @Test
    fun bouncyCastleMeetsTheV1Vectors() {
        val c = Conformance(BouncyCastlePrimitives, vectors)
        val failures = c.run(argonAtProductionCost = true)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertTrue(c.checked > 60, "only ${c.checked} cases ran")
    }

    /** The bite: a primitive that is off by one byte must make the suite red, or the suite proves nothing. */
    @Test
    fun aWrongPrimitiveIsCaught() {
        val broken = object : Primitives by BouncyCastlePrimitives {
            override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) =
                BouncyCastlePrimitives.hkdfSha256(ikm, salt, info, length).also { it[0] = (it[0] + 1).toByte() }
        }
        val failures = Conformance(broken, vectors).run(argonAtProductionCost = false)
        assertTrue(failures.any { it.startsWith("hkdf") }, "a broken HKDF went unnoticed")
        assertTrue(failures.any { it.startsWith("seal.asym") }, "a broken HKDF did not break the asymmetric wrap")
    }

    /** The trust checks' bite: a signature check that says yes to anything must make the chain's and the succession's
     *  refusals red, or they would refuse nothing on a platform whose verify is broken. */
    @Test
    fun aVerifyThatAcceptsEverythingIsCaught() {
        val lenient = object : Primitives by BouncyCastlePrimitives {
            override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = true
        }
        val failures = Conformance(lenient, vectors).run(argonAtProductionCost = false)
        val all = failures.joinToString("\n")
        assertTrue(failures.any { it.startsWith("key_chain.failures.identity_signature") }, all)
        assertTrue(failures.any { it.startsWith("key_chain.failures.proof_signature") }, all)
        assertTrue(failures.any { it.startsWith("scope_succession path refuses") }, all)
        assertTrue(failures.any { it.startsWith("join_record refuses") }, all)
        assertTrue(failures.any { it.startsWith("join_endorsement") }, all)
    }
}
