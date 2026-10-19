import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakeApi } from '../../test/apiMock'
import {
  fakePinServer,
  pinIdOf as pinIdAt,
  pinsOn as pinsAt,
  sealPinEntry,
  sealPinHead,
  sealPinStore,
  type PinServerState,
} from '../../test/pinStore'
import type { TasksApi } from '../../api/generated/tasks'

// The pin store's API + vault are mocked; the crypto (seal/open under the Master Key, the pin ids, the set digest) is
// REAL, so the tests prove a genuine round-trip, the per-entry refusals and the revision-guarded merge, not a stub. The
// fake server below seals nothing itself: what it holds is what the module wrote, or what `storeOf` seals in the
// documented format (v1.json#identity_pin) — an independent writer of the wire, so a drift of either side is red here.
vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), tasksApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import {
  checkAndPin,
  acceptKeyChange,
  getPin,
  safetyNumber,
  safetyNumberOfFingerprint,
  myIdentityPublicKeyB64,
  markVerified,
  trustState,
  trustStates,
  forgetVerifiedPins,
  IdentityMovedError,
  pinStoreDocument,
  ENTRY_BYTES,
  type Pin,
} from './identityPins'
import { fingerprintDigestB64 } from './safetyCode'
import { tasksApi } from '../../api/client'
import { masterKey, identityPrivate } from '../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type SymVector = { context: Record<string, string>; kek: string; blob: string; plaintext: string }
const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as {
  fingerprint: { kem: string; sign: string; digits: string; hash: string }
  identity_pin: { master_key: string; pin_ids: { userId: string; pinId: string }[]; entry_bytes: number }
  seal: { sym: Record<string, SymVector> }
}
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))

// One Master Key for the suite (the vector's, so the vector entry below opens as a stored one); the mocked vault hands
// out a FRESH copy each call because the data layer zeroes the copy it receives.
let MK = unhex(vectors.identity_pin.master_key)
const enc = new TextEncoder()

// Real identities: a pin is the FINGERPRINT of a served bundle, so a test key must be a bundle the codec decodes.
const keyOf = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
const KEY_A = keyOf()
const KEY_B = keyOf()
const KEY_C = keyOf()
const fp = (key: string) => fingerprintDigestB64(key)

// Contacts are canonical UUIDs: a pin's id is derived from the userId, and a second spelling would be a second slot.
const U1 = 'c0ffee00-0000-4000-8000-0000000000a1'
const U2 = 'c0ffee00-0000-4000-8000-0000000000a2'
const U3 = 'c0ffee00-0000-4000-8000-0000000000a3'
const OTHER = 'c0ffee00-0000-4000-8000-0000000000b1'
const NOBODY = 'c0ffee00-0000-4000-8000-0000000000ff'

const pinOf = (key: string, state: Pin['state'] = 'pinned', firstSeenAt = 1): Pin => ({ fingerprint: fp(key), state, firstSeenAt })

// The server side is the shared fake (`src/test/pinStore.ts`): it seals the documented format independently of this module.
type ServerState = PinServerState
const fakeServer = fakePinServer
const storeOf = (pins: Record<string, Pin>, revision: number) => sealPinStore(MK, pins, revision)
const headFor = (entries: Map<string, string>, revision: number) => sealPinHead(MK, entries, revision)
const sealEntryAs = (userId: string, pin: Pin, at: string) => sealPinEntry(MK, userId, pin, at)
const pinsOn = (state: ServerState) => pinsAt(MK, state)
const idOf = (userId: string, mk = MK) => pinIdAt(mk, userId)

/** The decline-clearing an accept drives (ADR 0060 Decision 7) — installed per test so each can read its calls. */
function fakeDeclines() {
  const clearDeclinesForAcceptedKey = vi.fn<TasksApi['clearDeclinesForAcceptedKey']>(async () => ({ cleared: 1 }))
  fakeApi(tasksApi, { clearDeclinesForAcceptedKey })
  return clearDeclinesForAcceptedKey
}

beforeEach(() => {
  vi.clearAllMocks()
  MK = unhex(vectors.identity_pin.master_key)
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  fakeDeclines()
  forgetVerifiedPins()
  // This device's local trust-state marks live in IndexedDB (ADR 0028 Decision 7): a fresh, empty one per test,
  // so every test starts as a device that has held nothing.
  globalThis.indexedDB = new IDBFactory()
})

describe('the stored format is the vectors\' (v1.json#identity_pin)', () => {
  it('an entry sealed by the independent generator opens as the pin of its contact', async () => {
    const v = vectors.seal.sym['identity-pin']
    const { userId, pinId } = vectors.identity_pin.pin_ids[0]
    expect(v.context.pinId).toBe(pinId)
    expect(ENTRY_BYTES).toBe(vectors.identity_pin.entry_bytes)
    const entries = new Map([[pinId, b64std(unhex(v.blob))]])
    fakeServer({ blob: headFor(entries, 4), revision: 4, entries })
    expect(await getPin(userId)).toEqual({ fingerprint: b64std(unhex(vectors.fingerprint.hash)), state: 'verified', firstSeenAt: 1760000000000 })
  })
})

describe('checkAndPin (TOFU)', () => {
  it('pins on first use as one entry plus a head; a re-check of the same key is a match', async () => {
    const srv = fakeServer()
    expect(await checkAndPin(U1, KEY_A)).toBe('firstUse')
    expect(srv.state.revision).toBe(1)
    expect(pinsOn(srv.state)).toEqual({ [U1]: { fingerprint: fp(KEY_A), state: 'pinned', firstSeenAt: expect.any(Number) } })
    // A later wrap to the SAME key proceeds without a write (no revision bump).
    expect(await checkAndPin(U1, KEY_A)).toBe('match')
    expect(srv.writeIdentityPins).toHaveBeenCalledTimes(1)
  })

  it('a pin writes its one entry and the head — never the store whole (ADR 0104, inbox #491)', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 5))
    expect(await checkAndPin(U3, KEY_C)).toBe('firstUse')
    const w = srv.writeIdentityPins.mock.calls[0][0].identityPinsWrite
    expect(w.put.map((e) => e.pinId)).toEqual([idOf(U3)])
    expect(w.remove).toEqual([])
    expect(w.replaceAll).toBe(false)
    expect(w.revision).toBe(5)
    // Every entry seals to one length, whatever its state.
    expect(fromB64std(w.put[0].entry).length).toBe(fromB64std(srv.state.entries.get(idOf(U1))!).length)
    expect(Object.keys(pinsOn(srv.state)).sort()).toEqual([U1, U2, U3].sort())
  })

  it('surfaces a CHANGED key without trusting it — the store is left untouched', async () => {
    const srv = fakeServer()
    await checkAndPin(U1, KEY_A)
    expect(await checkAndPin(U1, KEY_B)).toBe('changed')
    expect(srv.state.revision).toBe(1) // never re-pinned the changed key
    expect(pinsOn(srv.state)[U1].fingerprint).toBe(fp(KEY_A)) // original pin intact
  })

  it('a served key this device cannot decode matches no pin, and is never pinned as a first use', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 1))
    expect(await checkAndPin(U1, 'Z2FyYmxlZA==')).toBe('changed')
    await expect(checkAndPin(U2, 'Z2FyYmxlZA==')).rejects.toThrow()
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('reads an existing pin from a store another device wrote (cross-device round-trip)', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 3))
    expect(await checkAndPin(U1, KEY_A)).toBe('match')
    expect(await checkAndPin(U1, KEY_B)).toBe('changed')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled() // pure reads
  })

  it('a head sealed at one revision does not open as another — a replayed head is refused as unreadable', async () => {
    const at3 = storeOf({ [U1]: pinOf(KEY_A) }, 3)
    fakeServer({ ...at3, revision: 4 })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    // control: the same head at its own revision opens
    fakeServer(at3)
    expect((await getPin(U1))?.fingerprint).toBe(fp(KEY_A))
  })

  it('a head of another version does not open — the single-blob store has no read path (owner Q3)', async () => {
    const old = b64std(v1.seal({ purpose: 'identity-pins', revision: 2 }, MK, enc.encode(JSON.stringify({ pins: {} }))))
    fakeServer({ blob: old, revision: 2, entries: new Map() })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
  })

  it('merges on a 409 stale_revision — a concurrent device pin is preserved, not clobbered', async () => {
    const srv = fakeServer()
    srv.racedBy(storeOf({ [OTHER]: pinOf(KEY_B) }, 1))
    expect(await checkAndPin(U1, KEY_A)).toBe('firstUse')
    // After the re-read + re-apply + retry: BOTH contacts are pinned (the concurrent one was merged, not lost).
    expect(Object.keys(pinsOn(srv.state)).sort()).toEqual([U1, OTHER].sort())
    expect(srv.state.revision).toBe(2)
  })
})

describe('acceptKeyChange (human accept → re-pin)', () => {
  it('re-pins a contact to the new key, preserving firstSeenAt, so a later check matches', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 42) }, 1))
    await acceptKeyChange(U1, KEY_B)
    expect(pinsOn(srv.state)[U1]).toEqual({ fingerprint: fp(KEY_B), state: 'pinned', firstSeenAt: 42 })
    expect((await getPin(U1))?.fingerprint).toBe(fp(KEY_B))
    expect(await checkAndPin(U1, KEY_B)).toBe('match') // the changed key now trusted
  })

  it('clears this worker’s declines against that contact, so the accept actually delivers', async () => {
    // ADR 0028 (2) promised the wrap proceeds on accept and nothing made it true: a decline is permanent, so
    // the re-pin alone authorises a wrap that never happens (uc.account.rotate-keys §E6).
    const clear = fakeDeclines()
    fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 42) }, 1))
    await acceptKeyChange(U1, KEY_B)
    expect(clear).toHaveBeenCalledWith({ clearDeclinesRequest: { targetUserId: U1 } })
  })

  it('surfaces a failed clear instead of reporting an accept that delivers nothing', async () => {
    const clear = fakeDeclines()
    clear.mockRejectedValueOnce(new Error('offline'))
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 42) }, 1))
    await expect(acceptKeyChange(U1, KEY_B)).rejects.toThrow()
    // The re-pin itself landed — which is why a retry is safe rather than a second, conflicting write.
    expect(pinsOn(srv.state)[U1].fingerprint).toBe(fp(KEY_B))
  })
})

// The safety-number the ceremony compares out-of-band; must match the pinned `fingerprint` conformance vector.
const IDENTITY_FROM_VECTOR = b64std(
  v1.serializeIdentityPublic({ kem: unhex(vectors.fingerprint.kem), sign: unhex(vectors.fingerprint.sign) }),
)

describe('safetyNumber', () => {
  it('renders the 6×5 digit safety number and matches the fingerprint vector', () => {
    expect(safetyNumber(IDENTITY_FROM_VECTOR)).toBe(vectors.fingerprint.digits)
  })

  it('a pin\'s stored fingerprint renders the same number the bundle does (ADR 0104 decision 3)', () => {
    expect(fp(IDENTITY_FROM_VECTOR)).toBe(b64std(unhex(vectors.fingerprint.hash)))
    expect(safetyNumberOfFingerprint(fp(IDENTITY_FROM_VECTOR))).toBe(vectors.fingerprint.digits)
    expect(() => safetyNumberOfFingerprint(b64std(new Uint8Array(31)))).toThrow()
  })
})

describe('myIdentityPublicKeyB64', () => {
  it('reconstructs my own identity bundle from the vault private key → a stable safety-number', () => {
    const id = v1.generateIdentity()
    vi.mocked(identityPrivate).mockReturnValue({ kemSeed: id.priv.kemSeed.slice(), sign: id.priv.sign.slice() })
    expect(safetyNumber(myIdentityPublicKeyB64())).toBe(v1.fingerprint(id.pub))
  })

  it('throws when the vault is locked', () => {
    vi.mocked(identityPrivate).mockReturnValue(null)
    expect(() => myIdentityPublicKeyB64()).toThrow()
  })
})

describe('markVerified (out-of-band compare → verified)', () => {
  it('elevates a matching pin to verified, preserving fingerprint + firstSeenAt', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 7) }, 2))
    await markVerified(U1, fp(KEY_A))
    expect(pinsOn(srv.state)[U1]).toEqual({ fingerprint: fp(KEY_A), state: 'verified', firstSeenAt: 7 })
    expect(srv.state.revision).toBe(3)
  })

  it('aborts (never writes) when the pinned key moved under the compare', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 7) }, 2))
    await expect(markVerified(U1, fp(KEY_B))).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.state.revision).toBe(2) // no write — verified never stamped onto an unchecked key
    expect(pinsOn(srv.state)[U1].state).toBe('pinned')
  })

  it('aborts when there is no pin at all (nothing was compared)', async () => {
    fakeServer()
    await expect(markVerified(NOBODY, fp(KEY_A))).rejects.toBeInstanceOf(IdentityMovedError)
  })

  it('verified wins a same-contact 409: re-asserts verified over a concurrent pinned, merges the other pin', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 7) }, 1))
    srv.racedBy(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 7), [OTHER]: pinOf(KEY_B) }, 2))
    await markVerified(U1, fp(KEY_A))
    const pins = pinsOn(srv.state)
    expect(pins[U1].state).toBe('verified')
    expect(pins[OTHER].fingerprint).toBe(fp(KEY_B))
    expect(srv.state.revision).toBe(3)
  })

  it('aborts on a 409 re-base if the concurrent device changed the key (moved under the compare)', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 7) }, 1))
    srv.racedBy(storeOf({ [U1]: pinOf(KEY_B, 'pinned', 7) }, 2))
    await expect(markVerified(U1, fp(KEY_A))).rejects.toBeInstanceOf(IdentityMovedError)
    expect(pinsOn(srv.state)[U1].fingerprint).toBe(fp(KEY_B)) // left as the concurrent write; not re-stamped
  })
})

// ADR 0028 Decision 3: a member with no pin is pinned AT verification, with the fingerprint the compare confirmed - one
// user action, one sealed write (or the store's merge on 409), never a pin without the verification.
describe('markVerified with pinIfUnpinned (verifying an unpinned member pins them)', () => {
  it('pins the compared fingerprint already verified, in ONE write', async () => {
    const srv = fakeServer()
    await markVerified(U1, fp(KEY_A), { pinIfUnpinned: true })
    expect(srv.writeIdentityPins).toHaveBeenCalledTimes(1)
    expect(pinsOn(srv.state)[U1]).toMatchObject({ fingerprint: fp(KEY_A), state: 'verified' })
  })

  it('control: without the option an unpinned member is still refused - nothing pins on a plain markVerified', async () => {
    const srv = fakeServer()
    await expect(markVerified(U1, fp(KEY_A))).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('a concurrent pin of the SAME key merges on 409: it is verified, and the other contact is kept', async () => {
    const srv = fakeServer()
    srv.racedBy(storeOf({ [U1]: pinOf(KEY_A, 'pinned', 3), [OTHER]: pinOf(KEY_B) }, 1))
    await markVerified(U1, fp(KEY_A), { pinIfUnpinned: true })
    const pins = pinsOn(srv.state)
    expect(pins[U1]).toEqual({ fingerprint: fp(KEY_A), state: 'verified', firstSeenAt: 3 })
    expect(pins[OTHER].fingerprint).toBe(fp(KEY_B))
  })

  it('a concurrent pin of ANOTHER key throws IdentityMovedError and writes nothing', async () => {
    const srv = fakeServer()
    srv.racedBy(storeOf({ [U1]: pinOf(KEY_B, 'pinned', 3) }, 1))
    await expect(markVerified(U1, fp(KEY_A), { pinIfUnpinned: true })).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.state.revision).toBe(1) // only the concurrent write landed
    expect(pinsOn(srv.state)[U1]).toEqual(pinOf(KEY_B, 'pinned', 3))
  })

  it('a member pinned to another key at load is refused too - the option never overwrites a pin', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_B, 'pinned', 3) }, 1))
    await expect(markVerified(U1, fp(KEY_A), { pinIfUnpinned: true })).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })
})

describe('trustState (badge read)', () => {
  it('folds pin state against the current directory key across all branches', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'verified'), [U2]: pinOf(KEY_A) }, 1))
    expect(await trustState(U1, KEY_A)).toBe('verified')
    expect(await trustState(U2, KEY_A)).toBe('pinned')
    expect(await trustState(U1, KEY_B)).toBe('changed') // key differs → changed even for a verified pin
    expect(await trustState(U1, 'Z2FyYmxlZA==')).toBe('changed') // a key this device cannot decode is never trusted
    expect(await trustState(NOBODY, KEY_A)).toBe('unpinned')
  })

  it('trustStates folds a whole directory in one store read', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'verified'), [U2]: pinOf(KEY_A), [U3]: pinOf(KEY_C) }, 1))
    const states = await trustStates([
      { userId: U1, directoryKey: KEY_A },
      { userId: U2, directoryKey: KEY_A },
      { userId: U3, directoryKey: KEY_B }, // directory moved away from the pin → changed
      { userId: NOBODY, directoryKey: KEY_A }, // never pinned → unpinned
    ])
    expect(states).toEqual({
      [U1]: { state: 'verified', pinVerified: true },
      [U2]: { state: 'pinned', pinVerified: false },
      [U3]: { state: 'changed', pinVerified: false },
      [NOBODY]: { state: 'unpinned', pinVerified: false },
    })
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(1)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(1)
  })

  it('a VERIFIED contact whose directory key moved reads as changed AND verified (ADR 0028 Decision 3)', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'verified') }, 1))
    expect(await trustStates([{ userId: U1, directoryKey: KEY_B }])).toEqual({ [U1]: { state: 'changed', pinVerified: true } })
  })
})

describe('accepting a verified contact\'s changed key (ADR 0028 Decision 3)', () => {
  it('re-pins the new key as PINNED - verified returns only through a new compare', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A, 'verified', 5) }, 1))
    await acceptKeyChange(U1, KEY_B)
    expect(pinsOn(srv.state)[U1]).toEqual({ fingerprint: fp(KEY_B), state: 'pinned', firstSeenAt: 5 })
    expect(await trustStates([{ userId: U1, directoryKey: KEY_B }])).toEqual({ [U1]: { state: 'pinned', pinVerified: false } })
  })
})

// ADR 0028 Decision 7 — a device never accepts older trust state than it has held. Every case below fixes its lever
// (the payload the server serves) inside the test, and every negative names the refusal it must meet.
describe('the pin store never goes backwards on this device (ADR 0028 Decision 7)', () => {
  const EMPTY: ServerState = { blob: undefined, revision: 0, entries: new Map() }

  it('wipe: once this device opened v3, a missing store is refused — the changed key is not pinned as a first use', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 3))
    expect(await checkAndPin(U1, KEY_A)).toBe('match') // this device has now held v3
    const wiped = fakeServer(EMPTY)
    await expect(checkAndPin(U1, KEY_B)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(wiped.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('control: a device with no mark reads a missing store as empty, and pins on first use', async () => {
    const srv = fakeServer(EMPTY)
    expect(await checkAndPin(U1, KEY_B)).toBe('firstUse')
    expect(srv.state.revision).toBe(1)
  })

  it('unopenable: a garbage head is refused as unreadable, never read as the empty store', async () => {
    const srv = fakeServer({ blob: b64std(v1.randomBytes(64)), revision: 2, entries: new Map() })
    await expect(checkAndPin(U1, KEY_B)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('rollback: a store below the mark cannot reinstate a retired key as a match', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_B) }, 3))
    expect((await getPin(U1))?.fingerprint).toBe(fp(KEY_B)) // this device has held v3, where the pin is the new key
    const rolled = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 2)) // v2 still pins the retired one
    await expect(checkAndPin(U1, KEY_A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('a write raises the mark to the revision it stored — the next missing store is refused', async () => {
    const srv = fakeServer(EMPTY)
    expect(await checkAndPin(U1, KEY_B)).toBe('firstUse') // stored at v1 by this device
    expect(srv.state.revision).toBe(1)
    fakeServer(EMPTY)
    await expect(checkAndPin(U1, KEY_A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('the open before a write is checked too: an accept over a rolled-back store writes nothing', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_B) }, 3))
    await getPin(U1)
    const rolled = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 2))
    await expect(acceptKeyChange(U1, KEY_A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })
})

// ADR 0104 decision 4 — the same refusal PER ENTRY. The head is honest in every case below (sealed, at the mark); the
// server's lever is the listing. Each negative anchors on `trust_state_rolled_back` — the seal alone would refuse a moved
// entry as UNREADABLE, so the code is what proves the digest did the refusing — and the control serves the same head
// with the stored entries. The digest check is what every negative here rests on: skipped, each reads as a store.
describe('a withheld, added, rolled-back or moved entry is refused (ADR 0104 decision 4)', () => {
  function threePins() {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B), [U3]: pinOf(KEY_C) }, 4))
    return srv
  }

  it('control: the stored entries under the stored head open to every pin', async () => {
    threePins()
    expect(await trustStates([{ userId: U1, directoryKey: KEY_A }, { userId: U2, directoryKey: KEY_B }])).toEqual({
      [U1]: { state: 'pinned', pinVerified: false },
      [U2]: { state: 'pinned', pinVerified: false },
    })
  })

  it('withheld: an entry left out of the listing — the changed key would read as a first use', async () => {
    const srv = threePins()
    srv.tamper((e) => (e.delete(idOf(U2)), e))
    await expect(checkAndPin(U2, KEY_C)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('added: an entry the head does not count — one copied in from another slot', async () => {
    const srv = threePins()
    srv.tamper((e) => e.set(idOf(OTHER), e.get(idOf(U1))!))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('rolled back: an older ciphertext of one entry — a retired key reinstated as a match', async () => {
    const srv = threePins()
    const before = srv.state.entries.get(idOf(U1))!
    await acceptKeyChange(U1, KEY_C) // this device re-pins U1 to the new key (head v5)
    srv.tamper((e) => e.set(idOf(U1), before)) // the server serves U1's old entry under the new head
    forgetVerifiedPins() // as a fresh session would: list again
    await expect(checkAndPin(U1, KEY_A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('moved: one contact\'s entry served at another contact\'s id', async () => {
    const srv = threePins()
    srv.tamper((e) => {
      const u1 = e.get(idOf(U1))!
      e.delete(idOf(U1))
      return e.set(idOf(OTHER), u1)
    })
    await expect(checkAndPin(OTHER, KEY_A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('a head that moved between its read and the listing is read again, not refused', async () => {
    const srv = threePins()
    // Another device's write lands after this device read the head and before it listed: the listing is the new set.
    const next = storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B), [U3]: pinOf(KEY_C), [OTHER]: pinOf(KEY_A) }, 5)
    srv.listIdentityPinEntries.mockImplementationOnce(async () => {
      Object.assign(srv.state, { blob: next.blob, revision: next.revision, entries: new Map(next.entries) })
      return { entries: [...next.entries].sort(([a], [b]) => (a < b ? -1 : 1)).map(([pinId, entry]) => ({ pinId, entry })), more: false }
    })
    expect((await getPin(OTHER))?.fingerprint).toBe(fp(KEY_A))
    // The head, then the check that it moved — which is already the new head, opened and listed again.
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(2)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })

  it('an entry whose contact does not derive its id is refused as unreadable, even inside a matching digest', async () => {
    // Only a holder of the Master Key can build this (the digest covers it): the check is the reader's own, so an
    // entry sealed for one contact never answers for another's slot.
    const entries = new Map([[idOf(OTHER), sealEntryAs(U1, pinOf(KEY_A), idOf(OTHER))]])
    fakeServer({ blob: headFor(entries, 2), revision: 2, entries })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
  })

  it('a listing that does not advance is refused, not followed', async () => {
    const srv = threePins()
    const first = idOf(U1) < idOf(U2) ? U1 : U2
    srv.listIdentityPinEntries.mockImplementation(async () => ({
      entries: [{ pinId: idOf(first), entry: srv.state.entries.get(idOf(first))! }],
      more: true,
    }))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })
})

describe('the entries are listed once per head (ADR 0104 decision 6)', () => {
  it('pages through the whole set by pinId', async () => {
    const pins = Object.fromEntries([U1, U2, U3, OTHER].map((u) => [u, pinOf(KEY_A)]))
    const srv = fakeServer(storeOf(pins, 1), 1) // one entry a page
    expect(Object.keys(await trustStates([U1, U2, U3, OTHER].map((userId) => ({ userId, directoryKey: KEY_A }))))).toHaveLength(4)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(4)
  })

  it('a second read under the same head lists nothing; a head another device moved lists again', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 1))
    await getPin(U1)
    await getPin(U1)
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(2)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(1)
    const next = storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 2)
    Object.assign(srv.state, { blob: next.blob, revision: next.revision, entries: next.entries })
    expect((await getPin(U2))?.fingerprint).toBe(fp(KEY_B))
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })

  it('this device\'s own write is kept as verified: the next read is the head alone', async () => {
    const srv = fakeServer()
    await checkAndPin(U1, KEY_A)
    const listed = srv.listIdentityPinEntries.mock.calls.length
    expect(await checkAndPin(U1, KEY_A)).toBe('match')
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(listed)
  })

  it('a cached set is no defence a server can lean on: a rolled-back head is still refused by the marks', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_B) }, 3))
    await getPin(U1)
    fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 2))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })
})

// The rotation and the reset act on the store WHOLE, through `pinStoreDocument` — their behaviour is proven in
// `rotation/rotateKeys.test.ts` and `trustReset/trustReset.test.ts`, against this real module. What is pinned here
// is that the whole-document view is the same store: the same open (marks and digest), the whole set as one write.
describe('pinStoreDocument — the store as one whole document (ADR 0028 Decision 7, ADR 0104)', () => {
  const pinned = { [U1]: pinOf(KEY_A) }

  it('opens through the same marks as every read: a store below the mark is refused, and nothing is written', async () => {
    fakeServer(storeOf(pinned, 3))
    await getPin(U1) // this device has held v3
    const rolled = fakeServer(storeOf(pinned, 2))
    await expect(pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('and through the same digest: a withheld entry is refused there too', async () => {
    const srv = fakeServer(storeOf(pinned, 3))
    srv.tamper(() => new Map())
    await expect(pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
  })

  it('control: at the mark it opens to the pins, and its seal is the whole set as one replacing write', async () => {
    const srv = fakeServer(storeOf(pinned, 3))
    const pins = await pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())
    expect(pins).toEqual(pinned)
    const sealed = pinStoreDocument.seal(MK.slice(), pins, 4)
    expect(sealed).toMatchObject({ remove: [], replaceAll: true })
    await pinStoreDocument.write({ ...sealed, revision: 3 })
    expect(srv.state.revision).toBe(4)
    expect(pinsOn(srv.state)).toEqual(pinned)
  })

  it('sealed under another Master Key, every entry moves to that key\'s ids (the rotation re-keys them)', () => {
    const other = Uint8Array.from(v1.randomBytes(32))
    const sealed = pinStoreDocument.seal(other.slice(), pinned, 4)
    expect(sealed.put.map((e) => e.pinId)).toEqual([idOf(U1, other)])
    expect(sealed.put[0].pinId).not.toBe(idOf(U1))
  })

  it('the empty set is a head of no entries — the reset leaves no entry', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 3))
    await pinStoreDocument.write({ ...pinStoreDocument.seal(MK.slice(), pinStoreDocument.empty(), 4), revision: 3 })
    expect(srv.state.entries.size).toBe(0)
    expect(pinsOn(srv.state)).toEqual({})
  })
})
