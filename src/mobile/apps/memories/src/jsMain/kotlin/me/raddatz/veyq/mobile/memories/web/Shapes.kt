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

/**
 * One listed medium as the gallery's model reads it (`GalleryWeb.kt`): its id, its original's sealed type and name, whether
 * the operator blocked it, who added it, and of its opened listed tier the geometry and the capture times. Times are epoch
 * milliseconds as a `number`. No key: the web reads a medium's bytes itself.
 */
@JsExport
external interface GalleryItemShape {
    val mediaId: String
    val type: String?
    val name: String?
    val blocked: Boolean
    val ownerKey: String?
    val width: Double?
    val height: Double?
    val capturedAt: Double?
    val capturedAtOverride: Double?
}

/**
 * What the gallery's model reads and writes through the web (its `GalleryModel.Door`): each a `Promise`, a rejection where
 * it did not read or write.
 */
@JsExport
external interface GalleryDoorShape {
    /** The node's media as listed now, opened; null where it does not read. */
    fun gallery(): kotlin.js.Promise<JsReadonlyArray<GalleryItemShape>?>

    /** Whether the node's media moved since the last listing (the conditional read: a `304` is no). */
    fun moved(): kotlin.js.Promise<Boolean>

    /** Medium [mediaId] to the trash: `done`, `gone`, `scope_deleting`, `blocked` or `failed`. */
    fun trash(mediaId: String): kotlin.js.Promise<String>

    /** Medium [mediaId] back from the trash, answered as [trash]. */
    fun restore(mediaId: String): kotlin.js.Promise<String>

    /** Whether [mediaId] is one this device sent under the guest's link identity. */
    fun isOwn(mediaId: String): Boolean

    /** A guest's own medium [mediaId] purged for good; whether it went. */
    fun purge(mediaId: String): kotlin.js.Promise<Boolean>

    /** The settle (the `auto` date and place kept current) at the model's settle points: whether it wrote. */
    fun settle(): kotlin.js.Promise<Boolean>
}

/**
 * A page's state as the web draws it: `loading` before the first read, `error` where the last did not read (its rows stay),
 * else `ready`; the media ids of its grid in order, of its music and of its GPX tracks; whether a trash's Undo is offered;
 * the guest's own medium whose purge is asked; what the last Undo or purge left to say.
 */
@JsExport
external interface GalleryStateShape {
    val status: String
    val rows: JsReadonlyArray<String>
    val music: JsReadonlyArray<String>
    val tracks: JsReadonlyArray<String>
    val undo: Boolean
    val purging: String?
    val said: SaidShape?
}

/**
 * What a delete or an Undo left to say: `restoreRefused` (by [name], else the [unnamedAt]th medium; [reason] `gone`,
 * `scope_deleting`, `blocked` or `failed`) or `purgeFailed`.
 */
@JsExport
external interface SaidShape {
    val kind: String
    val name: String?
    val unnamedAt: Int?
    val reason: String?
}
