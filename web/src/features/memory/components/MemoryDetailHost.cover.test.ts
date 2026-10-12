// @vitest-environment happy-dom
// The Memory page's cover is core's, with ONE seam for the Memory's own source (inbox #411, follow-up 2026-10-12): the header
// action (id `cover`, Set/Change), the cover tile and the gallery lightbox's "Set as cover" all open core's cover DIALOG - the
// one a folder and a drop box get - and the lightbox feeds it the picked photo through the kind's `cover.extraSource`. Saving is core's
// one atomic write; removal lives in the dialog. A share guest's page shows the cover and carries no action.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn() }))
vi.mock('../../../core/node/cover/coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import MemoryDetailHost from './MemoryDetailHost.vue'
import MemoryDetail from './MemoryDetail.vue'
import { MEMORY_KIND } from '../kinds'
import { addMedia, type MediaDeriver } from '../media'
import { blobApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { writeNodeCover } from '../../../core/node/cover/coverWrite'
import { uploadedCover } from '../../../core/node/cover/coverModel'
import { resetCovers } from '../../../core/node/cover/coverStore'
import { ShareRole } from '../../../api/generated/sharing'
import type { NodeDetailProps } from '../../../core/applications/registry'
import type { EpochKeys } from '../../spaces/epochs'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { authenticatedCalls } from '../../../test/guestMode'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const ID = '22222222-2222-4222-8222-222222222222'
const KEY = v1.randomBytes(32)
const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}
const sealedMeta = (cover: object) =>
  b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId: ID }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1, cover }))))
const uploadCover = (crop = { x: 0, y: 0, width: 1, height: 1 }) =>
  sealedMeta(uploadedCover({ blobId: 'b-1', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: ID }, crop))

beforeEach(() => {
  vi.clearAllMocks()
  authenticatedCalls.length = 0
  resetCovers()
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaVersion: 4, replaced: null })
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
type Host = Awaited<ReturnType<typeof mountHost>>
const action = (w: Host, id: string) => w.find(`.veyq-node-header-actions button[data-action="${id}"]`)
const writer = () => memberAccessOf({ scopeId: SCOPE, epochs: {} as EpochKeys })
const dialog = () => document.body.querySelector<HTMLElement>('[role="dialog"]')
const dialogButton = (label: string) => [...document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find((b) => b.textContent?.trim() === label)
const cropperImg = () => document.body.querySelector<HTMLImageElement>('[role="dialog"] .memory-cover-cropper img')
async function frame(natW = 3200, natH = 1800) {
  const img = cropperImg()!
  Object.defineProperty(img, 'naturalWidth', { configurable: true, value: natW })
  Object.defineProperty(img, 'naturalHeight', { configurable: true, value: natH })
  img.dispatchEvent(new Event('load'))
  await flushPromises()
}
/** A photo in the gallery: its tile opens the lightbox. */
async function seedPhoto(): Promise<string> {
  const { mediaId } = await addMedia(SCOPE, ID, KEY, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, deriver)
  return mediaId
}
const lightboxSetCover = (w: Host) => w.find(`.media-lightbox [aria-label="${t('memory.cover.setFromPhoto')}"]`)

describe('the Memory page\'s cover action opens core\'s dialog', () => {
  it('"Set cover" opens it; the dialog offers choosing an image and the Memory\'s gallery hint, and nothing to remove', async () => {
    const w = await mountHost(writer())
    expect(action(w, 'cover').attributes('aria-label')).toBe(t('node.cover.set'))
    expect(action(w, 'cover').find('.pi-image').exists()).toBe(true)
    expect(dialog()).toBeNull()
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    expect(dialogButton(t('node.cover.choose'))).toBeTruthy()
    expect(dialog()!.textContent).toContain(t('memory.cover.galleryHint')) // the Memory's own line, passed in by its descriptor
    expect(dialogButton(t('node.cover.remove'))).toBeUndefined()
  })

  it('the cover tile opens the very same dialog', async () => {
    const w = await mountHost(writer())
    await w.find('.memory-cover-preview').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    expect(dialog()!.textContent).toContain(t('node.cover.copy'))
  })

  it('a Memory with a cover says Change, has no separate Remove, and removes inside the dialog: ONE write with no cover', async () => {
    const w = await mountHost(writer(), uploadCover())
    expect(action(w, 'cover').attributes('aria-label')).toBe(t('node.cover.change'))
    expect(w.find('.veyq-node-header-actions button[data-action="cover-remove"]').exists()).toBe(false)
    expect(w.findAll('.veyq-node-header-actions button').filter((b) => b.attributes('data-action')?.startsWith('cover'))).toHaveLength(1)
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    dialogButton(t('node.cover.remove'))!.click()
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ scopeId: SCOPE, nodeId: ID, cover: null, current: { metaVersion: 3 } })
    await vi.waitFor(() => expect(dialog()).toBeNull())
  })

  it('a Memory\'s stored crop is restored when its cover is re-opened (Change -> Save keeps the photo, moves nothing)', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }
    const mediaId = await seedPhoto()
    const w = await mountHost(writer(), sealedMeta({ source: { kind: 'media', mediaId }, crop }))
    await vi.waitFor(() => expect(w.find('.memory-cover-preview img').exists()).toBe(true)) // the cover has loaded
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(cropperImg()).not.toBeNull())
    await frame(3200, 1800)
    dialogButton(t('node.cover.save'))!.click()
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    const cover = vi.mocked(writeNodeCover).mock.calls[0][0].cover as { source: { kind: string; mediaId: string }; crop: typeof crop }
    expect(cover.source).toEqual({ kind: 'media', mediaId }) // the very photo, untouched
    expect(cover.crop.x).toBeCloseTo(crop.x)
    expect(cover.crop.width).toBeCloseTo(crop.width)
  })
})

describe('the gallery lightbox feeds a photo into the same dialog (the Memory\'s extra source)', () => {
  it('"Set as cover" opens the dialog on that photo; Save writes the stored photo-cover shape with the crop it was framed with', async () => {
    await seedPhoto()
    const w = await mountHost(writer())
    await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
    await w.find('[data-media-id] button').trigger('click')
    await vi.waitFor(() => expect(lightboxSetCover(w).exists()).toBe(true))
    await vi.waitFor(() => expect(w.findAll('.memory-gallery img, [data-media-id] img').length).toBeGreaterThan(0)) // thumbnail decrypted
    await lightboxSetCover(w).trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    await vi.waitFor(() => expect(cropperImg()).not.toBeNull())
    await frame(1000, 1000) // a square photo: the framed 16:9 window is what is stored
    dialogButton(t('node.cover.save'))!.click()
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    const write = vi.mocked(writeNodeCover).mock.calls[0][0]
    const cover = write.cover as { source: { kind: string; mediaId: string }; crop: { width: number; height: number } }
    expect(cover.source.kind).toBe('media')
    expect(cover.source.mediaId).toEqual(expect.any(String))
    expect(cover.crop.width).toBeCloseTo(1)
    expect(cover.crop.height).toBeCloseTo(9 / 16)
    expect(write).toMatchObject({ nodeId: ID, surfaceConflict: true })
    expect(write.uploadId, 'a photo needs no blob of its own').toBeUndefined()
    expect(Object.keys(cover)).toEqual(['source', 'crop']) // the stored key order of every Memory cover
  })

  it('a photo with no thumbnail yet (nothing to crop) is set as the cover full-frame in one step, no dialog', async () => {
    const w = await mountHost(writer())
    w.findComponent(MemoryDetail).vm.$emit('coverFromPhoto', { mediaId: 'm-9', src: null })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0].cover).toEqual({ source: { kind: 'media', mediaId: 'm-9' }, crop: { x: 0, y: 0, width: 1, height: 1 } })
    expect(dialog()).toBeNull()
  })

  it('cancelling the dialog writes nothing and leaves the lightbox to its keys again', async () => {
    await seedPhoto()
    const w = await mountHost(writer())
    await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
    await w.find('[data-media-id] button').trigger('click')
    await vi.waitFor(() => expect(lightboxSetCover(w).exists()).toBe(true))
    await lightboxSetCover(w).trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    dialogButton(t('common.cancel'))!.click()
    await vi.waitFor(() => expect(dialog()).toBeNull())
    expect(writeNodeCover).not.toHaveBeenCalled()
  })
})

describe('who gets the action', () => {
  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s guest\'s page shows the cover and has no cover action, no dialog, no authenticated call', async (role) => {
    const w = await mountHost(guestAccessOf(role), uploadCover())
    expect(action(w, 'cover').exists()).toBe(false)
    expect(w.find('[data-action^="cover"]').exists()).toBe(false)
    expect(w.find('.memory-cover-preview').exists()).toBe(true) // the cover is shown...
    expect((w.find('.memory-cover-preview').element as HTMLButtonElement).disabled).toBe(true) // ...but is no way in
    await w.find('.memory-cover-preview').trigger('click')
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(writeNodeCover).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })

  it('a pending deletion offers none (the space is read-only)', async () => {
    const w = await mountHost(memberAccessOf({ scopeId: SCOPE, epochs: {} as EpochKeys, readonly: true }))
    expect(action(w, 'cover').exists()).toBe(false)
    expect((w.find('.memory-cover-preview').element as HTMLButtonElement).disabled).toBe(true)
  })
})
