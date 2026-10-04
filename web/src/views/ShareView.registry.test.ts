// @vitest-environment happy-dom
// The share page as a SHELL over the application registry (cr-platform-web-boundaries step 8), proven against a
// STUBBED registry (a fresh `createRegistry()` per test behind a delegating Proxy) so each test chooses exactly which
// share bodies "an application" claimed, with a stub body that renders what the shell handed it. The real registry +
// the real Memories body are covered in ShareView.test.ts / .order.test.ts / .picker.test.ts. Every negative has its
// live control: the same share with the body claimed renders.
import { describe, it, expect, vi, beforeEach } from 'vitest'
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
import { createRegistry, DROP_SHARE, type Registry } from '../core/applications/registry'
import { NodeKind } from '../api/generated/content'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'

function head(over: Partial<PublicShareHead> = {}): PublicShareHead {
  return {
    shareId: 's1', share: {} as PublicShareHead['share'], kShare: new Uint8Array(32), scopeId: 'scope-1',
    name: 'Rom 2026', noteByLocale: {}, role: ShareRole.Viewer, rootKind: NodeKind.Memory, ...over,
  }
}

/** A body that renders what the shell handed it and can raise the one event a body has. */
const StubBody = defineComponent({
  name: 'StubBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload'],
  emits: ['unavailable'],
  setup: (p, { emit }) => () =>
    h('div', { class: 'body-stub', onClick: () => emit('unavailable') },
      `${p.head.name}|${p.shareId}|${p.fragmentKey}|${p.pwToken ?? '-'}`),
})

function registryWith(claims: Array<NodeKind | typeof DROP_SHARE>): Registry {
  const r = createRegistry()
  for (const key of claims) r.claimShareBody(key, StubBody)
  return r
}

function mountShare() {
  return mount(ShareView, { global: { plugins: [realI18n()] } })
}

beforeEach(() => {
  localStorage.clear()
  holder.reg = registryWith([NodeKind.Memory, DROP_SHARE])
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head())
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
  vi.mocked(derivePasswordToken).mockReset().mockResolvedValue('tok')
})

describe('ShareView dispatches on the share root kind', () => {
  it('renders the body claimed for the root kind, handing it the shell\'s context', async () => {
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').text()).toBe('Rom 2026|s1|AAAA|-')
  })

  it('a root kind nobody claimed is the explicit unavailable state, not a blank page (live control above)', async () => {
    holder.reg = registryWith([NodeKind.Memory, DROP_SHARE])
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ rootKind: NodeKind.Chapter }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })

  it('a share with no root node at all is unavailable too, never sent to the kind-less drop body', async () => {
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ rootKind: null })) // a VIEWER share that served no root
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
  })

  it('an UPLOADER share (root kind unknown) goes to the kind-less drop body', async () => {
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ role: ShareRole.Uploader, rootKind: null }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(true)
  })

  it('…and is unavailable when no application claimed the drop (an unrelated Memory claim does not stand in)', async () => {
    holder.reg = registryWith([NodeKind.Memory])
    vi.mocked(resolvePublicShare).mockResolvedValue(head({ role: ShareRole.Uploader, rootKind: null }))
    const w = mountShare()
    await flushPromises()
    expect(w.find('.body-stub').exists()).toBe(false)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
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
