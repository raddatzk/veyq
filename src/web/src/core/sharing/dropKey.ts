// A node's DROP KEY and its guests' IDENTITY KEYS (ADR 0031 point 7, ADR 0080 decision 11) — the platform's, whatever
// the node's kind: Memories' uploader seals each guest medium under it, Depot's drop box each guest's identity folder.
// A WRITE_ONLY guest never holds the node's content key: it mints its own identity key `K_up`, seals what it writes
// under it, and hands `K_up` over wrapped to the node's drop public key. Every reader holding the node's key opens it
// through the drop PRIVATE key, which the node keeps sealed under its content key:
//
//   K_node → drop private key (`drop-private-key`, on the node) → K_up (`upload-identity-key`, on the identity)
//
// One copy of these seals (CLAUDE.md: duplicated key-material logic is a security defect); Memories' `mediaKey.ts`
// builds its media resolver on it, core's tree (`core/node/tree.ts`, `entryKeyOf`) opens an identity folder's entry with it.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const dropPrivateContext = (scopeId: string, nodeId: string) => ({ purpose: 'drop-private-key', scopeId, nodeId }) as const
const DROP_PUBLIC_KEY = { purpose: 'drop-public-key' } as const
const uploadIdentityContext = (scopeId: string, nodeId: string, identityId: string) =>
  ({ purpose: 'upload-identity-key', scopeId, nodeId, identityId }) as const

/** Open a node's drop key from its node field, under that node's content key, bound to (scope, node). */
export function openDropKey(scopeId: string, nodeId: string, nodeKey: Uint8Array, sealed: string): v1.DropKey {
  return v1.deserializeDropKey(v1.open(dropPrivateContext(scopeId, nodeId), nodeKey, fromB64std(sealed)))
}

/**
 * The drop keys a WRITE_ONLY share is created with (owner side): the node's existing drop key when it already carries
 * one — everything a guest wrote so far is sealed to it — else a fresh one, whose node-key-sealed private half rides
 * along for the server to set on the node. Either way the share gets the public half under `K_share`.
 */
export function dropKeysForShare(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  kShare: Uint8Array,
  existingSealedDropPrivateKey: string | null,
): { sealedDropPublicKey: string; sealedDropPrivateKey?: string } {
  const drop = existingSealedDropPrivateKey
    ? openDropKey(scopeId, nodeId, nodeKey, existingSealedDropPrivateKey)
    : v1.generateDropKey()
  const sealedDropPublicKey = b64std(v1.seal(DROP_PUBLIC_KEY, kShare, v1.serializeHybridPublic(v1.dropKeyPublic(drop))))
  if (existingSealedDropPrivateKey) return { sealedDropPublicKey }
  return {
    sealedDropPublicKey,
    sealedDropPrivateKey: b64std(v1.seal(dropPrivateContext(scopeId, nodeId), nodeKey, v1.serializeDropKey(drop))),
  }
}

/** Guest side: the node's drop public key, opened from the WRITE_ONLY resolve with the fragment key. */
export function openDropPublicKey(kShare: Uint8Array, sealed: string): v1.HybridPublic {
  return v1.deserializeHybridPublic(v1.open(DROP_PUBLIC_KEY, kShare, fromB64std(sealed)))
}

/** Guest side: a fresh identity key `K_up` and its wrap to the node's drop public key, bound to (scope, node,
 *  identity). The guest seals what it writes under `K_up`, and keeps it — it never holds the node's key. */
export function mintIdentityKey(
  scopeId: string,
  nodeId: string,
  identityId: string,
  dropPublicKey: v1.HybridPublic,
): { identityKey: Uint8Array; wrappedIdentityKey: string } {
  const identityKey = v1.randomBytes(32)
  const wrapped = v1.sealTo(uploadIdentityContext(scopeId, nodeId, identityId), dropPublicKey, identityKey)
  return { identityKey, wrappedIdentityKey: b64std(wrapped) }
}

/** Reader side: an identity's `K_up` through the node's opened drop key. Throws `OpenRefused` when the wrap does not
 *  open for (scope, node, identity) — one moved from another node or identity included. */
export function openIdentityKey(
  scopeId: string, nodeId: string, drop: v1.DropKey, identityId: string, wrappedIdentityKey: string,
): Uint8Array {
  return v1.openAs(uploadIdentityContext(scopeId, nodeId, identityId), v1.dropKeySecret(drop), fromB64std(wrappedIdentityKey))
}

/** The key an identity folder's directory entry (name, slug, content-key wrap) lives under (ADR 0080 decision 11):
 *  its identity's `K_up`, opened through its parent's drop key under the parent's content key. */
export function identityEntryKey(
  scopeId: string,
  parent: { id: string; key: Uint8Array; sealedDropPrivateKey: string },
  identity: { identityId: string; wrappedIdentityKey: string },
): Uint8Array {
  const drop = openDropKey(scopeId, parent.id, parent.key, parent.sealedDropPrivateKey)
  return openIdentityKey(scopeId, parent.id, drop, identity.identityId, identity.wrappedIdentityKey)
}
