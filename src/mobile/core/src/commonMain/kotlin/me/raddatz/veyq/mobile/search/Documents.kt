package me.raddatz.veyq.mobile.search

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.session.OpenNode

/**
 * One space's documents (ADR 0125 decisions 2 and 6; the web's `core/search/documents.ts`, ported for ADR 0146 decisions 5
 * and 6): every node of the shell's opened tree as a document — its name, the names and ids above it, the facets its kind
 * adds from its opened meta — and an application's objects placed under their node's path.
 *
 * **No key is held or zeroed here:** a node's meta is opened by the caller ([NodeDocuments.openMeta], core's own node-meta
 * opener under the shell's key, read in place) and only the facets are kept. **Rebuilt per seal:** a node whose sealed meta
 * is the one its facets were read from keeps them, so a tree read anew opens only what moved.
 */
internal object Documents {
    /** Facets read from one seal: the next walk compares it. */
    class Facets(val seal: String?, val facets: SearchFacets)

    /** Where a document lies: the names and the ids of the nodes above it, outermost first. */
    class NodePlace(val names: List<String>, val ids: List<String>)

    /** The nodes above [node], outermost first (a cycle a tampered tree could hold ends the walk). */
    fun placeOf(node: OpenNode, byId: Map<String, OpenNode>): NodePlace {
        val names = ArrayList<String>()
        val ids = ArrayList<String>()
        val seen = hashSetOf(node.id)
        var cur = node.parentId?.let { byId[it] }
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name)
            ids.add(0, cur.id)
            cur = cur.parentId?.let { byId[it] }
        }
        return NodePlace(names, ids)
    }

    /** Where an object inside [node] lies (a photo of a Memory): the nodes above it and the node itself. */
    fun placeWithin(node: OpenNode, byId: Map<String, OpenNode>): NodePlace {
        val above = placeOf(node, byId)
        return NodePlace(above.names + node.name, above.ids + node.id)
    }

    /**
     * The documents of every node of [nodes] — one space's tree as the shell opened it —, its facets by [kinds] from the meta
     * [openMeta] opens (null: none, the node is found by its name alone), kept from [facets] where the seal did not move;
     * [facets] is filled with what this walk read. Cancellable between two nodes.
     */
    suspend fun nodeDocuments(
        scopeId: String, nodes: List<OpenNode>, kinds: SearchKinds, nowMs: Long,
        openMeta: (OpenNode) -> JsonObject?, facets: MutableMap<String, Facets>,
    ): List<SearchDocument> {
        val byId = nodes.associateBy { it.id }
        val out = ArrayList<SearchDocument>(nodes.size)
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            val seal = node.wrappedMeta
            val held = facets[node.id]?.takeIf { it.seal == seal }
            val f = held?.facets ?: run {
                val hook = kinds.kind(node.kind)?.facets
                val read = if (hook == null || seal == null) SearchFacets.NONE
                else runCatching { openMeta(node)?.let { hook(it, nowMs) } }.getOrNull() ?: SearchFacets.NONE
                facets[node.id] = Facets(seal, read)
                read
            }
            val above = placeOf(node, byId)
            out += SearchDocument.of(
                scopeId = scopeId, kind = node.kind, id = node.id, name = node.name, nodeId = node.id,
                revision = "${node.placementRevision}:${node.metaRevision}", path = above.names, pathIds = above.ids, facets = f,
            )
        }
        return out
    }

    /** An application's [objects] of one space as documents, each under its node's path; one whose node is not in the tree is left out. */
    fun scopeDocuments(scopeId: String, objects: List<ScopeDocument>, byId: Map<String, OpenNode>): List<SearchDocument> {
        val places = HashMap<String, NodePlace>()
        return objects.mapNotNull { o ->
            val node = byId[o.nodeId] ?: return@mapNotNull null
            val at = places.getOrPut(node.id) { placeWithin(node, byId) }
            SearchDocument.of(
                scopeId = scopeId, kind = o.kind, id = o.id, name = o.name, nodeId = o.nodeId, revision = o.revision,
                path = at.names, pathIds = at.ids, facets = o.facets, icon = o.icon,
            )
        }
    }
}
