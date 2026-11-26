// @vitest-environment happy-dom
// uc.memory.buckets § Correct a selection's capture time, wired as a member meets it: the Beiträge tab's selection reaches
// the detail's lightbox, "Shift capture time" opens it with the editor open, and the shift the lightbox plans is written by
// the detail's own composable through the member's media-meta door — two photos, two ordinary meta writes. Real DOM, real
// clicks; the media API is the in-memory gallery fake, the buckets list an empty one (every photo in your own primary).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), bucketsApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => toast }))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { readExifTags } from '../../../shared/image/exifTags'
import MemoryDetail from './MemoryDetail.vue'
import VeyqDateField from '../../../shared/ui/VeyqDateField.vue'
import { memberMemorySource } from '../source'
import { addMedia, unsealMeta, type MediaDeriver } from '../media'
import { MEMORY_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, bucketsApi, itemFavoritesApi } from '../api'
import { fakeApi, stubApi } from '../../../test/apiMock'
import type { ContentApi } from '../../../api/generated/content'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights, mediaItem } from '../../../test/models'
import { mountWithHeader } from '../../../test/nodeHeader'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const T1 = Date.UTC(2024, 5, 1, 10, 0, 0)
const T2 = Date.UTC(2024, 5, 1, 10, 20, 0)
const HOUR = 3600_000

const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}

let store: ReturnType<typeof galleryFakeStore>
beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(contentApi, { updateNode: vi.fn<ContentApi['updateNode']>(async () => {}) })
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  stubApi(bucketsApi, { listNodeBuckets: { buckets: [] } })
  store = galleryFakeStore()
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

/** The date field's value for `ms`: local wall-clock to the minute, as the lightbox writes it. */
const local = (ms: number) => {
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

describe('MemoryDetail — a selection\'s capture-time shift from the Beiträge tab', () => {
  /** Lever: the detail hands the lightbox no `captureTimeShift` (or no `startEditing`): the editor is closed, or no offer. */
  it('"Shift capture time" opens the lightbox with the editor; the shift writes both selected photos', async () => {
    const memoryKey = v1.randomBytes(32)
    for (const [i, at] of [T1, T2].entries()) {
      vi.mocked(readExifTags).mockResolvedValueOnce({ DateTimeOriginal: at })
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(i + 1) as BlobPart]), mediaType: 'image/jpeg' }, deriver)
    }
    const w = mountWithHeader(MemoryDetail, {
      props: {
        source: memberMemorySource({ scopeId: () => SCOPE_ID }), scopeId: SCOPE_ID, memoryKey,
        node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Rom 2026', parentId: null }), rights: memberMemoryRights(),
      },
      global: { plugins: [realI18n()] },
      attachTo: document.body,
    })
    await flushPromises()
    await flushPromises()
    await w.findAll('[role="tab"]').find((tab) => tab.text().startsWith(t('memory.buckets.title')))!.trigger('mousedown')
    await flushPromises()

    const checks = w.findAll('.veyq-bucket-organizer .media-tile-check input')
    expect(checks, 'control: both photos in your own bucket').toHaveLength(2)
    for (const c of checks) await c.trigger('change')
    await w.find('.bucket-more-button').trigger('click') // the selection bar's ⋯, the layer's real menu
    await flushPromises()
    const entry = [...document.querySelectorAll<HTMLElement>('.veyq-menu__item')].find((e) => e.textContent?.includes(t('memory.buckets.shiftCapturedAt')))!
    entry.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    await flushPromises()

    expect(w.find('.media-lightbox #captured-at-input').exists(), 'the editor is open').toBe(true)
    const field = w.findAllComponents(VeyqDateField).find((c) => c.props('inputId') === 'captured-at-input')!
    // the first selected in the grid: the organizer lists in listing order, so read which one the lightbox shows
    const shownBefore = new Date(field.props('modelValue') as string).getTime()
    field.vm.$emit('update:modelValue', local(shownBefore + 2 * HOUR))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    await w.find('[data-testid="capture-time-shift-all"]').trigger('click')
    await flushPromises()

    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(2) // one ordinary write per photo, no bulk call
    const overrides = store.media.map((m) => unsealMeta(SCOPE_ID, memoryKey, mediaItem({ mediaId: m.mediaId, wrappedMeta: m.wrappedMeta ?? undefined }))?.capturedAtOverride)
    expect(overrides).toEqual([T1 + 2 * HOUR, T2 + 2 * HOUR])
    expect(toast.showUndo.mock.calls[0]?.[0].summary).toBe(t('memory.buckets.shifted', { count: 2 }, 2))
    expect(toast.error).not.toHaveBeenCalled()
    w.unmount()
  })
})
