import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../../test/httpError'
import { fakeApi } from '../../../test/apiMock'
import type {
  AccountDeviceRegisterApi,
  AccountIdentityPinsApi,
  DeviceRegister,
  DeviceRegisterUpdate,
  IdentityPins,
  IdentityPinsUpdate,
} from '../../../api/generated/account'

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
import { checkAndPin, getPin } from '../identityPins'
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

type Doc = 'pins' | 'register'
const contextOf = (doc: Doc, revision: number) =>
  ({ purpose: doc === 'pins' ? 'identity-pins' : 'device-register', revision }) as const
const PLAIN = {
  pins: { pins: { u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 1 } } },
  register: { devices: { d1: { label: 'Work laptop', lastSeenAt: 1 } }, families: { fam1: 'd1' }, wraps: {} },
}
const EMPTY = { pins: { pins: {} }, register: { devices: {}, families: {}, wraps: {} } }
const sealed = (doc: Doc, revision: number) =>
  b64std(v1.seal(contextOf(doc, revision), MK, enc.encode(JSON.stringify(PLAIN[doc]))))
const opened = (doc: Doc, blob: string, revision: number) =>
  JSON.parse(dec.decode(v1.open(contextOf(doc, revision), MK, fromB64std(blob))))

/** What each document's endpoint stores and serves. */
type Stored = { pins: IdentityPins; register: DeviceRegister }

/** Both documents' endpoints, with the stores' real compare-and-set. `stale` answers that many PUTs 409 first,
 *  as if another device had written — which moves the served copy to `onStale()`'s. */
function fakeServer(
  served: Partial<Stored>,
  opts: { stale?: number; onStale?: (doc: Doc) => IdentityPins & DeviceRegister } = {},
) {
  const state: Stored = {
    pins: { ...(served.pins ?? { blob: null, revision: 0 }) },
    register: { ...(served.register ?? { blob: null, revision: 0 }) },
  }
  let staleLeft = opts.stale ?? 0
  /** The compare-and-set; a landed write answers with the stored document, as the endpoint does. */
  const land = <D extends Doc>(doc: D, update: IdentityPinsUpdate | DeviceRegisterUpdate): Stored[D] => {
    if (staleLeft > 0) {
      staleLeft -= 1
      if (opts.onStale) state[doc] = opts.onStale(doc)
      throw httpError(409, 'stale_revision')
    }
    if (update.revision !== state[doc].revision) throw httpError(409, 'stale_revision')
    state[doc] = { blob: update.blob, revision: state[doc].revision + 1 }
    return { ...state[doc] }
  }
  const putIdentityPins = vi.fn<AccountIdentityPinsApi['putIdentityPins']>(async ({ identityPinsUpdate }) =>
    land('pins', identityPinsUpdate),
  )
  const putDeviceRegister = vi.fn<AccountDeviceRegisterApi['putDeviceRegister']>(async ({ deviceRegisterUpdate }) =>
    land('register', deviceRegisterUpdate),
  )
  fakeApi(accountIdentityPinsApi, { getIdentityPins: async () => ({ ...state.pins }), putIdentityPins })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister: async () => ({ ...state.register }), putDeviceRegister })
  return { state, puts: { pins: putIdentityPins, register: putDeviceRegister } }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  globalThis.indexedDB = new IDBFactory()
})

describe.each(['pins', 'register'] as const)('starting the %s over', (doc) => {
  /** The document's ordinary read — what every surface does after the reset. */
  const readBack = () => (doc === 'pins' ? getPin('u1') : readRegisterView(['fam1'], null, 'me'))

  it('unreadable: writes the empty document one revision up, against the served one, and the mark follows', async () => {
    // Garbage at v3 with mark 3 — what a re-seal that never landed, or corruption at rest, leaves behind.
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer({ [doc]: { blob: GARBAGE, revision: 3 } })
    await expect(readBack()).rejects.toMatchObject({ code: 'trust_state_unreadable' })

    expect(await resetAccountDocument(doc)).toBe('reset')

    expect(srv.puts[doc]).toHaveBeenCalledTimes(1)
    expect(srv.state[doc].revision).toBe(4)
    expect(opened(doc, srv.state[doc].blob!, 4)).toEqual(EMPTY[doc])
    expect((await readMarks(MK))[doc]).toBe(4)
    await expect(readBack()).resolves.toBeDefined() // the refusal is gone
  })

  it('control: a served copy that opens is never wiped — the reset writes nothing', async () => {
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer({ [doc]: { blob: sealed(doc, 3), revision: 3 } })

    expect(await resetAccountDocument(doc)).toBe('alreadyReadable')

    expect(srv.puts[doc]).not.toHaveBeenCalled()
    expect(srv.state[doc].revision).toBe(3)
    expect(opened(doc, srv.state[doc].blob!, 3)).toEqual(PLAIN[doc])
  })

  it('rolled back: the mark is SET down to the revision the reset wrote, and the next read of it opens', async () => {
    // Mark 7, a valid copy served at v4 (a backup restore). The store's compare-and-set can only write v5, which
    // is below the mark — so the reset must lower the mark to 5, or the next read refuses the reset's own write.
    await raiseMarks(MK, { [doc]: 7 })
    const srv = fakeServer({ [doc]: { blob: sealed(doc, 4), revision: 4 } })
    await expect(readBack()).rejects.toMatchObject({ code: 'trust_state_rolled_back' })

    expect(await resetAccountDocument(doc)).toBe('reset')

    expect(srv.state[doc].revision).toBe(5)
    expect(opened(doc, srv.state[doc].blob!, 5)).toEqual(EMPTY[doc])
    expect((await readMarks(MK))[doc]).toBe(5)
    await expect(readBack()).resolves.toBeDefined()
  })

  it('a stale write re-decides: another device made it readable meanwhile, so nothing is wiped', async () => {
    await raiseMarks(MK, { [doc]: 3 })
    const srv = fakeServer(
      { [doc]: { blob: GARBAGE, revision: 3 } },
      { stale: 1, onStale: (d) => ({ blob: sealed(d, 4), revision: 4 }) },
    )

    expect(await resetAccountDocument(doc)).toBe('alreadyReadable')

    expect(srv.puts[doc]).toHaveBeenCalledTimes(1) // the one write that lost
    expect(opened(doc, srv.state[doc].blob!, 4)).toEqual(PLAIN[doc])
  })

  it('touches only the document it was asked to start over', async () => {
    const other: Doc = doc === 'pins' ? 'register' : 'pins'
    await raiseMarks(MK, { [doc]: 3, [other]: 3 })
    const srv = fakeServer({ [doc]: { blob: GARBAGE, revision: 3 }, [other]: { blob: GARBAGE, revision: 3 } })

    await resetAccountDocument(doc)

    expect(srv.puts[other]).not.toHaveBeenCalled()
    expect((await readMarks(MK))[other]).toBe(3)
  })
})

describe('what the reset buys, and what it costs (ADR 0028 Decision 7)', () => {
  it('after starting the pins over, a contact reads as first use again', async () => {
    await raiseMarks(MK, { pins: 3 })
    fakeServer({ pins: { blob: GARBAGE, revision: 3 } })
    await expect(checkAndPin('u1', 'a2V5')).rejects.toMatchObject({ code: 'trust_state_unreadable' })

    await resetAccountDocument('pins')

    expect(await checkAndPin('u1', 'a2V5')).toBe('firstUse')
  })

  it('past the bound of stale writes it rejects, and the mark is unchanged', async () => {
    await raiseMarks(MK, { pins: 7 })
    fakeServer({ pins: { blob: sealed('pins', 4), revision: 4 } }, { stale: 99 })

    await expect(resetAccountDocument('pins')).rejects.toMatchObject({ message: 'http 409 stale_revision' })

    expect((await readMarks(MK)).pins).toBe(7)
  })
})

describe('refusedDocuments — the read the hub offers the reset on', () => {
  it('names each document this device refuses, with its code, and writes nothing', async () => {
    await raiseMarks(MK, { pins: 7, register: 3 })
    const srv = fakeServer({ pins: { blob: sealed('pins', 4), revision: 4 }, register: { blob: GARBAGE, revision: 3 } })

    expect(await refusedDocuments()).toEqual([
      { document: 'pins', code: 'trust_state_rolled_back' },
      { document: 'register', code: 'trust_state_unreadable' },
    ])
    expect(srv.puts.pins).not.toHaveBeenCalled()
    expect(srv.puts.register).not.toHaveBeenCalled()
  })

  it('control: nothing refused, nothing offered — and a network failure is not a refusal', async () => {
    fakeServer({ pins: { blob: sealed('pins', 2), revision: 2 } })
    expect(await refusedDocuments()).toEqual([])

    fakeApi(accountIdentityPinsApi, {
      getIdentityPins: async () => {
        throw httpError(503)
      },
    })
    expect(await refusedDocuments()).toEqual([])
  })
})
