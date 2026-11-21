package me.raddatz.veyq.mobile.expenses

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Big] against `java.math.BigInteger`, whose `divide` and `remainder` have JS `BigInt`'s semantics (toward zero, the
 *  dividend's sign): random operands of every size the ledger meets and beyond, both signs, and the edges. */
class BigTest {
    private val random = Random(20261108)

    private fun randomText(): String {
        val digits = 1 + random.nextInt(60)
        val body = (1..digits).map { random.nextInt(10) }.joinToString("").trimStart('0').ifEmpty { "0" }
        return if (random.nextBoolean() && body != "0") "-$body" else body
    }

    private fun check(a: String, b: String) {
        val (x, y) = Big.parse(a) to Big.parse(b)
        val (bx, by) = BigInteger(a) to BigInteger(b)
        assertEquals(bx.add(by).toString(), (x + y).toString(), "$a + $b")
        assertEquals(bx.subtract(by).toString(), (x - y).toString(), "$a - $b")
        assertEquals(bx.multiply(by).toString(), (x * y).toString(), "$a * $b")
        assertEquals(bx.compareTo(by), x.compareTo(y), "$a <=> $b")
        if (by.signum() != 0) {
            assertEquals(bx.divide(by).toString(), (x / y).toString(), "$a / $b")
            assertEquals(bx.remainder(by).toString(), (x % y).toString(), "$a % $b")
        }
    }

    @Test
    fun matchesBigIntegerOnRandomOperands() {
        repeat(4000) { check(randomText(), randomText()) }
    }

    @Test
    fun matchesBigIntegerAtTheEdges() {
        val edges = listOf(
            "0", "1", "-1", "999999999", "1000000000", "-1000000000", "999999999999999999", "1000000000000000000",
            "9007199254740991", "-9007199254740991", "9223372036854775807", "-9223372036854775808",
            "123456789012345678901234567890", "-100000000000000000000000000",
        )
        for (a in edges) for (b in edges) check(a, b)
        assertEquals(Long.MIN_VALUE.toString(), Big.of(Long.MIN_VALUE).toString())
        assertEquals(Long.MAX_VALUE, Big.of(Long.MAX_VALUE).toLong())
        assertEquals("1" + "0".repeat(30), Big.pow10(30).toString())
    }
}
