// uc.account.unlock — the in-memory vault holding the unlocked Master Key + identity private key. This is
// the *unlocked* half of the account and lives ONLY here: never in `session.ts` (auth ≠ unlock — those gate
// a session, this opens content), never persisted, cleared on sign-out and gone on reload. Ephemeral by
// default: a fresh app session re-derives it (re-unlock), so we don't keep it anywhere durable.
//
// The raw key bytes are kept in a plain (non-reactive) holder so Vue's deep proxy never wraps a Uint8Array
// the crypto layer will use; the UI observes only the reactive `unlocked` flag.
import { reactive } from 'vue'
import type { Identity } from '@veyq/crypto/v1'

/**
 * What opened this vault — the input ADR 0060's cold-credential gate reads (uc.account.rotate-keys §2).
 *
 * **Three-valued, and the third value is the reason this is not a boolean.** A `PASSWORD` or released
 * `RECOVERY` wrap takes the Secret Key, which is precisely what a phished device-link grant never hands over,
 * so those two are `cold`. A `DEVICE` or `DEVICE_KEY` wrap is opened by material the device itself holds →
 * `device`. And the boot restore from the session vault opens **no wrap at all** — the Master Key was cached
 * in `sessionStorage`, so there is no method to name and `none` is its only honest entry; the same is true of
 * a vault opened from a Master Key handed over by a pairing grant. A boolean would force one of those two to
 * be labelled with a method it never used, and `none` is exactly the state ADR 0060 Decision 2 refuses.
 */
export type UnlockOrigin = 'cold' | 'device' | 'none'

const keys = {
  masterKey: null as Uint8Array | null,
  identityPrivate: null as Identity['priv'] | null,
}

/** Reactive unlock flag + how the vault was opened — never holds key material. `origin` is null while locked. */
export const vaultState = reactive({ unlocked: false, origin: null as UnlockOrigin | null })

/** Hold the freshly opened Master Key + identity private key for this working session, and how it was opened. */
export function setUnlocked(masterKey: Uint8Array, identityPrivate: Identity['priv'], origin: UnlockOrigin): void {
  keys.masterKey = masterKey
  keys.identityPrivate = identityPrivate
  vaultState.unlocked = true
  vaultState.origin = origin
}

/** How this vault was opened, or null while locked. See {@link UnlockOrigin}. */
export function unlockOrigin(): UnlockOrigin | null {
  return vaultState.origin
}

/** Drop all key material (sign-out / re-lock). Zeroes the key bytes before releasing the references, so
 *  the Master Key / identity private key don't linger in the heap until GC (best-effort: JS can't guarantee
 *  no copy was made, but it shortens the window for a heap snapshot to recover a key after sign-out). */
export function clearVault(): void {
  keys.masterKey?.fill(0)
  keys.identityPrivate?.kemX.fill(0)
  keys.identityPrivate?.mlkemSeed.fill(0)
  keys.identityPrivate?.sign.fill(0)
  keys.masterKey = null
  keys.identityPrivate = null
  vaultState.unlocked = false
  vaultState.origin = null
}

export function isUnlocked(): boolean {
  return vaultState.unlocked
}

// The getters return a fresh COPY, never the vault's own buffer: clearVault() zero-fills the internal bytes
// in place on re-lock, so handing out the live reference would let a held copy silently read zeroes after a
// sign-out (use-after-clear). The caller owns its copy and should use it transiently. Callers must not persist it.

/** The unlocked Master Key as a fresh copy, or null while locked. */
export function masterKey(): Uint8Array | null {
  return keys.masterKey ? keys.masterKey.slice() : null
}

/** The unlocked identity private key as a fresh copy, or null while locked. */
export function identityPrivate(): Identity['priv'] | null {
  const p = keys.identityPrivate
  return p ? { kemX: p.kemX.slice(), mlkemSeed: p.mlkemSeed.slice(), sign: p.sign.slice() } : null
}
