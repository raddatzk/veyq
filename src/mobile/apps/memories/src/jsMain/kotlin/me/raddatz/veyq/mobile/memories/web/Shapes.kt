@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.memories.web

import kotlin.js.collections.JsReadonlyArray

// The plain values Memories' façade takes and returns (ADR 0155 decision 5): external interfaces, so the `.d.mts` names each
// field with its type (never `any`) and the web passes and reads its own objects — no Kotlin class crosses. No `Long`
// crosses: a size, an offset and a length in ms are a `number`.

/** One medium the grid places: its id and its display aspect (width / height; absent, zero or negative: 4:3). */
@JsExport
external interface GridSourceShape {
    val id: String
    val aspectRatio: Double?
}

/** One placed tile: its frame in the grid, from the grid's own origin; a widow row's tile keeps the target height. */
@JsExport
external interface GridTileShape {
    val id: String
    val left: Double
    val top: Double
    val renderedWidth: Double
    val renderedHeight: Double
    val widow: Boolean
}

/** The grid laid out: every tile in the list's order, and the grid's whole height. */
@JsExport
external interface GridLayoutShape {
    val tiles: JsReadonlyArray<GridTileShape>
    val height: Double
}

/** A point in decimal degrees. */
@JsExport
external interface LatLngShape {
    val lat: Double
    val lng: Double
}

/** An ADTS frame: its byte length, its sample rate and its samples. */
@JsExport
external interface AdtsFrameShape {
    val length: Int
    val sampleRate: Int
    val samples: Int
}

/** One slice's part of an ADTS file's count: the bytes its whole frames span, and the seconds counted so far. */
@JsExport
external interface AdtsCountShape {
    val read: Int
    val seconds: Double
}

/** What the scan needs of a WAV's header: its PCM's shape, and where its data lies in the file and how long it is. */
@JsExport
external interface WavFormatShape {
    val channels: Int
    val sampleRate: Int
    val bitsPerSample: Int
    val float: Boolean
    val blockAlign: Int
    val dataOffset: Double
    val dataBytes: Double
}
