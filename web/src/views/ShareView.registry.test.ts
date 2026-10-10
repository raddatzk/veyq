// @vitest-environment happy-dom
// The share page as a SHELL over the application registry (ADR 0079, inbox #401 S5), proven against a STUBBED registry (a
// fresh `createRegistry()` per test behind a delegating Proxy) so each test chooses exactly which kinds "an application"
// registered and which drop pages it claimed, with stubs that render what the shell handed them. The dispatch: a
// WRITE_ONLY link gets the drop page claimed for its root kind; a READ or READ_WRITE link gets core's page
// (`ShareTreePage`, stubbed here — its own suites prove it) when the app can render the root kind as a member would
// (FOLDER, or a kind with a `detail`), and never a claimed drop page. The real registry + the real pages are covered in
// ShareView.test.ts / .memory.test.ts / .folder.test.ts / .picker.test.ts. Every negative has its live control.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PrimeVue from 'primevue/config'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('../core/applications/registry').Registry }))
vi.mock('../core/applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/applications/registry')>()
  // `src/test/setup.ts` registers the real applications into whatever this resolves to before the first test; each
  // test then swaps `holder.reg` for the registry it wants. A Proxy, so a method the registry gains is delegated too.
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../core/applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../core/sharing/ShareTreePage.vue', async () => {
  const { defineComponent: dc, h: hh } = await import('vue')
  return {
    default: dc({
      name: 'TreePageStub',
      props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'access'],
      emits: ['unavailable'],
      setup: (p) => () => hh('div', { class: 'tree-stub' }, `${p.head.name}|${p.access.role}`),
    }),
  }
})
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: 's1' }, hash: '#k=AAAA' }) }))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn(), derivePasswordToken: vi.fn() }
})

import ShareView from './ShareView.vue'
import {
  resolvePublicShare,
  fetchShareTerms,
  derivePasswordToken,
  SharePasswordRequiredError,
  type PublicShareHead,
} from '../core/sharing/resolvePublicShare'
import { createRegistry, type Registry } from '../core/applications/registry'
import { FOLDER_KIND, type NodeKind } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memory/kinds'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'

function head(over: Partial<PublicShareHead> = {}): PublicShareHead {
  return {
    shareId: 's1', share: {} as PublicShareHead['share'], kShare: new Uint8Array(32), scopeId: 'scope-1',
    name: 'Rom 2026', noteByLocale: {}, role: ShareRole.WriteOnly, rootKind: MEMORY_KIND, ...over,
  }
}

/** A drop page that renders what the shell handed it and can raise the one event a body has. */
const StubBody = defineComponent({
  name: 'StubBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload'],
  emits: ['unavailable'],
  setup: (p, { emit }) => () =>
    h('div', { class: 'body-stub', onClick: () => emit('unavailable') },
      `${p.head.name}|${p.shareId}|${p.fragmentKey}|${p.pwToken ?? '-'}`),
})

/** A kind's member detail (never mounted by these tests: the page that would mount it is stubbed). */
const StubDetail = defineComponent({ name: 'StubDetail', setup: () => () => h('div') })

function registryWith(claims: NodeKind[], details: NodeKind[] = []): Registry {
  const r = createRegistry()
  for (const key of details) r.registerKind({ kind: key, icon: 'x', shareable: true, detail: StubDetail })
  for (const key of claims) r.claimShareBody(key, StubBody)
  return r
}

function mountShare() {
  return mount(ShareView, { global: { plugins: [realI18n(), PrimeVue] } })
}

beforeEach(() => {
  localStorage.clear()
  holder.reg = registryWith([MEMORY_KIND])
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head())
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
  vi.mocked(derivePasswordToken).mockReset().mockResolvedValue('tok')
})

describe("ShareView dispatches a WRITE_ONLY link on its root kind's drop page", () => {
  it('renders the drop page claimed for the root kind, handing it the shell\'s context', async () => {
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').text()).toBe('Rom 2026|s1|AAAA|-')
  })

  it('a root kind nobody claimed is the explicit unavailable state, not a blank page (live control above)', async () => {
    holder.reg = registryWith([MEMORY_KIND])
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ rootKind: 'EXPENSE' }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })

  it('a share whose root kind the server did not name is unavailable too', async () => {
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ rootKind: null })) // a trashed root: no kind, no nodes
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })

  it('…even where the kind has a member detail: a WRITE_ONLY guest holds no key to render it with', async () => {
    holder.reg = registryWith([MEMORY_KIND], [MEMORY_KIND])
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(true)
    expect(w.find('.tree-stub').exists()).toBe(false)
  })

  it('shows the unavailable state when the body reports the share unusable', async () => {
    const w = mountShare()
    await flushPromises()
    await w.find('.body-stub').trigger('click') // the body's one event
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })

  it('an unavailable share (resolve fails) never reaches a body', async () => {
    vi.mocked(resolvePublicShare).mockRejectedValue(new Error('404'))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })
})

// READ and READ_WRITE: core's page — the member's views — never a claimed drop page (inbox #401: no guest-only body).
describe('ShareView gives a READ or READ_WRITE link the member\'s views (core\'s page)', () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: a root kind with a member detail renders core's page — not the drop page claimed for it`, async () => {
      holder.reg = registryWith([MEMORY_KIND], [MEMORY_KIND])
      vi.mocked(resolvePublicShare).mockResolvedValue(head({ role }))
      const w = mountShare()
      await flushPromises()
      expect(w.find('.tree-stub').text()).toBe(`Rom 2026|${role}`)
      expect(w.find('.body-stub').exists()).toBe(false)
    })
  }

  it('a FOLDER root renders core\'s page (the platform kind, no detail)', async () => {
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ role: ShareRole.Read, rootKind: FOLDER_KIND }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.tree-stub').exists()).toBe(true)
  })

  it('a root kind with no member detail is unavailable — a claimed drop page does not stand in (live control above)', async () => {
    holder.reg = registryWith([MEMORY_KIND])
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ role: ShareRole.Read }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.tree-stub').exists()).toBe(false)
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })
})

describe('ShareView runs the gates ahead of the body: password -> terms -> body', () => {
  it('asks for the password first, then the terms, and only then mounts the body — with the unlock token', async () => {
    vi.mocked(resolvePublicShare)
      .mockReset()
      .mockRejectedValueOnce(new SharePasswordRequiredError('salt'))
      .mockResolvedValue(head())
    vi.mocked(fetchShareTerms).mockResolvedValue({ tenantId: 't-1', termsVersion: 1, terms: { en: '## Rules' } })
    const w = mountShare()
    await flushPromises()

    expect(w.find('#share-password-input').exists()).toBe(true) // 1: password — terms not fetched, body not mounted
    expect(w.find('.share-terms-card').exists()).toBe(false)
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(fetchShareTerms).not.toHaveBeenCalled()

    await w.find('#share-password-input').setValue('secret')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(derivePasswordToken).toHaveBeenCalledWith('secret', 'salt')
    expect(w.find('.share-terms-card').exists()).toBe(true) // 2: terms — still no body
    expect(w.find('.body-stub').exists()).toBe(false)

    await w.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(w.find('.share-terms-card').exists()).toBe(false) // 3: the body, holding the guest token
    expect(w.find('.body-stub').text()).toBe('Rom 2026|s1|AAAA|tok')
  })

  it('hands the body a reload that re-resolves the share and swaps its head without remounting it', async () => {
    const w = mountShare()
    await flushPromises()
    const body = w.findComponent(StubBody)
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ name: 'Rom 2026 (+1)' }))

    await (body.props('reload') as () => Promise<void>)()
    await flushPromises()

    expect(resolvePublicShare).toHaveBeenCalledTimes(2)
    expect(w.findComponent(StubBody).exists()).toBe(true)
    expect(w.find('.body-stub').text()).toContain('Rom 2026 (+1)')
  })
})
