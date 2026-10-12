// @vitest-environment happy-dom
// The Memory page's header carries core's COVER action (inbox #411), and a Memory keeps its own way to choose: the action's
// id and place are core's (`cover`, `cover-remove`), its click opens the Memory's cover DIALOG (a photo of the Memory, an upload,
// a crop) instead of core's plain image picker - the application supplies the source UI through `choose`. A share guest's page has
// no cover action; Remove cover is core's removal (one write), the same as on any other kind.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn() }))
vi.mock('../../../core/node/cover/pickImage', async (orig) => ({ ...(await orig<object>()), pickImageFile: vi.fn() }))
vi.mock('../../../core/node/cover/coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import MemoryDetailHost from './MemoryDetailHost.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import { MEMORY_KIND } from '../kinds'
import { blobApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { pickImageFile } from '../../../core/node/cover/pickImage'
import { writeNodeCover } from '../../../core/node/cover/coverWrite'
import { uploadedCover } from '../../../core/node/cover/coverModel'
import { resetCovers } from '../../../core/node/cover/coverStore'
import { ShareRole } from '../../../api/generated/sharing'
import type { NodeDetailProps } from '../../../core/applications/registry'
import type { EpochKeys } from '../../spaces/epochs'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const ID = '22222222-2222-4222-8222-222222222222'
const KEY = v1.randomBytes(32)
const sealedCover = () =>
  b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId: ID }, KEY, new TextEncoder().encode(JSON.stringify({
    v: 1, cover: uploadedCover({ blobId: 'b-1', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: ID }),
  }))))

beforeEach(() => {
  vi.clearAllMocks()
  resetCovers()
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

async function mountHost(access: NodeDetailProps['access'], wrappedMeta: string | null = null) {
  const node = treeNode({ id: ID, kind: MEMORY_KIND, name: 'Rom 2026', parentId: null, wrappedMeta, metaVersion: wrappedMeta ? 3 : 0 })
  const tree = { ...memberTreeOf(() => [node], { scopeId: SCOPE }), keyOf: () => KEY }
  const w = mount(MemoryDetailHost, {
    props: { node, scopeId: SCOPE, epochs: null, contentKey: KEY, children: { folders: [], byKind: new Map() }, access, tree } as unknown as NodeDetailProps,
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
  await flushPromises()
  await flushPromises()
  return w
}
const action = (w: Awaited<ReturnType<typeof mountHost>>, id: string) => w.find(`.veyq-node-header-actions button[data-action="${id}"]`)
const writer = () => memberAccessOf({ scopeId: SCOPE, epochs: {} as EpochKeys })

describe('the Memory page\'s cover action is core\'s, its way to choose is the Memory\'s', () => {
  it('"Set cover" opens the Memory\'s own cover dialog - not core\'s picker', async () => {
    const w = await mountHost(writer())
    expect(action(w, 'cover').attributes('aria-label')).toBe(t('node.cover.set'))
    expect(w.findComponent(MemoryCoverDialog).exists()).toBe(false)
    await action(w, 'cover').trigger('click')
    await flushPromises()
    expect(w.findComponent(MemoryCoverDialog).exists()).toBe(true)
    expect(pickImageFile).not.toHaveBeenCalled()
  })

  it('a Memory with a cover says Change and offers Remove, which is core\'s removal: ONE write with no cover', async () => {
    vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaVersion: 4, replaced: null })
    const w = await mountHost(writer(), sealedCover())
    await vi.waitFor(() => expect(action(w, 'cover-remove').exists()).toBe(true))
    expect(action(w, 'cover').attributes('aria-label')).toBe(t('node.cover.change'))
    await action(w, 'cover-remove').trigger('click')
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ scopeId: SCOPE, nodeId: ID, cover: null, current: { metaVersion: 3 } })
    expect(w.findComponent(MemoryCoverDialog).exists()).toBe(false)
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s guest\'s page has neither action', async (role) => {
    const w = await mountHost(guestAccessOf(role))
    expect(action(w, 'cover').exists()).toBe(false)
    expect(action(w, 'cover-remove').exists()).toBe(false)
  })

  it('a pending deletion offers none (the space is read-only)', async () => {
    const w = await mountHost(memberAccessOf({ scopeId: SCOPE, epochs: {} as EpochKeys, readonly: true }))
    expect(action(w, 'cover').exists()).toBe(false)
  })
})
