// A Pot's download and its import (ADR 0082, docs/apps/expenses.md § Download and import), through the real write path
// on both ends: the content holds the settings, the people and each live entry's current revision; it passes its own
// contract check; and imported into a new Pot — every id minted anew — its balances and settle-up equal the original's.
// An archive that breaks the contract is refused before anything is written.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const state = vi.hoisted(() => ({
  source: null as unknown as import('./potTestUtil').PotServer,
  target: null as unknown as import('./potTestUtil').PotServer,
  meta: null as string | null,
}))
vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), appendPotWrites: vi.fn() }))
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
import { memberAccessOf } from '../../test/models'
import { epochsOf } from '../spaces/epochsTestUtil'
import { exportPot, importPot, validatePotContent } from './exportImport'
import { appendPotWrites, readMemberPot, sealPotWrites } from './domain/potApi'
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
    { entryId: '0e000000-0000-4000-9000-000000000002', content: { kind: 'expense', title: 'Bar', date: '2026-07-02', category: 'food', currency: 'CHF', minorUnits: 2, rate: '1.0412', amount: 5000, receipts: [], payers: [{ participant: BEN, amount: 5000 }], allocation: { mode: 'items', rest: 'equal', items: [{ label: 'Wine', amount: 3000, participants: [ANNA, CLEO] }, { label: 'Beer', amount: 1500, participants: [BEN] }] } } },
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
