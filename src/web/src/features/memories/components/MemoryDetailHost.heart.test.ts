// @vitest-environment happy-dom
// The Memory's header has core's heart, and ONLY core's (inbox #409): `MemoryDetailHost` hands the real `MemoryDetail` the
// favorite action core builds for the host's `access` and node, so a Memory page and a chapter page carry the same heart as
// every other kind's. A member's click toggles this node in the access's scope; a share guest's page has none. (The heart in a
// level's rows, for every kind, is `core/favorites/heart.test.ts`.)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))

import { mount, flushPromises } from '@vue/test-utils'
import MemoryDetailHost from './MemoryDetailHost.vue'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { blobApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { isFavorite, toggleFavorite } from '../../../core/favorites/favoritesStore'
import { ShareRole } from '../../../api/generated/sharing'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const ID = '22222222-2222-4222-8222-222222222222'

beforeEach(() => {
  vi.clearAllMocks()
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountHost(kind: string, access: NodeDetailProps['access']) {
  const node = treeNode({ id: ID, kind, name: 'Rom 2026', parentId: null })
  const w = mount(MemoryDetailHost, {
    props: {
      node, scopeId: SCOPE, epochs: null, contentKey: new Uint8Array(32).fill(5), children: { folders: [], byKind: new Map() },
      access, tree: memberTreeOf(() => [node], { scopeId: SCOPE }),
    } as unknown as NodeDetailProps,
    global: { plugins: [realI18n()] },
  })
  await flushPromises()
  await flushPromises()
  return w
}
const hearts = (w: Awaited<ReturnType<typeof mountHost>>) => w.findAll('.veyq-node-header-actions button[data-action="favorite"]')

describe('the Memory page\'s header heart is core\'s', () => {
  it.each([MEMORY_KIND, CHAPTER_KIND])('a member\'s %s page has exactly one heart, and a real click toggles it in the access\'s scope', async (kind) => {
    const w = await mountHost(kind, memberAccessOf({ scopeId: SCOPE }))
    expect(hearts(w)).toHaveLength(1)
    expect(hearts(w)[0].attributes('aria-label')).toBe(t('node.favorite'))
    expect(hearts(w)[0].attributes('aria-pressed')).toBe('false')
    await hearts(w)[0].trigger('click')
    expect(toggleFavorite).toHaveBeenCalledWith(ID, SCOPE)
  })

  it('a pinned Memory shows the heart pressed, with the way to remove it', async () => {
    vi.mocked(isFavorite).mockImplementation((id) => id === ID)
    const w = await mountHost(MEMORY_KIND, memberAccessOf({ scopeId: SCOPE }))
    expect(hearts(w)[0].attributes('aria-pressed')).toBe('true')
    expect(hearts(w)[0].attributes('aria-label')).toBe(t('node.unfavorite'))
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s guest\'s page has no heart and never reads the pin store', async (role) => {
    const w = await mountHost(MEMORY_KIND, guestAccessOf(role))
    expect(hearts(w)).toHaveLength(0)
    expect(isFavorite).not.toHaveBeenCalled()
    expect(toggleFavorite).not.toHaveBeenCalled()
  })
})
