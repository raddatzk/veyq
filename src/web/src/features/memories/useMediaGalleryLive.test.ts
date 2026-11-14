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
  return { useOptimisticTiles: () => ({ optimisticTiles: computed(() => []), donorPreviewFor: () => null, wantPreviews: () => {} }) }
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

// uc.moderation.take-a-measure (ADR 0093 § 6): a block, and its lift, bump the item's revision (ADR 0033), so the live
// poll carries the medium again - the gallery adopts its moderation state in place, a member's and a guest's alike.
describe('useMediaGalleryLive — a medium blocked or lifted while the gallery is open', () => {
  /** Load a, b, c with every thumbnail shown, each object URL its own, and the lightbox open on b. */
  async function openGallery() {
    let n = 0
    vi.mocked(URL.createObjectURL).mockImplementation(() => `blob:${++n}`)
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b'), item('c')]))
    const ctx = setup()
    await flushPromises()
    ctx.vm.wantThumbs('gallery', ['a', 'b', 'c'])
    await flushPromises()
    await new Promise((r) => setTimeout(r, 120)) // the thumbnails install together (INSTALL_EVERY_MS)
    const index = ctx.vm.layoutItems.findIndex((t) => t.id === 'b')
    ctx.vm.openLightboxAt(index)
    return ctx
  }
  const geometry = (vm: ReturnType<typeof setup>['vm']) => vm.layoutItems.map((t) => [t.id, t.aspectRatio])

  it('turns its tile to the placeholder at once, revokes its thumbnail URL, reads it no more, and keeps every tile in place', async () => {
    const { vm } = await openGallery()
    const shown = vm.thumbs.get('b')
    expect(shown).toMatch(/^blob:/)
    const before = geometry(vm)
    vi.mocked(openGrid).mockClear()

    vi.mocked(listMediaPages).mockResolvedValue(page([{ ...item('b'), blocked: true }]))
    await vm.pollMedia()

    const tile = vm.layoutItems.find((t) => t.id === 'b')
    expect(tile?.kind === 'media' && tile.media.blocked, 'the tile shows "Blocked by the operator"').toBe(true)
    expect(vm.thumbs.has('b'), 'its decrypted thumbnail is gone from the URL cache').toBe(false)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(shown)
    expect(vm.thumbs.get('a')).toMatch(/^blob:/) // the rest of the grid keeps its thumbnails
    expect(geometry(vm), 'no tile moves: the windowed grid keeps its scroll anchor').toEqual(before)
    // the open lightbox holds the same item: it shows the placeholder too (MediaLightbox revokes its preview)
    expect(vm.lightboxItems[vm.lightboxIndex!].mediaId).toBe('b')
    expect(vm.lightboxItems[vm.lightboxIndex!].blocked).toBe(true)

    vm.wantThumbs('gallery', ['a', 'b', 'c']) // the grid re-announces its window
    await flushPromises()
    expect(vi.mocked(openGrid).mock.calls.map(([, , m]) => m.mediaId), 'a blocked medium is not read (451)').not.toContain('b')
  })

  it('a lift restores the tile through the thumbnail pipeline', async () => {
    const { vm } = await openGallery()
    vi.mocked(listMediaPages).mockResolvedValue(page([{ ...item('b'), blocked: true }]))
    await vm.pollMedia()
    vi.mocked(openGrid).mockClear()

    vi.mocked(listMediaPages).mockResolvedValue(page([item('b')])) // lifted: blockedByOperator false again
    await vm.pollMedia()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 120))

    expect(vm.items.find((m) => m.mediaId === 'b')?.blocked).toBeFalsy()
    expect(vm.lightboxItems[vm.lightboxIndex!].blocked).toBeFalsy()
    expect(vi.mocked(openGrid).mock.calls.map(([, , m]) => m.mediaId)).toEqual(['b'])
    expect(vm.thumbs.get('b')).toMatch(/^blob:/)
  })

  it('a medium a poll adds gets its thumbnail once the grid wants it', async () => {
    const { vm } = await openGallery()
    vi.mocked(openGrid).mockClear()
    vi.mocked(listMediaPages).mockResolvedValue(page([item('d')]))
    await vm.pollMedia()
    vm.wantThumbs('gallery', ['a', 'b', 'c', 'd'])
    await flushPromises()
    expect(vi.mocked(openGrid).mock.calls.map(([, , m]) => m.mediaId)).toEqual(['d'])
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

  // uc.memory.tracks (ADR 0130 decisions 1 and 7): a GPX track is a medium the gallery lists like any, known by its opened
  // blob meta, and no photo - it is in `tracks`, never in `items` (the grid, the lightbox, the count, the organizer).
  it('hands a GPX track to the Map tab and keeps it out of every view of photos', async () => {
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) =>
      m.mediaId === 'trk' ? { v: 1, type: 'application/gpx+xml', name: 'ride.gpx' } : m.mediaId === 'pic' ? { v: 1, type: 'image/jpeg', name: 'IMG.jpg' } : null)
    vi.mocked(listMediaPages).mockResolvedValue(page([item('pic'), item('trk'), item('bare')]))
    const { vm } = setup()
    await flushPromises()
    expect(vm.tracks.map((i) => i.mediaId)).toEqual(['trk'])
    expect(vm.items.map((i) => i.mediaId)).toEqual(['pic', 'bare'])
    expect(vm.layoutItems.map((t) => t.id).sort()).toEqual(['bare', 'pic'])
    expect(vm.openLightboxOn('trk')).toBe(false)
    // a removal reaches a track as it reaches a photo
    vm.removeMedia('trk')
    expect(vm.tracks).toEqual([])
    expect(vm.items.map((i) => i.mediaId)).toEqual(['pic', 'bare'])
  })

  // en.memory.media § Which media a photo view shows (ADR 0135 decision 2): ONE positive predicate, `isGalleryMedium`, decides
  // what a photo view shows - an image or a video. A music track goes to `music`, a GPX track to `tracks`, and a type nobody
  // decided on to neither list and no photo view; a medium without any meta (older than `blob-meta`) stays a photo. Lever:
  // `items` filtering out tracks and music by name again (the unknown type then shows as a tile).
  it('shows only images and videos in the photo views; music and tracks go to their own lists, an unknown type to none', async () => {
    const types: Record<string, string> = { pic: 'image/jpeg', vid: 'video/mp4', song: 'audio/mpeg', trk: 'application/gpx+xml', odd: 'application/x-unknown' }
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => (types[m.mediaId] ? { v: 1, type: types[m.mediaId], name: m.mediaId } : null))
    vi.mocked(listMediaPages).mockResolvedValue(page(['pic', 'vid', 'song', 'trk', 'odd', 'bare'].map((id) => item(id))))
    const { vm } = setup()
    await flushPromises()
    expect(vm.items.map((i) => i.mediaId)).toEqual(['pic', 'vid', 'bare'])
    expect(vm.layoutItems.map((t) => t.id).sort()).toEqual(['bare', 'pic', 'vid'])
    expect(vm.music.map((i) => i.mediaId)).toEqual(['song'])
    expect(vm.tracks.map((i) => i.mediaId)).toEqual(['trk'])
    expect(['song', 'trk', 'odd'].map((id) => vm.openLightboxOn(id)), 'no lightbox opens on what is no photo').toEqual([false, false, false])
    vm.removeMedia('song')
    expect(vm.music).toEqual([])
  })
})
