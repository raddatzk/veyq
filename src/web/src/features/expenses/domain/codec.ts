// The format v1 codec of one Expenses revision (ADR 0108 decision 4): its plaintext padded to a size class, sealed as an
// `expense-entry` field under a random key of its own, `K_rev`, and `K_rev` wrapped under the Pot's `K_node` by
// `expense-entry-key`. Both seals bind (scope, Pot node, entry id, revision) — the ADDRESS the reader fetched the revision
// by, never a field of the row that carries it (ADR 0072 decision 1) — so a body or wrap the server serves at another
// entry, revision or Pot does not open. A re-key after a rotation (ADR 0109) is {@link rewrapRevisionKey}: it moves `K_rev`
// from the old key to the new one and never touches the body, so the body's digest, which later heads name, stays.
//
// The codec is the Kotlin core's (`expenses/Codec.kt`, the suite's `V1` over the web's own `@noble/*`, through
// `@veyq/core-js/expenses`, ADR 0155 decisions 7.3 and 9), held to `contracts/crypto/vectors/v1.json`'s `expense-entry` case;
// keys and bodies cross as views of the caller's buffer, never copies, so a key the caller zeroes is zeroed. This file keeps
// the types and the refusal as an error class of the web's own. It holds no rule.
import * as core from '@veyq/core-js/expenses'
import { formatRefusal, type RevisionBody } from './format'

/** Where a revision lives — everything its two seals bind. */
export type RevisionAddress = { scopeId: string; nodeId: string; entryId: string; revision: number }

/** One revision as the wire carries it: the sealed body and the wrapped `K_rev`, raw bytes. */
export type SealedRevision = { sealedBody: Uint8Array; wrappedRevisionKey: Uint8Array }

/** Why a served revision did not open: its wrap or body does not open at this address (a moved, swapped or forged one),
 *  or it opened to something that is not a padded v1 body. */
export class RevisionUnreadable extends Error {
  constructor(readonly reason: 'key' | 'body' | 'format') {
    super(`revision_unreadable: ${reason}`)
    this.name = 'RevisionUnreadable'
  }
}

/** The core's refusals as the web's own error classes. */
function refusal(e: unknown): unknown {
  if (e instanceof Error && e.name === 'RevisionUnreadable') return new RevisionUnreadable((e as Error & { reason: RevisionUnreadable['reason'] }).reason)
  return formatRefusal(e)
}
function guarded<T>(f: () => T): T {
  try {
    return f()
  } catch (e) {
    throw refusal(e)
  }
}

/** The digest a later revision's heads name for this one: SHA-256 of its sealed body exactly as served, base64url without
 *  padding (43 characters). A re-key moves the wrap, never the body, so it never moves this. */
export const revisionDigest = (sealedBody: Uint8Array): string => core.revisionDigest(sealedBody)

/** Seal one revision under a fresh `K_rev` and wrap that key under the Pot's key; the body is held to the v1 schema first,
 *  and one past 16 KiB throws `pad_too_large`. The money rules are the caller's to have checked (`ledger.ts`' `entryProblem`). */
export const sealRevision = (address: RevisionAddress, potKey: Uint8Array, body: RevisionBody): SealedRevision =>
  guarded(() => core.sealRevision(address, potKey, body as unknown as core.BodyShape))

/** Open one revision read at [address] with the Pot's key. Throws [RevisionUnreadable]. */
export const openRevision = (address: RevisionAddress, potKey: Uint8Array, sealed: SealedRevision): RevisionBody =>
  guarded(() => core.openRevision(address, potKey, sealed) as unknown as RevisionBody)

/** The `K_rev` of one revision (a report hands it over, ADR 0108 decision 6). Throws [RevisionUnreadable] when the wrap
 *  does not open at this address. */
export const revisionKeyOf = (address: RevisionAddress, potKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array =>
  guarded(() => core.revisionKeyOf(address, potKey, wrappedRevisionKey))

/** Open one revision's body with its `K_rev` alone — a moderator's review, handed the keys a report shared, holds no Pot
 *  key. Bound to [address] as every open is. Throws [RevisionUnreadable]. */
export const openRevisionWithKey = (address: RevisionAddress, kRev: Uint8Array, sealedBody: Uint8Array): RevisionBody =>
  guarded(() => core.openRevisionWithKey(address, kRev, sealedBody) as unknown as RevisionBody)

/** A re-key's part for one revision: its `K_rev` opened under the Pot's old key and wrapped under the new one, bound to
 *  the same address. The body is not an input, so it cannot change. Throws [RevisionUnreadable]. */
export const rewrapRevisionKey = (address: RevisionAddress, oldPotKey: Uint8Array, newPotKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array =>
  guarded(() => core.rewrapRevisionKey(address, oldPotKey, newPotKey, wrappedRevisionKey))
