// @vitest-environment happy-dom
// A Pot under its own link is the member's page with functions removed (inbox #401, ADR 0108 decision 6): core's real
// `ShareTreePage` mounts the Pot's `detail` at the link's root through the app's real registry, the guest's tree opens the
// Pot's key from the link's `K_share`, and every authenticated client throws (`test/guestMode.ts`). A `READ_WRITE` guest
// adds an entry and a person through the share path — naming itself first — and sees Settings with the people alone, no
// deactivate or delete of a person and no writer's name; a `READ` guest writes nothing. Its receipts (uc.expenses.attach-receipts E4): a `READ`
// guest opens and downloads them and adds and removes none, a `READ_WRITE` guest adds one through the link's own upload.
// The Pot's revisions are written by the real write path (`potTestUtil.ts`); only the share path's wire is replaced.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

const server = vi.hoisted(() => ({ current: null as unknown as import('../potTestUtil').PotServer, targets: [] as unknown[], uploads: [] as unknown[] }))
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
// every member has a picture on this device: a guest's page must draw none, which only a row that hands PersonAvatar a user id would
vi.mock('../../../core/people/avatarCache', async (orig) => ({ ...(await orig<object>()), avatarUrlOf: vi.fn((id: string | null | undefined) => (id ? `blob:picture-${id}` : null)) }))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), readGuestPot: vi.fn(), appendPotWrites: vi.fn() }))
vi.mock('../receipts', async (orig) => ({ ...(await orig<object>()), readGuestReceipts: vi.fn(), uploadReceipt: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { authenticatedCalls } from '../../../test/guestMode'
import { guestAccessFor } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'
import { appendPotWrites, readGuestPot, sealPotWrites } from '../domain/potApi'
import { readGuestReceipts, uploadReceipt } from '../receipts'
import { POT_KIND } from '../kinds'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person, potNode } from '../potTestUtil'
import { httpError } from '../../../test/httpError'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_8'
const K_SHARE = v1.randomBytes(32)
const DINNER = '0e000000-0000-4000-9000-000000000001'

function head(role: ShareRole): PublicShareHead {
  const pot = potNode(server.current.potKey)
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE, name: 'Trip', noteByLocale: {}, role, rootKind: POT_KIND,
    share: {
      role, scopeId: SCOPE, rootKind: POT_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, server.current.potKey)),
      nodes: [{ id: POT_ID, parentId: null, kind: POT_KIND, epoch: 0, wrappedMeta: pot.wrappedMeta }],
    } as unknown as PublicShare,
  }
}

let router: Router
beforeEach(async () => {
  vi.clearAllMocks()
  // The share path's wire, served by the test's Pot: no writer on a guest's read; each append as the guest it named.
  vi.mocked(readGuestPot).mockImplementation(async () => ({ revisions: server.current.guestRows(), etag: null, exchangeRateService: null }))
  vi.mocked(appendPotWrites).mockImplementation(async (target, key, held, writes, base) => {
    server.targets.push(target)
    const sealed = sealPotWrites(target, key.key, held, writes, base)
    server.current.append(writes, 'share:i-guest', held)
    return sealed
  })
  vi.mocked(readGuestReceipts).mockImplementation(async () => ({ receipts: server.current.receipts.map(({ writer: _w, ...r }) => r), etag: null }))
  vi.mocked(uploadReceipt).mockImplementation(async (target, _key, entryId, receiptId, file) => {
    server.uploads.push(target)
    const { blobKey: _k, writer: _w, ...r } = server.current.receipt(entryId, { name: file.name, type: file.type }, { receiptId })
    return r
  })
  document.body.innerHTML = ''
  authenticatedCalls.length = 0
  server.targets = []
  server.uploads = []
  server.current = new PotServer(v1.randomBytes(32))
  server.current.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  server.current.append([{ entryId: DINNER, content: expense('Dinner', 6000, ANNA, [ANNA, BEN]) }])
  server.current.append([{ entryId: DINNER, content: expense('Dinner for two', 6000, ANNA, [ANNA, BEN]) }], 'user:u-ben')
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}#k=${b64url(K_SHARE)}`)
  await router.isReady()
})

async function mountShare(role: ShareRole): Promise<VueWrapper> {
  const served = head(role)
  const w = mount(ShareTreePage, {
    props: {
      shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: served, reload: async () => {},
      access: guestAccessFor(served, { requestGuest: async () => ({ identityId: 'i-guest', contributorToken: 'dG9rZW4=' }) }),
    },
    global: { plugins: [router, realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  await flushPromises()
  return w
}
const tabLabels = (w: VueWrapper) => w.findAll('.veyq-tab').map((x) => x.text())
async function openTab(w: VueWrapper, label: string) {
  await w.findAll('.veyq-tab').find((x) => x.text() === label)!.trigger('mousedown', { button: 0 })
  await flushPromises()
}
const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement | null
const buttonIn = (root: ParentNode, label: string) => [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label) as HTMLButtonElement | undefined

describe('a Pot under its own link', () => {
  it('READ_WRITE: the member\'s page, its Settings the people alone, managed only by adding and renaming, no writer\'s name', async () => {
    const w = mountShare(ShareRole.ReadWrite)
    const page = await w
    expect(page.find('h1').text()).toBe('Trip')
    expect(page.text()).toContain('Dinner for two')
    expect(tabLabels(page)).toEqual([t('expenses.tabs.entries'), t('expenses.tabs.overview'), t('expenses.tabs.settings')])
    await openTab(page, t('expenses.tabs.settings'))
    // the people, without the Pot's currency or its categories (a member's who may change the meta)
    expect(page.find('.expenses-people').exists()).toBe(true)
    expect(page.text()).not.toContain(t('expenses.baseCurrency'))
    expect(page.text()).not.toContain(t('expenses.settings.categories'))
    expect(page.find('[data-action="delete"]').exists()).toBe(false)
    expect(page.find('[data-action="remove"]').exists()).toBe(false)
    expect(buttonIn(page.element, t('expenses.people.deactivate'))).toBeUndefined()
    expect(buttonIn(page.element, t('expenses.people.rename', { name: 'Ben' }))).toBeDefined()
    expect(buttonIn(page.element, t('expenses.people.add'))).toBeDefined()
    // the history: every version and its time, no one's name
    await openTab(page, t('expenses.tabs.entries'))
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    await buttonIn(dialog()!, t('expenses.history.show'))!.click()
    await flushPromises()
    const history = dialog()!.querySelector('.expenses-history')!
    expect(history.querySelectorAll('li')).toHaveLength(2)
    expect(history.textContent).not.toContain(t('expenses.history.unknownWriter'))
    expect(authenticatedCalls).toEqual([])
  })

  it('READ_WRITE: adds an entry and a person through the share path, as the guest it named', async () => {
    const page = await mountShare(ShareRole.ReadWrite)
    await buttonIn(page.element, t('expenses.entries.add'))!.click()
    await flushPromises()
    const inputs = dialog()!.querySelectorAll('input')
    ;(inputs[0] as HTMLInputElement).value = 'Taxi'
    inputs[0]!.dispatchEvent(new Event('input'))
    const amount = dialog()!.querySelector(`input[aria-label^="${t('expenses.entry.payerAmount', { name: '' }).trim()}"]`) as HTMLInputElement
    amount.value = '12.40'
    amount.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(page.text()).toContain('Taxi')
    await openTab(page, t('expenses.tabs.settings'))
    const name = page.find('form.expenses-inline-form input')
    await name.setValue('Cleo')
    await page.find('form.expenses-inline-form').trigger('submit')
    await flushPromises()
    expect(page.text()).toContain('Cleo')
    expect(vi.mocked(appendPotWrites)).toHaveBeenCalledTimes(2)
    expect(server.targets).toEqual([
      expect.objectContaining({ kind: 'guest', shareId: SHARE_ID, nodeId: POT_ID, contributorToken: 'dG9rZW4=' }),
      expect.objectContaining({ kind: 'guest', shareId: SHARE_ID }),
    ])
    expect(authenticatedCalls).toEqual([])
  })

  it('READ_WRITE: a member participant reads by the name sealed at adding, with no picture; the guest adds a name, never a member', async () => {
    const DANA = '0e000000-0000-4000-8000-0000000000d1'
    const U_DANA = '0d000000-0000-4000-8000-000000000001'
    server.current.append([{ entryId: DANA, content: { kind: 'participant', name: 'Dana Sealed', active: true, joined: 3, member: { userId: U_DANA } } }])
    const page = await mountShare(ShareRole.ReadWrite)
    await openTab(page, t('expenses.tabs.settings'))
    const dana = page.find(`[data-person="${DANA}"]`)
    expect(dana.text()).toContain('Dana Sealed')
    expect(dana.find('.veyq-avatar').exists()).toBe(true)
    expect(dana.find('img').exists()).toBe(false)
    expect(dana.find('[data-former]').exists()).toBe(false)
    expect(page.find('form.expenses-add-member').exists()).toBe(false)
    // a name is still added (the member's view with the member choice removed)
    await page.find('form.expenses-inline-form input').setValue('Cleo')
    await page.find('form.expenses-inline-form').trigger('submit')
    await flushPromises()
    expect(server.current.held().at(-1)!.body).toMatchObject({ kind: 'participant', name: 'Cleo' })
    expect(server.current.held().at(-1)!.body).not.toHaveProperty('member')
    expect(authenticatedCalls).toEqual([])
  })

  it('READ_WRITE: a save refused 451 says the operator blocked it and keeps the dialog (inbox #542; control: a refusal of another kind is the plain failure)', async () => {
    const page = await mountShare(ShareRole.ReadWrite)
    vi.mocked(appendPotWrites).mockRejectedValueOnce(httpError(451, 'blocked_by_operator'))
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()!.textContent).toContain(t('expenses.entry.blocked'))
    vi.mocked(appendPotWrites).mockRejectedValueOnce(httpError(500))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()!.textContent).toContain(t('expenses.entry.saveFailed'))
    expect(dialog()!.textContent).not.toContain(t('expenses.entry.blocked'))
  })

  it('READ: reads everything, writes nothing (control: READ_WRITE above adds)', async () => {
    const page = await mountShare(ShareRole.Read)
    expect(page.text()).toContain('Dinner for two')
    expect(buttonIn(page.element, t('expenses.entries.add'))).toBeUndefined()
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    expect(buttonIn(dialog()!, t('common.save'))).toBeUndefined()
    expect(buttonIn(dialog()!, t('expenses.entry.delete'))).toBeUndefined()
    expect([...dialog()!.querySelectorAll('input')].every((i) => i.disabled)).toBe(true)
    // no Settings tab at all: nothing in it is the guest's to change (control: READ_WRITE above has one, its people)
    expect(tabLabels(page)).toEqual([t('expenses.tabs.entries'), t('expenses.tabs.overview')])
    expect(buttonIn(page.element, t('expenses.people.add'))).toBeUndefined()
    await openTab(page, t('expenses.tabs.overview'))
    expect(buttonIn(page.element, t('expenses.balances.record'))).toBeUndefined()
    expect(vi.mocked(appendPotWrites)).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })

  async function billRow(role: ShareRole) {
    const bill = server.current.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    server.current.append([{ entryId: DINNER, content: { ...expense('Dinner for two', 6000, ANNA, [ANNA, BEN]), receipts: [bill.receiptId] } }], 'user:u-ben')
    const page = await mountShare(role)
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    const row = dialog()!.querySelector(`[data-receipt="${bill.receiptId}"]`)!
    expect(row.textContent).toContain('bill.jpg')
    expect(buttonIn(row, t('expenses.receipts.openNamed', { name: 'bill.jpg' }))).toBeDefined()
    expect(buttonIn(row, t('expenses.receipts.downloadNamed', { name: 'bill.jpg' }))).toBeDefined()
    return row
  }

  it('READ: a receipt opens and downloads, and none is added or removed', async () => {
    const row = await billRow(ShareRole.Read)
    expect(buttonIn(row, t('expenses.receipts.removeNamed', { name: 'bill.jpg' }))).toBeUndefined()
    expect(buttonIn(dialog()!, t('expenses.receipts.add'))).toBeUndefined()
    expect(dialog()!.querySelector('input[type="file"]')).toBeNull()
    expect(authenticatedCalls).toEqual([])
  })

  it('READ_WRITE: the same receipt is also offered its removal and the picker (control for READ)', async () => {
    const row = await billRow(ShareRole.ReadWrite)
    expect(buttonIn(row, t('expenses.receipts.removeNamed', { name: 'bill.jpg' }))).toBeDefined()
    expect(buttonIn(dialog()!, t('expenses.receipts.add'))).toBeDefined()
    expect(dialog()!.querySelector('input[type="file"]')).not.toBeNull()
    expect(authenticatedCalls).toEqual([])
  })

  it('READ_WRITE: a receipt is uploaded through the link as the guest it named, before the revision that lists it', async () => {
    const page = await mountShare(ShareRole.ReadWrite)
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    const input = dialog()!.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [new File([new Uint8Array(10)], 'taxi.pdf', { type: 'application/pdf' })], configurable: true })
    input.dispatchEvent(new Event('change'))
    await flushPromises()
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(server.uploads).toEqual([expect.objectContaining({ kind: 'guest', shareId: SHARE_ID, nodeId: POT_ID, contributorToken: 'dG9rZW4=' })])
    const held = server.current.held().filter((r) => r.entryId === DINNER).at(-1)!
    expect(held.body).toMatchObject({ receipts: [server.current.receipts[0]!.receiptId] })
    expect(authenticatedCalls).toEqual([])
  })
})
