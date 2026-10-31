// @vitest-environment happy-dom
// Creating a Pot (uc.expenses.create-pot): core's node create with Expenses' kind and the base currency sealed into its
// meta in the same request, then its people as ONE batch in the order added (their join order, the rounding's
// tie-break) through the real write path — names typed and members of the space chosen, each member's record as People
// writes it (ADR 0108 decision 2). A Pot whose people did not land is said, not hidden.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const state = vi.hoisted(() => ({ server: null as unknown as import('../potTestUtil').PotServer, meta: null as string | null, failAppend: false, kinds: [] as string[] }))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../../../core/node/tree', async (orig) => ({
  ...(await orig<object>()),
  createContentNode: vi.fn(async (scopeId: string, _e: unknown, _p: unknown, kind: string, _n: string, sealer: (k: Uint8Array, n: { scopeId: string; nodeId: string }) => string) => {
    state.kinds.push(kind)
    state.meta = sealer(state.server.potKey, { scopeId, nodeId: state.server.nodeId })
    return { nodeId: state.server.nodeId, contentKey: state.server.potKey, epoch: 0 }
  }),
}))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), appendPotWrites: vi.fn() }))
vi.mock('../../../core/people/directory', async (orig) => ({ ...(await orig<object>()), loadScopePeople: vi.fn(async () => []) }))

import * as v1 from '@veyq/crypto/v1'
import PotCreateDialog from './PotCreateDialog.vue'
import { unsealNodeMeta } from '../../../core/node/nodeMeta'
import { appendPotWrites, sealPotWrites } from '../domain/potApi'
import { buildLedger } from '../domain/ledger'
import { potMetaOf } from '../domain/potMeta'
import { POT_KIND } from '../kinds'
import { PotServer, SCOPE } from '../potTestUtil'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { participantRecord } from '../people'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import type { VueWrapper } from '@vue/test-utils'

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(appendPotWrites).mockImplementation(async (_t, key, held, writes, base) => {
    if (state.failAppend) throw new Error('offline')
    const sealed = sealPotWrites({ scopeId: state.server.scopeId, nodeId: state.server.nodeId }, key.key, held, writes, base)
    state.server.append(writes, 'user:u-anna', held, base)
    return sealed
  })
  document.body.innerHTML = ''
  state.server = new PotServer(v1.randomBytes(32))
  state.meta = null
  state.failAppend = false
  state.kinds = []
})

async function create(name: string, people: string[]) {
  const w = mount(PotCreateDialog, {
    props: { kind: POT_KIND, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parentId: null, parentKey: null, parentEpoch: null },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  const inputs = () => [...document.body.querySelectorAll('input.veyq-input')] as HTMLInputElement[]
  const set = (el: HTMLInputElement, v: string) => {
    el.value = v
    el.dispatchEvent(new Event('input'))
  }
  set(inputs()[0]!, name)
  for (let i = 2; i < people.length; i++) [...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('expenses.people.add'))!.click()
  await flushPromises()
  people.forEach((p, i) => set(inputs()[1 + i]!, p))
  await flushPromises()
  document.body.querySelector('form')!.dispatchEvent(new Event('submit'))
  await flushPromises()
  return w
}

describe('PotCreateDialog', () => {
  it('creates a Pot with its base currency sealed in its meta and its people, in the order typed, in one batch', async () => {
    const w = await create('Trip', ['Anna', 'Ben', 'Cleo'])
    expect(state.kinds).toEqual([POT_KIND])
    expect(potMetaOf(unsealNodeMeta(state.server.potKey, { scopeId: SCOPE, nodeId: state.server.nodeId }, state.meta))?.base).toEqual({ currency: 'USD', minorUnits: 2 })
    expect(vi.mocked(appendPotWrites)).toHaveBeenCalledTimes(1)
    const ledger = buildLedger(state.server.held(), { currency: 'USD', minorUnits: 2 })
    expect(ledger.participants.map((p) => p.name)).toEqual(['Anna', 'Ben', 'Cleo'])
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toHaveLength(1)
  })

  // ADR 0108 decision 2, inbox #783: the dialog offers the space's members beside typed names, with People's picker rules.
  it('adds a member of the space and a name: the member\'s record holds the id and the display name sealed, as People writes it', async () => {
    const U_DANA = '0d000000-0000-4000-8000-000000000001'
    const U_ERIK = '0d000000-0000-4000-8000-000000000002'
    const who = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })
    const dana = who(U_DANA, 'Dana Member')
    vi.mocked(loadScopePeople).mockResolvedValueOnce([dana, who(U_ERIK, 'Erik Member')])
    const w = mount(PotCreateDialog, {
      props: { kind: POT_KIND, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parentId: null, parentKey: null, parentEpoch: null },
      global: { plugins: [realI18n()] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(loadScopePeople).toHaveBeenCalledWith(SCOPE)
    type SelectProps = { placeholder?: string; options: { value: string }[] }
    const select = () => w.findAllComponents(VeyqSelect).find((c) => (c.props() as unknown as SelectProps).placeholder === t('expenses.people.chooseMember')) as VueWrapper | undefined
    const offered = () => (select()?.props() as unknown as SelectProps | undefined)?.options.map((o) => o.value) ?? []
    const button = (label: string) => [...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label)!
    const inputs = () => [...document.body.querySelectorAll('input.veyq-input')] as HTMLInputElement[]
    const set = (el: HTMLInputElement, v: string) => {
      el.value = v
      el.dispatchEvent(new Event('input'))
    }
    expect(offered()).toEqual([U_DANA, U_ERIK])
    set(inputs()[0]!, 'Trip')
    set(inputs()[1]!, 'Finn')
    // Erik chosen and taken out again goes back on offer; Dana chosen is not offered twice
    select()!.vm.$emit('update:modelValue', U_ERIK)
    await flushPromises()
    button(t('expenses.people.addMember')).click()
    await flushPromises()
    expect(offered()).toEqual([U_DANA])
    button(t('expenses.people.remove', { name: 'Erik Member' })).click()
    await flushPromises()
    expect(offered()).toEqual([U_DANA, U_ERIK])
    select()!.vm.$emit('update:modelValue', U_DANA)
    await flushPromises()
    button(t('expenses.people.addMember')).click()
    await flushPromises()
    expect(offered()).toEqual([U_ERIK])
    expect(document.body.querySelector(`[data-member="${U_DANA}"]`)?.textContent).toContain('Dana Member')
    document.body.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()
    expect(vi.mocked(appendPotWrites)).toHaveBeenCalledTimes(1)
    const held = state.server.held()
    // the blank second row is left out; the join order is the order added
    expect(held.map((r) => r.body && 'name' in r.body ? r.body.name : null)).toEqual(['Finn', 'Dana Member'])
    const { v: _v, heads: _h, ...record } = held[1]!.body as unknown as { v: 1; heads: unknown; joined: number }
    expect(record).toEqual(participantRecord({ member: dana }, record.joined))
    expect(record).toEqual({ kind: 'participant', name: 'Dana Member', active: true, joined: record.joined, member: { userId: U_DANA } })
    expect(held[0]!.body).not.toHaveProperty('member')
    const ledger = buildLedger(held, { currency: 'USD', minorUnits: 2 })
    expect(ledger.participants.map((p) => p.member)).toEqual([null, U_DANA])
    expect(w.emitted('done')).toHaveLength(1)
  })

  it('offers no member where the space\'s members were not read: names only (control: above, they were)', async () => {
    vi.mocked(loadScopePeople).mockRejectedValueOnce(new Error('offline'))
    await create('Trip', ['Anna'])
    expect(document.body.textContent).not.toContain(t('expenses.people.addMember'))
    expect(buildLedger(state.server.held(), { currency: 'USD', minorUnits: 2 }).participants.map((p) => [p.name, p.member])).toEqual([['Anna', null]])
  })

  it('says when the Pot was made but its people were not (control: above, they were)', async () => {
    state.failAppend = true
    const w = await create('Trip', ['Anna', 'Ben'])
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toBeUndefined()
    expect(document.body.textContent).toContain(t('expenses.create.peopleFailed'))
  })
})
