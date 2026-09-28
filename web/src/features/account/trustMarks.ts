// This device's trust-state marks (ADR 0028 Decision 7, ADR 0024 Decision 6's floor): the highest pin-store
// version, device-register version and per-scope epoch it has held, so a server cannot hand it an older copy of
// state it keeps on the account's behalf. The server decides which copy a device reads; binding the version into
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
// **Not a secret, so it survives sign-out** — only the session's convenience tiers are dropped there. No path
// deletes the marks today, because none deliberately removes an account's saved device data: the one wholesale
// wipe (`forgetAllTrust`) has no caller. Whatever path is added for that must take the marks with it. Losing
// them reopens the no-mark case and breaks nothing.
//
// **Best-effort storage, fail-closed reading.** A browser with no IndexedDB (or a denied one) holds no marks, which
// is again the no-mark case; a write that does not land leaves the mark lower, which is only ever safer for the
// server and never locks anything. The refusals are the other half: they are decided here, and never degrade.
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { idbRun } from '../../shared/idb'
import { masterKey } from '../../app/vault'

const DB = 'veyq-trust-marks'
const STORE = 'marks'
const SLOT_INFO = v1.utf8('veyq/v1/trust-marks-slot')

/** The two sealed account documents a mark versions. */
export type AccountDocument = 'pins' | 'register'

/** Everything this device remembers for one account. `epochs` is keyed by scope id. */
export type TrustMarks = { pins: number; register: number; epochs: Record<string, number> }

/** A partial raise — each value is merged as a maximum, never as an assignment. */
export type MarksRaise = { pins?: number; register?: number; epochs?: Record<string, number> }

/**
 * Why a sealed account document was refused (ADR 0028 Decision 7):
 *   - `trust_state_rolled_back`: served below the version this device has held — or missing after it held one;
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

const EMPTY = (): TrustMarks => ({ pins: 0, register: 0, epochs: {} })

/** The account's slot: a one-way, labelled derivation of its Master Key (see file header). */
function slotOf(mk: Uint8Array): string {
  return b64std(v1.hkdfSha256(mk, new Uint8Array(0), SLOT_INFO, 16))
}

const isMark = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0

/** A stored record read back defensively: anything that is not a mark counts as no mark. */
function normalize(raw: unknown): TrustMarks {
  const out = EMPTY()
  if (!raw || typeof raw !== 'object') return out
  const r = raw as Partial<Record<keyof TrustMarks, unknown>>
  if (isMark(r.pins)) out.pins = r.pins
  if (isMark(r.register)) out.register = r.register
  if (r.epochs && typeof r.epochs === 'object') {
    for (const [scopeId, e] of Object.entries(r.epochs as Record<string, unknown>)) if (isMark(e)) out.epochs[scopeId] = e
  }
  return out
}

/** `base` raised by `raise`, each value as a maximum. */
function merged(base: TrustMarks, raise: MarksRaise): TrustMarks {
  const out: TrustMarks = { pins: base.pins, register: base.register, epochs: { ...base.epochs } }
  if (isMark(raise.pins)) out.pins = Math.max(out.pins, raise.pins)
  if (isMark(raise.register)) out.register = Math.max(out.register, raise.register)
  // An epoch-0 floor refuses nothing, so it is never stored: the marks of a device's unrotated scopes would only
  // grow the record and the pairing envelope that carries it (whose contract caps it at 8192 characters).
  for (const [scopeId, e] of Object.entries(raise.epochs ?? {})) {
    if (isMark(e) && e > 0) out.epochs[scopeId] = Math.max(out.epochs[scopeId] ?? 0, e)
  }
  return out
}

const hasStore = (): boolean => typeof indexedDB !== 'undefined'

/** The marks this device holds for the account whose Master Key is `mk` — none when it holds none. */
export async function readMarks(mk: Uint8Array): Promise<TrustMarks> {
  if (!hasStore()) return EMPTY()
  try {
    return normalize(await idbRun<unknown>(DB, STORE, 'readonly', (s) => s.get(slotOf(mk))))
  } catch {
    return EMPTY()
  }
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

/** An account-key rotation moves the account to a new slot; the rotating device carries what it held across. */
export async function carryMarks(fromMk: Uint8Array, toMk: Uint8Array): Promise<void> {
  await raiseMarks(toMk, await readMarks(fromMk))
}

/**
 * The one read rule for a sealed account document (ADR 0028 Decision 7), before a read AND before every write,
 * since a write re-seals what it read. Refuses a served version below the mark, a missing store once a mark
 * exists, and a blob that does not `open`; on a successful open the mark rises to the served version. On a device
 * with no mark a missing store is the empty store, and legitimate — the named no-mark case.
 *
 * A stored blob always sits at version ≥ 1 (the server bumps the version it accepts), so `mark > 0` is exactly
 * "this device has held a store".
 */
export async function openAccountDocument<T>(
  document: AccountDocument,
  mk: Uint8Array,
  served: { blob: string | null; version: number },
  open: (blob: string) => T,
  empty: () => T,
): Promise<T> {
  const mark = (await readMarks(mk))[document]
  if (served.version < mark || (served.blob === null && mark > 0)) {
    throw new TrustStateRefused('trust_state_rolled_back', document)
  }
  if (served.blob === null) return empty()
  let value: T
  try {
    value = open(served.blob)
  } catch {
    throw new TrustStateRefused('trust_state_unreadable', document)
  }
  await raiseMarks(mk, { [document]: served.version })
  return value
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

/** This device now holds `epoch` of `scopeId` — a grant it opened, or a rotation it made. */
export function raiseEpochFloor(scopeId: string, epoch: number): Promise<void> {
  return withVaultKey((mk) => raiseMarks(mk, { epochs: { [scopeId]: epoch } }), undefined)
}

/** The i18n key that names a refusal on the nearest existing notice: pins under `verify`, the register under
 *  `sessions`. */
export function refusalMessageKey(e: TrustStateRefused): string {
  const unreadable = e.code === 'trust_state_unreadable'
  if (e.document === 'pins') return unreadable ? 'verify.trustUnreadable' : 'verify.trustRolledBack'
  return unreadable ? 'sessions.registerUnreadable' : 'sessions.registerRolledBack'
}
