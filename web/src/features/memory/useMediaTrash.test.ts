// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the media data layer so the composable is tested in isolation from crypto/API/object-store.
vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listTrash: vi.fn(),
  restoreMedia: vi.fn(),
  purgeMedia: vi.fn(),
  openGrid: vi.fn(),
  hasDerivatives: vi.fn(),
  unsealMeta: vi.fn(),
  originalBlobMeta: vi.fn(),
}))
// A refused restore is named in an error toast (inbox #432): stub the toast so the isolated composable runs and the
// test reads what it said.
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import { defineComponent, h, type UnwrapRef } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useMediaTrash } from './useMediaTrash'
import { memberMemorySource } from './source'
import { listTrash, restoreMedia, purgeMedia, openGrid, hasDerivatives, unsealMeta, originalBlobMeta, type MediaItem } from './media'
import { realI18n, t } from '../../test/i18n'
import { mediaItem, treeNode } from '../../test/models'
import { closeListing } from './openedListing'

const KEY = new Uint8Array(32)
const item = (id: string): MediaItem => mediaItem({ mediaId: id })

/** Pick the tile menu's Restore for [mediaId] as the rendered menu does: open it on the tile, run the entry, and do
 *  not await it (PrimeVue's `command` returns nothing). */
function restoreFromTileMenu(vm: UnwrapRef<ReturnType<typeof useMediaTrash>>, mediaId: string) {
  vm.openTrashMenu(new Event('click'), mediaId)
  const restore = vm.trashMenuModel[0]
  restore.command!({ originalEvent: new Event('click'), item: restore })
}

/** Mount a harness that exposes the composable's return + the two host callbacks as spies. */
function setup() {
  const reloadGallery = vi.fn()
  const liftTombstone = vi.fn()
  const wrapper = mount(
    defineComponent({
      setup(_, { expose }) {
        const api = useMediaTrash({
          source: memberMemorySource({ scopeId: () => 'scope-1' }),
          scopeId: () => 'scope-1',
          node: () => treeNode(),
          memoryKey: () => KEY,
          reloadGallery,
          liftTombstone,
        })
        expose(api)
        return () => h('div')
      },
    }),
    { global: { plugins: [realI18n()] } },
  )
  // `wrapper.vm` unwraps the exposed refs at runtime; type it as the unwrapped shape (refs → values, fns intact).
  return { vm: wrapper.vm as unknown as UnwrapRef<ReturnType<typeof useMediaTrash>>, reloadGallery, liftTombstone, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  closeListing('scope-1', KEY) // the opened listing keeps a value per seal; each case's mocks answer anew
  vi.mocked(restoreMedia).mockResolvedValue(undefined)
  vi.mocked(purgeMedia).mockResolvedValue(undefined)
  vi.mocked(openGrid).mockImplementation(async () => new Uint8Array([1, 2, 3]))
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, w: 100, h: 100 })
  vi.mocked(originalBlobMeta).mockReturnValue(null)
})

describe('useMediaTrash', () => {
  it('loadTrash lists the trashed media, decrypts a thumbnail per item, and lands ready', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    expect(listTrash).toHaveBeenCalledWith('scope-1', 'node-1')
    expect(vm.trashedItems.map((i) => i.mediaId)).toEqual(['a', 'b'])
    expect(vm.trashStatus).toBe('ready')
    expect(vm.trashThumbs.size).toBe(2)
  })

  it('loadTrash lands error when the list fails', async () => {
    vi.mocked(listTrash).mockRejectedValue(new Error('boom'))
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    expect(vm.trashStatus).toBe('error')
  })

  it('restoreSelected restores each, lifts the gallery tombstone, drops them, and re-lists once', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
    const { vm, reloadGallery, liftTombstone } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.toggleTrashSelect('a')
    vm.toggleTrashSelect('b')
    await vm.restoreSelected()
    expect(restoreMedia).toHaveBeenCalledTimes(2)
    expect(liftTombstone).toHaveBeenCalledWith('a')
    expect(liftTombstone).toHaveBeenCalledWith('b')
    expect(reloadGallery).toHaveBeenCalledTimes(1)
    expect(vm.trashedItems).toHaveLength(0)
    expect(vm.selectedTrash.size).toBe(0)
    await flushPromises()
    expect(toastError).not.toHaveBeenCalled() // a restore that went says nothing (inbox #432)
    expect(listTrash).toHaveBeenCalledTimes(1) // ... and does not re-list the tab
  })

  it("the menu's Restore restores the one, lifts its tombstone, drops it, re-lists the gallery, and says nothing", async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
    const { vm, reloadGallery, liftTombstone } = setup()
    await vm.loadTrash()
    await flushPromises()
    restoreFromTileMenu(vm, 'b')
    await flushPromises()
    expect(restoreMedia).toHaveBeenCalledWith('scope-1', 'b')
    expect(liftTombstone).toHaveBeenCalledWith('b')
    expect(reloadGallery).toHaveBeenCalledTimes(1)
    expect(vm.trashedItems.map((i) => i.mediaId)).toEqual(['a'])
    expect(toastError).not.toHaveBeenCalled()
    expect(listTrash).toHaveBeenCalledTimes(1)
  })

  it('confirmPurge hard-deletes the confirmed ids and drops them from the tab', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a')])
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.purgeConfirm = { ids: ['a'] }
    await vm.confirmPurge()
    expect(purgeMedia).toHaveBeenCalledWith('scope-1', 'a')
    expect(vm.trashedItems).toHaveLength(0)
    expect(vm.purgeConfirm).toBeNull()
  })

  it('toggleSelectAllTrash selects all visible ↔ clears, tracked by allTrashSelected + the bar label (cr-0152)', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
    const { vm } = setup()
    expect(vm.allTrashSelected).toBe(false) // empty trash never counts as "all selected"
    await vm.loadTrash()
    await flushPromises()
    vm.toggleTrashSelect('a') // partial selection → select-all still offers "select all"
    expect(vm.allTrashSelected).toBe(false)
    expect(vm.selectedTrashLabel).toBe(t('memory.trash.selected', { selected: 1, visible: 2 }))
    vm.toggleSelectAllTrash()
    expect(vm.selectedTrash.size).toBe(2)
    expect(vm.allTrashSelected).toBe(true)
    expect(vm.selectedTrashLabel).toBe(t('memory.trash.selected', { selected: 2, visible: 2 }))
    vm.toggleSelectAllTrash() // all selected → the same toggle deselects
    expect(vm.selectedTrash.size).toBe(0)
    expect(vm.allTrashSelected).toBe(false)
  })

  it('resetTrash clears listing/status/selection (Memory switch)', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a')])
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.toggleTrashSelect('a')
    vm.resetTrash()
    expect(vm.trashedItems).toHaveLength(0)
    expect(vm.trashStatus).toBe('idle')
    expect(vm.selectedTrash.size).toBe(0)
    expect(vm.trashThumbs.size).toBe(0)
  })
})

/** A generated-client refusal: the `ResponseError` shape `decodeHttpError` reads (`.response` with a JSON `code`). */
const refusal = (status: number, code: string) =>
  Object.assign(new Error(code), { response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

describe('useMediaTrash — a restore the server refuses (inbox #432)', () => {
  it('restoreSelected names the one refused of three with its reason, restores the other two, and re-lists the tab', async () => {
    vi.mocked(listTrash).mockResolvedValueOnce([item('a'), item('b'), item('c')]).mockResolvedValueOnce([item('b')])
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => ({ v: 1, type: 'image/jpeg', name: `${m.mediaId}.jpg` }))
    vi.mocked(restoreMedia).mockImplementation(async (_s, id) => {
      if (id === 'b') throw refusal(409, 'scope_deleting')
    })
    const { vm, reloadGallery } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.toggleSelectAllTrash()
    await vm.restoreSelected()
    await flushPromises()

    expect(restoreMedia).toHaveBeenCalledTimes(3) // the refusal did not stop the rest
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['Not restored: b.jpg. This space is being deleted.'])
    expect(reloadGallery).toHaveBeenCalledTimes(1) // a and c reappear in the gallery
    expect(listTrash).toHaveBeenCalledTimes(2) // the tab re-lists: b is still in the trash
    expect(vm.trashedItems.map((i) => i.mediaId)).toEqual(['b'])
  })

  it('restoreSelected names a medium without a sealed file name by its place in the trash grid, one error per reason', async () => {
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b'), item('c')])
    vi.mocked(restoreMedia).mockImplementation(async (_s, id) => {
      if (id === 'a') throw refusal(404, 'media_not_found') // purged meanwhile
      if (id === 'c') throw new TypeError('Failed to fetch')
    })
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.toggleSelectAllTrash()
    await vm.restoreSelected()
    await flushPromises()
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not restored: Item 1. Already gone from this Memory, or you no longer have access.',
      'Not restored: Item 3. Please try again.',
    ])
  })

  it('a restore of a medium the operator blocked is named as deletable, not restorable, and the rest is still restored', async () => {
    // uc.moderation.take-a-measure: a blocked medium's restore answers 451 blocked_by_operator; its delete stays allowed.
    vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => ({ v: 1, type: 'image/jpeg', name: `${m.mediaId}.jpg` }))
    vi.mocked(restoreMedia).mockImplementation(async (_s, id) => {
      if (id === 'a') throw refusal(451, 'blocked_by_operator')
    })
    const { vm } = setup()
    await vm.loadTrash()
    await flushPromises()
    vm.toggleSelectAllTrash()
    await vm.restoreSelected()
    await flushPromises()

    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not restored: a.jpg. Blocked by the operator: it can be deleted, not restored.',
    ])
    expect(restoreMedia).toHaveBeenCalledTimes(2) // the refusal did not stop b
  })

  it("the menu's Restore names its refused restore, with no unhandled rejection", async () => {
    const unhandled = vi.fn()
    process.on('unhandledRejection', unhandled)
    try {
      vi.mocked(listTrash).mockResolvedValue([item('a'), item('b')])
      vi.mocked(restoreMedia).mockRejectedValue(refusal(404, 'media_not_found'))
      const { vm, reloadGallery } = setup()
      await vm.loadTrash()
      await flushPromises()
      restoreFromTileMenu(vm, 'b')
      await flushPromises()
      await new Promise((r) => setTimeout(r, 0)) // let Node notice a rejection nobody handled

      expect(restoreMedia).toHaveBeenCalledWith('scope-1', 'b')
      expect(toastError.mock.calls.map(([line]) => line)).toEqual([
        'Not restored: Item 2. Already gone from this Memory, or you no longer have access.',
      ])
      expect(reloadGallery).toHaveBeenCalledTimes(1)
      expect(listTrash).toHaveBeenCalledTimes(2) // the tab re-lists after the refusal
      expect(unhandled).not.toHaveBeenCalled()
    } finally {
      process.off('unhandledRejection', unhandled)
    }
  })
})
