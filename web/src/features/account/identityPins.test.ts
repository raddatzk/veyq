import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../test/httpError'
import { fakeApi } from '../../test/apiMock'
import type { AccountIdentityPinsApi as PinsApi, IdentityPins } from '../../api/generated/account'
import type { TasksApi } from '../../api/generated/tasks'

// The pin store's API + vault are mocked; the crypto (seal/unseal under the Master Key) is REAL, so the tests
// prove a genuine round-trip and the revision-guarded union-merge, not a stub.
vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), tasksApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import {
  checkAndPin,
  acceptKeyChange,
  getPin,
  safetyNumber,
  myIdentityPublicKeyB64,
  markVerified,
  trustState,
  trustStates,
  IdentityMovedError,
  pinStoreDocument,
} from './identityPins'
import { accountIdentityPinsApi, tasksApi } from '../../api/client'
import { masterKey, identityPrivate } from '../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as { fingerprint: { kem: string; sign: string; digits: string } }
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))

// One fixed Master Key for the suite; the mocked vault hands out a FRESH copy each call because the data layer
// zeroes the copy it receives (returning the same buffer would zero our master after the first use).
const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const dec = new TextDecoder()
// The store is sealed bound to the revision it is stored at (ADR 0072), so a fixture names its revision too.
const pinsAt = (revision: number) => ({ purpose: 'identity-pins', revision }) as const
const seal = (pins: Record<string, unknown>, revision: number) =>
  b64std(v1.seal(pinsAt(revision), MK, enc.encode(JSON.stringify({ pins }))))
const unsealPins = (blob: string, revision: number): Record<string, { key: string; state: string; firstSeenAt: number }> =>
  JSON.parse(dec.decode(v1.open(pinsAt(revision), MK, fromB64std(blob)))).pins

// A fake server store with REAL optimistic concurrency: a PUT whose revision != the stored revision is a 409.
function fakeServer(initial: IdentityPins = { blob: undefined, revision: 0 }) {
  const state: IdentityPins = { ...initial }
  const stale = () => httpError(409, 'stale_revision')
  const getIdentityPins = vi.fn<PinsApi['getIdentityPins']>(async () => ({ blob: state.blob, revision: state.revision }))
  const putIdentityPins = vi.fn<PinsApi['putIdentityPins']>(async ({ identityPinsUpdate }) => {
    if (identityPinsUpdate.revision !== state.revision) throw stale()
    state.blob = identityPinsUpdate.blob
    state.revision += 1
    return { blob: state.blob, revision: state.revision }
  })
  fakeApi(accountIdentityPinsApi, { getIdentityPins, putIdentityPins })
  return { state, getIdentityPins, putIdentityPins, stale }
}

/** The decline-clearing an accept drives (ADR 0060 Decision 7) — installed per test so each can read its calls. */
function fakeDeclines() {
  const clearDeclinesForAcceptedKey = vi.fn<TasksApi['clearDeclinesForAcceptedKey']>(async () => ({ cleared: 1 }))
  fakeApi(tasksApi, { clearDeclinesForAcceptedKey })
  return clearDeclinesForAcceptedKey
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  fakeDeclines()
  // This device's local trust-state marks live in IndexedDB (ADR 0028 Decision 7): a fresh, empty one per test,
  // so every test starts as a device that has held nothing.
  globalThis.indexedDB = new IDBFactory()
})

describe('checkAndPin (TOFU)', () => {
  it('pins on first use and stores the sealed blob; a re-check of the same key is a match', async () => {
    const srv = fakeServer()
    const key = 'aWRlbnRpdHkta2V5LW9uZQ=='

    expect(await checkAndPin('u1', key)).toBe('firstUse')
    // The server now holds one opaque blob at revision 1 that unseals to the pinned contact.
    expect(srv.state.revision).toBe(1)
    expect(srv.state.blob).not.toBeNull()
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1.key).toBe(key)
    expect(pins.u1.state).toBe('pinned')

    // A later wrap to the SAME key proceeds without a write (no revision bump).
    expect(await checkAndPin('u1', key)).toBe('match')
    expect(srv.state.revision).toBe(1)
  })

  it('surfaces a CHANGED key without trusting it — the store is left untouched', async () => {
    const srv = fakeServer()
    await checkAndPin('u1', 'a2V5LW9uZQ==')
    const revisionAfterPin = srv.state.revision

    expect(await checkAndPin('u1', 'a2V5LXR3bw==')).toBe('changed')
    expect(srv.state.revision).toBe(revisionAfterPin) // never re-pinned the changed key
    expect(unsealPins(srv.state.blob!, srv.state.revision).u1.key).toBe('a2V5LW9uZQ==') // original pin intact
  })

  it('reads an existing pin from a previously-sealed store (cross-device round-trip)', async () => {
    // The store was written on another device (a sealed blob at revision 3); this device opens it and sees the pin.
    const srv = fakeServer({ blob: seal({ u9: { key: 'cHJlLXBpbm5lZA==', state: 'pinned', firstSeenAt: 1 } }, 3), revision: 3 })
    expect(await checkAndPin('u9', 'cHJlLXBpbm5lZA==')).toBe('match')
    expect(await checkAndPin('u9', 'ZGlmZmVyZW50')).toBe('changed')
    expect(srv.putIdentityPins).not.toHaveBeenCalled() // pure reads
  })

  it('a store sealed at one revision does not open as another — a rolled-back or replayed blob is refused', async () => {
    // ADR 0072: the blob is bound to the revision it is stored at. The server serving the v3 blob as v4 (or v2) is
    // refused at the seal, and the reader refuses it as unreadable — never as an empty store (ADR 0028 Decision 7),
    // which would make the pinned contact a first use.
    const blob = seal({ u9: { key: 'cHJlLXBpbm5lZA==', state: 'pinned', firstSeenAt: 1 } }, 3)
    expect(() => v1.open(pinsAt(4), MK, fromB64std(blob))).toThrow(new v1.OpenRefused('identity-pins'))
    fakeServer({ blob, revision: 4 })
    await expect(getPin('u9')).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    // control: the same blob at its own revision opens
    fakeServer({ blob, revision: 3 })
    expect((await getPin('u9'))?.key).toBe('cHJlLXBpbm5lZA==')
  })

  it('union-merges on a 409 stale_revision — a concurrent device pin is preserved, not clobbered', async () => {
    const srv = fakeServer()
    // Make the FIRST PUT lose to a concurrent device that just pinned contact "other" at v0→v1.
    let firstPut = true
    srv.putIdentityPins.mockImplementation(async ({ identityPinsUpdate }) => {
      if (firstPut) {
        firstPut = false
        srv.state.blob = seal({ other: { key: 'b3RoZXIta2V5', state: 'pinned', firstSeenAt: 1 } }, 1)
        srv.state.revision = 1
        throw srv.stale()
      }
      if (identityPinsUpdate.revision !== srv.state.revision) throw srv.stale()
      srv.state.blob = identityPinsUpdate.blob
      srv.state.revision += 1
      return { blob: srv.state.blob, revision: srv.state.revision }
    })

    expect(await checkAndPin('mine', 'bXkta2V5')).toBe('firstUse')
    // After the re-read + re-apply + retry: BOTH contacts are pinned (the concurrent one was merged, not lost).
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.other.key).toBe('b3RoZXIta2V5')
    expect(pins.mine.key).toBe('bXkta2V5')
    expect(srv.state.revision).toBe(2)
  })
})

describe('acceptKeyChange (human accept → re-pin)', () => {
  it('re-pins a contact to the new key, preserving firstSeenAt, so a later check matches', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'b2xkLWtleQ==', state: 'pinned', firstSeenAt: 42 } }, 1), revision: 1 })

    await acceptKeyChange('u1', 'bmV3LWtleQ==')
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1.key).toBe('bmV3LWtleQ==')
    expect(pins.u1.firstSeenAt).toBe(42) // preserved across the re-pin
    expect((await getPin('u1'))?.key).toBe('bmV3LWtleQ==')
    expect(await checkAndPin('u1', 'bmV3LWtleQ==')).toBe('match') // the changed key now trusted
  })

  it('clears this worker\u2019s declines against that contact, so the accept actually delivers', async () => {
    // ADR 0028 (2) promised the wrap proceeds on accept and nothing made it true: a decline is permanent, so
    // the re-pin alone authorises a wrap that never happens (uc.account.rotate-keys \u00a7E6 \u2014 the failure mode that
    // strands a rotated account). The re-pin is only half of an accept.
    const clear = fakeDeclines()
    fakeServer({ blob: seal({ u1: { key: 'b2xkLWtleQ==', state: 'pinned', firstSeenAt: 42 } }, 1), revision: 1 })

    await acceptKeyChange('u1', 'bmV3LWtleQ==')

    expect(clear).toHaveBeenCalledWith({ clearDeclinesRequest: { targetUserId: 'u1' } })
  })

  it('surfaces a failed clear instead of reporting an accept that delivers nothing', async () => {
    // Swallowing it would re-create the exact stranding above, and report success while doing so. Re-accepting
    // is idempotent, so a caller that raises the error ends up in the right state; one that hides it cannot.
    const clear = fakeDeclines()
    clear.mockRejectedValueOnce(new Error('offline'))
    const srv = fakeServer({ blob: seal({ u1: { key: 'b2xkLWtleQ==', state: 'pinned', firstSeenAt: 42 } }, 1), revision: 1 })

    await expect(acceptKeyChange('u1', 'bmV3LWtleQ==')).rejects.toThrow()
    // The re-pin itself landed \u2014 which is why a retry is safe rather than a second, conflicting write.
    expect(unsealPins(srv.state.blob!, srv.state.revision).u1.key).toBe('bmV3LWtleQ==')
  })
})

// The safety-number the ceremony compares out-of-band; must match the pinned `fingerprint` conformance vector.
const IDENTITY_FROM_VECTOR = b64std(
  v1.serializeIdentityPublic({
    kem: unhex(vectors.fingerprint.kem),
    sign: unhex(vectors.fingerprint.sign),
  }),
)

describe('safetyNumber', () => {
  it('renders the 6×5 digit safety number and matches the fingerprint vector', () => {
    expect(safetyNumber(IDENTITY_FROM_VECTOR)).toBe(vectors.fingerprint.digits)
  })
})

describe('myIdentityPublicKeyB64', () => {
  it('reconstructs my own identity bundle from the vault private key → a stable safety-number', () => {
    const id = v1.generateIdentity()
    vi.mocked(identityPrivate).mockReturnValue({
      kemSeed: id.priv.kemSeed.slice(),
      sign: id.priv.sign.slice(),
    })
    // The reconstructed public bundle's safety-number equals the fingerprint of the generated public bundle.
    expect(safetyNumber(myIdentityPublicKeyB64())).toBe(v1.fingerprint(id.pub))
  })

  it('throws when the vault is locked', () => {
    vi.mocked(identityPrivate).mockReturnValue(null)
    expect(() => myIdentityPublicKeyB64()).toThrow()
  })
})

describe('markVerified (out-of-band compare → verified)', () => {
  it('elevates a matching pin to verified, preserving key + firstSeenAt', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 7 } }, 2), revision: 2 })

    await markVerified('u1', 'a2V5')
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1.state).toBe('verified')
    expect(pins.u1.key).toBe('a2V5')
    expect(pins.u1.firstSeenAt).toBe(7) // untouched by the elevation
    expect(srv.state.revision).toBe(3)
  })

  it('aborts (never writes) when the pinned key moved under the compare', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 7 } }, 2), revision: 2 })

    await expect(markVerified('u1', 'ZGlmZg==')).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.state.revision).toBe(2) // no write — verified never stamped onto an unchecked key
    expect(unsealPins(srv.state.blob!, srv.state.revision).u1.state).toBe('pinned')
  })

  it('aborts when there is no pin at all (nothing was compared)', async () => {
    fakeServer()
    await expect(markVerified('nobody', 'a2V5')).rejects.toBeInstanceOf(IdentityMovedError)
  })

  it('verified wins a same-contact 409: re-asserts verified over a concurrent pinned, merges the other pin', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 7 } }, 1), revision: 1 })
    let firstPut = true
    srv.putIdentityPins.mockImplementation(async ({ identityPinsUpdate }) => {
      if (firstPut) {
        firstPut = false
        // A concurrent device re-pinned u1 (same key, still pinned) and added another contact.
        srv.state.blob = seal({
          u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 7 },
          other: { key: 'b3Ro', state: 'pinned', firstSeenAt: 1 },
        }, 2)
        srv.state.revision = 2
        throw srv.stale()
      }
      if (identityPinsUpdate.revision !== srv.state.revision) throw srv.stale()
      srv.state.blob = identityPinsUpdate.blob
      srv.state.revision += 1
      return { blob: srv.state.blob, revision: srv.state.revision }
    })

    await markVerified('u1', 'a2V5')
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1.state).toBe('verified') // verified wins over the concurrent pinned
    expect(pins.other.key).toBe('b3Ro') // the concurrent contact was merged, not clobbered
    expect(srv.state.revision).toBe(3)
  })

  it('aborts on a 409 re-base if the concurrent device changed the key (moved under the compare)', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 7 } }, 1), revision: 1 })
    let firstPut = true
    srv.putIdentityPins.mockImplementation(async ({ identityPinsUpdate }) => {
      if (firstPut) {
        firstPut = false
        srv.state.blob = seal({ u1: { key: 'bmV3', state: 'pinned', firstSeenAt: 7 } }, 2) // key moved
        srv.state.revision = 2
        throw srv.stale()
      }
      if (identityPinsUpdate.revision !== srv.state.revision) throw srv.stale()
      srv.state.blob = identityPinsUpdate.blob
      srv.state.revision += 1
      return { blob: srv.state.blob, revision: srv.state.revision }
    })

    await expect(markVerified('u1', 'a2V5')).rejects.toBeInstanceOf(IdentityMovedError)
    expect(unsealPins(srv.state.blob!, srv.state.revision).u1.key).toBe('bmV3') // left as the concurrent write; not re-stamped
  })
})

// ADR 0028 Decision 3: a member with no pin is pinned AT verification, with the key the compare confirmed - one user
// action, one sealed write (or the store's merge on 409), never a pin without the verification.
describe('markVerified with pinIfUnpinned (verifying an unpinned member pins them)', () => {
  /** A server whose FIRST put meets a concurrent device's store (`concurrent`), then behaves. */
  function serverRacedBy(concurrent: Record<string, unknown>) {
    const srv = fakeServer()
    let firstPut = true
    srv.putIdentityPins.mockImplementation(async ({ identityPinsUpdate }) => {
      if (firstPut) {
        firstPut = false
        srv.state.blob = seal(concurrent, 1)
        srv.state.revision = 1
        throw srv.stale()
      }
      if (identityPinsUpdate.revision !== srv.state.revision) throw srv.stale()
      srv.state.blob = identityPinsUpdate.blob
      srv.state.revision += 1
      return { blob: srv.state.blob, revision: srv.state.revision }
    })
    return srv
  }

  it('pins the compared key already verified, in ONE sealed write', async () => {
    const srv = fakeServer()
    await markVerified('u1', 'a2V5', { pinIfUnpinned: true })
    expect(srv.putIdentityPins).toHaveBeenCalledTimes(1)
    expect(srv.state.revision).toBe(1)
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1.key).toBe('a2V5')
    expect(pins.u1.state).toBe('verified')
  })

  it('control: without the option an unpinned member is still refused - nothing pins on a plain markVerified', async () => {
    const srv = fakeServer()
    await expect(markVerified('u1', 'a2V5')).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
  })

  it('a concurrent pin of the SAME key merges on 409: it is verified, and the other contact is kept', async () => {
    const srv = serverRacedBy({
      u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 3 },
      other: { key: 'b3Ro', state: 'pinned', firstSeenAt: 1 },
    })
    await markVerified('u1', 'a2V5', { pinIfUnpinned: true })
    const pins = unsealPins(srv.state.blob!, srv.state.revision)
    expect(pins.u1).toEqual({ key: 'a2V5', state: 'verified', firstSeenAt: 3 }) // the concurrent pin's firstSeenAt stays
    expect(pins.other.key).toBe('b3Ro')
  })

  it('a concurrent pin of ANOTHER key (the directory moved meanwhile) throws IdentityMovedError and writes nothing', async () => {
    const srv = serverRacedBy({ u1: { key: 'bmV3', state: 'pinned', firstSeenAt: 3 } })
    await expect(markVerified('u1', 'a2V5', { pinIfUnpinned: true })).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.state.revision).toBe(1) // only the concurrent write landed
    expect(unsealPins(srv.state.blob!, srv.state.revision).u1).toEqual({ key: 'bmV3', state: 'pinned', firstSeenAt: 3 })
  })

  it('a member pinned to another key at load is refused too - the option never overwrites a pin', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'bmV3', state: 'pinned', firstSeenAt: 3 } }, 1), revision: 1 })
    await expect(markVerified('u1', 'a2V5', { pinIfUnpinned: true })).rejects.toBeInstanceOf(IdentityMovedError)
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
  })
})

describe('trustState (badge read)', () => {
  it('folds pin state against the current directory key across all branches', async () => {
    fakeServer({
      blob: seal({
        vf: { key: 'a2V5', state: 'verified', firstSeenAt: 1 },
        pn: { key: 'a2V5', state: 'pinned', firstSeenAt: 1 },
      }, 1),
      revision: 1,
    })
    expect(await trustState('vf', 'a2V5')).toBe('verified')
    expect(await trustState('pn', 'a2V5')).toBe('pinned')
    expect(await trustState('vf', 'b3Ro')).toBe('changed') // key differs → changed even for a verified pin
    expect(await trustState('unknown', 'a2V5')).toBe('unpinned')
  })

  it('trustStates folds a whole directory in one fetch', async () => {
    const srv = fakeServer({
      blob: seal({
        vf: { key: 'a2V5', state: 'verified', firstSeenAt: 1 },
        pn: { key: 'a2V5', state: 'pinned', firstSeenAt: 1 },
        ch: { key: 'b2xk', state: 'pinned', firstSeenAt: 1 },
      }, 1),
      revision: 1,
    })
    const states = await trustStates([
      { userId: 'vf', directoryKey: 'a2V5' },
      { userId: 'pn', directoryKey: 'a2V5' },
      { userId: 'ch', directoryKey: 'bmV3' }, // directory moved away from the pin → changed
      { userId: 'new', directoryKey: 'a2V5' }, // never pinned → unpinned
    ])
    expect(states).toEqual({
      vf: { state: 'verified', pinVerified: true },
      pn: { state: 'pinned', pinVerified: false },
      ch: { state: 'changed', pinVerified: false },
      new: { state: 'unpinned', pinVerified: false },
    })
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(1) // one store fetch for the whole directory
  })

  it('a VERIFIED contact whose directory key moved reads as changed AND verified (ADR 0028 Decision 3)', async () => {
    fakeServer({ blob: seal({ vf: { key: 'a2V5', state: 'verified', firstSeenAt: 1 } }, 1), revision: 1 })
    expect(await trustStates([{ userId: 'vf', directoryKey: 'bmV3' }])).toEqual({ vf: { state: 'changed', pinVerified: true } })
  })
})

describe('accepting a verified contact\'s changed key (ADR 0028 Decision 3)', () => {
  it('re-pins the new key as PINNED - verified returns only through a new compare', async () => {
    const srv = fakeServer({ blob: seal({ vf: { key: 'b2xk', state: 'verified', firstSeenAt: 5 } }, 1), revision: 1 })
    await acceptKeyChange('vf', 'bmV3')
    expect(unsealPins(srv.state.blob!, srv.state.revision).vf).toEqual({ key: 'bmV3', state: 'pinned', firstSeenAt: 5 })
    expect(await trustStates([{ userId: 'vf', directoryKey: 'bmV3' }])).toEqual({ vf: { state: 'pinned', pinVerified: false } })
  })
})

// ADR 0028 Decision 7 — a device never accepts older trust state than it has held. Every case below fixes its lever
// (the payload the server serves) inside the test, and every negative names the refusal it must meet.
describe('the pin store never goes backwards on this device (ADR 0028 Decision 7)', () => {
  const OLD = 'b2xkLWtleQ=='
  const NEW = 'bmV3LWtleQ=='
  const pinOf = (key: string) => ({ u1: { key, state: 'pinned', firstSeenAt: 1 } })

  it('wipe: once this device opened v3, a missing store is refused — the changed key is not pinned as a first use', async () => {
    fakeServer({ blob: seal(pinOf(OLD), 3), revision: 3 })
    expect(await checkAndPin('u1', OLD)).toBe('match') // this device has now held v3
    const wiped = fakeServer({ blob: undefined, revision: 0 })
    await expect(checkAndPin('u1', NEW)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(wiped.putIdentityPins).not.toHaveBeenCalled()
  })

  it('control: a device with no mark reads a missing store as empty, and pins on first use', async () => {
    const srv = fakeServer({ blob: undefined, revision: 0 })
    expect(await checkAndPin('u1', NEW)).toBe('firstUse')
    expect(srv.state.revision).toBe(1)
  })

  it('unopenable: garbage bytes are refused as unreadable, never read as the empty store', async () => {
    const srv = fakeServer({ blob: b64std(v1.randomBytes(64)), revision: 2 })
    await expect(checkAndPin('u1', NEW)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
  })

  it('control: a valid blob at the same revision opens', async () => {
    fakeServer({ blob: seal(pinOf(OLD), 2), revision: 2 })
    expect(await checkAndPin('u1', OLD)).toBe('match')
  })

  it('rollback: a store below the mark cannot reinstate a retired key as a match', async () => {
    fakeServer({ blob: seal(pinOf(NEW), 3), revision: 3 })
    expect((await getPin('u1'))?.key).toBe(NEW) // this device has held v3, where the pin is the new key
    const rolled = fakeServer({ blob: seal(pinOf(OLD), 2), revision: 2 }) // v2 still pins the retired one
    await expect(checkAndPin('u1', OLD)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.putIdentityPins).not.toHaveBeenCalled()
  })

  it('a write raises the mark to the revision it stored — the next missing store is refused', async () => {
    const srv = fakeServer({ blob: undefined, revision: 0 })
    expect(await checkAndPin('u1', NEW)).toBe('firstUse') // stored at v1 by this device
    expect(srv.state.revision).toBe(1)
    fakeServer({ blob: undefined, revision: 0 })
    await expect(checkAndPin('u1', OLD)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('the open before a write is checked too: an accept over a rolled-back store writes nothing', async () => {
    fakeServer({ blob: seal(pinOf(NEW), 3), revision: 3 })
    await getPin('u1')
    const rolled = fakeServer({ blob: seal(pinOf(OLD), 2), revision: 2 })
    await expect(acceptKeyChange('u1', OLD)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.putIdentityPins).not.toHaveBeenCalled()
  })

  it('control: served v3, the retired key reads as changed', async () => {
    fakeServer({ blob: seal(pinOf(NEW), 3), revision: 3 })
    expect((await getPin('u1'))?.key).toBe(NEW)
    expect(await checkAndPin('u1', OLD)).toBe('changed')
  })
})

// The rotation and the reset act on the store WHOLE, through `pinStoreDocument` — their behaviour is proven in
// `rotation/rotateKeys.test.ts` and `trustReset/trustReset.test.ts`, against this real module. What is pinned here
// is that the whole-document view is the same store: the same open (the marks apply), the same seal context.
describe('pinStoreDocument — the store as one whole document (ADR 0028 Decision 7)', () => {
  const pinned = { u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 1 } }

  it('opens through the same marks as every read: a store below the mark is refused, and nothing is written', async () => {
    fakeServer({ blob: seal(pinned, 3), revision: 3 })
    await getPin('u1') // this device has held v3
    const rolled = fakeServer({ blob: seal(pinned, 2), revision: 2 })
    await expect(pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
    expect(rolled.putIdentityPins).not.toHaveBeenCalled()
  })

  it('control: at the mark it opens to the pins, and what it seals for v4 the ordinary read opens at v4', async () => {
    const srv = fakeServer({ blob: seal(pinned, 3), revision: 3 })
    const pins = await pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())
    expect(pins).toEqual(pinned)
    await pinStoreDocument.write({ blob: pinStoreDocument.seal(MK.slice(), pins, 4), revision: 3 })
    expect(srv.state.revision).toBe(4)
    expect(unsealPins(srv.state.blob!, 4)).toEqual(pinned)
  })
})
