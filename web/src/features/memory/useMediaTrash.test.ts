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
}))

import { defineComponent, h, type UnwrapRef } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useMediaTrash } from './useMediaTrash'
import { listTrash, restoreMedia, purgeMedia, openGrid, hasDerivatives, unsealMeta, type MediaItem } from './media'
import { realI18n, t } from '../../test/i18n'
import { mediaItem, treeNode } from '../../test/models'

const KEY = new Uint8Array(32)
const item = (id: string): MediaItem => mediaItem({ mediaId: id })

/** Mount a harness that exposes the composable's return + the two host callbacks as spies. */
function setup() {
  const reloadGallery = vi.fn()
  const liftTombstone = vi.fn()
  const wrapper = mount(
    defineComponent({
      setup(_, { expose }) {
        const api = useMediaTrash({
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
  vi.mocked(restoreMedia).mockResolvedValue(undefined)
  vi.mocked(purgeMedia).mockResolvedValue(undefined)
  vi.mocked(openGrid).mockImplementation(async () => new Uint8Array([1, 2, 3]))
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, w: 100, h: 100 })
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
