// The device floor for node keys (ADR 0109 point 6, layer 2; ADR 0024 Decision 6): a member's device seals nothing under
// a node's key whose node epoch is below the highest epoch it has held for the scope. The scope's opened `EpochKeys`
// already stand at or above that floor (`features/spaces/scopes.ts` `toOpenedScope` refuses a grant below it), so a
// node whose epoch is below `epochs.current` carries a key minted before the last rotation this device saw: an operator
// that served it the node's old wrap — or a re-key that has not reached it — would have the write sealed under a key the
// excluded party holds. Every member write that seals under a node's key derives that key through [nodeKeyForWrite];
// the server's two refusals (`stale_epoch`, `node_rekey_pending`) are layer 1, this is what holds when it lies.
import type { EpochKeys } from '../../../features/spaces/epochs'
import { nodeContentKey, type TreeNode } from '../tree'

/** A write meant to seal under a node whose key is from before the last rotation this device holds. */
export class NodeKeyBelowFloorError extends Error {
  constructor(readonly nodeId: string, readonly nodeEpoch: number, readonly floor: number) {
    super(`node_key_below_floor: ${nodeId} at ${nodeEpoch} < ${floor}`)
    this.name = 'NodeKeyBelowFloorError'
  }
}

/** Throws [NodeKeyBelowFloorError] when [node]'s key was minted below the device's floor for the scope ([epochs]). */
export function assertNodeKeyAtFloor(node: { id: string; epoch: number }, epochs: EpochKeys): void {
  if (node.epoch < epochs.current) throw new NodeKeyBelowFloorError(node.id, node.epoch, epochs.current)
}

/**
 * The content key of [nodeId] for a WRITE that seals under it, with the epoch the write states (`nodeEpoch` /
 * `parentEpoch`): the key from the tree, refused below the floor. Null when the node is not in the tree or its chain does
 * not open — as [nodeContentKey].
 */
export function nodeKeyForWrite(
  scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string,
): { key: Uint8Array; epoch: number } | null {
  const node = tree.find((n) => n.id === nodeId)
  if (!node) return null
  assertNodeKeyAtFloor(node, epochs)
  const key = nodeContentKey(scopeId, epochs, tree, nodeId)
  return key ? { key, epoch: node.epoch } : null
}
