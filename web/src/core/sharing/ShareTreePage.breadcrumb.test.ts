// @vitest-environment happy-dom
// The folder share page's breadcrumb, clicked through the DOM (cr-node-key-hierarchy 5b.4b; found in the browser,
// 2026-10-07). `ShareTreePage.test.ts` stubs the Breadcrumb and the router, so it pins only the `push` arguments — it
// cannot see that PrimeVue renders every crumb as an `<a href>` whose DEFAULT action still runs after the `command`:
// with `href="#"` the browser cleared the `#k=` fragment (the share key) and the pending push was cancelled. Here the
// real PrimeVue Breadcrumb and the real vue-router render and route; the registry is a Proxy onto a fresh one, as there.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { guestAccessFor } from '../../test/models'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
vi.mock('../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))

import ShareTreePage from './ShareTreePage.vue'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { realI18n } from '../../test/i18n'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const ROOT = '6e5a0000-0000-4000-8000-0000000000d0'
const LETTERS = '6e5a0000-0000-4000-8000-0000000000d1'
const NESTED = '6e5a0000-0000-4000-8000-0000000000d2'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), letters: v1.randomBytes(32), nested: v1.randomBytes(32) }
const HASH = `#k=${b64url(K_SHARE)}`

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementRevision: 0 }
  return { id, parentId, kind: FOLDER_KIND, epoch: 0, placementRevision: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
const head: PublicShareHead = {
  shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role: ShareRole.Read, rootKind: FOLDER_KIND,
  share: {
    role: ShareRole.Read, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
    nodes: [
      { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
      child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe'),
      child(NESTED, LETTERS, keys.letters, keys.nested, 'Feldpost'),
    ],
  } as PublicShare,
}

let router: Router
beforeEach(async () => {
  holder.reg = createRegistry()
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}?node=${NESTED}${HASH}`)
  await router.isReady()
})

async function mountAtNested() {
  const w = mount(ShareTreePage, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head, reload: async () => {}, access: guestAccessFor(head) },
    global: { plugins: [router, realI18n(), PrimeVue] },
  })
  await flushPromises()
  return w
}
/** Click the rendered crumb link as a user does; the event says whether its default action (following `href`) ran. */
async function clickCrumb(w: Awaited<ReturnType<typeof mountAtNested>>, label: string) {
  const link = w.findAll('a.p-breadcrumb-item-link').find((a) => a.text() === label)
  expect(link, `a crumb link "${label}"`).toBeTruthy()
  const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
  link!.element.dispatchEvent(click)
  await flushPromises()
  return click
}

describe('ShareTreePage: a breadcrumb click keeps the share key', () => {
  it('renders each crumb as a real link to its node, the #k= fragment included', async () => {
    const w = await mountAtNested()
    const links = w.findAll('a.p-breadcrumb-item-link')
    expect(links.map((a) => a.text())).toEqual(['Familie', 'Briefe', 'Feldpost'])
    expect(links[0].attributes('href')).toBe(`/s/${SHARE_ID}${HASH}`)
    expect(links[1].attributes('href')).toBe(`/s/${SHARE_ID}?node=${LETTERS}${HASH}`)
  })

  it('the ROOT crumb navigates to the root: no ?node=, the fragment kept, the anchor default prevented', async () => {
    const w = await mountAtNested()
    const click = await clickCrumb(w, 'Familie')
    expect(click.defaultPrevented).toBe(true)
    expect(router.currentRoute.value.hash).toBe(HASH)
    expect(router.currentRoute.value.query.node).toBeUndefined()
    expect(w.find('.veyq-node-header-title').text()).toBe('Familie')
  })

  it('a MIDDLE crumb navigates to that folder, the fragment kept, the anchor default prevented', async () => {
    const w = await mountAtNested()
    const click = await clickCrumb(w, 'Briefe')
    expect(click.defaultPrevented).toBe(true)
    expect(router.currentRoute.value.hash).toBe(HASH)
    expect(router.currentRoute.value.query.node).toBe(LETTERS)
    expect(w.find('.veyq-node-header-title').text()).toBe('Briefe')
  })

  it('a modified click is left to the browser (open the real href elsewhere): not prevented, no navigation here', async () => {
    const w = await mountAtNested()
    const link = w.findAll('a.p-breadcrumb-item-link').find((a) => a.text() === 'Briefe')!
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, metaKey: true })
    link.element.dispatchEvent(click)
    await flushPromises()
    expect(click.defaultPrevented).toBe(false)
    expect(router.currentRoute.value.query.node).toBe(NESTED)
  })
})
