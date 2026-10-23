// A member's node-key write helpers over the ACTIVE scope (ADR 0109 point 6): the device floor checked against the opened
// scope's epochs (`rekey/floor.ts`, layer 2), and the re-read of one node after an epoch refusal — the active scope's tree
// is loaded again and the node's key and sealed meta read from it, so a write can be rebuilt under the key the re-key
// gave the node.
import { activeEpochKeys, activeSpace } from '../../app/activeSpace'
import { nodeMetaState } from './nodeMeta'
import type { NodeMetaReread, WriteNodeMetaOptions } from './nodeMetaWrite'
import { assertNodeKeyAtFloor, nodeKeyForWrite } from './rekey/floor'
import { loadTree, treeStore } from './treeStore'
import type { NodeParent } from './tree'

/** The node is not in the active scope's tree any more, or its key chain does not open: nothing to rebuild under. */
export class NodeRereadError extends Error {
  constructor(readonly nodeId: string) {
    super(`node_reread_failed: ${nodeId}`)
    this.name = 'NodeRereadError'
  }
}

/** The device floor for a member write under `node`'s key in scope `scopeId` (`assertNodeKeyAtFloor` against the active
 *  scope's opened epochs). A scope that is not the active one has no epochs here and is the caller's to check. */
export function assertActiveScopeFloor(scopeId: string, node: { id: string; epoch: number }): void {
  const epochs = activeSpace.scopeId === scopeId ? activeEpochKeys() : null
  if (epochs) assertNodeKeyAtFloor(node, epochs)
}

/** Re-read `nodeId` of the ACTIVE scope `scopeId` (the member's tree, `treeStore`) for {@link writeNodeMeta}'s
 *  `reread`, its key derived through the floor (`nodeKeyForWrite`). Throws {@link NodeRereadError} when the scope is not
 *  the active one or the node does not open, and `NodeKeyBelowFloorError` when it is still not re-keyed. */
export function rereadMemberNode(scopeId: string, nodeId: string): NodeMetaReread {
  return async () => {
    if (activeSpace.scopeId !== scopeId) throw new NodeRereadError(nodeId)
    await loadTree()
    const epochs = activeEpochKeys()
    const node = treeStore.nodes.find((n) => n.id === nodeId)
    const at = epochs && node ? nodeKeyForWrite(scopeId, epochs, treeStore.nodes, nodeId) : null
    if (!node || !at) throw new NodeRereadError(nodeId)
    return { nodeKey: at.key, state: nodeMetaState(node) }
  }
}

/** What a member's meta write under `nodeId` of the active scope passes {@link writeNodeMeta}: the floor before each
 *  attempt and the re-read after an epoch refusal. Only for a writer whose edit carries nothing sealed under the old key. */
export function memberNodeMetaOptions(scopeId: string, nodeId: string): WriteNodeMetaOptions {
  return { atFloor: (epoch) => assertActiveScopeFloor(scopeId, { id: nodeId, epoch }), reread: rereadMemberNode(scopeId, nodeId) }
}

/** Re-read the parent `parentId` of a create in the ACTIVE scope `scopeId` (`createContentNode`'s `reread`): its key and
 *  epoch as the reloaded tree holds them, through the floor (`nodeKeyForWrite`). */
export function rereadMemberParent(scopeId: string, parentId: string): () => Promise<NodeParent> {
  return async () => {
    if (activeSpace.scopeId !== scopeId) throw new NodeRereadError(parentId)
    await loadTree()
    const epochs = activeEpochKeys()
    const at = epochs ? nodeKeyForWrite(scopeId, epochs, treeStore.nodes, parentId) : null
    if (!at) throw new NodeRereadError(parentId)
    return { id: parentId, key: at.key, epoch: at.epoch }
  }
}

/** A member's create under `parent` in the active scope: re-read the parent after an epoch refusal; none at the root. */
export function memberCreateOptions(scopeId: string, parent: NodeParent): { reread?: () => Promise<NodeParent> } {
  return parent ? { reread: rereadMemberParent(scopeId, parent.id) } : {}
}
