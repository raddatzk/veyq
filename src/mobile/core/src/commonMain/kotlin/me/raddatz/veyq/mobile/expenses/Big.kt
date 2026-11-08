package me.raddatz.veyq.mobile.expenses

/**
 * An arbitrary-precision integer for Expenses' exact arithmetic (`money.ts` computes in JS `BigInt`): Kotlin Multiplatform
 * has no common one, and the rounding of a cent must come out the same on every device. Only what the ledger needs — the
 * four operations with JS `BigInt` semantics (division truncates toward zero, the remainder takes the dividend's sign),
 * comparison, powers of ten and decimal text. Schoolbook on base-10⁹ limbs: the numbers here stay small (amounts below
 * 2⁵³, rates of 24 digits, weights' common denominators). `BigTest` holds it against `java.math.BigInteger`.
 */
class Big private constructor(private val negative: Boolean, private val mag: IntArray) : Comparable<Big> {
    /** -1, 0 or 1. */
    val signum: Int get() = if (mag.isEmpty()) 0 else if (negative) -1 else 1

    operator fun unaryMinus(): Big = if (mag.isEmpty()) this else Big(!negative, mag)

    fun abs(): Big = if (negative) -this else this

    operator fun plus(o: Big): Big = when {
        negative == o.negative -> make(negative, addMag(mag, o.mag))
        cmpMag(mag, o.mag) >= 0 -> make(negative, subMag(mag, o.mag))
        else -> make(o.negative, subMag(o.mag, mag))
    }

    operator fun minus(o: Big): Big = this + (-o)

    operator fun times(o: Big): Big = make(negative != o.negative, mulMag(mag, o.mag))

    /** Division truncating toward zero, as JS `BigInt` divides. */
    operator fun div(o: Big): Big = make(negative != o.negative, divModMag(mag, o.mag).first)

    /** The remainder of [div], with the dividend's sign, as JS `BigInt` takes it. */
    operator fun rem(o: Big): Big = make(negative, divModMag(mag, o.mag).second)

    override fun compareTo(other: Big): Int {
        if (negative != other.negative) return if (negative) -1 else 1
        val c = cmpMag(mag, other.mag)
        return if (negative) -c else c
    }

    override fun equals(other: Any?): Boolean = other is Big && negative == other.negative && mag.contentEquals(other.mag)
    override fun hashCode(): Int = mag.contentHashCode() * 31 + if (negative) 1 else 0

    override fun toString(): String {
        if (mag.isEmpty()) return "0"
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(mag.last())
        for (i in mag.size - 2 downTo 0) sb.append(mag[i].toString().padStart(9, '0'))
        return sb.toString()
    }

    /** The value as a Long; throws when it does not fit. */
    fun toLong(): Long {
        require(mag.size <= 3) { "out of Long range" }
        var v = 0L
        for (i in mag.indices.reversed()) {
            require(v <= (Long.MAX_VALUE - mag[i]) / BASE) { "out of Long range" }
            v = v * BASE + mag[i]
        }
        return if (negative) -v else v
    }

    companion object {
        private const val BASE = 1_000_000_000L
        val ZERO = Big(false, IntArray(0))
        val ONE = of(1)

        fun of(v: Long): Big {
            if (v == 0L) return ZERO
            // Long.MIN_VALUE has no positive twin: take it apart from below.
            val neg = v < 0
            val limbs = ArrayList<Int>()
            var x = v
            while (x != 0L) {
                limbs += kotlin.math.abs(x % BASE).toInt()
                x /= BASE
            }
            return Big(neg, limbs.toIntArray())
        }

        fun of(v: Int): Big = of(v.toLong())

        /** A decimal integer, an optional leading minus; throws on anything else. */
        fun parse(text: String): Big {
            require(Regex("^-?[0-9]+$").matches(text)) { "not an integer: $text" }
            val neg = text.startsWith("-")
            val digits = text.removePrefix("-").trimStart('0')
            if (digits.isEmpty()) return ZERO
            val limbs = ArrayList<Int>()
            var end = digits.length
            while (end > 0) {
                val start = maxOf(0, end - 9)
                limbs += digits.substring(start, end).toInt()
                end = start
            }
            return Big(neg, limbs.toIntArray())
        }

        /** 10^[n], n ≥ 0. */
        fun pow10(n: Int): Big {
            require(n >= 0)
            return parse("1" + "0".repeat(n))
        }

        private fun make(negative: Boolean, mag: IntArray): Big {
            var n = mag.size
            while (n > 0 && mag[n - 1] == 0) n--
            val m = if (n == mag.size) mag else mag.copyOf(n)
            return if (m.isEmpty()) ZERO else Big(negative, m)
        }

        private fun cmpMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices.reversed()) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(maxOf(a.size, b.size) + 1)
            var carry = 0L
            for (i in out.indices) {
                val s = carry + (a.getOrElse(i) { 0 }) + (b.getOrElse(i) { 0 })
                out[i] = (s % BASE).toInt()
                carry = s / BASE
            }
            return out
        }

        /** a - b for |a| ≥ |b|. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                var d = a[i].toLong() - borrow - b.getOrElse(i) { 0 }
                borrow = if (d < 0) 1 else 0
                if (d < 0) d += BASE
                out[i] = d.toInt()
            }
            return out
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            if (a.isEmpty() || b.isEmpty()) return IntArray(0)
            val acc = LongArray(a.size + b.size + 1)
            for (i in a.indices) {
                var carry = 0L
                for (j in b.indices) {
                    val cur = acc[i + j] + a[i].toLong() * b[j] + carry
                    acc[i + j] = cur % BASE
                    carry = cur / BASE
                }
                var k = i + b.size
                while (carry != 0L) {
                    val cur = acc[k] + carry
                    acc[k] = cur % BASE
                    carry = cur / BASE
                    k++
                }
            }
            return IntArray(acc.size) { acc[it].toInt() }
        }

        private fun trim(m: IntArray): IntArray {
            var n = m.size
            while (n > 0 && m[n - 1] == 0) n--
            return if (n == m.size) m else m.copyOf(n)
        }

        private fun mulSmall(a: IntArray, k: Long): IntArray {
            val out = IntArray(a.size + 1)
            var carry = 0L
            for (i in a.indices) {
                val cur = a[i] * k + carry
                out[i] = (cur % BASE).toInt()
                carry = cur / BASE
            }
            out[a.size] = carry.toInt()
            return trim(out)
        }

        /** (|a| / |b|, |a| % |b|): long division, each base-10⁹ digit of the quotient found by bisection. */
        private fun divModMag(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
            require(b.isNotEmpty()) { "division by zero" }
            if (cmpMag(a, b) < 0) return IntArray(0) to a
            val quotient = IntArray(a.size)
            var rest = IntArray(0)
            for (i in a.indices.reversed()) {
                // rest = rest * BASE + a[i]
                rest = trim(IntArray(rest.size + 1) { if (it == 0) a[i] else rest[it - 1] })
                var lo = 0L
                var hi = BASE - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (cmpMag(mulSmall(b, mid), rest) <= 0) lo = mid else hi = mid - 1
                }
                quotient[i] = lo.toInt()
                if (lo > 0) rest = trim(subMag(rest, mulSmall(b, lo)))
            }
            return trim(quotient) to rest
        }
    }
}
