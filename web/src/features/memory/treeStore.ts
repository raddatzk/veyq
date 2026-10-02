// The active scope's decrypted tree, loaded once and shared by the browse + Memory views (cr-0030). Holds
// plaintext node names (decrypted on device) — never key material (K_SC lives in app/activeSpace). Mutations
// (create/rename/delete) call `loadTree()` to refresh.
import { reactive } from 'vue'
import { activeSpace, activeEpochKeys } from '../../app/activeSpace'
import { listTree, type TreeNode } from './tree'

export const treeStore = reactive({
  nodes: [] as TreeNode[],
  loaded: false,
  error: false,
})

/** (Re)load the active scope's tree. No-op if no space is open. */
export async function loadTree(): Promise<void> {
  const epochs = activeEpochKeys()
  if (!activeSpace.scopeId || !epochs) return
  try {
    treeStore.nodes = await listTree(activeSpace.scopeId, epochs)
    treeStore.loaded = true
    treeStore.error = false
  } catch {
    treeStore.error = true
  }
}

/**
 * Patch a node's sealed `wrappedMeta` and the `metaVersion` it is at, in place, after a meta write or a conflict
 * that handed back the current value (cr-0067, ADR 0077) — keeps the browse card + detail header reactive without
 * a full re-list, and the next write's version current. No-op if the node isn't loaded.
 */
export function patchNodeMeta(nodeId: string, wrappedMeta: string | null, metaVersion: number): void {
  const node = treeStore.nodes.find((n) => n.id === nodeId)
  if (!node) return
  node.wrappedMeta = wrappedMeta
  node.metaVersion = metaVersion
}

/** Forget the loaded tree (logout / space switch). */
export function resetTree(): void {
  treeStore.nodes = []
  treeStore.loaded = false
  treeStore.error = false
}
