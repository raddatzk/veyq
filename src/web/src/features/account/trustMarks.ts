// This device's trust-state marks (ADR 0028 Decision 7, ADR 0024 Decision 6's floor): the highest pin-store
// revision, device-register revision and per-scope epoch it has held, so a server cannot hand it an older copy of
// state it keeps on the account's behalf. The server decides which copy a device reads; binding the revision into
// the seal (ADR 0072) makes it authentic, and only a local memory of what was already held makes it fresh.
//
// **Local, never synced.** A synced mark would be one more thing the server can roll back. The one way a mark
// leaves this device is inside the MAC-verified pairing envelope, to a device this one links (ADR 0059).
//
// **Keyed by the account's Master Key, never by anything the server names.** The slot is a one-way derivation of
// the Master Key, so the marks of two accounts on one browser never meet, and a server cannot steer a device to
// an empty slot by reporting a different account id — the lever a userId-keyed slot would hand it. The cost is
// that an account-key rotation moves the account to a new slot: the rotating device carries its marks across
// ({@link carryMarks}); every other device must unlock afresh — with the password and Secret Key it starts without
// marks (ADR 0028 Decision 7's named no-mark case), linked again it starts from its linker's.
//
// **Not a secret, so it survives sign-out** — only the session's convenience tiers are dropped there. One path
// deletes them: erasing the account (uc.account.erase-account, `erasure/eraseAccount.ts`) takes its slot with it
// ({@link forgetMarks}), the browser that erased clearing its own records for the account (ADR 0101 decision 4). The
// one wholesale wipe (`forgetAllTrust`) has no caller; whatever caller it gains must take the marks with it too.
// Losing them reopens the no-mark case and breaks nothing.
//
// **Best-effort storage, fail-closed reading.** A browser with no IndexedDB (or a denied one) holds no marks, which
// is again the no-mark case; a write that does not land leaves the mark lower, which is only ever safer for the
// server and never locks anything. The refusals are the other half: they are decided here, and never degrade.
//
// **Beside each floor, the key it stands for** (ADR 0111 decision 23). A floor is a number, and a number does not tell a
// later epoch that links to the key this device held from one an operator planted. So the device also keeps, per scope, a
// commitment to the newest epoch key it held ({@link holdEpochKey}), and a grant for a later epoch opens only if its chain
// peels down to that key (`spaces/scopes.ts`). A commitment is a labelled one-way derivation of a random 32-byte key: it
// says nothing about the key, so it is no secret either. It is never carried by pairing ({@link readMarks} leaves it
// out): the envelope that carries the marks is capped, and a linked device starts without commitments - the named
// no-mark case of a device unlocked afresh.
//
// **Marks only rise, with one exception, and it is a person's.** A refused document's way back is the reset the
// user confirms (ADR 0028 Decision 7): it writes the empty document and SETS the mark to the revision it wrote
// ({@link setMark}). No other path lowers a mark, and no server response reaches that one.
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { idbRun } from '../../shared/idb'
import { masterKey } from '../../app/vault'

const DB = 'veyq-trust-marks'
const STORE = 'marks'
const SLOT_INFO = v1.utf8('veyq/v1/trust-marks-slot')

/** The two sealed account documents a mark tracks. */
export type AccountDocument = 'pins' | 'register'

/** Everything this device remembers for one account. `epochs` is keyed by scope id. */
export type TrustMarks = { pins: number; register: number; epochs: Record<string, number> }

/** The newest epoch key this device held of one scope, as a commitment ({@link epochKeyCommitment}) at its epoch. */
export type HeldEpochKey = { epoch: number; commitment: string }

/** A partial raise — each value is merged as a maximum, never as an assignment; a held key by its epoch. */
export type MarksRaise = { pins?: number; register?: number; epochs?: Record<string, number>; held?: Record<string, HeldEpochKey> }

/** The record as it is stored: the marks and, per scope, the newest epoch key held. Only {@link readMarks}' callers see
 *  the marks alone. */
type StoredMarks = TrustMarks & { held: Record<string, HeldEpochKey> }

/**
 * Why a sealed account document was refused (ADR 0028 Decision 7):
 *   - `trust_state_rolled_back`: served below the revision this device has held — or missing after it held one;
 *   - `trust_state_unreadable`: it does not open. Never read as the empty store: an empty store is exactly what
 *     turns a detected key change back into a silent first use.
 */
export type TrustStateRefusal = 'trust_state_rolled_back' | 'trust_state_unreadable'

export class TrustStateRefused extends Error {
  constructor(readonly code: TrustStateRefusal, readonly document: AccountDocument) {
    super(`${code}: ${document}`)
    this.name = 'TrustStateRefused'
  }
}

const EMPTY = (): StoredMarks => ({ pins: 0, register: 0, epochs: {}, held: {} })
const COMMITMENT_INFO = v1.utf8('veyq/v1/epoch-key-commitment')
const COMMITMENT = /^[A-Za-z0-9+/]{22}==$/

/** The account's slot: a one-way, labelled derivation of its Master Key (see file header). */
function slotOf(mk: Uint8Array): string {
  return b64std(v1.hkdfSha256(mk, new Uint8Array(0), SLOT_INFO, 16))
}

const isMark = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0

const isHeld = (h: unknown): h is HeldEpochKey => {
  const v = h as Partial<HeldEpochKey> | null
  return !!v && typeof v === 'object' && isMark(v.epoch) && typeof v.commitment === 'string' && COMMITMENT.test(v.commitment)
}

/** A stored record read back defensively: anything that is not a mark counts as no mark. */
function normalize(raw: unknown): StoredMarks {
  const out = EMPTY()
  if (!raw || typeof raw !== 'object') return out
  const r = raw as Partial<Record<keyof StoredMarks, unknown>>
  if (isMark(r.pins)) out.pins = r.pins
  if (isMark(r.register)) out.register = r.register
  if (r.epochs && typeof r.epochs === 'object') {
    for (const [scopeId, e] of Object.entries(r.epochs as Record<string, unknown>)) if (isMark(e)) out.epochs[scopeId] = e
  }
  if (r.held && typeof r.held === 'object') {
    for (const [scopeId, h] of Object.entries(r.held as Record<string, unknown>)) {
      if (isHeld(h)) out.held[scopeId] = { epoch: h.epoch, commitment: h.commitment }
    }
  }
  return out
}

/** `base` raised by `raise`, each value as a maximum; a held key only by a higher epoch (at the same epoch the first one
 *  stays: the check before every hold has compared the two). */
function merged(base: StoredMarks, raise: MarksRaise): StoredMarks {
  const out: StoredMarks = { pins: base.pins, register: base.register, epochs: { ...base.epochs }, held: { ...base.held } }
  if (isMark(raise.pins)) out.pins = Math.max(out.pins, raise.pins)
  if (isMark(raise.register)) out.register = Math.max(out.register, raise.register)
  // An epoch-0 floor refuses nothing, so it is never stored: the marks of a device's unrotated scopes would only
  // grow the record and the pairing envelope that carries it (whose contract caps it at 8192 characters).
  for (const [scopeId, e] of Object.entries(raise.epochs ?? {})) {
    if (isMark(e) && e > 0) out.epochs[scopeId] = Math.max(out.epochs[scopeId] ?? 0, e)
  }
  for (const [scopeId, h] of Object.entries(raise.held ?? {})) {
    if (isHeld(h) && (out.held[scopeId] === undefined || h.epoch > out.held[scopeId].epoch)) out.held[scopeId] = { ...h }
  }
  return out
}

const hasStore = (): boolean => typeof indexedDB !== 'undefined'

/** The whole stored record of the account whose Master Key is `mk` — empty when it holds none. */
async function readStored(mk: Uint8Array): Promise<StoredMarks> {
  if (!hasStore()) return EMPTY()
  try {
    return normalize(await idbRun<unknown>(DB, STORE, 'readonly', (s) => s.get(slotOf(mk))))
  } catch {
    return EMPTY()
  }
}

/** The marks this device holds for the account whose Master Key is `mk` — none when it holds none. Without the held
 *  keys: this is what pairing carries (file header). */
export async function readMarks(mk: Uint8Array): Promise<TrustMarks> {
  const { pins, register, epochs } = await readStored(mk)
  return { pins, register, epochs }
}

/**
 * Raise this device's marks for the account whose Master Key is `mk`. The read and the write run in ONE
 * transaction, so two raises racing each other cannot lower a mark. Best-effort: never throws (file header).
 */
export async function raiseMarks(mk: Uint8Array, raise: MarksRaise): Promise<void> {
  if (!hasStore()) return
  const slot = slotOf(mk)
  try {
    await idbRun(DB, STORE, 'readwrite', (s) => {
      const req = s.get(slot)
      // `idbRun` owns `onsuccess`; a listener is what lets the put ride the same transaction.
      req.addEventListener('success', () => {
        s.put(merged(normalize(req.result), raise), slot)
      })
      return req
    })
  } catch {
    /* a mark that did not land is a lower mark: safer for the server, never a lockout */
  }
}

/**
 * Set one document's mark to exactly `revision` — the only operation here that can LOWER a mark.
 *
 * It exists for one caller: the user-confirmed reset (`trustReset/trustReset.ts`, ADR 0028 Decision 7's way back),
 * which writes the empty document at `served + 1` and must then accept that copy even where the served revision
 * sat below the mark (a rolled-back store) — a reset that merely raised would refuse its own write. Lowering is
 * sound only there: the mark protects this device from a SERVED older copy, and the copy it accepts is the one it
 * just wrote itself. `eslint.config.js` (`veyq/trust-reset-one-door`) refuses this import everywhere else.
 * Best-effort like {@link raiseMarks}; the read and the write share one transaction.
 */
export async function setMark(mk: Uint8Array, document: AccountDocument, revision: number): Promise<void> {
  if (!hasStore() || !isMark(revision)) return
  const slot = slotOf(mk)
  try {
    await idbRun(DB, STORE, 'readwrite', (s) => {
      const req = s.get(slot)
      req.addEventListener('success', () => {
        s.put({ ...normalize(req.result), [document]: revision }, slot)
      })
      return req
    })
  } catch {
    /* a mark that did not land is the mark that was there: the reset's write is then refused on the next read,
       and the person can start over again — never a silent acceptance */
  }
}

/**
 * Forget every mark this device holds for the account whose Master Key is `mk`: its slot's record is deleted. For the
 * erasure of that account only (file header) — a mark is never lowered on any other path. Best-effort, never throws:
 * a record left behind is keyed by a Master Key nobody can unlock again.
 */
export async function forgetMarks(mk: Uint8Array): Promise<void> {
  if (!hasStore()) return
  const slot = slotOf(mk)
  try {
    await idbRun(DB, STORE, 'readwrite', (s) => s.delete(slot))
  } catch {
    /* nothing to forget, or storage denied */
  }
}

/** An account-key rotation moves the account to a new slot; the rotating device carries what it held across. */
export async function carryMarks(fromMk: Uint8Array, toMk: Uint8Array): Promise<void> {
  await raiseMarks(toMk, await readStored(fromMk))
}

/**
 * The one read rule for a sealed account document (ADR 0028 Decision 7), before a read AND before every write,
 * since a write re-seals what it read. Refuses a served revision below the mark, a missing store once a mark
 * exists, and a blob that does not `open`; on a successful open the mark rises to the served revision. On a device
 * with no mark a missing store is the empty store, and legitimate — the named no-mark case.
 *
 * A stored blob always sits at revision ≥ 1 (the server bumps the revision it accepts), so `mark > 0` is exactly
 * "this device has held a store".
 */
export async function openAccountDocument<T>(
  document: AccountDocument,
  mk: Uint8Array,
  served: ServedDocument,
  open: (blob: string) => T,
  empty: () => T,
): Promise<T> {
  const mark = (await readMarks(mk))[document]
  if (served.revision < mark || (served.blob === null && mark > 0)) {
    throw new TrustStateRefused('trust_state_rolled_back', document)
  }
  if (served.blob === null) return empty()
  let value: T
  try {
    value = open(served.blob)
  } catch {
    throw new TrustStateRefused('trust_state_unreadable', document)
  }
  await raiseMarks(mk, { [document]: served.revision })
  return value
}

/** A served copy of a sealed account document: the opaque blob (null when never written) and its revision. */
export type ServedDocument = { blob: string | null; revision: number }

/** The served document as the trust marks read it: a store never written has no `blob` on the wire (the API omits
 *  a field with no value), which is the `null` every check below means by "born empty". */
export function servedDocument(doc: { blob?: string | null; revision: number }): ServedDocument {
  return { blob: doc.blob ?? null, revision: doc.revision }
}

/**
 * One sealed account document as the two operations that act on it WHOLE need it — the rotation's re-seal under
 * a new Master Key (`rotation/rotateKeys.ts`) and the user's reset (`trustReset/trustReset.ts`). Each document
 * module exports one, so neither operation reaches into a module's seal context or endpoints.
 *
 * `open` is the module's open through {@link openAccountDocument}: the marks apply, so a refused document is a
 * `TrustStateRefused` here exactly as on every other read.
 */
export interface AccountDocumentStore<T, W extends { revision: number } = { blob: string; revision: number }> {
  readonly document: AccountDocument
  fetch(): Promise<ServedDocument>
  open(mk: Uint8Array, served: ServedDocument): Promise<T>
  /** Seal `value` under `mk` for the revision it will be STORED at — the write's `revision + 1` — as the document's own
   *  write body without its `revision`: one blob for the register, the whole entry set and its head for the pin store. */
  seal(mk: Uint8Array, value: T, storedRevision: number): Omit<W, 'revision'>
  empty(): T
  /** The document's own revision-guarded write; `409 stale_revision` when `revision` is no longer the stored one. */
  write(update: W): Promise<void>
}

/** The vault's Master Key, used for one call and zeroed. Null when locked: then there is no account to key by. */
async function withVaultKey<T>(use: (mk: Uint8Array) => Promise<T>, locked: T): Promise<T> {
  const mk = masterKey()
  if (!mk) return locked
  try {
    return await use(mk)
  } finally {
    mk.fill(0)
  }
}

/** The highest epoch this device has held for `scopeId` (0 when none) — the floor of ADR 0024 Decision 6. */
export function epochFloor(scopeId: string): Promise<number> {
  return withVaultKey(async (mk) => (await readMarks(mk)).epochs[scopeId] ?? 0, 0)
}

/** This device now holds `epoch` of `scopeId` — the floor alone; {@link holdEpochKey} is what a grant or a rotation
 *  records. */
export function raiseEpochFloor(scopeId: string, epoch: number): Promise<void> {
  return withVaultKey((mk) => raiseMarks(mk, { epochs: { [scopeId]: epoch } }), undefined)
}

/** The commitment to an epoch key a device keeps (file header): 16 bytes of a labelled HKDF of the key, base64. */
export function epochKeyCommitment(key: Uint8Array): string {
  return b64std(v1.hkdfSha256(key, new Uint8Array(0), COMMITMENT_INFO, 16))
}

/** The newest epoch key this device held of `scopeId`, as a commitment — null when it held none (or is locked). */
export function heldEpochKey(scopeId: string): Promise<HeldEpochKey | null> {
  return withVaultKey(async (mk) => (await readStored(mk)).held[scopeId] ?? null, null)
}

/** This device now holds `key`, epoch `epoch` of `scopeId` — a grant it opened, a rotation it made, or the tenant key an
 *  accept checked or born (`views/TenantJoinView.vue`): the floor rises to it and, when it is newer than the held one, it
 *  becomes the key a later epoch must link down to. One transaction. Under the vault's Master Key, or under
 *  `accountKey` where the vault holds none yet — a new account's, at the accept that registers it; the caller owns it. */
export function holdEpochKey(scopeId: string, epoch: number, key: Uint8Array, accountKey?: Uint8Array): Promise<void> {
  const raise: MarksRaise = { epochs: { [scopeId]: epoch }, held: { [scopeId]: { epoch, commitment: epochKeyCommitment(key) } } }
  return accountKey ? raiseMarks(accountKey, raise) : withVaultKey((mk) => raiseMarks(mk, raise), undefined)
}

/** The i18n key that names a refusal on the nearest existing notice: pins under `verify`, the register under
 *  `sessions`. */
export function refusalMessageKey(e: TrustStateRefused): string {
  const unreadable = e.code === 'trust_state_unreadable'
  if (e.document === 'pins') return unreadable ? 'verify.trustUnreadable' : 'verify.trustRolledBack'
  return unreadable ? 'sessions.registerUnreadable' : 'sessions.registerRolledBack'
}
