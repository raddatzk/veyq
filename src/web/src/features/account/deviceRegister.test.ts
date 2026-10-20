import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../test/httpError'
import { fakeApi } from '../../test/apiMock'
import type { AccountDeviceRegisterApi as RegisterApi, DeviceRegister } from '../../api/generated/account'

// The register's API + vault are mocked; the crypto (seal/unseal under the Master Key) is REAL, so these tests
// prove a genuine round-trip and the revision-guarded merge, not a stub.
vi.mock('../../api/client', () => ({ accountDeviceRegisterApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))

import {
  readRegisterView,
  rememberThisDevice,
  rememberThisDeviceWrap,
  reconcileWithSessions,
  renameDeviceOfFamily,
  MAX_DEVICES,
  registerDocument,
  type RegisterPlain,
} from './deviceRegister'
import { accountDeviceRegisterApi } from '../../api/client'
import { masterKey } from '../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// One fixed Master Key for the suite; the mocked vault hands out a FRESH copy each call because the data layer
// zeroes the copy it receives (returning the same buffer would zero our master after the first use).
const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const dec = new TextDecoder()

// The register is sealed bound to the revision it is stored at (ADR 0072), so a fixture names its revision too.
const registerAt = (revision: number) => ({ purpose: 'device-register', revision }) as const
const seal = (reg: RegisterPlain, revision: number) =>
  b64std(v1.seal(registerAt(revision), MK, enc.encode(JSON.stringify(reg))))
const open = (blob: string, revision: number): RegisterPlain =>
  JSON.parse(dec.decode(v1.open(registerAt(revision), MK, fromB64std(blob))))

const reg = (
  devices: RegisterPlain['devices'],
  families: RegisterPlain['families'],
  wraps: RegisterPlain['wraps'] = {},
): RegisterPlain => ({ devices, families, wraps })
const dev = (label: string, lastSeenAt = 1_000) => ({ label, lastSeenAt })

/** The names half of the view, which is what most of these tests are about. */
const namesOf = (liveFamilyIds: ReadonlyArray<string>, thisDeviceId = 'nobody') =>
  readRegisterView(liveFamilyIds, null, thisDeviceId).then((v) => v.names)

/** A fake server with REAL optimistic concurrency: a PUT whose revision != the stored revision is a 409. */
function fakeServer(initial: DeviceRegister = { blob: undefined, revision: 0 }) {
  const state: DeviceRegister = { ...initial }
  const stale = () => httpError(409, 'stale_revision')
  const getDeviceRegister = vi.fn<RegisterApi['getDeviceRegister']>(async () => ({ blob: state.blob, revision: state.revision }))
  const putDeviceRegister = vi.fn<RegisterApi['putDeviceRegister']>(async ({ deviceRegisterUpdate }) => {
    if (deviceRegisterUpdate.revision !== state.revision) throw stale()
    state.blob = deviceRegisterUpdate.blob
    state.revision += 1
    return { blob: state.blob, revision: state.revision }
  })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister, putDeviceRegister })
  return { state, getDeviceRegister, putDeviceRegister }
}

/** Make the NEXT n PUTs lose a race, as if another device wrote between our read and our write. */
function interposeWrites(server: ReturnType<typeof fakeServer>, writes: ReadonlyArray<RegisterPlain>) {
  let i = 0
  const original = server.putDeviceRegister.getMockImplementation()!
  server.putDeviceRegister.mockImplementation(async (arg) => {
    if (i < writes.length) {
      server.state.revision += 1
      server.state.blob = seal(writes[i], server.state.revision)
      i += 1
      throw httpError(409, 'stale_revision')
    }
    return original(arg)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  // This device's local trust-state marks (ADR 0028 Decision 7): a fresh, empty store per test.
  globalThis.indexedDB = new IDBFactory()
})

describe('reading names', () => {
  it('labels each family the register can join to a device', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' }), 1), revision: 1 })
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' })
  })

  it('leaves a family whose device is gone unnamed rather than inventing one (§E4)', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1', orphan: 'vanished' }), 1), revision: 1 })
    const names = await namesOf(['fam1', 'orphan'])
    expect(names).toEqual({ fam1: 'Work laptop' })
    expect(names.orphan).toBeUndefined()
  })

  it('a born-empty register names nothing and does not throw', async () => {
    fakeServer()
    expect(await namesOf([])).toEqual({})
  })

  it('an unreadable blob is refused as unreadable, never read as an empty register (ADR 0028 Decision 7)', async () => {
    // Sealed under a DIFFERENT key — exactly what a rotation whose re-seal never landed looks like from here. The
    // sessions view still lists and revokes every row unnamed (§E4); it is the register that refuses.
    const foreign = b64std(v1.seal(registerAt(3), v1.randomBytes(32), enc.encode('{}')))
    const server = fakeServer({ blob: foreign, revision: 3 })
    await expect(namesOf(['fam1'])).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    // …and a write never re-seals over it, which is how the old degrade-to-empty silently replaced it.
    await expect(rememberThisDevice('me', 'Firefox', 'fam1', null)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('control: a register sealed under the account key at that revision opens', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' }), 3), revision: 3 })
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' })
  })
})

describe('write moment 1 — remembering this device', () => {
  it('records the device and the family the login created', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', null, 5_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices).toEqual({ me: { label: 'Firefox · macOS', lastSeenAt: 5_000 } })
    expect(stored.families).toEqual({ famA: 'me' })
  })

  it('preserves an existing label — a sign-in must never undo a rename', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Arbeitslaptop', 1) }, {}), 1), revision: 1 })
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', null, 9_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices.me.label).toBe('Arbeitslaptop')
    expect(stored.devices.me.lastSeenAt).toBe(9_000)
  })

  it('records the device with no family when the client never learned one (cold boot restore)', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', null, null, 5_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices.me).toBeDefined()
    expect(stored.families).toEqual({})
  })

  it('union-merges on a 409: a concurrent device\'s entry survives our write', async () => {
    const server = fakeServer()
    interposeWrites(server, [reg({ other: dev('Their phone') }, { famB: 'other' })])
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', null, 5_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices.other).toEqual({ label: 'Their phone', lastSeenAt: 1_000 })
    expect(stored.devices.me.label).toBe('Firefox · macOS')
    expect(stored.families).toEqual({ famB: 'other', famA: 'me' })
    expect(server.state.revision).toBe(2)
  })

  // Self-healing: the wrap row is re-asserted from this device's LOCAL record on every unlock, so a row lost to
  // a prune, a failed write, or an operator that briefly withheld the wrap comes back on its own.
  it('re-asserts this device\'s wrap row from its local record', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Laptop') }, { famA: 'me' }, {}), 1), revision: 1 })
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 'w-mine', 5_000)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({ 'w-mine': 'me' })
  })

  it('records no wrap row when this device holds no convenience wrap yet', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', null, 5_000)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({})
  })
})

describe('write moment 2 — the enrolment that mints the id', () => {
  it('records the join at the one moment the new wrap id exists', async () => {
    const server = fakeServer()
    await rememberThisDeviceWrap('me', 'Firefox · macOS', 'w1', 5_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.wraps).toEqual({ w1: 'me' })
    // The device entry is upserted alongside: write moment 1 is fire-and-forget, so this can land first and a
    // wrap row pointing at a device with no entry is an unresolvable join.
    expect(stored.devices.me).toEqual({ label: 'Firefox · macOS', lastSeenAt: 5_000 })
  })

  it('follows the id across a re-enrolment, which mints a new one and deletes the old', async () => {
    const server = fakeServer()
    await rememberThisDeviceWrap('me', 'Firefox · macOS', 'w-old', 5_000)
    // Re-enrolment: a fresh id (ADR 0005 §9). The old row is NOT dropped here — its truth is the server's wrap
    // list, and only write moment 3's frozen delta may prune it.
    await rememberThisDeviceWrap('me', 'Firefox · macOS', 'w-new', 6_000)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({ 'w-old': 'me', 'w-new': 'me' })

    // …and that is what the reconcile against the served list then does.
    await reconcileWithSessions([], ['w-new'], 'me', 'fallback', null, 7_000)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({ 'w-new': 'me' })
  })

  it('preserves an existing label — enrolling must never undo a rename', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Arbeitslaptop', 1) }, {}), 1), revision: 1 })
    await rememberThisDeviceWrap('me', 'Firefox · macOS', 'w1', 9_000)
    expect(open(server.state.blob!, server.state.revision).devices.me.label).toBe('Arbeitslaptop')
  })
})

describe('write moment 3 — reconciling against the server lists', () => {
  it('prunes family rows the server no longer lists', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Laptop') }, { live: 'd1', dead: 'd1' }), 1),
      revision: 1,
    })
    await reconcileWithSessions(['live'], null, 'd1', 'fallback', 'live', 7_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.families).toEqual({ live: 'd1' })
  })

  it('keeps the DEVICE entry when its families all died — a rename must survive an expiry', async () => {
    const server = fakeServer({
      blob: seal(reg({ old: dev('Arbeitslaptop'), me: dev('This one') }, { dead: 'old' }), 1),
      revision: 1,
    })
    await reconcileWithSessions([], null, 'me', 'fallback', null, 7_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.families).toEqual({})
    expect(stored.devices.old.label).toBe('Arbeitslaptop')
  })

  it('adds the current family when write moment 1 could not name it', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Laptop') }, {}), 1), revision: 1 })
    await reconcileWithSessions(['famC'], null, 'me', 'fallback', 'famC', 7_000)
    expect(open(server.state.blob!, server.state.revision).families).toEqual({ famC: 'me' })
  })

  it('writes nothing when there is nothing to reconcile', async () => {
    const server = fakeServer({ blob: seal(reg({ me: dev('Laptop') }, { famC: 'me' }), 1), revision: 1 })
    expect(await reconcileWithSessions(['famC'], null, 'me', 'fallback', 'famC', 7_000)).toBe(false)
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
  })

  // THE case that distinguishes the two prune formulations. "Remove exactly these ids" and "keep only what I
  // saw" are indistinguishable on every other test in this file; only a family that appears BETWEEN our read
  // and our write tells them apart — and the wrong one deletes it.
  it('a family created after our read survives the retry (frozen id-delta, not keep-only-what-I-saw)', async () => {
    const server = fakeServer({
      blob: seal(reg({ me: dev('Laptop') }, { dead: 'me', mine: 'me' }), 1),
      revision: 1,
    })
    // Another device logs in mid-flight and adds famNew — which is NOT in the list we are reconciling against.
    interposeWrites(server, [reg({ me: dev('Laptop'), other: dev('Phone') }, { dead: 'me', mine: 'me', famNew: 'other' })])

    await reconcileWithSessions(['mine'], null, 'me', 'fallback', 'mine', 7_000)

    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.families.famNew).toBe('other') // survives: never in our doomed set
    expect(stored.families.dead).toBeUndefined() // still pruned: it WAS in our doomed set
    expect(stored.families.mine).toBe('me') // the positive control on the same predicate
  })

  it('prunes wrap rows the server no longer serves', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Laptop') }, {}, { live: 'd1', dead: 'd1' }), 1),
      revision: 1,
    })
    await reconcileWithSessions([], ['live'], 'd1', 'fallback', null, 7_000)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({ live: 'd1' })
  })

  // The wrap half of the same distinction — and the one that matters most, because the wrong formulation here
  // deletes the join row a CONCURRENT device just minted, silently un-escalating the very device it named.
  it('a wrap minted after our read survives the retry (second frozen id-delta)', async () => {
    const server = fakeServer({
      blob: seal(reg({ me: dev('Laptop') }, {}, { dead: 'me', mine: 'me' }), 1),
      revision: 1,
    })
    interposeWrites(server, [
      reg({ me: dev('Laptop'), other: dev('Phone') }, {}, { dead: 'me', mine: 'me', wNew: 'other' }),
    ])

    await reconcileWithSessions([], ['mine'], 'me', 'fallback', null, 7_000)

    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.wraps.wNew).toBe('other') // survives: never in our doomed set
    expect(stored.wraps.dead).toBeUndefined() // still pruned: it WAS in our doomed set
    expect(stored.wraps.mine).toBe('me') // the positive control on the same predicate
  })

  // `null` is "I do not hold the wrap list" (the key-material fetch failed). An empty array is the opposite
  // claim — "the server serves none" — and only a successful fetch may make it. Confusing the two would wipe
  // every join row on a transport error, which is exactly the withholding shape the register must not have.
  it('prunes no wrap row when the caller does not hold the served list', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Laptop') }, { famA: 'd1' }, { w1: 'd1' }), 1),
      revision: 1,
    })
    expect(await reconcileWithSessions(['famA'], null, 'd1', 'fallback', 'famA', 7_000)).toBe(false)
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({ w1: 'd1' })
  })

  it('an empty served list DOES prune — that is a fact the caller measured', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Laptop') }, { famA: 'd1' }, { w1: 'd1' }), 1),
      revision: 1,
    })
    expect(await reconcileWithSessions(['famA'], [], 'd1', 'fallback', 'famA', 7_000)).toBe(true)
    expect(open(server.state.blob!, server.state.revision).wraps).toEqual({})
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
    const server = fakeServer({ blob: seal(reg(devices, families), 1), revision: 1 })

    await rememberThisDevice('newest', 'Newest', 'fNew', 'wNew', 99_999)

    const stored = open(server.state.blob!, server.state.revision)
    expect(Object.keys(stored.devices)).toHaveLength(MAX_DEVICES)
    expect(stored.devices.newest).toBeDefined()
    expect(stored.devices.d0).toBeUndefined() // the oldest lastSeenAt went
    expect(stored.devices.d1).toBeDefined() // …and only the oldest
    // A family pointing at an evicted device goes with it — an unresolvable join is worse than no join.
    expect(stored.families.f0).toBeUndefined()
    expect(stored.families.f1).toBe('d1')
  })

  it('takes the evicted device\'s WRAP rows with it too — the third map has no bound of its own', async () => {
    const devices: RegisterPlain['devices'] = {}
    const wraps: RegisterPlain['wraps'] = {}
    for (let i = 0; i < MAX_DEVICES; i++) {
      devices[`d${i}`] = dev(`device ${i}`, 1_000 + i)
      wraps[`w${i}`] = `d${i}`
    }
    const server = fakeServer({ blob: seal(reg(devices, {}, wraps), 1), revision: 1 })

    await rememberThisDevice('newest', 'Newest', null, 'wNew', 99_999)

    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices.d0).toBeUndefined()
    expect(stored.wraps.w0).toBeUndefined() // went with its device
    expect(stored.wraps.w1).toBe('d1') // …and only that one
    expect(stored.wraps.wNew).toBe('newest')
  })

  // The mirror image of the frozen-id-delta case: eviction's truth is the register's own contents, so it must be
  // recomputed on the MERGED map. Frozen from the pre-merge map it would evict by a stale ordering and could
  // leave the register over its own bound.
  it('is recomputed on the merged map after a 409, not frozen from the pre-merge one', async () => {
    const devices: RegisterPlain['devices'] = {}
    for (let i = 0; i < MAX_DEVICES - 1; i++) devices[`d${i}`] = dev(`device ${i}`, 1_000 + i)
    const server = fakeServer({ blob: seal(reg(devices, {}), 1), revision: 1 })

    // A concurrent device pushes the map to exactly MAX_DEVICES; our own addition then makes it one too many,
    // which only a recomputed eviction can see.
    const bumped = { ...devices, concurrent: dev('Concurrent', 50_000) }
    interposeWrites(server, [reg(bumped, {})])

    await rememberThisDevice('newest', 'Newest', null, null, 99_999)

    const stored = open(server.state.blob!, server.state.revision)
    expect(Object.keys(stored.devices)).toHaveLength(MAX_DEVICES)
    expect(stored.devices.newest).toBeDefined()
    expect(stored.devices.concurrent).toBeDefined()
    expect(stored.devices.d0).toBeUndefined()
  })
})

describe('renaming', () => {
  it('renames the device the family belongs to, reaching every session of it', async () => {
    const server = fakeServer({
      blob: seal(reg({ d1: dev('Firefox · macOS') }, { famA: 'd1', famB: 'd1' }), 1),
      revision: 1,
    })
    await renameDeviceOfFamily('famA', 'Arbeitslaptop')
    expect(open(server.state.blob!, server.state.revision).devices.d1.label).toBe('Arbeitslaptop')
    expect(await namesOf(['famA', 'famB'])).toEqual({ famA: 'Arbeitslaptop', famB: 'Arbeitslaptop' })
  })

  it('is a no-op for a family the register cannot name — there is no device to rename', async () => {
    const server = fakeServer({ blob: seal(reg({}, {}), 1), revision: 1 })
    await renameDeviceOfFamily('unknown', 'Whatever')
    expect(server.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('the later write wins on a 409 — a rename is deliberate', async () => {
    const server = fakeServer({ blob: seal(reg({ d1: dev('Old') }, { famA: 'd1' }), 1), revision: 1 })
    interposeWrites(server, [reg({ d1: dev('Concurrent rename') }, { famA: 'd1' })])
    await renameDeviceOfFamily('famA', 'Mine wins')
    expect(open(server.state.blob!, server.state.revision).devices.d1.label).toBe('Mine wins')
  })
})

describe('reading the view the sessions panel renders', () => {
  const view = (
    families: RegisterPlain['families'],
    wraps: RegisterPlain['wraps'],
    devices: RegisterPlain['devices'],
  ) => {
    fakeServer({ blob: seal(reg(devices, families, wraps), 1), revision: 1 })
  }

  it('names a family\'s device and hands back that device\'s served wrap', async () => {
    view({ famA: 'd1' }, { w1: 'd1' }, { d1: dev('Work laptop') })
    const v = await readRegisterView(['famA'], ['w1'], 'me')
    expect(v.names).toEqual({ famA: 'Work laptop' })
    expect(v.wrapsOfFamily).toEqual({ famA: ['w1'] })
  })

  it('offers no wrap for a family whose device the register names no wrap for (§E4)', async () => {
    view({ famA: 'd1' }, {}, { d1: dev('Work laptop') })
    const v = await readRegisterView(['famA'], ['w1'], 'me')
    expect(v.names).toEqual({ famA: 'Work laptop' })
    expect(v.wrapsOfFamily).toEqual({})
  })

  it('offers no wrap the server no longer serves — the register never asserts one into existence', async () => {
    view({ famA: 'd1' }, { gone: 'd1' }, { d1: dev('Work laptop') })
    const v = await readRegisterView(['famA'], ['other'], 'me')
    expect(v.wrapsOfFamily).toEqual({})
    expect(v.savedKeyDevices).toEqual([])
  })

  it('offers nothing wrap-shaped when the caller holds no served list', async () => {
    view({ famA: 'd1' }, { w1: 'd1' }, { d1: dev('Work laptop') })
    const v = await readRegisterView(['famA'], null, 'me')
    expect(v.names).toEqual({ famA: 'Work laptop' }) // the naming half is unaffected
    expect(v.wrapsOfFamily).toEqual({})
    expect(v.savedKeyDevices).toEqual([])
  })

  it('lists a signed-out device that still holds a served wrap (§ Devices with a saved key)', async () => {
    view({ famA: 'me', dead: 'lost' }, { w1: 'lost' }, { me: dev('This one'), lost: dev('Lost laptop') })
    const v = await readRegisterView(['famA'], ['w1'], 'me')
    expect(v.savedKeyDevices).toEqual([{ deviceId: 'lost', label: 'Lost laptop', wrapIds: ['w1'] }])
  })

  it('keeps a device with a LIVE family out of that list — it is a session row, not a leftover', async () => {
    view({ famA: 'other' }, { w1: 'other' }, { other: dev('Their phone') })
    const v = await readRegisterView(['famA'], ['w1'], 'me')
    expect(v.savedKeyDevices).toEqual([])
    expect(v.wrapsOfFamily).toEqual({ famA: ['w1'] })
  })

  // Both wrap-bearing results exclude THIS device: every action they feed reaches into another device, and the
  // local equivalent (untrust/forget) also drops the local key. Offering the remote-only half here would leave
  // this browser holding a record whose wrap is gone.
  it('never offers this device\'s own wrap, in either result', async () => {
    view({ famA: 'me' }, { wMine: 'me' }, { me: dev('This one') })
    const v = await readRegisterView(['famA'], ['wMine'], 'me')
    expect(v.wrapsOfFamily).toEqual({})
    expect(v.savedKeyDevices).toEqual([])
  })
})

describe('what never reaches the server', () => {
  it('the PUT body carries only the sealed blob and the revision', async () => {
    const server = fakeServer()
    // A long, distinctive wrap id on purpose: a two-character needle turns up in random base64 by chance, so a
    // short one would make this assertion flaky rather than meaningful.
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 'wrap-id-should-not-be-legible', 5_000)
    const body = server.putDeviceRegister.mock.calls[0][0].deviceRegisterUpdate
    expect(Object.keys(body).sort()).toEqual(['blob', 'revision'])
    // The label and the wrap id are genuinely inside the ciphertext, not merely absent from a field name.
    expect(body.blob).not.toContain('Firefox')
    expect(body.blob).not.toContain('wrap-id-should-not-be-legible')
    // The PUT of a born-empty register (revision 0) is stored at revision 1 — the revision its seal is bound to.
    expect(open(body.blob, body.revision! + 1).devices.me.label).toBe('Firefox · macOS')
    expect(open(body.blob, body.revision! + 1).wraps).toEqual({ 'wrap-id-should-not-be-legible': 'me' })
    expect(() => open(body.blob, body.revision!)).toThrow(new v1.OpenRefused('device-register'))
  })

  // sealRegister and unsealRegister enumerate the maps BY NAME, so a map that reaches `RegisterPlain` and the
  // spread but not those two is dropped silently on write — and every in-memory assertion above stays green,
  // because none of them goes through the seal twice. This one does.
  it('a sealed round-trip carries all three maps', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox · macOS', 'famA', 'w1', 5_000)
    // The second write re-reads what the server stored, through the real unseal, and bases itself on it — while
    // supplying NEITHER famA nor w1 again. So each of those can only be in the final blob by having survived a
    // seal and an unseal; re-supplying them would make this test pass with either half missing.
    await rememberThisDevice('me', 'Firefox · macOS', 'famB', null, 6_000)
    const stored = open(server.state.blob!, server.state.revision)
    expect(stored.devices.me.label).toBe('Firefox · macOS')
    expect(stored.families).toEqual({ famA: 'me', famB: 'me' })
    expect(stored.wraps).toEqual({ w1: 'me' })
  })
})

// ADR 0028 Decision 7 covers the register too: a device never accepts a register older than one it has opened.
describe('the register never goes backwards on this device (ADR 0028 Decision 7)', () => {
  it('a register served below the revision this device has opened is refused', async () => {
    fakeServer({ blob: seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' }), 4), revision: 4 })
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' }) // this device has now held v4
    const rolled = fakeServer({ blob: seal(reg({ d1: dev('Old name') }, { fam1: 'd1' }), 3), revision: 3 })
    await expect(readRegisterView(['fam1'], null, 'nobody')).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    await expect(rememberThisDevice('me', 'Firefox', 'fam2', null)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('a write raises the mark to the revision it stored — a missing register afterwards is refused', async () => {
    const server = fakeServer()
    await rememberThisDevice('me', 'Firefox', 'fam1', null) // stored at v1 by this device
    expect(server.state.revision).toBe(1)
    fakeServer()
    await expect(namesOf(['fam1'])).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('registerDocument — the register as one whole document — opens through the same marks, and writes nothing on a refusal', async () => {
    // The rotation and the reset act on the register whole (their behaviour: rotation/rotateKeys.test.ts,
    // trustReset/trustReset.test.ts, against this real module); this pins that the whole-document view is the same
    // register — the same open, the same seal context.
    const blob = seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' }), 4)
    fakeServer({ blob, revision: 4 })
    await namesOf(['fam1']) // held v4
    const rolled = fakeServer({ blob: seal(reg({}, {}), 3), revision: 3 })
    await expect(registerDocument.open(MK.slice(), await registerDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
    expect(rolled.putDeviceRegister).not.toHaveBeenCalled()

    // Control: at the mark it opens, and what it seals for v5 the ordinary read opens at v5.
    const server = fakeServer({ blob, revision: 4 })
    const opened = await registerDocument.open(MK.slice(), await registerDocument.fetch())
    await registerDocument.write({ ...registerDocument.seal(MK.slice(), opened, 5), revision: 4 })
    expect(server.state.revision).toBe(5)
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' })
  })

  it('control: served again at the mark, the register is accepted', async () => {
    const blob = seal(reg({ d1: dev('Work laptop') }, { fam1: 'd1' }), 4)
    fakeServer({ blob, revision: 4 })
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' })
    fakeServer({ blob, revision: 4 })
    expect(await namesOf(['fam1'])).toEqual({ fam1: 'Work laptop' })
  })
})
