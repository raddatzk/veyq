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

// The generated client is never hit (resolvePublicShare/openSharedMemory/fetchShareTerms are overridden below), but share.ts imports it
// at module load — mock it exactly as share.test.ts does so loading the real module is side-effect-free.
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))

// A resolvable, unlocked READ share reached via a valid fragment link.
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: SHARE_ID }, hash: '#k=AAAA' }) }))

// Keep the REAL terms helpers (acceptedTermsVersion/recordTermsAccepted/pickShareTerms) + error classes; override
// only the two network reads so no resolve crypto runs.
// The shell resolves the platform half and fetches the terms; the Memories body opens the rest. Only those three
// reads are stubbed — the shell, the registry, the body and the helpers it uses are real.
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
vi.mock('../features/memory/share', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memory/share')>()
  return { ...actual, openSharedMemory: vi.fn(), listShareMedia: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms } from '../core/sharing/resolvePublicShare'
import { openSharedMemory, listShareMedia, NO_SHARE_MEDIA, type ResolvedShare, type ShareAccess } from '../features/memory/share'
import { headOf, openedOf } from '../test/shareHead'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'
import type { NodeKind } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'

function mountShareView() {
  return mount(ShareView, {
    global: {
      plugins: [realI18n()],
      stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true },
    },
  })
}

/** Serve one resolved share to the shell (head) and the Memories body (the rest), as production splits it. */
function serve(r: ResolvedShare, rootKind: NodeKind = MEMORY_KIND) {
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(headOf(r, 's1', rootKind))
  vi.mocked(openSharedMemory).mockReset().mockReturnValue(openedOf(r))
  vi.mocked(listShareMedia).mockReset().mockResolvedValue(NO_SHARE_MEDIA) // the items come from openSharedMemory's stub
}

function viewerShare(): ResolvedShare {
  return {
    memoryName: 'My Memory', noteByLocale: {}, role: ShareRole.Read, scopeId: 'scope-1',
    access: { kind: 'read', memoryKey: new Uint8Array() }, items: [],
  }
}

describe('ShareView — accountless terms gate', () => {
  beforeEach(() => {
    localStorage.clear()
    serve(viewerShare())
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

// The gate order password -> terms -> name -> content (the name pre-screen is the Memories body's first screen). This
// is the only place the terms gate and the name gate meet, so it is where swapping them in the shell goes red.
describe('ShareView — terms come before the name pre-screen', () => {
  it('a not-yet-accepted contributor sees the terms first and the name screen only after accepting', async () => {
    localStorage.clear()
    serve({ ...viewerShare(), role: ShareRole.ReadWrite, access: { kind: 'read', memoryKey: new Uint8Array(32) } })
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsVersion: 1, terms: { en: '## Rules' } })
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true)
    expect(wrapper.find('.share-name-card:not(.share-terms-card)').exists()).toBe(false) // name screen withheld

    await wrapper.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false)
    expect(wrapper.find('.share-name-card').exists()).toBe(true) // now the name screen
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
    [ShareRole.ReadWrite, { kind: 'read', memoryKey: new Uint8Array(32) }],
    [ShareRole.WriteOnly, { kind: 'drop', nodeId: 'n1', dropPublicKey: { x: new Uint8Array(), m: new Uint8Array() } }],
  ])('the %s name screen says only the Memory\'s members see the name', async (role, access) => {
    serve({ ...viewerShare(), role, access })
    const wrapper = mountShareView()
    await flushPromises()

    const card = wrapper.find('.share-name-card')
    expect(card.exists()).toBe(true) // the gate itself, so the text checks below cannot pass on an empty page
    expect(card.text()).toContain('Only the people this memory belongs to see your name.')
    expect(card.text()).not.toContain('everyone')
  })
})

// A chapter is a share root of its own (ADR 0080 Decision 13): over the REAL registry, a share the server roots at a
// CHAPTER reaches the same Memories body as a Memory's — for a viewer, and for the WRITE_ONLY drop — never the
// shell's unavailable state, which is what an unclaimed root kind renders (`ShareView.registry.test.ts`).
describe('ShareView — a chapter share', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsVersion: 0, terms: {} })
  })

  it('a viewer link rooted at a CHAPTER renders the Memories body under the chapter\'s title', async () => {
    serve({ ...viewerShare(), memoryName: 'Day 1' }, CHAPTER_KIND)
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-unavailable').exists()).toBe(false)
    expect(wrapper.find('.share-view-header').text()).toContain('Day 1')
  })

  it('an uploader link rooted at a CHAPTER reaches the drop\'s name screen', async () => {
    const access: ShareAccess = { kind: 'drop', nodeId: 'chapter-1', dropPublicKey: { x: new Uint8Array(), m: new Uint8Array() } }
    serve({ ...viewerShare(), memoryName: 'Day 1', role: ShareRole.WriteOnly, access }, CHAPTER_KIND)
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-unavailable').exists()).toBe(false)
    expect(wrapper.find('.share-name-card').exists()).toBe(true)
  })
})
