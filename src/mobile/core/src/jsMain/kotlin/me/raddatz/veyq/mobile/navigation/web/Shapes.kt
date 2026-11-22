@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.navigation.web

import kotlin.js.collections.JsReadonlyArray

// The plain values the plans' façade takes and returns (ADR 0155 decision 5): each an external interface, so its `.d.ts`
// names every field with its type and the web passes its own objects and reads plain ones back — no Kotlin class crosses.
// An entry is a `HeaderAction`'s serial name (`favorite`, `open-own-page`, `report-shared`), the id the web's actions carry.

/** What a node grants a header or a row (the core's `NodeHeader.Rights`): each flag the rights answer AND the page's door. */
@JsExport
external interface RightsShape {
    val favorite: Boolean
    val share: Boolean
    val rename: Boolean
    val move: Boolean
    val cover: Boolean
    val copy: Boolean
    val download: Boolean
    val report: Boolean
    val delete: Boolean
    val offline: Boolean
}

/** A header's plan (the core's `NodeHeader.Plan`): the entries that may be buttons, in order, then the menu's two groups. */
@JsExport
external interface HeaderPlanShape {
    val buttons: JsReadonlyArray<String>
    val menu: JsReadonlyArray<String>
    val last: JsReadonlyArray<String>
}

/** A row's menu (the core's `LevelRow.Plan`): its first group, then after a separator its [last]. */
@JsExport
external interface RowPlanShape {
    val menu: JsReadonlyArray<String>
    val last: JsReadonlyArray<String>
}

/** A kept link's rights (the core's `CoreRights.Bookmark`). */
@JsExport
external interface BookmarkRightsShape {
    val open: Boolean
    val rename: Boolean
    val move: Boolean
    val delete: Boolean
}
