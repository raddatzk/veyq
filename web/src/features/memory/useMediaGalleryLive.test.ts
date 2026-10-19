// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Isolate the composable from crypto/API/object-store + the upload queue.
vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listMediaPages: vi.fn(),
  openGrid: vi.fn(),
  deleteMedia: vi.fn(),
  restoreMedia: vi.fn(),
  originalBlobMeta: vi.fn(),
  hasDerivatives: vi.fn(),
  unsealMeta: vi.fn(),
  effectiveCapturedAt: vi.fn(),
}))
vi.mock('./mediaRevisionPolling', () => ({
  startMediaRevisionPolling: vi.fn(),
  stopMediaRevisionPolling: vi.fn(),
}))
// The optimistic tiles pull from the global upload queue — stub them out (no donations in these cases).
vi.mock('./useOptimisticTiles', async () => {
  const { computed } = await import('vue')
  return { useOptimisticTiles: () => ({ optimisticTiles: computed(() => []), donorPreviewFor: () => null }) }
})
// The undo affordance is now a global toast (cr-0157) + i18n summary: stub the toast so the isolated composable runs and
// the test can capture the `showUndo({ onUndo })` call to drive the restore (the toast surface lives in App.vue).
const { showUndo, dismissUndo, toastError } = vi.hoisted(() => ({ showUndo: vi.fn(), dismissUndo: vi.fn(), toastError: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo, dismissUndo }),
  UNDO_GROUP: 'undo',
}))

import { defineComponent, h, type UnwrapRef } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useMediaGalleryLive } from './useMediaGalleryLive'
import { memberMemorySource } from './source'
import {
  listMediaPages,
  deleteMedia,
  restoreMedia,
  originalBlobMeta,
  openGrid,
  hasDerivatives,
  unsealMeta,
  effectiveCapturedAt,
  type MediaItem,
} from './media'
import { realI18n } from '../../test/i18n'
import { mediaItem, treeNode } from '../../test/models'

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:x')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.mocked(openGrid).mockImplementation(async () => new Uint8Array([1, 2, 3]))
  vi.mocked(deleteMedia).mockResolvedValue(undefined)
  vi.mocked(restoreMedia).mockResolvedValue(undefined)
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, w: 100, h: 100 })
  vi.mocked(effectiveCapturedAt).mockImplementation((m) => (m ? 1 : undefined))
  vi.mocked(originalBlobMeta).mockReturnValue(null)
})

const KEY = new Uint8Array(32)
const item = (id: string): MediaItem => mediaItem({ mediaId: id })
const page = (items: MediaItem[], mediaIds: string[] | null = null, cursor: string | null = null) =>
  ({ notModified: false, items, mediaRevision: 1, deleteRevision: 0, mediaIds, cursor }) as const

/** Mount a harness exposing the composable + a spy onSettle. */
function setup() {
  const onSettle = vi.fn(async () => {})
  const wrapper = mount(
    defineComponent({
      setup(_, { expose }) {
        const api = useMediaGalleryLive({
          source: memberMemorySource({ scopeId: () => 'scope-1' }),
          scopeId: () => 'scope-1',
          node: () => treeNode(),
          memoryKey: () => KEY,
          onSettle,
        })
        expose(api)
        return () => h('div')
      },
    }),
    { global: { plugins: [realI18n()] } },
  )
  return { vm: wrapper.vm as unknown as UnwrapRef<ReturnType<typeof useMediaGalleryLive>>, onSettle, wrapper }
}

describe('useMediaGalleryLive', () => {
  it('loads the media list on mount, lands ready and settles; a thumbnail is read once its grid wants it', async () => {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b')]))
    const { vm, onSettle } = setup()
    await flushPromises()
    expect(vm.items.map((i) => i.mediaId)).toEqual(['a', 'b'])
    expect(vm.status).toBe('ready')
    expect(onSettle).toHaveBeenCalled()
    expect(openGrid).not.toHaveBeenCalled() // listing reads no thumbnail: the grid asks for its window (ADR 0105)
    vm.wantThumbs('gallery', ['a'])
    await flushPromises()
    expect(vm.thumbs.get('a')).toBe('blob:x')
    expect(vm.thumbs.has('b')).toBe(false)
  })

  // ADR 0105 decision 2 (and inbox #488: the read scheduler serves a lane in the order asked): the thumbnails are asked
  // for in the order the grid announces its window — nearest to the viewport's centre first
  it('asks for the thumbnails in the order the grid wants them, not in listing order', async () => {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b'), item('c')]))
    const { vm } = setup()
    await flushPromises()
    vm.wantThumbs('gallery', ['b', 'c', 'a'])
    await flushPromises()
    expect(vi.mocked(openGrid).mock.calls.map(([, , m]) => m.mediaId)).toEqual(['b', 'c', 'a'])
  })

  it('an optimistic delete removes the tile, arms undo, soft-deletes, and a stale poll cannot re-add it', async () => {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b')]))
    const { vm } = setup()
    await flushPromises()

    await vm.onDeleteFromLightbox({ mediaId: 'a' })
    expect(vm.items.map((i) => i.mediaId)).toEqual(['b']) // optimistically removed
    expect(showUndo).toHaveBeenCalledTimes(1) // undo-toast armed
    expect(deleteMedia).toHaveBeenCalledWith('scope-1', 'a')

    // a live poll that still lists 'a' (pre-commit read) must NOT resurrect it (cr-0111 tombstone)
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b')], ['a', 'b']))
    await vm.pollMedia()
    expect(vm.items.map((i) => i.mediaId)).toEqual(['b'])
  })

  // ADR 0033 decision 6: the listing answers the FIRST page's revisions (read before its rows) and the LAST page's cursor;
  // the live poll continues from exactly those — a cursor assembled from the items, or a later page's revision, loses a
  // change committed while the pages came in (core's readItemPages and listMediaPages pin the merge itself).
  it('polls from the revisions and the cursor the listing answered, and moves on to the ones a poll answers', async () => {
    vi.mocked(listMediaPages).mockResolvedValue({ ...page([item('a'), item('b')], null, '12,b'), mediaRevision: 4, deleteRevision: 2 })
    const { vm } = setup()
    await flushPromises()
    vi.mocked(listMediaPages).mockResolvedValue({ ...page([item('c')], null, '15,c'), mediaRevision: 6, deleteRevision: 2 })
    await vm.pollMedia()
    vi.mocked(listMediaPages).mockResolvedValue({ ...page([], ['a', 'c'], null), mediaRevision: 7, deleteRevision: 3 }) // a removal only
    await vm.pollMedia()
    vi.mocked(listMediaPages).mockResolvedValue({ notModified: true })
    await vm.pollMedia()
    expect(vi.mocked(listMediaPages).mock.calls.map(([, , o]) => o)).toEqual([
      undefined, // the mount's first load: no ETag, from the beginning
      { ifNoneMatch: '4', since: '12,b', sinceDelete: 2 },
      { ifNoneMatch: '6', since: '15,c', sinceDelete: 2 },
      { ifNoneMatch: '7', since: '15,c', sinceDelete: 3 }, // a delta without a row keeps the cursor held
    ])
    expect(vm.items.map((i) => i.mediaId)).toEqual(['a', 'c'])
  })

  it('undo restores server-side and lifts the tombstone so the media re-lists', async () => {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a')]))
    const { vm } = setup()
    await flushPromises()
    await vm.onDeleteFromLightbox({ mediaId: 'a' })
    expect(vm.items).toEqual([])

    vi.mocked(listMediaPages).mockResolvedValue(page([item('a')]))
    await showUndo.mock.calls[0][0].onUndo() // the toast's "Rückgängig" action
    await flushPromises()
    expect(restoreMedia).toHaveBeenCalledWith('scope-1', 'a')
    expect(vm.items.map((i) => i.mediaId)).toEqual(['a']) // re-listed after the tombstone lifted
    expect(toastError).not.toHaveBeenCalled() // a restore that went says nothing
  })
})

/** A generated-client refusal: the `ResponseError` shape `decodeHttpError` reads (`.response` with a JSON `code`). */
const refusal = (status: number, code: string) =>
  Object.assign(new Error(code), { response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

describe('useMediaGalleryLive — a lightbox Undo whose restore is refused (inbox #429)', () => {
  /** Open the lightbox on `id` over a bucket's grid in the order c, b, a (the organizer's `openBucketLightbox`), trash
   *  it there, then hit the undo toast's Undo. */
  async function deleteInLightboxAndUndo(id: string) {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b'), item('c')]))
    const { vm } = setup()
    await flushPromises()
    const grid = ['c', 'b', 'a'].map(item)
    vm.openBucketLightbox({ items: grid, index: grid.findIndex((m) => m.mediaId === id) })
    await vm.onDeleteFromLightbox({ mediaId: id })
    await showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
  }

  it('names the refused medium by its sealed file name with its reason, and re-lists', async () => {
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => ({ v: 1, type: 'image/jpeg', name: `${m.mediaId}.jpg` }))
    vi.mocked(restoreMedia).mockRejectedValue(refusal(409, 'scope_deleting'))
    await deleteInLightboxAndUndo('b')

    expect(restoreMedia).toHaveBeenCalledWith('scope-1', 'b')
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['Not restored: b.jpg. This space is being deleted.'])
    expect(listMediaPages).toHaveBeenCalledTimes(2) // the mount's list and the re-list after the refused restore
  })

  it('names a medium without a sealed file name by its place in the grid the lightbox opened on, by reason', async () => {
    vi.mocked(restoreMedia).mockRejectedValue(refusal(404, 'media_not_found')) // purged meanwhile
    await deleteInLightboxAndUndo('a')
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not restored: Item 3. Already gone from this Memory, or you no longer have access.',
    ])

    toastError.mockClear()
    showUndo.mockClear()
    vi.mocked(restoreMedia).mockRejectedValue(new TypeError('Failed to fetch'))
    await deleteInLightboxAndUndo('b')
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['Not restored: Item 2. Please try again.'])
  })
})
