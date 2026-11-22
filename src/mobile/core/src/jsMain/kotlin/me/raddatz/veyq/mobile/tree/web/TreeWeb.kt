@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.tree.web

import me.raddatz.veyq.mobile.tree.DisplayNames
import me.raddatz.veyq.mobile.tree.KindFacts
import me.raddatz.veyq.mobile.tree.LevelOrder
import me.raddatz.veyq.mobile.tree.MovePlan
import me.raddatz.veyq.mobile.tree.SiblingNames
import me.raddatz.veyq.mobile.tree.TreeFacts
import kotlin.js.collections.JsReadonlyArray
import kotlin.js.collections.JsReadonlyMap

// The node tree's façade for the web (ADR 0155 decisions 4 and 5; `@veyq/core-js/tree`): the platform's `tree` rules — a
// level's order, the numbering of equal names, a name's sibling collision, the move's plan — behind plain values
// (`Shapes.kt`), so the web orders, numbers, refuses and plans as the apps do and keeps only what a browser does: its
// views, its router's slug paths, its network. What it exports is what the web's callers call. Nothing here reaches
// kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the package's slice check reads the shipped bundle's
// sources), and no `Long` runs on its path (an instant is a `Double`).

/** The kinds' facts the rules read (the core's [KindFacts]), built once from the web's registry. Opaque: held `markRaw`. */
@JsExport
class KindFactsHandle internal constructor(internal val facts: KindFacts)

/** One generation of the tree a session reads (the core's [TreeFacts]), rebuilt when the session replaces its node list.
 *  Opaque: held `markRaw`, passed back, never read. */
@JsExport
class TreeFactsHandle internal constructor(internal val facts: TreeFacts)

@JsExport
fun kindFacts(kinds: JsReadonlyArray<KindShape>): KindFactsHandle {
    val a = kinds.unsafeCast<Array<KindShape>>()
    val out = ArrayList<KindFacts.Kind>(a.size)
    for (k in a) out.add(KindFacts.Kind(k.kind, k.route, k.folding, k.root, k.parents.unsafeCast<Array<String>>().asList()))
    return KindFactsHandle(KindFacts(out))
}

@JsExport
fun treeFacts(nodes: JsReadonlyArray<TreeNodeShape>): TreeFactsHandle {
    val a = nodes.unsafeCast<Array<TreeNodeShape>>()
    val out = ArrayList<TreeFacts.Node>(a.size)
    for (n in a) out.add(TreeFacts.Node(n.id, n.parentId, n.kind, n.name, n.createdAt, n.identity))
    return TreeFactsHandle(TreeFacts(out))
}

/** The ids of a level in the order it is drawn: [folders] by name, then [others] by name, every kind mixed (`LevelOrder`). */
@JsExport
fun orderLevel(folders: JsReadonlyArray<EntryShape>, others: JsReadonlyArray<EntryShape>): JsReadonlyArray<String> {
    val out = array<String>()
    for (e in LevelOrder.order(list(folders), list(others), { it.id }, { it.name }, { it.createdAt })) out.push(e.id)
    return out
}

/** The label of each of [siblings] by id (`DisplayNames`): in creation order, or in id order where [byId] (a guest's). */
@JsExport
fun displayNames(siblings: JsReadonlyArray<EntryShape>, byId: Boolean): JsReadonlyMap<String, String> {
    val labels: dynamic = js("new Map()")
    DisplayNames.each(list(siblings), { it.id }, { it.name }, { it.createdAt }, !byId) { id, label -> labels.set(id, label) }
    return labels.unsafeCast<JsReadonlyMap<String, String>>()
}

/** Sibling order: the creation instant, then the id in UTF-16 code units. */
@JsExport
fun compareByCreation(a: CreationShape, b: CreationShape): Int = DisplayNames.compareByCreation(a.createdAt, a.id, b.createdAt, b.id)

/** The id of the first child of [parentId] whose token input [name] would take for a node of [kind], or null; [self] never. */
@JsExport
fun collidingSibling(kinds: KindFactsHandle, tree: TreeFactsHandle, parentId: String?, kind: String, name: String, self: String?): String? =
    SiblingNames.colliding(kinds.facts, tree.facts, parentId, kind, name, self)?.id

/** Why [nodes] cannot go to [at] (null: the scope root) — `self`, `inside`, `current`, `identityFolder`, `kind` — or null. */
@JsExport
fun moveHereBlock(kinds: KindFactsHandle, tree: TreeFactsHandle, nodes: JsReadonlyArray<MovedShape>, guest: Boolean, at: String?, copy: Boolean): String? =
    MovePlan.hereBlock(placement(kinds), tree.facts, moved(nodes), guest, at, copy)

/** Why several [nodes] cannot go to [at], and the id of the one that blocks it; null where the place takes them. */
@JsExport
fun moveHereBlockOf(kinds: KindFactsHandle, tree: TreeFactsHandle, nodes: JsReadonlyArray<MovedShape>, guest: Boolean, at: String?, copy: Boolean): BlockOfShape? {
    val r = MovePlan.hereBlockOf(placement(kinds), tree.facts, moved(nodes), guest, at, copy) ?: return null
    val o: dynamic = js("({})")
    o.block = r.block
    o.node = r.node?.id
    return o.unsafeCast<BlockOfShape>()
}

/** A selection's move to [to]: the ids sent in order, and those left out with why. */
@JsExport
fun planMove(kinds: KindFactsHandle, tree: TreeFactsHandle, nodes: JsReadonlyArray<MovedShape>, to: String?): MovePlanShape {
    val plan = MovePlan.plan(kinds.facts, tree.facts, moved(nodes), to)
    val send = array<String>()
    for (m in plan.send) send.push(m.id)
    val leftOut = array<LeftOutShape>()
    for (l in plan.leftOut) {
        val o: dynamic = js("({})")
        o.node = l.node.id
        o.reason = l.reason
        o.sibling = l.sibling?.id
        leftOut.push(o)
    }
    val o: dynamic = js("({})")
    o.send = send
    o.leftOut = leftOut
    return o.unsafeCast<MovePlanShape>()
}

/** Where the move's browser opens; [top] is the top of what the session reads (null: a member's scope root). */
@JsExport
fun moveStart(tree: TreeFactsHandle, top: String?, nodes: JsReadonlyArray<MovedShape>): String? = MovePlan.start(tree.facts, top, moved(nodes))

/** The trail from [top] down to [at], both included. */
@JsExport
fun moveTrail(tree: TreeFactsHandle, top: String?, at: String?): JsReadonlyArray<String?> {
    val out = array<String?>()
    for (id in MovePlan.trail(tree.facts, top, at)) out.push(id)
    return out
}

/** The places the move's browser lists at [at], in the tree's order. */
@JsExport
fun movePlaces(kinds: KindFactsHandle, tree: TreeFactsHandle, nodes: JsReadonlyArray<MovedShape>, guest: Boolean, at: String?): JsReadonlyArray<PlaceShape> {
    val out = array<PlaceShape>()
    for (p in MovePlan.places(placement(kinds), tree.facts, moved(nodes), guest, at)) {
        val o: dynamic = js("({})")
        o.node = p.node.id
        o.blocked = p.blocked
        out.push(o)
    }
    return out
}

/** Whether moving node [id] ends a drop box guest's folder. */
@JsExport
fun endsIdentity(tree: TreeFactsHandle, id: String): Boolean = MovePlan.endsIdentity(tree.facts, id)

/** The kinds' placement as the plan asks it. */
private fun placement(kinds: KindFactsHandle): (String, String?) -> Boolean = { kind, parent -> kinds.facts.placementAllows(kind, parent) }

private fun moved(nodes: JsReadonlyArray<MovedShape>): List<TreeFacts.Node> {
    val a = nodes.unsafeCast<Array<MovedShape>>()
    val out = ArrayList<TreeFacts.Node>(a.size)
    for (n in a) out.add(TreeFacts.Node(n.id, n.parentId, n.kind, n.name ?: "", null, false))
    return out
}

/** A JavaScript array read as a list, not copied. */
private fun <T> list(a: JsReadonlyArray<T>): List<T> = a.unsafeCast<Array<T>>().asList()

/** An empty JavaScript array, pushed to and handed back as it is. */
private fun <T> array(): JsArray<T> = js("[]").unsafeCast<JsArray<T>>()

/** The engine's own array: [push] is its method, and it is the readonly array the shapes name. */
private external interface JsArray<T> : JsReadonlyArray<T> {
    fun push(e: T)
}
