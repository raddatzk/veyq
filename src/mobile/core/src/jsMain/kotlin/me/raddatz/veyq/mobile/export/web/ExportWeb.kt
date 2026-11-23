@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.export.web

import me.raddatz.veyq.mobile.export.ImportRefused
import me.raddatz.veyq.mobile.export.ManifestRules
import me.raddatz.veyq.mobile.export.ZipReader
import me.raddatz.veyq.mobile.export.ZipWriter
import me.raddatz.veyq.mobile.json.JsJsonAccess
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.js.collections.JsReadonlyArray

// The export archive's façade for the web (ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/export`): a download's ZIP
// written (`export/ZipWriter`) and its exact length (`ZipWriter.length`), an archive opened and read (`export/ZipReader`),
// the manifest checked and its payloads found (`export/ManifestRules`) — behind plain values (`Shapes.kt`), so the web
// writes and distrusts an archive as the apps do and keeps only what a browser does: its sink (a stream to the download
// route or a file), its `Blob` slices, the `JSON.parse` of a manifest. Streaming stays streaming: a record is handed to the
// web's sink as it is made, each chunk pulled from the web as it is written, an entry read a `READ_CHUNK` at a time — the
// writer holds one chunk and a directory line per entry, never the archive. Each write and read is the web's own promise,
// awaited through the standard library's coroutine intrinsics — no kotlinx.coroutines, no serialization, no Ktor, no
// `NotOnTheWeb` refusal (the package's slice check). A refusal crosses as an `Error` whose `veyq` names its kind (`zip`,
// `export`) beside its stable `code` and its `detail`; any other failure (a sink's, a read's) crosses as it was thrown. Bytes
// cross without a copy: a `Uint8Array` is read through an `Int8Array` view of its buffer — Kotlin/JS's `ByteArray` is one —
// and handed back as a `Uint8Array` view of the same bytes.

/** A writer under way. Opaque: passed back, never read. */
@JsExport
class ZipWriterHandle internal constructor(internal val writer: ZipWriter)

/** An opened archive. Opaque: passed back, never read. */
@JsExport
class ZipReaderHandle internal constructor(internal val reader: ZipReader)

/** One entry's read under way. Opaque: passed back, never read. */
@JsExport
class ZipReadingHandle internal constructor(internal val chunks: ZipReader.Chunks)

/** A refusal as the web throws it: an `Error` with `veyq` (its kind), `code` and, where there is one, `detail`. */
private fun refusal(kind: String, code: String, message: String?, detail: String? = null): Throwable {
    val e: dynamic = js("new Error()")
    e.message = message ?: code
    e.veyq = kind
    e.code = code
    if (detail != null) e.detail = detail
    return e.unsafeCast<Throwable>()
}

/** [block], a core refusal turned into the web's. */
private inline fun <T> refusing(block: () -> T): T = try {
    block()
} catch (e: ZipReader.ZipError) {
    throw refusal("zip", e.code.name.lowercase(), e.message)
} catch (e: ImportRefused) {
    throw refusal("export", e.refusal.wire, e.message, e.detail)
}

/** [block] on the standard library's coroutine machinery, its answer or failure (a refusal the web's) a `Promise`'s. */
private fun <T> promise(block: suspend () -> T): Promise<T> = Promise { resolve, reject ->
    suspend { refusing { block() } }.startCoroutine(Continuation(EmptyCoroutineContext) { result -> result.fold(resolve, reject) })
}

private suspend fun <T> awaited(p: Promise<T>): T = suspendCoroutine { c -> p.then({ c.resume(it) }, { c.resumeWithException(it) }) }

private fun bytes(u: Uint8Array): ByteArray = Int8Array(u.buffer, u.byteOffset, u.length).unsafeCast<ByteArray>()

private fun view(b: ByteArray): Uint8Array = b.unsafeCast<Int8Array>().let { Uint8Array(it.buffer, it.byteOffset, it.length) }

// --- writing ---------------------------------------------------------------------------------------------------------

/** A writer into [sink], every entry stamped [modifiedMs] (UTC, the DOS 2-second grid), ZIP64 from [zip64At] bytes. */
@JsExport
fun zipWriter(sink: ZipSinkShape, modifiedMs: Double, zip64At: Double): ZipWriterHandle =
    ZipWriterHandle(ZipWriter({ awaited(sink.write(view(it))) }, modifiedMs.toLong(), zip64At.toLong()))

/**
 * One file of [size] bytes, pulled from [chunks] and written as each arrives; its CRC-32. Refused (`zip`) for a size or a
 * path that is not plain (`corrupt`), a path written twice (`duplicate`), chunks that add up to anything but [size]
 * (`size_mismatch`).
 */
@JsExport
fun zipAddFile(writer: ZipWriterHandle, path: String, size: Double, chunks: ZipChunksShape): Promise<Double> = promise {
    writer.writer.addFile(path, ZipWriter.declaredSize(size, path)) { put ->
        while (true) put(bytes(awaited(chunks.next()) ?: break))
    }.toDouble()
}

/** A directory entry (`path/`): one that is empty still unpacks. */
@JsExport
fun zipAddDirectory(writer: ZipWriterHandle, path: String): Promise<Unit> = promise { writer.writer.addDirectory(path) }

/** The central directory and the end records: the archive whole. The web closes its sink after. */
@JsExport
fun zipFinish(writer: ZipWriterHandle): Promise<Unit> = promise { writer.writer.finish() }

/** The archive ends unwritten: nothing more is taken. The web aborts its sink. */
@JsExport
fun zipAbort(writer: ZipWriterHandle) = writer.writer.abort()

/** The exact length in bytes of the archive a writer writes for [plan], in order, ZIP64 from [zip64At] bytes. */
@JsExport
fun zipLength(plan: JsReadonlyArray<ZipPlanShape>, zip64At: Double): Double =
    ZipWriter.length(plan.unsafeCast<Array<ZipPlanShape>>().map { ZipWriter.Planned(it.path, (it.size ?: 0.0).toLong(), it.directory == true) }, zip64At.toLong()).toDouble()

// --- reading ---------------------------------------------------------------------------------------------------------

/** [file] opened: its directory read and its structure checked, no entry's bytes read. Refused (`zip`) for what it does not take. */
@JsExport
fun zipOpen(file: ArchiveFileShape): Promise<ZipReaderHandle> = promise {
    ZipReaderHandle(ZipReader.open(file.size.toLong()) { from, length -> bytes(awaited(file.read(from.toDouble(), length))) })
}

/** The archive's entries, in its directory's order. */
@JsExport
fun zipEntries(reader: ZipReaderHandle): Array<ZipEntryShape> = reader.reader.entries.map { e ->
    val o: dynamic = js("({})")
    o.name = e.name
    o.size = e.size.toDouble()
    o.crc32 = e.crc32.toDouble()
    o.offset = e.offset.toDouble()
    o.dataAt = e.dataAt.toDouble()
    o.unsafeCast<ZipEntryShape>()
}.toTypedArray()

/** Entry [name]'s read, [zipNext] a chunk at a time. Refused (`zip` `missing`) for no such entry. */
@JsExport
fun zipReading(reader: ZipReaderHandle, name: String): ZipReadingHandle = refusing { ZipReadingHandle(reader.reader.reading(name)) }

/** The entry's next chunk (at most [zipReadChunk] bytes); `null` after the last, once its CRC-32 matched (`zip` `crc_mismatch` where not). */
@JsExport
fun zipNext(reading: ZipReadingHandle): Promise<Uint8Array?> = promise { reading.chunks.next()?.let(::view) }

/** Small entry [name] read whole, refused (`zip` `unsupported`) above [max] bytes; its CRC-32 checked. */
@JsExport
fun zipBytes(reader: ZipReaderHandle, name: String, max: Double): Promise<Uint8Array> = promise { view(reader.reader.bytes(name, max.toLong())) }

/** How much of an entry one read takes: the memory bound of a streamed read. */
@JsExport
fun zipReadChunk(): Int = ZipReader.READ_CHUNK

// --- the manifest ----------------------------------------------------------------------------------------------------

/**
 * [json] (a manifest as `JSON.parse` read it) checked; its version. Refused (`export`) with `not_an_export`,
 * `newer_version`, `invalid_manifest`, `too_large` or `unsafe_path`, and a `detail` where the check names one.
 */
@JsExport
fun manifestCheck(json: JsonShape?): Int = refusing { ManifestRules.check(JsJsonAccess, json) }

/** Every payload reference inside [value] in walk order — [value]'s own objects. */
@JsExport
fun manifestPayloads(value: JsonShape?): Array<JsonShape> = ManifestRules.payloadsOf(JsJsonAccess, value).toTypedArray().unsafeCast<Array<JsonShape>>()

/** Whether [path] is a version 1 payload path an importer may open. */
@JsExport
fun manifestPathV1IsSafe(path: String): Boolean = ManifestRules.isSafePayloadPathV1(path)

/** The application a content type belongs to: the part before its dot. */
@JsExport
fun manifestApplicationOf(contentType: String): String = ManifestRules.applicationOf(contentType)

/** The manifest's facts and caps. */
@JsExport
fun manifestFacts(): ManifestFactsShape {
    val limits: dynamic = js("({})")
    limits.appVersion = ManifestRules.MAX_APP_VERSION
    limits.applications = ManifestRules.MAX_APPLICATIONS
    limits.kind = ManifestRules.MAX_KIND
    limits.name = ManifestRules.MAX_NAME
    limits.path = ManifestRules.MAX_PATH
    limits.contentType = ManifestRules.MAX_CONTENT_TYPE
    limits.mediaType = ManifestRules.MAX_MEDIA_TYPE
    val o: dynamic = js("({})")
    o.format = ManifestRules.FORMAT
    o.version = ManifestRules.VERSION
    o.path = ManifestRules.PATH
    o.pathV1 = ManifestRules.PATH_V1
    o.maxBytes = ManifestRules.MAX_BYTES.toDouble()
    o.maxDepth = ManifestRules.MAX_DEPTH
    o.maxNodes = ManifestRules.MAX_NODES
    o.limits = limits
    return o.unsafeCast<ManifestFactsShape>()
}
