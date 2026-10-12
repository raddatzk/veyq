// @vitest-environment happy-dom
// A link rooted at a FOLDER on the share page, with the REAL registry (cr-node-key-hierarchy 5b.4b): the shell renders
// core's page (`ShareTreePage`), not the unavailable state; a Memory below the folder opens in the member's REAL
// `MemoryDetail` (inbox #401 S5). Only the network reads are stubbed (the resolve, the terms, Memories' media read; every
// authenticated client throws, `test/guestMode.ts`); the subtree is real crypto.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PrimeVue from 'primevue/config'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memory/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/files/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
const route = vi.hoisted(() => ({ params: { shareId: 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_3' }, hash: '', query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn(), resolve: () => ({ href: '/s/stub' }) }) }))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { FOLDER_KIND } from '../core/node/kind'
import { sealName, wrapContentKey } from '../core/node/tree'
import { MEMORY_KIND } from '../features/memory/kinds'
import { publicShareMediaApi } from '../features/memory/api'
import { ShareRole, type PublicShare } from '../api/generated/sharing'
import { stubApi } from '../test/apiMock'
import { realI18n } from '../test/i18n'
import { authenticatedCalls } from '../test/guestMode'

const SCOPE_ID = '66666666-6666-4666-8666-666666666666'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000e1'
const K_SHARE = v1.randomBytes(32)
const FOLDER_KEY = v1.randomBytes(32)
const MEMORY_KEY = v1.randomBytes(32)

function folderHead(): PublicShareHead {
  const address = { scopeId: SCOPE_ID, nodeId: MEMORY, epoch: 0, placementVersion: 0 }
  return {
    shareId: route.params.shareId, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role: ShareRole.Read, rootKind: FOLDER_KIND,
    share: {
      role: ShareRole.Read, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, FOLDER_KEY)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        {
          id: MEMORY, parentId: ROOT, kind: MEMORY_KIND, epoch: 0, placementVersion: 0,
          encryptedName: sealName(FOLDER_KEY, address, 'Rom 2026'), wrappedContentKey: wrapContentKey(FOLDER_KEY, address, MEMORY_KEY),
        },
      ],
    } as PublicShare,
  }
}

function mountShareView() {
  return mount(ShareView, { global: { plugins: [realI18n(), PrimeVue], stubs: { MediaGrid: true, OptimisticTile: true }, directives: { tooltip: {} } } })
}

beforeEach(() => {
  authenticatedCalls.length = 0
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  route.hash = `#k=${b64url(K_SHARE)}`
  route.query = {}
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(folderHead())
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
  stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [] } })
})

describe('ShareView: a link rooted at a FOLDER', () => {
  it("renders core's page — the folder's level — not the unavailable state", async () => {
    const w = mountShareView()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.veyq-node-header-title').exists()).toBe(true))
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.veyq-node-header-title').text()).toBe('Familie')
    expect(w.find('.memory-card strong').text(), "a Memory is the member's MemoryCard").toBe('Rom 2026')
    expect(authenticatedCalls).toEqual([])
  })

  it("a Memory below the folder opens in the member's MemoryDetail", async () => {
    route.query = { node: MEMORY }
    const w = mountShareView()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.veyq-node-header-title').exists()).toBe(true))
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.veyq-node-header-title').text()).toBe('Rom 2026')
    expect(w.find('.memory-detail').exists()).toBe(true)
    expect(authenticatedCalls).toEqual([])
  })
})
