@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.crypto

/** What one run of [Conformance] found: the failures (empty when green) and how many cases ran. */
@JsExport
class ConformanceResult(val failures: Array<String>, val checked: Int)

/**
 * The suite's conformance over the web's primitives ([JsPrimitives]) and the vectors' text, as the JVM's
 * `ConformanceTest` and the native `LibsodiumConformanceTest` run it. A binary of its own, built into
 * `@veyq/core-js`'s `dist/conformance/` and never into the shipped module (ADR 0155 decision 6).
 */
@JsExport
fun runConformance(vectors: String, argonAtProductionCost: Boolean): ConformanceResult {
    val c = Conformance(JsPrimitives, vectors)
    val failures = c.run(argonAtProductionCost)
    return ConformanceResult(failures.toTypedArray(), c.checked)
}

/** The bite, as `ConformanceTest.aWrongPrimitiveIsCaught`: HKDF off by one byte must make the suite red. */
@JsExport
fun runConformanceWithBrokenHkdf(vectors: String): Array<String> {
    val broken = object : Primitives by JsPrimitives {
        override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) =
            JsPrimitives.hkdfSha256(ikm, salt, info, length).also { it[0] = (it[0] + 1).toByte() }
    }
    return Conformance(broken, vectors).run(argonAtProductionCost = false).toTypedArray()
}
