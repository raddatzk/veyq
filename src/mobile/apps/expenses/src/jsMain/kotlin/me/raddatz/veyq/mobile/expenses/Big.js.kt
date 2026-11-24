package me.raddatz.veyq.mobile.expenses

/** [Big] on JavaScript: the engine's own `BigInt`, exactly the arithmetic the web's TypeScript ran. */
actual class Big private constructor(private val v: dynamic) : Comparable<Big> {
    actual val signum: Int get() = sign(v)

    actual operator fun unaryMinus(): Big = Big(neg(v))
    actual fun abs(): Big = if (signum < 0) -this else this
    actual operator fun plus(o: Big): Big = Big(add(v, o.v))
    actual operator fun minus(o: Big): Big = Big(sub(v, o.v))
    actual operator fun times(o: Big): Big = Big(mul(v, o.v))
    actual operator fun div(o: Big): Big = Big(quot(v, o.v))
    actual operator fun rem(o: Big): Big = Big(mod(v, o.v))
    actual override fun compareTo(other: Big): Int = cmp(v, other.v)
    actual override fun equals(other: Any?): Boolean = other is Big && cmp(v, other.v) == 0
    actual override fun hashCode(): Int = toString().hashCode()
    actual override fun toString(): String = text(v)

    actual fun toLong(): Long {
        require(cmp(v, MIN) >= 0 && cmp(v, MAX) <= 0) { "out of Long range" }
        return toString().toLong()
    }

    /** The `BigInt` itself, for the façade (`expenses/web`). */
    internal val bigint: dynamic get() = v

    actual companion object {
        private val MIN: dynamic = big("-9223372036854775808")
        private val MAX: dynamic = big("9223372036854775807")
        actual val ZERO: Big = Big(big("0"))
        actual val ONE: Big = Big(big("1"))
        actual fun of(v: Long): Big = Big(big(v.toString()))
        actual fun of(v: Int): Big = Big(big(v.toString()))
        actual fun parse(text: String): Big {
            require(Regex("^-?[0-9]+$").matches(text)) { "not an integer: $text" }
            return Big(big(text))
        }
        actual fun pow10(n: Int): Big {
            require(n >= 0)
            return parse("1" + "0".repeat(n))
        }

        /** A `BigInt` the façade hands in. */
        internal fun ofBigInt(b: dynamic): Big = Big(b)
    }
}

private fun big(text: String): dynamic = js("BigInt(text)")
private fun sign(a: dynamic): Int = js("a > BigInt(0) ? 1 : a < BigInt(0) ? -1 : 0").unsafeCast<Int>()
private fun neg(a: dynamic): dynamic = js("-a")
private fun add(a: dynamic, b: dynamic): dynamic = js("a + b")
private fun sub(a: dynamic, b: dynamic): dynamic = js("a - b")
private fun mul(a: dynamic, b: dynamic): dynamic = js("a * b")
private fun quot(a: dynamic, b: dynamic): dynamic {
    require(sign(b) != 0) { "division by zero" }
    return js("a / b")
}
private fun mod(a: dynamic, b: dynamic): dynamic {
    require(sign(b) != 0) { "division by zero" }
    return js("a % b")
}
private fun cmp(a: dynamic, b: dynamic): Int = js("a < b ? -1 : a > b ? 1 : 0").unsafeCast<Int>()
private fun text(a: dynamic): String = js("a.toString()").unsafeCast<String>()
