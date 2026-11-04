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
import { flushPromises, type VueWrapper } from '@vue/test-utils'

const server = vi.hoisted(() => ({ current: null as unknown as import('../potTestUtil').PotServer, appends: [] as unknown[] }))
vi.mock('../receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn() }))
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
  // the Pot's scope's current members (ADR 0108 decision 2): none unless a case says so
  loadScopePeople: vi.fn(async () => []),
}))
// every member has a picture on this device: a row draws it exactly where it hands PersonAvatar a user id
vi.mock('../../../core/people/avatarCache', async (orig) => ({ ...(await orig<object>()), avatarUrlOf: vi.fn((id: string | null | undefined) => (id ? `blob:picture-${id}` : null)) }))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), appendPotWrites: vi.fn(), eraseParticipant: vi.fn() }))
vi.mock('../../../core/node/nodeMetaWrite', () => ({ writeNodeMeta: vi.fn(async () => ({})) }))
vi.mock('../../../core/report/report', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<object>()), useReportOffered: () => ref(true), openReport: vi.fn() }
})

import * as v1 from '@veyq/crypto/v1'
import PotDetail from './PotDetail.vue'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { treeStore } from '../../../core/node/treeStore'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { appendPotWrites, eraseParticipant, readMemberPot, sealPotWrites, StaleRevisionError } from '../domain/potApi'
import { readMemberReceipts } from '../receipts'
import { writeNodeMeta } from '../../../core/node/nodeMetaWrite'
import { openReport } from '../../../core/report/report'
import { POT_META_KEY, potMetaObject, potMetaOf } from '../domain/potMeta'
import type { RawNodeMeta } from '../../../core/node/nodeMeta'
import type { MetaEditContext } from '../../../core/node/sealedMetaWrite'
import { EXPENSES } from '../../platform/capabilities'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { memberAccessOf, memberTreeOf } from '../../../test/models'
import { headerButtonIds, headerMenuIds } from '../../../test/nodeHeader'
import { ANNA, BEN, CLEO, PotServer, POT_ID, SCOPE, expense, person, potNode } from '../potTestUtil'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { setAccountUserId } from '../../../app/account'
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { hostFor } from '../../../test/nodeHost'
import { mountNodePage } from '../../../test/nodePage'

const DINNER = '0e000000-0000-4000-9000-000000000001'
let node: ReturnType<typeof potNode>

function mountPot(access = memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)) })): VueWrapper {
  return mountNodePage(PotDetail, {
    props: {
      node, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: server.current.potKey,
      children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'], access, tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
    },
    // Mounted as the member's page mounts it: under core's page (its header), with the page's doors.
    global: { plugins: [realI18n()] },
    host: hostFor(access),

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
  vi.mocked(readMemberReceipts).mockImplementation(async () => ({ receipts: [...server.current.receipts], etag: null }))
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
    expect(tabLabels(w)).toEqual([t('expenses.tabs.entries'), t('expenses.tabs.overview'), t('expenses.tabs.settings')])
    expect(headerButtonIds(w)).toEqual(expect.arrayContaining(['share']))
    expect(await headerMenuIds(w)).toEqual(expect.arrayContaining(['move', 'delete']))
    // The Pot's name is renamed in place, no entry of its own (ADR 0110 decision 11).
    expect([...headerButtonIds(w), ...(await headerMenuIds(w))]).not.toContain('rename')
    expect(w.find('h1 button[data-testid="node-header-rename"]').attributes('aria-label')).toBe(t('node.renameNamed', { name: 'Trip' }))
    expect(w.text()).toContain('Dinner')
    expect(w.text()).not.toContain(t('expenses.ledger.missing'))
  })

  it('lists the entries of one date in the order recorded, the last on top, and says what each kind is', async () => {
    // Recorded after the dinner, all on its date, in an order their ids contradict: the id decides only a tie of the
    // server's time, never the order the people entered them.
    const LUNCH = '0e000000-0000-4000-9000-000000000009'
    const REFUND = '0e000000-0000-4000-9000-000000000002'
    const PAYBACK = '0e000000-0000-4000-9000-000000000005'
    server.current.append([{ entryId: LUNCH, content: { ...expense('Lunch', 3000, BEN, [ANNA, BEN]) } }])
    server.current.append([{ entryId: REFUND, content: { kind: 'refund', title: 'Deposit', date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount: 1000, receipts: [], receivers: [{ participant: CLEO, amount: 1000 }], allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, CLEO] } } } }])
    server.current.append([{ entryId: PAYBACK, content: { kind: 'transfer', date: '2026-07-01', currency: 'EUR', minorUnits: 2, rate: '1', amount: 500, receipts: [], from: CLEO, to: ANNA } }])
    const w = mountPot()
    await flushPromises()
    expect(w.findAll('.expenses-entries > .veyq-list [data-entry]').map((r) => r.attributes('data-entry'))).toEqual([PAYBACK, REFUND, LUNCH, DINNER])
    const row = (id: string) => w.find(`[data-entry="${id}"]`).text()
    expect(row(DINNER)).toContain(t('expenses.entries.expenseBy', { names: 'Anna' }))
    expect(row(REFUND)).toContain(t('expenses.entries.refundTo', { names: 'Cleo' }))
    // an untitled transfer is named by its kind, and its line says who paid whom
    expect(row(PAYBACK)).toContain(t('expenses.kind.transfer'))
    expect(row(PAYBACK)).toContain(t('expenses.entries.transferFromTo', { from: 'Cleo', to: 'Anna' }))
  })

  it('shows the balances and the settle-up of the entries, and records one transfer of it', async () => {
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.overview'))
    expect(w.text()).toContain(t('expenses.balances.owed', { amount: '€60.00' }))
    // each row says what makes its balance: Anna paid the dinner and consumed a third of it
    expect(w.find(`.expenses-balances [data-person="${ANNA}"]`).text()).toContain(
      [t('expenses.balances.paid', { amount: '€90.00' }), t('expenses.balances.consumed', { amount: '€30.00' })].join(' · '),
    )
    // the people's figures and the costs are this one tab: the category total beside the balances
    expect(w.find('.expenses-categories').text()).toContain('€90.00')
    expect(w.text()).toContain(t('expenses.balances.pays', { from: 'Ben', to: 'Anna' }))
    await buttonIn(w.element, t('expenses.balances.recordAll'))!.click()
    await flushPromises()
    expect(server.appends).toHaveLength(1)
    expect((server.appends[0] as { content: { kind: string; amount: number } }[]).map((x) => [x.content.kind, x.content.amount])).toEqual([['transfer', 3000], ['transfer', 3000]])
    expect(w.text()).toContain(t('expenses.balances.allSettled'))
    // the transfers now explain the balances: Anna received both, Ben sent his
    expect(w.find(`.expenses-balances [data-person="${ANNA}"]`).text()).toContain(t('expenses.balances.received', { amount: '€60.00' }))
    expect(w.find(`.expenses-balances [data-person="${BEN}"]`).text()).toContain(t('expenses.balances.sent', { amount: '€30.00' }))
  })

  // The overview shows who owes whom and addresses no one (owner 2026-11-04): no "who am I" choice, no row marked as the
  // viewer's — also not a signed-in member's own member participant — and nothing stored on the device.
  it('the overview marks no row as the viewer\'s, though the signed-in member is a participant of the Pot', async () => {
    const self = '0d000000-0000-4000-8000-0000000000f1'
    setAccountUserId(self)
    localStorage.clear()
    const ME = '0e000000-0000-4000-8000-0000000000e1'
    server.current.append([{ entryId: ME, content: { kind: 'participant', name: 'Me Sealed', active: true, joined: 4, member: { userId: self } } }])
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.overview'))
    // control: the member's own row is there, with its figures
    expect(w.find(`.expenses-balances [data-person="${ME}"]`).exists()).toBe(true)
    expect(w.findComponent(VeyqSelect).exists(), 'no "who am I" choice').toBe(false)
    // every row's subtitle is its figures alone, opening with what the person paid — nothing addressed to the viewer
    const paid = t('expenses.balances.paid', { amount: '' }).trim()
    const subtitles = w.findAll('.expenses-balances .veyq-row-subtitle').map((x) => x.text())
    expect(subtitles).toHaveLength(4)
    for (const line of subtitles) expect(line.startsWith(paid), line).toBe(true)
    expect(Object.keys(localStorage).filter((k) => k.startsWith('veyq.expenses.'))).toEqual([])
    setAccountUserId('')
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
    // The lane L1 pass: the poll brought Ben's version in while Anna's editor showed the one
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

  it('reporting an entry from its dialog keeps the dialog open and offers the Pot as what holds it', async () => {
    // Core's report dialog stacks above an open dialog (inbox #545, `core/report/ReportDialog.test.ts`), so the entry
    // dialog no longer closes first.
    const w = mountPot()
    await flushPromises()
    await w.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    await buttonIn(dialog()!, t('report.action'))!.click()
    await flushPromises()
    expect(dialog()).not.toBeNull()
    expect(openReport).toHaveBeenCalledTimes(1)
    const request = vi.mocked(openReport).mock.calls[0]![0]
    expect(request.targets).toEqual([expect.objectContaining({ kind: 'ITEM', id: DINNER })])
    expect(request.enclosing).toMatchObject({ kind: 'NODE', id: POT_ID })
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
    await openTab(w, t('expenses.tabs.settings'))
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

  // A participant that is a member of the space (ADR 0108 decision 2, inbox #783).
  const U_DANA = '0d000000-0000-4000-8000-000000000001'
  const U_ERIK = '0d000000-0000-4000-8000-000000000002'
  const U_GUS = '0d000000-0000-4000-8000-000000000003'
  const DANA = '0e000000-0000-4000-8000-0000000000d1'
  const GUS = '0e000000-0000-4000-8000-0000000000a7'
  const who = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })
  const memberRow = (userId: string, name: string, joined: number) => ({ kind: 'participant' as const, name, active: true, joined, member: { userId } })

  it('adds a member of the space beside a name: the record holds the id and the display name sealed with it, and that member is not offered again', async () => {
    vi.mocked(loadScopePeople).mockResolvedValueOnce([who(U_DANA, 'Dana Member'), who(U_ERIK, 'Erik Member')])
    const w = mountPot()
    await flushPromises()
    expect(loadScopePeople).toHaveBeenCalledWith(SCOPE)
    await openTab(w, t('expenses.tabs.settings'))
    const picker = () => w.find('form.expenses-add-member')
    type SelectProps = { placeholder?: string; options: { value: string }[] }
    const propsOf = (c: VueWrapper) => c.props() as unknown as SelectProps
    const select = () => w.findAllComponents(VeyqSelect).find((c) => propsOf(c).placeholder === t('expenses.people.chooseMember'))!
    const offered = () => propsOf(select()).options.map((o) => o.value)
    expect(offered()).toEqual([U_DANA, U_ERIK])
    select().vm.$emit('update:modelValue', U_DANA)
    await flushPromises()
    await picker().trigger('submit')
    await flushPromises()
    const added = server.current.held().at(-1)!
    expect(added.body).toMatchObject({ kind: 'participant', name: 'Dana Member', active: true, member: { userId: U_DANA } })
    expect(w.find(`[data-person="${added.entryId}"]`).text()).toContain('Dana Member')
    expect(offered()).toEqual([U_ERIK])
    // and a name, typed, as before: a record without a member
    await w.find('form.expenses-inline-form input').setValue('Finn')
    await w.find('form.expenses-inline-form').trigger('submit')
    await flushPromises()
    const finn = server.current.held().at(-1)!
    expect(finn.body).toMatchObject({ kind: 'participant', name: 'Finn' })
    expect(finn.body).not.toHaveProperty('member')
  })

  it('reads a member participant by the current name and picture, one the space no longer lists as a former member by the sealed name, its entries standing', async () => {
    server.current.append([{ entryId: DANA, content: memberRow(U_DANA, 'Dana Old', 4) }, { entryId: GUS, content: memberRow(U_GUS, 'Gus Sealed', 5) }])
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000007', content: expense('Boat', 2000, GUS, [GUS, DANA]) }])
    vi.mocked(loadScopePeople).mockResolvedValueOnce([who(U_DANA, 'Dana Now')])
    const w = mountPot()
    await flushPromises()
    expect(w.find('[data-entry="0e000000-0000-4000-9000-000000000007"]').text()).toContain('Gus Sealed')
    await openTab(w, t('expenses.tabs.settings'))
    const dana = w.find(`[data-person="${DANA}"]`)
    expect(dana.text()).toContain('Dana Now')
    expect(dana.text()).not.toContain('Dana Old')
    expect(dana.find('img').attributes('src')).toBe(`blob:picture-${U_DANA}`)
    expect(dana.find('[data-former]').exists()).toBe(false)
    const gus = w.find(`[data-person="${GUS}"]`)
    expect(gus.text()).toContain('Gus Sealed')
    expect(gus.find('[data-former]').text()).toBe(t('expenses.people.formerMember'))
    expect(gus.find('img').exists()).toBe(false)
    // a member participant is never renamed (its name is the member's own); a name is (control)
    expect(buttonIn(dana.element, t('expenses.people.rename', { name: 'Dana Old' }))).toBeUndefined()
    expect(buttonIn(w.find(`[data-person="${ANNA}"]`).element, t('expenses.people.rename', { name: 'Anna' }))).toBeDefined()
    // the balances name them the same way
    await openTab(w, t('expenses.tabs.overview'))
    expect(w.text()).toContain('Dana Now')
    expect(w.text()).not.toContain('Dana Old')
  })

  it('deactivating a member participant keeps it the member (control for the kind rule: the writer carries the member along)', async () => {
    server.current.append([{ entryId: DANA, content: memberRow(U_DANA, 'Dana Old', 4) }])
    vi.mocked(loadScopePeople).mockResolvedValueOnce([who(U_DANA, 'Dana Now')])
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
    await buttonIn(w.find(`[data-person="${DANA}"]`).element, t('expenses.people.deactivate'))!.click()
    await flushPromises()
    const latest = server.current.held().filter((r) => r.entryId === DANA).at(-1)!
    expect(latest.body).toEqual(expect.objectContaining({ kind: 'participant', name: 'Dana Old', active: false, member: { userId: U_DANA } }))
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
    await openTab(w, t('expenses.tabs.overview'))
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
    await openTab(w, t('expenses.tabs.overview'))
    expect(w.text()).toContain(t('expenses.balances.owed', { amount: '€60.00' }))
  })

  it('removes a person no entry names, offers a named one delete only, and deactivates a settled one', async () => {
    const DAN = '0e000000-0000-4000-8000-000000000004'
    server.current.append([{ entryId: DAN, content: person('Dan', 4) }])
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000003', content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 3000, receipts: [], from: CLEO, to: ANNA } }])
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
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

  it('a removed person appears nowhere, not even as a deleted one; a deleted one is still counted (control)', async () => {
    // The lane L1 pass: after Cleo was deleted and an unnamed person removed, the People
    // tab said "2 deleted people" — the removed one counted, though nothing shows them anywhere else.
    const DAN = '0e000000-0000-4000-8000-000000000004'
    server.current.append([{ entryId: DAN, content: person('Dan', 4) }])
    server.current.erase(DAN)
    server.current.append([{ entryId: '0e000000-0000-4000-9000-000000000003', content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 3000, receipts: [], from: CLEO, to: ANNA } }])
    server.current.erase(CLEO)
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
    expect(w.text()).toContain(t('expenses.people.deletedSummary', { n: 1 }, 1))
    expect(w.text()).not.toContain(t('expenses.people.deletedSummary', { n: 2 }, 2))
  })

  it('a settings change re-runs on the meta it is handed, and the base currency is fixed once an entry exists', async () => {
    const w = mountPot()
    await flushPromises()
    await openTab(w, t('expenses.tabs.settings'))
    expect(w.text()).toContain(t('expenses.settings.currencyFixed'))
    const input = w.find('.expenses-settings form.expenses-add-category input')
    await input.setValue('Ski pass')
    await w.find('.expenses-settings form.expenses-add-category').trigger('submit')
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

  // A Pot carries a cover (owner 2026-11-04): core's header draws and offers it from the kind's `cover`, no Expenses code.
  it('the header\'s visual is the Pot\'s cover: the empty 16:9 frame with the wallet sets it for a writer, no action', async () => {
    const w = mountPot()
    await flushPromises()
    const frame = w.get('.veyq-node-header-leading button[data-testid="node-header-cover"]')
    expect(frame.classes()).toEqual(expect.arrayContaining(['veyq-node-header-cover', 'is-empty']))
    expect(frame.attributes('aria-label')).toBe(t('node.cover.set'))
    expect(frame.findComponent(VeyqIcon).props('name')).toBe('wallet')
    expect(w.find('.veyq-node-header-actions [data-action^="cover"]').exists(), 'the visual is the way in').toBe(false)
    // control: Expenses switched off, the same frame is plain - a Pot then takes no cover write
    const off = mountPot(memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), applicationsOff: new Set([EXPENSES]) }))
    await flushPromises()
    expect(off.find('[data-testid="node-header-cover"]').exists()).toBe(false)
    expect(off.find('.veyq-node-header-leading [data-testid="node-header-cover-frame"]').exists()).toBe(true)
  })

  it('a Pot with no base currency says so and shows no entry', async () => {
    node = potNode(server.current.potKey, null)
    const w = mountPot()
    await flushPromises()
    expect(w.text()).toContain(t('expenses.settings.missing'))
    expect(w.text()).not.toContain('Dinner')
  })
})
