@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.navigation.web

import me.raddatz.veyq.mobile.navigation.CoreRights
import me.raddatz.veyq.mobile.navigation.HeaderAction
import me.raddatz.veyq.mobile.navigation.LevelRow
import me.raddatz.veyq.mobile.navigation.NodeHeader
import kotlin.js.collections.JsArray
import kotlin.js.collections.JsReadonlyArray

// The plans' façade for the web (ADR 0154 decision 2, ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/navigation`): a node
// header's plan and a level row's menu (`NodeHeader.plan`, `LevelRow.plan`) and core's favourite, cover and bookmark rights
// (`CoreRights`) behind plain values (`Shapes.kt`), so the web offers, orders and groups a node's actions as the apps do and
// keeps only what a browser does: its labels, icons and handlers, the measured fit of a header's buttons, a row's inline
// buttons. Nothing here reaches kotlinx.serialization, coroutines, Ktor, a `NotOnTheWeb` refusal or the words catalog (the
// package's slice check reads the shipped bundle's sources). An entry crosses as its serial name; one the core does not know
// throws — a web entry of its own is the defect ADR 0154 decision 2 names.

/** A header's plan for [rights], the kind's [order], the page's [own] entries and, where a bar holds only so many, [maxButtons]
 *  (null: every entry that may be a button is one, and the web fits them itself). */
@JsExport
fun headerPlan(rights: RightsShape, order: JsReadonlyArray<String>, own: JsReadonlyArray<String>, maxButtons: Int?): HeaderPlanShape {
    val buttons = ArrayList<HeaderAction>()
    val menu = ArrayList<HeaderAction>()
    val last = ArrayList<HeaderAction>()
    NodeHeader.planInto(granted(rights), actions(order), maxButtons ?: Int.MAX_VALUE, actions(own), buttons, menu, last)
    val o: dynamic = js("({})")
    o.buttons = ids(buttons)
    o.menu = ids(menu)
    o.last = ids(last)
    return o.unsafeCast<HeaderPlanShape>()
}

/** A row's menu for [rights] and the kind's [own] entries. */
@JsExport
fun rowPlan(rights: RightsShape, own: JsReadonlyArray<String>): RowPlanShape {
    val menu = ArrayList<HeaderAction>()
    val last = ArrayList<HeaderAction>()
    LevelRow.planInto(granted(rights), actions(own), menu, last)
    val o: dynamic = js("({})")
    o.menu = ids(menu)
    o.last = ids(last)
    return o.unsafeCast<RowPlanShape>()
}

/** The heart ([CoreRights.favorite]). */
@JsExport
fun favoriteRight(member: Boolean, node: Boolean, known: Boolean): Boolean = CoreRights.favorite(member, node, known)

/** Setting a node's cover ([CoreRights.cover]). */
@JsExport
fun coverRight(
    member: Boolean, keys: Boolean, readonly: Boolean, node: Boolean, inIdentityFolder: Boolean, applicationOn: Boolean, takesCover: Boolean,
): Boolean = CoreRights.cover(member, keys, readonly, node, inIdentityFolder, applicationOn, takesCover)

/** A kept link's rights ([CoreRights.bookmark]); [rename], [move] and [delete] the node's own. */
@JsExport
fun bookmarkRights(member: Boolean, node: Boolean, keys: Boolean, rename: Boolean, move: Boolean, delete: Boolean): BookmarkRightsShape {
    val b = CoreRights.bookmark(member, node, keys, rename, move, delete)
    val o: dynamic = js("({})")
    o.open = b.open
    o.rename = b.rename
    o.move = b.move
    o.delete = b.delete
    return o.unsafeCast<BookmarkRightsShape>()
}

/** The entries [r] grants ([NodeHeader.granted]): each flag of the shape is named for its entry. */
private fun granted(r: RightsShape): List<HeaderAction> {
    val flags: dynamic = r
    val out = ArrayList<HeaderAction>(NodeHeader.RIGHTS.size)
    for (a in NodeHeader.RIGHTS) if (flags[ID_OF[a.ordinal]] == true) out.add(a)
    return out
}

/** Each entry's serial name by its ordinal: its constant's name in lower case, `_` as `-` (`PlanVectorsTest` holds the rule). */
private val ID_OF: Array<String> = Array(HeaderAction.entries.size) { HeaderAction.entries[it].name.lowercase().replace('_', '-') }

/** Each entry by its serial name: a plain object, no `HashMap` on the path. */
private val BY_ID: dynamic = byId()

private fun byId(): dynamic {
    val o: dynamic = js("({})")
    for (a in HeaderAction.entries) o[ID_OF[a.ordinal]] = a
    return o
}

private fun actions(a: JsReadonlyArray<String>): List<HeaderAction> {
    val ids = a.unsafeCast<Array<String>>()
    val out = ArrayList<HeaderAction>(ids.size)
    for (id in ids) {
        val known: dynamic = if (js("Object").prototype.hasOwnProperty.call(BY_ID, id).unsafeCast<Boolean>()) BY_ID[id] else null
        out.add(known?.unsafeCast<HeaderAction>() ?: throw IllegalArgumentException("not an entry of a node's plan: $id"))
    }
    return out
}

private fun ids(l: List<HeaderAction>): JsReadonlyArray<String> {
    val out = js("[]").unsafeCast<JsArray<String>>()
    for (a in l) out.asDynamic().push(ID_OF[a.ordinal])
    return out
}
