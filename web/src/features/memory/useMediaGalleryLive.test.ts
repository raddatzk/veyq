// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Isolate the composable from crypto/API/object-store + the upload queue.
vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listMediaPage: vi.fn(),
  openGrid: vi.fn(),
  deleteMedia: vi.fn(),
  restoreMedia: vi.fn(),
  originalBlobMeta: vi.fn(),
  hasDerivatives: vi.fn(),
  unsealMeta: vi.fn(),
  effectiveCapturedAt: vi.fn(),
  newestCursor: vi.fn(),
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
  listMediaPage,
  deleteMedia,
  restoreMedia,
  originalBlobMeta,
  openGrid,
  hasDerivatives,
  unsealMeta,
  effectiveCapturedAt,
  newestCursor,
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
  vi.mocked(newestCursor).mockReturnValue(undefined)
  vi.mocked(originalBlobMeta).mockReturnValue(null)
})

const KEY = new Uint8Array(32)
const item = (id: string): MediaItem => mediaItem({ mediaId: id })
const page = (items: MediaItem[], mediaIds: string[] | null = null) =>
  ({ notModified: false, items, mediaRevision: 1, deleteRevision: 0, mediaIds })

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
  it('loads the media list on mount, decrypts thumbnails, lands ready, and settles', async () => {
    vi.mocked(listMediaPage).mockResolvedValue(page([item('a'), item('b')]))
    const { vm, onSettle } = setup()
    await flushPromises()
    expect(vm.items.map((i) => i.mediaId)).toEqual(['a', 'b'])
    expect(vm.status).toBe('ready')
    expect(vm.thumbs.get('a')).toBe('blob:x')
    expect(onSettle).toHaveBeenCalled()
  })

  it('an optimistic delete removes the tile, arms undo, soft-deletes, and a stale poll cannot re-add it', async () => {
    vi.mocked(listMediaPage).mockResolvedValue(page([item('a'), item('b')]))
    const { vm } = setup()
    await flushPromises()

    await vm.onDeleteFromLightbox({ mediaId: 'a' })
    expect(vm.items.map((i) => i.mediaId)).toEqual(['b']) // optimistically removed
    expect(showUndo).toHaveBeenCalledTimes(1) // undo-toast armed
    expect(deleteMedia).toHaveBeenCalledWith('scope-1', 'a')

    // a live poll that still lists 'a' (pre-commit read) must NOT resurrect it (cr-0111 tombstone)
    vi.mocked(listMediaPage).mockResolvedValue(page([item('a'), item('b')], ['a', 'b']))
    await vm.pollMedia()
    expect(vm.items.map((i) => i.mediaId)).toEqual(['b'])
  })

  it('undo restores server-side and lifts the tombstone so the media re-lists', async () => {
    vi.mocked(listMediaPage).mockResolvedValue(page([item('a')]))
    const { vm } = setup()
    await flushPromises()
    await vm.onDeleteFromLightbox({ mediaId: 'a' })
    expect(vm.items).toEqual([])

    vi.mocked(listMediaPage).mockResolvedValue(page([item('a')]))
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
    vi.mocked(listMediaPage).mockResolvedValue(page([item('a'), item('b'), item('c')]))
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
    expect(listMediaPage).toHaveBeenCalledTimes(2) // the mount's list and the re-list after the refused restore
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
