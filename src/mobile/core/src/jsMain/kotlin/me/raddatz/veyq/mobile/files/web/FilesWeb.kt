@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.files.web

import me.raddatz.veyq.mobile.blob.DownloadName
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.export.ArchiveName
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

// The file facts' façade for the web (ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/files`): the platform's sniff
// (`blob/Sniff`), download name (`blob/DownloadName`) and the archive's safe names (`export/ArchiveName`) behind plain values
// (`Shapes.kt`), so the web decides what a file is, how it is saved and what it is called inside a ZIP as the apps do, and
// keeps only what a browser does: reading and streaming the chunks, decoding a text, drawing. Nothing here reaches
// kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the package's slice check), no `Long` crosses, and a
// file's bytes cross without a copy: the web's `Uint8Array` is read through an `Int8Array` view of its buffer — Kotlin/JS's
// `ByteArray` is one.

private fun bytes(head: Uint8Array): ByteArray = Int8Array(head.buffer, head.byteOffset, head.length).unsafeCast<ByteArray>()

/** The raster image type [head] is (`image/jpeg` … `image/heic`), or null ([Sniff.image]). */
@JsExport
fun sniffImage(head: Uint8Array): String? = Sniff.image(bytes(head))?.mime

/** The audio or video container [head] is, or null ([Sniff.stream]). */
@JsExport
fun sniffStream(head: Uint8Array): StreamShape? {
    val s = Sniff.stream(bytes(head)) ?: return null
    val o: dynamic = js("({})")
    o.element = if (s.video) "video" else "audio"
    o.type = s.mime
    return o.unsafeCast<StreamShape>()
}

/** Whether [head] is a PDF ([Sniff.pdf]). */
@JsExport
fun sniffPdf(head: Uint8Array): Boolean = Sniff.pdf(bytes(head))

/** The encoding [head] is valid text in (`utf-8`, `utf-16le`, `utf-16be`), or null; [complete]: [head] is the whole file
 *  ([Sniff.textEncoding]). */
@JsExport
fun sniffText(head: Uint8Array, complete: Boolean): String? = Sniff.textEncoding(bytes(head), complete)

/** Whether [head] is an SVG document by its markup ([Sniff.svg]); a viewer still needs the claim to agree ([claimsSvg]). */
@JsExport
fun sniffSvg(head: Uint8Array): Boolean = Sniff.svg(bytes(head))

/** Whether the claimed [type] or [name] says text ([Sniff.claimsText]). */
@JsExport
fun claimsText(type: String?, name: String?): Boolean = Sniff.claimsText(type, name)

/** Whether the claimed [type] or [name] says SVG ([Sniff.claimsSvg]). */
@JsExport
fun claimsSvg(type: String?, name: String?): Boolean = Sniff.claimsSvg(type, name)

/** The lower-cased extension of [name], or "" ([Sniff.extensionOf]). */
@JsExport
fun extensionOf(name: String?): String = Sniff.extensionOf(name)

/**
 * The name a blob is saved under ([DownloadName]): [objectName], else [sealedName], else [fallback], with the extension the
 * sealed [type] names where it has none; null where both names are blank and no [fallback] is given — the caller then
 * computes its fallback and asks again, so a fallback is made only where it is needed.
 */
@JsExport
fun downloadName(objectName: String?, sealedName: String?, type: String?, fallback: String?): String? {
    val name = DownloadName.named(objectName, sealedName) ?: fallback ?: return null
    return DownloadName.withExtension(name, type)
}

/** The safe name of [name] in a directory whose folded names so far are [taken]; the result is added to [taken]
 *  ([ArchiveName.of]). */
@JsExport
fun archiveName(name: String, taken: TakenNames): String = ArchiveName.of(
    name,
    object : ArchiveName.Taken {
        override fun has(folded: String) = taken.has(folded)
        override fun add(folded: String) = taken.add(folded)
    },
)

/** How two names of one directory compare: NFC, ignoring case ([ArchiveName.fold]). */
@JsExport
fun foldArchiveName(name: String): String = ArchiveName.fold(name)

/** Whether [segment] is a segment the rules produce ([ArchiveName.isName]). */
@JsExport
fun isArchiveName(segment: String): Boolean = ArchiveName.isName(segment)

/** Whether [path] is a relative path of safe segments outside the manifest's directory ([ArchiveName.isPath]). */
@JsExport
fun isArchivePath(path: String): Boolean = ArchiveName.isPath(path)

/** The top-level directory that holds an archive's manifest ([ArchiveName.MANIFEST_DIR]). */
@JsExport
fun archiveManifestDir(): String = ArchiveName.MANIFEST_DIR

/** The extension a sealed [type] names exactly as sealed, or null where the table names none ([DownloadName.extensionOf]). */
@JsExport
fun typeExtension(type: String?): String? = DownloadName.extensionOf(type)
