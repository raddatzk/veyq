// Ported to Kotlin from ZXing's qrcode/decoder/{Version,ErrorCorrectionLevel,FormatInformation,DataMask,Mode}.java
// (Copyright 2007 ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream commit). Changed: Mode
// keeps the modes the parser reads, a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

/** The four error-correction levels, in the order of ISO 18004's tables (and of [Version]'s blocks). */
internal enum class ErrorCorrectionLevel(val bits: Int) {
    /** ~7 % correction */
    L(0x01),

    /** ~15 % correction */
    M(0x00),

    /** ~25 % correction */
    Q(0x03),

    /** ~30 % correction */
    H(0x02),
    ;

    companion object {
        private val FOR_BITS = arrayOf(M, L, H, Q)

        fun forBits(bits: Int): ErrorCorrectionLevel = FOR_BITS[bits]
    }
}

/** A code's 15 format bits: its error-correction level and data mask, read with up to 3 bits wrong. */
internal class FormatInformation private constructor(formatInfo: Int) {
    val errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.forBits((formatInfo shr 3) and 0x03)
    val dataMask: Int = formatInfo and 0x07

    companion object {
        private const val FORMAT_INFO_MASK_QR = 0x5412

        /** The masked format bits of every level and mask; the index is the 5 unmasked bits. */
        private val FORMAT_INFO_DECODE_LOOKUP = intArrayOf(
            0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
            0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
            0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
            0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED,
        )

        fun numBitsDiffering(a: Int, b: Int): Int = (a xor b).countOneBits()

        /** The two copies a code carries, read; null when neither is within 3 bits of a valid format. */
        fun decode(maskedFormatInfo1: Int, maskedFormatInfo2: Int): FormatInformation? =
            doDecode(maskedFormatInfo1, maskedFormatInfo2)
                // Should return null, but, some QR codes apparently do not mask this info. Try again by actually
                // masking the pattern first
                ?: doDecode(maskedFormatInfo1 xor FORMAT_INFO_MASK_QR, maskedFormatInfo2 xor FORMAT_INFO_MASK_QR)

        private fun doDecode(maskedFormatInfo1: Int, maskedFormatInfo2: Int): FormatInformation? {
            // Find the int in FORMAT_INFO_DECODE_LOOKUP with fewest bits differing
            var bestDifference = Int.MAX_VALUE
            var bestFormatInfo = 0
            for ((decoded, targetInfo) in FORMAT_INFO_DECODE_LOOKUP.withIndex()) {
                if (targetInfo == maskedFormatInfo1 || targetInfo == maskedFormatInfo2) return FormatInformation(decoded)
                var bitsDifference = numBitsDiffering(maskedFormatInfo1, targetInfo)
                if (bitsDifference < bestDifference) {
                    bestFormatInfo = decoded
                    bestDifference = bitsDifference
                }
                if (maskedFormatInfo1 != maskedFormatInfo2) {
                    // also try the other option
                    bitsDifference = numBitsDiffering(maskedFormatInfo2, targetInfo)
                    if (bitsDifference < bestDifference) {
                        bestFormatInfo = decoded
                        bestDifference = bitsDifference
                    }
                }
            }
            // Hamming distance of the 32 masked codes is 7, by construction, so <= 3 bits differing means we found a
            // match
            return if (bestDifference <= 3) FormatInformation(bestFormatInfo) else null
        }
    }
}

/** The eight data masks of ISO 18004 6.8.1, each a predicate on (row [i], column [j]). */
internal enum class DataMask {
    DATA_MASK_000 { override fun isMasked(i: Int, j: Int) = ((i + j) and 0x01) == 0 },
    DATA_MASK_001 { override fun isMasked(i: Int, j: Int) = (i and 0x01) == 0 },
    DATA_MASK_010 { override fun isMasked(i: Int, j: Int) = j % 3 == 0 },
    DATA_MASK_011 { override fun isMasked(i: Int, j: Int) = (i + j) % 3 == 0 },
    DATA_MASK_100 { override fun isMasked(i: Int, j: Int) = (((i / 2) + (j / 3)) and 0x01) == 0 },
    DATA_MASK_101 { override fun isMasked(i: Int, j: Int) = (i * j) % 6 == 0 },
    DATA_MASK_110 { override fun isMasked(i: Int, j: Int) = ((i * j) % 6) < 3 },
    DATA_MASK_111 { override fun isMasked(i: Int, j: Int) = ((i + j + ((i * j) % 3)) and 0x01) == 0 },
    ;

    abstract fun isMasked(i: Int, j: Int): Boolean

    /** Flips every module the mask covers: masking and unmasking are the same. */
    fun unmaskBitMatrix(bits: BitMatrix, dimension: Int) {
        for (i in 0 until dimension) for (j in 0 until dimension) if (isMasked(i, j)) bits.flip(j, i)
    }
}

/** A data segment's mode, its 4 bits and its character count's width per version range. */
internal enum class Mode(private val characterCountBitsForVersions: IntArray, val bits: Int) {
    TERMINATOR(intArrayOf(0, 0, 0), 0x00), // Not really a mode...
    NUMERIC(intArrayOf(10, 12, 14), 0x01),
    ALPHANUMERIC(intArrayOf(9, 11, 13), 0x02),
    STRUCTURED_APPEND(intArrayOf(0, 0, 0), 0x03), // Not supported
    BYTE(intArrayOf(8, 16, 16), 0x04),
    ECI(intArrayOf(0, 0, 0), 0x07), // character counts don't apply
    KANJI(intArrayOf(8, 10, 12), 0x08),
    FNC1_FIRST_POSITION(intArrayOf(0, 0, 0), 0x05),
    FNC1_SECOND_POSITION(intArrayOf(0, 0, 0), 0x09),

    /** See GBT 18284-2000; "Hanzi" is a transliteration of this mode name. */
    HANZI(intArrayOf(8, 10, 12), 0x0D),
    ;

    fun characterCountBits(version: Version): Int {
        val number = version.versionNumber
        val offset = if (number <= 9) 0 else if (number <= 26) 1 else 2
        return characterCountBitsForVersions[offset]
    }

    companion object {
        fun forBits(bits: Int): Mode = entries.firstOrNull { it.bits == bits } ?: throw QrReadException("mode $bits")
    }
}

/** A version's error-correction blocks at one level: [ecCodewordsPerBlock] for each block, the blocks in [ecBlocks]. */
internal class ECBlocks(val ecCodewordsPerBlock: Int, vararg val ecBlocks: ECB) {
    val numBlocks: Int get() = ecBlocks.sumOf { it.count }
    val totalECCodewords: Int get() = ecCodewordsPerBlock * numBlocks
}

/** [count] blocks of [dataCodewords] data codewords each. */
internal class ECB(val count: Int, val dataCodewords: Int)

/** One of the 40 versions (sizes) of a QR code, ISO 18004:2006 6.5.1 Table 9. */
internal class Version private constructor(
    val versionNumber: Int,
    val alignmentPatternCenters: IntArray,
    private vararg val ecBlocks: ECBlocks,
) {
    val totalCodewords: Int = ecBlocks[0].let { first -> first.ecBlocks.sumOf { it.count * (it.dataCodewords + first.ecCodewordsPerBlock) } }
    val dimension: Int get() = 17 + 4 * versionNumber

    fun ecBlocksForLevel(ecLevel: ErrorCorrectionLevel): ECBlocks = ecBlocks[ecLevel.ordinal]

    /** The modules that are function patterns (finders, timing, alignment, format and version), not data. */
    fun buildFunctionPattern(): BitMatrix {
        val dimension = dimension
        val bitMatrix = BitMatrix(dimension)
        // Top left finder pattern + separator + format
        bitMatrix.setRegion(0, 0, 9, 9)
        // Top right finder pattern + separator + format
        bitMatrix.setRegion(dimension - 8, 0, 8, 9)
        // Bottom left finder pattern + separator + format
        bitMatrix.setRegion(0, dimension - 8, 9, 8)
        // Alignment patterns
        val max = alignmentPatternCenters.size
        for (x in 0 until max) {
            val i = alignmentPatternCenters[x] - 2
            for (y in 0 until max) {
                if ((x != 0 || (y != 0 && y != max - 1)) && (x != max - 1 || y != 0)) bitMatrix.setRegion(alignmentPatternCenters[y] - 2, i, 5, 5)
                // else no o alignment patterns near the three finder patterns
            }
        }
        // Vertical timing pattern
        bitMatrix.setRegion(6, 9, 1, dimension - 17)
        // Horizontal timing pattern
        bitMatrix.setRegion(9, 6, dimension - 17, 1)
        if (versionNumber > 6) {
            // Version info, top right
            bitMatrix.setRegion(dimension - 11, 0, 3, 6)
            // Version info, bottom left
            bitMatrix.setRegion(0, dimension - 11, 6, 3)
        }
        return bitMatrix
    }

    override fun toString(): String = versionNumber.toString()

    companion object {
        /** The 18-bit version information of versions 7 to 40, with its BCH bits. */
        private val VERSION_DECODE_INFO = intArrayOf(
            0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6, 0x0C762, 0x0D847, 0x0E60D, 0x0F928, 0x10B78,
            0x1145D, 0x12A17, 0x13532, 0x149A6, 0x15683, 0x168C9, 0x177EC, 0x18EC4, 0x191E1, 0x1AFAB,
            0x1B08E, 0x1CC1A, 0x1D33F, 0x1ED75, 0x1F250, 0x209D5, 0x216F0, 0x228BA, 0x2379F, 0x24B0B,
            0x2542E, 0x26A64, 0x27541, 0x28C69,
        )

        /** The version a dimension estimated from the finder patterns suggests. */
        fun provisionalForDimension(dimension: Int): Version {
            if (dimension % 4 != 1) throw QrReadException("dimension $dimension")
            val number = (dimension - 17) / 4
            if (number < 1 || number > 40) throw QrReadException("dimension $dimension")
            return forNumber(number)
        }

        fun forNumber(versionNumber: Int): Version {
            require(versionNumber in 1..40) { "version $versionNumber" }
            return VERSIONS[versionNumber - 1]
        }

        /** The version 18 bits name, read with up to 3 bits wrong; null when none is that close. */
        fun decodeVersionInformation(versionBits: Int): Version? {
            var bestDifference = Int.MAX_VALUE
            var bestVersion = 0
            for (i in VERSION_DECODE_INFO.indices) {
                val targetVersion = VERSION_DECODE_INFO[i]
                // Do the version info bits match exactly? done.
                if (targetVersion == versionBits) return forNumber(i + 7)
                // Otherwise see if this is the closest to a real version info bit string we have seen so far
                val bitsDifference = FormatInformation.numBitsDiffering(versionBits, targetVersion)
                if (bitsDifference < bestDifference) {
                    bestVersion = i + 7
                    bestDifference = bitsDifference
                }
            }
            // We can tolerate up to 3 bits of error since no two version info codewords will differ in less than 8
            // bits.
            return if (bestDifference <= 3) forNumber(bestVersion) else null
        }

        /** ISO 18004:2006 6.5.1 Table 9. */
        private val VERSIONS: Array<Version> = arrayOf(
        Version(1, intArrayOf(),
            ECBlocks(7, ECB(1, 19)),
            ECBlocks(10, ECB(1, 16)),
            ECBlocks(13, ECB(1, 13)),
            ECBlocks(17, ECB(1, 9))),
        Version(2, intArrayOf(6, 18),
            ECBlocks(10, ECB(1, 34)),
            ECBlocks(16, ECB(1, 28)),
            ECBlocks(22, ECB(1, 22)),
            ECBlocks(28, ECB(1, 16))),
        Version(3, intArrayOf(6, 22),
            ECBlocks(15, ECB(1, 55)),
            ECBlocks(26, ECB(1, 44)),
            ECBlocks(18, ECB(2, 17)),
            ECBlocks(22, ECB(2, 13))),
        Version(4, intArrayOf(6, 26),
            ECBlocks(20, ECB(1, 80)),
            ECBlocks(18, ECB(2, 32)),
            ECBlocks(26, ECB(2, 24)),
            ECBlocks(16, ECB(4, 9))),
        Version(5, intArrayOf(6, 30),
            ECBlocks(26, ECB(1, 108)),
            ECBlocks(24, ECB(2, 43)),
            ECBlocks(18, ECB(2, 15),
                ECB(2, 16)),
            ECBlocks(22, ECB(2, 11),
                ECB(2, 12))),
        Version(6, intArrayOf(6, 34),
            ECBlocks(18, ECB(2, 68)),
            ECBlocks(16, ECB(4, 27)),
            ECBlocks(24, ECB(4, 19)),
            ECBlocks(28, ECB(4, 15))),
        Version(7, intArrayOf(6, 22, 38),
            ECBlocks(20, ECB(2, 78)),
            ECBlocks(18, ECB(4, 31)),
            ECBlocks(18, ECB(2, 14),
                ECB(4, 15)),
            ECBlocks(26, ECB(4, 13),
                ECB(1, 14))),
        Version(8, intArrayOf(6, 24, 42),
            ECBlocks(24, ECB(2, 97)),
            ECBlocks(22, ECB(2, 38),
                ECB(2, 39)),
            ECBlocks(22, ECB(4, 18),
                ECB(2, 19)),
            ECBlocks(26, ECB(4, 14),
                ECB(2, 15))),
        Version(9, intArrayOf(6, 26, 46),
            ECBlocks(30, ECB(2, 116)),
            ECBlocks(22, ECB(3, 36),
                ECB(2, 37)),
            ECBlocks(20, ECB(4, 16),
                ECB(4, 17)),
            ECBlocks(24, ECB(4, 12),
                ECB(4, 13))),
        Version(10, intArrayOf(6, 28, 50),
            ECBlocks(18, ECB(2, 68),
                ECB(2, 69)),
            ECBlocks(26, ECB(4, 43),
                ECB(1, 44)),
            ECBlocks(24, ECB(6, 19),
                ECB(2, 20)),
            ECBlocks(28, ECB(6, 15),
                ECB(2, 16))),
        Version(11, intArrayOf(6, 30, 54),
            ECBlocks(20, ECB(4, 81)),
            ECBlocks(30, ECB(1, 50),
                ECB(4, 51)),
            ECBlocks(28, ECB(4, 22),
                ECB(4, 23)),
            ECBlocks(24, ECB(3, 12),
                ECB(8, 13))),
        Version(12, intArrayOf(6, 32, 58),
            ECBlocks(24, ECB(2, 92),
                ECB(2, 93)),
            ECBlocks(22, ECB(6, 36),
                ECB(2, 37)),
            ECBlocks(26, ECB(4, 20),
                ECB(6, 21)),
            ECBlocks(28, ECB(7, 14),
                ECB(4, 15))),
        Version(13, intArrayOf(6, 34, 62),
            ECBlocks(26, ECB(4, 107)),
            ECBlocks(22, ECB(8, 37),
                ECB(1, 38)),
            ECBlocks(24, ECB(8, 20),
                ECB(4, 21)),
            ECBlocks(22, ECB(12, 11),
                ECB(4, 12))),
        Version(14, intArrayOf(6, 26, 46, 66),
            ECBlocks(30, ECB(3, 115),
                ECB(1, 116)),
            ECBlocks(24, ECB(4, 40),
                ECB(5, 41)),
            ECBlocks(20, ECB(11, 16),
                ECB(5, 17)),
            ECBlocks(24, ECB(11, 12),
                ECB(5, 13))),
        Version(15, intArrayOf(6, 26, 48, 70),
            ECBlocks(22, ECB(5, 87),
                ECB(1, 88)),
            ECBlocks(24, ECB(5, 41),
                ECB(5, 42)),
            ECBlocks(30, ECB(5, 24),
                ECB(7, 25)),
            ECBlocks(24, ECB(11, 12),
                ECB(7, 13))),
        Version(16, intArrayOf(6, 26, 50, 74),
            ECBlocks(24, ECB(5, 98),
                ECB(1, 99)),
            ECBlocks(28, ECB(7, 45),
                ECB(3, 46)),
            ECBlocks(24, ECB(15, 19),
                ECB(2, 20)),
            ECBlocks(30, ECB(3, 15),
                ECB(13, 16))),
        Version(17, intArrayOf(6, 30, 54, 78),
            ECBlocks(28, ECB(1, 107),
                ECB(5, 108)),
            ECBlocks(28, ECB(10, 46),
                ECB(1, 47)),
            ECBlocks(28, ECB(1, 22),
                ECB(15, 23)),
            ECBlocks(28, ECB(2, 14),
                ECB(17, 15))),
        Version(18, intArrayOf(6, 30, 56, 82),
            ECBlocks(30, ECB(5, 120),
                ECB(1, 121)),
            ECBlocks(26, ECB(9, 43),
                ECB(4, 44)),
            ECBlocks(28, ECB(17, 22),
                ECB(1, 23)),
            ECBlocks(28, ECB(2, 14),
                ECB(19, 15))),
        Version(19, intArrayOf(6, 30, 58, 86),
            ECBlocks(28, ECB(3, 113),
                ECB(4, 114)),
            ECBlocks(26, ECB(3, 44),
                ECB(11, 45)),
            ECBlocks(26, ECB(17, 21),
                ECB(4, 22)),
            ECBlocks(26, ECB(9, 13),
                ECB(16, 14))),
        Version(20, intArrayOf(6, 34, 62, 90),
            ECBlocks(28, ECB(3, 107),
                ECB(5, 108)),
            ECBlocks(26, ECB(3, 41),
                ECB(13, 42)),
            ECBlocks(30, ECB(15, 24),
                ECB(5, 25)),
            ECBlocks(28, ECB(15, 15),
                ECB(10, 16))),
        Version(21, intArrayOf(6, 28, 50, 72, 94),
            ECBlocks(28, ECB(4, 116),
                ECB(4, 117)),
            ECBlocks(26, ECB(17, 42)),
            ECBlocks(28, ECB(17, 22),
                ECB(6, 23)),
            ECBlocks(30, ECB(19, 16),
                ECB(6, 17))),
        Version(22, intArrayOf(6, 26, 50, 74, 98),
            ECBlocks(28, ECB(2, 111),
                ECB(7, 112)),
            ECBlocks(28, ECB(17, 46)),
            ECBlocks(30, ECB(7, 24),
                ECB(16, 25)),
            ECBlocks(24, ECB(34, 13))),
        Version(23, intArrayOf(6, 30, 54, 78, 102),
            ECBlocks(30, ECB(4, 121),
                ECB(5, 122)),
            ECBlocks(28, ECB(4, 47),
                ECB(14, 48)),
            ECBlocks(30, ECB(11, 24),
                ECB(14, 25)),
            ECBlocks(30, ECB(16, 15),
                ECB(14, 16))),
        Version(24, intArrayOf(6, 28, 54, 80, 106),
            ECBlocks(30, ECB(6, 117),
                ECB(4, 118)),
            ECBlocks(28, ECB(6, 45),
                ECB(14, 46)),
            ECBlocks(30, ECB(11, 24),
                ECB(16, 25)),
            ECBlocks(30, ECB(30, 16),
                ECB(2, 17))),
        Version(25, intArrayOf(6, 32, 58, 84, 110),
            ECBlocks(26, ECB(8, 106),
                ECB(4, 107)),
            ECBlocks(28, ECB(8, 47),
                ECB(13, 48)),
            ECBlocks(30, ECB(7, 24),
                ECB(22, 25)),
            ECBlocks(30, ECB(22, 15),
                ECB(13, 16))),
        Version(26, intArrayOf(6, 30, 58, 86, 114),
            ECBlocks(28, ECB(10, 114),
                ECB(2, 115)),
            ECBlocks(28, ECB(19, 46),
                ECB(4, 47)),
            ECBlocks(28, ECB(28, 22),
                ECB(6, 23)),
            ECBlocks(30, ECB(33, 16),
                ECB(4, 17))),
        Version(27, intArrayOf(6, 34, 62, 90, 118),
            ECBlocks(30, ECB(8, 122),
                ECB(4, 123)),
            ECBlocks(28, ECB(22, 45),
                ECB(3, 46)),
            ECBlocks(30, ECB(8, 23),
                ECB(26, 24)),
            ECBlocks(30, ECB(12, 15),
                ECB(28, 16))),
        Version(28, intArrayOf(6, 26, 50, 74, 98, 122),
            ECBlocks(30, ECB(3, 117),
                ECB(10, 118)),
            ECBlocks(28, ECB(3, 45),
                ECB(23, 46)),
            ECBlocks(30, ECB(4, 24),
                ECB(31, 25)),
            ECBlocks(30, ECB(11, 15),
                ECB(31, 16))),
        Version(29, intArrayOf(6, 30, 54, 78, 102, 126),
            ECBlocks(30, ECB(7, 116),
                ECB(7, 117)),
            ECBlocks(28, ECB(21, 45),
                ECB(7, 46)),
            ECBlocks(30, ECB(1, 23),
                ECB(37, 24)),
            ECBlocks(30, ECB(19, 15),
                ECB(26, 16))),
        Version(30, intArrayOf(6, 26, 52, 78, 104, 130),
            ECBlocks(30, ECB(5, 115),
                ECB(10, 116)),
            ECBlocks(28, ECB(19, 47),
                ECB(10, 48)),
            ECBlocks(30, ECB(15, 24),
                ECB(25, 25)),
            ECBlocks(30, ECB(23, 15),
                ECB(25, 16))),
        Version(31, intArrayOf(6, 30, 56, 82, 108, 134),
            ECBlocks(30, ECB(13, 115),
                ECB(3, 116)),
            ECBlocks(28, ECB(2, 46),
                ECB(29, 47)),
            ECBlocks(30, ECB(42, 24),
                ECB(1, 25)),
            ECBlocks(30, ECB(23, 15),
                ECB(28, 16))),
        Version(32, intArrayOf(6, 34, 60, 86, 112, 138),
            ECBlocks(30, ECB(17, 115)),
            ECBlocks(28, ECB(10, 46),
                ECB(23, 47)),
            ECBlocks(30, ECB(10, 24),
                ECB(35, 25)),
            ECBlocks(30, ECB(19, 15),
                ECB(35, 16))),
        Version(33, intArrayOf(6, 30, 58, 86, 114, 142),
            ECBlocks(30, ECB(17, 115),
                ECB(1, 116)),
            ECBlocks(28, ECB(14, 46),
                ECB(21, 47)),
            ECBlocks(30, ECB(29, 24),
                ECB(19, 25)),
            ECBlocks(30, ECB(11, 15),
                ECB(46, 16))),
        Version(34, intArrayOf(6, 34, 62, 90, 118, 146),
            ECBlocks(30, ECB(13, 115),
                ECB(6, 116)),
            ECBlocks(28, ECB(14, 46),
                ECB(23, 47)),
            ECBlocks(30, ECB(44, 24),
                ECB(7, 25)),
            ECBlocks(30, ECB(59, 16),
                ECB(1, 17))),
        Version(35, intArrayOf(6, 30, 54, 78, 102, 126, 150),
            ECBlocks(30, ECB(12, 121),
                ECB(7, 122)),
            ECBlocks(28, ECB(12, 47),
                ECB(26, 48)),
            ECBlocks(30, ECB(39, 24),
                ECB(14, 25)),
            ECBlocks(30, ECB(22, 15),
                ECB(41, 16))),
        Version(36, intArrayOf(6, 24, 50, 76, 102, 128, 154),
            ECBlocks(30, ECB(6, 121),
                ECB(14, 122)),
            ECBlocks(28, ECB(6, 47),
                ECB(34, 48)),
            ECBlocks(30, ECB(46, 24),
                ECB(10, 25)),
            ECBlocks(30, ECB(2, 15),
                ECB(64, 16))),
        Version(37, intArrayOf(6, 28, 54, 80, 106, 132, 158),
            ECBlocks(30, ECB(17, 122),
                ECB(4, 123)),
            ECBlocks(28, ECB(29, 46),
                ECB(14, 47)),
            ECBlocks(30, ECB(49, 24),
                ECB(10, 25)),
            ECBlocks(30, ECB(24, 15),
                ECB(46, 16))),
        Version(38, intArrayOf(6, 32, 58, 84, 110, 136, 162),
            ECBlocks(30, ECB(4, 122),
                ECB(18, 123)),
            ECBlocks(28, ECB(13, 46),
                ECB(32, 47)),
            ECBlocks(30, ECB(48, 24),
                ECB(14, 25)),
            ECBlocks(30, ECB(42, 15),
                ECB(32, 16))),
        Version(39, intArrayOf(6, 26, 54, 82, 110, 138, 166),
            ECBlocks(30, ECB(20, 117),
                ECB(4, 118)),
            ECBlocks(28, ECB(40, 47),
                ECB(7, 48)),
            ECBlocks(30, ECB(43, 24),
                ECB(22, 25)),
            ECBlocks(30, ECB(10, 15),
                ECB(67, 16))),
        Version(40, intArrayOf(6, 30, 58, 86, 114, 142, 170),
            ECBlocks(30, ECB(19, 118),
                ECB(6, 119)),
            ECBlocks(28, ECB(18, 47),
                ECB(31, 48)),
            ECBlocks(30, ECB(34, 24),
                ECB(34, 25)),
            ECBlocks(30, ECB(20, 15),
                ECB(61, 16))),
        )
    }
}
