// uc.memory.date (web) — a Memory's curated date, sealed on the NODE under its `K_node` (cr-0067).
// Operator-blind: the effective day range travels in one client-versioned JSON blob `wrappedMeta`
// `{ v, date: { mode, from?, to? }, location? }` (the place slice rides the same blob — no contract change),
// AEAD-sealed under `K_node` (AAD context `node-meta`) so the server only ever holds ciphertext. The range is
// **materialized** for every mode — the browse card grid shows + sorts by it WITHOUT loading a Memory's media.
//
// The seal is **read-modify-write** (cr-0069): `setMemoryDate` unseals the current `{ v, date?, location? }`,
// swaps in only the `date`, and re-seals — so writing a date never clobbers a sibling `location` (and vice-versa,
// via `setMemoryLocation`). It is version-checked, so the sibling a PEER wrote since our tree loaded survives too
// (ADR 0077). The shared envelope and the write loop live in `nodeMeta.ts`.
//
// The `auto` range read from the Memory's own media (`computeAutoRange`, `reconcileMemoryDate`) is `memoryDateAuto.ts`: it
// opens the gallery's listing, and this module is read by the level's rows and the search facets in the entry chunk.
import { openContentKey, type TreeNode } from '../../core/node/tree'
import { editField, sealNodeMeta, unsealNodeMeta, type NodeMetaAddress } from '../../core/node/nodeMeta'
import { writeNodeMeta, type WriteNodeMetaOptions } from '../../core/node/nodeMetaWrite'
import type { NodeMetaState } from '../../core/node/nodeMeta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'

/** The four date modes: derive from media (`auto`), a single day, a `from..to` span, or an open-ended `since`. */
export type MemoryDateMode = 'auto' | 'single' | 'range' | 'ongoing'

/**
 * A Memory's effective day range. `single` ⇒ `from === to`; `range` ⇒ both; `ongoing` ⇒ `from`, no `to`; a
 * materialized `auto` ⇒ both (or neither when the Memory has no trustworthy dated media). Days are `YYYY-MM-DD`.
 */
export type MemoryDate = { mode: MemoryDateMode; from?: string; to?: string }

const MODES: readonly MemoryDateMode[] = ['auto', 'single', 'range', 'ongoing']
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/

const isoDayOrUndef = (v: unknown): string | undefined => (typeof v === 'string' && ISO_DAY.test(v) ? v : undefined)

/** Normalize a loosely-typed date payload to a valid [MemoryDate], or null when the mode is unrecognized. */
export function normalizeDate(raw: unknown): MemoryDate | null {
  if (!raw || typeof raw !== 'object') return null
  const d = raw as { mode?: unknown; from?: unknown; to?: unknown }
  if (typeof d.mode !== 'string' || !MODES.includes(d.mode as MemoryDateMode)) return null
  return { mode: d.mode as MemoryDateMode, from: isoDayOrUndef(d.from), to: isoDayOrUndef(d.to) }
}

/** Seal a **fresh** date-only `wrappedMeta` blob under `nodeKey` (`K_node`) → base64 (a node born with only a date). */
export function sealMemoryDate(nodeKey: Uint8Array, node: NodeMetaAddress, date: MemoryDate): string {
  return sealNodeMeta(nodeKey, node, { v: 1, date })
}

/**
 * Unseal the Memory date from a node's `wrappedMeta` under `nodeKey` (`K_node`). Best-effort (E3): null when the
 * node carries no meta, the wrap doesn't open, the JSON is malformed, or the mode is unrecognized — the header
 * then falls back to the neutral `auto`/no-date state rather than break.
 */
export function unsealMemoryDate(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): MemoryDate | null {
  return normalizeDate(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).date)
}

/**
 * The date EDITOR's save: **read-modify-write** the node-meta blob — swap in only the `date`, re-seal, PATCH with
 * the `metaRevision` it was built from (cr-0067 / cr-0069, ADR 0077). A sibling `location`/`cover` is preserved,
 * including one a peer wrote meanwhile (the `409` re-run). If the peer changed the DATE itself, the save throws
 * `MetaConflictError` carrying the current state, and the editor re-opens on it; a blob that does not open throws
 * `MetaUnreadableError`. Returns the state the server confirmed, for the caller's tree.
 */
export async function setMemoryDate(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  date: MemoryDate,
  current: NodeMetaState,
  opts: WriteNodeMetaOptions = {},
): Promise<SealedMetaState> {
  const openedWith = unsealNodeMeta(nodeKey, { scopeId, nodeId }, current.wrappedMeta).date
  const { state } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, editField('date', date, openedWith), undefined, opts)
  return state
}

/** Whether two date ranges are equal (mode + both ends) — the reconcile re-seals only when this is false. */
export function sameMemoryDate(a: MemoryDate | null, b: MemoryDate | null): boolean {
  return a?.mode === b?.mode && a?.from === b?.from && a?.to === b?.to
}

/**
 * A card node's own date for the browse grid (cr-0067): open its `K_node` under `parentKey` (`K_SC` for a
 * scope-root Memory, the folder's key for one inside a folder, the parent Memory's `K_node` for a chapter), then unseal its `wrappedMeta`. Best-effort:
 * null when the key/meta is absent or won't open — **without loading the Memory's media**.
 */
export function unsealNodeDate(scopeId: string, parentKey: Uint8Array | null, node: TreeNode): MemoryDate | null {
  if (!parentKey) return null
  try {
    const key = openContentKey(scopeId, parentKey, node)
    return key ? unsealMemoryDate(scopeId, key, node) : null
  } catch {
    return null // a key that won't open (wrong parent / malformed wrap) → no date, never a throw
  }
}

/**
 * Order memory/chapter nodes for the card grid: by **start day, newest first, undated last** (stable within a
 * group). Each node's date is unsealed under the key `keyFor(node)` returns — a **per-node** resolver so a browse
 * grid spanning several epochs opens each scope-root Memory under its own epoch's `K_SC` (ADR 0024) and one inside
 * a folder under the folder's key (ADR 0080), while a
 * Memory's chapters all resolve under that Memory's single `K_node`. The result pairs every node with its date so
 * the card can show the range without a second unseal. Reads only `wrappedMeta` — never a Memory's media.
 */
export function sortNodesByDate(
  scopeId: string,
  keyFor: (node: TreeNode) => Uint8Array | null,
  nodes: TreeNode[],
): { node: TreeNode; date: MemoryDate | null }[] {
  return nodes
    .map((node, i) => ({ node, i, date: unsealNodeDate(scopeId, keyFor(node), node) }))
    .sort((a, b) => {
      const da = a.date?.from
      const db = b.date?.from
      if (!da && !db) return a.i - b.i // both undated → stable list order, undated grouped last
      if (!da) return 1
      if (!db) return -1
      return da < db ? 1 : da > db ? -1 : a.i - b.i // larger ISO day (later) first
    })
    .map(({ node, date }) => ({ node, date }))
}
