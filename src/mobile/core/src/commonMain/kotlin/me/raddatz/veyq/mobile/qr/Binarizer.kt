// Ported to Kotlin from ZXing's common/HybridBinarizer.java and common/GlobalHistogramBinarizer.java (Copyright 2009
// ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream commit). Changed: one function over a
// packed luminance plane (ZXing's LuminanceSource), the black matrix only (the QR reader reads no black row).
package me.raddatz.veyq.mobile.qr

/**
 * A frame's luminance turned into dark and light: ZXing's `HybridBinarizer`, a local threshold per 8×8 block from the
 * 5×5 blocks around it, which holds against shadows and gradients; below 40 px on an edge the global histogram's one
 * threshold, as upstream.
 */
internal object Binarizer {
    private const val BLOCK_SIZE_POWER = 3
    private const val BLOCK_SIZE = 1 shl BLOCK_SIZE_POWER
    private const val BLOCK_SIZE_MASK = BLOCK_SIZE - 1
    private const val MINIMUM_DIMENSION = BLOCK_SIZE * 5
    private const val MIN_DYNAMIC_RANGE = 24

    private const val LUMINANCE_BITS = 5
    private const val LUMINANCE_SHIFT = 8 - LUMINANCE_BITS
    private const val LUMINANCE_BUCKETS = 1 shl LUMINANCE_BITS

    /** [luminances] is `width × height` bytes, row after row with no padding. */
    fun blackMatrix(luminances: ByteArray, width: Int, height: Int): BitMatrix {
        if (width < MINIMUM_DIMENSION || height < MINIMUM_DIMENSION) return globalBlackMatrix(luminances, width, height)
        var subWidth = width shr BLOCK_SIZE_POWER
        if ((width and BLOCK_SIZE_MASK) != 0) subWidth++
        var subHeight = height shr BLOCK_SIZE_POWER
        if ((height and BLOCK_SIZE_MASK) != 0) subHeight++
        val blackPoints = calculateBlackPoints(luminances, subWidth, subHeight, width, height)
        val matrix = BitMatrix(width, height)
        calculateThresholdForBlock(luminances, subWidth, subHeight, width, height, blackPoints, matrix)
        return matrix
    }

    private fun calculateThresholdForBlock(
        luminances: ByteArray, subWidth: Int, subHeight: Int, width: Int, height: Int,
        blackPoints: Array<IntArray>, matrix: BitMatrix,
    ) {
        val maxYOffset = height - BLOCK_SIZE
        val maxXOffset = width - BLOCK_SIZE
        for (y in 0 until subHeight) {
            var yoffset = y shl BLOCK_SIZE_POWER
            if (yoffset > maxYOffset) yoffset = maxYOffset
            val top = cap(y, subHeight - 3)
            for (x in 0 until subWidth) {
                var xoffset = x shl BLOCK_SIZE_POWER
                if (xoffset > maxXOffset) xoffset = maxXOffset
                val left = cap(x, subWidth - 3)
                var sum = 0
                for (z in -2..2) {
                    val blackRow = blackPoints[top + z]
                    sum += blackRow[left - 2] + blackRow[left - 1] + blackRow[left] + blackRow[left + 1] + blackRow[left + 2]
                }
                thresholdBlock(luminances, xoffset, yoffset, sum / 25, width, matrix)
            }
        }
    }

    private fun cap(value: Int, max: Int): Int = if (value < 2) 2 else minOf(value, max)

    private fun thresholdBlock(luminances: ByteArray, xoffset: Int, yoffset: Int, threshold: Int, stride: Int, matrix: BitMatrix) {
        var offset = yoffset * stride + xoffset
        for (y in 0 until BLOCK_SIZE) {
            for (x in 0 until BLOCK_SIZE) {
                if ((luminances[offset + x].toInt() and 0xFF) <= threshold) matrix.set(xoffset + x, yoffset + y)
            }
            offset += stride
        }
    }

    private fun calculateBlackPoints(luminances: ByteArray, subWidth: Int, subHeight: Int, width: Int, height: Int): Array<IntArray> {
        val maxYOffset = height - BLOCK_SIZE
        val maxXOffset = width - BLOCK_SIZE
        val blackPoints = Array(subHeight) { IntArray(subWidth) }
        for (y in 0 until subHeight) {
            var yoffset = y shl BLOCK_SIZE_POWER
            if (yoffset > maxYOffset) yoffset = maxYOffset
            for (x in 0 until subWidth) {
                var xoffset = x shl BLOCK_SIZE_POWER
                if (xoffset > maxXOffset) xoffset = maxXOffset
                var sum = 0
                var min = 0xFF
                var max = 0
                var yy = 0
                var offset = yoffset * width + xoffset
                while (yy < BLOCK_SIZE) {
                    for (xx in 0 until BLOCK_SIZE) {
                        val pixel = luminances[offset + xx].toInt() and 0xFF
                        sum += pixel
                        if (pixel < min) min = pixel
                        if (pixel > max) max = pixel
                    }
                    // short-circuit min/max tests once dynamic range is met
                    if (max - min > MIN_DYNAMIC_RANGE) {
                        // finish the rest of the rows quickly
                        yy++
                        offset += width
                        while (yy < BLOCK_SIZE) {
                            for (xx in 0 until BLOCK_SIZE) sum += luminances[offset + xx].toInt() and 0xFF
                            yy++
                            offset += width
                        }
                    }
                    yy++
                    offset += width
                }
                var average = sum shr (BLOCK_SIZE_POWER * 2)
                if (max - min <= MIN_DYNAMIC_RANGE) {
                    // A block of one tone: assume it is light, unless its neighbours say the block is dark.
                    average = min / 2
                    if (y > 0 && x > 0) {
                        val averageNeighborBlackPoint = (blackPoints[y - 1][x] + (2 * blackPoints[y][x - 1]) + blackPoints[y - 1][x - 1]) / 4
                        if (min < averageNeighborBlackPoint) average = averageNeighborBlackPoint
                    }
                }
                blackPoints[y][x] = average
            }
        }
        return blackPoints
    }

    /** ZXing's `GlobalHistogramBinarizer.getBlackMatrix`: one threshold from the histogram of four sampled rows. */
    private fun globalBlackMatrix(luminances: ByteArray, width: Int, height: Int): BitMatrix {
        val matrix = BitMatrix(width, height)
        val buckets = IntArray(LUMINANCE_BUCKETS)
        for (y in 1 until 5) {
            val row = height * y / 5
            val right = (width * 4) / 5
            for (x in width / 5 until right) buckets[(luminances[row * width + x].toInt() and 0xff) shr LUMINANCE_SHIFT]++
        }
        val blackPoint = estimateBlackPoint(buckets)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) if ((luminances[offset + x].toInt() and 0xff) < blackPoint) matrix.set(x, y)
        }
        return matrix
    }

    private fun estimateBlackPoint(buckets: IntArray): Int {
        val numBuckets = buckets.size
        var maxBucketCount = 0
        var firstPeak = 0
        var firstPeakSize = 0
        for (x in 0 until numBuckets) {
            if (buckets[x] > firstPeakSize) {
                firstPeak = x
                firstPeakSize = buckets[x]
            }
            if (buckets[x] > maxBucketCount) maxBucketCount = buckets[x]
        }
        var secondPeak = 0
        var secondPeakScore = 0
        for (x in 0 until numBuckets) {
            val distanceToBiggest = x - firstPeak
            val score = buckets[x] * distanceToBiggest * distanceToBiggest
            if (score > secondPeakScore) {
                secondPeak = x
                secondPeakScore = score
            }
        }
        if (firstPeak > secondPeak) {
            val temp = firstPeak
            firstPeak = secondPeak
            secondPeak = temp
        }
        if (secondPeak - firstPeak <= numBuckets / 16) throw QrReadException("no contrast")
        var bestValley = secondPeak - 1
        var bestValleyScore = -1
        for (x in secondPeak - 1 downTo firstPeak + 1) {
            val fromFirst = x - firstPeak
            val score = fromFirst * fromFirst * (secondPeak - x) * (maxBucketCount - buckets[x])
            if (score > bestValleyScore) {
                bestValley = x
                bestValleyScore = score
            }
        }
        return bestValley shl LUMINANCE_SHIFT
    }
}
