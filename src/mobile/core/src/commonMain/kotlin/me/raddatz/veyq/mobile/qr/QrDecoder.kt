// Ported to Kotlin from ZXing's qrcode/QRCodeReader.java and qrcode/decoder/{Decoder,BitMatrixParser,DataBlock}.java
// (Copyright 2007 ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream commit). Changed: one
// entry point over a greyscale frame that also tries the frame inverted; no result metadata; a failure is null.
package me.raddatz.veyq.mobile.qr

import kotlin.math.roundToInt

/**
 * A greyscale frame: one byte of luminance per pixel (0 black, 255 white), [height] rows of [width] pixels, each row
 * starting [rowStride] bytes after the one before — the Y plane of a camera's YUV frame, or a still image turned grey.
 */
class QrFrame(val width: Int, val height: Int, val rowStride: Int, val luminance: ByteArray) {
    init {
        require(width > 0 && height > 0) { "a frame of ${width}x$height" }
        require(rowStride >= width) { "a row stride of $rowStride under the width $width" }
        require(luminance.size.toLong() >= rowStride.toLong() * (height - 1) + width) { "${luminance.size} bytes for ${width}x$height at a stride of $rowStride" }
    }

    /** The luminance packed row after row with no padding, as the binarizer reads it. */
    internal fun packed(): ByteArray {
        if (rowStride == width && luminance.size == width * height) return luminance
        val out = ByteArray(width * height)
        for (y in 0 until height) luminance.copyInto(out, y * width, y * rowStride, y * rowStride + width)
        return out
    }
}

/**
 * The one QR reader of every app (ADR 0136 decision 1): ZXing's QR path ported to common Kotlin, so a frame an attacker
 * points the camera at is parsed by memory-safe code in the process that holds the Master Key.
 */
object QrDecoder {
    /**
     * The text of the QR code in [frame], or null when it holds none that reads — a code that is not there, is cut off,
     * has more errors than its correction holds, or is in a character set no veyq code uses. Tries the frame as it is
     * and, failing that, inverted (a code drawn light on dark); each mirrored, as upstream's decoder does.
     * [tryHarder] scans every third row for the finder patterns rather than skipping by the frame's height: slower, for
     * a still image read once rather than a camera's stream.
     */
    fun decode(frame: QrFrame, tryHarder: Boolean = false): String? = content(frame, tryHarder)?.text

    /** [decode]'s content, segment by segment: what the corpus test reads, whose texts include Shift_JIS. */
    internal fun content(frame: QrFrame, tryHarder: Boolean): QrContent? {
        val matrix = try {
            Binarizer.blackMatrix(frame.packed(), frame.width, frame.height)
        } catch (_: QrReadException) {
            return null
        }
        return read(matrix, tryHarder) ?: run {
            matrix.flip()
            read(matrix, tryHarder)
        }
    }

    private fun read(matrix: BitMatrix, tryHarder: Boolean): QrContent? {
        val finder = FinderPatternFinder(matrix)
        attempt { CodeDecoder.decode(Detector.detect(matrix, finder.find(tryHarder))) }?.let { return it }
        if (!finder.stoppedEarly) return null
        // Not upstream's: ZXing stops scanning once three confirmed finder patterns agree, and in a crisp code a run of
        // data modules can pass for one (measured: the core's own `veyqkit:` code at 3 px a module, which ZXing does not
        // read either). Where the first reading stopped early and failed, the whole frame is scanned and the patterns
        // that fit best are read; a frame with no code never stopped early, so it costs nothing more.
        return attempt { CodeDecoder.decode(Detector.detect(matrix, FinderPatternFinder(matrix).find(tryHarder, exhaustive = true))) }
    }

    private inline fun attempt(read: () -> QrContent): QrContent? = try {
        read()
    } catch (_: QrReadException) {
        null
    } catch (_: IllegalArgumentException) {
        // A table lookup outside its range on a malformed code: no code, as upstream maps it.
        null
    }

    /**
     * The modules of a code that fills [matrix] squarely with no rotation or perspective (ZXing's `PURE_BARCODE`): what
     * upstream's black-box harness tries before the detector. Only the corpus test reads it.
     */
    internal fun pure(frame: QrFrame): QrContent? = try {
        CodeDecoder.decode(extractPureBits(Binarizer.blackMatrix(frame.packed(), frame.width, frame.height)))
    } catch (_: QrReadException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun extractPureBits(image: BitMatrix): BitMatrix {
        val leftTopBlack = image.topLeftOnBit()
        val rightBottomBlack = image.bottomRightOnBit()
        if (leftTopBlack == null || rightBottomBlack == null) throw QrReadException("blank")
        val moduleSize = moduleSize(leftTopBlack, image)
        var top = leftTopBlack[1]
        val bottom = rightBottomBlack[1]
        var left = leftTopBlack[0]
        var right = rightBottomBlack[0]
        // Sanity check!
        if (left >= right || top >= bottom) throw QrReadException("no square")
        if (bottom - top != right - left) {
            // Special case, where bottom-right module wasn't black so we found something else in the last row. Assume
            // it's a square, so use height as the width
            right = left + (bottom - top)
            // Abort if that would not make sense -- off image
            if (right >= image.width) throw QrReadException("no square")
        }
        val matrixWidth = ((right - left + 1) / moduleSize).roundToInt()
        val matrixHeight = ((bottom - top + 1) / moduleSize).roundToInt()
        if (matrixWidth <= 0 || matrixHeight <= 0) throw QrReadException("no modules")
        if (matrixHeight != matrixWidth) throw QrReadException("not square") // Only possibly decode square regions
        // Push in the "border" by half the module width so that we start sampling in the middle of the module. Just in
        // case the image is a little off, this will help recover.
        val nudge = (moduleSize / 2.0f).toInt()
        top += nudge
        left += nudge
        // But careful that this does not sample off the edge. "right" is the farthest-right valid pixel location --
        // right+1 is not necessarily. This is positive by how much the inner x loop below would be too large
        val nudgedTooFarRight = left + ((matrixWidth - 1) * moduleSize).toInt() - right
        if (nudgedTooFarRight > 0) {
            if (nudgedTooFarRight > nudge) throw QrReadException("off the image") // Neither way fits; abort
            left -= nudgedTooFarRight
        }
        // See logic above
        val nudgedTooFarDown = top + ((matrixHeight - 1) * moduleSize).toInt() - bottom
        if (nudgedTooFarDown > 0) {
            if (nudgedTooFarDown > nudge) throw QrReadException("off the image") // Neither way fits; abort
            top -= nudgedTooFarDown
        }
        // Now just read off the bits
        val bits = BitMatrix(matrixWidth, matrixHeight)
        for (y in 0 until matrixHeight) {
            val iOffset = top + (y * moduleSize).toInt()
            for (x in 0 until matrixWidth) if (image[left + (x * moduleSize).toInt(), iOffset]) bits.set(x, y)
        }
        return bits
    }

    private fun moduleSize(leftTopBlack: IntArray, image: BitMatrix): Float {
        val height = image.height
        val width = image.width
        var x = leftTopBlack[0]
        var y = leftTopBlack[1]
        var inBlack = true
        var transitions = 0
        while (x < width && y < height) {
            if (inBlack != image[x, y]) {
                if (++transitions == 5) break
                inBlack = !inBlack
            }
            x++
            y++
        }
        if (x == width || y == height) throw QrReadException("no finder pattern")
        return (x - leftTopBlack[0]) / 7.0f
    }
}

/** Decodes a code's sampled modules: version, format, unmasking, codewords, Reed–Solomon, and the bit stream. */
internal object CodeDecoder {
    /** The content of the code in [bits]; read mirrored when it does not read as it is (upstream's retry). */
    fun decode(bits: BitMatrix): QrContent {
        val parser = BitMatrixParser(bits)
        val first: QrReadException
        try {
            return decode(parser)
        } catch (e: QrReadException) {
            first = e
        }
        try {
            // Revert the bit matrix
            parser.remask()
            // Will be attempting a mirrored reading of the version and format info.
            parser.setMirror(true)
            // Preemptively read the version.
            parser.readVersion()
            // Preemptively read the format information.
            parser.readFormatInformation()
            // Since we're here, this means we have successfully detected some kind of version and format information
            // when mirrored. This is a good sign, that the QR code may be mirrored, and we should try once more with a
            // mirrored content.
            parser.mirror()
            return decode(parser)
        } catch (_: QrReadException) {
            // Throw the exception from the original reading
            throw first
        }
    }

    private fun decode(parser: BitMatrixParser): QrContent {
        val version = parser.readVersion()
        val ecLevel = parser.readFormatInformation().errorCorrectionLevel
        // Read codewords
        val codewords = parser.readCodewords()
        // Separate into data blocks
        val dataBlocks = DataBlock.of(codewords, version, ecLevel)
        // Count total number of data bytes
        val resultBytes = ByteArray(dataBlocks.sumOf { it.numDataCodewords })
        var resultOffset = 0
        var errorsCorrected = 0
        // Error-correct and copy data blocks together into a stream of bytes
        for (dataBlock in dataBlocks) {
            val codewordBytes = dataBlock.codewords
            val numDataCodewords = dataBlock.numDataCodewords
            errorsCorrected += correctErrors(codewordBytes, numDataCodewords)
            for (i in 0 until numDataCodewords) resultBytes[resultOffset++] = codewordBytes[i]
        }
        // Decode the contents of that stream of bytes
        return BitStreamParser.decode(resultBytes, version, errorsCorrected)
    }

    /** Corrects a block's data codewords in place with its EC codewords. */
    private fun correctErrors(codewordBytes: ByteArray, numDataCodewords: Int): Int {
        // First read into an array of ints
        val codewordsInts = IntArray(codewordBytes.size) { codewordBytes[it].toInt() and 0xFF }
        val errorsCorrected = ReedSolomonDecoder.decodeWithECCount(codewordsInts, codewordBytes.size - numDataCodewords)
        // Copy back into array of bytes -- only need to worry about the bytes that were data. We don't care about
        // errors in the error-correction codewords
        for (i in 0 until numDataCodewords) codewordBytes[i] = codewordsInts[i].toByte()
        return errorsCorrected
    }
}

/** Reads the version, format and codewords out of a code's modules. */
internal class BitMatrixParser(private val bitMatrix: BitMatrix) {
    private var parsedVersion: Version? = null
    private var parsedFormatInfo: FormatInformation? = null
    private var mirror = false

    init {
        val dimension = bitMatrix.height
        if (dimension < 21 || (dimension and 0x03) != 1) throw QrReadException("dimension $dimension")
    }

    /** The format information from its two copies (around the top-left finder, and split between the others). */
    fun readFormatInformation(): FormatInformation {
        parsedFormatInfo?.let { return it }
        // Read top-left format info bits
        var formatInfoBits1 = 0
        for (i in 0 until 6) formatInfoBits1 = copyBit(i, 8, formatInfoBits1)
        // .. and skip a bit in the timing pattern ...
        formatInfoBits1 = copyBit(7, 8, formatInfoBits1)
        formatInfoBits1 = copyBit(8, 8, formatInfoBits1)
        formatInfoBits1 = copyBit(8, 7, formatInfoBits1)
        // .. and skip a bit in the timing pattern ...
        for (j in 5 downTo 0) formatInfoBits1 = copyBit(8, j, formatInfoBits1)
        // Read the top-right/bottom-left pattern too
        val dimension = bitMatrix.height
        var formatInfoBits2 = 0
        val jMin = dimension - 7
        for (j in dimension - 1 downTo jMin) formatInfoBits2 = copyBit(8, j, formatInfoBits2)
        for (i in dimension - 8 until dimension) formatInfoBits2 = copyBit(i, 8, formatInfoBits2)
        val info = FormatInformation.decode(formatInfoBits1, formatInfoBits2) ?: throw QrReadException("format")
        parsedFormatInfo = info
        return info
    }

    /** The version: from the dimension up to version 6, from either of its two copies from version 7. */
    fun readVersion(): Version {
        parsedVersion?.let { return it }
        val dimension = bitMatrix.height
        val provisionalVersion = (dimension - 17) / 4
        if (provisionalVersion <= 6) return Version.forNumber(provisionalVersion)
        // Read top-right version info: 3 wide by 6 tall
        var versionBits = 0
        val ijMin = dimension - 11
        for (j in 5 downTo 0) for (i in dimension - 9 downTo ijMin) versionBits = copyBit(i, j, versionBits)
        var theParsedVersion = Version.decodeVersionInformation(versionBits)
        if (theParsedVersion != null && theParsedVersion.dimension == dimension) {
            parsedVersion = theParsedVersion
            return theParsedVersion
        }
        // Hmm, failed. Try bottom left: 6 wide by 3 tall
        versionBits = 0
        for (i in 5 downTo 0) for (j in dimension - 9 downTo ijMin) versionBits = copyBit(i, j, versionBits)
        theParsedVersion = Version.decodeVersionInformation(versionBits)
        if (theParsedVersion != null && theParsedVersion.dimension == dimension) {
            parsedVersion = theParsedVersion
            return theParsedVersion
        }
        throw QrReadException("version")
    }

    private fun copyBit(i: Int, j: Int, versionBits: Int): Int {
        val bit = if (mirror) bitMatrix[j, i] else bitMatrix[i, j]
        return if (bit) (versionBits shl 1) or 0x1 else versionBits shl 1
    }

    /** The codewords in the order ISO 18004 places them: two-column strips from the bottom right, up and down. */
    fun readCodewords(): ByteArray {
        val formatInfo = readFormatInformation()
        val version = readVersion()
        // Get the data mask for the format used in this QR Code. This will exclude some bits from reading as we wind
        // through the bit matrix.
        val dataMask = DataMask.entries[formatInfo.dataMask]
        val dimension = bitMatrix.height
        dataMask.unmaskBitMatrix(bitMatrix, dimension)
        val functionPattern = version.buildFunctionPattern()
        var readingUp = true
        val result = ByteArray(version.totalCodewords)
        var resultOffset = 0
        var currentByte = 0
        var bitsRead = 0
        // Read columns in pairs, from right to left
        var j = dimension - 1
        while (j > 0) {
            if (j == 6) {
                // Skip whole column with vertical alignment pattern; saves time and makes the other code proceed more
                // cleanly
                j--
            }
            // Read alternatingly from bottom to top then top to bottom
            for (count in 0 until dimension) {
                val i = if (readingUp) dimension - 1 - count else count
                for (col in 0 until 2) {
                    // Ignore bits covered by the function pattern
                    if (!functionPattern[j - col, i]) {
                        // Read a bit
                        bitsRead++
                        currentByte = currentByte shl 1
                        if (bitMatrix[j - col, i]) currentByte = currentByte or 1
                        // If we've made a whole byte, save it off
                        if (bitsRead == 8) {
                            if (resultOffset == result.size) throw QrReadException("codewords") // past the version's count
                            result[resultOffset++] = currentByte.toByte()
                            bitsRead = 0
                            currentByte = 0
                        }
                    }
                }
            }
            readingUp = !readingUp // switch directions
            j -= 2
        }
        if (resultOffset != version.totalCodewords) throw QrReadException("codewords")
        return result
    }

    /** Puts the data mask back, so the modules are as read. */
    fun remask() {
        val info = parsedFormatInfo ?: return // We have no format information, and have no data mask
        DataMask.entries[info.dataMask].unmaskBitMatrix(bitMatrix, bitMatrix.height)
    }

    /** Whether version and format are read transposed; forgets what was read. */
    fun setMirror(mirror: Boolean) {
        parsedVersion = null
        parsedFormatInfo = null
        this.mirror = mirror
    }

    /** Transposes the modules. */
    fun mirror() {
        for (x in 0 until bitMatrix.width) {
            for (y in x + 1 until bitMatrix.height) {
                if (bitMatrix[x, y] != bitMatrix[y, x]) {
                    bitMatrix.flip(y, x)
                    bitMatrix.flip(x, y)
                }
            }
        }
    }
}

/** One error-correction block: its data codewords first, then its EC codewords. */
internal class DataBlock private constructor(val numDataCodewords: Int, val codewords: ByteArray) {
    companion object {
        /**
         * The blocks [rawCodewords] interleave: ISO 18004 places the first codeword of every block, then the second,
         * and so on; the longer blocks' extra data codeword after the shorter blocks' last.
         */
        fun of(rawCodewords: ByteArray, version: Version, ecLevel: ErrorCorrectionLevel): List<DataBlock> {
            require(rawCodewords.size == version.totalCodewords)
            // Figure out the number and size of data blocks used by this version and error correction level
            val ecBlocks = version.ecBlocksForLevel(ecLevel)
            // Now establish DataBlocks of the appropriate size and number of data codewords
            val result = ArrayList<DataBlock>()
            for (ecBlock in ecBlocks.ecBlocks) {
                for (i in 0 until ecBlock.count) {
                    val numDataCodewords = ecBlock.dataCodewords
                    val numBlockCodewords = ecBlocks.ecCodewordsPerBlock + numDataCodewords
                    result += DataBlock(numDataCodewords, ByteArray(numBlockCodewords))
                }
            }
            val numResultBlocks = result.size
            // All blocks have the same amount of data, except that the last n (where n may be 0) have 1 more byte.
            // Figure out where these start.
            val shorterBlocksTotalCodewords = result[0].codewords.size
            var longerBlocksStartAt = result.size - 1
            while (longerBlocksStartAt >= 0) {
                val numCodewords = result[longerBlocksStartAt].codewords.size
                if (numCodewords == shorterBlocksTotalCodewords) break
                longerBlocksStartAt--
            }
            longerBlocksStartAt++
            val shorterBlocksNumDataCodewords = shorterBlocksTotalCodewords - ecBlocks.ecCodewordsPerBlock
            // The last elements of result may be 1 element longer; first fill out as many elements as all of them have
            var rawCodewordsOffset = 0
            for (i in 0 until shorterBlocksNumDataCodewords) {
                for (j in 0 until numResultBlocks) result[j].codewords[i] = rawCodewords[rawCodewordsOffset++]
            }
            // Fill out the last data block in the longer ones
            for (j in longerBlocksStartAt until numResultBlocks) result[j].codewords[shorterBlocksNumDataCodewords] = rawCodewords[rawCodewordsOffset++]
            // Now add in error correction blocks
            val max = result[0].codewords.size
            for (i in shorterBlocksNumDataCodewords until max) {
                for (j in 0 until numResultBlocks) {
                    val iOffset = if (j < longerBlocksStartAt) i else i + 1
                    result[j].codewords[iOffset] = rawCodewords[rawCodewordsOffset++]
                }
            }
            return result
        }
    }
}
