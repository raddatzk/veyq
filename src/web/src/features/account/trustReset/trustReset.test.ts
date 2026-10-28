import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../../test/httpError'
import { fakeApi } from '../../../test/apiMock'
import { fakePinServer, identityKey, pinOf, pinsOn, sealPinStore, SOME_HEAD, type PinServerState } from '../../../test/pinStore'
import type { AccountDeviceRegisterApi, DeviceRegister } from '../../../api/generated/account'

// Starting a refused trust document over (ADR 0028 Decision 7's way back, inbox #192). The endpoints and the vault
// are faked; the two document modules, the marks and the crypto are REAL — so "the reset wrote the empty store"
// is asserted by OPENING what was written, and "the mark went down" by the next ordinary read accepting it.
vi.mock('../../../api/client', () => ({
  accountIdentityPinsApi: vi.fn(),
  accountDeviceRegisterApi: vi.fn(),
  tasksApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import { resetAccountDocument } from './trustReset'
import { refusedDocuments } from './refusedDocuments'
import { anchorIdentity, forgetVerifiedPins, getPin } from '../identityPins'
import { readRegisterView } from '../deviceRegister'
import { raiseMarks, readMarks } from '../trustMarks'
import { accountDeviceRegisterApi, accountIdentityPinsApi } from '../../../api/client'
import { masterKey } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const dec = new TextDecoder()
const GARBAGE = b64std(v1.randomBytes(64))
const U1 = 'c0ffee00-0000-4000-8000-0000000000a1'
const KEY = identityKey()

type Doc = 'pins' | 'register'
// The pin store is a head plus entries (ADR 0104), the register one blob: each document's served copy is its own shape.
type Served = { pins: PinServerState; register: DeviceRegister }

const registerAt = (revision: number) => ({ purpose: 'device-register', revision }) as const
const PLAIN = {
  pins: { [U1]: pinOf(KEY) },
  register: { devices: { d1: { label: 'Work laptop', lastSeenAt: 1 } }, families: { fam1: 'd1' }, wraps: {} },
}
const EMPTY = { pins: {}, register: { devices: {}, families: {}, wraps: {} } }

/** A valid copy of `doc` at `revision`, sealed under the account's key. */
function sealed<D extends Doc>(doc: D, revision: number): Served[D] {
  if (doc === 'pins') return sealPinStore(MK, PLAIN.pins, revision) as Served[D]
  return { blob: b64std(v1.seal(registerAt(revision), MK, enc.encode(JSON.stringify(PLAIN.register)))), revision } as Served[D]
}

/** Bytes that open as nothing, at `revision`. */
function garbage<D extends Doc>(doc: D, revision: number): Served[D] {
  return (doc === 'pins' ? { blob: GARBAGE, revision, entries: new Map() } : { blob: GARBAGE, revision }) as Served[D]
}

/** Both documents' endpoints, with the stores' real compare-and-set. `stale` answers that many writes 409 first,
 *  as if another device had written — which moves the served copy to `onStale()`'s. */
function fakeServer(served: Partial<Served>, opts: { stale?: number; onStale?: <D extends Doc>(doc: D) => Served[D] } = {}) {
  let staleLeft = opts.stale ?? 0
  const pins = fakePinServer(served.pins)
  const real = pins.writeIdentityPins.getMockImplementation()!
  pins.writeIdentityPins.mockImplementation(async (req) => {
    if (staleLeft > 0) {
      staleLeft -= 1
      if (opts.onStale) pins.become(opts.onStale('pins'))
      throw httpError(409, 'stale_revision')
    }
    return real(req)
  })
  const register: DeviceRegister = { ...(served.register ?? { revision: 0 }) }
  const putDeviceRegister = vi.fn<AccountDeviceRegisterApi['putDeviceRegister']>(async ({ deviceRegisterUpdate }) => {
    if (staleLeft > 0) {
      staleLeft -= 1
      if (opts.onStale) Object.assign(register, opts.onStale('register'))
      throw httpError(409, 'stale_revision')
    }
    if (deviceRegisterUpdate.revision !== register.revision) throw httpError(409, 'stale_revision')
    Object.assign(register, { blob: deviceRegisterUpdate.blob, revision: register.revision + 1 })
    return { ...register }
  })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister: async () => ({ ...register }), putDeviceRegister })
  return {
    puts: { pins: pins.writeIdentityPins, register: putDeviceRegister },
    revision: (doc: Doc) => (doc === 'pins' ? pins.state.revision : register.revision),
    /** What the server holds for `doc`, opened with the account's key. */
    opened: (doc: Doc): unknown =>
      doc === 'pins'
        ? pinsOn(MK, pins.state)
        : JSON.parse(dec.decode(v1.open(registerAt(register.revision), MK, fromB64std(register.blob!)))),
    pinEntries: () => pins.state.entries.size,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  forgetVerifiedPins()
  globalThis.indexedDB = new IDBFactory()
})

describe.each(['pins', 'register'] as const)('starting the %s over', (doc) => {
  /** The document's ordinary read — what every surface does after the reset. */
  const readBack = () => (doc === 'pins' ? getPin(U1) : readRegisterView(['fam1'], null, 'me'))

  it('unreadable: writes the empty document one revision up, against the served one, and the mark follows', async () => {
    // Garbage at v3 with mark 3 — what a re-seal that never landed, or corruption at rest, leaves behind.
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer({ [doc]: garbage(doc, 3) } as Partial<Served>)
    await expect(readBack()).rejects.toMatchObject({ code: 'trust_state_unreadable' })

    expect(await resetAccountDocument(doc)).toBe('reset')

    expect(srv.puts[doc]).toHaveBeenCalledTimes(1)
    expect(srv.revision(doc)).toBe(4)
    expect(srv.opened(doc)).toEqual(EMPTY[doc])
    expect((await readMarks(MK))[doc]).toBe(4)
    await expect(readBack()).resolves.toBeDefined() // the refusal is gone
  })

  it('control: a served copy that opens is never wiped — the reset writes nothing', async () => {
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer({ [doc]: sealed(doc, 3) } as Partial<Served>)

    expect(await resetAccountDocument(doc)).toBe('alreadyReadable')

    expect(srv.puts[doc]).not.toHaveBeenCalled()
    expect(srv.revision(doc)).toBe(3)
    expect(srv.opened(doc)).toEqual(PLAIN[doc])
  })

  it('rolled back: the mark is SET down to the revision the reset wrote, and the next read of it opens', async () => {
    // Mark 7, a valid copy served at v4 (a backup restore). The store's compare-and-set can only write v5, which
    // is below the mark — so the reset must lower the mark to 5, or the next read refuses the reset's own write.
    await raiseMarks(MK, { [doc]: 7 })
    const srv = fakeServer({ [doc]: sealed(doc, 4) } as Partial<Served>)
    await expect(readBack()).rejects.toMatchObject({ code: 'trust_state_rolled_back' })

    expect(await resetAccountDocument(doc)).toBe('reset')

    expect(srv.revision(doc)).toBe(5)
    expect(srv.opened(doc)).toEqual(EMPTY[doc]) // for the pins: the stored entry is gone too
    expect((await readMarks(MK))[doc]).toBe(5)
    await expect(readBack()).resolves.toBeDefined()
  })

  it('a stale write re-decides: another device made it readable meanwhile, so nothing is wiped', async () => {
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer(
      { [doc]: garbage(doc, 3) } as Partial<Served>,
      { stale: 1, onStale: (d) => sealed(d, 4) },
    )

    expect(await resetAccountDocument(doc)).toBe('alreadyReadable')

    expect(srv.puts[doc]).toHaveBeenCalledTimes(1) // the one write that lost
    expect(srv.opened(doc)).toEqual(PLAIN[doc])
  })

  it('touches only the document it was asked to start over', async () => {
    const other: Doc = doc === 'pins' ? 'register' : 'pins'
    await raiseMarks(MK, { [doc]: 3, [other]: 3 })
    const srv = fakeServer({ [doc]: garbage(doc, 3), [other]: garbage(other, 3) } as Partial<Served>)

    await resetAccountDocument(doc)

    expect(srv.puts[other]).not.toHaveBeenCalled()
    expect((await readMarks(MK))[other]).toBe(3)
  })
})

describe('what the reset buys, and what it costs (ADR 0028 Decision 7)', () => {
  it('after starting the pins over, a contact reads as first use again', async () => {
    await raiseMarks(MK, { pins: 3 })
    fakeServer({ pins: garbage('pins', 3) })
    await expect(anchorIdentity(U1, { identityPublicKey: KEY, keyChainHead: SOME_HEAD }, { firstUse: 'served' })).rejects.toMatchObject({ code: 'trust_state_unreadable' })

    await resetAccountDocument('pins')

    expect(await anchorIdentity(U1, { identityPublicKey: KEY, keyChainHead: SOME_HEAD }, { firstUse: 'served' })).toBe('firstUse')
  })

  it('past the bound of stale writes it rejects, and the mark is unchanged', async () => {
    await raiseMarks(MK, { pins: 7 })
    fakeServer({ pins: sealed('pins', 4) }, { stale: 99 })

    await expect(resetAccountDocument('pins')).rejects.toMatchObject({ message: 'http 409 stale_revision' })

    expect((await readMarks(MK)).pins).toBe(7)
  })
})

describe('refusedDocuments — the read the hub offers the reset on', () => {
  it('names each document this device refuses, with its code, and writes nothing', async () => {
    await raiseMarks(MK, { pins: 7, register: 3 })
    const srv = fakeServer({ pins: sealed('pins', 4), register: garbage('register', 3) })

    expect(await refusedDocuments()).toEqual([
      { document: 'pins', code: 'trust_state_rolled_back' },
      { document: 'register', code: 'trust_state_unreadable' },
    ])
    expect(srv.puts.pins).not.toHaveBeenCalled()
    expect(srv.puts.register).not.toHaveBeenCalled()
  })

  it('control: nothing refused, nothing offered — and a network failure is not a refusal', async () => {
    fakeServer({ pins: sealed('pins', 2) })
    expect(await refusedDocuments()).toEqual([])

    fakeApi(accountIdentityPinsApi, {
      getIdentityPins: async () => {
        throw httpError(503)
      },
    })
    expect(await refusedDocuments()).toEqual([])
  })
})
