@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.depot.web

import kotlin.js.collections.JsReadonlyArray

// The plain values Depot's façade takes and returns (ADR 0155 decision 5): external interfaces, so the `.d.mts` names each
// field with its type (never `any`) and the web passes and reads its own objects — no Kotlin class crosses but the one
// opaque handle (`FolderRootNames`). No `Long` crosses: a size is a `number`.

/** What an opened file is (`FileView.decide`): `kind` one of `image`, `stream`, `pdf`, `text`, `too-large`, `empty`,
 *  `unsupported`; an image's and a stream's sniffed `type`, a stream's `element` (`video`, `audio`), a text's `encoding`
 *  (`utf-8`, `utf-16le`, `utf-16be`), the `frames` to read (all, or a text's first MiB) and whether it is `truncated`; a
 *  `too-large` one's `limit` in bytes. Null or 0 where the kind has none. */
@JsExport
external interface FileViewShape {
    val kind: String
    val type: String?
    val element: String?
    val encoding: String?
    val frames: Int
    val truncated: Boolean
    val limit: Double
}

/** The caps a file's page reads within (`FileView`): an image's and a PDF's whole bytes, a text's shown bytes. */
@JsExport
external interface FileViewCapsShape {
    val image: Double
    val text: Double
    val pdf: Double
}

/** One file of a picked directory: its path below the directory (`/`-separated) and its size in bytes. */
@JsExport
external interface FolderEntryShape {
    val path: String
    val size: Double
}

/** A child the level holds already: its kind and its name. */
@JsExport
external interface SiblingShape {
    val kind: String
    val name: String
}

/** A file of the plan: its safe `name` and the `entry` it is, an index into the entries planned. */
@JsExport
external interface PlannedFileShape {
    val name: String
    val entry: Int
}

/** A directory of the plan: its safe name, its sub-directories and its files. */
@JsExport
external interface PlannedFolderShape {
    val name: String
    val folders: JsReadonlyArray<PlannedFolderShape>
    val files: JsReadonlyArray<PlannedFileShape>
}

/** What a folder upload makes: the tree (its top already numbered among the level's siblings), how many files and bytes it
 *  holds, and the top's further names (`depotFolderRootNext`) for a sibling only the server knew. */
@JsExport
external interface FolderPlanShape {
    val root: PlannedFolderShape
    val files: Int
    val bytes: Double
    val rootNames: FolderRootNames
}

/** A raster of an SVG: its pixel size and the pixels per laid-out unit it was planned at (`SvgView.plan`). */
@JsExport
external interface SvgRasterShape {
    val width: Int
    val height: Int
    val ratio: Double
}
