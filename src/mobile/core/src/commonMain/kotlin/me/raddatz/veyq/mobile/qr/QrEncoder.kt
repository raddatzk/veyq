// Ported to Kotlin from ZXing's qrcode/encoder/{Encoder,MatrixUtil,MaskUtil,ByteMatrix,BlockPair}.java and
// common/BitArray.java (Copyright 2008 ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream
// commit). Changed: level M only, no hints (no ECI, no forced version or mask, no Kanji, no MinimalEncoder); a byte
// segment is the text's UTF-8 with no ECI, as the web's `qrcode` writes it; the alignment centres are the decoder's
// [Version] table; a text too long for version 40 is an IllegalArgumentException.
package me.raddatz.veyq.mobile.qr

/**
 * A QR code's modules, [size] × [size], and the [QUIET_ZONE] of light modules every drawing keeps around them: [dark]
 * reads the drawing's [side] × [side] grid, the zone included, so a caller draws exactly what it is handed.
 */
class QrMatrix internal constructor(val size: Int, private val modules: BooleanArray) {
    /** The side of the drawn grid: the code and its quiet zone on both sides. */
    val side: Int get() = size + 2 * QUIET_ZONE

    /** Whether the module at column [x], row [y] of the drawn grid is dark; the quiet zone is light. */
    fun dark(x: Int, y: Int): Boolean {
        val cx = x - QUIET_ZONE
        val cy = y - QUIET_ZONE
        return cx in 0 until size && cy in 0 until size && modules[cy * size + cx]
    }

    companion object {
        /** ISO 18004's quiet zone: four light modules on every side. */
        const val QUIET_ZONE = 4
    }
}

/**
 * The one QR writer of every app (ADR 0161 decision 1, D2 of the QR CR): a text into a [QrMatrix] at error-correction
 * level M (about 15 % of the codewords recoverable), the smallest version it fits, the mask ISO 18004's penalty rules
 * choose. A phone draws the matrix itself; a desktop asks [QrPng] for its picture.
 */
object QrEncoder {
    private val EC_LEVEL = ErrorCorrectionLevel.M

    /** The code of [text]; an IllegalArgumentException when it does not fit version 40 at level M (2331 bytes). */
    fun encode(text: String): QrMatrix {
        // Pick an encoding mode appropriate for the content.
        val mode = chooseMode(text)
        // This will store the header information, like mode and length, as well as "header" segments like an ECI
        // segment.
        val headerBits = BitArray()
        headerBits.appendBits(mode.bits, 4)
        // Collect data within the main segment, separately, to count its size if needed. Don't add it to main payload
        // yet.
        val dataBits = BitArray()
        val bytes = text.encodeToByteArray()
        when (mode) {
            Mode.NUMERIC -> appendNumericBytes(text, dataBits)
            Mode.ALPHANUMERIC -> appendAlphanumericBytes(text, dataBits)
            else -> for (b in bytes) dataBits.appendBits(b.toInt() and 0xFF, 8)
        }
        val version = recommendVersion(mode, headerBits, dataBits)
        val headerAndDataBits = BitArray()
        headerAndDataBits.appendBitArray(headerBits)
        // Find "length" of main segment and write it
        val numLetters = if (mode == Mode.BYTE) bytes.size else text.length
        appendLengthInfo(numLetters, version, mode, headerAndDataBits)
        // Put data together into the overall payload
        headerAndDataBits.appendBitArray(dataBits)

        val ecBlocks = version.ecBlocksForLevel(EC_LEVEL)
        val numDataBytes = version.totalCodewords - ecBlocks.totalECCodewords
        // Terminate the bits properly.
        terminateBits(numDataBytes, headerAndDataBits)
        // Interleave data bits with error correction code.
        val finalBits = interleaveWithECBytes(headerAndDataBits, version.totalCodewords, numDataBytes, ecBlocks.numBlocks)

        // Choose the mask pattern and set to "qrCode".
        val dimension = version.dimension
        val matrix = ByteMatrix(dimension)
        val maskPattern = chooseMaskPattern(finalBits, version, matrix)
        // Build the matrix and set it to "qrCode".
        MatrixUtil.buildMatrix(finalBits, EC_LEVEL, version, maskPattern, matrix)
        return QrMatrix(dimension, BooleanArray(dimension * dimension) { matrix[it % dimension, it / dimension] == 1.toByte() })
    }

    private val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    /** The code of [c] in alphanumeric mode, or -1 when it has none. */
    private fun alphanumericCode(c: Char): Int = ALPHANUMERIC.indexOf(c)

    /** Numeric for digits only, alphanumeric for its 45 characters, byte for anything else; as upstream, one segment. */
    private fun chooseMode(content: String): Mode {
        var hasNumeric = false
        var hasAlphanumeric = false
        for (c in content) {
            if (c in '0'..'9') hasNumeric = true
            else if (alphanumericCode(c) != -1) hasAlphanumeric = true
            else return Mode.BYTE
        }
        if (hasAlphanumeric) return Mode.ALPHANUMERIC
        if (hasNumeric) return Mode.NUMERIC
        return Mode.BYTE
    }

    /**
     * Decides the smallest version on which data can fit. The character count's width depends on the version, so it
     * is chosen twice: first with version 1's count, then with the provisional version's.
     */
    private fun recommendVersion(mode: Mode, headerBits: BitArray, dataBits: BitArray): Version {
        val provisionalBitsNeeded = headerBits.size + mode.characterCountBits(Version.forNumber(1)) + dataBits.size
        val provisionalVersion = chooseVersion(provisionalBitsNeeded)
        // Use that guess to calculate the right version. I am still not sure this works in 100% of cases.
        val bitsNeeded = headerBits.size + mode.characterCountBits(provisionalVersion) + dataBits.size
        return chooseVersion(bitsNeeded)
    }

    private fun chooseVersion(numInputBits: Int): Version {
        for (versionNum in 1..40) {
            val version = Version.forNumber(versionNum)
            if (willFit(numInputBits, version)) return version
        }
        throw IllegalArgumentException("Data too big: $numInputBits bits do not fit version 40 at level M")
    }

    /** Whether [numInputBits] fit [version]'s data codewords at level M. */
    private fun willFit(numInputBits: Int, version: Version): Boolean {
        // numBytes = 196
        val numBytes = version.totalCodewords
        // getNumECBytes = 130
        val numEcBytes = version.ecBlocksForLevel(EC_LEVEL).totalECCodewords
        // getNumDataBytes = 196 - 130 = 66
        val numDataBytes = numBytes - numEcBytes
        val totalInputBytes = (numInputBits + 7) / 8
        return numDataBytes >= totalInputBytes
    }

    /** Terminates [bits] as described in 8.4.8 and 8.4.9 of JISX0510:2004 (p.24). */
    private fun terminateBits(numDataBytes: Int, bits: BitArray) {
        val capacity = numDataBytes * 8
        require(bits.size <= capacity) { "data bits cannot fit in the QR Code${bits.size} > $capacity" }
        // Append Mode.TERMINATE if there is enough space (value is 0000)
        var i = 0
        while (i < 4 && bits.size < capacity) {
            bits.appendBit(false)
            ++i
        }
        // Append termination bits. See 8.4.8 of JISX0510:2004 (p.24) for details. If the last byte isn't 8-bit
        // aligned, we'll add padding bits.
        val numBitsInLastByte = bits.size and 0x07
        if (numBitsInLastByte > 0) for (j in numBitsInLastByte until 8) bits.appendBit(false)
        // If we have more space, we'll fill the space with padding patterns defined in 8.4.9 (p.24).
        val numPaddingBytes = numDataBytes - bits.sizeInBytes
        for (j in 0 until numPaddingBytes) bits.appendBits(if ((j and 0x01) == 0) 0xEC else 0x11, 8)
        check(bits.size == capacity) { "Bits size does not equal capacity" }
    }

    /**
     * Interleaves [bits] with the EC codewords of their blocks, as JISX0510:2004 (p.30) describes: the blocks split
     * into two groups, the second's one data codeword longer; data codewords first, block by block, then EC codewords.
     */
    private fun interleaveWithECBytes(bits: BitArray, numTotalBytes: Int, numDataBytes: Int, numRSBlocks: Int): BitArray {
        // "bits" must have "getNumDataBytes" bytes of data.
        check(bits.sizeInBytes == numDataBytes) { "Number of bits and data bytes does not match" }
        // Step 1. Divide data bytes into blocks and generate error correction bytes for them. We'll store the divided
        // data bytes blocks and error correction bytes blocks into "blocks".
        var dataBytesOffset = 0
        var maxNumDataBytes = 0
        var maxNumEcBytes = 0
        // Since, we know the number of reedsolmon blocks, we can initialize the vector with the number.
        val blocks = ArrayList<Pair<ByteArray, ByteArray>>(numRSBlocks)
        for (i in 0 until numRSBlocks) {
            val (size, numEcBytes) = numDataBytesAndNumECBytesForBlockID(numTotalBytes, numDataBytes, numRSBlocks, i)
            val dataBytes = ByteArray(size)
            bits.toBytes(8 * dataBytesOffset, dataBytes, 0, size)
            val ecBytes = generateECBytes(dataBytes, numEcBytes)
            blocks += dataBytes to ecBytes
            maxNumDataBytes = maxOf(maxNumDataBytes, size)
            maxNumEcBytes = maxOf(maxNumEcBytes, ecBytes.size)
            dataBytesOffset += size
        }
        check(numDataBytes == dataBytesOffset) { "Data bytes does not match offset" }
        val result = BitArray()
        // First, place data blocks.
        for (i in 0 until maxNumDataBytes) for ((dataBytes, _) in blocks) if (i < dataBytes.size) result.appendBits(dataBytes[i].toInt() and 0xFF, 8)
        // Then, place error correction blocks.
        for (i in 0 until maxNumEcBytes) for ((_, ecBytes) in blocks) if (i < ecBytes.size) result.appendBits(ecBytes[i].toInt() and 0xFF, 8)
        check(numTotalBytes == result.sizeInBytes) { "Interleaving error: $numTotalBytes and ${result.sizeInBytes} differ." }
        return result
    }

    /** A block's data and EC codeword counts, from the totals and its index (JISX0510:2004 Table 12). */
    private fun numDataBytesAndNumECBytesForBlockID(numTotalBytes: Int, numDataBytes: Int, numRSBlocks: Int, blockID: Int): Pair<Int, Int> {
        check(blockID < numRSBlocks) { "Block ID too large" }
        // numRsBlocksInGroup2 = 196 % 5 = 1
        val numRsBlocksInGroup2 = numTotalBytes % numRSBlocks
        // numRsBlocksInGroup1 = 5 - 1 = 4
        val numRsBlocksInGroup1 = numRSBlocks - numRsBlocksInGroup2
        // numTotalBytesInGroup1 = 196 / 5 = 39
        val numTotalBytesInGroup1 = numTotalBytes / numRSBlocks
        // numTotalBytesInGroup2 = 39 + 1 = 40
        val numTotalBytesInGroup2 = numTotalBytesInGroup1 + 1
        // numDataBytesInGroup1 = 66 / 5 = 13
        val numDataBytesInGroup1 = numDataBytes / numRSBlocks
        // numDataBytesInGroup2 = 13 + 1 = 14
        val numDataBytesInGroup2 = numDataBytesInGroup1 + 1
        // numEcBytesInGroup1 = 39 - 13 = 26
        val numEcBytesInGroup1 = numTotalBytesInGroup1 - numDataBytesInGroup1
        // numEcBytesInGroup2 = 40 - 14 = 26
        val numEcBytesInGroup2 = numTotalBytesInGroup2 - numDataBytesInGroup2
        // Sanity checks.
        check(numEcBytesInGroup1 == numEcBytesInGroup2) { "EC bytes mismatch" }
        check(numRSBlocks == numRsBlocksInGroup1 + numRsBlocksInGroup2) { "RS blocks mismatch" }
        check(
            numTotalBytes == ((numDataBytesInGroup1 + numEcBytesInGroup1) * numRsBlocksInGroup1) +
                ((numDataBytesInGroup2 + numEcBytesInGroup2) * numRsBlocksInGroup2),
        ) { "Total bytes mismatch" }
        return if (blockID < numRsBlocksInGroup1) numDataBytesInGroup1 to numEcBytesInGroup1 else numDataBytesInGroup2 to numEcBytesInGroup2
    }

    private fun generateECBytes(dataBytes: ByteArray, numEcBytesInBlock: Int): ByteArray {
        val numDataBytes = dataBytes.size
        val toEncode = IntArray(numDataBytes + numEcBytesInBlock)
        for (i in 0 until numDataBytes) toEncode[i] = dataBytes[i].toInt() and 0xFF
        ReedSolomonEncoder.encode(toEncode, numEcBytesInBlock)
        return ByteArray(numEcBytesInBlock) { toEncode[numDataBytes + it].toByte() }
    }

    private fun appendLengthInfo(numLetters: Int, version: Version, mode: Mode, bits: BitArray) {
        val numBits = mode.characterCountBits(version)
        require(numLetters < (1 shl numBits)) { "$numLetters is bigger than ${(1 shl numBits) - 1}" }
        bits.appendBits(numLetters, numBits)
    }

    private fun appendNumericBytes(content: CharSequence, bits: BitArray) {
        val length = content.length
        var i = 0
        while (i < length) {
            val num1 = content[i] - '0'
            if (i + 2 < length) {
                // Encode three numeric letters in ten bits.
                val num2 = content[i + 1] - '0'
                val num3 = content[i + 2] - '0'
                bits.appendBits(num1 * 100 + num2 * 10 + num3, 10)
                i += 3
            } else if (i + 1 < length) {
                // Encode two numeric letters in seven bits.
                val num2 = content[i + 1] - '0'
                bits.appendBits(num1 * 10 + num2, 7)
                i += 2
            } else {
                // Encode one numeric letter in four bits.
                bits.appendBits(num1, 4)
                i++
            }
        }
    }

    private fun appendAlphanumericBytes(content: CharSequence, bits: BitArray) {
        val length = content.length
        var i = 0
        while (i < length) {
            val code1 = alphanumericCode(content[i])
            if (i + 1 < length) {
                val code2 = alphanumericCode(content[i + 1])
                // Encode two alphanumeric letters in 11 bits.
                bits.appendBits(code1 * 45 + code2, 11)
                i += 2
            } else {
                // Encode one alphanumeric letter in six bits.
                bits.appendBits(code1, 6)
                i++
            }
        }
    }

    /** The mask whose matrix ISO 18004's four penalty rules score lowest. */
    private fun chooseMaskPattern(bits: BitArray, version: Version, matrix: ByteMatrix): Int {
        var minPenalty = Int.MAX_VALUE // Lower penalty is better.
        var bestMaskPattern = -1
        // We try all mask patterns to choose the best one.
        for (maskPattern in 0 until 8) {
            MatrixUtil.buildMatrix(bits, EC_LEVEL, version, maskPattern, matrix)
            val penalty = MaskUtil.penalty(matrix)
            if (penalty < minPenalty) {
                minPenalty = penalty
                bestMaskPattern = maskPattern
            }
        }
        return bestMaskPattern
    }
}

/** A growable array of bits, the encoder's; bit 0 is the first appended. */
internal class BitArray {
    private var bits = IntArray(1)
    var size = 0
        private set

    val sizeInBytes: Int get() = (size + 7) / 8

    operator fun get(i: Int): Boolean = (bits[i / 32] and (1 shl (i and 0x1F))) != 0

    private fun ensureCapacity(newSize: Int) {
        if (newSize > bits.size * 32) bits = bits.copyOf(maxOf(bits.size * 2, (newSize + 31) / 32 + 1))
    }

    fun appendBit(bit: Boolean) {
        ensureCapacity(size + 1)
        if (bit) bits[size / 32] = bits[size / 32] or (1 shl (size and 0x1F))
        size++
    }

    /** Appends the least-significant [numBits] of [value], most significant first. */
    fun appendBits(value: Int, numBits: Int) {
        require(numBits in 0..32) { "Num bits must be between 0 and 32" }
        for (numBitsLeft in numBits downTo 1) appendBit(((value shr (numBitsLeft - 1)) and 0x01) == 1)
    }

    fun appendBitArray(other: BitArray) {
        for (i in 0 until other.size) appendBit(other[i])
    }

    /** [numBytes] bytes from [bitOffset], most significant bit first, into [array] at [offset]. */
    fun toBytes(bitOffset: Int, array: ByteArray, offset: Int, numBytes: Int) {
        var at = bitOffset
        for (i in 0 until numBytes) {
            var theByte = 0
            for (j in 0 until 8) {
                if (get(at)) theByte = theByte or (1 shl (7 - j))
                at++
            }
            array[offset + i] = theByte.toByte()
        }
    }

    fun xor(other: BitArray) {
        require(size == other.size) { "Sizes don't match" }
        for (i in 0 until size) if (other[i]) bits[i / 32] = bits[i / 32] xor (1 shl (i and 0x1F))
    }
}

/** The matrix the encoder builds: -1 not yet set, 0 light, 1 dark; `[x, y]`. */
internal class ByteMatrix(val dimension: Int) {
    private val bytes = ByteArray(dimension * dimension)

    operator fun get(x: Int, y: Int): Byte = bytes[y * dimension + x]

    operator fun set(x: Int, y: Int, value: Int) {
        bytes[y * dimension + x] = value.toByte()
    }

    operator fun set(x: Int, y: Int, value: Boolean) = set(x, y, if (value) 1 else 0)

    fun clear(value: Int) = bytes.fill(value.toByte())
}

/** Places a code's function patterns, format and version information and data into a [ByteMatrix]. */
internal object MatrixUtil {
    private val POSITION_DETECTION_PATTERN = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
    )

    private val POSITION_ADJUSTMENT_PATTERN = arrayOf(
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 0, 1, 0, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1),
    )

    /** Type info cells at the left top corner. */
    private val TYPE_INFO_COORDINATES = arrayOf(
        intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3), intArrayOf(8, 4), intArrayOf(8, 5),
        intArrayOf(8, 7), intArrayOf(8, 8), intArrayOf(7, 8), intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8),
        intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8),
    )

    /** From Appendix D in JISX0510:2004 (p. 67) */
    private const val VERSION_INFO_POLY = 0x1f25 // 1 1111 0010 0101

    /** From Appendix C in JISX0510:2004 (p.65). */
    private const val TYPE_INFO_POLY = 0x537
    private const val TYPE_INFO_MASK_PATTERN = 0x5412

    /** Builds the complete matrix of [dataBits] at [ecLevel], [version] and [maskPattern]. */
    fun buildMatrix(dataBits: BitArray, ecLevel: ErrorCorrectionLevel, version: Version, maskPattern: Int, matrix: ByteMatrix) {
        matrix.clear(-1)
        embedBasicPatterns(version, matrix)
        // Type information appear with any version.
        embedTypeInfo(ecLevel, maskPattern, matrix)
        // Version info appear if version >= 7.
        maybeEmbedVersionInfo(version, matrix)
        // Data should be embedded at end.
        embedDataBits(dataBits, maskPattern, matrix)
    }

    /**
     * Embeds the basic patterns: position detection patterns with their separators, the dark dot at the left bottom
     * corner, the position adjustment patterns (version 2 and up), the timing patterns.
     */
    private fun embedBasicPatterns(version: Version, matrix: ByteMatrix) {
        // Let's get started with embedding big squares at corners.
        embedPositionDetectionPatternsAndSeparators(matrix)
        // Then, embed the dark dot at the left bottom corner.
        embedDarkDotAtLeftBottomCorner(matrix)
        // Position adjustment patterns appear if version >= 2.
        maybeEmbedPositionAdjustmentPatterns(version, matrix)
        // Timing patterns should be embedded after position adj. patterns.
        embedTimingPatterns(matrix)
    }

    /** Embeds type information (15 bits: the EC level and the mask), twice. */
    private fun embedTypeInfo(ecLevel: ErrorCorrectionLevel, maskPattern: Int, matrix: ByteMatrix) {
        val typeInfoBits = BitArray()
        makeTypeInfoBits(ecLevel, maskPattern, typeInfoBits)
        for (i in 0 until typeInfoBits.size) {
            // Place bits in LSB to MSB order. LSB (least significant bit) is the last value in "typeInfoBits".
            val bit = typeInfoBits[typeInfoBits.size - 1 - i]
            // Type info bits at the left top corner. See 8.9 of JISX0510:2004 (p.46).
            val coordinates = TYPE_INFO_COORDINATES[i]
            matrix[coordinates[0], coordinates[1]] = bit
            if (i < 8) {
                // Right top corner.
                matrix[matrix.dimension - i - 1, 8] = bit
            } else {
                // Left bottom corner.
                matrix[8, matrix.dimension - 7 + (i - 8)] = bit
            }
        }
    }

    /** Embeds version information (18 bits) twice, from version 7. See 8.10 of JISX0510:2004 (p.47). */
    private fun maybeEmbedVersionInfo(version: Version, matrix: ByteMatrix) {
        if (version.versionNumber < 7) return // Don't need version info.
        val versionInfoBits = BitArray()
        makeVersionInfoBits(version, versionInfoBits)
        var bitIndex = 6 * 3 - 1 // It will decrease from 17 to 0.
        for (i in 0 until 6) {
            for (j in 0 until 3) {
                // Place bits in LSB (least significant bit) to MSB order.
                val bit = versionInfoBits[bitIndex]
                bitIndex--
                // Left bottom corner.
                matrix[i, matrix.dimension - 11 + j] = bit
                // Right bottom corner.
                matrix[matrix.dimension - 11 + j, i] = bit
            }
        }
    }

    /** Embeds the data bits, masked, into the cells left empty, in the two-column zigzag of 8.7 of JISX0510:2004 (p.38). */
    private fun embedDataBits(dataBits: BitArray, maskPattern: Int, matrix: ByteMatrix) {
        var bitIndex = 0
        var direction = -1
        // Start from the right bottom cell.
        var x = matrix.dimension - 1
        var y = matrix.dimension - 1
        while (x > 0) {
            // Skip the vertical timing pattern.
            if (x == 6) x -= 1
            while (y >= 0 && y < matrix.dimension) {
                for (i in 0 until 2) {
                    val xx = x - i
                    // Skip the cell if it's not empty.
                    if (matrix[xx, y].toInt() != -1) continue
                    var bit: Boolean
                    if (bitIndex < dataBits.size) {
                        bit = dataBits[bitIndex]
                        ++bitIndex
                    } else {
                        // Padding bit. If there is no bit left, we'll fill the left cells with 0, as described in 8.4.9
                        // of JISX0510:2004 (p. 24).
                        bit = false
                    }
                    // Skip masking if mask_pattern is -1.
                    if (maskPattern != -1 && MaskUtil.dataMaskBit(maskPattern, xx, y)) bit = !bit
                    matrix[xx, y] = bit
                }
                y += direction
            }
            direction = -direction // Reverse the direction.
            y += direction
            x -= 2 // Move to the left.
        }
        // All bits should be consumed.
        check(bitIndex == dataBits.size) { "Not all bits consumed: $bitIndex/${dataBits.size}" }
    }

    /** The position of the most significant set bit, from 1; 0 for 0. */
    private fun findMSBSet(value: Int): Int = 32 - value.countLeadingZeroBits()

    /**
     * The BCH code of [value] under [poly]: the remainder of `value * x^(degree of poly)` divided by it in GF(2)
     * (ISO 18004 Annex C and D).
     */
    fun calculateBCHCode(valueIn: Int, poly: Int): Int {
        require(poly != 0) { "0 polynomial" }
        // If poly is "1 1111 0010 0101" (version info poly), msbSetInPoly is 13. We'll subtract 1 from 13 to make it
        // 12.
        val msbSetInPoly = findMSBSet(poly)
        var value = valueIn shl (msbSetInPoly - 1)
        // Do the division business using exclusive-or operations.
        while (findMSBSet(value) >= msbSetInPoly) value = value xor (poly shl (findMSBSet(value) - msbSetInPoly))
        // Now the "value" is the remainder (i.e. the BCH code)
        return value
    }

    /** The 15 type information bits of [ecLevel] and [maskPattern], masked (8.9 of JISX0510:2004, p.45). */
    private fun makeTypeInfoBits(ecLevel: ErrorCorrectionLevel, maskPattern: Int, bits: BitArray) {
        require(maskPattern in 0 until 8) { "Invalid mask pattern" }
        val typeInfo = (ecLevel.bits shl 3) or maskPattern
        bits.appendBits(typeInfo, 5)
        val bchCode = calculateBCHCode(typeInfo, TYPE_INFO_POLY)
        bits.appendBits(bchCode, 10)
        val maskBits = BitArray()
        maskBits.appendBits(TYPE_INFO_MASK_PATTERN, 15)
        bits.xor(maskBits)
        check(bits.size == 15) { "should not happen but we got: ${bits.size}" }
    }

    /** The 18 version information bits of [version] (8.10 of JISX0510:2004, p.45). */
    private fun makeVersionInfoBits(version: Version, bits: BitArray) {
        bits.appendBits(version.versionNumber, 6)
        val bchCode = calculateBCHCode(version.versionNumber, VERSION_INFO_POLY)
        bits.appendBits(bchCode, 12)
        check(bits.size == 18) { "should not happen but we got: ${bits.size}" }
    }

    private fun embedTimingPatterns(matrix: ByteMatrix) {
        // -8 is for skipping position detection patterns (7: size), and two horizontal/vertical separation patterns
        // (1: size). Thus, 8 = 7 + 1.
        for (i in 8 until matrix.dimension - 8) {
            val bit = (i + 1) % 2
            // Horizontal line.
            if (matrix[i, 6].toInt() == -1) matrix[i, 6] = bit
            // Vertical line.
            if (matrix[6, i].toInt() == -1) matrix[6, i] = bit
        }
    }

    /** Embeds the dark dot at the left bottom corner (8.9 of JISX0510:2004, p.46). */
    private fun embedDarkDotAtLeftBottomCorner(matrix: ByteMatrix) {
        check(matrix[8, matrix.dimension - 8].toInt() != 0)
        matrix[8, matrix.dimension - 8] = 1
    }

    private fun embedHorizontalSeparationPattern(xStart: Int, yStart: Int, matrix: ByteMatrix) {
        for (x in 0 until 8) {
            check(matrix[xStart + x, yStart].toInt() == -1)
            matrix[xStart + x, yStart] = 0
        }
    }

    private fun embedVerticalSeparationPattern(xStart: Int, yStart: Int, matrix: ByteMatrix) {
        for (y in 0 until 7) {
            check(matrix[xStart, yStart + y].toInt() == -1)
            matrix[xStart, yStart + y] = 0
        }
    }

    private fun embedPositionAdjustmentPattern(xStart: Int, yStart: Int, matrix: ByteMatrix) {
        for (y in 0 until 5) for (x in 0 until 5) matrix[xStart + x, yStart + y] = POSITION_ADJUSTMENT_PATTERN[y][x]
    }

    private fun embedPositionDetectionPattern(xStart: Int, yStart: Int, matrix: ByteMatrix) {
        for (y in 0 until 7) for (x in 0 until 7) matrix[xStart + x, yStart + y] = POSITION_DETECTION_PATTERN[y][x]
    }

    /** Embeds the three position detection patterns and the separators around them. */
    private fun embedPositionDetectionPatternsAndSeparators(matrix: ByteMatrix) {
        // Embed three big squares at corners.
        val pdpWidth = 7
        // Left top corner.
        embedPositionDetectionPattern(0, 0, matrix)
        // Right top corner.
        embedPositionDetectionPattern(matrix.dimension - pdpWidth, 0, matrix)
        // Left bottom corner.
        embedPositionDetectionPattern(0, matrix.dimension - pdpWidth, matrix)
        // Embed horizontal separation patterns around the squares.
        val hspWidth = 8
        // Left top corner.
        embedHorizontalSeparationPattern(0, hspWidth - 1, matrix)
        // Right top corner.
        embedHorizontalSeparationPattern(matrix.dimension - hspWidth, hspWidth - 1, matrix)
        // Left bottom corner.
        embedHorizontalSeparationPattern(0, matrix.dimension - hspWidth, matrix)
        // Embed vertical separation patterns around the squares.
        val vspSize = 7
        // Left top corner.
        embedVerticalSeparationPattern(vspSize, 0, matrix)
        // Right top corner.
        embedVerticalSeparationPattern(matrix.dimension - vspSize - 1, 0, matrix)
        // Left bottom corner.
        embedVerticalSeparationPattern(vspSize, matrix.dimension - vspSize, matrix)
    }

    /** Embeds the position adjustment patterns, version 2 and up, at the centres the decoder's [Version] table names. */
    private fun maybeEmbedPositionAdjustmentPatterns(version: Version, matrix: ByteMatrix) {
        if (version.versionNumber < 2) return // The patterns appear if version >= 2
        val coordinates = version.alignmentPatternCenters
        for (y in coordinates) {
            for (x in coordinates) {
                // If the cell is unset, we embed the position adjustment pattern here. -2 is necessary since the x/y
                // coordinates point to the center of the pattern, not the left top corner.
                if (matrix[x, y].toInt() == -1) embedPositionAdjustmentPattern(x - 2, y - 2, matrix)
            }
        }
    }
}

/** ISO 18004's mask penalty rules (8.8.2) and the data masks of 8.8.1, as the encoder applies them. */
internal object MaskUtil {
    // Penalty weights from section 6.8.2.1
    private const val N1 = 3
    private const val N2 = 3
    private const val N3 = 40
    private const val N4 = 10

    fun penalty(matrix: ByteMatrix): Int = rule1(matrix, true) + rule1(matrix, false) + rule2(matrix) + rule3(matrix) + rule4(matrix)

    /** Rule 2: 2x2 blocks of the same color. */
    private fun rule2(matrix: ByteMatrix): Int {
        var penalty = 0
        val n = matrix.dimension
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val value = matrix[x, y]
                if (value == matrix[x + 1, y] && value == matrix[x, y + 1] && value == matrix[x + 1, y + 1]) penalty++
            }
        }
        return N2 * penalty
    }

    /** Rule 3: 1:1:3:1:1 ratio (dark:light:dark:light:dark) patterns with four light modules on either side. */
    private fun rule3(matrix: ByteMatrix): Int {
        var numPenalties = 0
        val n = matrix.dimension
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (x + 6 < n && matrix[x, y].toInt() == 1 && matrix[x + 1, y].toInt() == 0 && matrix[x + 2, y].toInt() == 1 &&
                    matrix[x + 3, y].toInt() == 1 && matrix[x + 4, y].toInt() == 1 && matrix[x + 5, y].toInt() == 0 &&
                    matrix[x + 6, y].toInt() == 1 && (isWhiteHorizontal(matrix, y, x - 4, x) || isWhiteHorizontal(matrix, y, x + 7, x + 11))
                ) numPenalties++
                if (y + 6 < n && matrix[x, y].toInt() == 1 && matrix[x, y + 1].toInt() == 0 && matrix[x, y + 2].toInt() == 1 &&
                    matrix[x, y + 3].toInt() == 1 && matrix[x, y + 4].toInt() == 1 && matrix[x, y + 5].toInt() == 0 &&
                    matrix[x, y + 6].toInt() == 1 && (isWhiteVertical(matrix, x, y - 4, y) || isWhiteVertical(matrix, x, y + 7, y + 11))
                ) numPenalties++
            }
        }
        return numPenalties * N3
    }

    private fun isWhiteHorizontal(matrix: ByteMatrix, y: Int, from: Int, to: Int): Boolean {
        if (from < 0 || matrix.dimension < to) return false
        for (i in from until to) if (matrix[i, y].toInt() == 1) return false
        return true
    }

    private fun isWhiteVertical(matrix: ByteMatrix, x: Int, from: Int, to: Int): Boolean {
        if (from < 0 || matrix.dimension < to) return false
        for (i in from until to) if (matrix[x, i].toInt() == 1) return false
        return true
    }

    /** Rule 4: the share of dark cells, 10 points per 5 % away from half. */
    private fun rule4(matrix: ByteMatrix): Int {
        var numDarkCells = 0
        val n = matrix.dimension
        for (y in 0 until n) for (x in 0 until n) if (matrix[x, y].toInt() == 1) numDarkCells++
        val numTotalCells = n * n
        val fivePercentVariances = kotlin.math.abs(numDarkCells * 2 - numTotalCells) * 10 / numTotalCells
        return fivePercentVariances * N4
    }

    /** Whether data mask [maskPattern] flips the module at column [x], row [y] (8.8.1 of JISX0510:2004). */
    fun dataMaskBit(maskPattern: Int, x: Int, y: Int): Boolean {
        val intermediate: Int
        val temp: Int
        when (maskPattern) {
            0 -> intermediate = (y + x) and 0x1
            1 -> intermediate = y and 0x1
            2 -> intermediate = x % 3
            3 -> intermediate = (y + x) % 3
            4 -> intermediate = ((y / 2) + (x / 3)) and 0x1
            5 -> {
                temp = y * x
                intermediate = (temp and 0x1) + (temp % 3)
            }
            6 -> {
                temp = y * x
                intermediate = ((temp and 0x1) + (temp % 3)) and 0x1
            }
            7 -> {
                temp = y * x
                intermediate = ((temp % 3) + ((y + x) and 0x1)) and 0x1
            }
            else -> throw IllegalArgumentException("Invalid mask pattern: $maskPattern")
        }
        return intermediate == 0
    }

    /** Rule 1: runs of five or more modules of one colour in a row (or a column), 3 points and 1 more per module. */
    private fun rule1(matrix: ByteMatrix, isHorizontal: Boolean): Int {
        var penalty = 0
        val n = matrix.dimension
        for (i in 0 until n) {
            var numSameBitCells = 0
            var prevBit = -1
            for (j in 0 until n) {
                val bit = (if (isHorizontal) matrix[j, i] else matrix[i, j]).toInt()
                if (bit == prevBit) {
                    numSameBitCells++
                } else {
                    if (numSameBitCells >= 5) penalty += N1 + (numSameBitCells - 5)
                    numSameBitCells = 1 // Include the cell itself.
                    prevBit = bit
                }
            }
            if (numSameBitCells >= 5) penalty += N1 + (numSameBitCells - 5)
        }
        return penalty
    }
}
