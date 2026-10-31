// Identity pinning — the operator-blind, cross-device pin store and the one key check every wrap and every grant from
// another account goes through (en.account.identity-pinning, ADR 0028, ADR 0104, ADR 0111 decisions 9, 15 and 16).
//
// **A pin is a contact's key-chain head** (ADR 0028 Decision 2). A client pins the head of a contact's key
// chain the first time it meets them (trust on first use) together with the fingerprint of the key at that head. A later
// key is accepted only when the chain reaches it from that pinned head — every step signed by the identity AND the
// Secret-Key proof key it retires — and the pin then moves to the new head; anything else is an **anomaly**: never wrapped
// to, never accepted as a granter, never offered to a human to accept. The head anchors the identity and the proof key
// together: with the identity alone pinned, an operator could serve an invented prefix whose last step names the pinned
// identity beside a proof key of its own, and a thief holding that identity could then sign a step every member accepts.
//
// **One sealed entry per pin under a sealed head** (ADR 0104). A pin lives at an opaque id only this account can compute
// — `pinId = HMAC-SHA256(K_pinIndex, userId)`, `K_pinIndex` derived from the Master Key — sealed under the Master Key
// and bound to that id (`identity-pin`), its plaintext `{ u: userId, f: fingerprint, h: head, i: index, t: firstSeenAt,
// r: replaced }` (single-letter keys, so an entry with its head stays inside one padded length) padded to one length.
// There is no `verified` in it: a verification is a signed statement in the tenant (`tenantTrust.ts`, ADR 0111 decision
// 12), and the pin store is the device's counter only (decision 16). What a pin holds is
// the contact's FINGERPRINT HASH (`v1.fingerprintHash`, the digest the safety number and its QR already are), not their
// bundle, and the head of their chain. The HEAD of the store (`identity-pins`, bound to the store's revision) holds
// `{ version: 2, count, digest }`, the digest over every entry in `pinId` order — so a withheld, added, rolled-back or
// moved entry is refused exactly as a rolled-back store is (ADR 0028 Decision 7). The server sees opaque ids, sealed
// entries and the count.
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
import { accountIdentityPinsApi, keyChainApi } from '../../api/client'
import type { IdentityPinEntry, IdentityPinsWrite } from '../../api/generated/account'
import type { KeyChain } from '../../api/generated/scopes'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, isHttpStatus, HttpStatus } from '../../shared/httpError'
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
 *  (ADR 0104 decision 7 — veyq was not live), so anything else does not open. */
const HEAD_VERSION = 2

/** Every entry's plaintext is padded with spaces to exactly this many bytes, so any two contacts seal to one length
 *  (ADR 0104 decision 2; v1.json#identity_pin.entry_bytes). */
export const ENTRY_BYTES = 192

/** The listing's page size — the contract's maximum. */
const PAGE = 1000

/** The most entries a store holds (the contract's `put` bound): a listing that runs past it is no store this account
 *  wrote, and is refused rather than followed. */
const MAX_ENTRIES = 25_000

/** One pinned contact: the head of their key chain this account pinned (base64 of 32 bytes), the fingerprint hash of the
 *  identity public bundle at that head (base64 of 32 bytes, `fingerprintDigestB64`), the index of that head's step in the
 *  chain, first-seen, and whether a join record replaced a pin that
 *  was not on its chain (ADR 0111 decision 15: "the key you saw before wasn't theirs"). */
export interface Pin {
  fingerprint: string
  head: string
  index: number
  firstSeenAt: number
  replaced: boolean
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

/**
 * What a served key comes to against this account's pin (ADR 0028 Decision 2) — and, in a tenant, against the join
 * record that anchors the person there (ADR 0111 decisions 12 and 15, {@link Standing}):
 * - `firstUse` — no pin: the chain was read, its head is the served one and its newest key the served key; the head is
 *   pinned (trust on first use, ADR 0111 decision 15's unknown person);
 * - `match` — the served head is the pinned head and the served key the key at it;
 * - `advanced` — the chain walks from the pinned head to the served head, every step signed by both keys it retires,
 *   and ends at the served key: the pin moved to the new head;
 * - `replaced` — in a tenant only: the pinned head is not on the chain the person's join record anchors, and that chain
 *   is at least as long as the pinned one, so the pin was a key that was not theirs (an operator's at a first contact):
 *   the pin moves to the anchored head, marked `replaced`. A shorter chain is not this — it may be a rollback;
 * - `anomaly` — anything else, a served head older than the pinned one included (a rollback). Never wrapped to.
 */
export type Anchor = 'firstUse' | 'match' | 'advanced' | 'replaced' | 'anomaly'

/**
 * Where a person stands in one tenant before any key is judged (ADR 0111 decisions 12 and 15, `tenantTrust.ts`):
 * - `joined` — their join record opened, verifies owner → invite → newcomer, and its signer is an owner the server lists
 *   and this device anchors: the chain is walked from the identity and proof key it names;
 * - `root` — an owner the server lists who has no join record (the tenant's first owner): trusted on first use, by the pin;
 * - `unanchored` — anyone else: a non-owner without a join record (a membership the server made), a join record that does
 *   not verify, or no member at all. Never wrapped to;
 * - `unavailable` — this device could not read the tenant's directory or key: a wrap treats it as `unanchored`, the check
 *   of a grant's granter falls back to the pin alone.
 */
export type Standing =
  | { kind: 'joined'; identityPublicKey: Uint8Array; secretKeyProofPublicKey: Uint8Array }
  | { kind: 'root' }
  | { kind: 'unanchored' }
  | { kind: 'unavailable' }

/** One tenant's standings, read once and asked per person (`tenantTrust.ts` § `loadTenantView`). `pins` is the pass the
 *  read belongs to, when it is one ({@link PinPass}): every check judged with these standings reads the pin store
 *  through it. */
export interface TenantStandings {
  tenantId: string
  standing(userId: string): Promise<Standing>
  pins?: PinPass
}

/**
 * One pass of anchor checks over many people — a member list, Who's who (inbox #710): the pin store is read on the pass's
 * first check and not again. It lives as long as its caller keeps it — one call — so nothing a pass read reaches the next
 * one: each pass starts from the server's head, through this device's marks, as a single check does. Without a pass every
 * check reads the store itself and writes its own pin at once.
 *
 * **A pass writes once, at its end** (inbox #741): a check of the pass that pins someone records its change instead of
 * writing it — the pass reads it back at once, so every later check of the pass judges against it exactly as against a
 * pin written — and {@link flush} stores every recorded change in one revision-guarded write. On a `409 stale_revision`
 * that write re-reads the store and re-applies every change in order on top of it, as a single check's write re-applies
 * its own: a first sight claims only an empty slot and a move replaces only the pin it was judged against, so what a
 * concurrent device wrote stands. A check that ends in an anomaly records nothing. The caller flushes in a `finally`, so
 * a pass that fails midway still keeps what it pinned. Only for a pass whose answers are shown, never one that gates a
 * wrap: a wrap must not seal to a key whose pin is not stored yet.
 */
export class PinPass {
  /** The store as this pass last read or wrote it at the server, or null before the first read. */
  private base: OpenedStore | null = null
  /** The checks' changes not written yet, in the order they were made. */
  private pending: Array<(pins: PinMap) => void> = []

  /** The store as the pass's checks see it — {@link base} with every pending change applied; read on first use. A copy. */
  async read(): Promise<OpenedStore> {
    if (!this.base) this.base = await loadStore()
    const pins: PinMap = { ...this.base.pins }
    for (const apply of this.pending) apply(pins)
    return { revision: this.base.revision, pins, entries: new Map(this.base.entries) }
  }

  /** A check's change to the pins: recorded for {@link flush}, seen by the pass's next read. */
  record(apply: (pins: PinMap) => void): void {
    this.pending.push(apply)
  }

  /** Store every recorded change in one write ({@link mutateAndSave}: revision-guarded, re-based and re-applied on a `409
   *  stale_revision`; changes that change nothing send nothing). Throws what the write throws; the changes are not kept
   *  for a retry. */
  async flush(): Promise<void> {
    if (!this.base || this.pending.length === 0) return
    const changes = this.pending
    this.pending = []
    await mutateAndSave(this.base, (pins) => changes.forEach((apply) => apply(pins)), this)
  }

  /** A write of this pass landed, or its re-base re-read the store: the pass continues from `store`. */
  moved(store: OpenedStore): void {
    this.base = copyOf(store)
  }
}

/** Store a check's change: through its pass ({@link PinPass.record}, written at the pass's end), or at once. */
async function save(store: OpenedStore, apply: (pins: PinMap) => void, pass: PinPass | undefined): Promise<void> {
  if (pass) pass.record(apply)
  else await mutateAndSave(store, apply)
}

/** The store a check reads: its pass's, or a fresh read. */
const storeOf = (pass: PinPass | undefined): Promise<OpenedStore> => (pass ? pass.read() : loadStore())

/**
 * Thrown by a **UI-path** wrap site (the group add, its name key) when the contact's served key is an
 * {@link Anchor} `anomaly`: nothing is sealed to it, and there is no accept — the key returns only through its chain.
 * The human-less workers do not throw this; they decline or declare the member pending instead.
 */
export class IdentityUnanchoredError extends Error {
  constructor(public readonly userId: string) {
    super(`identity key not anchored for ${userId}`)
    this.name = 'IdentityUnanchoredError'
  }
}

/**
 * Thrown by a verification's write (`tenantTrust.ts` § `verifyPerson`) when the person's anchored head is no longer the
 * one the human just compared out-of-band — their key moved (a rotation the chain reached, or a concurrent device)
 * *between* the compare and the write. Nothing is signed for a head nobody checked; the caller re-runs the compare.
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
  // Key order and names are the vector's (v1.json#identity_pin): {u, f, h, i, t, r}.
  const json = encoder.encode(
    JSON.stringify({ u: userId, f: pin.fingerprint, h: pin.head, i: pin.index, t: pin.firstSeenAt, r: pin.replaced ? 1 : 0 }),
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
  const { u: userId, f: fingerprint, h: head, i: index, t: firstSeenAt, r: replaced } = e
  if (
    typeof userId !== 'string' ||
    typeof fingerprint !== 'string' ||
    fromB64std(fingerprint).length !== 32 ||
    typeof head !== 'string' ||
    fromB64std(head).length !== 32 ||
    !Number.isSafeInteger(index) ||
    (index as number) < 0 ||
    (replaced !== 0 && replaced !== 1) ||
    !Number.isSafeInteger(firstSeenAt) ||
    (firstSeenAt as number) < 0 ||
    hex(v1.identityPinId(indexKey, userId)) !== pinIdHex
  ) {
    throw new Error('identity pin entry: not the pin of its id')
  }
  return [userId, { fingerprint, head, index: index as number, firstSeenAt: firstSeenAt as number, replaced: replaced === 1 }]
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
  a === b ||
  (!!a &&
    !!b &&
    a.fingerprint === b.fingerprint &&
    a.head === b.head &&
    a.index === b.index &&
    a.firstSeenAt === b.firstSeenAt &&
    a.replaced === b.replaced)

/**
 * Apply a change to the pins and store it: the changed entries and a new head, revision-guarded. On a `409
 * stale_revision` (another device wrote first) re-read the store, **re-apply the same change on top of it** (`apply` is
 * written to be additive/idempotent, so a concurrent pin for another contact is preserved), and retry. Starts from an
 * already-loaded `base`; every re-base comes from {@link loadStore}, so the open before each write meets the same marks
 * and the same digest check as a read. A change that changes nothing writes nothing. A write that lands raises the mark
 * to the revision it stored, and the session keeps the store it wrote as verified.
 */
async function mutateAndSave(base: OpenedStore, apply: (pins: PinMap) => void, pass?: PinPass): Promise<void> {
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
      const written = { revision: store.revision + 1, pins: next, entries }
      remember(indexKey, written, setDigest(entries))
      pass?.moved(written)
      return
    } catch (e) {
      if (attempt + 1 < MAX_WRITE_ATTEMPTS && (await isStaleRevision(e))) {
        store = await loadStore() // re-base on the server's current store, then re-apply + retry
        pass?.moved(store)
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

// ── The key chain ────────────────────────────────────────────────────────────────────────────────────────────

/** A person's key chain as the server serves it, or null when it serves none to this account (`404`: no such person,
 *  or one who shares no tenant with it — ADR 0042). Any other failure is thrown: it says nothing about the key. */
export async function fetchKeyChain(userId: string): Promise<KeyChain | null> {
  try {
    return await withRefresh(() => keyChainApi().getKeyChain({ userId }))
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.notFound)) return null
    throw e
  }
}

/** What a served chain comes to against a pin: `anomaly`, or the anchor with the chain's head, the fingerprint of the key
 *  each step installs (index → fingerprint), and the server-observed time of the newest step when it is a rotation. */
export type ChainReading =
  | { anchor: 'anomaly' }
  | { anchor: Exclude<Anchor, 'anomaly'>; head: string; index: number; fingerprints: string[]; rotatedAt: Date | null }

type AnchoredReading = Exclude<ChainReading, { anchor: 'anomaly' }>

const ANOMALY: ChainReading = { anchor: 'anomaly' }

/** One served step, decoded; throws on a field that is no base64 (the caller reads that as an anomaly). Exported for
 *  the account's own rotation, which walks its own chain to name the step it appends (`rotation/rotateKeys.ts`). */
export function linkOf(step: KeyChain['steps'][number]): v1.KeyChainLink {
  const opt = (b: string | null | undefined) => (b == null ? null : fromB64std(b))
  return {
    index: step.index,
    previousHead: opt(step.previousHead),
    identityPublicKey: fromB64std(step.identityPublicKey),
    secretKeyProofPublicKey: fromB64std(step.secretKeyProofPublicKey),
    identitySignature: opt(step.identitySignature),
    proofSignature: opt(step.proofSignature),
  }
}

/** The fingerprint (base64) of a decoded identity bundle; '' for one that does not decode (it then equals no pin). */
function fingerprintOfBundle(bundle: Uint8Array): string {
  try {
    return b64std(v1.fingerprintHash(v1.deserializeIdentityPublic(bundle)))
  } catch {
    return ''
  }
}

/**
 * Walk a served chain from `pin`'s head (from its genesis for an unpinned contact) — pure, no fetch, no write. Every step
 * is checked by `verifyKeyChain` (both signatures under the keys the step retires, each predecessor the head the step
 * before makes); with a pin, the walk must pass through the pinned head, and the key at it must be the pinned
 * fingerprint. So the pinned head anchors the identity AND the proof key: a chain that names the pinned identity beside
 * another proof key — behind an invented prefix — never passes the pinned head and is an anomaly. A chain whose newest
 * head is older than the pinned one (a rollback) never reaches it either. The chain's own `head` field must be the head
 * its steps make. Never a throw: every byte here came from the server.
 */
export function readChain(pin: Pin | undefined, userId: string, chain: KeyChain): ChainReading {
  if (chain.userId !== userId) return ANOMALY
  let links: v1.KeyChainLink[]
  let from: Uint8Array | null = null
  try {
    links = chain.steps.map(linkOf)
    if (pin) from = fromB64std(pin.head)
  } catch {
    return ANOMALY
  }
  const walked = v1.verifyKeyChain({ userId, links }, from)
  if (!walked.ok) return ANOMALY
  const head = b64std(walked.head)
  if (head !== chain.head) return ANOMALY
  const fingerprints = links.map((l) => fingerprintOfBundle(l.identityPublicKey))
  if (pin && (walked.fromIndex === null || fingerprints[walked.fromIndex] !== pin.fingerprint)) return ANOMALY
  const anchor = !pin ? 'firstUse' : walked.fromIndex === walked.index ? 'match' : 'advanced'
  return { anchor, head, index: walked.index, fingerprints, rotatedAt: walked.index >= 1 ? chain.steps[walked.index].createdAt : null }
}

/** The head each step of a chain makes (index → base64 head). Only for links `verifyKeyChain` already accepted. */
function stepHeads(userId: string, links: readonly v1.KeyChainLink[]): string[] {
  return links.map((link, i) =>
    b64std(
      v1.keyChainHead(
        i === 0
          ? v1.keyChainGenesisStatement(userId, link)
          : v1.keyChainStepStatement({ userId, index: i, previousHead: link.previousHead!, retired: links[i - 1], installed: link }),
      ),
    ),
  )
}

const sameBytes = (a: Uint8Array, b: Uint8Array): boolean => a.length === b.length && a.every((x, i) => x === b[i])

/** A chain read from a join record: the reading, and the index of the step the person joined the tenant with — no key
 *  before it counts in that tenant. */
export type JoinedReading = { anchor: 'anomaly' } | (AnchoredReading & { joinedAt: number })

const JOINED_ANOMALY: JoinedReading = { anchor: 'anomaly' }

/**
 * Walk a served chain from the step a join record names (ADR 0111 decisions 12 and 15) — pure, no fetch, no write. The
 * whole chain must verify (`verifyKeyChain`), and one step must install exactly the identity AND proof key the join record
 * names: from there on every step is signed by keys the record anchors, so whatever prefix the server serves before it
 * is never trusted. The pin stays the counter (decision 16): a pinned head on the chain must carry the pinned key, and
 * the walk is `match` or `advanced` from it; a pinned head NOT on the chain is `replaced` when the chain is at least as
 * long as the pinned one was (the pin was never theirs — an operator's key at a first contact), and an `anomaly`
 * otherwise (a shorter chain may be a rollback of a head this account saw). Never a throw.
 */
export function readJoinedChain(
  pin: Pin | undefined, userId: string, chain: KeyChain, joined: Extract<Standing, { kind: 'joined' }>,
): JoinedReading {
  if (chain.userId !== userId) return JOINED_ANOMALY
  let links: v1.KeyChainLink[]
  try {
    links = chain.steps.map(linkOf)
  } catch {
    return JOINED_ANOMALY
  }
  const walked = v1.verifyKeyChain({ userId, links }, null)
  if (!walked.ok) return JOINED_ANOMALY
  const head = b64std(walked.head)
  if (head !== chain.head) return JOINED_ANOMALY
  const joinedAt = links.findIndex(
    (l) => sameBytes(l.identityPublicKey, joined.identityPublicKey) && sameBytes(l.secretKeyProofPublicKey, joined.secretKeyProofPublicKey),
  )
  if (joinedAt < 0) return JOINED_ANOMALY
  const fingerprints = links.map((l) => fingerprintOfBundle(l.identityPublicKey))
  const rotatedAt = walked.index >= 1 ? chain.steps[walked.index].createdAt : null
  const reading = { head, index: walked.index, fingerprints, rotatedAt, joinedAt }
  if (!pin) return { anchor: 'firstUse', ...reading }
  const at = stepHeads(userId, links).indexOf(pin.head)
  if (at >= 0) {
    if (fingerprints[at] !== pin.fingerprint) return JOINED_ANOMALY
    return { anchor: at === walked.index ? 'match' : 'advanced', ...reading }
  }
  return pin.index >= 0 && pin.index <= walked.index ? { anchor: 'replaced', ...reading } : JOINED_ANOMALY
}

/** Whether a reading ends exactly at what a directory served: the served head, and the served key at it. */
const endsAt = (r: ChainReading, served: ServedIdentity): r is AnchoredReading =>
  r.anchor !== 'anomaly' && r.head === served.keyChainHead && r.fingerprints.at(-1) === fingerprintOrNull(served.identityPublicKey)

/** The pin a reading moves to: the chain's head, its index and its newest key. A move keeps first-seen and the
 *  `replaced` mark; a replacement starts both afresh (the person is new to this account under the anchored key). */
const pinAtReading = (r: AnchoredReading, firstSeenAt: number, replaced = false): Pin => ({
  fingerprint: r.fingerprints.at(-1)!,
  head: r.head,
  index: r.index,
  firstSeenAt,
  replaced,
})

/** Store what a reading of `userId` concluded: a first sight claims an empty slot (first-online-wins — a device that
 *  pinned the contact meanwhile keeps its pin), a move replaces only the pin it was judged against (a device that moved
 *  it meanwhile keeps its move). `match` writes nothing. */
function recordReading(pins: PinMap, userId: string, judgedFrom: Pin | undefined, r: AnchoredReading): void {
  const cur = pins[userId]
  if (r.anchor === 'firstUse' && !cur) pins[userId] = pinAtReading(r, Date.now())
  if (r.anchor === 'advanced' && judgedFrom && (!cur || cur.head === judgedFrom.head)) {
    pins[userId] = pinAtReading(r, judgedFrom.firstSeenAt, judgedFrom.replaced)
  }
  if (r.anchor === 'replaced' && judgedFrom && (!cur || cur.head === judgedFrom.head)) {
    pins[userId] = pinAtReading(r, Date.now(), true)
  }
}

// ── The operations ───────────────────────────────────────────────────────────────────────────────────────────

/** A contact's key as a directory read serves it to a check: the identity bundle and the key-chain head beside it
 *  (`ScopeMember`, `ResolvedGroupCandidate`, `GrantDeliveryTarget`, `OpenInvite`). */
export interface ServedIdentity {
  identityPublicKey: string
  keyChainHead: string
}

/**
 * The one check before a wrap to another account's key, and before trusting a key another account signed with
 * (ADR 0028 Decision 2; it replaces the fingerprint-only `checkAndPin`). Answers {@link Anchor}:
 * `match` is local (no fetch); the chain is read only when the served head differs from the pinned one, or on first use;
 * `firstUse` and `advanced` move the pin. Never accepts a prefix the server supplies: the walk starts at the pinned head.
 *
 * **`tenant`** (ADR 0111 decisions 12 and 15): the wrap is in that tenant, so the person's standing there decides first —
 * `unanchored` and `unavailable` are an `anomaly` with no fetch; a `joined` person's chain is always read and walked from
 * the step their join record names ({@link readJoinedChain}), the pin kept as the counter; a `root` owner is judged by
 * the pin alone, as without a tenant. Without `tenant` (an invite's accept, a moderation delivery — no tenant the two
 * share) the pin alone decides.
 *
 * A key this device cannot decode is never pinned; a chain the server does not serve is an anomaly. Requires an
 * unlocked vault; throws `TrustStateRefused` when this device refuses its own pin store.
 */
export async function anchorIdentity(
  userId: string, served: ServedIdentity, opts: { tenant?: TenantStandings } = {},
): Promise<Anchor> {
  const standing = opts.tenant ? await opts.tenant.standing(userId) : null
  if (standing && (standing.kind === 'unanchored' || standing.kind === 'unavailable')) return 'anomaly'
  const pass = opts.tenant?.pins
  const store = await storeOf(pass)
  const pin = store.pins[userId]
  const servedFingerprint = fingerprintOrNull(served.identityPublicKey)
  if (servedFingerprint === null) return 'anomaly'
  if (standing?.kind === 'joined') {
    const chain = await fetchKeyChain(userId)
    if (!chain) return 'anomaly'
    const reading = readJoinedChain(pin, userId, chain, standing)
    if (!endsAt(reading, served)) return 'anomaly'
    await save(store, (pins) => recordReading(pins, userId, pin, reading), pass)
    return reading.anchor
  }
  if (pin && pin.head === served.keyChainHead) return pin.fingerprint === servedFingerprint ? 'match' : 'anomaly'
  const chain = await fetchKeyChain(userId)
  if (!chain) return 'anomaly'
  const reading = readChain(pin, userId, chain)
  if (!endsAt(reading, served)) return 'anomaly'
  await save(store, (pins) => recordReading(pins, userId, pin, reading), pass)
  return reading.anchor
}

/** A key the server served with no head beside it — the per-grant snapshot of a granter's key, a pending moderator's —
 *  judged against the chain: its {@link Anchor}, and whether the key is a RETIRED key of that chain (an earlier step's,
 *  not the newest). */
export type ChainKeyAnchor = { anchor: Anchor; retired: boolean }

/**
 * {@link anchorIdentity} for a key served without a head (ADR 0028 Decision 2: the per-grant snapshot gets
 * none, it may be a retired key). The pinned key itself is `match` with no fetch. Any other key is looked up in the
 * contact's chain, walked from the pinned head as {@link anchorIdentity} walks it: the newest key is `firstUse` or
 * `advanced` (the pin moves as there), an earlier key of the chain is answered with `retired` — the caller decides what
 * a retired key may still do — and a key the chain does not hold, a chain that does not verify from the pinned head, or
 * one the server does not serve, is an `anomaly`.
 *
 * With `tenant`, the person's standing there decides first, as for {@link anchorIdentity}: `unanchored` is an anomaly; a
 * `joined` person's chain is read from their join record's step, and only a key at or after that step counts; a `root`
 * owner, and a tenant this device could not read (`unavailable` — a grant in a tenant whose directory it cannot open),
 * are judged by the pin alone.
 */
export async function anchorChainKey(
  userId: string, identityPublicKeyB64: string, opts: { tenant?: TenantStandings } = {},
): Promise<ChainKeyAnchor> {
  const unanchored: ChainKeyAnchor = { anchor: 'anomaly', retired: false }
  const standing = opts.tenant ? await opts.tenant.standing(userId) : null
  if (standing?.kind === 'unanchored') return unanchored
  const pass = opts.tenant?.pins
  const store = await storeOf(pass)
  const pin = store.pins[userId]
  const fingerprint = fingerprintOrNull(identityPublicKeyB64)
  if (fingerprint === null) return unanchored
  if (standing?.kind === 'joined') {
    const chain = await fetchKeyChain(userId)
    if (!chain) return unanchored
    const reading = readJoinedChain(pin, userId, chain, standing)
    if (reading.anchor === 'anomaly') return unanchored
    const at = reading.fingerprints.lastIndexOf(fingerprint)
    if (at < reading.joinedAt) return unanchored
    await save(store, (pins) => recordReading(pins, userId, pin, reading), pass)
    return { anchor: reading.anchor, retired: at < reading.fingerprints.length - 1 }
  }
  if (pin && pin.fingerprint === fingerprint) return { anchor: 'match', retired: false }
  const chain = await fetchKeyChain(userId)
  if (!chain) return unanchored
  const reading = readChain(pin, userId, chain)
  if (reading.anchor === 'anomaly') return unanchored
  const at = reading.fingerprints.lastIndexOf(fingerprint)
  if (at < 0) return unanchored
  await save(store, (pins) => recordReading(pins, userId, pin, reading), pass)
  return { anchor: reading.anchor, retired: at < reading.fingerprints.length - 1 }
}

/**
 * The inviting owner of a member invite, judged by the key chain the invite serves beside it (ADR 0111
 * decision 19) — the accept's one anchor check, where the account is not yet a co-member and the server serves it
 * no chain through `getKeyChain`. The caller passes the chain as `checkInvite` (`@veyq/crypto/invite`) walked it: from the head inside
 * the link to the served owner key, so `chain.head` is that walk's end and `served` its key. Here the pin decides, as
 * {@link readChain} reads it: a pinned head must lie on the chain and carry the pinned key (else `anomaly`), and a first
 * use pins the walked head at its real index. Nothing is fetched; a reading that does not end at `served` is an anomaly.
 * Requires an unlocked vault; throws `TrustStateRefused` when this device refuses its own pin store.
 */
export async function anchorServedChain(userId: string, chain: KeyChain, served: ServedIdentity): Promise<Anchor> {
  const store = await loadStore()
  const pin = store.pins[userId]
  const reading = readChain(pin, userId, chain)
  if (!endsAt(reading, served)) return 'anomaly'
  await mutateAndSave(store, (pins) => recordReading(pins, userId, pin, reading))
  return reading.anchor
}

/**
 * The pin store as one whole document (`trustMarks.ts` § `AccountDocumentStore`), for the two operations that
 * act on it whole:
 *
 *  - **key rotation** (ADR 0060 § Consequences) opens it under the OLD Master Key and re-seals it under the new one inside
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

/** Read a contact's current pin (or null), for a UI surface that shows trust state — through `pass` when the read is part
 *  of one ({@link PinPass}). No write. */
export async function getPin(userId: string, pass?: PinPass): Promise<Pin | null> {
  const store = await storeOf(pass)
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
