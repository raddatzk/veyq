// Scope content-key epochs (ADR 0024, cr-0089). A rotated scope's content key is a forward chain of epoch keys
// E0, E1, …; each new epoch wraps its predecessor (`seal({ purpose: 'scope-epoch', scopeId, epoch: n+1 }, E_{n+1}, E_n)`). A member holds the latest epoch (via
// their grant) and peels the chain down to E_0. New content seals under the **write key** (the highest epoch they
// hold); a node sealed under epoch k is read with E_k. Operator-blind: the peel is on-device; the server stores
// only the opaque chain-links.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

/** The seal context of the chain link stored at `epoch` — `E_{epoch-1}` sealed under `E_epoch`, bound to its scope
 *  and to the epoch it is stored at, so a link served for another scope or epoch does not open (ADR 0072). */
export const epochLinkContext = (scopeId: string, epoch: number) => ({ purpose: 'scope-epoch', scopeId, epoch }) as const

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

/** A single scope epoch-chain link (opaque `wrappedPrev` = `E_{epoch-1}` sealed under `E_epoch`). */
export type EpochLink = { epoch: number; wrappedPrev: string }

/**
 * Peel the epoch chain from the grant key (`E_grantedEpoch`) down to `E_0`, using the chain-links. Returns the
 * full key set the caller holds. With no rotation (`grantedEpoch = 0`, no links) this is just `{ 0: grantKey }`.
 */
export function deriveEpochKeys(scopeId: string, grantKey: Uint8Array, grantedEpoch: number, links: EpochLink[]): EpochKeys {
  const keys = new Map<number, Uint8Array>()
  keys.set(grantedEpoch, grantKey)
  const linkByEpoch = new Map(links.map((l) => [l.epoch, l.wrappedPrev]))
  for (let e = grantedEpoch; e >= 1; e--) {
    const wrappedPrev = linkByEpoch.get(e)
    if (!wrappedPrev) break
    keys.set(e - 1, v1.open(epochLinkContext(scopeId, e), keys.get(e)!, fromB64std(wrappedPrev)))
  }
  return { current: grantedEpoch, keys }
}

/** Best-effort wipe of every held epoch key (on scope leave). */
export function zeroEpochKeys(ek: EpochKeys): void {
  ek.keys.forEach((k) => k.fill(0))
}

/** A copy of every held epoch key, for work that outlives the holder's own set (which may be zeroed meanwhile); the
 *  caller zeroes the copy with {@link zeroEpochKeys}. */
export function copyEpochKeys(ek: EpochKeys): EpochKeys {
  return { current: ek.current, keys: new Map([...ek.keys].map(([e, k]) => [e, k.slice()])) }
}

/**
 * Open a value whose row does not record the epoch it was sealed under — a member's join record, name and picture on the
 * tenant profile: under every epoch key held, **newest first**; the first that opens wins, returned with its epoch. `open`
 * returns null (or throws) for a key it does not open under. Only the keys of `ek` are tried, so a reader opens exactly
 * what its grant's keys open: a value sealed under an epoch it does not hold stays shut. Null when none opens.
 *
 * Newest first, because a writer seals under the current epoch of the moment it writes and a member's own client moves its
 * name and picture forward to the current epoch (`features/account/profile/carryProfileForward.ts`): the first try almost
 * always opens, and a value a rotation left behind costs one failed AEAD open per epoch in between.
 */
export function openUnderHeldEpochs<T>(ek: EpochKeys, open: (key: Uint8Array) => T | null): { value: T; epoch: number } | null {
  for (let e = ek.current; e >= 0; e--) {
    const key = ek.keys.get(e)
    if (!key) continue
    let value: T | null = null
    try {
      value = open(key)
    } catch {
      /* not sealed under this epoch's key */
    }
    if (value !== null) return { value, epoch: e }
  }
  return null
}
