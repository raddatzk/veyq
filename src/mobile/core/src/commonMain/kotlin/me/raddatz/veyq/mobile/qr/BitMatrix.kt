// Ported to Kotlin from ZXing's common/BitMatrix.java and common/BitSource.java (Copyright 2007-2008 ZXing authors,
// Apache License 2.0; NOTICE beside this file names the upstream commit). Changed: only what the QR reader and writer
// use, a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

/** A 2-D matrix of bits, `x` the column and `y` the row; a set bit is a dark module or pixel. */
internal class BitMatrix(val width: Int, val height: Int) {
    private val rowSize = (width + 31) / 32
    private val bits: IntArray

    init {
        require(width >= 1 && height >= 1) { "Both dimensions must be greater than 0" }
        bits = IntArray(rowSize * height)
    }

    constructor(dimension: Int) : this(dimension, dimension)

    operator fun get(x: Int, y: Int): Boolean = ((bits[y * rowSize + (x / 32)] ushr (x and 0x1f)) and 1) != 0

    fun set(x: Int, y: Int) {
        val offset = y * rowSize + (x / 32)
        bits[offset] = bits[offset] or (1 shl (x and 0x1f))
    }

    fun flip(x: Int, y: Int) {
        val offset = y * rowSize + (x / 32)
        bits[offset] = bits[offset] xor (1 shl (x and 0x1f))
    }

    /** Every bit flipped: the matrix of a code drawn light on dark. */
    fun flip() {
        for (i in bits.indices) bits[i] = bits[i].inv()
    }

    fun setRegion(left: Int, top: Int, width: Int, height: Int) {
        require(top >= 0 && left >= 0) { "Left and top must be nonnegative" }
        require(height >= 1 && width >= 1) { "Height and width must be at least 1" }
        val right = left + width
        val bottom = top + height
        require(bottom <= this.height && right <= this.width) { "The region must fit inside the matrix" }
        for (y in top until bottom) {
            val offset = y * rowSize
            for (x in left until right) bits[offset + (x / 32)] = bits[offset + (x / 32)] or (1 shl (x and 0x1f))
        }
    }

    /** The top-left set bit as `[x, y]`, or null. */
    fun topLeftOnBit(): IntArray? {
        var bitsOffset = 0
        while (bitsOffset < bits.size && bits[bitsOffset] == 0) bitsOffset++
        if (bitsOffset == bits.size) return null
        val y = bitsOffset / rowSize
        var x = (bitsOffset % rowSize) * 32
        val theBits = bits[bitsOffset]
        var bit = 0
        while ((theBits shl (31 - bit)) == 0) bit++
        x += bit
        return intArrayOf(x, y)
    }

    /** The bottom-right set bit as `[x, y]`, or null. */
    fun bottomRightOnBit(): IntArray? {
        var bitsOffset = bits.size - 1
        while (bitsOffset >= 0 && bits[bitsOffset] == 0) bitsOffset--
        if (bitsOffset < 0) return null
        val y = bitsOffset / rowSize
        var x = (bitsOffset % rowSize) * 32
        val theBits = bits[bitsOffset]
        var bit = 31
        while ((theBits ushr bit) == 0) bit--
        x += bit
        return intArrayOf(x, y)
    }
}

/** Reads bits from a byte array, most significant bit first. */
internal class BitSource(private val bytes: ByteArray) {
    private var byteOffset = 0
    private var bitOffset = 0

    fun readBits(count: Int): Int {
        var numBits = count
        if (numBits < 1 || numBits > 32 || numBits > available()) throw QrReadException("bit stream: $numBits bits past its end")
        var result = 0
        if (bitOffset > 0) {
            val bitsLeft = 8 - bitOffset
            val toRead = minOf(numBits, bitsLeft)
            val bitsToNotRead = bitsLeft - toRead
            val mask = (0xFF shr (8 - toRead)) shl bitsToNotRead
            result = (bytes[byteOffset].toInt() and mask) shr bitsToNotRead
            numBits -= toRead
            bitOffset += toRead
            if (bitOffset == 8) {
                bitOffset = 0
                byteOffset++
            }
        }
        if (numBits > 0) {
            while (numBits >= 8) {
                result = (result shl 8) or (bytes[byteOffset].toInt() and 0xFF)
                byteOffset++
                numBits -= 8
            }
            if (numBits > 0) {
                val bitsToNotRead = 8 - numBits
                val mask = (0xFF shr bitsToNotRead) shl bitsToNotRead
                result = (result shl numBits) or ((bytes[byteOffset].toInt() and mask) shr bitsToNotRead)
                bitOffset += numBits
            }
        }
        return result
    }

    fun available(): Int = 8 * (bytes.size - byteOffset) - bitOffset
}

/** Why a frame read no code: no code found, or one found that does not read (its format, its checksum). */
internal class QrReadException(message: String) : Exception(message)
