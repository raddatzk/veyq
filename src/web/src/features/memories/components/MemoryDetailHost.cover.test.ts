// @vitest-environment happy-dom
// The Memory page's cover is core's (inbox #411, follow-up 2026-10-12): the header action (id `cover`, Set/Change) and the cover
// tile open core's cover DIALOG - the one a folder and a drop box get - and a cover is set only by an upload there (the lightbox
// offers no "Set as cover"). The Memory's one seam is `cover.findStored`: an uploaded image equal to a gallery photo shares that
// photo's PREVIEW (ADR 0013 § 5). Saving is core's one atomic write; removal lives in the dialog. A share guest's page shows the
// cover and carries no action.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
vi.mock('../../../core/node/cover/coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import MemoryDetailHost from './MemoryDetailHost.vue'
import { MEMORY_KIND } from '../kinds'
import { addMedia, listMedia, type MediaDeriver } from '../media'
import { blobApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { uploadCoverImage, writeNodeCover } from '../../../core/node/cover/coverWrite'
import { uploadedCover } from '../../../core/node/cover/coverModel'
import { resetCovers } from '../../../core/node/cover/coverStore'
import { ShareRole } from '../../../api/generated/sharing'
import type { NodeDetailProps } from '../../../core/applications/registry'
import type { EpochKeys } from '../../spaces/epochs'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { authenticatedCalls } from '../../../test/guestMode'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const ID = '22222222-2222-4222-8222-222222222222'
const KEY = v1.randomBytes(32)
const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}
const sealedMeta = (cover: object) =>
  b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId: ID }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1, cover }))))
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
  vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaRevision: 4, replaced: null })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountHost(access: NodeDetailProps['access'], wrappedMeta: string | null = null) {
  const node = treeNode({ id: ID, kind: MEMORY_KIND, name: 'Rom 2026', parentId: null, wrappedMeta, metaRevision: wrappedMeta ? 3 : 0 })
  const tree = { ...memberTreeOf(() => [node], { scopeId: SCOPE }), keyOf: () => KEY }
  const w = mount(MemoryDetailHost, {
    props: { node, scopeId: SCOPE, epochs: null, contentKey: KEY, children: { folders: [], byKind: new Map() }, access, tree } as unknown as NodeDetailProps,
    global: { plugins: [realI18n()] },
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
const cropperImg = () => document.body.querySelector<HTMLImageElement>('[role="dialog"] .veyq-cropper-frame img')
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

describe('the Memory page\'s cover action opens core\'s dialog', () => {
  it('"Set cover" opens it; the dialog offers choosing an image - no other source - and nothing to remove', async () => {
    const w = await mountHost(writer())
    expect(action(w, 'cover').attributes('aria-label')).toBe(t('node.cover.set'))
    expect(w.findAllComponents(VeyqButton).find((b) => b.element === action(w, 'cover').element)?.props('icon')).toBe('image')
    expect(dialog()).toBeNull()
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    expect(dialogButton(t('node.cover.choose'))).toBeTruthy()
    expect(dialog()!.textContent).not.toContain('gallery') // a cover is set only by an upload
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
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ scopeId: SCOPE, nodeId: ID, cover: null, current: { metaRevision: 3 } })
    await vi.waitFor(() => expect(dialog()).toBeNull())
  })

})

describe('an uploaded image the gallery already stores is shared, not stored again (ADR 0013 § 5)', () => {
  /** Pick `file` in the open dialog's file input, frame it and save. */
  async function pickAndSave(file: File) {
    const input = document.body.querySelector<HTMLInputElement>('[role="dialog"] input[type="file"]')!
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    input.dispatchEvent(new Event('change'))
    await vi.waitFor(() => expect(cropperImg()).not.toBeNull())
    await frame(1000, 1000)
    dialogButton(t('node.cover.save'))!.click()
  }

  it('a picked image equal to a gallery photo\'s original becomes a row on that photo\'s PREVIEW: nothing uploaded', async () => {
    const mediaId = await seedPhoto()
    const preview = (await listMedia(SCOPE, ID)).find((m) => m.mediaId === mediaId)!.derivatives.find((d) => d.kind === 'PREVIEW')!.ref
    const w = await mountHost(writer())
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    await pickAndSave(new File([new Uint8Array(8).fill(1)], 'same.jpg', { type: 'image/jpeg' }))
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    const write = vi.mocked(writeNodeCover).mock.calls[0][0]
    expect(write.shared?.sourceBlobId).toBe(preview.blobId)
    expect(write.uploadId).toBeUndefined()
    expect(vi.mocked(uploadCoverImage)).not.toHaveBeenCalled()
    const cover = write.cover as { source: { kind: string; ref: { blobId: string } } }
    expect(cover.source).toMatchObject({ kind: 'upload', ref: { blobId: write.shared!.blobId } })
  })

  it('a picked image of another content is uploaded as before', async () => {
    await seedPhoto()
    vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'up-c', plan: { blobId: 'c-1', chunkCount: 1, byteSize: 32, wrappedBlobKey: 'd3JhcA==', ownerId: ID } })
    const w = await mountHost(writer())
    await action(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(dialog()).not.toBeNull())
    await pickAndSave(new File([new Uint8Array(8).fill(2)], 'other.jpg', { type: 'image/jpeg' }))
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ uploadId: 'up-c', shared: undefined })
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
