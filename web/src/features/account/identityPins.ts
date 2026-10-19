// Identity pinning — the operator-blind, cross-device TOFU pin store (en.account.identity-pinning, cr-0098,
// ADR 0028). A client remembers each contact's identity public key the first time it wraps to them, so a later
// *changed* key (an active operator's MITM, or a legitimate rotation) is surfaced, never silently trusted.
//
// The store is ONE blob sealed under the Master Key (`seal({ purpose: "identity-pins", revision }, masterKey, blob)`) and synced with
// the account: the server relays one opaque ciphertext + a monotonic `revision` for optimistic concurrency, and
// never sees a userId, a pinned key, or the count. The sealed plaintext is `{ pins: { <userId>: { key, state,
// firstSeenAt } } }`; `revision` is the cleartext wire counter (the server's authority for concurrency — the
// enabler's `{ revision, pins }` shows both halves conceptually). Account-global: identity is one keypair per
// account across tenants (ADR 0023), so there is one pin per contact everywhere.
//
// Every open — before a read and before every write — goes through this device's trust-state marks
// (`trustMarks.ts`, ADR 0028 Decision 7): a store older than one this device has held, a missing store after it
// held one, and a store that does not open are each refused as a `TrustStateRefused`, never read as empty.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountIdentityPinsApi, tasksApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { masterKey, identityPrivate } from '../../app/vault'
import { openAccountDocument, raiseMarks, servedDocument, type AccountDocumentStore } from './trustMarks'

/** The pin store's seal context at a stored `revision` (ADR 0072, vector `seal.sym.identity-pins`): bound to the
 *  revision the server stores it at, so a blob served under another revision does not open. */
const pinsContext = (revision: number) => ({ purpose: 'identity-pins', revision }) as const

/** A pin's trust state. `pinned` = trust-on-first-use; `verified` = an out-of-band safety-number compare
 *  succeeded (the ceremony on a member's profile, ADR 0028 Decision 3). `verified` rides inside the same Master-Key-sealed blob. */
export type PinState = 'pinned' | 'verified'

/** The trust a contact reads as, folding the pin state against their *current* directory key (the badge read). */
export type TrustState = 'verified' | 'pinned' | 'changed' | 'unpinned'

/** One contact's badge read: the folded {@link TrustState}, and whether the pin it folded is `verified`. On `changed`
 *  that second half is the louder case - **a verified contact's key changed** (ADR 0028 Decision 3) - which the badge
 *  and the review say. A separate flag rather than a fifth state, so every `state === 'changed'` check keeps
 *  catching the verified case instead of silently missing it. */
export interface ContactTrust {
  state: TrustState
  pinVerified: boolean
}

/** One pinned contact: their full identity public bundle (serialized, base64), the trust state, and first-seen. */
export interface Pin {
  key: string
  state: PinState
  firstSeenAt: number
}

type PinMap = Record<string, Pin>

/** The opened store: the server's current `revision` (for the next guarded write) + the decrypted pin map. */
interface OpenedStore {
  revision: number
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

/** Bounds the re-read/merge/retry loop on a `409 stale_revision` — a real conflict is a concurrent device write. */
const MAX_WRITE_ATTEMPTS = 5

/** Decrypt the pin map from a sealed blob. Throws on anything that is not a store this client wrote — a blob that
 *  does not open, or opens to another shape; the caller refuses it as unreadable (ADR 0028 Decision 7). */
function unsealPins(mk: Uint8Array, blobB64: string, revision: number): PinMap {
  const parsed = JSON.parse(decoder.decode(v1.open(pinsContext(revision), mk, fromB64std(blobB64)))) as { pins?: unknown }
  if (!parsed || typeof parsed.pins !== 'object' || parsed.pins === null) throw new Error('identity_pins_unreadable')
  return parsed.pins as PinMap
}

/** Open a served store through this device's marks — refused if rolled back, missing after a mark, or unreadable. */
function openPins(mk: Uint8Array, served: { blob: string | null; revision: number }): Promise<PinMap> {
  return openAccountDocument('pins', mk, served, (blob) => unsealPins(mk, blob, served.revision), (): PinMap => ({}))
}

/** Seal a pin map under the Master Key for the revision it will be stored at — the PUT's `revision + 1`, since the
 *  server bumps the revision it accepts by one → the opaque base64 blob to PUT. */
function sealPins(mk: Uint8Array, pins: PinMap, storedRevision: number): string {
  const json = encoder.encode(JSON.stringify({ pins }))
  return b64std(v1.seal(pinsContext(storedRevision), mk, json))
}

/** Fetch + open the caller's pin store (born-empty: revision 0, no pins). Requires an unlocked vault. Throws
 *  `TrustStateRefused` for a store this device must not accept. */
async function loadStore(): Promise<OpenedStore> {
  const mk = masterKey()
  if (!mk) throw new Error('identity pin store requires an unlocked vault')
  try {
    const res = await withRefresh(() => accountIdentityPinsApi().getIdentityPins())
    return { revision: res.revision, pins: await openPins(mk, servedDocument(res)) }
  } finally {
    mk.fill(0)
  }
}

/** Whether a failed call is a `409 stale_revision` (a concurrent device advanced the store under our write). */
function isStaleRevision(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_revision')
}

/**
 * Apply a change to the pin map and store it, revision-guarded. On a `409 stale_revision` (another device wrote
 * first) re-read the server's current store, **re-apply the same change on top of it** (client-side union-merge —
 * `apply` is written to be additive/idempotent, so a concurrent pin for another contact is preserved), and retry.
 * Starts from an already-loaded `base` to avoid a redundant GET on the common no-conflict path. `base` and every
 * re-base come from {@link loadStore}, so the open before each write meets the same marks as a read; a write that
 * lands raises the mark to the revision it stored.
 */
async function mutateAndSave(base: OpenedStore, apply: (pins: PinMap) => void): Promise<void> {
  let store = base
  for (let attempt = 0; ; attempt++) {
    const next: PinMap = { ...store.pins }
    apply(next)
    const mk = masterKey()
    if (!mk) throw new Error('identity pin store requires an unlocked vault')
    try {
      const blob = sealPins(mk, next, store.revision + 1)
      await withRefresh(() =>
        accountIdentityPinsApi().putIdentityPins({ identityPinsUpdate: { blob, revision: store.revision } }),
      )
      await raiseMarks(mk, { pins: store.revision + 1 })
      return
    } catch (e) {
      if (attempt + 1 < MAX_WRITE_ATTEMPTS && (await isStaleRevision(e))) {
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
 * original `firstSeenAt`. Drops back to `state: pinned` even for a contact that was `verified` - `verified` returns
 * only through a new compare on their profile (ADR 0028 Decision 3), never with the accept. Overwrites
 * unconditionally — the human explicitly accepted this key.
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
 * The pin store as one whole document (`trustMarks.ts` § `AccountDocumentStore`), for the two operations that
 * act on it whole:
 *
 *  - **key rotation** (ADR 0060 step 4) opens it under the OLD Master Key and re-seals it under the new one inside
 *    the rotation's own request — so a re-seal that cannot land fails the rotation instead of stranding the store
 *    under a retired key. Both keys are explicit, because mid-rotation the vault still holds the old one. The pins
 *    cross verbatim: rotating THIS account's identity says nothing about anybody else's key;
 *  - **the user's reset** (`trustReset/trustReset.ts`) writes its `empty()` when this device refuses the store.
 *
 * `open` is {@link openPins}, so the marks apply to both exactly as to every read.
 */
export const pinStoreDocument: AccountDocumentStore<PinMap> = {
  document: 'pins',
  fetch: async () => servedDocument(await withRefresh(() => accountIdentityPinsApi().getIdentityPins())),
  open: openPins,
  seal: sealPins,
  empty: (): PinMap => ({}),
  write: async (identityPinsUpdate) => {
    await withRefresh(() => accountIdentityPinsApi().putIdentityPins({ identityPinsUpdate }))
  },
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
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}

/**
 * Elevate a contact's pin `→ verified` after a successful out-of-band safety-number compare (the human half of
 * ADR 0028). Guarded twice: only stamps `verified` if the **current** pin key still equals `verifiedKey` (the key
 * the human actually compared) — both at load and after any 409 re-base — else throws {@link IdentityMovedError}
 * (the key moved under the compare; nothing is written). `verified` wins a same-contact merge: the re-applied
 * upgrade re-asserts `verified` on top of a concurrent device's `pinned`. Requires an unlocked vault.
 *
 * `pinIfUnpinned` (ADR 0028 Decision 3): the compare was against the DIRECTORY's key because there was no pin, so a
 * contact still unpinned at the write is pinned with the compared key, already `verified` - in the same one sealed
 * write, never a pin without the verification. A pin that appeared meanwhile (a concurrent device, the delivery
 * worker's TOFU) is held to the same guard: its key must be the compared one.
 */
export async function markVerified(userId: string, verifiedKey: string, opts: { pinIfUnpinned?: boolean } = {}): Promise<void> {
  const store = await loadStore()
  await mutateAndSave(store, (pins) => {
    const prior = pins[userId]
    if (!prior && opts.pinIfUnpinned) {
      pins[userId] = { key: verifiedKey, state: 'verified', firstSeenAt: Date.now() }
      return
    }
    if (!prior || prior.key !== verifiedKey) throw new IdentityMovedError(userId)
    pins[userId] = { ...prior, state: 'verified' }
  })
}

/**
 * The **badge read** — fold one pin against a contact's *current* directory key.
 * - no pin → `unpinned`
 * - pin key ≠ directory key → `changed` (surfaced-not-trusted; wrap/delivery stays gated until reviewed)
 * - pin key = directory key → `verified` if the pin was verified out-of-band, else `pinned`
 * Exported for a surface that already holds the pin ({@link getPin}) - the profile's compare - so it folds without a
 * second store fetch.
 */
export function foldTrust(pin: Pin | null | undefined, directoryKey: string): TrustState {
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
 * directory currently returns for them, and says whether the pin was `verified` ({@link ContactTrust}). Keyed by
 * userId. Pure, no write; requires an unlocked vault.
 */
export async function trustStates(
  contacts: ReadonlyArray<{ userId: string; directoryKey: string }>,
): Promise<Record<string, ContactTrust>> {
  const store = await loadStore()
  const out: Record<string, ContactTrust> = {}
  for (const c of contacts) {
    const pin = store.pins[c.userId]
    out[c.userId] = { state: foldTrust(pin, c.directoryKey), pinVerified: pin?.state === 'verified' }
  }
  return out
}
