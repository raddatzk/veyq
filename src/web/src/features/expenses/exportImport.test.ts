// A Pot's download and its import (ADR 0082, docs/apps/expenses.md § Download and import), through the real write path
// on both ends: the content holds the settings, the people and each live entry's current revision; it passes its own
// contract check; and imported into a new Pot — every id minted anew — its balances and settle-up equal the original's.
// An archive that breaks the contract is refused before anything is written.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const state = vi.hoisted(() => ({
  source: null as unknown as import('./potTestUtil').PotServer,
  target: null as unknown as import('./potTestUtil').PotServer,
  meta: null as string | null,
}))
vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), readGuestPot: vi.fn(), appendPotWrites: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({
  ...(await orig<object>()),
  createContentNode: vi.fn(async (scopeId: string, _e: unknown, _p: unknown, _k: unknown, _n: unknown, sealer: (key: Uint8Array, node: { scopeId: string; nodeId: string }) => string) => {
    state.meta = sealer(state.target.potKey, { scopeId, nodeId: state.target.nodeId })
    return { nodeId: state.target.nodeId, contentKey: state.target.potKey, epoch: 0 }
  }),
}))

import * as v1 from '@veyq/crypto/v1'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import type { ExportNodeContext, ImportNodeContext } from '../../core/export/hooks'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { activeSpace } from '../../app/activeSpace'
import { ShareRole } from '../../api/generated/sharing'
import { epochsOf } from '../spaces/epochsTestUtil'
import { readFileSync } from 'node:fs'
import { MAX_RECEIPT_NAME, exportPot, importPot, validatePotContent } from './exportImport'
import { RECEIPT_MAX_BYTES } from './receipts'
import { MAX_RECEIPTS } from './domain/format'
import { appendPotWrites, readGuestPot, readMemberPot, sealPotWrites } from './domain/potApi'
import { buildLedger } from './domain/ledger'
import { potMetaOf } from './domain/potMeta'
import { ANNA, BEN, CLEO, EUR, PotServer, SCOPE, expense, person, potNode } from './potTestUtil'

const OWN = '0e000000-0000-4000-a000-000000000001'

beforeEach(() => {
  vi.mocked(readMemberPot).mockImplementation(async () => ({ notModified: false, items: [...state.source.rows], revision: 1, deleteRevision: 0, ids: null, cursor: null, guestWriters: [] }))
  vi.mocked(appendPotWrites).mockImplementation(async (_target, key, held, writes, base) => {
    const sealed = sealPotWrites({ scopeId: state.target.scopeId, nodeId: state.target.nodeId }, key.key, held, writes, base)
    state.target.append(writes, 'user:u-anna', held, base)
    return sealed
  })
  state.source = new PotServer(v1.randomBytes(32))
  state.target = new PotServer(v1.randomBytes(32), SCOPE, '0e000000-0000-4000-b000-000000000099')
  const s = state.source
  s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  s.append([
    { entryId: '0e000000-0000-4000-9000-000000000001', content: { ...expense('Ski pass', 9001, ANNA, [ANNA, BEN, CLEO]), category: OWN } },
    { entryId: '0e000000-0000-4000-9000-000000000002', content: { kind: 'expense', title: 'Bar', date: '2026-07-02', category: 'food', currency: 'CHF', minorUnits: 2, rate: '1.0412', amount: 5000, receipts: [], payers: [{ participant: BEN, amount: 5000 }], allocation: { items: [{ label: 'Wine', amount: 3000, split: { mode: 'equal', participants: [ANNA, CLEO] } }, { label: 'Beer', amount: 1500, split: { mode: 'shares', shares: [{ participant: BEN, shares: 1 }] } }], rest: { mode: 'percent', percents: [{ participant: ANNA, percent: 2500 }, { participant: BEN, percent: 7500 }] } } } },
    { entryId: '0e000000-0000-4000-9000-000000000003', content: expense('Deleted later', 100, CLEO, [CLEO, ANNA]) },
  ])
  s.append([{ entryId: '0e000000-0000-4000-9000-000000000003', content: { kind: 'deleted' } }])
})

function exportCtx(): ExportNodeContext {
  const node = potNode(state.source.potKey, EUR, [{ id: OWN, name: 'Ski' }])
  return {
    purpose: 'download', access: memberAccessOf({ scopeId: SCOPE }), tree: {} as ExportNodeContext['tree'], scopeId: SCOPE, node,
    nodeKey: state.source.potKey, cache: new Map(), addPayload: vi.fn((path: string) => ({ path, size: 1 })) as unknown as ExportNodeContext['addPayload'],
  } as ExportNodeContext
}

describe('the Pot as a download', () => {
  it('holds the settings, the people and every live entry\'s current revision — not the deleted one, not the history', async () => {
    const content = (await exportPot(exportCtx()))!
    expect(content).toMatchObject({ type: 'expenses.pot', schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [{ id: OWN, name: 'Ski' }] })
    expect((content.participants as { name: string }[]).map((p) => p.name)).toEqual(['Anna', 'Ben', 'Cleo'])
    expect((content.entries as { entry: { title: string } }[]).map((e) => e.entry.title).sort()).toEqual(['Bar', 'Ski pass'])
    expect((content.entries as { entry: object }[]).every((e) => !('heads' in e.entry) && !('v' in e.entry))).toBe(true)
    expect(content.table).toEqual({ path: 'Trip.csv', size: 1 })
    expect(validatePotContent(content)).toBeNull()
  })

  it('imports into a new Pot with new ids whose balances and settle-up equal the original\'s', async () => {
    const content = (await exportPot(exportCtx()))!
    const created = await importPot({ purpose: 'import', scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parent: null, name: 'Trip', node: { id: 'x', kind: 'EXPENSES_POT', name: 'Trip', content }, cache: new Map(), payload: vi.fn() } as unknown as ImportNodeContext)
    expect(created.nodeId).toBe(state.target.nodeId)
    const meta = potMetaOf(unsealNodeMeta(state.target.potKey, { scopeId: SCOPE, nodeId: state.target.nodeId }, state.meta))!
    expect(meta.base).toEqual(EUR)
    expect(meta.categories.map((c) => c.name)).toEqual(['Ski'])
    expect(meta.categories[0]!.id).not.toBe(OWN)
    const before = buildLedger(state.source.held(), EUR)
    const after = buildLedger(state.target.held(), EUR)
    const byName = (l: typeof before) => new Map(l.participants.map((p) => [p.name, Number(l.balances.get(p.id))]))
    expect(byName(after)).toEqual(byName(before))
    expect(after.settleUp.map((t) => Number(t.amount))).toEqual(before.settleUp.map((t) => Number(t.amount)))
    expect(after.participants.map((p) => p.id).some((id) => [ANNA, BEN, CLEO].includes(id))).toBe(false)
    // the own category an entry named is the new one
    expect(after.entries.find((e) => e.content?.title === 'Ski pass')!.content).toMatchObject({ category: meta.categories[0]!.id })
  })
})

// A participant that is a member of the space (ADR 0108 decision 2): its user id means a person in its own tenant only.
describe('a member participant in a download and its import', () => {
  const U_DANA = '0d000000-0000-4000-8000-000000000001'
  const DANA = '0e000000-0000-4000-8000-0000000000d1'
  const T_HERE = '7e000000-0000-4000-8000-000000000001'
  const T_ELSEWHERE = '7e000000-0000-4000-8000-000000000002'
  const importCtx = (content: Record<string, unknown>) =>
    ({ purpose: 'import', scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parent: null, name: 'Trip', node: { id: 'x', kind: 'EXPENSES_POT', name: 'Trip', content }, cache: new Map(), payload: vi.fn() }) as unknown as ImportNodeContext
  beforeEach(() => {
    state.source.append([{ entryId: DANA, content: { kind: 'participant', name: 'Dana Sealed', active: true, joined: 4, member: { userId: U_DANA } } }])
    state.source.append([{ entryId: '0e000000-0000-4000-9000-000000000007', content: expense('Boat', 2000, DANA, [DANA, ANNA]) }])
    activeSpace.tenantId = T_HERE
  })
  afterEach(() => {
    activeSpace.tenantId = null
  })
  const imported = () => buildLedger(state.target.held(), EUR).participants.find((p) => p.name === 'Dana Sealed')!

  it('a member\'s download carries the member id with its tenant; an import into that tenant keeps the member', async () => {
    const content = (await exportPot(exportCtx()))!
    expect(content.tenantId).toBe(T_HERE)
    expect((content.participants as { name: string; memberId?: string }[]).map((p) => [p.name, p.memberId])).toEqual([['Anna', undefined], ['Ben', undefined], ['Cleo', undefined], ['Dana Sealed', U_DANA]])
    expect(validatePotContent(content)).toBeNull()
    await importPot(importCtx(content))
    expect(imported()).toMatchObject({ member: U_DANA })
    expect(imported().id).not.toBe(DANA)
  })

  it('an import into another tenant drops the id: the participant is the name sealed with it, its entry standing (control: the same tenant above)', async () => {
    const content = (await exportPot(exportCtx()))!
    activeSpace.tenantId = T_ELSEWHERE
    await importPot(importCtx(content))
    expect(imported()).toMatchObject({ member: null, name: 'Dana Sealed' })
    const boat = buildLedger(state.target.held(), EUR).entries.find((e) => e.content?.title === 'Boat')!
    expect(boat.status).toBe('live')
    expect(state.target.held().filter((r) => r.body?.kind === 'participant').every((r) => !('member' in r.body!))).toBe(true)
  })

  it('a link guest\'s download names no tenant and so carries no member id', async () => {
    vi.mocked(readGuestPot).mockImplementation(async () => ({ revisions: state.source.guestRows(), etag: null, exchangeRateService: null }))
    const content = (await exportPot({ ...exportCtx(), access: guestAccessOf(ShareRole.Read) }))!
    expect(content).not.toHaveProperty('tenantId')
    expect((content.participants as { memberId?: string }[]).some((p) => p.memberId !== undefined)).toBe(false)
  })

  it('refuses a member without its tenant, a member who is two people, a deleted one who is a member, and a member id that is no id', () => {
    const base = { type: 'expenses.pot', schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, entries: [] }
    const ok = { ...base, tenantId: T_HERE, participants: [{ id: ANNA, joined: 1, name: 'Anna', memberId: U_DANA }] }
    expect(validatePotContent(ok)).toBeNull()
    expect(validatePotContent({ ...ok, tenantId: undefined })?.message).toContain('without their tenant')
    expect(validatePotContent({ ...ok, tenantId: 'tenant-1' })?.message).toContain('tenant that is no id')
    expect(validatePotContent({ ...ok, participants: [...ok.participants, { id: BEN, joined: 2, name: 'Dana', memberId: U_DANA }] })?.message).toContain('two people')
    expect(validatePotContent({ ...ok, participants: [{ id: ANNA, joined: 1, deleted: true, memberId: U_DANA }] })?.message).toContain('deleted person who is a member')
    expect(validatePotContent({ ...ok, participants: [{ id: ANNA, joined: 1, name: 'Anna', memberId: 'u-dana' }] })?.message).toContain('member that is no id')
  })
})

describe('validatePotContent', () => {
  const sound = { type: 'expenses.pot', schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, participants: [{ id: ANNA, joined: 1, name: 'Anna' }], entries: [{ id: '0e000000-0000-4000-9000-000000000001', entry: { ...expense('Taxi', 100, ANNA, [ANNA]) } }] }
  it('takes a sound content (control) and refuses each break of its contract', () => {
    expect(validatePotContent(sound)).toBeNull()
    expect(validatePotContent({ ...sound, baseCurrency: 'eur' })?.code).toBe('invalid_manifest')
    expect(validatePotContent({ ...sound, participants: [{ id: ANNA, joined: 1 }] })?.code).toBe('invalid_manifest')
    expect(validatePotContent({ ...sound, participants: [{ id: ANNA, joined: 1, name: 'x'.repeat(101) }] })?.code).toBe('too_large')
    expect(validatePotContent({ ...sound, entries: [{ id: sound.entries[0]!.id, entry: { ...sound.entries[0]!.entry, amount: 0 } }] })?.code).toBe('invalid_manifest')
    expect(validatePotContent({ ...sound, entries: [{ id: sound.entries[0]!.id, entry: expense('Taxi', 100, BEN, [ANNA]) }] })?.message).toContain('does not have')
    expect(validatePotContent({ ...sound, entries: [{ id: sound.entries[0]!.id, entry: { kind: 'participant', name: 'x', active: true, joined: 1 } }] })?.code).toBe('invalid_manifest')
  })
})

describe('validatePotContent — the receipts an entry carries', () => {
  const R1 = '0e000000-0000-4000-a000-000000000001'
  const R2 = '0e000000-0000-4000-a000-000000000002'
  const file = (over: Record<string, unknown> = {}) => ({ path: 'Trip/Taxi.jpg', size: 10, mediaType: 'image/jpeg', ...over })
  const pot = (entries: { listed: string[]; carried?: unknown }[]) => ({
    type: 'expenses.pot', schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, participants: [{ id: ANNA, joined: 1, name: 'Anna' }],
    entries: entries.map((e, i) => ({
      id: `0e000000-0000-4000-9000-00000000000${i + 1}`, entry: { ...expense('Taxi', 100, ANNA, [ANNA]), receipts: e.listed },
      ...(e.carried === undefined ? {} : { receipts: e.carried }),
    })),
  })
  const problem = (entries: { listed: string[]; carried?: unknown }[]) => validatePotContent(pot(entries))

  it('takes receipts that are exactly the ones their entry lists (control)', () => {
    expect(problem([{ listed: [R1, R2], carried: [{ id: R1, name: 'Taxi.jpg', file: file() }, { id: R2, file: file({ path: 'Trip/b.pdf', mediaType: '' }) }] }])).toBeNull()
    expect(problem([{ listed: [] }])).toBeNull()
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file({ size: RECEIPT_MAX_BYTES }) }] }])).toBeNull()
  })

  it('refuses a receipt listed but not carried, carried but not listed, or carried twice', () => {
    expect(problem([{ listed: [R1] }])?.message).toContain('not the ones it lists')
    expect(problem([{ listed: [], carried: [{ id: R1, file: file() }] }])?.message).toContain('not the ones it lists')
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file() }] }, { listed: [R1], carried: [{ id: R1, file: file() }] }])?.message).toContain('carried twice')
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file() }, { id: R1, file: file() }] }])?.message).toContain('carried twice')
  })

  it('refuses a receipt above 10 MiB, one that claims no image or PDF, and one without its file', () => {
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file({ size: RECEIPT_MAX_BYTES + 1 }) }] }])?.code).toBe('too_large')
    expect(problem([{ listed: [R1], carried: [{ id: R1, name: 'note.txt', file: file({ mediaType: 'text/plain' }) }] }])?.message).toContain('no image or PDF')
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file({ path: 'Trip/note', mediaType: '' }) }] }])?.message).toContain('no image or PDF')
    expect(problem([{ listed: [R1], carried: [{ id: R1 }] }])?.message).toContain('without its file')
    expect(problem([{ listed: [R1], carried: [{ id: R1, file: file({ size: -1 }) }] }])?.message).toContain('without its file')
    expect(problem([{ listed: [R1], carried: [{ id: 'r1', file: file() }] }])?.message).toContain('without an id')
    expect(problem([{ listed: [R1], carried: [{ id: R1, name: `${'x'.repeat(MAX_RECEIPT_NAME)}.jpg`, file: file() }] }])?.code).toBe('too_large')
    expect(problem([{ listed: [R1], carried: { id: R1 } }])?.message).toContain('no list')
  })

  it("each limit is the schema's", () => {
    const schema = JSON.parse(readFileSync(new URL('../../../../../contracts/export/veyq-subtree.schema.json', import.meta.url), 'utf8'))
    const d = schema.$defs
    expect({
      name: d.expensesReceipt.properties.name.maxLength,
      size: d.expensesReceipt.properties.file.properties.size.maximum,
      perEntry: d.expensesEntry.properties.receipts.maxItems,
    }).toEqual({ name: MAX_RECEIPT_NAME, size: RECEIPT_MAX_BYTES, perEntry: MAX_RECEIPTS })
    expect(d.expensesReceipt.required).toEqual(['id', 'file'])
  })
})
