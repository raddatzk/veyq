// The active scope's decrypted tree, loaded once and shared by the browse + Memory views (cr-0030). Holds
// plaintext node names (decrypted on device) — never key material (K_SC lives in app/activeSpace). Mutations
// (create/rename/delete) call `loadTree()` to refresh.
import { reactive } from 'vue'
import { activeSpace, activeEpochKeys } from '../../app/activeSpace'
import { fetchTreeRows, openTree, treeFingerprint, type TreeNode } from './tree'

export const treeStore = reactive({
  nodes: [] as TreeNode[],
  loaded: false,
  error: false,
})

// What the tree on screen was read from: bumped by every load and reset, so a live poll (below) that began before one
// of them is recognised as stale when it lands. `shown` is the fingerprint of the rows `nodes` holds.
let generation = 0
let shown: string | null = null

/** (Re)load the active scope's tree. No-op if no space is open. */
export async function loadTree(): Promise<void> {
  const epochs = activeEpochKeys()
  if (!activeSpace.scopeId || !epochs) return
  generation++
  try {
    const rows = await fetchTreeRows(activeSpace.scopeId)
    treeStore.nodes = openTree(activeSpace.scopeId, epochs, rows)
    shown = treeFingerprint(rows)
    treeStore.loaded = true
    treeStore.error = false
  } catch {
    treeStore.error = true
  }
}

/**
 * One tick of the level view's live refresh (`useLevelLive`, inbox #408): re-read the scope's rows and, only if they
 * differ from what is shown, open them and replace the tree. Resolves whether it changed anything; REJECTS when the read
 * fails (the poller backs off) and never touches `treeStore.error` — a transient failure of a background read must not
 * turn a working page into the error state.
 *
 * It never races a write. A user's write ends in `loadTree()`, which bumps the generation: a poll that STARTED before
 * any load began, or whose rows came back after one began, is dropped unopened (its rows may predate the write). The
 * decrypting and the swap happen in one synchronous step after the last await, so nothing can interleave with them.
 */
export async function pollTree(signal: AbortSignal): Promise<boolean> {
  const epochs = activeEpochKeys()
  const scopeId = activeSpace.scopeId
  if (!scopeId || !epochs || !treeStore.loaded) return false
  const startedAt = generation
  const rows = await fetchTreeRows(scopeId)
  if (signal.aborted || startedAt !== generation || scopeId !== activeSpace.scopeId) return false
  const fingerprint = treeFingerprint(rows)
  if (fingerprint === shown) return false
  treeStore.nodes = openTree(scopeId, epochs, rows)
  shown = fingerprint
  return true
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
  generation++
  shown = null
  treeStore.nodes = []
  treeStore.loaded = false
  treeStore.error = false
}
