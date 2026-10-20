// en.memory.node-meta (web) — the sealed `wrappedMeta` blob on a node, the single home for a node's curated
// node-level metadata: a Memory's `date` and `location`, and any node's `cover` (inbox #411 moved this envelope from
// Memories to core, so a folder's and a drop box's cover use the very one a Memory's does). AEAD-sealed
// under a field key derived from the node's `K_node` (purpose `node-meta`, bound to (scope, node) — ADR 0072, ADR 0093
// § 9) so the blind server only ever holds ciphertext it cannot move to another node (cr-0067 / cr-0069 / cr-0072).
//
// The blob is **client-versioned** and grows without a contract change: `{ v, date?, location?, cover? }`. Because a
// single node PATCH replaces the WHOLE `wrappedMeta`, every writer must **read-modify-write** — unseal the
// current blob, merge only its own sub-object, re-seal — or it would clobber a sibling (writing a `location`
// must not wipe the `date`, and vice-versa). And since any member writes it, the read it modifies must be the
// server's current one: every write goes through `writeNodeMeta` (`nodeMetaWrite.ts`), which states the
// `metaRevision` it was built from and re-runs the change on a `409 stale_revision` (ADR 0077). This module owns the
// envelope and stays PURE — it is in the crypto mutation scope (`src/web/stryker.mutate.json`), which admits a file
// only when nothing in it does I/O, so the PATCH lives next door. The domain modules (`memoryDate`,
// `memoryLocation`, `memoryCover`) own how each sub-object is validated + materialized.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { MetaConflictError, MetaUnreadableError, stableJson, type MetaEditContext, type SealedMetaState } from './sealedMetaWrite'

/** The node a metadata blob belongs to (ADR 0072). */
export type NodeMetaAddress = { scopeId: string; nodeId: string }
const metaContext = (a: NodeMetaAddress) => ({ purpose: 'node-meta', scopeId: a.scopeId, nodeId: a.nodeId }) as const

/**
 * The raw node-metadata blob as it sits sealed. Sub-objects are left **loosely typed** on purpose: this module
 * knows the envelope, not the semantics — each domain module normalizes its own sub-object (`date`, `location`)
 * after unsealing. Keeping them `unknown` also means a writer preserves a *future* sibling it doesn't understand.
 */
export type RawNodeMeta = { v: number; cover?: unknown; [field: string]: unknown }

/**
 * Unseal a node's whole `wrappedMeta` under `nodeKey` (`K_node`) into the raw envelope **for display**.
 * **Best-effort (E3):** an absent, wrong-key, another node's, or malformed blob yields an empty `{ v: 1 }` (never a
 * throw), so a header or card degrades instead of breaking. **Never the base of a write** — that empty envelope,
 * re-sealed with one field, is exactly how a write erased every sibling (#273); writers open with
 * [openNodeMetaForWrite]. Unknown sibling fields are preserved verbatim.
 */
export function unsealNodeMeta(nodeKey: Uint8Array, node: NodeMetaAddress, wrappedMeta: string | null | undefined): RawNodeMeta {
  if (!wrappedMeta) return { v: 1 }
  try {
    const json = new TextDecoder().decode(v1.openField(metaContext(node), nodeKey, fromB64std(wrappedMeta)))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return { ...(parsed as RawNodeMeta), v: 1 }
  } catch {
    // fall through to the empty envelope
  }
  return { v: 1 }
}

/** Seal a whole node-metadata blob under `nodeKey` (`K_node`), bound to its node → opaque base64. */
export function sealNodeMeta(nodeKey: Uint8Array, node: NodeMetaAddress, meta: RawNodeMeta): string {
  const bytes = new TextEncoder().encode(JSON.stringify({ ...meta, v: 1 }))
  return b64std(v1.sealField(metaContext(node), nodeKey, bytes))
}

/**
 * Open a node's blob **for a write** (ADR 0077 point 4). Unlike [unsealNodeMeta] this is not best-effort: only an
 * ABSENT blob is a fresh `{ v: 1 }`. A present blob that does not open (wrong key, another node's, tampered) or
 * does not parse throws [MetaUnreadableError] — rebuilding it from empty would re-seal the writer's one field and
 * drop every sibling (inbox #273: a date save over such a blob erased the place). Unknown fields are kept verbatim.
 */
export function openNodeMetaForWrite(nodeKey: Uint8Array, node: NodeMetaAddress, wrappedMeta: string | null): RawNodeMeta {
  if (!wrappedMeta) return { v: 1 }
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder().decode(v1.openField(metaContext(node), nodeKey, fromB64std(wrappedMeta))))
  } catch {
    // leaves `parsed` undefined, which the one refusal below catches — a second `throw` here would be a mutant no
    // test can tell from its removal (measured: Stryker's lone survivor in this function)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new MetaUnreadableError()
  return { ...(parsed as RawNodeMeta), v: 1 }
}

/** A node's sealed meta as the tree holds it (`listNodes` carries both). */
export const nodeMetaState = (node: { wrappedMeta: string | null; metaRevision: number }): SealedMetaState => ({
  wrappedMeta: node.wrappedMeta,
  metaRevision: node.metaRevision,
})

/**
 * An EDITOR's change to one sub-object: set it (or, for null, drop it). On a re-run after a conflict, if that same
 * field is no longer what the editor opened with, it does not retry — it throws [MetaConflictError] so the editor
 * re-opens on the current value (ADR 0077 point 3); a conflict on any other field merges silently. `surface:
 * false` re-applies regardless (the cover editor once its save moved the cover blob — D9).
 */
export function editField(
  field: string,
  value: unknown,
  openedWith: unknown,
  surface = true,
): (meta: RawNodeMeta, ctx: MetaEditContext) => RawNodeMeta {
  return (meta, { retry, state }) => {
    if (retry && surface && stableJson(meta[field]) !== stableJson(openedWith)) throw new MetaConflictError(state)
    if (value === null || value === undefined) delete meta[field]
    else meta[field] = value
    return meta
  }
}

/**
 * An editor's change to the `participants` sub-object (uc.memory.participants, ADR 0083) that MERGES instead of
 * surfacing a conflict: `merge` gets the ids stored in the blob the edit runs on (the first attempt's or a conflict's) and
 * returns the list to store. No difference to the stored list means no write (`null`); an empty list drops the sub-object.
 * Unknown fields inside the sub-object are preserved. The sub-object holds opaque user ids only.
 */
export function editParticipantsOf(merge: (stored: string[]) => string[]): (meta: RawNodeMeta, ctx: MetaEditContext) => RawNodeMeta | null {
  return (meta) => {
    const raw = meta.participants
    // No `typeof raw === 'object'` guard here: the blob is parsed JSON, and no JSON primitive has a `userIds`, so
    // that conjunct changed nothing — Stryker measured it as an equivalent mutant (2026-10-17).
    const rawIds = Array.isArray((raw as { userIds?: unknown } | null | undefined)?.userIds) ? ((raw as { userIds: unknown[] }).userIds) : []
    const stored = rawIds.filter((id): id is string => typeof id === 'string' && id !== '')
    const next = merge([...new Set(stored)])
    if (stableJson(next) === stableJson(stored)) return null
    if (next.length === 0) delete meta.participants
    // An array is a non-null object too, but no sub-object: spreading it would write its elements as keys `0`, `1`, …
    // (inbox #473) — it is treated like a missing sub-object.
    else meta.participants = { ...(raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}), userIds: next }
    return meta
  }
}
