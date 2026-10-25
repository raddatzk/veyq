// @vitest-environment happy-dom
// Creating a Pot (uc.expenses.create-pot): core's node create with Expenses' kind and the base currency sealed into its
// meta in the same request, then its people as ONE batch in the order typed (their join order, the rounding's
// tie-break) through the real write path. A Pot whose people did not land is said, not hidden.
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

  it('says when the Pot was made but its people were not (control: above, they were)', async () => {
    state.failAppend = true
    const w = await create('Trip', ['Anna', 'Ben'])
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toBeUndefined()
    expect(document.body.textContent).toContain(t('expenses.create.peopleFailed'))
  })
})
