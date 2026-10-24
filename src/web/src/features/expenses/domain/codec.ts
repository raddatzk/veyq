// The format v1 codec of one Expenses revision (ADR 0108 decision 4): its plaintext padded to a size class, sealed as an
// `expense-entry` field under a random key of its own, `K_rev`, and `K_rev` wrapped under the Pot's `K_node` by
// `expense-entry-key`. Both seals bind (scope, Pot node, entry id, revision) — the ADDRESS the reader fetched the revision
// by, never a field of the row that carries it (ADR 0072 decision 1) — so a body or wrap the server serves at another
// entry, revision or Pot does not open. Every seal is `@veyq/crypto`'s; this file only chooses the purposes and the classes.
//
// A re-key after a rotation (ADR 0109) is {@link rewrapRevisionKey}: it opens `K_rev` under the old key and wraps it
// under the new one, and never touches the body — so the body's digest, which later revisions' heads name, stays.
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { decodeRevisionBody, encodeRevisionBody, type RevisionBody } from './format'

/** The padded plaintext sizes (512 B, 2 KiB, 8 KiB, 16 KiB — the contract's 16 KiB cap is the last). The server takes a
 *  body of exactly one of their sealed lengths, so a revision's size tells it nothing finer than its class. */
export const SIZE_CLASSES = [512, 2048, 8192, 16384] as const

const K_REV_BYTES = 32

/** Where a revision lives — everything its two seals bind. */
export type RevisionAddress = { scopeId: string; nodeId: string; entryId: string; revision: number }

const keyContext = (a: RevisionAddress) =>
  ({ purpose: 'expense-entry-key', scopeId: a.scopeId, nodeId: a.nodeId, entryId: a.entryId, revision: a.revision }) as const
const bodyContext = (a: RevisionAddress) =>
  ({ purpose: 'expense-entry', scopeId: a.scopeId, nodeId: a.nodeId, entryId: a.entryId, revision: a.revision }) as const

/** One revision as the wire carries it: the sealed body and the wrapped `K_rev`, raw bytes. */
export type SealedRevision = { sealedBody: Uint8Array; wrappedRevisionKey: Uint8Array }

/** The digest a later revision's heads name for this one: SHA-256 of its sealed body exactly as served, base64url
 *  without padding (43 characters). A re-key moves the wrap, never the body, so it never moves this. */
export const revisionDigest = (sealedBody: Uint8Array): string => b64url(v1.sha256Digest(sealedBody))

/**
 * Seal one revision under a fresh `K_rev` and wrap that key under the Pot's key. The body is held to the v1 schema
 * first ([encodeRevisionBody]) and padded to the smallest class that carries it; one past 16 KiB throws `pad_too_large`.
 * The money rules (an unbalanced entry is never saved) are the caller's to have checked — `ledger.ts`'s `entryProblem`.
 */
export function sealRevision(address: RevisionAddress, potKey: Uint8Array, body: RevisionBody): SealedRevision {
  const kRev = v1.randomBytes(K_REV_BYTES)
  const padded = v1.padToClass(encodeRevisionBody(body), SIZE_CLASSES)
  return {
    sealedBody: v1.sealField(bodyContext(address), kRev, padded),
    wrappedRevisionKey: v1.seal(keyContext(address), potKey, kRev),
  }
}

/** Why a served revision did not open: its wrap or body does not open at this address (a moved, swapped or forged one),
 *  or it opened to something that is not a padded v1 body. */
export class RevisionUnreadable extends Error {
  constructor(readonly reason: 'key' | 'body' | 'format') {
    super(`revision_unreadable: ${reason}`)
    this.name = 'RevisionUnreadable'
  }
}

/** Open one revision read at [address] with the Pot's key: `K_rev` from its wrap, the body under `K_rev`, unpadded and
 *  held to the schema. Throws [RevisionUnreadable]. */
export function openRevision(address: RevisionAddress, potKey: Uint8Array, sealed: SealedRevision): RevisionBody {
  let kRev: Uint8Array
  try {
    kRev = v1.open(keyContext(address), potKey, sealed.wrappedRevisionKey)
  } catch {
    throw new RevisionUnreadable('key')
  }
  let padded: Uint8Array
  try {
    padded = v1.openField(bodyContext(address), kRev, sealed.sealedBody)
  } catch {
    throw new RevisionUnreadable('body')
  }
  try {
    return decodeRevisionBody(v1.unpad(padded))
  } catch {
    throw new RevisionUnreadable('format')
  }
}

/** The `K_rev` of one revision (a report hands it over, ADR 0108 decision 6). Throws [RevisionUnreadable] when the wrap
 *  does not open at this address. */
export function revisionKeyOf(address: RevisionAddress, potKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array {
  try {
    return v1.open(keyContext(address), potKey, wrappedRevisionKey)
  } catch {
    throw new RevisionUnreadable('key')
  }
}

/** A re-key's part for one revision: its `K_rev` opened under the Pot's old key and wrapped under the new one, bound to
 *  the same address. The body is not an input, so it cannot change. */
export const rewrapRevisionKey = (address: RevisionAddress, oldPotKey: Uint8Array, newPotKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array =>
  v1.seal(keyContext(address), newPotKey, revisionKeyOf(address, oldPotKey, wrappedRevisionKey))
