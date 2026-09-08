// en.memory.node-meta (web) — the sealed `wrappedMeta` blob on a content node (MEMORY/CHAPTER), the single
// home for a Memory's curated node-level metadata (its `date`, its `location`, and its `cover`). AEAD-sealed
// under the node's `K_mem` (AAD context `node-meta`) so the blind server only ever holds ciphertext
// (cr-0067 / cr-0069 / cr-0072).
//
// The blob is **client-versioned** and grows without a contract change: `{ v, date?, location?, cover? }`. Because a
// single node PATCH replaces the WHOLE `wrappedMeta`, every writer must **read-modify-write** — unseal the
// current blob, merge only its own sub-object, re-seal — or it would clobber a sibling (writing a `location`
// must not wipe the `date`, and vice-versa). This module owns that envelope; the domain modules (`memoryDate`,
// `memoryLocation`) own how each sub-object is validated + materialized.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** AAD context binding the sealed node-metadata blob (`wrappedMeta`) to its purpose (cr-0067). */
export const NODE_META_CTX = 'node-meta'

/**
 * The raw node-metadata blob as it sits sealed. Sub-objects are left **loosely typed** on purpose: this module
 * knows the envelope, not the semantics — each domain module normalizes its own sub-object (`date`, `location`)
 * after unsealing. Keeping them `unknown` also means a writer preserves a *future* sibling it doesn't understand.
 */
export type RawNodeMeta = { v: number; date?: unknown; location?: unknown; cover?: unknown }

/**
 * Unseal a node's whole `wrappedMeta` under `nodeKey` (`K_mem`) into the raw envelope. **Best-effort (E3):** an
 * absent, wrong-key, or malformed blob yields an empty `{ v: 1 }` (never a throw) — so a writer read-modify-write
 * still produces a clean single-field blob instead of failing. Unknown sibling fields are preserved verbatim.
 */
export function unsealNodeMeta(nodeKey: Uint8Array, wrappedMeta: string | null | undefined): RawNodeMeta {
  if (!wrappedMeta) return { v: 1 }
  try {
    const json = new TextDecoder().decode(v1.symUnwrap(nodeKey, fromB64std(wrappedMeta), NODE_META_CTX))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return { ...(parsed as RawNodeMeta), v: 1 }
  } catch {
    // fall through to the empty envelope
  }
  return { v: 1 }
}

/** Seal a whole node-metadata blob under `nodeKey` (`K_mem`, AAD `node-meta`) → opaque base64. */
export function sealNodeMeta(nodeKey: Uint8Array, meta: RawNodeMeta): string {
  const bytes = new TextEncoder().encode(JSON.stringify({ ...meta, v: 1 }))
  return b64std(v1.symWrap(nodeKey, bytes, NODE_META_CTX, v1.randomBytes(12)))
}
