// Scope content-key epochs (ADR 0024, cr-0089). A rotated scope's content key is a forward chain of epoch keys
// E0, E1, …; each new epoch wraps its predecessor (`symWrap(E_{n+1}, E_n)`). A member holds the latest epoch (via
// their grant) and peels the chain down to E_0. New content seals under the **write key** (the highest epoch they
// hold); a node sealed under epoch k is read with E_k. Operator-blind: the peel is on-device; the server stores
// only the opaque chain-links.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

/** AAD binding an epoch-chain link: `symWrap(E_n, E_{n-1}, EPOCH_LINK)`. */
export const EPOCH_LINK = 'scope-epoch'

/** The content-key epochs a caller holds for a scope: the current (write) epoch + the per-epoch keys. */
export type EpochKeys = {
  /** The highest epoch the caller holds — new content seals under this one. */
  current: number
  /** epoch → key, for every epoch from 0 up to `current` (the peeled chain). */
  keys: ReadonlyMap<number, Uint8Array>
}

/** The write key — the current epoch's key (new content seals under it). */
export function writeKey(ek: EpochKeys): Uint8Array {
  return ek.keys.get(ek.current)!
}

/** The key that seals epoch `epoch` — to read a node tagged with it. Falls back to the write key for an epoch the
 *  caller doesn't hold (shouldn't happen for a current member: they hold every epoch ≤ current, and every node is
 *  ≤ current). */
export function keyAt(ek: EpochKeys, epoch: number): Uint8Array {
  return ek.keys.get(epoch) ?? writeKey(ek)
}

/** A single scope epoch-chain link (opaque `wrappedPrev` = `symWrap(E_epoch, E_{epoch-1})`). */
export type EpochLink = { epoch: number; wrappedPrev: string }

/**
 * Peel the epoch chain from the grant key (`E_grantedEpoch`) down to `E_0`, using the chain-links. Returns the
 * full key set the caller holds. With no rotation (`grantedEpoch = 0`, no links) this is just `{ 0: grantKey }`.
 */
export function deriveEpochKeys(grantKey: Uint8Array, grantedEpoch: number, links: EpochLink[]): EpochKeys {
  const keys = new Map<number, Uint8Array>()
  keys.set(grantedEpoch, grantKey)
  const linkByEpoch = new Map(links.map((l) => [l.epoch, l.wrappedPrev]))
  for (let e = grantedEpoch; e >= 1; e--) {
    const wrappedPrev = linkByEpoch.get(e)
    if (!wrappedPrev) break
    keys.set(e - 1, v1.symUnwrap(keys.get(e)!, fromB64std(wrappedPrev), EPOCH_LINK))
  }
  return { current: grantedEpoch, keys }
}

/** Best-effort wipe of every held epoch key (on scope leave). */
export function zeroEpochKeys(ek: EpochKeys): void {
  ek.keys.forEach((k) => k.fill(0))
}
