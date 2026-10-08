// A file's sealed metadata (docs/apps/files.md § How a file is stored): its MIME type, its plaintext size and its
// modified time, as a small JSON in the FILE node's generic `wrappedMeta`, sealed under the file node's own key with the
// `node-meta` seal bound to (scope, node) — ADR 0072 — so the blind server holds ciphertext it cannot move to another
// node. Files adds no column and no field to the contract: the node's meta is core's slot, written through core's
// `updateNode` with the `metaVersion` it was built from (ADR 0077) by core's one write loop (`writeSealedMeta`).
//
// The envelope is client-versioned like every node meta: `{ v: 1, mime, size, modifiedAt }`. A writer keeps any field it
// does not know (a later version's) verbatim, and a present blob that does not open is refused for a write, never
// rebuilt from empty (`MetaUnreadableError`, ADR 0077 point 4).
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { contentApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { MetaUnreadableError, writeSealedMeta, type SealedMetaResult, type SealedMetaState } from '../../core/node/sealedMetaWrite'

/** The node a file's metadata belongs to (ADR 0072). */
export type FileMetaAddress = { scopeId: string; nodeId: string }
const metaContext = (a: FileMetaAddress) => ({ purpose: 'node-meta', scopeId: a.scopeId, nodeId: a.nodeId }) as const

/** What a file's metadata says: the MIME type the browser reported (may be empty), the plaintext size in bytes and the
 *  file's own modified time (ms since the epoch, `File.lastModified`). */
export type FileMeta = { mime: string; size: number; modifiedAt: number }

/** The sealed envelope: the known fields loosely typed (each is checked on read), anything else kept verbatim. */
export type RawFileMeta = { v: number; mime?: unknown; size?: unknown; modifiedAt?: unknown; [field: string]: unknown }

/** The metadata of a picked file, as the browser reports it. */
export function fileMetaOf(file: File): FileMeta {
  return { mime: file.type, size: file.size, modifiedAt: file.lastModified }
}

/** Seal a file's metadata envelope under its node key, bound to the node → opaque base64. */
export function sealFileMeta(nodeKey: Uint8Array, node: FileMetaAddress, meta: RawFileMeta): string {
  return b64std(v1.seal(metaContext(node), nodeKey, new TextEncoder().encode(JSON.stringify({ ...meta, v: 1 }))))
}

function openRaw(nodeKey: Uint8Array, node: FileMetaAddress, wrappedMeta: string): unknown {
  return JSON.parse(new TextDecoder().decode(v1.open(metaContext(node), nodeKey, fromB64std(wrappedMeta))))
}

/** Open a file's envelope **for a write**: an absent blob is a fresh `{ v: 1 }`; a present one that does not open or
 *  parse throws [MetaUnreadableError]. Unknown fields are kept. */
export function openFileMetaForWrite(nodeKey: Uint8Array, node: FileMetaAddress, wrappedMeta: string | null): RawFileMeta {
  if (!wrappedMeta) return { v: 1 }
  let parsed: unknown
  try {
    parsed = openRaw(nodeKey, node, wrappedMeta)
  } catch {
    // `parsed` stays undefined: the one refusal below
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new MetaUnreadableError()
  return { ...(parsed as RawFileMeta), v: 1 }
}

const finite = (x: unknown): number | null => (typeof x === 'number' && Number.isFinite(x) && x >= 0 ? x : null)

/** Read a file's metadata **for display**: null when absent, when it does not open (another node's, the wrong key,
 *  tampered) or when a field is malformed — the list then falls back to what the blob says, never to a guess. */
export function readFileMeta(nodeKey: Uint8Array, node: FileMetaAddress, wrappedMeta: string | null | undefined): FileMeta | null {
  if (!wrappedMeta) return null
  let parsed: unknown
  try {
    parsed = openRaw(nodeKey, node, wrappedMeta)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object') return null
  const raw = parsed as RawFileMeta
  const size = finite(raw.size)
  const modifiedAt = finite(raw.modifiedAt)
  if (typeof raw.mime !== 'string' || size === null || modifiedAt === null) return null
  return { mime: raw.mime, size, modifiedAt }
}

/**
 * Write a file's metadata into its node's `wrappedMeta`, version-checked (ADR 0077): sets `mime`, `size` and
 * `modifiedAt`, keeps every other field, and PATCHes with the `metaVersion` it was built from; on `409 stale_version`
 * the change is re-run on the blob the conflict handed back (core's `writeSealedMeta`). A file's metadata is the
 * uploader's statement about the bytes it just attached, so a re-run overwrites a peer's: whoever attached the blob
 * last describes it.
 */
export function writeFileMeta(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  base: SealedMetaState,
  meta: FileMeta,
): Promise<SealedMetaResult<RawFileMeta>> {
  const address = { scopeId, nodeId }
  return writeSealedMeta<RawFileMeta>(
    base,
    {
      open: (wrappedMeta) => openFileMetaForWrite(nodeKey, address, wrappedMeta),
      seal: (raw) => sealFileMeta(nodeKey, address, raw),
      send: async (wrappedMeta, metaVersion) => {
        await withRefresh(() =>
          contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta: wrappedMeta!, metaVersion } }),
        )
      },
    },
    (raw) => ({ ...raw, mime: meta.mime, size: meta.size, modifiedAt: meta.modifiedAt }),
  )
}
