// uc.sharing.share-uploader (web) — the key a media is sealed under is fixed by WHERE IT CAME FROM (ADR 0031
// point 7). A member, a READ_WRITE guest and a viewer link all seal and open under the Memory's `K_mem`. A
// WRITE_ONLY guest never holds `K_mem`: it mints its own identity key `K_up`, seals its name and every upload under
// it, and hands `K_up` over wrapped to the Memory's drop public key. Every reader of the Memory opens such a media
// through the drop PRIVATE key, which the Memory keeps sealed under `K_mem`:
//
//   K_mem → drop private key (`drop-private-key`, on the node) → K_up (`upload-identity-key`, on the identity)
//
// The call sites do not branch on this. A media (or a bucket) that a WRITE_ONLY identity added carries its
// [UploaderOrigin], attached where the row was listed; [mediaKey] is the one place that turns `K_mem` + a row into
// the key the row's seals are under. A member who edits such a media's metadata re-seals under that same `K_up`,
// so no media ever changes path.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/**
 * Where a WRITE_ONLY guest's row opens from: the Memory it was added to, that Memory's `K_mem`-sealed drop private
 * key, and the identity with its wrapped `K_up` — all as the listing served them, so every field is addressed by
 * where it was fetched (ADR 0072 Decision 1).
 */
export type UploaderOrigin = {
  nodeId: string
  sealedDropPrivateKey: string
  identityId: string
  wrappedIdentityKey: string
}

const dropPrivateContext = (scopeId: string, nodeId: string) => ({ purpose: 'drop-private-key', scopeId, nodeId }) as const
const DROP_PUBLIC_KEY = { purpose: 'drop-public-key' } as const
const uploadIdentityContext = (scopeId: string, nodeId: string, identityId: string) =>
  ({ purpose: 'upload-identity-key', scopeId, nodeId, identityId }) as const

/** The uploader origin a listing row carries, when its owner (`share:{identityId}`) is a WRITE_ONLY identity the
 *  listing served a wrapped key for; undefined for every other row — which then opens under `K_mem`. */
export function uploaderOriginOf(
  ownerKey: string | undefined | null,
  nodeId: string,
  sealedDropPrivateKey: string | undefined | null,
  wrappedKeys: ReadonlyMap<string, string>,
): UploaderOrigin | undefined {
  if (!ownerKey?.startsWith('share:') || !sealedDropPrivateKey) return undefined
  const identityId = ownerKey.slice('share:'.length)
  const wrappedIdentityKey = wrappedKeys.get(identityId)
  return wrappedIdentityKey ? { nodeId, sealedDropPrivateKey, identityId, wrappedIdentityKey } : undefined
}

// K_up per (K_mem, origin): opening it costs an ML-KEM decapsulation, and a gallery asks for every tile. Keyed by
// the K_mem ARRAY so a cached key never outlives the key that opened it; a mismatched K_mem simply misses.
const opened = new WeakMap<Uint8Array, Map<string, Uint8Array>>()

/**
 * THE resolver: the key a row's seals are under — `K_up` for a row a WRITE_ONLY guest added (opened through the
 * Memory's drop key), `K_mem` for every other. Throws [v1.OpenRefused] when an uploader row's chain does not open
 * (a moved or tampered wrap), exactly as the AEAD open the caller was about to do would.
 */
export function mediaKey(scopeId: string, memoryKey: Uint8Array, row: { uploader?: UploaderOrigin }): Uint8Array {
  const o = row.uploader
  if (!o) return memoryKey
  const cacheKey = `${scopeId}|${o.nodeId}|${o.identityId}|${o.wrappedIdentityKey}|${o.sealedDropPrivateKey}`
  let byOrigin = opened.get(memoryKey)
  const hit = byOrigin?.get(cacheKey)
  if (hit) return hit
  const drop = openDropKey(scopeId, o.nodeId, memoryKey, o.sealedDropPrivateKey)
  const kUp = v1.openAs(
    uploadIdentityContext(scopeId, o.nodeId, o.identityId),
    v1.dropKeySecret(drop),
    v1.dropKeyPublic(drop),
    fromB64std(o.wrappedIdentityKey),
  )
  if (!byOrigin) opened.set(memoryKey, (byOrigin = new Map()))
  byOrigin.set(cacheKey, kUp)
  return kUp
}

/** Open a Memory's drop key from its node field, under that Memory's `K_mem`, bound to (scope, node). */
export function openDropKey(scopeId: string, nodeId: string, memoryKey: Uint8Array, sealed: string): v1.DropKey {
  return v1.deserializeDropKey(v1.open(dropPrivateContext(scopeId, nodeId), memoryKey, fromB64std(sealed)))
}

/**
 * The drop keys a WRITE_ONLY share is created with (owner side): the Memory's existing drop key when its node
 * already carries one — every upload so far is sealed to it — else a fresh one, whose `K_mem`-sealed private half
 * rides along for the server to set on the Memory. Either way the share gets the public half under `K_share`.
 */
export function dropKeysForShare(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  kShare: Uint8Array,
  existingSealedDropPrivateKey: string | null,
): { sealedDropPublicKey: string; sealedDropPrivateKey?: string } {
  const drop = existingSealedDropPrivateKey
    ? openDropKey(scopeId, nodeId, memoryKey, existingSealedDropPrivateKey)
    : v1.generateDropKey()
  const sealedDropPublicKey = b64std(v1.seal(DROP_PUBLIC_KEY, kShare, v1.serializeHybridPublic(v1.dropKeyPublic(drop))))
  if (existingSealedDropPrivateKey) return { sealedDropPublicKey }
  return {
    sealedDropPublicKey,
    sealedDropPrivateKey: b64std(v1.seal(dropPrivateContext(scopeId, nodeId), memoryKey, v1.serializeDropKey(drop))),
  }
}

/** Guest side: the Memory's drop public key, opened from the WRITE_ONLY resolve with the fragment key. */
export function openDropPublicKey(kShare: Uint8Array, sealed: string): v1.HybridPublic {
  return v1.deserializeHybridPublic(v1.open(DROP_PUBLIC_KEY, kShare, fromB64std(sealed)))
}

/** Guest side: a fresh identity key `K_up` and its wrap to the Memory's drop public key, bound to (scope, node,
 *  identity). The guest seals its name and every upload under `K_up`, and keeps it — it never holds `K_mem`. */
export function mintUploaderKey(
  scopeId: string,
  nodeId: string,
  identityId: string,
  dropPublicKey: v1.HybridPublic,
): { uploaderKey: Uint8Array; wrappedIdentityKey: string } {
  const uploaderKey = v1.randomBytes(32)
  const wrapped = v1.sealTo(uploadIdentityContext(scopeId, nodeId, identityId), dropPublicKey, uploaderKey)
  return { uploaderKey, wrappedIdentityKey: b64std(wrapped) }
}
