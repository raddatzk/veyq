@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.memories.web

import me.raddatz.veyq.mobile.memories.MediaGridLayout
import me.raddatz.veyq.mobile.memories.Participants
import me.raddatz.veyq.mobile.memories.music.Frames
import me.raddatz.veyq.mobile.memories.music.Wav
import me.raddatz.veyq.mobile.memories.place.medoidAt
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.collections.JsReadonlyArray

// Memories' façade for the web (ADR 0155 decisions 4, 5 and 7.3; `@veyq/core-js/memories`), the code of Memories' own module
// file (`kotlin_veyq_mobile_apps_memories.mjs`), which imports the platform's: the justified grid's geometry
// (`MediaGridLayout`), a Memory's medoid (`MemoryLocation.medoid`), the participants' merge (`Participants.merge`), the audio
// frame headers and a raw ADTS file's count (`music/Frames`) and a WAV's header, samples and ends (`music/Wav`) behind plain
// values (`Shapes.kt`), so the web answers them as the apps do and keeps what a browser does — measuring the container,
// reading a file's slices, decoding audio, sealing and writing. Nothing here reaches kotlinx.serialization, coroutines, Ktor
// or a `NotOnTheWeb` refusal (the package's slice check), and no `Long` crosses.

private fun bytes(b: Uint8Array): ByteArray = Int8Array(b.buffer, b.byteOffset, b.length).unsafeCast<ByteArray>()

/** A fresh plain object, filled field by field. */
private fun plain(): dynamic = js("({})")

/** A refusal as the web's own `Error`, its message the rule's (`not_wav`, `wav_format`): no Kotlin class crosses. */
private fun refused(message: String?): Nothing {
    val m = message
    throw js("new Error(m)").unsafeCast<Throwable>()
}

private fun strings(a: JsReadonlyArray<String>): List<String> = a.unsafeCast<Array<String>>().asList()

/** A medium [width] × [height]'s aspect as the grid shows it, bounded to 3:4 … 4:3 (`MediaGridLayout.displayAspect`). */
@JsExport
fun memoriesDisplayAspect(width: Double?, height: Double?): Double = MediaGridLayout.displayAspectOf(width, height)

/**
 * [items] laid out in justified rows [containerWidth] wide, each aiming at [targetHeight], [gap] apart; the last, widow
 * row keeps the target height, or is left out where [showWidows] is false (`MediaGridLayout.justify`).
 */
@JsExport
fun memoriesGridLayout(
    items: JsReadonlyArray<GridSourceShape>, containerWidth: Double, targetHeight: Double, gap: Double, showWidows: Boolean,
): GridLayoutShape {
    val all = items.unsafeCast<Array<GridSourceShape>>()
    val placed = ArrayList<MediaGridLayout.Tile>(all.size)
    val height = MediaGridLayout.justifyInto(all.map { MediaGridLayout.Source(it.id, it.aspectRatio) }, containerWidth, targetHeight, gap, showWidows, placed)
    val tiles = arrayOfNulls<Any>(placed.size)
    placed.forEachIndexed { i, t ->
        val o = plain()
        o.id = t.id
        o.left = t.left
        o.top = t.top
        o.renderedWidth = t.width
        o.renderedHeight = t.height
        o.widow = t.widow
        tiles[i] = o
    }
    val o = plain()
    o.tiles = tiles
    o.height = height
    return o.unsafeCast<GridLayoutShape>()
}

/** The point of [points] with the least summed great-circle distance to the others; ties to the first; null for none. */
@JsExport
fun memoriesMedoid(points: JsReadonlyArray<LatLngShape>): LatLngShape? {
    val all = points.unsafeCast<Array<LatLngShape>>()
    val i = medoidAt(DoubleArray(all.size) { all[it].lat }, DoubleArray(all.size) { all[it].lng })
    if (i < 0) return null
    val o = plain()
    o.lat = all[i].lat
    o.lng = all[i].lng
    return o.unsafeCast<LatLngShape>()
}

/** The participants' merge on a conflict: [stored] united with [wanted], less what the editor removed ([openedWith] − [wanted]). */
@JsExport
fun memoriesMergeParticipants(
    stored: JsReadonlyArray<String>, wanted: JsReadonlyArray<String>, openedWith: JsReadonlyArray<String>,
): JsReadonlyArray<String> =
    Participants.merge(strings(stored), strings(wanted), strings(openedWith)).toTypedArray().unsafeCast<JsReadonlyArray<String>>()

/** The byte length of the MP3 (Layer III) frame whose header stands at [at] of [b], or 0 where none does. */
@JsExport
fun memoriesMp3FrameLength(b: Uint8Array, at: Int): Int = Frames.mp3FrameLength(bytes(b), at)

/** The ADTS frame whose header stands at [at] of [b]; null where none does. */
@JsExport
fun memoriesAdtsFrame(b: Uint8Array, at: Int): AdtsFrameShape? {
    val f = Frames.adtsFrame(bytes(b), at) ?: return null
    val o = plain()
    o.length = f.length
    o.sampleRate = f.sampleRate
    o.samples = f.samples
    return o.unsafeCast<AdtsFrameShape>()
}

/** The first offset in [b] where [chain] frames of [kind] (`mp3`, `adts`) stand in a row, or -1. */
@JsExport
fun memoriesFirstFrameChain(b: Uint8Array, kind: String, chain: Int): Int = Frames.firstFrameChain(
    bytes(b),
    when (kind) {
        "mp3" -> Frames::mp3FrameLength
        "adts" -> Frames::adtsFrameLength
        else -> refused("frame_kind")
    },
    chain,
)

/** The frames of [chunk], read from a file at [at] of [size] bytes, counted onto [seconds] (the ADTS count's step). */
@JsExport
fun memoriesAdtsCount(chunk: Uint8Array, at: Double, size: Double, seconds: Double): AdtsCountShape {
    val c = Frames.adtsCount(bytes(chunk), at, size, seconds)
    val o = plain()
    o.read = c.read
    o.seconds = c.seconds
    return o.unsafeCast<AdtsCountShape>()
}

/** The ADTS count's answer where a slice holds no frame [remaining] bytes before the end: its ms, or null for a break. */
@JsExport
fun memoriesAdtsEnd(seconds: Double, remaining: Double): Double? = Frames.adtsEnd(seconds, remaining)?.toDouble()

/** The ADTS count's [seconds] in whole ms. */
@JsExport
fun memoriesAdtsMs(seconds: Double): Double = Frames.adtsMs(seconds).toDouble()

/** The header in [head] (a WAV file's first bytes) of a file of [fileSize] bytes; null where `data` lies beyond it. Throws
 *  `Error('not_wav')` for no WAV, `Error('wav_format')` for one this does not read. */
@JsExport
fun memoriesWavHeader(head: Uint8Array, fileSize: Double): WavFormatShape? {
    val f = try {
        Wav.parseHeader(bytes(head), fileSize.toLong())
    } catch (e: Wav.Unreadable) {
        refused(e.message)
    } ?: return null
    val o = plain()
    o.channels = f.channels
    o.sampleRate = f.sampleRate
    o.bitsPerSample = f.bitsPerSample
    o.float = f.float
    o.blockAlign = f.blockAlign
    o.dataOffset = f.dataOffset.toDouble()
    o.dataBytes = f.dataBytes.toDouble()
    return o.unsafeCast<WavFormatShape>()
}

private fun format(f: WavFormatShape) =
    Wav.Format(f.channels, f.sampleRate, f.bitsPerSample, f.float, f.blockAlign, f.dataOffset.toLong(), f.dataBytes.toLong())

/** Whole blocks of [data] as samples per channel in [-1, 1] (`Wav.samples`), one array per channel. */
@JsExport
fun memoriesWavSamples(data: Uint8Array, fmt: WavFormatShape): JsReadonlyArray<Float32Array> =
    Wav.samples(bytes(data), format(fmt)).channels.toTypedArray().unsafeCast<JsReadonlyArray<Float32Array>>()

/** How many bytes of [fmt]'s data the first and the last [limitMs] each span: whole blocks, at most all of it. */
@JsExport
fun memoriesWavEndBytes(fmt: WavFormatShape, limitMs: Double): Double = Wav.endBytes(format(fmt), limitMs.toLong()).toDouble()
