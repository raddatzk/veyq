// @vitest-environment happy-dom
// A Pot under its own link is the member's page with functions removed (inbox #401, ADR 0108 decision 6): core's real
// `ShareTreePage` mounts the Pot's `detail` at the link's root through the app's real registry, the guest's tree opens the
// Pot's key from the link's `K_share`, and every authenticated client throws (`test/guestMode.ts`). A `READ_WRITE` guest
// adds an entry and a person through the share path — naming itself first — and sees no Settings, no deactivate or delete
// of a person and no writer's name; a `READ` guest writes nothing. The Pot's revisions are written by the real write path
// (`potTestUtil.ts`); only the share path's wire is replaced.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

const server = vi.hoisted(() => ({ current: null as unknown as import('../potTestUtil').PotServer, targets: [] as unknown[] }))
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), readGuestPot: vi.fn(), appendPotWrites: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { authenticatedCalls } from '../../../test/guestMode'
import { guestAccessFor } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'
import { appendPotWrites, readGuestPot, sealPotWrites } from '../domain/potApi'
import { POT_KIND } from '../kinds'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person, potNode } from '../potTestUtil'

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
  vi.mocked(readGuestPot).mockImplementation(async () => ({ revisions: server.current.guestRows(), etag: null }))
  vi.mocked(appendPotWrites).mockImplementation(async (target, key, held, writes, base) => {
    server.targets.push(target)
    const sealed = sealPotWrites(target, key.key, held, writes, base)
    server.current.append(writes, 'share:i-guest', held)
    return sealed
  })
  document.body.innerHTML = ''
  authenticatedCalls.length = 0
  server.targets = []
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
  it('READ_WRITE: the member\'s page without Settings, people managed only by adding and renaming, no writer\'s name', async () => {
    const w = mountShare(ShareRole.ReadWrite)
    const page = await w
    expect(page.find('h1').text()).toBe('Trip')
    expect(page.text()).toContain('Dinner for two')
    expect(tabLabels(page)).toEqual([t('expenses.tabs.entries'), t('expenses.tabs.balances'), t('expenses.tabs.overview'), t('expenses.tabs.people')])
    await openTab(page, t('expenses.tabs.people'))
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
    ;(inputs[1] as HTMLInputElement).value = '12.40'
    inputs[1]!.dispatchEvent(new Event('input'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(page.text()).toContain('Taxi')
    await openTab(page, t('expenses.tabs.people'))
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

  it('READ: reads everything, writes nothing (control: READ_WRITE above adds)', async () => {
    const page = await mountShare(ShareRole.Read)
    expect(page.text()).toContain('Dinner for two')
    expect(buttonIn(page.element, t('expenses.entries.add'))).toBeUndefined()
    await page.find(`[data-entry="${DINNER}"]`).trigger('click')
    await flushPromises()
    expect(buttonIn(dialog()!, t('common.save'))).toBeUndefined()
    expect(buttonIn(dialog()!, t('expenses.entry.delete'))).toBeUndefined()
    expect([...dialog()!.querySelectorAll('input')].every((i) => i.disabled)).toBe(true)
    await openTab(page, t('expenses.tabs.people'))
    expect(buttonIn(page.element, t('expenses.people.add'))).toBeUndefined()
    expect(buttonIn(page.element, t('expenses.people.rename', { name: 'Ben' }))).toBeUndefined()
    await openTab(page, t('expenses.tabs.balances'))
    expect(buttonIn(page.element, t('expenses.balances.record'))).toBeUndefined()
    expect(vi.mocked(appendPotWrites)).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })
})
