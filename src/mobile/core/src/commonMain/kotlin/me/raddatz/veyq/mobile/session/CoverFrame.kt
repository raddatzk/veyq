package me.raddatz.veyq.mobile.session

import kotlin.math.max

/**
 * The web's 16:9 pan-and-zoom cropper (`shared/ui/ImageCropper.vue`) as a pure model, the same math for every app: an image
 * of [imageWidth] × [imageHeight] pixels under a frame of [frameWidth] × [frameHeight] points. At zoom 1 the image fills the
 * frame ([baseScale], `object-fit: cover`); it zooms to 3; the pan ([Pose.x], [Pose.y]: the image's top-left against the
 * frame's) is held so no empty corner shows. A pose reads back as the normalised crop the cover stores ([crop]), and a stored
 * crop reopens as the pose that shows it ([pose]) — a cover framed in one client reopens as it was framed in another.
 */
class CoverFrame(
    val imageWidth: Double, val imageHeight: Double, val frameWidth: Double, val frameHeight: Double,
) {
    /** Where the image sits: its zoom over [baseScale], and its top-left against the frame's (≤ 0). */
    data class Pose(val zoom: Double, val x: Double, val y: Double)

    val baseScale: Double = max(frameWidth / imageWidth, frameHeight / imageHeight)

    /** The image centred at [zoom] (the web's `centerImage`): what a fresh picture opens with. */
    fun centred(zoom: Double = 1.0): Pose {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return clamp(Pose(z, (frameWidth - imageWidth * baseScale * z) / 2, (frameHeight - imageHeight * baseScale * z) / 2))
    }

    /** [pose] held so the image covers the frame (the web's `clampOffset`), its zoom within 1…3. */
    fun clamp(pose: Pose): Pose {
        val z = pose.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val w = imageWidth * baseScale * z
        val h = imageHeight * baseScale * z
        return Pose(z, pose.x.coerceIn(minOf(0.0, frameWidth - w), 0.0), pose.y.coerceIn(minOf(0.0, frameHeight - h), 0.0))
    }

    /** [pose] moved by a drag of [dx], [dy] points. */
    fun pan(pose: Pose, dx: Double, dy: Double): Pose = clamp(Pose(pose.zoom, pose.x + dx, pose.y + dy))

    /** [pose] zoomed by [factor] about the frame's centre (the web's `updateZoom`). */
    fun zoom(pose: Pose, factor: Double): Pose {
        val next = (pose.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val cx = frameWidth / 2
        val cy = frameHeight / 2
        return clamp(Pose(next, cx - (cx - pose.x) / pose.zoom * next, cy - (cy - pose.y) / pose.zoom * next))
    }

    /** The normalised rectangle of the image [pose] shows (the web's `currentCrop`). */
    fun crop(pose: Pose): NodeCover.Crop {
        val scale = baseScale * pose.zoom
        // `+ 0.0` makes a negated 0 the 0 it is (`-0.0` is no crop edge and compares unequal)
        return NodeCover.Crop(
            x = (-pose.x / scale / imageWidth).coerceIn(0.0, 1.0) + 0.0,
            y = (-pose.y / scale / imageHeight).coerceIn(0.0, 1.0) + 0.0,
            width = (frameWidth / scale / imageWidth).coerceIn(0.0, 1.0),
            height = (frameHeight / scale / imageHeight).coerceIn(0.0, 1.0),
        )
    }

    /** The pose that shows a stored [crop] (the web's `applyCrop`); the full frame (no crop stored) is the centred image. */
    fun pose(crop: NodeCover.Crop): Pose {
        if (crop == NodeCover.Crop.FULL) return centred()
        val z = (frameWidth / (crop.width * imageWidth * baseScale)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = baseScale * z
        return clamp(Pose(z, -crop.x * imageWidth * scale, -crop.y * imageHeight * scale))
    }

    companion object {
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 3.0
        /** A cover's frame: 16:9 (the web's `ImageCropper` aspect for a cover). */
        const val ASPECT = 16.0 / 9.0

        /**
         * The pixel rectangle of an image [width] × [height] that [crop] shows (the web's `coverImageStyle`, which fills its
         * box with exactly that part): what a client cuts out before it draws a cover. The full frame is the whole image.
         */
        fun pixels(crop: NodeCover.Crop, width: Int, height: Int): IntArray {
            val x = (crop.x * width).toInt().coerceIn(0, width - 1)
            val y = (crop.y * height).toInt().coerceIn(0, height - 1)
            val w = (crop.width * width).toInt().coerceIn(1, width - x)
            val h = (crop.height * height).toInt().coerceIn(1, height - y)
            return intArrayOf(x, y, w, h)
        }
    }
}
