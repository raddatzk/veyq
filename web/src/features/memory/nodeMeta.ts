// en.memory.node-meta (web) — the sealed `wrappedMeta` blob on a content node (MEMORY/CHAPTER), the single
// home for a Memory's curated node-level metadata (its `date`, its `location`, and its `cover`). AEAD-sealed
// under the node's `K_mem` (purpose `node-meta`, bound to (scope, node) — ADR 0072) so the blind server only ever
// holds ciphertext it cannot move to another node (cr-0067 / cr-0069 / cr-0072).
//
// The blob is **client-versioned** and grows without a contract change: `{ v, date?, location?, cover? }`. Because a
// single node PATCH replaces the WHOLE `wrappedMeta`, every writer must **read-modify-write** — unseal the
// current blob, merge only its own sub-object, re-seal — or it would clobber a sibling (writing a `location`
// must not wipe the `date`, and vice-versa). And since any member writes it, the read it modifies must be the
// server's current one: every write goes through `writeNodeMeta` (`nodeMetaWrite.ts`), which states the
// `metaVersion` it was built from and re-runs the change on a `409 stale_version` (ADR 0077). This module owns the
// envelope and stays PURE — it is in the crypto mutation scope (`web/stryker.mutate.json`), which admits a file
// only when nothing in it does I/O, so the PATCH lives next door. The domain modules (`memoryDate`,
// `memoryLocation`, `memoryCover`) own how each sub-object is validated + materialized.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { MetaConflictError, MetaUnreadableError, stableJson, type MetaEditContext, type SealedMetaState } from '../../core/node/sealedMetaWrite'

/** The node a metadata blob belongs to (ADR 0072). */
export type NodeMetaAddress = { scopeId: string; nodeId: string }
const metaContext = (a: NodeMetaAddress) => ({ purpose: 'node-meta', scopeId: a.scopeId, nodeId: a.nodeId }) as const

/**
 * The raw node-metadata blob as it sits sealed. Sub-objects are left **loosely typed** on purpose: this module
 * knows the envelope, not the semantics — each domain module normalizes its own sub-object (`date`, `location`)
 * after unsealing. Keeping them `unknown` also means a writer preserves a *future* sibling it doesn't understand.
 */
export type RawNodeMeta = { v: number; date?: unknown; location?: unknown; cover?: unknown }

/**
 * Unseal a node's whole `wrappedMeta` under `nodeKey` (`K_mem`) into the raw envelope **for display**.
 * **Best-effort (E3):** an absent, wrong-key, another node's, or malformed blob yields an empty `{ v: 1 }` (never a
 * throw), so a header or card degrades instead of breaking. **Never the base of a write** — that empty envelope,
 * re-sealed with one field, is exactly how a write erased every sibling (#273); writers open with
 * [openNodeMetaForWrite]. Unknown sibling fields are preserved verbatim.
 */
export function unsealNodeMeta(nodeKey: Uint8Array, node: NodeMetaAddress, wrappedMeta: string | null | undefined): RawNodeMeta {
  if (!wrappedMeta) return { v: 1 }
  try {
    const json = new TextDecoder().decode(v1.open(metaContext(node), nodeKey, fromB64std(wrappedMeta)))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return { ...(parsed as RawNodeMeta), v: 1 }
  } catch {
    // fall through to the empty envelope
  }
  return { v: 1 }
}

/** Seal a whole node-metadata blob under `nodeKey` (`K_mem`), bound to its node → opaque base64. */
export function sealNodeMeta(nodeKey: Uint8Array, node: NodeMetaAddress, meta: RawNodeMeta): string {
  const bytes = new TextEncoder().encode(JSON.stringify({ ...meta, v: 1 }))
  return b64std(v1.seal(metaContext(node), nodeKey, bytes))
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
    parsed = JSON.parse(new TextDecoder().decode(v1.open(metaContext(node), nodeKey, fromB64std(wrappedMeta))))
  } catch {
    // leaves `parsed` undefined, which the one refusal below catches — a second `throw` here would be a mutant no
    // test can tell from its removal (measured: Stryker's lone survivor in this function)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new MetaUnreadableError()
  return { ...(parsed as RawNodeMeta), v: 1 }
}

/** A node's sealed meta as the tree holds it (`listNodes` carries both). */
export const nodeMetaState = (node: { wrappedMeta: string | null; metaVersion: number }): SealedMetaState => ({
  wrappedMeta: node.wrappedMeta,
  metaVersion: node.metaVersion,
})

/**
 * An EDITOR's change to one sub-object: set it (or, for null, drop it). On a re-run after a conflict, if that same
 * field is no longer what the editor opened with, it does not retry — it throws [MetaConflictError] so the editor
 * re-opens on the current value (ADR 0077 point 3); a conflict on any other field merges silently. `surface:
 * false` re-applies regardless (the cover editor once its save moved the cover blob — D9).
 */
export function editField(
  field: 'date' | 'location' | 'cover',
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
