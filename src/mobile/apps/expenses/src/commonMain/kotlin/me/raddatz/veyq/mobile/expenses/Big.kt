package me.raddatz.veyq.mobile.expenses

/**
 * An arbitrary-precision integer for Expenses' exact arithmetic, the web's and the apps' alike (`Money`, `Ledger`): Kotlin
 * Multiplatform has no common one, and the rounding of a cent must come out the same on every device. Only what the ledger
 * needs — the four operations with JS `BigInt` semantics (division truncates toward zero, the remainder takes the
 * dividend's sign), comparison, powers of ten and decimal text. On the JVM and natively schoolbook limbs
 * (`Big.threaded.kt`, held against `java.math.BigInteger` by `BigTest`); on JavaScript the engine's own `BigInt`
 * (`Big.js.kt`): Kotlin/JS emulates a `Long`, and the limbs over it took a 10,000-entry Pot's ledger from 38 ms to 26 s
 * (ADR 0155 § Expenses' move, measured), the move's named fallback. Both answer `contracts/expenses/v1-vectors.json`.
 */
expect class Big : Comparable<Big> {
    /** -1, 0 or 1. */
    val signum: Int

    operator fun unaryMinus(): Big
    fun abs(): Big
    operator fun plus(o: Big): Big
    operator fun minus(o: Big): Big
    operator fun times(o: Big): Big

    /** Division truncating toward zero, as JS `BigInt` divides. */
    operator fun div(o: Big): Big

    /** The remainder of [div], with the dividend's sign, as JS `BigInt` takes it. */
    operator fun rem(o: Big): Big

    override fun compareTo(other: Big): Int
    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String

    /** The value as a Long; throws when it does not fit. */
    fun toLong(): Long

    companion object {
        val ZERO: Big
        val ONE: Big
        fun of(v: Long): Big
        fun of(v: Int): Big

        /** A decimal integer, an optional leading minus; throws on anything else. */
        fun parse(text: String): Big

        /** 10^[n], n ≥ 0. */
        fun pow10(n: Int): Big
    }
}
