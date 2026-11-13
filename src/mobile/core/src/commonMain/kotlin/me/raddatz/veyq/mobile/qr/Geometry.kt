// Ported to Kotlin from ZXing's ResultPoint.java, common/detector/MathUtils.java, common/PerspectiveTransform.java,
// common/GridSampler.java and common/DefaultGridSampler.java (Copyright 2007 ZXing authors, Apache License 2.0; NOTICE
// beside this file names the upstream commit). Changed: one sampler, no global instance; a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

import kotlin.math.sqrt

/** A point in the frame, `x` across and `y` down. */
internal open class ResultPoint(val x: Float, val y: Float)

internal object Points {
    fun round(d: Float): Int = (d + (if (d < 0.0f) -0.5f else 0.5f)).toInt()

    fun distance(aX: Float, aY: Float, bX: Float, bY: Float): Float {
        val xDiff = (aX - bX).toDouble()
        val yDiff = (aY - bY).toDouble()
        return sqrt(xDiff * xDiff + yDiff * yDiff).toFloat()
    }

    fun distance(aX: Int, aY: Int, bX: Int, bY: Int): Float {
        val xDiff = (aX - bX).toDouble()
        val yDiff = (aY - bY).toDouble()
        return sqrt(xDiff * xDiff + yDiff * yDiff).toFloat()
    }

    fun distance(a: ResultPoint, b: ResultPoint): Float = distance(a.x, a.y, b.x, b.y)

    /** Orders three finder patterns as bottom-left, top-left, top-right (in place). */
    fun <T : ResultPoint> orderBestPatterns(patterns: Array<T>) {
        val zeroOneDistance = distance(patterns[0], patterns[1])
        val oneTwoDistance = distance(patterns[1], patterns[2])
        val zeroTwoDistance = distance(patterns[0], patterns[2])
        var pointA: T
        val pointB: T
        var pointC: T
        // The top-left is the point opposite the longest side.
        if (oneTwoDistance >= zeroOneDistance && oneTwoDistance >= zeroTwoDistance) {
            pointB = patterns[0]; pointA = patterns[1]; pointC = patterns[2]
        } else if (zeroTwoDistance >= oneTwoDistance && zeroTwoDistance >= zeroOneDistance) {
            pointB = patterns[1]; pointA = patterns[0]; pointC = patterns[2]
        } else {
            pointB = patterns[2]; pointA = patterns[0]; pointC = patterns[1]
        }
        // The cross product tells which of A and C is the bottom-left: a code read mirrored is told apart here.
        if (crossProductZ(pointA, pointB, pointC) < 0.0f) {
            val temp = pointA
            pointA = pointC
            pointC = temp
        }
        patterns[0] = pointA
        patterns[1] = pointB
        patterns[2] = pointC
    }

    private fun crossProductZ(pointA: ResultPoint, pointB: ResultPoint, pointC: ResultPoint): Float {
        val bX = pointB.x
        val bY = pointB.y
        return ((pointC.x - bX) * (pointA.y - bY)) - ((pointC.y - bY) * (pointA.x - bX))
    }
}

/** A plane-to-plane projection: where a module of the code lies in the frame. */
internal class PerspectiveTransform private constructor(
    private val a11: Float, private val a21: Float, private val a31: Float,
    private val a12: Float, private val a22: Float, private val a32: Float,
    private val a13: Float, private val a23: Float, private val a33: Float,
) {
    fun transformPoints(points: FloatArray) {
        val maxI = points.size - 1 // points.size must be even
        var i = 0
        while (i < maxI) {
            val x = points[i]
            val y = points[i + 1]
            val denominator = a13 * x + a23 * y + a33
            points[i] = (a11 * x + a21 * y + a31) / denominator
            points[i + 1] = (a12 * x + a22 * y + a32) / denominator
            i += 2
        }
    }

    private fun buildAdjoint() = PerspectiveTransform(
        a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
        a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
        a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21,
    )

    private fun times(o: PerspectiveTransform) = PerspectiveTransform(
        a11 * o.a11 + a21 * o.a12 + a31 * o.a13, a11 * o.a21 + a21 * o.a22 + a31 * o.a23, a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
        a12 * o.a11 + a22 * o.a12 + a32 * o.a13, a12 * o.a21 + a22 * o.a22 + a32 * o.a23, a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
        a13 * o.a11 + a23 * o.a12 + a33 * o.a13, a13 * o.a21 + a23 * o.a22 + a33 * o.a23, a13 * o.a31 + a23 * o.a32 + a33 * o.a33,
    )

    companion object {
        fun quadrilateralToQuadrilateral(
            x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
            x0p: Float, y0p: Float, x1p: Float, y1p: Float, x2p: Float, y2p: Float, x3p: Float, y3p: Float,
        ): PerspectiveTransform {
            val qToS = squareToQuadrilateral(x0, y0, x1, y1, x2, y2, x3, y3).buildAdjoint()
            val sToQ = squareToQuadrilateral(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p)
            return sToQ.times(qToS)
        }

        private fun squareToQuadrilateral(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PerspectiveTransform {
            val dx3 = x0 - x1 + x2 - x3
            val dy3 = y0 - y1 + y2 - y3
            if (dx3 == 0.0f && dy3 == 0.0f) {
                // Affine
                return PerspectiveTransform(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0f, 0.0f, 1.0f)
            }
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val denominator = dx1 * dy2 - dx2 * dy1
            val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
            val a23 = (dx1 * dy3 - dx3 * dy1) / denominator
            return PerspectiveTransform(
                x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
                y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
                a13, a23, 1.0f,
            )
        }
    }
}

/** Reads a code's modules out of the frame's black matrix through a [PerspectiveTransform]. */
internal object GridSampler {
    fun sampleGrid(image: BitMatrix, dimensionX: Int, dimensionY: Int, transform: PerspectiveTransform): BitMatrix {
        if (dimensionX <= 0 || dimensionY <= 0) throw QrReadException("no grid")
        val bits = BitMatrix(dimensionX, dimensionY)
        val points = FloatArray(2 * dimensionX)
        for (y in 0 until dimensionY) {
            val max = points.size
            val iValue = y + 0.5f
            var x = 0
            while (x < max) {
                points[x] = (x / 2).toFloat() + 0.5f
                points[x + 1] = iValue
                x += 2
            }
            transform.transformPoints(points)
            // Quick check to see if points transformed to something inside the image; sufficient to check the endpoints.
            checkAndNudgePoints(image, points)
            x = 0
            try {
                while (x < max) {
                    if (image[points[x].toInt(), points[x + 1].toInt()]) bits.set(x / 2, y)
                    x += 2
                }
            } catch (_: IndexOutOfBoundsException) {
                // A point between the checked ends fell off the matrix: no code (as upstream, which catches the same).
                throw QrReadException("grid outside the frame")
            }
        }
        return bits
    }

    /**
     * Brings the ends of a row of points that lie one pixel outside the image back onto its edge, and fails for a point
     * further out: only the first and last points of a row are checked, the rest assumed between them.
     */
    private fun checkAndNudgePoints(image: BitMatrix, points: FloatArray) {
        val width = image.width
        val height = image.height
        var nudged = true
        val maxOffset = points.size - 1 // points.size must be even
        var offset = 0
        while (offset < maxOffset && nudged) {
            nudged = nudge(points, offset, width, height)
            offset += 2
        }
        nudged = true
        offset = points.size - 2
        while (offset >= 0 && nudged) {
            nudged = nudge(points, offset, width, height)
            offset -= 2
        }
    }

    private fun nudge(points: FloatArray, offset: Int, width: Int, height: Int): Boolean {
        val x = points[offset].toInt()
        val y = points[offset + 1].toInt()
        if (x < -1 || x > width || y < -1 || y > height) throw QrReadException("grid outside the frame")
        var nudged = false
        if (x == -1) {
            points[offset] = 0.0f
            nudged = true
        } else if (x == width) {
            points[offset] = (width - 1).toFloat()
            nudged = true
        }
        if (y == -1) {
            points[offset + 1] = 0.0f
            nudged = true
        } else if (y == height) {
            points[offset + 1] = (height - 1).toFloat()
            nudged = true
        }
        return nudged
    }
}
