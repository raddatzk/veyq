@file:OptIn(ExperimentalJsExport::class)
@file:Suppress("PropertyName") // the tags' standard names, as the web's ExifTags type spells them

package me.raddatz.veyq.mobile.media.web

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// The plain values the media headers' façade takes and returns (ADR 0155 decision 5): external interfaces, so the `.d.ts`
// names each field with its type and the web passes its own objects.

/** A file the web hands over: its size, and a read of `[from, from + length)` — fewer bytes only at its end. */
@JsExport
external interface MediaFile {
    val size: Double
    fun read(from: Double, length: Int): Promise<Uint8Array>
}

/** A size in pixels. */
@JsExport
external interface SizeShape {
    val w: Double
    val h: Double
}

/**
 * A photo's tags by their standard names (the web's `ExifTags`): only those present are set. Dates are epoch ms, the wall
 * clock read in the device's zone; the position signed decimal degrees.
 */
@JsExport
external interface ExifShape {
    val Make: String?
    val Model: String?
    val Orientation: Double?
    val Software: String?
    val Artist: String?
    val Copyright: String?
    val Rating: Double?
    val ImageDescription: String?
    val DateTime: Double?
    val ImageWidth: Double?
    val ImageLength: Double?
    val ExposureTime: Double?
    val FNumber: Double?
    val ISOSpeedRatings: Double?
    val DateTimeOriginal: Double?
    val DateTimeDigitized: Double?
    val ExposureBiasValue: Double?
    val Flash: Double?
    val FocalLength: Double?
    val FocalLengthIn35mmFilm: Double?
    val PixelXDimension: Double?
    val PixelYDimension: Double?
    val LensMake: String?
    val LensModel: String?
    val GPSAltitude: Double?
    val GPSAltitudeRef: Double?
    val GPSImgDirection: Double?
    val GPSImgDirectionRef: String?
    val GPSHPositioningError: Double?
    val latitude: Double?
    val longitude: Double?
    val xmpCapturedAt: Double?
    val xmpCreatorTool: String?
    val xmpRating: Double?
    val xmpTitle: String?
    val xmpDescription: String?
    val iptcObjectName: String?
}
