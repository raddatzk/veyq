// The account's kit record (ADR 0157 decisions 1-4, uc.account.renew-emergency-kit E4): sealed under the Master Key with
// purpose `kit-record` and no binding field, the compact JSON `{"layout":n}`; no record, a foreign or garbled blob, and
// JSON without a positive integer layout all read as layout 1 — never an error. The layout is read once per unlock and
// kept until the keys go; a write remembers that the kit is current.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { AccountKitRecordApi } from '../../../api/generated/account'

vi.mock('../../../api/client', () => ({ accountKitRecordApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
const vault = vi.hoisted(() => ({ mk: null as Uint8Array | null }))
vi.mock('../../../app/vault', () => ({ masterKey: vi.fn(() => (vault.mk ? vault.mk.slice() : null)) }))

import { accountKitRecordApi } from '../../../api/client'
import { fakeApi } from '../../../test/apiMock'
import {
  ensureKitLayout, kitLayoutOlder, kitLayoutState, LAYOUT_WITHOUT_RECORD, layoutOf, readKitLayout, rememberCurrentKit, resetKitLayout,
  sealKitRecord, writeKitRecord,
} from './kitRecord'
import { KIT_LAYOUT } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../../i18n'

const MK = v1.randomBytes(32)
const enc = new TextEncoder()
/** A record of [plain] as another writer would seal it: the purpose, under [mk]. */
const sealed = (mk: Uint8Array, plain: string) => b64std(v1.seal({ purpose: 'kit-record' }, mk, enc.encode(plain)))

function serve(blob: string | undefined) {
  return fakeApi(accountKitRecordApi, {
    getKitRecord: vi.fn<AccountKitRecordApi['getKitRecord']>().mockResolvedValue(blob === undefined ? {} : { blob }),
    putKitRecord: vi.fn<AccountKitRecordApi['putKitRecord']>().mockResolvedValue(undefined),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vault.mk = MK.slice()
  resetKitLayout()
})

describe('the kit layout (ADR 0157 decisions 1-2)', () => {
  it('this client prints layout 2: the sheet whose key is labelled "9 blocks in 3 lines"', () => {
    // admin-web's `features/setup/emergencyKit.test.ts` pins the same number for its writer: a raise touches both.
    expect(KIT_LAYOUT).toBe(2)
  })

  it('every layout from 2 to KIT_LAYOUT says what it does better, in both languages (kit.layoutNews.<n>)', () => {
    for (let n = 2; n <= KIT_LAYOUT; n++) {
      for (const locale of SUPPORTED_LOCALES) {
        expect(i18n.global.te(`kit.layoutNews.${n}`, locale), `${locale}: kit.layoutNews.${n}`).toBe(true)
        expect(i18n.global.t(`kit.layoutNews.${n}`, {}, { locale })).not.toBe(`kit.layoutNews.${n}`)
      }
    }
  })
})

describe('the record round trip', () => {
  it('seals {"layout":KIT_LAYOUT} under the Master Key, compact, and opens back to it', () => {
    const blob = sealKitRecord(MK)
    expect(new TextDecoder().decode(v1.open({ purpose: 'kit-record' }, MK, Uint8Array.from(atob(blob), (c) => c.charCodeAt(0))))).toBe(
      `{"layout":${KIT_LAYOUT}}`,
    )
    expect(layoutOf(MK, blob)).toBe(KIT_LAYOUT)
  })

  it('reads the contract vector’s blob, written by an independent implementation, as its layout', () => {
    const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
    const v = vectors.seal.sym['kit-record']
    const hex = (s: string) => Uint8Array.from(s.match(/../g)!.map((b) => parseInt(b, 16)))
    expect(layoutOf(hex(v.kek), b64std(hex(v.blob)))).toBe(2)
  })

  it('no record, a foreign key, a garbled blob and a layout that is no positive integer all read as layout 1', () => {
    expect(LAYOUT_WITHOUT_RECORD).toBe(1)
    expect(layoutOf(MK, undefined)).toBe(1)
    expect(layoutOf(MK, null)).toBe(1)
    expect(layoutOf(MK, '')).toBe(1)
    expect(layoutOf(v1.randomBytes(32), sealKitRecord(MK)), 'another account’s record').toBe(1)
    expect(layoutOf(MK, b64std(v1.randomBytes(48))), 'garbage').toBe(1)
    expect(layoutOf(MK, sealed(MK, 'not json'))).toBe(1)
    for (const plain of ['{}', '{"layout":0}', '{"layout":-3}', '{"layout":2.5}', '{"layout":"2"}', 'null', '[2]']) {
      expect(layoutOf(MK, sealed(MK, plain)), plain).toBe(1)
    }
    expect(layoutOf(MK, sealed(MK, '{"layout":7}')), 'control: a newer writer’s layout').toBe(7)
  })
})

describe('reading and writing it', () => {
  it('readKitLayout GETs the caller’s record and opens it; an absent blob is layout 1', async () => {
    serve(sealed(MK, '{"layout":1}'))
    expect(await readKitLayout(MK)).toBe(1)
    serve(undefined)
    expect(await readKitLayout(MK)).toBe(1)
    serve(sealKitRecord(MK))
    expect(await readKitLayout(MK)).toBe(KIT_LAYOUT)
  })

  it('writeKitRecord PUTs the sealed blob as it is and remembers that the kit is current', async () => {
    const api = serve(undefined)
    kitLayoutState.layout = 1
    const blob = sealKitRecord(MK)
    await writeKitRecord(blob)
    expect(api.putKitRecord).toHaveBeenCalledWith({ kitRecordUpdate: { blob } })
    expect(kitLayoutState.layout).toBe(KIT_LAYOUT)
    expect(kitLayoutOlder.value).toBe(false)
  })

  it('a refused write remembers nothing', async () => {
    const api = serve(undefined)
    api.putKitRecord.mockRejectedValue(new Error('offline'))
    kitLayoutState.layout = 1
    await expect(writeKitRecord(sealKitRecord(MK))).rejects.toThrow('offline')
    expect(kitLayoutOlder.value).toBe(true)
  })
})

describe('once per unlock (decision 6)', () => {
  it('the first caller reads, every later one shares the answer — one GET', async () => {
    const api = serve(sealed(MK, '{"layout":1}'))
    await Promise.all([ensureKitLayout(), ensureKitLayout()])
    await ensureKitLayout()
    expect(api.getKitRecord).toHaveBeenCalledTimes(1)
    expect(kitLayoutState.layout).toBe(1)
    expect(kitLayoutOlder.value).toBe(true)
  })

  it('reads nothing while the vault is locked', async () => {
    const api = serve(undefined)
    vault.mk = null
    await ensureKitLayout()
    expect(api.getKitRecord).not.toHaveBeenCalled()
    expect(kitLayoutState.layout).toBeNull()
  })

  it('a failed read leaves the layout unknown and suggests nothing; the next caller asks again', async () => {
    const api = serve(undefined)
    api.getKitRecord.mockRejectedValueOnce(new Error('offline'))
    await ensureKitLayout()
    expect(kitLayoutState).toMatchObject({ layout: null, failed: true })
    expect(kitLayoutOlder.value).toBe(false)
    await ensureKitLayout()
    expect(kitLayoutState).toMatchObject({ layout: 1, failed: false })
  })

  it('a reset drops the answer, and a read still on its way when the keys went installs nothing', async () => {
    let answer!: (r: { blob?: string }) => void
    const api = serve(undefined)
    api.getKitRecord.mockReturnValueOnce(new Promise((r) => (answer = r)))
    const pending = ensureKitLayout()
    resetKitLayout()
    answer({ blob: sealed(MK, '{"layout":1}') })
    await pending
    expect(kitLayoutState.layout).toBeNull()
  })

  it('a kit made meanwhile wins over a read that answered for the record before it', async () => {
    let answer!: (r: { blob?: string }) => void
    const api = serve(undefined)
    api.getKitRecord.mockReturnValueOnce(new Promise((r) => (answer = r)))
    const pending = ensureKitLayout()
    rememberCurrentKit()
    answer({})
    await pending
    expect(kitLayoutState.layout).toBe(KIT_LAYOUT)
  })
})
