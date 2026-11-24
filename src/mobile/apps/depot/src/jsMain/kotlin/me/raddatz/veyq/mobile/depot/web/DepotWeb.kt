@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.depot.web

import me.raddatz.veyq.mobile.depot.FileView
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.depot.SvgView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.collections.JsReadonlyArray

// Depot's façade for the web (ADR 0155 decisions 4, 5 and 7.3; `@veyq/core-js/depot`), the code of Depot's own module file
// (`kotlin_veyq_mobile_apps_depot.mjs`), which imports the platform's: what an opened file is (`FileView.decide`), the plan
// of a folder upload (`FolderUpload`'s names and tree) and an SVG's raster and zoom steps (`SvgView`) behind plain values
// (`Shapes.kt`), so the web decides them as the apps do and keeps what a browser does — reading the chunks, decoding a
// text, painting a canvas, creating the folders and queueing the files. Nothing here reaches kotlinx.serialization,
// coroutines, Ktor or a `NotOnTheWeb` refusal (the package's slice check), and no `Long` crosses.

private fun bytes(head: Uint8Array): ByteArray = Int8Array(head.buffer, head.byteOffset, head.length).unsafeCast<ByteArray>()

/** A fresh plain object, filled field by field (no `Pair` per field: the plan makes one per file). */
private fun plain(): dynamic = js("({})")

/**
 * What a file of [chunkCount] frames and [total] plaintext bytes is, its first frame [head] opened, the blob's sealed
 * [type] and the [name] its claims ([FileView.decide]; the web draws an SVG — as pixels, `svgRaster.ts` — so an SVG whose
 * markup and claim agree is an image here).
 */
@JsExport
fun depotFileView(head: Uint8Array, chunkCount: Int, total: Double, type: String?, name: String?): FileViewShape {
    val a = FileView.answer(bytes(head), chunkCount, total.toLong(), type, name, svg = true)
    val o = plain()
    o.kind = a.kind
    o.type = a.type
    o.element = a.element
    o.encoding = a.encoding
    o.frames = a.frames
    o.truncated = a.truncated
    o.limit = a.limit.toDouble()
    return o.unsafeCast<FileViewShape>()
}

/** The caps a file's page reads within ([FileView.IMAGE_CAP], [FileView.TEXT_CAP], [FileView.PDF_CAP]). */
@JsExport
fun depotFileViewCaps(): FileViewCapsShape {
    val o = plain()
    o.image = FileView.IMAGE_CAP.toDouble()
    o.text = FileView.TEXT_CAP.toDouble()
    o.pdf = FileView.PDF_CAP.toDouble()
    return o.unsafeCast<FileViewCapsShape>()
}

/** The top folder's further names, numbered past the level's siblings and the names handed out. Opaque: passed back. */
@JsExport
class FolderRootNames internal constructor(internal val namer: FolderUpload.Namer, internal val name: String)

/** The top folder's next name (`FolderUpload.made`'s numbering), for a `slug_taken` the plan could not foresee. */
@JsExport
fun depotFolderRootNext(names: FolderRootNames): String = names.namer.next("FOLDER", names.name)

/**
 * The plan of a directory named [rootName] whose files are [entries], uploaded under a level holding [siblings]
 * ([FolderUpload]'s plan): the tree of safe names, each kind's slug ([slugFor], the web's `registry.tokenInputFor`) told
 * apart per directory, the top numbered among the siblings.
 */
@JsExport
fun depotFolderPlan(
    rootName: String,
    entries: JsReadonlyArray<FolderEntryShape>,
    siblings: JsReadonlyArray<SiblingShape>,
    slugFor: (kind: String, name: String) -> String,
): FolderPlanShape {
    val all = entries.unsafeCast<Array<FolderEntryShape>>()
    val tokenInput: (String, String) -> String? = slugFor
    val planned = FolderUpload.planned(rootName, all.indices.toList(), { all[it].path }, tokenInput)
    val roots = FolderUpload.Namer(tokenInput, siblings.unsafeCast<Array<SiblingShape>>().map { slugFor(it.kind, it.name) })
    fun folder(p: FolderUpload.Planned<Int>): dynamic {
        val o = plain()
        o.name = p.name
        o.folders = p.dirs.map { folder(it) }.toTypedArray()
        o.files = p.files.map { (n, i) ->
            val f = plain()
            f.name = n
            f.entry = i
            f
        }.toTypedArray()
        return o
    }
    var bytes = 0.0
    for (i in planned.placed) bytes += all[i].size
    val names = FolderRootNames(roots, planned.root.name)
    val root = folder(planned.root)
    root.name = depotFolderRootNext(names)
    val o = plain()
    o.root = root
    o.files = planned.placed.size
    o.bytes = bytes
    o.rootNames = names
    return o.unsafeCast<FolderPlanShape>()
}

/** The canvas for a region [width] × [height] CSS px at [deviceRatio] device px per CSS px, within the caps ([SvgView.plan]). */
@JsExport
fun depotSvgRaster(width: Double, height: Double, deviceRatio: Double): SvgRasterShape {
    val r = SvgView.plan(width, height, deviceRatio)
    val o = plain()
    o.width = r.width
    o.height = r.height
    o.ratio = r.ratio
    return o.unsafeCast<SvgRasterShape>()
}

/** The most pixels one painted canvas holds ([SvgView.MAX_PIXELS]). */
@JsExport
fun depotSvgMaxPixels(): Int = SvgView.MAX_PIXELS

/** The longest side, in pixels, of one painted canvas ([SvgView.MAX_EDGE]). */
@JsExport
fun depotSvgMaxEdge(): Int = SvgView.MAX_EDGE

/** The zoom steps, ×1 the picture fitted ([SvgView.ZOOM_STEPS]). */
@JsExport
fun depotSvgZoomSteps(): JsReadonlyArray<Double> = SvgView.ZOOM_STEPS.toTypedArray().unsafeCast<JsReadonlyArray<Double>>()

/** The next step above [zoom], or the last ([SvgView.zoomIn]). */
@JsExport
fun depotSvgZoomIn(zoom: Double): Double = SvgView.zoomIn(zoom)

/** The next step below [zoom], or ×1 ([SvgView.zoomOut]). */
@JsExport
fun depotSvgZoomOut(zoom: Double): Double = SvgView.zoomOut(zoom)
