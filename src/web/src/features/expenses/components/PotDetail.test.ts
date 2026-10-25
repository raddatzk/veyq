// @vitest-environment happy-dom
// An opened Pot for a member (uc.expenses.record-entries, uc.expenses.balances, uc.expenses.manage-participants,
// uc.expenses.entry-history, uc.expenses.verify-ledger): the revisions are written by the REAL write path and opened by
// the real reader (`potTestUtil.ts`' server, whose append takes revision `n + 1` only on `n`, as the backend does); only
// the wire is replaced. Each rule beside its control: the ledger notice shows only for a list missing a revision; a save
// on a stale entry shows the other writer's version and saves on top; a person is deleted only when settled; the
// history names writers; a deleted entry is restored; a person no entry names is removed, a named one only deleted;
// the settings are a change re-run on the meta the writer reads, the base currency fixed once an entry exists;
// Expenses switched off leaves the page read-only.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const server = vi.hoisted(() => ({ current: null as unknown as import('../potTestUtil').PotServer, appends: [] as unknown[] }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../../../core/node/rekey/floor', async (orig) => ({
  ...(await orig<object>()),
  nodeKeyForWrite: vi.fn(() => ({ key: server.current.potKey, epoch: 0 })),
}))
vi.mock('../../../core/people/directory', async (orig) => ({
  ...(await orig<object>()),
  loadTenantPeople: vi.fn(async () => new Map([['u-anna', { userId: 'u-anna', username: 'anna', realName: 'Anna Member', displayName: 'Anna Member' }]])),
}))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), appendPotWrites: vi.fn(), eraseParticipant: vi.fn() }))
vi.mock('../../../core/node/nodeMetaWrite', () => ({ writeNodeMeta: vi.fn(async () => ({})) }))

import * as v1 from '@veyq/crypto/v1'
import PotDetail from './PotDetail.vue'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { treeStore } from '../../../core/node/treeStore'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { appendPotWrites, eraseParticipant, readMemberPot, sealPotWrites, StaleRevisionError } from '../domain/potApi'
import { writeNodeMeta } from '../../../core/node/nodeMetaWrite'
import { POT_META_KEY, potMetaObject, potMetaOf } from '../domain/potMeta'
import type { RawNodeMeta } from '../../../core/node/nodeMeta'
import type { MetaEditContext } from '../../../core/node/sealedMetaWrite'
import { EXPENSES } from '../../platform/capabilities'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { memberAccessOf, memberTreeOf } from '../../../test/models'
import { headerButtonIds } from '../../../test/nodeHeader'
import { ANNA, BEN, CLEO, PotServer, SCOPE, expense, person, potNode } from '../potTestUtil'

const DINNER = '0e000000-0000-4000-9000-000000000001'
let node: ReturnType<typeof potNode>

function mountPot(access = memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)) })): VueWrapper {
  return mount(PotDetail, {
    props: {
      node, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: server.current.potKey,
      children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'], access, tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
}
const tabLabels = (w: VueWrapper) => w.findAll('.veyq-tab').map((x) => x.text())
async function openTab(w: VueWrapper, label: string) {
  await w.findAll('.veyq-tab').find((x) => x.text() === label)!.trigger('mousedown', { button: 0 })
  await flushPromises()
}
const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement | null
const buttonIn = (root: ParentNode, label: string) => [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label) as HTMLButtonElement | undefined

/** The wire, served by the test's Pot: the member's read, and the append with the server's compare-and-set — revision
 *  n + 1 lands only on n, else the whole batch is refused. */
function serve() {
  vi.mocked(readMemberPot).mockImplementation(async () => {
    const rows = server.current.rows
    return { notModified: false, items: [...rows], revision: rows.length, deleteRevision: 0, ids: rows.map((r) => `${r.entryId}:${r.revision}`), cursor: 'c', guestWriters: [] }
  })
  vi.mocked(appendPotWrites).mockImplementation(async (target, key, held, writes, base) => {
    const sealed = sealPotWrites(target, key.key, held, writes, base)
    const latest = (id: string) => Math.max(0, ...server.current.rows.filter((r) => r.entryId === id).map((r) => r.revision))
    const stale = sealed.filter((w) => w.revision !== latest(w.entryId) + 1)
    if (stale.length) throw new StaleRevisionError(stale.map((w) => ({ entryId: w.entryId, revision: latest(w.entryId) })))
    server.appends.push(writes)
    server.current.append(writes, 'user:u-anna', held)
    return sealed
  })
  vi.mocked(eraseParticipant).mockImplementation(async (_target, _key, _held, id) => {
    server.current.erase(id)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  serve()
  document.body.innerHTML = ''
  server.current = new PotServer(v1.randomBytes(32))
  server.appends = []
  server.current.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  server.current.append([{ entryId: DINNER, content: expense('Dinner', 9000, ANNA, [ANNA, BEN, CLEO]) }])
  node = potNode(server.current.potKey)
  treeStore.nodes = [node]
})

describe('PotDetail for a member', () => {
  it('heads the page with the Pot, offers every tab and the Pot\'s own actions', async () => {
    const w = mountPot()
    await flushPromises()
    expect(w.find('h1').text()).toBe('Trip')
    expect(tabLabels(w)).toEqual([t('expenses.tabs.entries'), t('expenses.tabs.balances'), t('expenses.tabs.overview'), t('expenses.tabs.people'), t('expenses.tabs.settings')])
    expect(headerButtonIds(w)).toEqual(expect.arrayContaining(['share', 'rename', 'move']))
    expect(w.text()).toContain('Dinner')
    expect(w.text()).not.toContain(t('expenses.ledger.missing'))
  })

  it('shows the balances and the settle-up of the entries, and records one transfer of it', async () => {
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.balances'))
    expect(w.text()).toContain(t('expenses.balances.owed', { amount: '€60.00' }))
    expect(w.text()).toContain(t('expenses.balances.pays', { from: 'Ben', to: 'Anna' }))
    await buttonIn(w.element, t('expenses.balances.recordAll'))!.click()
    await flushPromises()
    expect(server.appends).toHaveLength(1)
    expect((server.appends[0] as { content: { kind: string; amount: number } }[]).map((x) => [x.content.kind, x.content.amount])).toEqual([['transfer', 3000], ['transfer', 3000]])
    expect(w.text()).toContain(t('expenses.balances.allSettled'))
  })

  it('the ledger notice names an entry whose revision the server left out (control: the full list shows none)', async () => {
    // Ben edits the dinner, then the taxi is written on top of it: the taxi's heads name the edit.
    server.current.append([{ entryId: DINNER, content: expense('Dinner for three', 9000, ANNA, [ANNA, BEN, CLEO]) }], 'user:u-ben')
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000002', content: expense('Taxi', 3000, BEN, [ANNA, BEN]) }])
    const full = mountPot()
    await flushPromises()
    expect(full.text()).not.toContain(t('expenses.ledger.missing'))
    full.unmount()
    server.current.rows = server.current.rows.filter((r) => !(r.entryId === DINNER && r.revision === 2))
    const thinned = mountPot()
    await flushPromises()
    const notice = thinned.find('.expenses-ledger-notice')
    expect(notice.text()).toContain(t('expenses.ledger.missing'))
    expect(notice.findAll('li').map((li) => li.text())).toEqual(['Dinner'])
  })

  it('a save on an entry someone changed meanwhile shows their version; saving again puts this one on top', async () => {
    const w = mountPot()
    await flushPromises()
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    // Ben saves first, from another device.
    server.current.append([{ entryId: DINNER, content: expense('Dinner (Ben)', 9300, ANNA, [ANNA, BEN, CLEO]) }], 'user:u-ben')
    const title = dialog()!.querySelector('input') as HTMLInputElement
    title.value = 'Dinner (Anna)'
    title.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()!.textContent).toContain(t('expenses.entry.stale'))
    expect((dialog()!.querySelector('input') as HTMLInputElement).value).toBe('Dinner (Ben)')
    expect(server.appends).toHaveLength(0)
    const again = dialog()!.querySelector('input') as HTMLInputElement
    again.value = 'Dinner (Anna)'
    again.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(server.current.held().filter((r) => r.entryId === DINNER).map((r) => r.revision)).toEqual([1, 2, 3])
    expect(w.text()).toContain('Dinner (Anna)')
  })

  it('a save on an entry the live poll changed under the open editor is stale too, never written over theirs', async () => {
    // The lane L1 pass (cr-expenses-ledger step 3): the poll brought Ben's version in while Anna's editor showed the one
    // before, so a write sealed on the latest HELD revision would land as n + 2 and drop Ben's change unseen.
    const w = mountPot()
    await flushPromises()
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    server.current.append([{ entryId: DINNER, content: expense('Dinner (Ben)', 9300, ANNA, [ANNA, BEN, CLEO]) }], 'user:u-ben')
    window.dispatchEvent(new Event('focus')) // the live poll's wake: it reads Ben's revision into the page
    await flushPromises()
    expect(w.text()).toContain('Dinner (Ben)')
    const title = dialog()!.querySelector('input') as HTMLInputElement
    expect(title.value).toBe('Dinner')
    title.value = 'Dinner (Anna)'
    title.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()!.textContent).toContain(t('expenses.entry.stale'))
    expect((dialog()!.querySelector('input') as HTMLInputElement).value).toBe('Dinner (Ben)')
    expect(server.appends).toHaveLength(0)
    // Control: saved again on the version it now shows, it goes on top.
    const again = dialog()!.querySelector('input') as HTMLInputElement
    again.value = 'Dinner (Anna)'
    again.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(server.current.held().filter((r) => r.entryId === DINNER).map((r) => r.revision)).toEqual([1, 2, 3])
  })

  it('the history names who wrote each version, for a member', async () => {
    server.current.append([{ entryId: DINNER, content: expense('Dinner for three', 9000, ANNA, [ANNA, BEN, CLEO]) }])
    const w = mountPot()
    await flushPromises()
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    await buttonIn(dialog()!, t('expenses.history.show'))!.click()
    await flushPromises()
    const history = dialog()!.querySelector('.expenses-history')!
    expect(history.querySelectorAll('li')).toHaveLength(2)
    expect(history.textContent).toContain('Anna Member')
  })

  it('deletes a settled person after the confirm, and offers no delete for one who is not settled', async () => {
    // Cleo settles her part of the dinner; Ben does not.
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000003', content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 3000, receipts: [], from: CLEO, to: ANNA } }])
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.people'))
    const row = (id: string) => w.find(`[data-person="${id}"]`)
    expect(row(BEN).find('[data-action="delete"]').exists()).toBe(false)
    expect(row(CLEO).find('[data-action="delete"]').exists()).toBe(true)
    await row(CLEO).find('[data-action="delete"]').trigger('click')
    await flushPromises()
    expect(confirmRequest.value?.message).toBe(t('expenses.people.deleteConfirm'))
    settleConfirm(true)
    await flushPromises()
    expect(eraseParticipant).toHaveBeenCalledWith(expect.objectContaining({ kind: 'member' }), expect.anything(), expect.anything(), CLEO, expect.anything())
    expect(w.text()).toContain(t('expenses.people.deletedSummary', { n: 1 }, 1))
  })

  it('a deleted entry leaves the list for the deleted ones and is restored as its next revision', async () => {
    const w = mountPot()
    await flushPromises()
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    await buttonIn(dialog()!, t('expenses.entry.delete'))!.click()
    await flushPromises()
    expect(server.appends.at(-1)).toEqual([{ entryId: DINNER, content: { kind: 'deleted' }, basedOn: 1 }])
    expect(w.text()).toContain(t('expenses.entries.deletedTitle'))
    await openTab(w, t('expenses.tabs.balances'))
    expect(w.text()).toContain(t('expenses.balances.allSettled'))
    await openTab(w, t('expenses.tabs.entries'))
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    expect(dialog()!.textContent).toContain(t('expenses.entry.deletedHint'))
    expect(buttonIn(dialog()!, t('common.save'))).toBeUndefined()
    await buttonIn(dialog()!, t('expenses.entry.restore'))!.click()
    await flushPromises()
    const dinner = server.current.held().filter((r) => r.entryId === DINNER)
    expect(dinner.map((r) => r.revision)).toEqual([1, 2, 3])
    const { heads, ...restored } = dinner[2]!.body as unknown as { heads: { entryId: string; revision: number }[] }
    expect(restored).toEqual({ v: 1, ...expense('Dinner', 9000, ANNA, [ANNA, BEN, CLEO]) })
    // It names the heads its writer read — the tombstone it lifts — never those the deleted content once named.
    expect(heads.map((h) => `${h.entryId}:${h.revision}`)).toEqual([`${DINNER}:2`])
    expect(w.text()).not.toContain(t('expenses.entries.deletedTitle'))
    await openTab(w, t('expenses.tabs.balances'))
    expect(w.text()).toContain(t('expenses.balances.owed', { amount: '€60.00' }))
  })

  it('removes a person no entry names, offers a named one delete only, and deactivates a settled one', async () => {
    const DAN = '0e000000-0000-4000-8000-000000000004'
    server.current.append([{ entryId: DAN, content: person('Dan', 4) }])
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000003', content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 3000, receipts: [], from: CLEO, to: ANNA } }])
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.people'))
    const row = (id: string) => w.find(`[data-person="${id}"]`)
    // Cleo is named by the dinner: delete, never remove; Dan by nothing: remove, never delete.
    expect([row(CLEO).find('[data-action="delete"]').exists(), row(CLEO).find('[data-action="remove"]').exists()]).toEqual([true, false])
    expect([row(DAN).find('[data-action="delete"]').exists(), row(DAN).find('[data-action="remove"]').exists()]).toEqual([false, true])
    // Ben is not settled: neither deactivate nor delete is offered.
    expect(buttonIn(row(BEN).element, t('expenses.people.deactivate'))).toBeUndefined()
    await buttonIn(row(CLEO).element, t('expenses.people.deactivate'))!.click()
    await flushPromises()
    expect(server.appends.at(-1)).toEqual([{ entryId: CLEO, content: person('Cleo', 3, false) }])
    await row(DAN).find('[data-action="remove"]').trigger('click')
    await flushPromises()
    expect(confirmRequest.value?.message).toBe(t('expenses.people.removeConfirm'))
    settleConfirm(true)
    await flushPromises()
    expect(eraseParticipant).toHaveBeenCalledWith(expect.objectContaining({ kind: 'member' }), expect.anything(), expect.anything(), DAN, expect.anything())
  })

  it('a settings change re-runs on the meta it is handed, and the base currency is fixed once an entry exists', async () => {
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
    expect(w.text()).toContain(t('expenses.settings.currencyFixed'))
    const input = w.find('.expenses-settings form.expenses-inline-form input')
    await input.setValue('Ski pass')
    await w.find('.expenses-settings form.expenses-inline-form').trigger('submit')
    await flushPromises()
    expect(writeNodeMeta).toHaveBeenCalledTimes(1)
    // The edit is applied to whatever meta the write reads (a peer's category written meanwhile included): it keeps
    // the peer's and every other key of the envelope, and adds its own.
    const edit = vi.mocked(writeNodeMeta).mock.calls[0]![4]
    const current: RawNodeMeta = { v: 1, cover: { kept: true }, [POT_META_KEY]: potMetaObject({ base: { currency: 'EUR', minorUnits: 2 }, categories: [{ id: '0e000000-0000-4000-a000-0000000000c1', name: 'Peer' }] }) }
    const written = await edit(structuredClone(current), { retry: true } as unknown as MetaEditContext)
    expect(written!.cover).toEqual({ kept: true })
    expect(potMetaOf(written!)!.categories.map((c) => c.name)).toEqual(['Peer', 'Ski pass'])
  })

  it('control: a Pot with people only offers its base currency', async () => {
    server.current.rows = server.current.rows.filter((r) => r.entryId !== DINNER)
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
    expect(w.text()).not.toContain(t('expenses.settings.currencyFixed'))
    expect(w.text()).toContain(t('expenses.create.currencyHint'))
  })

  it('Expenses switched off: no entry is added and no setting changed; the Pot still opens', async () => {
    const w = mountPot(memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), applicationsOff: new Set([EXPENSES]) }))
    await flushPromises()
    expect(w.text()).toContain('Dinner')
    expect(buttonIn(w.element, t('expenses.entries.add'))).toBeUndefined()
    expect(tabLabels(w)).not.toContain(t('expenses.tabs.settings'))
    expect(headerButtonIds(w)).not.toContain('rename')
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    expect(buttonIn(dialog()!, t('common.save'))).toBeUndefined()
  })

  it('a Pot with no base currency says so and shows no entry', async () => {
    node = potNode(server.current.potKey, null)
    const w = mountPot()
    await flushPromises()
    expect(w.text()).toContain(t('expenses.settings.missing'))
    expect(w.text()).not.toContain('Dinner')
  })
})
