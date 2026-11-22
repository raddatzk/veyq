@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.tree.web

import kotlin.js.collections.JsReadonlyArray

// The plain values the tree's façade takes and returns (ADR 0155 decision 5): each an external interface, so its `.d.ts`
// names every field with its type and the web passes its own objects (a `TreeNode` is an `EntryShape`, a `MovedShape` and
// a `SiblingShape` as it is) and reads plain ones back — no Kotlin class crosses but the two opaque handles (`TreeWeb.kt`).
// Answers name nodes by id; the web maps them back to its own nodes. No key, sealed byte or slug token is among them.

/** A kind's facts (the core's `KindFacts.Kind`): its route slug (null: none), its folding (`NAME`, `FILE_NAME`), its placement. */
@JsExport
external interface KindShape {
    val kind: String
    val route: String?
    val folding: String
    val root: Boolean
    val parents: JsReadonlyArray<String>
}

/** A node of the tree a session reads (the core's `TreeFacts.Node`): `createdAt` in epoch milliseconds, 0 or null where none
 *  is served; `identity` a drop box guest's identity folder. */
@JsExport
external interface TreeNodeShape {
    val id: String
    val parentId: String?
    val kind: String
    val name: String
    val createdAt: Double?
    val identity: Boolean
}

/** A node as a level's order and numbering read it. */
@JsExport
external interface EntryShape {
    val id: String
    val name: String
    val createdAt: Double?
}

/** What the creation order reads. */
@JsExport
external interface CreationShape {
    val id: String
    val createdAt: Double?
}

/** A moved (or copied) node: its name is read by `planMove` only. */
@JsExport
external interface MovedShape {
    val id: String
    val kind: String
    val parentId: String?
    val name: String?
}

/** Why several nodes cannot go to a place, and the one that blocks it (null: all of them are there already). */
@JsExport
external interface BlockOfShape {
    val block: String
    val node: String?
}

/** One node of a selection left out before anything is sent: `current` (already there) or `nameTaken` by `sibling`. */
@JsExport
external interface LeftOutShape {
    val node: String
    val reason: String
    val sibling: String?
}

/** A selection's move: what is sent, in order, and what is left out. */
@JsExport
external interface MovePlanShape {
    val send: JsReadonlyArray<String>
    val leftOut: JsReadonlyArray<LeftOutShape>
}

/** A place the move's browser lists: `blocked` (`self`, `inside`) says it cannot be opened. */
@JsExport
external interface PlaceShape {
    val node: String
    val blocked: String?
}
