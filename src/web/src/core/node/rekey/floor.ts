// The device floor for node keys (ADR 0109 point 6, layer 2; ADR 0024 Decision 6; ADR 0115 decision 6): a member's
// device seals nothing under a node's key that was minted under a scope epoch below the highest one it has held for the
// scope. The scope's opened `ScopeKey` already stands at or above that floor (`features/spaces/scopes.ts` `toOpenedScope`
// refuses a grant below it), so a node whose `scopeEpoch` is below `epochs.epoch` carries a key minted before the last
// rotation this device saw: an operator that served it the node's old wrap — or a re-key that has not reached it — would
// have the write sealed under a key the excluded party holds. Every member write that seals under a node's key derives
// that key through [nodeKeyForWrite]; the server's two refusals (`stale_epoch`, `node_rekey_pending`) are layer 1, this
// is what holds when it lies. The number is authentic because the node's seals bind it (ADR 0115 decision 6).
import type { ScopeKey } from '../../../features/spaces/epochs'
import { nodeContentKey, type TreeNode } from '../tree'

/** A write meant to seal under a node whose key is from before the last rotation this device holds. */
export class NodeKeyBelowFloorError extends Error {
  constructor(readonly nodeId: string, readonly nodeScopeEpoch: number, readonly floor: number) {
    super(`node_key_below_floor: ${nodeId} at ${nodeScopeEpoch} < ${floor}`)
    this.name = 'NodeKeyBelowFloorError'
  }
}

/** Throws [NodeKeyBelowFloorError] when [node]'s key was minted under a scope epoch below the device's floor for the
 *  scope ([epochs]). */
export function assertNodeKeyAtFloor(node: { id: string; scopeEpoch: number }, epochs: ScopeKey): void {
  if (node.scopeEpoch < epochs.epoch) throw new NodeKeyBelowFloorError(node.id, node.scopeEpoch, epochs.epoch)
}

/**
 * The content key of [nodeId] for a WRITE that seals under it, with the node's own epoch the write states (`nodeEpoch`
 * / `parentEpoch`, ADR 0114 decision 3) and the scope epoch its key was minted under: the key from the tree, refused
 * below the floor. Null when the node is not in the tree or its chain does not open — as [nodeContentKey].
 */
export function nodeKeyForWrite(
  scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string,
): { key: Uint8Array; epoch: number; scopeEpoch: number } | null {
  const node = tree.find((n) => n.id === nodeId)
  if (!node) return null
  assertNodeKeyAtFloor(node, epochs)
  const key = nodeContentKey(scopeId, epochs, tree, nodeId)
  return key ? { key, epoch: node.epoch, scopeEpoch: node.scopeEpoch } : null
}
