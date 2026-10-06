package me.raddatz.veyq.core.content

/**
 * The subtree a principal-free node operation is confined to (ADR 0080 Decisions 6, 9, 10): the node [rootNodeId] and
 * everything below it. Passed to [NodesHandler]'s `…InScope` operations, it holds them to two rules, each decided by an
 * upward walk ([NodesStore.withinSubtree]) **under the scope lock** — the lock every tree writer takes first, so a
 * member's concurrent move of an ancestor out of the subtree has either committed before the walk (and is seen) or
 * waits until this write is done:
 *  - the node an update or a delete acts on lies strictly inside: [rootNodeId] itself is never moved, renamed or
 *    deleted through a boundary;
 *  - a create's parent and a placement's destination lie inside, [rootNodeId] included.
 *
 * No caller passes one yet: the member endpoints act on the whole scope. A share's guest operations will (sub-slice
 * 5a.3 of cr-node-key-hierarchy), with the share's root as [rootNodeId].
 */
data class SubtreeBoundary(val rootNodeId: String)
