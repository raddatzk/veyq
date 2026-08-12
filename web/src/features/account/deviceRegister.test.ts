import { describe, it, expect, vi, beforeEach } from 'vitest'

// The register's API + vault are mocked; the crypto (seal/unseal under the Master Key) is REAL, so these tests
// prove a genuine round-trip and the version-guarded merge, not a stub.
vi.mock('../../api/client', () => ({ accountDeviceRegisterApi: vi.fn() }))
vi.mock('./login/login', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))

import {
  deviceNames,
  rememberThisDevice,
  reconcileWithSessions,
  renameDeviceOfFamily,
  DEVICE_REGISTER,
  MAX_DEVICES,
  type RegisterPlain,
} from './deviceRegister'
import { accountDeviceRegisterApi } from '../../api/client'
import { masterKey } from '../../app/vault'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

// One fixed Master Key for the suite; the mocked vault hands out a FRESH copy each call because the data layer
// zeroes the copy it receives (returning the same buffer would zero our master after the first use).
const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const dec = new TextDecoder()

const seal = (reg: RegisterPlain) =>
  b64std(v1.symWrap(MK, enc.encode(JSON.stringify(reg)), DEVICE_REGISTER, v1.randomBytes(12)))
const open = (blob: string): RegisterPlain =>
  JSON.parse(dec.decode(v1.symUnwrap(MK, fromB64std(blob), DEVICE_REGISTER)))

const reg = (devices: RegisterPlain['devices'], families: RegisterPlain['families']): RegisterPlain => ({ devices, families })
const dev = (label: string, lastSeenAt = 1_000) => ({ label, lastSeenAt })

/** A fake server with REAL optimistic concurrency: a PUT whose version != the stored version is a 409. */
function fakeServer(initial: { blob: string | null; version: number } = { blob: null, version: 0 }) {
  const state = { ...initial }
  const stale = () => ({ response: { status: 409, clone: () => ({ json: async () => ({ code: 'stale_version' }) }) } })
  const getDeviceRegister = vi.fn(async () => ({ blob: state.blob, version: state.version }))
  const putDeviceRegister = vi.fn(async ({ deviceRegisterUpdate }: { deviceRegisterUpdate: { blob: string; version: number } }) => {
    if (deviceRegisterUpdate.version !== state.version) throw stale()
    state.blob = deviceRegisterUpdate.blob
    state.version += 1
    return { blob: state.blob, version: state.version }
  })
  vi.mocked(accountDeviceRegisterApi).mockReturnValue(
    { getDeviceRegister, putDeviceRegister } as unknown as ReturnType<typeof accountDeviceRegisterApi>,
  )
  return { state, getDeviceRegister, putDeviceRegister }
}

/** Make the NEXT n PUTs lose a race, as if another device wrote between our read and our write. */
function interposeWrites(server: ReturnType<typeof fakeServer>, writes: ReadonlyArray<RegisterPlain>) {
  let i = 0
  const original = server.putDeviceRegister.getMockImplementation()!
  server.putDeviceRegister.mockImplementation(async (arg) => {
    if (i < writes.length) {
      server.state.blob = seal(writes[i])
      server.state.version += 1
      i += 1
      throw { response: { status: 409, clone: () => ({ json: async () => ({ code: 'stale_version' }) }) } }
    }
    return original(arg)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
})

describe('reading names', () => {
  it('labels each family the register can join to a device', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' })), version: 1 })
    expect(await deviceNames()).toEqual({ fam1: 'Work laptop' })
  })

  it('leaves a family whose device is gone unnamed rather than inventing one (§E4)', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1', orphan: 'vanished' })), version: 1 })
    const names = await deviceNames()
    expect(names).toEqual({ fam1: 'Work laptop' })
    expect(names.orphan).toBeUndefined()
  })

  it('a born-empty register names nothing and does not throw', async () => {
    fakeServer()
    expect(await deviceNames()).toEqual({})
  })

  it('an unreadable blob degrades to empty instead of throwing (§E4)', async () => {
    // Sealed under a DIFFERENT key — exactly what a rotated Master Key looks like from here.
    const foreign = b64std(v1.symWrap(v1.randomBytes(32), enc.encode('{}'), DEVICE_REGISTER, v1.randomBytes(12)))
    fakeServer({ blob: foreign, version: 3 })
    expect(await deviceNames()).toEqual({})
  })
})

describe('write moment 1 — remembering this device', () => {
  it('records the device and the family the login created', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 5_000)
    const stored = open(server.state.blob!)
    expect(stored.devices).toEqual({ me: { label: 'Firefox · macOS', lastSeenAt: 5_000 } })
    expect(stored.families).toEqual({ famA: 'me' })
  })

  it('preserves an existing label — a sign-in must never undo a rename', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Arbeitslaptop', 1) }, {})), version: 1 })
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 9_000)
    const stored = open(server.state.blob!)
    expect(stored.devices.me.label).toBe('Arbeitslaptop')
    expect(stored.devices.me.lastSeenAt).toBe(9_000)
  })

  it('records the device with no family when the client never learned one (cold boot restore)', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', null, 5_000)
    const stored = open(server.state.blob!)
    expect(stored.devices.me).toBeDefined()
    expect(stored.families).toEqual({})
  })

  it('union-merges on a 409: a concurrent device\'s entry survives our write', async () => {
    const server = fakeServer()
    interposeWrites(server, [reg({ other: dev('Their phone') }, { famB: 'other' })])
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 5_000)
    const stored = open(server.state.blob!)
    expect(stored.devices.other).toEqual({ label: 'Their phone', lastSeenAt: 1_000 })
    expect(stored.devices.me.label).toBe('Firefox · macOS')
    expect(stored.families).toEqual({ famB: 'other', famA: 'me' })
    expect(server.state.version).toBe(2)
  })
})

describe('write moment 2 — reconciling against the server list', () => {
  it('prunes family rows the server no longer lists', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Laptop') }, { live: 'd1', dead: 'd1' })),
      version: 1,
    })
    await reconcileWithSessions(['live'], 'd1', 'fallback', 'live', 7_000)
    const stored = open(server.state.blob!)
    expect(stored.families).toEqual({ live: 'd1' })
  })

  it('keeps the DEVICE entry when its families all died — a rename must survive an expiry', async () => {
    const server = fakeServer({
      blob: seal(reg({ old: dev('Arbeitslaptop'), me: dev('This one') }, { dead: 'old' })),
      version: 1,
    })
    await reconcileWithSessions([], 'me', 'fallback', null, 7_000)
    const stored = open(server.state.blob!)
    expect(stored.families).toEqual({})
    expect(stored.devices.old.label).toBe('Arbeitslaptop')
  })

  it('adds the current family when write moment 1 could not name it', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Laptop') }, {})), version: 1 })
    await reconcileWithSessions(['famC'], 'me', 'fallback', 'famC', 7_000)
    expect(open(server.state.blob!).families).toEqual({ famC: 'me' })
  })

  it('writes nothing when there is nothing to reconcile', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Laptop') }, { famC: 'me' })), version: 1 })
    expect(await reconcileWithSessions(['famC'], 'me', 'fallback', 'famC', 7_000)).toBe(false)
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
  })

  // THE case that distinguishes the two prune formulations. "Remove exactly these ids" and "keep only what I
  // saw" are indistinguishable on every other test in this file; only a family that appears BETWEEN our read
  // and our write tells them apart — and the wrong one deletes it.
  it('a family created after our read survives the retry (frozen id-delta, not keep-only-what-I-saw)', async () => {
    const server = fakeServer({
      blob: seal(reg({ me: dev('Laptop') }, { dead: 'me', mine: 'me' })),
      version: 1,
    })
    // Another device logs in mid-flight and adds famNew — which is NOT in the list we are reconciling against.
    interposeWrites(server, [reg({ me: dev('Laptop'), other: dev('Phone') }, { dead: 'me', mine: 'me', famNew: 'other' })])

    await reconcileWithSessions(['mine'], 'me', 'fallback', 'mine', 7_000)

    const stored = open(server.state.blob!)
    expect(stored.families.famNew).toBe('other') // survives: never in our doomed set
    expect(stored.families.dead).toBeUndefined() // still pruned: it WAS in our doomed set
    expect(stored.families.mine).toBe('me') // the positive control on the same predicate
  })
})

describe('eviction', () => {
  it('bounds the register at MAX_DEVICES, dropping the least-recently-seen', async () => {
    const devices: RegisterPlain['devices'] = {}
    const families: RegisterPlain['families'] = {}
    for (let i = 0; i < MAX_DEVICES; i++) {
      devices[`d${i}`] = dev(`device ${i}`, 1_000 + i)
      families[`f${i}`] = `d${i}`
    }
    const server = fakeServer({ blob: seal(reg(devices, families)), version: 1 })

    await rememberThisDevice('newest', 'Newest', 'fNew', 99_999)

    const stored = open(server.state.blob!)
    expect(Object.keys(stored.devices)).toHaveLength(MAX_DEVICES)
    expect(stored.devices.newest).toBeDefined()
    expect(stored.devices.d0).toBeUndefined() // the oldest lastSeenAt went
    expect(stored.devices.d1).toBeDefined() // …and only the oldest
    // A family pointing at an evicted device goes with it — an unresolvable join is worse than no join.
    expect(stored.families.f0).toBeUndefined()
    expect(stored.families.f1).toBe('d1')
  })

  // The mirror image of the frozen-id-delta case: eviction's truth is the register's own contents, so it must be
  // recomputed on the MERGED map. Frozen from the pre-merge map it would evict by a stale ordering and could
  // leave the register over its own bound.
  it('is recomputed on the merged map after a 409, not frozen from the pre-merge one', async () => {
    const devices: RegisterPlain['devices'] = {}
    for (let i = 0; i < MAX_DEVICES - 1; i++) devices[`d${i}`] = dev(`device ${i}`, 1_000 + i)
    const server = fakeServer({ blob: seal(reg(devices, {})), version: 1 })

    // A concurrent device pushes the map to exactly MAX_DEVICES; our own addition then makes it one too many,
    // which only a recomputed eviction can see.
    const bumped = { ...devices, concurrent: dev('Concurrent', 50_000) }
    interposeWrites(server, [reg(bumped, {})])

    await rememberThisDevice('newest', 'Newest', null, 99_999)

    const stored = open(server.state.blob!)
    expect(Object.keys(stored.devices)).toHaveLength(MAX_DEVICES)
    expect(stored.devices.newest).toBeDefined()
    expect(stored.devices.concurrent).toBeDefined()
    expect(stored.devices.d0).toBeUndefined()
  })
})

describe('renaming', () => {
  it('renames the device the family belongs to, reaching every session of it', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Firefox · macOS') }, { famA: 'd1', famB: 'd1' })),
      version: 1,
    })
    await renameDeviceOfFamily('famA', 'Arbeitslaptop')
    expect(open(server.state.blob!).devices.d1.label).toBe('Arbeitslaptop')
    expect(await deviceNames()).toEqual({ famA: 'Arbeitslaptop', famB: 'Arbeitslaptop' })
  })

  it('is a no-op for a family the register cannot name — there is no device to rename', async () => {
    const server = fakeServer({ blob: seal(reg({}, {})), version: 1 })
    await renameDeviceOfFamily('unknown', 'Whatever')
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('the later write wins on a 409 — a rename is deliberate', async () => {
    const server = fakeServer({ blob: seal(reg({ d1: dev('Old') }, { famA: 'd1' })), version: 1 })
    interposeWrites(server, [reg({ d1: dev('Concurrent rename') }, { famA: 'd1' })])
    await renameDeviceOfFamily('famA', 'Mine wins')
    expect(open(server.state.blob!).devices.d1.label).toBe('Mine wins')
  })
})

describe('what never reaches the server', () => {
  it('the PUT body carries only the sealed blob and the version', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 5_000)
    const body = server.putDeviceRegister.mock.calls[0][0].deviceRegisterUpdate
    expect(Object.keys(body).sort()).toEqual(['blob', 'version'])
    // The label is genuinely inside the ciphertext, not merely absent from a field name.
    expect(body.blob).not.toContain('Firefox')
    expect(open(body.blob).devices.me.label).toBe('Firefox · macOS')
  })
})
