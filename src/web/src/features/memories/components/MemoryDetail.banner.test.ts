// @vitest-environment happy-dom
// uc.memory.banner A2 (ADR 0102 decision 7): deleting the gallery medium that is the banner's picture - its original's SHA-256
// is the one the banner was made from, the two may share one stored object (ADR 0013 § 5) - asks first: the banner stays, and
// the confirmation offers to remove it in the same step. Declined, nothing is deleted; accepted, the medium goes to the trash
// as any other, and the banner goes too only where that was ticked. Nothing else ties the two: another medium's delete asks
// nothing, and neither does a banner without a digest. The real confirmation dialog, real clicks.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn(), memoryBannerApi: vi.fn() }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))

import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { contentDigest } from '../../../core/blob/sharedBlob'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi, memoryBannerApi } from '../api'
import type { MemoryBannerApi } from '../../../api/generated/memory-banner'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { settleConfirm } from '../../../shared/state/useConfirm'
import { sealNodeMeta } from '../../../core/node/nodeMeta'
import { MEMORY_KIND } from '../kinds'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}
const LightboxStub = {
  props: ['items'], emits: ['delete'],
  template: `<div><button v-for="i in items" :key="i.mediaId" class="stub-delete" :data-id="i.mediaId" @click="$emit('delete', { mediaId: i.mediaId })" /></div>`,
}

let removeMemoryBanner: ReturnType<typeof vi.fn<MemoryBannerApi['removeMemoryBanner']>>
beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(contentApi, { updateNode: vi.fn(async () => undefined) })
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  removeMemoryBanner = vi.fn<MemoryBannerApi['removeMemoryBanner']>(async () => undefined)
  fakeApi(memoryBannerApi, { removeMemoryBanner })
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  settleConfirm(false)
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** A Memory with two media, its banner made from the first's file (or, `digest: false`, an older banner with none). */
async function mountWithBanner(opts: { digest?: boolean } = {}) {
  const memoryKey = v1.randomBytes(32)
  const media = []
  for (const fill of [1, 2]) {
    media.push((await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(fill) as BlobPart]), mediaType: 'image/jpeg' }, deriver)).mediaId)
  }
  const banner = {
    medium: 'image', blob: { blobId: 'banner-blob', chunkCount: 1, byteSize: 40, wrappedBlobKey: 'd3JhcA==' },
    ...(opts.digest === false ? { origin: { mediaId: media[0] } } : { sha256: await contentDigest(new Blob([new Uint8Array(8).fill(1)])) }),
    frame: 'fill', focal: { x: 0.5, y: 0.5 },
  }
  const node = { ...treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Rom 2026', parentId: null }), wrappedMeta: sealNodeMeta(memoryKey, { scopeId: SCOPE_ID, nodeId: NODE_ID }, { v: 1, banner }), metaRevision: 2 }
  const source = memberMemorySource({ scopeId: () => SCOPE_ID })
  const trash = vi.spyOn(source.deleteMedia as { trash: (id: string) => Promise<void> }, 'trash')
  const confirm = mountConfirmDialog()
  const w = mount(MemoryDetail, {
    props: { source, node, scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights() },
    global: { plugins: [realI18n()], stubs: { MediaLightbox: LightboxStub } },
    attachTo: document.body,
  })
  await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
  await w.find('[data-media-id] button').trigger('click')
  return { w, media, trash, confirm }
}

describe("deleting the banner's picture (uc.memory.banner A2)", () => {
  it('asks, says the banner stays, and removes the banner only where that was ticked', async () => {
    const { w, media, trash, confirm } = await mountWithBanner()
    await w.find(`.stub-delete[data-id="${media[0]}"]`).trigger('click')
    await flushPromises()
    expect(confirm.text()).toContain(t('memory.banner.pictureNotice'))
    expect(confirm.text()).toContain(t('memory.banner.pictureRemove'))
    ;(document.body.querySelector('#confirm-option-0') as HTMLInputElement).click()
    confirm.click(t('media.delete'))
    await flushPromises()
    expect(trash).toHaveBeenCalledWith(media[0])
    expect(removeMemoryBanner).toHaveBeenCalledOnce()
    expect(removeMemoryBanner.mock.calls[0][0].removeMemoryBannerRequest).toMatchObject({ blobId: 'banner-blob', posterBlobId: null, metaRevision: 2 })
  })

  it('accepted without the option: the medium goes, the banner stays; declined: nothing goes', async () => {
    const { w, media, trash, confirm } = await mountWithBanner()
    await w.find(`.stub-delete[data-id="${media[0]}"]`).trigger('click')
    await flushPromises()
    confirm.click(t('common.cancel'))
    await flushPromises()
    expect(trash, 'declined: nothing deleted').not.toHaveBeenCalled()

    await w.find(`.stub-delete[data-id="${media[0]}"]`).trigger('click')
    await flushPromises()
    confirm.click(t('media.delete'))
    await flushPromises()
    expect(trash).toHaveBeenCalledWith(media[0])
    expect(removeMemoryBanner).not.toHaveBeenCalled()
  })

  it('another medium\'s delete asks nothing: nothing else ties the two', async () => {
    const { w, media, trash, confirm } = await mountWithBanner()
    await w.find(`.stub-delete[data-id="${media[1]}"]`).trigger('click')
    await flushPromises()
    expect(confirm.isOpen()).toBe(false)
    expect(trash).toHaveBeenCalledWith(media[1])
  })

  it('an older banner naming its source by origin, without a digest, asks nothing (its origin is read by nobody)', async () => {
    const { w, media, trash, confirm } = await mountWithBanner({ digest: false })
    await w.find(`.stub-delete[data-id="${media[0]}"]`).trigger('click')
    await flushPromises()
    expect(confirm.isOpen()).toBe(false)
    expect(trash).toHaveBeenCalledWith(media[0])
  })
})
