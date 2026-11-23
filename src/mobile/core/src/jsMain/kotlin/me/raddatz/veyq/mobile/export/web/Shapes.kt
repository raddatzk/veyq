@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.export.web

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// The plain values the export archive's façade takes and returns (ADR 0155 decision 5): external interfaces, so the `.d.ts`
// names each field with its type and the web passes its own objects.

/** Where a writer's records go, in order, each awaited: the web's `ByteSink.write`. */
@JsExport
external interface ZipSinkShape {
    fun write(bytes: Uint8Array): Promise<Unit>
}

/** A file's bytes, pulled one chunk at a time; `null` once they are all delivered. */
@JsExport
external interface ZipChunksShape {
    fun next(): Promise<Uint8Array?>
}

/** An archive the web hands over: its size, and a read of `[from, from + length)` (a `Blob` slice). */
@JsExport
external interface ArchiveFileShape {
    val size: Double
    fun read(from: Double, length: Int): Promise<Uint8Array>
}

/** One entry of a planned archive: a file of `size` bytes, or a directory (`directory: true`). */
@JsExport
external interface ZipPlanShape {
    val path: String
    val size: Double?
    val directory: Boolean?
}

/** One file of an opened archive: its name, its size, the CRC-32 its directory records, where its header and bytes start. */
@JsExport
external interface ZipEntryShape {
    val name: String
    val size: Double
    val crc32: Double
    val offset: Double
    val dataAt: Double
}

/** A parsed JSON value the web hands over (`JSON.parse`'s), read where it lies. */
@JsExport
external interface JsonShape

/** The manifest's facts: where it lies, the version written, the caps a reader holds. */
@JsExport
external interface ManifestFactsShape {
    val format: String
    val version: Int
    val path: String
    val pathV1: String
    val maxBytes: Double
    val maxDepth: Int
    val maxNodes: Int
    val limits: ManifestLimitsShape
}

/** The contract's `maxLength` (and `maxItems`) of every field the check reads. */
@JsExport
external interface ManifestLimitsShape {
    val appVersion: Int
    val applications: Int
    val kind: Int
    val name: Int
    val path: Int
    val contentType: Int
    val mediaType: Int
}
