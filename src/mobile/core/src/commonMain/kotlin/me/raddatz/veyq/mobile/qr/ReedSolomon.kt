// Ported to Kotlin from ZXing's common/reedsolomon/{GenericGF,GenericGFPoly,ReedSolomonDecoder,ReedSolomonEncoder}.java
// (Copyright 2007-2008 ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream commit). Changed:
// QR's field only (GF(256), x^8 + x^4 + x^3 + x^2 + 1, generator base 0); a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

/** QR's Galois field GF(256) with its exponent and logarithm tables. */
internal object GaloisField {
    private const val PRIMITIVE = 0x011D // x^8 + x^4 + x^3 + x^2 + 1
    const val SIZE = 256
    const val GENERATOR_BASE = 0

    private val expTable = IntArray(SIZE)
    private val logTable = IntArray(SIZE)

    init {
        var x = 1
        for (i in 0 until SIZE) {
            expTable[i] = x
            x *= 2 // 2 (the polynomial x) is a primitive element
            if (x >= SIZE) {
                x = x xor PRIMITIVE
                x = x and (SIZE - 1)
            }
        }
        for (i in 0 until SIZE - 1) logTable[expTable[i]] = i
        // logTable[0] == 0 but this should never be used
    }

    val zero = GaloisPoly(intArrayOf(0))
    val one = GaloisPoly(intArrayOf(1))

    /** The monomial `coefficient * x^degree`. */
    fun buildMonomial(degree: Int, coefficient: Int): GaloisPoly {
        require(degree >= 0)
        if (coefficient == 0) return zero
        val coefficients = IntArray(degree + 1)
        coefficients[0] = coefficient
        return GaloisPoly(coefficients)
    }

    /** Addition and subtraction are the same in GF(size). */
    fun addOrSubtract(a: Int, b: Int): Int = a xor b

    /** 2 to the power of [a]. */
    fun exp(a: Int): Int = expTable[a]

    /** Base 2 log of [a]. */
    fun log(a: Int): Int {
        if (a == 0) throw QrReadException("log of 0")
        return logTable[a]
    }

    fun inverse(a: Int): Int {
        if (a == 0) throw QrReadException("inverse of 0")
        return expTable[SIZE - logTable[a] - 1]
    }

    fun multiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[(logTable[a] + logTable[b]) % (SIZE - 1)]
    }
}

/** A polynomial over [GaloisField], its coefficients from the highest degree down; leading zeros are stripped. */
internal class GaloisPoly(coefficients: IntArray) {
    val coefficients: IntArray

    init {
        require(coefficients.isNotEmpty())
        val length = coefficients.size
        this.coefficients = if (length > 1 && coefficients[0] == 0) {
            // Leading term must be non-zero for anything except the constant polynomial "0"
            var firstNonZero = 1
            while (firstNonZero < length && coefficients[firstNonZero] == 0) firstNonZero++
            if (firstNonZero == length) intArrayOf(0) else coefficients.copyOfRange(firstNonZero, length)
        } else {
            coefficients
        }
    }

    val degree: Int get() = coefficients.size - 1

    val isZero: Boolean get() = coefficients[0] == 0

    /** The coefficient of the `x^degree` term. */
    fun coefficient(degree: Int): Int = coefficients[coefficients.size - 1 - degree]

    fun evaluateAt(a: Int): Int {
        if (a == 0) return coefficient(0) // Just return the x^0 coefficient
        if (a == 1) {
            // Just the sum of the coefficients
            var result = 0
            for (c in coefficients) result = GaloisField.addOrSubtract(result, c)
            return result
        }
        var result = coefficients[0]
        for (i in 1 until coefficients.size) result = GaloisField.addOrSubtract(GaloisField.multiply(a, result), coefficients[i])
        return result
    }

    fun addOrSubtract(other: GaloisPoly): GaloisPoly {
        if (isZero) return other
        if (other.isZero) return this
        var smaller = coefficients
        var larger = other.coefficients
        if (smaller.size > larger.size) {
            val temp = smaller
            smaller = larger
            larger = temp
        }
        val sumDiff = IntArray(larger.size)
        val lengthDiff = larger.size - smaller.size
        // Copy high-order terms only found in higher-degree polynomial's coefficients
        larger.copyInto(sumDiff, 0, 0, lengthDiff)
        for (i in lengthDiff until larger.size) sumDiff[i] = GaloisField.addOrSubtract(smaller[i - lengthDiff], larger[i])
        return GaloisPoly(sumDiff)
    }

    fun multiply(other: GaloisPoly): GaloisPoly {
        if (isZero || other.isZero) return GaloisField.zero
        val a = coefficients
        val b = other.coefficients
        val product = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            val aCoeff = a[i]
            for (j in b.indices) product[i + j] = GaloisField.addOrSubtract(product[i + j], GaloisField.multiply(aCoeff, b[j]))
        }
        return GaloisPoly(product)
    }

    fun multiply(scalar: Int): GaloisPoly {
        if (scalar == 0) return GaloisField.zero
        if (scalar == 1) return this
        return GaloisPoly(IntArray(coefficients.size) { GaloisField.multiply(coefficients[it], scalar) })
    }

    fun multiplyByMonomial(degree: Int, coefficient: Int): GaloisPoly {
        require(degree >= 0)
        if (coefficient == 0) return GaloisField.zero
        val product = IntArray(coefficients.size + degree)
        for (i in coefficients.indices) product[i] = GaloisField.multiply(coefficients[i], coefficient)
        return GaloisPoly(product)
    }

    /** The quotient and remainder of this divided by [other]. */
    fun divide(other: GaloisPoly): Pair<GaloisPoly, GaloisPoly> {
        require(!other.isZero) { "Divide by 0" }
        var quotient = GaloisField.zero
        var remainder = this
        val inverseDenominatorLeadingTerm = GaloisField.inverse(other.coefficient(other.degree))
        while (remainder.degree >= other.degree && !remainder.isZero) {
            val degreeDifference = remainder.degree - other.degree
            val scale = GaloisField.multiply(remainder.coefficient(remainder.degree), inverseDenominatorLeadingTerm)
            val term = other.multiplyByMonomial(degreeDifference, scale)
            val iterationQuotient = GaloisField.buildMonomial(degreeDifference, scale)
            quotient = quotient.addOrSubtract(iterationQuotient)
            remainder = remainder.addOrSubtract(term)
        }
        return quotient to remainder
    }
}

/**
 * Reed–Solomon error correction over QR's field: corrects a block's codewords in place, up to half its EC codewords
 * wrong, and fails for more (Berlekamp–Massey through the extended Euclidean algorithm, Chien search, Forney).
 */
internal object ReedSolomonDecoder {
    /** Corrects [received] (data and EC codewords) in place; returns how many codewords were corrected. */
    fun decodeWithECCount(received: IntArray, twoS: Int): Int {
        val poly = GaloisPoly(received)
        val syndromeCoefficients = IntArray(twoS)
        var noError = true
        for (i in 0 until twoS) {
            val eval = poly.evaluateAt(GaloisField.exp(i + GaloisField.GENERATOR_BASE))
            syndromeCoefficients[syndromeCoefficients.size - 1 - i] = eval
            if (eval != 0) noError = false
        }
        if (noError) return 0
        val syndrome = GaloisPoly(syndromeCoefficients)
        val (sigma, omega) = runEuclideanAlgorithm(GaloisField.buildMonomial(twoS, 1), syndrome, twoS)
        val errorLocations = findErrorLocations(sigma)
        val errorMagnitudes = findErrorMagnitudes(omega, errorLocations)
        for (i in errorLocations.indices) {
            val position = received.size - 1 - GaloisField.log(errorLocations[i])
            if (position < 0) throw QrReadException("Bad error location")
            received[position] = GaloisField.addOrSubtract(received[position], errorMagnitudes[i])
        }
        return errorLocations.size
    }

    private fun runEuclideanAlgorithm(aIn: GaloisPoly, bIn: GaloisPoly, r: Int): Pair<GaloisPoly, GaloisPoly> {
        var a = aIn
        var b = bIn
        // Assume a's degree is >= b's
        if (a.degree < b.degree) {
            val temp = a
            a = b
            b = temp
        }
        var rLast = a
        var rr = b
        var tLast = GaloisField.zero
        var t = GaloisField.one

        // Run Euclidean algorithm until r's degree is less than R/2
        while (2 * rr.degree >= r) {
            val rLastLast = rLast
            val tLastLast = tLast
            rLast = rr
            tLast = t

            // Divide rLastLast by rLast, with quotient in q and remainder in r
            if (rLast.isZero) throw QrReadException("r_{i-1} was zero") // Oops, Euclidean algorithm already terminated?
            rr = rLastLast
            var q = GaloisField.zero
            val denominatorLeadingTerm = rLast.coefficient(rLast.degree)
            val dltInverse = GaloisField.inverse(denominatorLeadingTerm)
            while (rr.degree >= rLast.degree && !rr.isZero) {
                val degreeDiff = rr.degree - rLast.degree
                val scale = GaloisField.multiply(rr.coefficient(rr.degree), dltInverse)
                q = q.addOrSubtract(GaloisField.buildMonomial(degreeDiff, scale))
                rr = rr.addOrSubtract(rLast.multiplyByMonomial(degreeDiff, scale))
            }
            t = q.multiply(tLast).addOrSubtract(tLastLast)
            if (rr.degree >= rLast.degree) throw QrReadException("Division algorithm failed to reduce polynomial")
        }

        val sigmaTildeAtZero = t.coefficient(0)
        if (sigmaTildeAtZero == 0) throw QrReadException("sigmaTilde(0) was zero")
        val inverse = GaloisField.inverse(sigmaTildeAtZero)
        return t.multiply(inverse) to rr.multiply(inverse)
    }

    private fun findErrorLocations(errorLocator: GaloisPoly): IntArray {
        // This is a direct application of Chien's search
        val numErrors = errorLocator.degree
        if (numErrors == 1) return intArrayOf(errorLocator.coefficient(1)) // shortcut
        val result = IntArray(numErrors)
        var e = 0
        var i = 1
        while (i < GaloisField.SIZE && e < numErrors) {
            if (errorLocator.evaluateAt(i) == 0) {
                result[e] = GaloisField.inverse(i)
                e++
            }
            i++
        }
        if (e != numErrors) throw QrReadException("Error locator degree does not match number of roots")
        return result
    }

    private fun findErrorMagnitudes(errorEvaluator: GaloisPoly, errorLocations: IntArray): IntArray {
        // This is directly applying Forney's Formula
        val s = errorLocations.size
        val result = IntArray(s)
        for (i in 0 until s) {
            val xiInverse = GaloisField.inverse(errorLocations[i])
            var denominator = 1
            for (j in 0 until s) {
                if (i != j) {
                    // Below is a funny-looking workaround from Steven Parkes for 1 + x, in place of addOrSubtract
                    val term = GaloisField.multiply(errorLocations[j], xiInverse)
                    val termPlus1 = if ((term and 0x1) == 0) term or 1 else term and 1.inv()
                    denominator = GaloisField.multiply(denominator, termPlus1)
                }
            }
            result[i] = GaloisField.multiply(errorEvaluator.evaluateAt(xiInverse), GaloisField.inverse(denominator))
            if (GaloisField.GENERATOR_BASE != 0) result[i] = GaloisField.multiply(result[i], xiInverse)
        }
        return result
    }
}

/** Reed–Solomon encoding over QR's field: a block's EC codewords from its data codewords. */
internal object ReedSolomonEncoder {
    /**
     * The generator polynomial of [degree] EC codewords. Built per call (at most 30 products) rather than cached as
     * upstream does: an object shared by every thread holds no mutable state.
     */
    private fun buildGenerator(degree: Int): GaloisPoly {
        var generator = GaloisPoly(intArrayOf(1))
        for (d in 1..degree) generator = generator.multiply(GaloisPoly(intArrayOf(1, GaloisField.exp(d - 1 + GaloisField.GENERATOR_BASE))))
        return generator
    }

    /** Fills the last [ecBytes] of [toEncode] with the EC codewords of the data before them. */
    fun encode(toEncode: IntArray, ecBytes: Int) {
        require(ecBytes != 0) { "No error correction bytes" }
        val dataBytes = toEncode.size - ecBytes
        require(dataBytes > 0) { "No data bytes provided" }
        val generator = buildGenerator(ecBytes)
        val info = GaloisPoly(toEncode.copyOf(dataBytes)).multiplyByMonomial(ecBytes, 1)
        val coefficients = info.divide(generator).second.coefficients
        val numZeroCoefficients = ecBytes - coefficients.size
        for (i in 0 until numZeroCoefficients) toEncode[dataBytes + i] = 0
        coefficients.copyInto(toEncode, dataBytes + numZeroCoefficients)
    }
}
