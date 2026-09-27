// en.memory.date (web) — a Memory's curated date, sealed on the NODE under its `K_mem` (uc.memory.date-place,
// cr-0067). Operator-blind: the effective day range travels in one client-versioned JSON blob `wrappedMeta`
// `{ v, date: { mode, from?, to? }, location? }` (the place slice rides the same blob — no contract change),
// AEAD-sealed under `K_mem` (AAD context `node-meta`) so the server only ever holds ciphertext. The range is
// **materialized** for every mode — the browse card grid shows + sorts by it WITHOUT loading a Memory's media.
//
// The seal is **read-modify-write** (cr-0069): `setMemoryDate` unseals the current `{ v, date?, location? }`,
// swaps in only the `date`, and re-seals — so writing a date never clobbers a sibling `location` (and vice-versa,
// via `setMemoryLocation`). The shared envelope lives in `nodeMeta.ts`.
import { contentApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { epochMsToIsoDate } from '../../shared/isoDate'
import { effectiveCapturedAt, unsealMeta, type MediaItem } from './media'
import { openContentKey, type TreeNode } from './tree'
import { sealNodeMeta, unsealNodeMeta } from './nodeMeta'

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
function normalizeDate(raw: unknown): MemoryDate | null {
  if (!raw || typeof raw !== 'object') return null
  const d = raw as { mode?: unknown; from?: unknown; to?: unknown }
  if (typeof d.mode !== 'string' || !MODES.includes(d.mode as MemoryDateMode)) return null
  return { mode: d.mode as MemoryDateMode, from: isoDayOrUndef(d.from), to: isoDayOrUndef(d.to) }
}

/** Seal a **fresh** date-only `wrappedMeta` blob under `nodeKey` (`K_mem`) → base64 (a node born with only a date). */
export function sealMemoryDate(nodeKey: Uint8Array, date: MemoryDate): string {
  return sealNodeMeta(nodeKey, { v: 1, date })
}

/**
 * Unseal the Memory date from a node's `wrappedMeta` under `nodeKey` (`K_mem`). Best-effort (E3): null when the
 * node carries no meta, the wrap doesn't open, the JSON is malformed, or the mode is unrecognized — the header
 * then falls back to the neutral `auto`/no-date state rather than break.
 */
export function unsealMemoryDate(nodeKey: Uint8Array, node: { wrappedMeta: string | null }): MemoryDate | null {
  return normalizeDate(unsealNodeMeta(nodeKey, node.wrappedMeta).date)
}

/**
 * **Read-modify-write** the node-meta blob: unseal the current `{ v, date?, location? }` under `nodeKey`
 * (`K_mem`), swap in only the `date`, re-seal, and PATCH via the partial `updateNode` (cr-0067 / cr-0069). This
 * **preserves a sibling `location`** — writing a date never clobbers the place. `currentMeta` is the node's live
 * `wrappedMeta` (or null for a node with none). Returns the sealed blob so the caller can update its local
 * `node.wrappedMeta` (and re-render the card/header) without a re-list.
 */
export async function setMemoryDate(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  date: MemoryDate,
  currentMeta: string | null,
): Promise<string> {
  const meta = unsealNodeMeta(nodeKey, currentMeta)
  meta.date = date
  const wrappedMeta = sealNodeMeta(nodeKey, meta)
  await withRefresh(() => contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta } }))
  return wrappedMeta
}

/**
 * Materialize a Memory's `auto` range from its **own** media, day-truncated (local `YYYY-MM-DD`). Counts only
 * **trustworthy** capture times — a user `capturedAtOverride`, or an EXIF-sourced `capturedAt` — so a
 * file-modification-date fallback can't stretch the range (cr-0067). An empty result ⇒ `{ mode: 'auto' }` (the
 * "no date yet" state). `nodeKey` = the Memory's `K_mem` (each media's capture-time tier is sealed under it).
 */
export function computeAutoRange(items: MediaItem[], nodeKey: Uint8Array): MemoryDate {
  const days: number[] = []
  for (const item of items) {
    const tier = unsealMeta(nodeKey, item)
    if (!tier) continue
    const trustworthy = tier.capturedAtOverride !== undefined || tier.capturedAtSource === 'exif'
    const ms = effectiveCapturedAt(tier)
    if (trustworthy && ms !== undefined) days.push(ms)
  }
  if (days.length === 0) return { mode: 'auto' }
  return { mode: 'auto', from: epochMsToIsoDate(Math.min(...days)), to: epochMsToIsoDate(Math.max(...days)) }
}

/** Whether two date ranges are equal (mode + both ends) — the reconcile re-seals only when this is false. */
export function sameMemoryDate(a: MemoryDate | null, b: MemoryDate | null): boolean {
  return a?.mode === b?.mode && a?.from === b?.from && a?.to === b?.to
}

/**
 * A card node's own date for the browse grid (cr-0067): open its `K_mem` under `parentKey` (`K_SC` for a
 * top-level Memory, the parent Memory's `K_mem` for a chapter), then unseal its `wrappedMeta`. Best-effort:
 * null when the key/meta is absent or won't open — **without loading the Memory's media**.
 */
export function unsealNodeDate(parentKey: Uint8Array | null, node: TreeNode): MemoryDate | null {
  if (!parentKey) return null
  try {
    const key = openContentKey(parentKey, node)
    return key ? unsealMemoryDate(key, node) : null
  } catch {
    return null // a key that won't open (wrong parent / malformed wrap) → no date, never a throw
  }
}

/**
 * Order memory/chapter nodes for the card grid: by **start day, newest first, undated last** (stable within a
 * group). Each node's date is unsealed under the key `keyFor(node)` returns — a **per-node** resolver so a browse
 * grid spanning several epochs opens each top-level Memory under its own epoch's `K_SC` (ADR 0024), while a
 * Memory's chapters all resolve under that Memory's single `K_mem`. The result pairs every node with its date so
 * the card can show the range without a second unseal. Reads only `wrappedMeta` — never a Memory's media.
 */
export function sortNodesByDate(
  keyFor: (node: TreeNode) => Uint8Array | null,
  nodes: TreeNode[],
): { node: TreeNode; date: MemoryDate | null }[] {
  return nodes
    .map((node, i) => ({ node, i, date: unsealNodeDate(keyFor(node), node) }))
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
