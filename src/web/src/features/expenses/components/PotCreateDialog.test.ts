// @vitest-environment happy-dom
// Creating a Pot (uc.expenses.create-pot): core's node create with Expenses' kind and the base currency sealed into its
// meta in the same request, then its people as ONE batch in the order added (their join order, the rounding's
// tie-break) through the real write path — names typed and members of the space chosen in the one people field, each
// member's record as People writes it (ADR 0108 decision 2). A Pot whose people did not land is said, not hidden.
// The field is driven by the keys its layer component listens to (typing, Enter, Backspace on the text field).
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

const U_DANA = '0d000000-0000-4000-8000-000000000001'
const U_ERIK = '0d000000-0000-4000-8000-000000000002'
const who = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })

const nameField = () => document.body.querySelector<HTMLInputElement>('input.veyq-input')!
const peopleField = () => document.body.querySelector<HTMLInputElement>('input.veyq-tags-input__input')!
const chips = () => [...document.body.querySelectorAll('.veyq-tags-input__tag')].map((c) => c.querySelector('.veyq-tags-input__label')!.textContent!.trim())
// a member's suggestion leads with their avatar (its letter), so the text is read without it
const offered = () => [...document.body.querySelectorAll('.veyq-tags-input__option')].map((o) => o.querySelector(':scope > span')!.textContent!.trim())
const button = (label: string) => [...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label)

function setName(v: string) {
  nameField().value = v
  nameField().dispatchEvent(new Event('input'))
}
async function type(text: string) {
  const el = peopleField()
  el.focus()
  el.value = text
  el.setSelectionRange(text.length, text.length)
  el.dispatchEvent(new InputEvent('input', { data: text.slice(-1) || null, bubbles: true }))
  await flushPromises()
}
async function press(key: string) {
  peopleField().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}
/** Types [text] and picks the highlighted suggestion with Enter — the first that matches. */
async function pick(text: string) {
  await type(text)
  await press('Enter')
}
async function submit() {
  document.body.querySelector('form')!.dispatchEvent(new Event('submit'))
  await flushPromises()
}

async function open() {
  const w = mount(PotCreateDialog, {
    props: { kind: POT_KIND, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parentId: null, parentKey: null, parentEpoch: null },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}

async function create(name: string, people: string[]) {
  const w = await open()
  setName(name)
  for (const p of people) await pick(p)
  await submit()
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

  it('takes a name out again — by its × or by Backspace — and creates the Pot without it', async () => {
    await open()
    setName('Trip')
    for (const p of ['Anna', 'Ben', 'Cleo', 'Dan']) await pick(p)
    expect(chips()).toEqual(['Anna', 'Ben', 'Cleo', 'Dan'])
    button(t('expenses.people.remove', { name: 'Ben' }))!.click()
    await flushPromises()
    await press('Backspace')
    expect(chips()).toEqual(['Anna', 'Cleo'])
    await submit()
    expect(buildLedger(state.server.held(), { currency: 'USD', minorUnits: 2 }).participants.map((p) => p.name)).toEqual(['Anna', 'Cleo'])
  })

  it('adds a name once, case aside, and never a blank one (control: a new name is offered)', async () => {
    await open()
    setName('Trip')
    await pick('Anna')
    await type('aNNa')
    expect(offered()).toEqual([])
    await type('   ')
    expect(offered()).toEqual([])
    await type(' Finn ')
    expect(offered()).toEqual([t('expenses.people.addAsName', { name: 'Finn' })])
    await press('Enter')
    expect(chips()).toEqual(['Anna', 'Finn'])
  })

  // Owner 2026-11-01: text still in the field when Create is pressed is not lost - a member whose whole name it is, else
  // a name; never twice, never blank.
  it('takes the text still in the field on Create: a name, or the member whose whole name it is', async () => {
    vi.mocked(loadScopePeople).mockResolvedValueOnce([who(U_DANA, 'Dana Member'), who(U_ERIK, 'Erik Member')])
    await open()
    setName('Trip')
    await pick('Anna')
    await type(' Finn ')
    await submit()
    expect(buildLedger(state.server.held(), { currency: 'USD', minorUnits: 2 }).participants.map((p) => p.name)).toEqual(['Anna', 'Finn'])
  })

  it('a member\'s whole name left in the field is the member, a part of it a name, a name already in nothing new', async () => {
    vi.mocked(loadScopePeople).mockResolvedValueOnce([who(U_DANA, 'Dana Member'), who(U_ERIK, 'Erik Member')])
    await open()
    setName('Trip')
    await pick('Anna')
    await type('dána member')
    await submit()
    const ledger = buildLedger(state.server.held(), { currency: 'USD', minorUnits: 2 })
    expect(ledger.participants.map((p) => p.name)).toEqual(['Anna', 'Dana Member'])
    expect(ledger.participants[1]!.member).toBe(U_DANA)
  })

  // ADR 0108 decision 2, inbox #783: the dialog offers the space's members beside typed names, with People's picker rules.
  it('adds a member of the space and a name: the member\'s record holds the id and the display name sealed, as People writes it', async () => {
    const dana = who(U_DANA, 'Dana Member')
    vi.mocked(loadScopePeople).mockResolvedValueOnce([dana, who(U_ERIK, 'Erik Member')])
    const w = await open()
    expect(loadScopePeople).toHaveBeenCalledWith(SCOPE)
    expect(peopleField().placeholder).toBe(t('expenses.people.memberOrName'))
    setName('Trip')
    // typing suggests the members whose name holds the text, the text as a name last
    await type('member')
    expect(offered()).toEqual(['Dana Member', 'Erik Member', t('expenses.people.addAsName', { name: 'member' })])
    // Erik chosen and taken out again goes back on offer; Dana chosen is not offered twice
    await pick('erik')
    expect(chips()).toEqual(['Erik Member'])
    expect(document.body.querySelector('.veyq-tags-input__tag .veyq-avatar')).not.toBeNull()
    await type('member')
    expect(offered()).toEqual(['Dana Member', t('expenses.people.addAsName', { name: 'member' })])
    button(t('expenses.people.remove', { name: 'Erik Member' }))!.click()
    await flushPromises()
    await type('member')
    expect(offered()).toEqual(['Dana Member', 'Erik Member', t('expenses.people.addAsName', { name: 'member' })])
    await pick('Finn')
    await pick('dána')
    await type('member')
    expect(offered()).toEqual(['Erik Member', t('expenses.people.addAsName', { name: 'member' })])
    await type('')
    expect(chips()).toEqual(['Finn', 'Dana Member'])
    await submit()
    expect(vi.mocked(appendPotWrites)).toHaveBeenCalledTimes(1)
    const held = state.server.held()
    // the join order is the order added
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
    await open()
    expect(peopleField().placeholder).toBe(t('expenses.people.nameLabel'))
    setName('Trip')
    await type('a')
    expect(offered()).toEqual([t('expenses.people.addAsName', { name: 'a' })])
    await pick('Anna')
    await submit()
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
