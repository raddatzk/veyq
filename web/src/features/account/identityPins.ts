// Identity pinning — the operator-blind, cross-device TOFU pin store (en.account.identity-pinning, ADR 0028, ADR 0104).
// A client remembers each contact's identity key the first time it wraps to them, so a later *changed* key (an active
// operator's MITM, or a legitimate rotation) is surfaced, never silently trusted.
//
// **One sealed entry per pin under a sealed head** (ADR 0104). A pin lives at an opaque id only this account can compute
// — `pinId = HMAC-SHA256(K_pinIndex, userId)`, `K_pinIndex` derived from the Master Key — sealed under the Master Key
// and bound to that id (`identity-pin`), its plaintext `{ userId, fingerprint, state, firstSeenAt }` padded to one
// length. What a pin holds is the contact's FINGERPRINT HASH (`v1.fingerprintHash`, the digest the safety number and its
// QR already are), not their bundle: every reader of a pin is an equality check or a safety number, both functions of
// the hash (ADR 0104 decision 3). The HEAD (`identity-pins`, bound to the store's revision) holds `{ version: 2, count,
// digest }`, the digest over every entry in `pinId` order — so a withheld, added, rolled-back or moved entry is refused
// exactly as a rolled-back store is (ADR 0028 Decision 7). The server sees opaque ids, sealed entries and the count.
//
// **Every open goes through this device's trust-state marks** (`trustMarks.ts`): the head is opened under them like any
// sealed account document — refused if older than one this device held, missing after it held one, or unreadable — and
// the entries are then checked against the head's digest. A mismatch while the head stayed put is a rollback; a head
// that moved meanwhile is read again. The entries are listed once per head revision and kept for the session; a later
// read with the same head is the head alone.
//
// **Every write is one compare-and-set** on the head's revision: the changed entries plus a new head, or — for the reset
// and the rotation — the whole set with `replaceAll`. A `409 stale_revision` re-reads and re-applies, bounded.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountIdentityPinsApi, tasksApi } from '../../api/client'
import type { IdentityPinEntry, IdentityPinsWrite } from '../../api/generated/account'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { masterKey, identityPrivate } from '../../app/vault'
import { fingerprintDigestB64 } from './safetyCode'
import {
  openAccountDocument,
  raiseMarks,
  servedDocument,
  TrustStateRefused,
  type AccountDocumentStore,
  type ServedDocument,
} from './trustMarks'

/** The head's seal context at a stored `revision` (ADR 0072, vector `seal.sym.identity-pins`): bound to the revision
 *  the server stores it at, so a head served under another revision does not open. */
const headContext = (revision: number) => ({ purpose: 'identity-pins', revision }) as const

/** An entry's seal context (vector `seal.sym.identity-pin`): bound to the id it is stored at, so an entry the server
 *  moves to another contact's slot does not open. */
const entryContext = (pinId: Uint8Array) => ({ purpose: 'identity-pin', pinId }) as const

/** The head's plaintext version (v1.json#identity_pin). There is no other: the single-blob store had no read path kept
 *  (owner decision Q3 of cr-identity-pins-per-entry — veyq was not live), so anything else does not open. */
const HEAD_VERSION = 2

/** Every entry's plaintext is padded with spaces to exactly this many bytes, so `pinned` and `verified` (and any two
 *  contacts) seal to one length (ADR 0104 decision 2; v1.json#identity_pin.entry_bytes). */
export const ENTRY_BYTES = 192

/** The listing's page size — the contract's maximum. */
const PAGE = 1000

/** The most entries a store holds (the contract's `put` bound): a listing that runs past it is no store this account
 *  wrote, and is refused rather than followed. */
const MAX_ENTRIES = 25_000

/** A pin's trust state. `pinned` = trust-on-first-use; `verified` = an out-of-band safety-number compare succeeded (the
 *  ceremony on a member's profile, ADR 0028 Decision 3). */
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

/** One pinned contact: the fingerprint hash of their identity public bundle (base64 of 32 bytes, `fingerprintDigestB64`),
 *  the trust state, and first-seen. */
export interface Pin {
  fingerprint: string
  state: PinState
  firstSeenAt: number
}

type PinMap = Record<string, Pin>

/** The store's one write as this module sends it — the contract's body, its revision always given. */
export type PinStoreWrite = Omit<IdentityPinsWrite, 'revision'> & { revision: number }

/** The opened store: the head's `revision` (for the next guarded write), the pins by userId, and the entries as stored
 *  (hex pinId → sealed entry, base64) — what the next write's digest is computed over without re-sealing the rest. */
interface OpenedStore {
  revision: number
  pins: PinMap
  entries: Map<string, string>
}

/** The opened head. */
interface Head {
  count: number
  digest: string
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
 * Thrown by {@link markVerified} when the pinned fingerprint no longer equals the one the human just compared
 * out-of-band — the contact's key moved (a rotation, or a concurrent-device accept) *between* the compare and the write.
 * The ceremony must not stamp `verified` onto a key nobody actually checked; the caller re-runs the compare.
 */
export class IdentityMovedError extends Error {
  constructor(public readonly userId: string) {
    super(`identity key moved for ${userId} — re-verify`)
    this.name = 'IdentityMovedError'
  }
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Bounds the re-read/re-apply/retry loop on a `409 stale_revision` (a concurrent device write), and the re-reads of a
 *  head that keeps moving under a listing. */
const MAX_WRITE_ATTEMPTS = 5

const hex = (b: Uint8Array): string => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const HEX_ID = /^[0-9a-f]{64}$/
const unhexId = (h: string): Uint8Array => Uint8Array.from(h.match(/../g)!, (b) => parseInt(b, 16))

/** The fingerprint a pin compares against, for a served identity bundle — or null for a bundle this device cannot
 *  decode, which then matches no pin (a garbled key is never trusted). */
function fingerprintOrNull(identityPublicKeyB64: string): string | null {
  try {
    return fingerprintDigestB64(identityPublicKeyB64)
  } catch {
    return null
  }
}

// ── The head ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The digest over a set of stored entries (hex pinId → base64 entry), base64. */
function setDigest(entries: Map<string, string>): string {
  return b64std(v1.identityPinSetDigest([...entries].map(([id, e]) => ({ pinId: unhexId(id), entry: fromB64std(e) }))))
}

function sealHead(mk: Uint8Array, entries: Map<string, string>, storedRevision: number): string {
  const json = JSON.stringify({ version: HEAD_VERSION, count: entries.size, digest: setDigest(entries) })
  return b64std(v1.seal(headContext(storedRevision), mk, encoder.encode(json)))
}

/** Open a served head. Throws on anything that is not a version-2 head this client wrote — the caller refuses it as
 *  unreadable (ADR 0028 Decision 7). */
function unsealHead(mk: Uint8Array, blobB64: string, revision: number): Head {
  const h = JSON.parse(decoder.decode(v1.open(headContext(revision), mk, fromB64std(blobB64)))) as Record<string, unknown>
  if (
    h?.version !== HEAD_VERSION ||
    !Number.isSafeInteger(h.count) ||
    (h.count as number) < 0 ||
    typeof h.digest !== 'string' ||
    fromB64std(h.digest).length !== 32
  ) {
    throw new Error('identity_pins_unreadable')
  }
  return { count: h.count as number, digest: h.digest }
}

/** The head of a store never written: no entries. */
const emptyHead = (): Head => ({ count: 0, digest: setDigest(new Map()) })

// ── The entries ──────────────────────────────────────────────────────────────────────────────────────────────

function sealEntry(mk: Uint8Array, pinId: Uint8Array, userId: string, pin: Pin): string {
  const json = encoder.encode(
    JSON.stringify({ userId, fingerprint: pin.fingerprint, state: pin.state, firstSeenAt: pin.firstSeenAt }),
  )
  if (json.length > ENTRY_BYTES) throw new Error('identity pin entry: over its padded length')
  const padded = new Uint8Array(ENTRY_BYTES).fill(0x20)
  padded.set(json)
  return b64std(v1.seal(entryContext(pinId), mk, padded))
}

/** Open one entry at the id it was listed under, and check it is the pin OF that id: its `userId` must derive the id
 *  under this account's index key, so an entry copied from one contact's slot to another's is refused even where the
 *  seal would allow it. Throws on anything else. */
function openEntry(mk: Uint8Array, indexKey: Uint8Array, pinIdHex: string, entryB64: string): [string, Pin] {
  const pinId = unhexId(pinIdHex)
  const plain = v1.open(entryContext(pinId), mk, fromB64std(entryB64))
  if (plain.length !== ENTRY_BYTES) throw new Error('identity pin entry: not its padded length')
  const e = JSON.parse(decoder.decode(plain)) as Record<string, unknown>
  const { userId, fingerprint, state, firstSeenAt } = e
  if (
    typeof userId !== 'string' ||
    typeof fingerprint !== 'string' ||
    fromB64std(fingerprint).length !== 32 ||
    (state !== 'pinned' && state !== 'verified') ||
    !Number.isSafeInteger(firstSeenAt) ||
    (firstSeenAt as number) < 0 ||
    hex(v1.identityPinId(indexKey, userId)) !== pinIdHex
  ) {
    throw new Error('identity pin entry: not the pin of its id')
  }
  return [userId, { fingerprint, state, firstSeenAt: firstSeenAt as number }]
}

/** Every listed entry, page by page. A listing that does not advance or runs past the store's bound is refused as
 *  unreadable — it is no store this account wrote, and following it would never end. */
async function listEntries(): Promise<Map<string, string>> {
  const out = new Map<string, string>()
  let after: string | undefined
  for (;;) {
    const page = await withRefresh(() => accountIdentityPinsApi().listIdentityPinEntries({ after, limit: PAGE }))
    for (const e of page.entries) {
      if (!HEX_ID.test(e.pinId) || (after !== undefined && e.pinId <= after) || out.has(e.pinId)) {
        throw new TrustStateRefused('trust_state_unreadable', 'pins')
      }
      out.set(e.pinId, e.entry)
      after = e.pinId
    }
    if (out.size > MAX_ENTRIES || (page.more && page.entries.length === 0)) {
      throw new TrustStateRefused('trust_state_unreadable', 'pins')
    }
    if (!page.more) return out
  }
}

// ── Reading ──────────────────────────────────────────────────────────────────────────────────────────────────

/** The store this session last verified — keyed by a one-way tag of the account's index key, so another account's
 *  store is never served from it — reused while the head's revision and digest are the ones it was verified under. */
let verified: { slot: string; revision: number; digest: string; store: OpenedStore } | null = null

const slotOf = (indexKey: Uint8Array): string => b64std(v1.sha256Digest(indexKey))

function remember(indexKey: Uint8Array, store: OpenedStore, digest: string): void {
  verified = { slot: slotOf(indexKey), revision: store.revision, digest, store }
}

const copyOf = (s: OpenedStore): OpenedStore => ({ revision: s.revision, pins: { ...s.pins }, entries: new Map(s.entries) })

/** Forget the store this session verified — the next read lists the entries again. For tests, and for nothing else:
 *  a stale cache entry is never served (it is keyed by the head it was verified under). */
export function forgetVerifiedPins(): void {
  verified = null
}

const fetchHead = async (): Promise<ServedDocument> =>
  servedDocument(await withRefresh(() => accountIdentityPinsApi().getIdentityPins()))

/** The entries checked against the head: the digest and count first — a mismatch is `null`, for the caller to tell a
 *  rollback from a head that moved — and then every entry opened as the pin of its id (else unreadable). */
function verifyEntries(mk: Uint8Array, indexKey: Uint8Array, revision: number, head: Head, listed: Map<string, string>): OpenedStore | null {
  let digest: string
  try {
    digest = setDigest(listed)
  } catch {
    return null
  }
  if (digest !== head.digest || listed.size !== head.count) return null
  const pins: PinMap = {}
  try {
    for (const [id, entry] of listed) {
      // No contact can appear twice: each id is a function of its userId, and the listing holds each id once.
      const [userId, pin] = openEntry(mk, indexKey, id, entry)
      pins[userId] = pin
    }
  } catch {
    throw new TrustStateRefused('trust_state_unreadable', 'pins')
  }
  return { revision, pins, entries: listed }
}

/**
 * Read and verify the store under `mk`, starting from a served head (fetched when not given). The head opens through
 * this device's marks; the entries are listed (unless this session already verified this very head) and checked against
 * it. A mismatch with the head unmoved is `trust_state_rolled_back` — the server withheld, added, rolled back or moved
 * an entry; a head that moved meanwhile is read again, bounded.
 */
async function readStore(mk: Uint8Array, first?: ServedDocument): Promise<OpenedStore> {
  const indexKey = v1.identityPinIndexKey(mk)
  let served = first ?? (await fetchHead())
  for (let attempt = 1; ; attempt++) {
    const head = await openAccountDocument(
      'pins', mk, served, (blob) => unsealHead(mk, blob, served.revision), emptyHead,
    )
    const cached = verified
    if (cached && cached.slot === slotOf(indexKey) && cached.revision === served.revision && cached.digest === head.digest) {
      return copyOf(cached.store)
    }
    const store = verifyEntries(mk, indexKey, served.revision, head, await listEntries())
    if (store) {
      remember(indexKey, store, head.digest)
      return copyOf(store)
    }
    const now = await fetchHead()
    if (now.revision === served.revision) throw new TrustStateRefused('trust_state_rolled_back', 'pins')
    if (attempt >= MAX_WRITE_ATTEMPTS) throw new Error('identity_pins_unsettled')
    served = now
  }
}

/** Fetch, open and verify the caller's pin store (born-empty: revision 0, no pins). Requires an unlocked vault. Throws
 *  `TrustStateRefused` for a store this device must not accept. */
async function loadStore(): Promise<OpenedStore> {
  const mk = masterKey()
  if (!mk) throw new Error('identity pin store requires an unlocked vault')
  try {
    return await readStore(mk)
  } finally {
    mk.fill(0)
  }
}

// ── Writing ──────────────────────────────────────────────────────────────────────────────────────────────────

/** Whether a failed call is a `409 stale_revision` (a concurrent device advanced the store under our write). */
function isStaleRevision(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_revision')
}

const samePin = (a: Pin | undefined, b: Pin | undefined): boolean =>
  a === b || (!!a && !!b && a.fingerprint === b.fingerprint && a.state === b.state && a.firstSeenAt === b.firstSeenAt)

/**
 * Apply a change to the pins and store it: the changed entries and a new head, revision-guarded. On a `409
 * stale_revision` (another device wrote first) re-read the store, **re-apply the same change on top of it** (`apply` is
 * written to be additive/idempotent, so a concurrent pin for another contact is preserved), and retry. Starts from an
 * already-loaded `base`; every re-base comes from {@link loadStore}, so the open before each write meets the same marks
 * and the same digest check as a read. A change that changes nothing writes nothing. A write that lands raises the mark
 * to the revision it stored, and the session keeps the store it wrote as verified.
 */
async function mutateAndSave(base: OpenedStore, apply: (pins: PinMap) => void): Promise<void> {
  let store = base
  for (let attempt = 0; ; attempt++) {
    const next: PinMap = { ...store.pins }
    apply(next)
    const changed = Object.keys(next).filter((userId) => !samePin(store.pins[userId], next[userId]))
    const removed = Object.keys(store.pins).filter((userId) => !Object.hasOwn(next, userId))
    if (changed.length === 0 && removed.length === 0) return
    const mk = masterKey()
    if (!mk) throw new Error('identity pin store requires an unlocked vault')
    try {
      const indexKey = v1.identityPinIndexKey(mk)
      const entries = new Map(store.entries)
      const put: IdentityPinEntry[] = changed.map((userId) => {
        const pinId = v1.identityPinId(indexKey, userId)
        const entry = sealEntry(mk, pinId, userId, next[userId])
        entries.set(hex(pinId), entry)
        return { pinId: hex(pinId), entry }
      })
      const del = removed.map((userId) => hex(v1.identityPinId(indexKey, userId)))
      del.forEach((id) => entries.delete(id))
      const head = sealHead(mk, entries, store.revision + 1)
      await withRefresh(() =>
        accountIdentityPinsApi().writeIdentityPins({
          identityPinsWrite: { revision: store.revision, head, put, remove: del, replaceAll: false },
        }),
      )
      await raiseMarks(mk, { pins: store.revision + 1 })
      remember(indexKey, { revision: store.revision + 1, pins: next, entries }, setDigest(entries))
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

/** The whole set sealed under `mk` as one write that replaces every stored entry — ids derived under `mk`'s index key,
 *  every entry re-sealed, the head for `storedRevision`. The rotation (a new Master Key, so new ids) and the reset (the
 *  empty set) write this. */
function sealWholeSet(mk: Uint8Array, pins: PinMap, storedRevision: number): Omit<PinStoreWrite, 'revision'> {
  const indexKey = v1.identityPinIndexKey(mk)
  const entries = new Map<string, string>()
  const put = Object.entries(pins).map(([userId, pin]) => {
    const pinId = v1.identityPinId(indexKey, userId)
    const entry = sealEntry(mk, pinId, userId, pin)
    entries.set(hex(pinId), entry)
    return { pinId: hex(pinId), entry }
  })
  return { head: sealHead(mk, entries, storedRevision), put, remove: [], replaceAll: true }
}

// ── The operations ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * TOFU check before a foreign-identity wrap: compare the directory-returned `identityPublicKeyB64` for `userId`
 * against the pin store, by fingerprint.
 * - **no pin** → pin it (`state: pinned`) and return `firstUse` — the caller proceeds with the wrap (trust-on-first-use).
 * - **pin matches** → return `match` — proceed.
 * - **pin differs** → return `changed` — the caller must NOT silently wrap (the worker declines the job; a UI path
 *   surfaces a notice + accept → re-pin). The store is left untouched.
 * A first-use key this device cannot decode is never pinned: it throws, as the wrap to it would.
 * Requires an unlocked vault.
 */
export async function checkAndPin(userId: string, identityPublicKeyB64: string): Promise<CheckResult> {
  const store = await loadStore()
  const existing = store.pins[userId]
  if (existing) return existing.fingerprint === fingerprintOrNull(identityPublicKeyB64) ? 'match' : 'changed'
  const fingerprint = fingerprintDigestB64(identityPublicKeyB64)
  await mutateAndSave(store, (pins) => {
    // Additive union-merge: only claim the slot if still unpinned after a possible re-base (first-online-wins;
    // a concurrent device that pinned this same contact first keeps its pin).
    if (!pins[userId]) pins[userId] = { fingerprint, state: 'pinned', firstSeenAt: Date.now() }
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
  const fingerprint = fingerprintDigestB64(identityPublicKeyB64)
  const store = await loadStore()
  await mutateAndSave(store, (pins) => {
    const prior = pins[userId]
    pins[userId] = { fingerprint, state: 'pinned', firstSeenAt: prior?.firstSeenAt ?? Date.now() }
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
 *    under a retired key. Both keys are explicit, because mid-rotation the vault still holds the old one. A new Master
 *    Key is a new index key, so every entry moves to a new id: the write replaces the whole set (ADR 0104). The pins
 *    cross verbatim: rotating THIS account's identity says nothing about anybody else's key;
 *  - **the user's reset** (`trustReset/trustReset.ts`) writes its `empty()` when this device refuses the store.
 *
 * `open` is the whole read — the head through the marks, the entries against its digest — so both meet every refusal a
 * read does.
 */
export const pinStoreDocument: AccountDocumentStore<PinMap, PinStoreWrite> = {
  document: 'pins',
  fetch: fetchHead,
  open: async (mk, served) => (await readStore(mk, served)).pins,
  seal: sealWholeSet,
  empty: (): PinMap => ({}),
  write: async (identityPinsWrite) => {
    await withRefresh(() => accountIdentityPinsApi().writeIdentityPins({ identityPinsWrite }))
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
 * vector-pinned). The input is the base64 identity bundle the directory returns.
 */
export function safetyNumber(identityPublicKeyB64: string): string {
  return v1.fingerprint(v1.deserializeIdentityPublic(fromB64std(identityPublicKeyB64)))
}

/** The safety number of a stored fingerprint — a pin's — rendered from the hash alone (`v1.fingerprintDigits`), so the
 *  number a pin stands for needs no bundle (ADR 0104 decision 3). */
export function safetyNumberOfFingerprint(fingerprintB64: string): string {
  return v1.fingerprintDigits(fromB64std(fingerprintB64))
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
 * ADR 0028). The compare is over a fingerprint (the pin's, or the directory key's for an unpinned contact), and so is
 * the guard: only stamps `verified` if the **current** pin's fingerprint still equals `verifiedFingerprint` — both at
 * load and after any 409 re-base — else throws {@link IdentityMovedError} (the key moved under the compare; nothing is
 * written). `verified` wins a same-contact merge: the re-applied upgrade re-asserts `verified` on top of a concurrent
 * device's `pinned`. Requires an unlocked vault.
 *
 * `pinIfUnpinned` (ADR 0028 Decision 3): the compare was against the DIRECTORY's key because there was no pin, so a
 * contact still unpinned at the write is pinned with the compared fingerprint, already `verified` - in the same one
 * sealed write, never a pin without the verification. A pin that appeared meanwhile (a concurrent device, the delivery
 * worker's TOFU) is held to the same guard: its fingerprint must be the compared one.
 */
export async function markVerified(
  userId: string,
  verifiedFingerprint: string,
  opts: { pinIfUnpinned?: boolean } = {},
): Promise<void> {
  const store = await loadStore()
  await mutateAndSave(store, (pins) => {
    const prior = pins[userId]
    if (!prior && opts.pinIfUnpinned) {
      pins[userId] = { fingerprint: verifiedFingerprint, state: 'verified', firstSeenAt: Date.now() }
      return
    }
    if (!prior || prior.fingerprint !== verifiedFingerprint) throw new IdentityMovedError(userId)
    pins[userId] = { ...prior, state: 'verified' }
  })
}

/**
 * The **badge read** — fold one pin against a contact's *current* directory key.
 * - no pin → `unpinned`
 * - pin fingerprint ≠ the directory key's (or a key this device cannot decode) → `changed` (surfaced-not-trusted;
 *   wrap/delivery stays gated until reviewed)
 * - pin fingerprint = the directory key's → `verified` if the pin was verified out-of-band, else `pinned`
 * Exported for a surface that already holds the pin ({@link getPin}) - the profile's compare - so it folds without a
 * second store fetch.
 */
export function foldTrust(pin: Pin | null | undefined, directoryKey: string): TrustState {
  if (!pin) return 'unpinned'
  if (pin.fingerprint !== fingerprintOrNull(directoryKey)) return 'changed'
  return pin.state === 'verified' ? 'verified' : 'pinned'
}

/** The badge read for a single contact (pure, no write). */
export async function trustState(userId: string, directoryKey: string): Promise<TrustState> {
  return foldTrust((await getPin(userId)) ?? undefined, directoryKey)
}

/**
 * Batch badge read for a whole member directory in **one** store read — folds every contact against the key the
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
