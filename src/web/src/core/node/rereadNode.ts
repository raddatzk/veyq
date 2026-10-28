// A member's node-key write helpers over the ACTIVE scope (ADR 0109 point 6): the device floor checked against the opened
// scope's epochs (`rekey/floor.ts`, layer 2), and the re-read of one node after an epoch refusal — the active scope's tree
// is loaded again and the node's key and sealed meta read from it, so a write can be rebuilt under the key the re-key
// gave the node.
import { activeEpochKeys, activeSpace } from '../../app/activeSpace'
import { nodeMetaState } from './nodeMeta'
import type { NodeMetaReread, WriteNodeMetaOptions } from './nodeMetaWrite'
import { assertNodeKeyAtFloor, nodeKeyForWrite } from './rekey/floor'
import { loadTree, treeStore } from './treeStore'
import { pullRekeyPath } from './rekey/rekeyScope'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import type { CreateNodeOptions, NodeParent, TreeNode } from './tree'
import type { EpochKeys } from '../../features/spaces/epochs'

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
 *  attempt, the pull of the node's path on the first epoch refusal (ADR 0111 decision 11) and the re-read after it.
 *  Only for a writer whose edit carries nothing sealed under the old key. */
export function memberNodeMetaOptions(scopeId: string, nodeId: string): WriteNodeMetaOptions {
  return {
    atFloor: (epoch) => assertActiveScopeFloor(scopeId, { id: nodeId, epoch }),
    reread: rereadMemberNode(scopeId, nodeId),
    pull: pullRekeyPath(scopeId, [nodeId]),
  }
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

/** Re-read `nodeId`'s key and epoch in the ACTIVE scope `scopeId` for a write that seals under it after an epoch refusal
 *  (an upload into it, an edit of what it holds): {@link rereadMemberParent} by another name. */
export function rereadMemberKey(scopeId: string, nodeId: string): () => Promise<{ key: Uint8Array; epoch: number }> {
  const reread = rereadMemberParent(scopeId, nodeId)
  return async () => {
    const at = (await reread())!
    return { key: at.key, epoch: at.epoch }
  }
}

/**
 * A member's write sealed under `nodeId`'s key in the ACTIVE scope `scopeId`, built by `write` from `at` (the key and
 * epoch it read): the device floor before each attempt, and on an epoch refusal the node's path pulled forward on this
 * device (ADR 0111 decision 11) — or waited for, when it cannot — the key re-read and the write rebuilt under it
 * (`writeAtNodeEpoch`). Only for a write whose payload is sealed afresh each time, never one carrying a ciphertext read
 * under the old key: a rebuild gets `node`, the node as the re-read tree holds it (its meta re-sealed by the re-key, so
 * what the write reads from it — a cover's or a banner's wraps — opens under the new key), null on the first attempt,
 * where the caller's own read stands. A flow that uploads under the key (a cover, a banner) uploads again in the rebuild.
 */
export function writeUnderMemberNode<T>(
  scopeId: string, nodeId: string, at: { key: Uint8Array; epoch: number },
  write: (at: { key: Uint8Array; epoch: number }, node: TreeNode | null) => Promise<T>,
): Promise<T> {
  const reread = rereadMemberKey(scopeId, nodeId)
  return writeAtNodeEpoch(async (again) => {
    const now = again ? await reread() : at
    assertActiveScopeFloor(scopeId, { id: nodeId, epoch: now.epoch })
    return write(now, again ? treeStore.nodes.find((n) => n.id === nodeId) ?? null : null)
  }, { pull: pullRekeyPath(scopeId, [nodeId]) })
}

/** The scope's epochs as this device holds them now, when `scopeId` is the active scope; null otherwise. What a long run
 *  states once a pull re-opened the scope after a rotation it raced (`pullRekeyPath`): the epochs it was started with
 *  are then the old ones, and zeroed (`refreshActiveScope`). */
export function heldScopeEpochs(scopeId: string): EpochKeys | null {
  return activeSpace.scopeId === scopeId ? activeEpochKeys() : null
}

/** A member's create under `parent` in the active scope: pull the parent's path forward on an epoch refusal (ADR 0111
 *  decision 11) and re-read the parent, the new node minted at the epoch the device holds then. At the root, where a
 *  create states no parent epoch, only that epoch can move: a `stale_epoch` has the pull of an empty path re-open the
 *  scope this device held behind, and the create runs again at its current epoch (inbox #670). */
export function memberCreateOptions(scopeId: string, parent: NodeParent): CreateNodeOptions {
  const epochs = () => heldScopeEpochs(scopeId)
  return parent
    ? { reread: rereadMemberParent(scopeId, parent.id), pull: pullRekeyPath(scopeId, [parent.id]), epochs }
    : { reread: async () => null, pull: pullRekeyPath(scopeId, []), epochs }
}

/** A node's key and the epoch it was minted at, as a write seals under them and states them. */
export type NodeKeyAt = { key: Uint8Array; epoch: number }

/**
 * A node a member's run created and keeps writing into — an import's or a copy's Memory, Pot or file, a new Pot's first
 * records (inbox #670). A rotation that lands while the run is under way leaves the node at the epoch it was born at, so
 * each write goes through {@link writeUnderMemberNode} — the device floor, the pull of the node's path on an epoch refusal,
 * the re-read and the rebuild — and the writer keeps the key and epoch the last write landed under, so the run's next
 * write, and what it creates below the node, seal under the node's key now rather than the one the re-key replaced.
 * Only for writes that are sealed afresh on each attempt (an upload, an append), never one carrying a ciphertext read
 * under the old key. A node only an owner re-keys (below a drop box) is not this member's to pull: its writes wait.
 */
export type MemberNodeWriter = {
  readonly scopeId: string
  readonly nodeId: string
  /** The key and epoch the node's last write landed under — its birth's until a re-key moved them. */
  readonly at: NodeKeyAt
  /** Run `build` under the node's key as {@link writeUnderMemberNode} does; `node` is the re-read tree's row in a rebuild,
   *  null on the first attempt. */
  write<T>(build: (at: NodeKeyAt, node: TreeNode | null) => Promise<T>): Promise<T>
}

/** The {@link MemberNodeWriter} of `nodeId` in the active scope `scopeId`, starting from `at` (its key as created). */
export function memberNodeWriter(scopeId: string, nodeId: string, at: NodeKeyAt): MemberNodeWriter {
  let now = at
  return {
    scopeId,
    nodeId,
    get at() {
      return now
    },
    write: (build) => writeUnderMemberNode(scopeId, nodeId, now, async (attempt, node) => {
      const result = await build(attempt, node)
      now = attempt
      return result
    }),
  }
}
