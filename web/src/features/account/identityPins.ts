// Identity pinning — the operator-blind, cross-device TOFU pin store (en.account.identity-pinning, cr-0098,
// ADR 0028). A client remembers each contact's identity public key the first time it wraps to them, so a later
// *changed* key (an active operator's MITM, or a legitimate rotation) is surfaced, never silently trusted.
//
// The store is ONE blob sealed under the Master Key (`symWrap(masterKey, blob, "identity-pins")`) and synced with
// the account: the server relays one opaque ciphertext + a monotonic `version` for optimistic concurrency, and
// never sees a userId, a pinned key, or the count. The sealed plaintext is `{ pins: { <userId>: { key, state,
// firstSeenAt } } }`; `version` is the cleartext wire counter (the server's authority for concurrency — the
// enabler's `{ version, pins }` shows both halves conceptually). Account-global: identity is one keypair per
// account across tenants (ADR 0023), so there is one pin per contact everywhere.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountIdentityPinsApi, tasksApi } from '../../api/client'
import { withRefresh } from './login/login'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { masterKey, identityPrivate } from '../../app/vault'

/** The AAD context binding the pin-store seal (ADR 0017, vector-pinned `sym_identity_pins`). */
export const IDENTITY_PINS = 'identity-pins'

/** A pin's trust state. `pinned` = trust-on-first-use; `verified` = an out-of-band safety-number compare
 *  succeeded (the human ceremony, cr-0099). `verified` rides inside the same Master-Key-sealed blob. */
export type PinState = 'pinned' | 'verified'

/** The trust a contact reads as, folding the pin state against their *current* directory key (the badge read). */
export type TrustState = 'verified' | 'pinned' | 'changed' | 'unpinned'

/** One pinned contact: their full identity public bundle (serialized, base64), the trust state, and first-seen. */
export interface Pin {
  key: string
  state: PinState
  firstSeenAt: number
}

type PinMap = Record<string, Pin>

/** The opened store: the server's current `version` (for the next guarded write) + the decrypted pin map. */
interface OpenedStore {
  version: number
  pins: PinMap
}

/** The TOFU outcome of checking a contact's directory key against the pin store. */
export type CheckResult = 'firstUse' | 'match' | 'changed'

/**
 * Thrown by a **UI-path** wrap site (e.g. the group invite name-key) when a contact's directory key differs from
 * the pin — the caller must surface a notice and let the human accept (→ {@link acceptKeyChange}) rather than
 * silently wrap to a possibly-substituted key. The human-less grant-delivery worker does not throw this; it
 * declines the job instead.
 */
export class IdentityChangedError extends Error {
  constructor(public readonly userId: string) {
    super(`identity key changed for ${userId}`)
    this.name = 'IdentityChangedError'
  }
}

/**
 * Thrown by {@link markVerified} when the pinned key no longer equals the key the human just compared out-of-band
 * — the contact's key moved (a rotation, or a concurrent-device accept) *between* the compare and the write. The
 * ceremony must not stamp `verified` onto a key nobody actually checked; the caller re-runs the compare.
 */
export class IdentityMovedError extends Error {
  constructor(public readonly userId: string) {
    super(`identity key moved for ${userId} — re-verify`)
    this.name = 'IdentityMovedError'
  }
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Bounds the re-read/merge/retry loop on a `409 stale_version` — a real conflict is a concurrent device write. */
const MAX_WRITE_ATTEMPTS = 5

/** Decrypt the pin map from a sealed blob; an unreadable/absent blob is treated as an empty store (never throws). */
function unsealPins(mk: Uint8Array, blobB64: string | null): PinMap {
  if (!blobB64) return {}
  try {
    const json = decoder.decode(v1.symUnwrap(mk, fromB64std(blobB64), IDENTITY_PINS))
    const parsed = JSON.parse(json) as { pins?: unknown }
    return parsed && typeof parsed.pins === 'object' && parsed.pins !== null ? (parsed.pins as PinMap) : {}
  } catch {
    return {}
  }
}

/** Seal a pin map under the Master Key with a fresh nonce → the opaque base64 blob to PUT. */
function sealPins(mk: Uint8Array, pins: PinMap): string {
  const json = encoder.encode(JSON.stringify({ pins }))
  return b64std(v1.symWrap(mk, json, IDENTITY_PINS, v1.randomBytes(12)))
}

/** Fetch + open the caller's pin store (born-empty → `{ version: 0, pins: {} }`). Requires an unlocked vault. */
async function loadStore(): Promise<OpenedStore> {
  const mk = masterKey()
  if (!mk) throw new Error('identity pin store requires an unlocked vault')
  try {
    const res = await withRefresh(() => accountIdentityPinsApi().getIdentityPins())
    return { version: res.version, pins: unsealPins(mk, res.blob) }
  } finally {
    mk.fill(0)
  }
}

/** Whether a failed call is a `409 stale_version` (a concurrent device advanced the store under our write). */
function isStaleVersion(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_version')
}

/**
 * Apply a change to the pin map and store it, version-guarded. On a `409 stale_version` (another device wrote
 * first) re-read the server's current store, **re-apply the same change on top of it** (client-side union-merge —
 * `apply` is written to be additive/idempotent, so a concurrent pin for another contact is preserved), and retry.
 * Starts from an already-loaded `base` to avoid a redundant GET on the common no-conflict path.
 */
async function mutateAndSave(base: OpenedStore, apply: (pins: PinMap) => void): Promise<void> {
  let store = base
  for (let attempt = 0; ; attempt++) {
    const next: PinMap = { ...store.pins }
    apply(next)
    const mk = masterKey()
    if (!mk) throw new Error('identity pin store requires an unlocked vault')
    try {
      const blob = sealPins(mk, next)
      await withRefresh(() =>
        accountIdentityPinsApi().putIdentityPins({ identityPinsUpdate: { blob, version: store.version } }),
      )
      return
    } catch (e) {
      if (attempt + 1 < MAX_WRITE_ATTEMPTS && (await isStaleVersion(e))) {
        store = await loadStore() // re-base on the server's current store, then re-apply + retry
        continue
      }
      throw e
    } finally {
      mk.fill(0)
    }
  }
}

/**
 * TOFU check before a foreign-identity wrap: compare the directory-returned `identityPublicKeyB64` for `userId`
 * against the pin store.
 * - **no pin** → pin it (`state: pinned`) and return `firstUse` — the caller proceeds with the wrap (trust-on-first-use).
 * - **pin matches** → return `match` — proceed.
 * - **pin differs** → return `changed` — the caller must NOT silently wrap (the worker declines the job; a UI path
 *   surfaces a notice + accept → re-pin). The store is left untouched.
 * Requires an unlocked vault.
 */
export async function checkAndPin(userId: string, identityPublicKeyB64: string): Promise<CheckResult> {
  const store = await loadStore()
  const existing = store.pins[userId]
  if (existing) return existing.key === identityPublicKeyB64 ? 'match' : 'changed'
  await mutateAndSave(store, (pins) => {
    // Additive union-merge: only claim the slot if still unpinned after a possible re-base (first-online-wins;
    // a concurrent device that pinned this same contact first keeps its pin).
    if (!pins[userId]) pins[userId] = { key: identityPublicKeyB64, state: 'pinned', firstSeenAt: Date.now() }
  })
  return 'firstUse'
}

/**
 * Accept a **changed** key for a contact (ADR 0028 human path): re-pin `userId := new key`, preserving the
 * original `firstSeenAt`. Drops back to `state: pinned` (the `verified` elevation via the QR safety-number compare
 * is the follow-up CR). Overwrites unconditionally — the human explicitly accepted this key.
 *
 * **Two writes, because a re-pin alone does not deliver anything.** A worker that declined `identity_changed`
 * is excluded from that job permanently — nothing expires a decline — so before ADR 0060 Decision 7 the accept
 * authorised a wrap that nothing then performed. That is uc.account.rotate-keys §E6, the failure mode that
 * strands a rotated account outside the scopes it was rotating to rejoin. Clearing this worker's own declines
 * against this contact is what makes ADR 0028 (2)'s *"the wrap then proceeds"* true; delivery is re-driven by
 * the ordinary next claim.
 *
 * The clear's failure is **not** swallowed, and that is deliberate: silently dropping it would re-create the
 * exact stranding this exists to end. Re-accepting is idempotent — the re-pin overwrites unconditionally and
 * the clear deletes nothing the second time — so a caller that reports the error and lets the human retry ends
 * up in the right state, whereas one that reports success cannot.
 */
export async function acceptKeyChange(userId: string, identityPublicKeyB64: string): Promise<void> {
  const store = await loadStore()
  await mutateAndSave(store, (pins) => {
    const prior = pins[userId]
    pins[userId] = { key: identityPublicKeyB64, state: 'pinned', firstSeenAt: prior?.firstSeenAt ?? Date.now() }
  })
  await withRefresh(() =>
    tasksApi().clearDeclinesForAcceptedKey({ clearDeclinesRequest: { targetUserId: userId } }),
  )
}

/**
 * Re-seal the pin store from the **old** Master Key to a **new** one — key rotation (ADR 0060, step 4).
 *
 * It takes both keys explicitly instead of reading the vault, and that is the whole reason it exists: during a
 * rotation the vault still holds the *old* Master Key (it must — the sole-member scope re-key that follows opens
 * grants with the old identity), so every other function in this file would seal under exactly the key the
 * rotation is retiring.
 *
 * **Best-effort by design, and the failure is a degradation rather than a lockout.** An unreadable blob already
 * degrades to an empty store ({@link unsealPins} swallows), so a re-seal that never lands costs the pins, not
 * the account: every contact falls back to `unpinned` and is re-pinned on first use. That is why the rotation
 * runs this *after* the irreversible publish — a re-seal before it would, on a failed publish, have thrown the
 * pins away for a rotation that never happened.
 *
 * The pins themselves are carried across verbatim: rotating **this** account's identity says nothing about
 * anybody else's key, so dropping them would manufacture a `changed` for every contact at once.
 */
export async function resealPinsUnderNewMasterKey(oldMk: Uint8Array, newMk: Uint8Array): Promise<void> {
  const res = await withRefresh(() => accountIdentityPinsApi().getIdentityPins())
  const pins = unsealPins(oldMk, res.blob)
  await withRefresh(() =>
    accountIdentityPinsApi().putIdentityPins({
      identityPinsUpdate: { blob: sealPins(newMk, pins), version: res.version },
    }),
  )
}

/** Read a contact's current pin (or null), for a UI surface that shows trust state. No write. */
export async function getPin(userId: string): Promise<Pin | null> {
  const store = await loadStore()
  return store.pins[userId] ?? null
}

/**
 * The out-of-band **safety number** ("6×5 digits") for a serialized identity public bundle — what two members
 * read aloud / scan to compare. Pure and vault-free: `fingerprint(deserializeIdentityPublic(...))` (ADR 0017,
 * vector-pinned). The input is the same base64 identity bundle the directory returns and the pin store holds.
 */
export function safetyNumber(identityPublicKeyB64: string): string {
  return v1.fingerprint(v1.deserializeIdentityPublic(fromB64std(identityPublicKeyB64)))
}

/**
 * My OWN identity public bundle (base64) — reconstructed from the unlocked vault's private key, so the ceremony
 * can render *my* safety-number + a QR of it for the other person to scan, without trusting a directory round-trip
 * for my own key. Requires an unlocked vault. The private copy is zeroed after derivation.
 */
export function myIdentityPublicKeyB64(): string {
  const priv = identityPrivate()
  if (!priv) throw new Error('identity verify requires an unlocked vault')
  try {
    return b64std(v1.serializeIdentityPublic(v1.identityPublicFromPrivate(priv)))
  } finally {
    priv.kemX.fill(0)
    priv.mlkemSeed.fill(0)
    priv.sign.fill(0)
  }
}

/**
 * Elevate a contact's pin `→ verified` after a successful out-of-band safety-number compare (the human half of
 * ADR 0028). Guarded twice: only stamps `verified` if the **current** pin key still equals `verifiedKey` (the key
 * the human actually compared) — both at load and after any 409 re-base — else throws {@link IdentityMovedError}
 * (the key moved under the compare; nothing is written). `verified` wins a same-contact merge: the re-applied
 * upgrade re-asserts `verified` on top of a concurrent device's `pinned`. Requires an unlocked vault.
 */
export async function markVerified(userId: string, verifiedKey: string): Promise<void> {
  const store = await loadStore()
  await mutateAndSave(store, (pins) => {
    const prior = pins[userId]
    if (!prior || prior.key !== verifiedKey) throw new IdentityMovedError(userId)
    pins[userId] = { ...prior, state: 'verified' }
  })
}

/**
 * The **badge read** — fold one pin against a contact's *current* directory key.
 * - no pin → `unpinned`
 * - pin key ≠ directory key → `changed` (surfaced-not-trusted; wrap/delivery stays gated until reviewed)
 * - pin key = directory key → `verified` if the pin was verified out-of-band, else `pinned`
 */
function foldTrust(pin: Pin | undefined, directoryKey: string): TrustState {
  if (!pin) return 'unpinned'
  if (pin.key !== directoryKey) return 'changed'
  return pin.state === 'verified' ? 'verified' : 'pinned'
}

/** The badge read for a single contact (pure, no write). */
export async function trustState(userId: string, directoryKey: string): Promise<TrustState> {
  return foldTrust((await getPin(userId)) ?? undefined, directoryKey)
}

/**
 * Batch badge read for a whole member directory in **one** store fetch — folds every contact against the key the
 * directory currently returns for them. Keyed by userId. Pure, no write; requires an unlocked vault.
 */
export async function trustStates(
  contacts: ReadonlyArray<{ userId: string; directoryKey: string }>,
): Promise<Record<string, TrustState>> {
  const store = await loadStore()
  const out: Record<string, TrustState> = {}
  for (const c of contacts) out[c.userId] = foldTrust(store.pins[c.userId], c.directoryKey)
  return out
}
