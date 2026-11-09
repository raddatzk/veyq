package me.raddatz.veyq.mobile.session

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The web's `ImageCropper` math as the apps' cover sheet frames a picture with it. */
class CoverFrameTest {
    // a 4:3 picture under a 16:9 frame 320 points wide
    private val frame = CoverFrame(4000.0, 3000.0, 320.0, 180.0)

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9
    private fun assertCrop(expected: NodeCover.Crop, actual: NodeCover.Crop) =
        assertTrue(near(expected.x, actual.x) && near(expected.y, actual.y) && near(expected.width, actual.width) && near(expected.height, actual.height), "$actual is not $expected")

    @Test
    fun aFreshPictureFillsTheFrameCentred() {
        // 4:3 under 16:9: the whole width, three quarters of the height, centred
        assertCrop(NodeCover.Crop(0.0, 0.125, 1.0, 0.75), frame.crop(frame.centred()))
    }

    @Test
    fun theZoomStaysWithinOneAndThree() {
        assertEquals(CoverFrame.MAX_ZOOM, frame.zoom(frame.centred(), 10.0).zoom)
        assertEquals(CoverFrame.MIN_ZOOM, frame.zoom(frame.centred(), 0.1).zoom)
    }

    @Test
    fun aPanNeverShowsAnEmptyCorner() {
        val far = frame.pan(frame.centred(2.0), 10_000.0, -10_000.0)
        val c = frame.crop(far)
        assertTrue(near(c.x, 0.0), "dragged right: the image's left edge at the frame's")
        assertTrue(near(c.y + c.height, 1.0), "dragged up: the image's bottom edge at the frame's")
    }

    @Test
    fun aStoredCropReopensAsItWasFramed() {
        val framed = frame.pan(frame.zoom(frame.centred(), 2.0), -40.0, 25.0)
        val crop = frame.crop(framed)
        // another client's frame of another size reads the same crop back
        val other = CoverFrame(4000.0, 3000.0, 640.0, 360.0)
        assertCrop(crop, other.crop(other.pose(crop)))
    }

    @Test
    fun anEdgeIsZeroNotMinusZero() {
        // dragged to the image's left edge: the crop starts at 0, equal to a stored 0 (a negated 0 is not)
        val crop = frame.crop(frame.pan(frame.centred(2.0), 10_000.0, 0.0))
        assertEquals(NodeCover.Crop(0.0, crop.y, crop.width, crop.height), crop)
    }

    @Test
    fun theFullFrameReopensCentred() {
        assertEquals(frame.centred(), frame.pose(NodeCover.Crop.FULL))
    }

    @Test
    fun aCropIsThePixelsItShows() {
        assertContentEquals(intArrayOf(0, 375, 4000, 2250), CoverFrame.pixels(NodeCover.Crop(0.0, 0.125, 1.0, 0.75), 4000, 3000))
        assertContentEquals(intArrayOf(0, 0, 4000, 3000), CoverFrame.pixels(NodeCover.Crop.FULL, 4000, 3000))
    }
}
