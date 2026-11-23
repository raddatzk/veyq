@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.media.web

import me.raddatz.veyq.mobile.media.ExifReader
import me.raddatz.veyq.mobile.media.ExifTags
import me.raddatz.veyq.mobile.media.ImageHeader
import me.raddatz.veyq.mobile.media.MediaBytes
import me.raddatz.veyq.mobile.media.VideoHeader
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

// The media headers' façade for the web (ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/media`): an image's size
// (`media/ImageHeader`), a video's size and capture time (`media/VideoHeader`) and a photo's tags (`media/ExifReader`) behind
// plain values (`Shapes.kt`), so the web reads a picked file as the apps do and keeps only what a browser does: slicing the
// file. A read is the web's own (`MediaFile.read`, a `Blob` slice), awaited through the standard library's coroutine
// intrinsics — no kotlinx.coroutines, no serialization, no Ktor, no `NotOnTheWeb` refusal (the package's slice check) — and
// each answer is a `Promise`. No `Long` crosses: offsets, sizes and instants are numbers. A slice's bytes cross without a
// copy: the web's `Uint8Array` is read through an `Int8Array` view of its buffer — Kotlin/JS's `ByteArray` is one.

/** [block] run on the standard library's coroutine machinery, its answer or failure a `Promise`'s. */
private fun <T> promise(block: suspend () -> T): Promise<T> = Promise { resolve, reject ->
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result -> result.fold(resolve, reject) })
}

/** The web's file as the headers read it: each read the web's `Promise` of a slice, awaited. */
private fun bytesOf(file: MediaFile): MediaBytes = MediaBytes(file.size) { from, length ->
    val slice: Uint8Array = suspendCoroutine { c -> file.read(from, length).then({ c.resume(it) }, { c.resumeWithException(it) }) }
    Int8Array(slice.buffer, slice.byteOffset, slice.length).unsafeCast<ByteArray>()
}

private fun shape(size: ImageHeader.Size?): SizeShape? {
    size ?: return null
    val o: dynamic = js("({})")
    o.w = size.width
    o.h = size.height
    return o.unsafeCast<SizeShape>()
}

/** The pixel size [file]'s header names, as a browser shows it, or null ([ImageHeader.size]). */
@JsExport
fun imageSize(file: MediaFile): Promise<SizeShape?> = promise { shape(ImageHeader.size(bytesOf(file))) }

/** The display size [file]'s container names, as a player shows it, or null ([VideoHeader.dimensions]). */
@JsExport
fun videoSize(file: MediaFile): Promise<SizeShape?> = promise { shape(VideoHeader.dimensions(bytesOf(file))) }

/** The capture time (epoch ms) [file]'s container names, or null; [now] bounds a time ahead of the clock
 *  ([VideoHeader.captureTime]). */
@JsExport
fun videoCaptureTime(file: MediaFile, now: Double): Promise<Double?> = promise { VideoHeader.captureTime(bytesOf(file), now) }

/**
 * [file]'s tags under the web's names ([ExifReader.read]), only those [scope] asks for — `date`: the three EXIF dates;
 * `xmpDate`: XMP's capture time; `all`: every one —, or null where none is there. Only the tags present are set.
 */
@JsExport
fun exifTags(file: MediaFile, scope: String): Promise<ExifShape?> = promise { project(ExifReader.read(bytesOf(file)), scope) }

private fun project(t: ExifTags?, scope: String): ExifShape? {
    t ?: return null
    val o: dynamic = js("({})")
    var any = false
    fun put(name: String, value: Any?) {
        if (value != null) {
            o[name] = value
            any = true
        }
    }
    if (scope != "xmpDate") {
        put("DateTimeOriginal", t.dateTimeOriginalMs)
        put("DateTimeDigitized", t.dateTimeDigitizedMs)
        put("DateTime", t.dateTimeMs)
    }
    if (scope != "date") put("xmpCapturedAt", t.xmpCapturedAtMs)
    if (scope == "all") {
        put("Make", t.make)
        put("Model", t.model)
        put("Orientation", t.orientation)
        put("Software", t.software)
        put("Artist", t.artist)
        put("Copyright", t.copyright)
        put("Rating", t.rating)
        put("ImageDescription", t.imageDescription)
        put("ImageWidth", t.imageWidth)
        put("ImageLength", t.imageLength)
        put("ExposureTime", t.exposureTime)
        put("FNumber", t.fNumber)
        put("ISOSpeedRatings", t.isoSpeedRatings)
        put("ExposureBiasValue", t.exposureBiasValue)
        put("Flash", t.flash)
        put("FocalLength", t.focalLength)
        put("FocalLengthIn35mmFilm", t.focalLengthIn35mmFilm)
        put("PixelXDimension", t.pixelXDimension)
        put("PixelYDimension", t.pixelYDimension)
        put("LensMake", t.lensMake)
        put("LensModel", t.lensModel)
        put("GPSAltitude", t.gpsAltitude)
        put("GPSAltitudeRef", t.gpsAltitudeRef)
        put("GPSImgDirection", t.gpsImgDirection)
        put("GPSImgDirectionRef", t.gpsImgDirectionRef)
        put("GPSHPositioningError", t.gpsHPositioningError)
        put("latitude", t.latitude)
        put("longitude", t.longitude)
        put("xmpCreatorTool", t.xmpCreatorTool)
        put("xmpRating", t.xmpRating)
        put("xmpTitle", t.xmpTitle)
        put("xmpDescription", t.xmpDescription)
        put("iptcObjectName", t.iptcObjectName)
    }
    return if (any) o.unsafeCast<ExifShape>() else null
}

/** The most of a PNG or a WebP the tags are read from ([ExifReader.CHUNKED_PREFIX]). */
@JsExport
fun exifChunkedPrefix(): Int = ExifReader.CHUNKED_PREFIX

/** The most of a bare TIFF the tags are read from ([ExifReader.TIFF_PREFIX]). */
@JsExport
fun exifTiffPrefix(): Int = ExifReader.TIFF_PREFIX
