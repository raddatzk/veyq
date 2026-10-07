// @vitest-environment happy-dom
// A link rooted at a FOLDER on the share page, with the REAL registry (cr-node-key-hierarchy 5b.4b): core pre-registers
// its folder body, so the shell renders it instead of the unavailable state it showed before; a Memory below the folder
// opens in the REAL Memories body, embedded. Only the network reads are stubbed (the resolve, the terms, Memories' media
// read); the subtree is real crypto.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../features/memory/useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../features/memory/api', () => ({ publicShareMediaApi: vi.fn() }))
const route = vi.hoisted(() => ({ params: { shareId: 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_3' }, hash: '', query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }))
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
  return mount(ShareView, { global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } } })
}

beforeEach(() => {
  route.hash = `#k=${b64url(K_SHARE)}`
  route.query = {}
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(folderHead())
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
  stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [] } })
})

describe('ShareView: a link rooted at a FOLDER', () => {
  it("renders core's folder body, not the unavailable state", async () => {
    const w = mountShareView()
    await flushPromises()
    // the folder body is registered lazily (it reads the registry: a static import would be a cycle) — it loads first
    await vi.waitFor(() => expect(w.find('.veyq-section-header h1').exists()).toBe(true))
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.veyq-section-header h1').text()).toBe('Familie')
    expect(w.find('.veyq-node-card strong').text()).toBe('Rom 2026')
  })

  it('a Memory below the folder opens in the Memories body, embedded', async () => {
    route.query = { node: MEMORY }
    const w = mountShareView()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.gallery-title').exists()).toBe(true))
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
  })
})
