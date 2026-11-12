// Ported to Kotlin from ZXing's qrcode/detector/{Detector,FinderPatternFinder,FinderPattern,AlignmentPatternFinder,
// AlignmentPattern}.java (Copyright 2007 ZXing authors, Apache License 2.0; NOTICE beside this file names the upstream
// commit). Changed: no result-point callback, TRY_HARDER a flag; a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

import kotlin.math.abs

internal class FinderPattern(x: Float, y: Float, val estimatedModuleSize: Float, val count: Int = 1) : ResultPoint(x, y) {
    /** Whether a centre found at ([i], [j]) with [moduleSize] is this pattern seen again. */
    fun aboutEquals(moduleSize: Float, i: Float, j: Float): Boolean {
        if (abs(i - y) <= moduleSize && abs(j - x) <= moduleSize) {
            val moduleSizeDiff = abs(moduleSize - estimatedModuleSize)
            return moduleSizeDiff <= 1.0f || moduleSizeDiff <= estimatedModuleSize
        }
        return false
    }

    /** The weighted average of this pattern and a new sighting of it. */
    fun combineEstimate(i: Float, j: Float, newModuleSize: Float): FinderPattern {
        val combinedCount = count + 1
        val combinedX = (count * x + j) / combinedCount
        val combinedY = (count * y + i) / combinedCount
        val combinedModuleSize = (count * estimatedModuleSize + newModuleSize) / combinedCount
        return FinderPattern(combinedX, combinedY, combinedModuleSize, combinedCount)
    }
}

internal class AlignmentPattern(x: Float, y: Float, private val estimatedModuleSize: Float) : ResultPoint(x, y) {
    fun aboutEquals(moduleSize: Float, i: Float, j: Float): Boolean {
        if (abs(i - y) <= moduleSize && abs(j - x) <= moduleSize) {
            val moduleSizeDiff = abs(moduleSize - estimatedModuleSize)
            return moduleSizeDiff <= 1.0f || moduleSizeDiff <= estimatedModuleSize
        }
        return false
    }

    fun combineEstimate(i: Float, j: Float, newModuleSize: Float): AlignmentPattern =
        AlignmentPattern((x + j) / 2.0f, (y + i) / 2.0f, (estimatedModuleSize + newModuleSize) / 2.0f)
}

/** The three finder patterns of a code: bottom-left, top-left, top-right. */
internal class FinderPatternInfo(val bottomLeft: FinderPattern, val topLeft: FinderPattern, val topRight: FinderPattern)

/** Finds the three finder patterns (the 1:1:3:1:1 squares in a code's corners) in a black matrix. */
internal class FinderPatternFinder(private val image: BitMatrix) {
    private val possibleCenters = ArrayList<FinderPattern>()
    private var hasSkipped = false
    private val crossCheckStateCount = IntArray(5)

    fun find(tryHarder: Boolean): FinderPatternInfo {
        val maxI = image.height
        val maxJ = image.width
        // Search every third row at least, and as far apart as a code of version 20 still allows unless trying harder.
        var iSkip = (3 * maxI) / (4 * MAX_MODULES)
        if (iSkip < MIN_SKIP || tryHarder) iSkip = MIN_SKIP

        var done = false
        val stateCount = IntArray(5)
        var i = iSkip - 1
        while (i < maxI && !done) {
            // Get a row of black/white values
            doClearCounts(stateCount)
            var currentState = 0
            var j = 0
            while (j < maxJ) {
                if (image[j, i]) {
                    // Black pixel
                    if ((currentState and 1) == 1) currentState++ // Counting white pixels
                    stateCount[currentState]++
                } else { // White pixel
                    if ((currentState and 1) == 0) { // Counting black pixels
                        if (currentState == 4) { // A winner?
                            if (foundPatternCross(stateCount)) { // Yes
                                val confirmed = handlePossibleCenter(stateCount, i, j)
                                if (confirmed) {
                                    // Start examining every other line. Checking each line turned out to be too
                                    // expensive and didn't improve performance.
                                    iSkip = 2
                                    if (hasSkipped) {
                                        done = haveMultiplyConfirmedCenters()
                                    } else {
                                        val rowSkip = findRowSkip()
                                        if (rowSkip > stateCount[2]) {
                                            // Skip rows between row of lower confirmed center and top of presumed
                                            // third confirmed center but back up a bit to get a full chance of
                                            // detecting it, entire width of center of finder pattern
                                            i += rowSkip - stateCount[2] - iSkip
                                            j = maxJ - 1
                                        }
                                    }
                                } else {
                                    doShiftCounts2(stateCount)
                                    currentState = 3
                                    j++
                                    continue
                                }
                                // Clear state to start looking again
                                currentState = 0
                                doClearCounts(stateCount)
                            } else { // No, shift counts back by two
                                doShiftCounts2(stateCount)
                                currentState = 3
                            }
                        } else {
                            stateCount[++currentState]++
                        }
                    } else { // Counting white pixels
                        stateCount[currentState]++
                    }
                }
                j++
            }
            if (foundPatternCross(stateCount)) {
                val confirmed = handlePossibleCenter(stateCount, i, maxJ)
                if (confirmed) {
                    iSkip = stateCount[0]
                    if (hasSkipped) {
                        // Found a third one
                        done = haveMultiplyConfirmedCenters()
                    }
                }
            }
            i += iSkip
        }

        val patternInfo = selectBestPatterns()
        Points.orderBestPatterns(patternInfo)
        return FinderPatternInfo(patternInfo[0], patternInfo[1], patternInfo[2])
    }

    private fun crossCheckDiagonal(centerI: Int, centerJ: Int): Boolean {
        val stateCount = clearedCrossCheckStateCount()
        // Start counting up, left from center finding black center mass
        var i = 0
        while (centerI >= i && centerJ >= i && image[centerJ - i, centerI - i]) {
            stateCount[2]++
            i++
        }
        if (stateCount[2] == 0) return false
        // Continue up, left finding white space
        while (centerI >= i && centerJ >= i && !image[centerJ - i, centerI - i]) {
            stateCount[1]++
            i++
        }
        if (stateCount[1] == 0) return false
        // Continue up, left finding black border
        while (centerI >= i && centerJ >= i && image[centerJ - i, centerI - i]) {
            stateCount[0]++
            i++
        }
        if (stateCount[0] == 0) return false

        val maxI = image.height
        val maxJ = image.width
        // Now also count down, right from center
        i = 1
        while (centerI + i < maxI && centerJ + i < maxJ && image[centerJ + i, centerI + i]) {
            stateCount[2]++
            i++
        }
        while (centerI + i < maxI && centerJ + i < maxJ && !image[centerJ + i, centerI + i]) {
            stateCount[3]++
            i++
        }
        if (stateCount[3] == 0) return false
        while (centerI + i < maxI && centerJ + i < maxJ && image[centerJ + i, centerI + i]) {
            stateCount[4]++
            i++
        }
        if (stateCount[4] == 0) return false
        return foundPatternDiagonal(stateCount)
    }

    /**
     * After a horizontal scan finds a potential finder pattern, this method "cross-checks" by scanning down vertically
     * through the center of the possible finder pattern to see if the same proportion is detected.
     */
    private fun crossCheckVertical(startI: Int, centerJ: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxI = image.height
        val stateCount = clearedCrossCheckStateCount()
        // Start counting up from center
        var i = startI
        while (i >= 0 && image[centerJ, i]) {
            stateCount[2]++
            i--
        }
        if (i < 0) return Float.NaN
        while (i >= 0 && !image[centerJ, i] && stateCount[1] <= maxCount) {
            stateCount[1]++
            i--
        }
        // If already too many modules in this state or ran off the edge:
        if (i < 0 || stateCount[1] > maxCount) return Float.NaN
        while (i >= 0 && image[centerJ, i] && stateCount[0] <= maxCount) {
            stateCount[0]++
            i--
        }
        if (stateCount[0] > maxCount) return Float.NaN

        // Now also count down from center
        i = startI + 1
        while (i < maxI && image[centerJ, i]) {
            stateCount[2]++
            i++
        }
        if (i == maxI) return Float.NaN
        while (i < maxI && !image[centerJ, i] && stateCount[3] < maxCount) {
            stateCount[3]++
            i++
        }
        if (i == maxI || stateCount[3] >= maxCount) return Float.NaN
        while (i < maxI && image[centerJ, i] && stateCount[4] < maxCount) {
            stateCount[4]++
            i++
        }
        if (stateCount[4] >= maxCount) return Float.NaN

        // If we found a finder-pattern-like section, but its size is more than 40% different than the original, assume
        // it's a false positive
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        if (5 * abs(stateCountTotal - originalStateCountTotal) >= 2 * originalStateCountTotal) return Float.NaN
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, i) else Float.NaN
    }

    /** Like [crossCheckVertical], and in fact is basically identical, except it reads horizontally instead of vertically. */
    private fun crossCheckHorizontal(startJ: Int, centerI: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxJ = image.width
        val stateCount = clearedCrossCheckStateCount()
        var j = startJ
        while (j >= 0 && image[j, centerI]) {
            stateCount[2]++
            j--
        }
        if (j < 0) return Float.NaN
        while (j >= 0 && !image[j, centerI] && stateCount[1] <= maxCount) {
            stateCount[1]++
            j--
        }
        if (j < 0 || stateCount[1] > maxCount) return Float.NaN
        while (j >= 0 && image[j, centerI] && stateCount[0] <= maxCount) {
            stateCount[0]++
            j--
        }
        if (stateCount[0] > maxCount) return Float.NaN

        j = startJ + 1
        while (j < maxJ && image[j, centerI]) {
            stateCount[2]++
            j++
        }
        if (j == maxJ) return Float.NaN
        while (j < maxJ && !image[j, centerI] && stateCount[3] < maxCount) {
            stateCount[3]++
            j++
        }
        if (j == maxJ || stateCount[3] >= maxCount) return Float.NaN
        while (j < maxJ && image[j, centerI] && stateCount[4] < maxCount) {
            stateCount[4]++
            j++
        }
        if (stateCount[4] >= maxCount) return Float.NaN

        // If we found a finder-pattern-like section, but its size is significantly different than the original, assume
        // it's a false positive
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        if (5 * abs(stateCountTotal - originalStateCountTotal) >= originalStateCountTotal) return Float.NaN
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, j) else Float.NaN
    }

    /**
     * Called when a horizontal scan finds a possible finder pattern: cross-checks it vertically, horizontally and
     * diagonally, and counts it as a sighting of a known centre or adds it as a new one. True when it was confirmed.
     */
    private fun handlePossibleCenter(stateCount: IntArray, i: Int, j: Int): Boolean {
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        var centerJ = centerFromEnd(stateCount, j)
        val centerI = crossCheckVertical(i, centerJ.toInt(), stateCount[2], stateCountTotal)
        if (!centerI.isNaN()) {
            // Re-cross check
            centerJ = crossCheckHorizontal(centerJ.toInt(), centerI.toInt(), stateCount[2], stateCountTotal)
            if (!centerJ.isNaN() && crossCheckDiagonal(centerI.toInt(), centerJ.toInt())) {
                val estimatedModuleSize = stateCountTotal / 7.0f
                var found = false
                for (index in possibleCenters.indices) {
                    val center = possibleCenters[index]
                    // Look for about the same center and module size:
                    if (center.aboutEquals(estimatedModuleSize, centerI, centerJ)) {
                        possibleCenters[index] = center.combineEstimate(centerI, centerJ, estimatedModuleSize)
                        found = true
                        break
                    }
                }
                if (!found) possibleCenters.add(FinderPattern(centerJ, centerI, estimatedModuleSize))
                return true
            }
        }
        return false
    }

    /**
     * How far down to skip once two finder patterns are confirmed: the third is at least that far away, at about the
     * same row as one of them.
     */
    private fun findRowSkip(): Int {
        if (possibleCenters.size <= 1) return 0
        var firstConfirmedCenter: ResultPoint? = null
        for (center in possibleCenters) {
            if (center.count >= CENTER_QUORUM) {
                if (firstConfirmedCenter == null) {
                    firstConfirmedCenter = center
                } else {
                    // We have two confirmed centers. How far down can we skip before resuming looking for the next
                    // pattern? In the worst case, only the difference between the difference in the x / y
                    // coordinates of the two centers. This is the case where you find top left last.
                    hasSkipped = true
                    return (abs(firstConfirmedCenter.x - center.x) - abs(firstConfirmedCenter.y - center.y)).toInt() / 2
                }
            }
        }
        return 0
    }

    /** True when at least three centres are confirmed and their module sizes agree within 5 %. */
    private fun haveMultiplyConfirmedCenters(): Boolean {
        var confirmedCount = 0
        var totalModuleSize = 0.0f
        val max = possibleCenters.size
        for (pattern in possibleCenters) {
            if (pattern.count >= CENTER_QUORUM) {
                confirmedCount++
                totalModuleSize += pattern.estimatedModuleSize
            }
        }
        if (confirmedCount < 3) return false
        // OK, we have at least 3 confirmed centers, but, it's possible that one is a "false positive" and that we need
        // to keep looking. We detect this by asking if the estimated module sizes vary too much. We arbitrarily say
        // that when the total deviation from average exceeds 5% of the total module size estimates, it's too much.
        val average = totalModuleSize / max
        var totalDeviation = 0.0f
        for (pattern in possibleCenters) totalDeviation += abs(pattern.estimatedModuleSize - average)
        return totalDeviation <= 0.05f * totalModuleSize
    }

    /** The three confirmed centres that come closest to the right isosceles triangle a code's three corners make. */
    private fun selectBestPatterns(): Array<FinderPattern> {
        if (possibleCenters.size < 3) throw QrReadException("fewer than three finder patterns")
        possibleCenters.removeAll { it.count < CENTER_QUORUM }
        possibleCenters.sortBy { it.estimatedModuleSize }

        var distortion = Double.MAX_VALUE
        var best: Array<FinderPattern>? = null
        for (i in 0 until possibleCenters.size - 2) {
            val fpi = possibleCenters[i]
            val minModuleSize = fpi.estimatedModuleSize
            for (j in i + 1 until possibleCenters.size - 1) {
                val fpj = possibleCenters[j]
                val squares0 = squaredDistance(fpi, fpj)
                for (k in j + 1 until possibleCenters.size) {
                    val fpk = possibleCenters[k]
                    val maxModuleSize = fpk.estimatedModuleSize
                    if (maxModuleSize > minModuleSize * 1.4f) continue // module size is not similar
                    var a = squares0
                    var b = squaredDistance(fpj, fpk)
                    var c = squaredDistance(fpi, fpk)
                    // sorts ascending - inlined
                    if (a < b) {
                        if (b > c) {
                            if (a < c) {
                                val temp = b; b = c; c = temp
                            } else {
                                val temp = a; a = c; c = b; b = temp
                            }
                        }
                    } else {
                        if (b < c) {
                            if (a < c) {
                                val temp = a; a = b; b = temp
                            } else {
                                val temp = a; a = b; b = c; c = temp
                            }
                        } else {
                            val temp = a; a = c; c = temp
                        }
                    }
                    // a^2 + b^2 = c^2 (Pythagorean theorem), and a = b (isosceles triangle). Since any right triangle
                    // satisfies the formula c^2 - b^2 - a^2 = 0, we need to check both two equal sides separately.
                    // The value of |c^2 - 2 * b^2| + |c^2 - 2 * a^2| increases as dissimilarity from isosceles right
                    // triangle.
                    val d = abs(c - 2 * b) + abs(c - 2 * a)
                    if (d < distortion) {
                        distortion = d
                        best = arrayOf(fpi, fpj, fpk)
                    }
                }
            }
        }
        return best ?: throw QrReadException("no three finder patterns fit")
    }

    private fun clearedCrossCheckStateCount(): IntArray {
        doClearCounts(crossCheckStateCount)
        return crossCheckStateCount
    }

    companion object {
        private const val CENTER_QUORUM = 2
        private const val MIN_SKIP = 3 // 1 pixel/module times 3 modules/center
        private const val MAX_MODULES = 97 // support up to version 20 for mobile clients

        private fun centerFromEnd(stateCount: IntArray, end: Int): Float =
            (end - stateCount[4] - stateCount[3]) - stateCount[2] / 2.0f

        /** Whether the counts are close enough to 1:1:3:1:1 to be a finder pattern. */
        fun foundPatternCross(stateCount: IntArray): Boolean = ratiosHold(stateCount, 2.0f)

        private fun foundPatternDiagonal(stateCount: IntArray): Boolean = ratiosHold(stateCount, 1.333f)

        private fun ratiosHold(stateCount: IntArray, varianceDivisor: Float): Boolean {
            var totalModuleSize = 0
            for (i in 0 until 5) {
                val count = stateCount[i]
                if (count == 0) return false
                totalModuleSize += count
            }
            if (totalModuleSize < 7) return false
            val moduleSize = totalModuleSize / 7.0f
            val maxVariance = moduleSize / varianceDivisor
            // Allow less than 50% variance from 1-1-3-1-1 proportions
            return abs(moduleSize - stateCount[0]) < maxVariance &&
                abs(moduleSize - stateCount[1]) < maxVariance &&
                abs(3.0f * moduleSize - stateCount[2]) < 3 * maxVariance &&
                abs(moduleSize - stateCount[3]) < maxVariance &&
                abs(moduleSize - stateCount[4]) < maxVariance
        }

        private fun doClearCounts(counts: IntArray) = counts.fill(0)

        private fun doShiftCounts2(stateCount: IntArray) {
            stateCount[0] = stateCount[2]
            stateCount[1] = stateCount[3]
            stateCount[2] = stateCount[4]
            stateCount[3] = 1
            stateCount[4] = 0
        }

        private fun squaredDistance(a: FinderPattern, b: FinderPattern): Double {
            val x = (a.x - b.x).toDouble()
            val y = (a.y - b.y).toDouble()
            return x * x + y * y
        }
    }
}

/**
 * Finds an alignment pattern (the 1:1:1 square near a code's bottom-right) in a region around where it should be.
 * Searches rows from the middle of the region outwards.
 */
internal class AlignmentPatternFinder(
    private val image: BitMatrix,
    private val startX: Int,
    private val startY: Int,
    private val width: Int,
    private val height: Int,
    private val moduleSize: Float,
) {
    private val possibleCenters = ArrayList<AlignmentPattern>(5)
    private val crossCheckStateCount = IntArray(3)

    fun find(): AlignmentPattern {
        val maxJ = startX + width
        val middleI = startY + (height / 2)
        // We are looking for black/white/black modules in 1:1:1 ratio; this tracks the number of black/white/black
        // modules seen so far
        val stateCount = IntArray(3)
        for (iGen in 0 until height) {
            // Search from middle outwards
            val i = middleI + (if ((iGen and 0x01) == 0) (iGen + 1) / 2 else -((iGen + 1) / 2))
            stateCount[0] = 0
            stateCount[1] = 0
            stateCount[2] = 0
            var j = startX
            // Burn off leading white pixels before anything else; if we start in the middle of a white run, it doesn't
            // make sense to count its length, since we don't know if the white run continued to the left of the start
            // point
            while (j < maxJ && !image[j, i]) j++
            var currentState = 0
            while (j < maxJ) {
                if (image[j, i]) {
                    // Black pixel
                    if (currentState == 1) { // Counting black pixels
                        stateCount[1]++
                    } else { // Counting white pixels
                        if (currentState == 2) { // A winner?
                            if (foundPatternCross(stateCount)) { // Yes
                                val confirmed = handlePossibleCenter(stateCount, i, j)
                                if (confirmed != null) return confirmed
                            }
                            stateCount[0] = stateCount[2]
                            stateCount[1] = 1
                            stateCount[2] = 0
                            currentState = 1
                        } else {
                            stateCount[++currentState]++
                        }
                    }
                } else { // White pixel
                    if (currentState == 1) currentState++ // Counting black pixels
                    stateCount[currentState]++
                }
                j++
            }
            if (foundPatternCross(stateCount)) {
                val confirmed = handlePossibleCenter(stateCount, i, maxJ)
                if (confirmed != null) return confirmed
            }
        }
        // Hmm, nothing we saw was observed and confirmed twice. If we had any guess at all, return it.
        return possibleCenters.firstOrNull() ?: throw QrReadException("no alignment pattern")
    }

    private fun foundPatternCross(stateCount: IntArray): Boolean {
        val maxVariance = moduleSize / 2.0f
        for (i in 0 until 3) if (abs(moduleSize - stateCount[i]) >= maxVariance) return false
        return true
    }

    private fun crossCheckVertical(startI: Int, centerJ: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxI = image.height
        val stateCount = crossCheckStateCount
        stateCount[0] = 0
        stateCount[1] = 0
        stateCount[2] = 0
        // Start counting up from center
        var i = startI
        while (i >= 0 && image[centerJ, i] && stateCount[1] <= maxCount) {
            stateCount[1]++
            i--
        }
        // If already too many modules in this state or ran off the edge:
        if (i < 0 || stateCount[1] > maxCount) return Float.NaN
        while (i >= 0 && !image[centerJ, i] && stateCount[0] <= maxCount) {
            stateCount[0]++
            i--
        }
        if (stateCount[0] > maxCount) return Float.NaN
        // Now also count down from center
        i = startI + 1
        while (i < maxI && image[centerJ, i] && stateCount[1] <= maxCount) {
            stateCount[1]++
            i++
        }
        if (i == maxI || stateCount[1] > maxCount) return Float.NaN
        while (i < maxI && !image[centerJ, i] && stateCount[2] <= maxCount) {
            stateCount[2]++
            i++
        }
        if (stateCount[2] > maxCount) return Float.NaN
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2]
        if (5 * abs(stateCountTotal - originalStateCountTotal) >= 2 * originalStateCountTotal) return Float.NaN
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, i) else Float.NaN
    }

    /** A sighting cross-checked vertically: the pattern when it was seen before, else remembered and null. */
    private fun handlePossibleCenter(stateCount: IntArray, i: Int, j: Int): AlignmentPattern? {
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2]
        val centerJ = centerFromEnd(stateCount, j)
        val centerI = crossCheckVertical(i, centerJ.toInt(), 2 * stateCount[1], stateCountTotal)
        if (!centerI.isNaN()) {
            val estimatedModuleSize = (stateCount[0] + stateCount[1] + stateCount[2]) / 3.0f
            for (center in possibleCenters) {
                // Look for about the same center and module size:
                if (center.aboutEquals(estimatedModuleSize, centerI, centerJ)) return center.combineEstimate(centerI, centerJ, estimatedModuleSize)
            }
            // Hadn't found this before; save it
            possibleCenters.add(AlignmentPattern(centerJ, centerI, estimatedModuleSize))
        }
        return null
    }

    private fun centerFromEnd(stateCount: IntArray, end: Int): Float = (end - stateCount[2]) - stateCount[1] / 2.0f
}

/** Where a code is in a black matrix and its modules sampled out of it. */
internal object Detector {
    fun detect(image: BitMatrix, tryHarder: Boolean): BitMatrix {
        val info = FinderPatternFinder(image).find(tryHarder)
        val topLeft = info.topLeft
        val topRight = info.topRight
        val bottomLeft = info.bottomLeft

        val moduleSize = calculateModuleSize(image, topLeft, topRight, bottomLeft)
        if (moduleSize < 1.0f) throw QrReadException("modules under a pixel")
        val dimension = computeDimension(topLeft, topRight, bottomLeft, moduleSize)
        val provisionalVersion = Version.provisionalForDimension(dimension)
        val modulesBetweenFPCenters = provisionalVersion.dimension - 7

        var alignmentPattern: AlignmentPattern? = null
        // Anything above version 1 has an alignment pattern
        if (provisionalVersion.alignmentPatternCenters.isNotEmpty()) {
            // Guess where a "bottom right" finder pattern would have been
            val bottomRightX = topRight.x - topLeft.x + bottomLeft.x
            val bottomRightY = topRight.y - topLeft.y + bottomLeft.y
            // Estimate that alignment pattern is closer by 3 modules from "bottom right" to known top left location
            val correctionToTopLeft = 1.0f - 3.0f / modulesBetweenFPCenters
            val estAlignmentX = (topLeft.x + correctionToTopLeft * (bottomRightX - topLeft.x)).toInt()
            val estAlignmentY = (topLeft.y + correctionToTopLeft * (bottomRightY - topLeft.y)).toInt()
            // Kind of arbitrary -- expand search radius before giving up
            var i = 4
            while (i <= 16) {
                try {
                    alignmentPattern = findAlignmentInRegion(image, moduleSize, estAlignmentX, estAlignmentY, i.toFloat())
                    break
                } catch (_: QrReadException) {
                    // try next round
                }
                i = i shl 1
            }
            // If we didn't find alignment pattern... well try anyway without it
        }

        val transform = createTransform(topLeft, topRight, bottomLeft, alignmentPattern, dimension)
        return GridSampler.sampleGrid(image, dimension, dimension, transform)
    }

    private fun createTransform(
        topLeft: ResultPoint, topRight: ResultPoint, bottomLeft: ResultPoint, alignmentPattern: ResultPoint?, dimension: Int,
    ): PerspectiveTransform {
        val dimMinusThree = dimension - 3.5f
        val bottomRightX: Float
        val bottomRightY: Float
        val sourceBottomRightX: Float
        val sourceBottomRightY: Float
        if (alignmentPattern != null) {
            bottomRightX = alignmentPattern.x
            bottomRightY = alignmentPattern.y
            sourceBottomRightX = dimMinusThree - 3.0f
            sourceBottomRightY = sourceBottomRightX
        } else {
            // Don't have an alignment pattern, just make up the bottom-right point
            bottomRightX = (topRight.x - topLeft.x) + bottomLeft.x
            bottomRightY = (topRight.y - topLeft.y) + bottomLeft.y
            sourceBottomRightX = dimMinusThree
            sourceBottomRightY = dimMinusThree
        }
        return PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f, 3.5f, dimMinusThree, 3.5f, sourceBottomRightX, sourceBottomRightY, 3.5f, dimMinusThree,
            topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRightX, bottomRightY, bottomLeft.x, bottomLeft.y,
        )
    }

    /** The code's dimension from the finder patterns' distances and the module size, rounded to a valid 4n+1. */
    private fun computeDimension(topLeft: ResultPoint, topRight: ResultPoint, bottomLeft: ResultPoint, moduleSize: Float): Int {
        val tltrCentersDimension = Points.round(Points.distance(topLeft, topRight) / moduleSize)
        val tlblCentersDimension = Points.round(Points.distance(topLeft, bottomLeft) / moduleSize)
        var dimension = ((tltrCentersDimension + tlblCentersDimension) / 2) + 7
        when (dimension and 0x03) { // mod 4
            0 -> dimension++
            // 1? do nothing
            2 -> dimension--
            3 -> dimension -= 2
        }
        return dimension
    }

    private fun calculateModuleSize(image: BitMatrix, topLeft: ResultPoint, topRight: ResultPoint, bottomLeft: ResultPoint): Float =
        // Take the average
        (calculateModuleSizeOneWay(image, topLeft, topRight) + calculateModuleSizeOneWay(image, topLeft, bottomLeft)) / 2.0f

    private fun calculateModuleSizeOneWay(image: BitMatrix, pattern: ResultPoint, otherPattern: ResultPoint): Float {
        val moduleSizeEst1 = sizeOfBlackWhiteBlackRunBothWays(image, pattern.x.toInt(), pattern.y.toInt(), otherPattern.x.toInt(), otherPattern.y.toInt())
        val moduleSizeEst2 = sizeOfBlackWhiteBlackRunBothWays(image, otherPattern.x.toInt(), otherPattern.y.toInt(), pattern.x.toInt(), pattern.y.toInt())
        if (moduleSizeEst1.isNaN()) return moduleSizeEst2 / 7.0f
        if (moduleSizeEst2.isNaN()) return moduleSizeEst1 / 7.0f
        // Average them, and divide by 7 since we've counted the width of 3 black modules, and 1 white and 1 black
        // module on either side. Ergo, divide sum by 14.
        return (moduleSizeEst1 + moduleSizeEst2) / 14.0f
    }

    /** See [sizeOfBlackWhiteBlackRun]; computes the total width of a finder pattern by looking both ways from its centre. */
    private fun sizeOfBlackWhiteBlackRunBothWays(image: BitMatrix, fromX: Int, fromY: Int, toX: Int, toY: Int): Float {
        var result = sizeOfBlackWhiteBlackRun(image, fromX, fromY, toX, toY)
        // Now count other way -- don't run off image though of course
        var scale = 1.0f
        var otherToX = fromX - (toX - fromX)
        if (otherToX < 0) {
            scale = fromX / (fromX - otherToX).toFloat()
            otherToX = 0
        } else if (otherToX >= image.width) {
            scale = (image.width - 1 - fromX) / (otherToX - fromX).toFloat()
            otherToX = image.width - 1
        }
        var otherToY = (fromY - (toY - fromY) * scale).toInt()
        scale = 1.0f
        if (otherToY < 0) {
            scale = fromY / (fromY - otherToY).toFloat()
            otherToY = 0
        } else if (otherToY >= image.height) {
            scale = (image.height - 1 - fromY) / (otherToY - fromY).toFloat()
            otherToY = image.height - 1
        }
        otherToX = (fromX + (otherToX - fromX) * scale).toInt()
        result += sizeOfBlackWhiteBlackRun(image, fromX, fromY, otherToX, otherToY)
        // Middle pixel is double-counted this way; subtract 1
        return result - 1.0f
    }

    /**
     * The length of a black-white-black run from a finder pattern's centre towards a point (Bresenham), which is the
     * pattern's 1:1:3 part: NaN when the run is not found.
     */
    private fun sizeOfBlackWhiteBlackRun(image: BitMatrix, fromXIn: Int, fromYIn: Int, toXIn: Int, toYIn: Int): Float {
        var fromX = fromXIn
        var fromY = fromYIn
        var toX = toXIn
        var toY = toYIn
        // Mild variant of Bresenham's algorithm; see http://en.wikipedia.org/wiki/Bresenham's_line_algorithm
        val steep = abs(toY - fromY) > abs(toX - fromX)
        if (steep) {
            var temp = fromX
            fromX = fromY
            fromY = temp
            temp = toX
            toX = toY
            toY = temp
        }
        val dx = abs(toX - fromX)
        val dy = abs(toY - fromY)
        var error = -dx / 2
        val xstep = if (fromX < toX) 1 else -1
        val ystep = if (fromY < toY) 1 else -1

        // In black pixels, looking for white, first or second time.
        var state = 0
        // Loop up until x == toX, but not beyond
        val xLimit = toX + xstep
        var x = fromX
        var y = fromY
        while (x != xLimit) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y
            // Does current pixel mean we have moved white to black or vice versa? Scanning black in state 0,2 and
            // white in state 1, so if we find the wrong color, advance to next state or end if we are in state 2
            // already
            if ((state == 1) == image[realX, realY]) {
                if (state == 2) return Points.distance(x, y, fromX, fromY)
                state++
            }
            error += dy
            if (error > 0) {
                if (y == toY) break
                y += ystep
                error -= dx
            }
            x += xstep
        }
        // Found black-white-black; give the benefit of the doubt that the next pixel outside the image is "white" so
        // this last point at (toX+xStep,toY) is the right ending. This is really a small approximation;
        // (toX+xStep,toY+yStep) might be really correct. Ignore this.
        if (state == 2) return Points.distance(toX + xstep, toY, fromX, fromY)
        // else we didn't find even black-white-black; no estimate is really possible
        return Float.NaN
    }

    /** Looks for an alignment pattern within [allowanceFactor] modules of where it is estimated to be. */
    private fun findAlignmentInRegion(image: BitMatrix, overallEstModuleSize: Float, estAlignmentX: Int, estAlignmentY: Int, allowanceFactor: Float): AlignmentPattern {
        // Look for an alignment pattern (3 modules in size) around where it should be
        val allowance = (allowanceFactor * overallEstModuleSize).toInt()
        val alignmentAreaLeftX = maxOf(0, estAlignmentX - allowance)
        val alignmentAreaRightX = minOf(image.width - 1, estAlignmentX + allowance)
        if (alignmentAreaRightX - alignmentAreaLeftX < overallEstModuleSize * 3) throw QrReadException("alignment region too small")
        val alignmentAreaTopY = maxOf(0, estAlignmentY - allowance)
        val alignmentAreaBottomY = minOf(image.height - 1, estAlignmentY + allowance)
        if (alignmentAreaBottomY - alignmentAreaTopY < overallEstModuleSize * 3) throw QrReadException("alignment region too small")
        return AlignmentPatternFinder(
            image, alignmentAreaLeftX, alignmentAreaTopY,
            alignmentAreaRightX - alignmentAreaLeftX, alignmentAreaBottomY - alignmentAreaTopY, overallEstModuleSize,
        ).find()
    }
}
