// The active scope's decrypted tree, loaded once and shared by the browse + Memory views (cr-0030). Holds
// plaintext node names (decrypted on device) — never key material (K_SC lives in app/activeSpace). Mutations
// (create/rename/delete) call `loadTree()` to refresh.
import { reactive } from 'vue'
import { activeSpace, activeEpochKeys } from '../../app/activeSpace'
import { fetchTree, openTree, treeFingerprint, type TreeNode } from './tree'
import { HttpStatus, isCodedError } from '../../shared/httpError'

export const treeStore = reactive({
  nodes: [] as TreeNode[],
  loaded: false,
  error: false,
  /** The server no longer knows this member in the active scope — a read of it answered `404 scope_not_found` (they
   *  were removed from the group, or it is gone): the page says the access ended and stops asking (inbox #546). */
  gone: false,
})

/**
 * Whether [e] says the member no longer reaches the ACTIVE scope [scopeId] (`404 scope_not_found`, "no such scope, or the
 * caller holds no grant"); if so the tree turns to its access-ended state, which stops the tree's live poll and unmounts
 * the page — and with it every application's own poll (an Expenses Pot's, a Memory's gallery). Any poll of a scope's
 * content asks this of its failures, so one that lands while the tree's poll is paused (a dialog is open) ends it too.
 * Another failure, or a scope that is no longer the active one, changes nothing.
 */
export async function noteScopeGone(scopeId: string, e: unknown): Promise<boolean> {
  if (!scopeId || scopeId !== activeSpace.scopeId) return false
  if (!(await isCodedError(e, HttpStatus.notFound, 'scope_not_found'))) return false
  if (scopeId !== activeSpace.scopeId) return false
  treeStore.gone = true
  return true
}

// What the tree on screen was read from: bumped by every load and reset, so a live poll (below) that began before one
// of them is recognised as stale when it lands. `shown` is the fingerprint of the rows `nodes` holds; `shownTag` the
// tree revision (the listing's `ETag`, ADR 0081) those rows were read at, which a poll echoes as `If-None-Match`.
let generation = 0
let shown: string | null = null
let shownTag: string | null = null

/** (Re)load the active scope's tree. No-op if no space is open. */
export async function loadTree(): Promise<void> {
  const epochs = activeEpochKeys()
  if (!activeSpace.scopeId || !epochs) return
  generation++
  const scopeId = activeSpace.scopeId
  try {
    const { rows, etag } = (await fetchTree(scopeId))! // no tag sent, so never a 304
    treeStore.nodes = openTree(scopeId, epochs, rows)
    shown = treeFingerprint(rows)
    shownTag = etag
    treeStore.loaded = true
    treeStore.error = false
  } catch (e) {
    if (!(await noteScopeGone(scopeId, e))) treeStore.error = true
  }
}

/**
 * One tick of the level view's live refresh (`useLevelLive`, inbox #408): a CONDITIONAL read of the scope's rows (ADR
 * 0081) — the tag of what is shown goes as `If-None-Match`, and an unchanged scope answers `304` with no rows, no
 * decryption and no work at all. Only a moved scope sends rows, and those replace the tree only if they differ from
 * what is shown (the fingerprint gate stays, as the fallback for a server that sends no tag and for a change in another
 * folder, which moves the scope's revision and not this level's rows). Resolves whether the scope MOVED — the sections'
 * cue to look again at what lives beside the tree (Depot's blob listing: an upload completing changes the revision and
 * no row) — and REJECTS when the read fails (the poller backs off); it never touches `treeStore.error`: a transient
 * failure of a background read must not turn a working page into the error state. Only the answer that the member no
 * longer reaches the scope ends it, as {@link noteScopeGone} says.
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
  let read: Awaited<ReturnType<typeof fetchTree>>
  try {
    read = await fetchTree(scopeId, shownTag)
  } catch (e) {
    if (await noteScopeGone(scopeId, e)) return false // the access ended: the poll stops (its `active` reads `gone`)
    throw e
  }
  if (signal.aborted || startedAt !== generation || scopeId !== activeSpace.scopeId) return false
  if (!read) return false // 304: the revision is the one shown
  shownTag = read.etag
  const fingerprint = treeFingerprint(read.rows)
  if (fingerprint !== shown) {
    treeStore.nodes = openTree(scopeId, epochs, read.rows)
    shown = fingerprint
  }
  return true
}

/**
 * Patch a node's sealed `wrappedMeta` and the `metaRevision` it is at, in place, after a meta write or a conflict
 * that handed back the current value (cr-0067, ADR 0077) — keeps the browse card + detail header reactive without
 * a full re-list, and the next write's version current. No-op if the node isn't loaded.
 */
export function patchNodeMeta(nodeId: string, wrappedMeta: string | null, metaRevision: number): void {
  const node = treeStore.nodes.find((n) => n.id === nodeId)
  if (!node) return
  node.wrappedMeta = wrappedMeta
  node.metaRevision = metaRevision
}

/** Forget the loaded tree (logout / space switch). */
export function resetTree(): void {
  generation++
  shown = null
  shownTag = null
  treeStore.nodes = []
  treeStore.loaded = false
  treeStore.error = false
  treeStore.gone = false
}
