// @vitest-environment happy-dom
// The accountless terms gate on the share page (uc.sharing.share-accept, ADR 0035 §3). Load-bearing behaviours:
// a not-yet-accepted visitor sees the gate (not the content); accepting clears it and reveals the content and
// persists per tenant (a reload does not re-prompt); an operator version bump re-gates; a transient terms-fetch
// error FAILS OPEN (content, never a lock-out). We drive the REAL terms helpers (localStorage per tenant, the
// locale pick) + the REAL ShareView wiring (needsTerms/loadTerms/acceptTerms) — only the network resolve/terms
// reads and the heavy media composables are stubbed, so what's exercised is the gate itself, not resolve crypto.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const SHARE_ID = 's1'
const TENANT = 't-1'
const ACCEPT_KEY = `veyq:terms-accepted:${TENANT}`

// The two heavy media composables → inert stubs (no crypto, no object URLs, no timers).
vi.mock('../features/memory/useOptimisticTiles', () => ({
  useOptimisticTiles: () => ({ optimisticTiles: { value: [] }, donorPreviewFor: () => undefined }),
}))
vi.mock('../features/memory/useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({
    thumbs: new Map(),
    reconcile: vi.fn(),
    decrypt: vi.fn().mockResolvedValue(undefined),
    revokeAll: vi.fn(),
  }),
}))

// The generated client is never hit (resolveShare/fetchShareTerms are overridden below), but share.ts imports it
// at module load — mock it exactly as share.test.ts does so loading the real module is side-effect-free.
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))

// A resolvable, unlocked VIEWER share reached via a valid fragment link.
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: SHARE_ID }, hash: '#k=AAAA' }) }))

// Keep the REAL terms helpers (acceptedTermsVersion/recordTermsAccepted/pickShareTerms) + error classes; override
// only the two network reads so no resolve crypto runs.
vi.mock('../features/memory/share', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memory/share')>()
  return {
    ...actual,
    resolveShare: vi.fn(),
    fetchShareTerms: vi.fn(),
  }
})

import ShareView from './ShareView.vue'
import { resolveShare, fetchShareTerms, type ResolvedShare, type ShareAccess } from '../features/memory/share'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'

function mountShareView() {
  return mount(ShareView, {
    global: {
      plugins: [realI18n()],
      stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true },
    },
  })
}

function viewerShare(): ResolvedShare {
  return {
    memoryName: 'My Memory', noteByLocale: {}, role: ShareRole.Viewer, scopeId: 'scope-1',
    access: { kind: 'read', memoryKey: new Uint8Array() }, items: [],
  }
}

describe('ShareView — accountless terms gate', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(resolveShare).mockReset().mockResolvedValue(viewerShare())
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({
      tenantId: TENANT,
      termsVersion: 1,
      terms: { de: '## Regeln', en: '## Rules' },
    })
  })

  it('shows the terms gate (not the content) for a not-yet-accepted visitor', async () => {
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true) // the gate
    expect(wrapper.find('.share-view-header').exists()).toBe(false) // content is withheld
    // the terms markdown is rendered (safe markdown) inside the gate
    expect(wrapper.find('.share-terms-body').html()).toContain('Rules')
  })

  it('accepting clears the gate, reveals the content, and persists per tenant', async () => {
    const wrapper = mountShareView()
    await flushPromises()

    await wrapper.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false) // gate cleared
    expect(wrapper.find('.share-view-header').exists()).toBe(true) // content shown
    expect(localStorage.getItem(ACCEPT_KEY)).toBe('1') // acceptance is client-only, keyed per tenant
  })

  it('does not re-prompt after a reload once accepted for the version', async () => {
    localStorage.setItem(ACCEPT_KEY, '1') // accepted on a previous visit
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false)
    expect(wrapper.find('.share-view-header').exists()).toBe(true)
  })

  it('re-gates when the operator bumps the terms version', async () => {
    localStorage.setItem(ACCEPT_KEY, '1') // previously accepted v1
    vi.mocked(fetchShareTerms).mockResolvedValue({ tenantId: TENANT, termsVersion: 2, terms: { en: '## Rules v2' } })
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true) // stored 1 < current 2 → gate re-fires
    expect(wrapper.find('.share-view-header').exists()).toBe(false)
  })

  it('fails open (shows content, no gate) on a transient terms-fetch error', async () => {
    vi.mocked(fetchShareTerms).mockRejectedValue(new Error('network'))
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false) // a network blip never locks the visitor out
    expect(wrapper.find('.share-view-header').exists()).toBe(true)
  })
})

// The name pre-screen tells a guest who reads the name it types (ADR 0030 point 8): the Memory's members, and no
// other link holder — which is what the resolve serves, so the page must not promise "everyone" either.
describe('ShareView — who sees a guest name', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsVersion: 0, terms: {} })
  })

  it.each<[ShareRole, ShareAccess]>([
    [ShareRole.Contributor, { kind: 'read', memoryKey: new Uint8Array(32) }],
    [ShareRole.Uploader, { kind: 'drop', nodeId: 'n1', dropPublicKey: { x: new Uint8Array(), m: new Uint8Array() } }],
  ])('the %s name screen says only the Memory\'s members see the name', async (role, access) => {
    vi.mocked(resolveShare).mockReset().mockResolvedValue({ ...viewerShare(), role, access })
    const wrapper = mountShareView()
    await flushPromises()

    const card = wrapper.find('.share-name-card')
    expect(card.exists()).toBe(true) // the gate itself, so the text checks below cannot pass on an empty page
    expect(card.text()).toContain('Only the people this memory belongs to see your name.')
    expect(card.text()).not.toContain('everyone')
  })
})
