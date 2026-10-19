// @vitest-environment happy-dom
// ADR 0105 decision 3 (inbox #489): a listed seal is opened ONCE per seal and kept — a sort, a mark filter, a re-list
// and a re-render read the opened value; only a seal that changed is opened again. Counted where the page opens a seal
// (`unsealMeta`, `originalBlobMeta`: no Worker runs in this env, so every open is the page's and every one is seen).
// Bite: make `openedMeta` skip the memo (always open) and the first case goes red on its counts.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listMediaPages: vi.fn(),
  openGrid: vi.fn(),
  originalBlobMeta: vi.fn(),
  hasDerivatives: vi.fn(),
  unsealMeta: vi.fn(),
}))
vi.mock('./mediaRevisionPolling', () => ({ startMediaRevisionPolling: vi.fn(), stopMediaRevisionPolling: vi.fn() }))
vi.mock('./useOptimisticTiles', async () => {
  const { computed } = await import('vue')
  return { useOptimisticTiles: () => ({ optimisticTiles: computed(() => []), donorPreviewFor: () => null }) }
})
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }),
  UNDO_GROUP: 'undo',
}))

import { defineComponent, h, ref, type UnwrapRef } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useMediaGalleryLive } from './useMediaGalleryLive'
import { memberMemorySource } from './source'
import { listMediaPages, openGrid, originalBlobMeta, hasDerivatives, unsealMeta, type MediaItem } from './media'
import { closeListing, openListing, openedMeta, openedBlobMeta } from './openedListing'
import { realI18n } from '../../test/i18n'
import { mediaItem, treeNode } from '../../test/models'

const KEY = new Uint8Array(32)
const taken: Record<string, number> = { a: 3, b: 1, c: 2 }

beforeEach(() => {
  vi.clearAllMocks()
  closeListing('scope-1', KEY)
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:x')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.mocked(openGrid).mockImplementation(async () => new Uint8Array([1]))
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(unsealMeta).mockImplementation((_s, _k, m) => ({ v: 1, w: 120, h: 80, capturedAt: taken[m.mediaId] }))
  vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => ({ v: 1, type: 'image/jpeg', name: `${m.mediaId}.jpg` }))
})

const item = (id: string, wrappedMeta = `seal-${id}`): MediaItem => mediaItem({ mediaId: id, wrappedMeta })
const page = (items: MediaItem[]) => ({ notModified: false, items, mediaRevision: 1, deleteRevision: 0, mediaIds: null, cursor: null }) as const

/** How often the page opened each media's listed tier / blob meta. */
const opens = (fn: typeof unsealMeta | typeof originalBlobMeta) => {
  const by: Record<string, number> = {}
  for (const call of vi.mocked(fn).mock.calls) by[call[2].mediaId] = (by[call[2].mediaId] ?? 0) + 1
  return by
}

function setup() {
  const filter = ref<((id: string) => boolean) | null>(null)
  const wrapper = mount(
    defineComponent({
      setup(_, { expose }) {
        expose(useMediaGalleryLive({
          source: memberMemorySource({ scopeId: () => 'scope-1' }),
          scopeId: () => 'scope-1',
          node: () => treeNode(),
          memoryKey: () => KEY,
          onSettle: async () => {},
          markFilter: () => filter.value,
        }))
        return () => h('div')
      },
    }),
    { global: { plugins: [realI18n()] } },
  )
  return { vm: wrapper.vm as unknown as UnwrapRef<ReturnType<typeof useMediaGalleryLive>>, filter, wrapper }
}

describe('the opened listing (ADR 0105 decision 3)', () => {
  it('opens each seal once across a sort, a mark filter, a re-list and repeated layouts; an edited seal alone again', async () => {
    vi.mocked(listMediaPages).mockResolvedValue(page([item('a'), item('b'), item('c')]))
    const { vm, filter } = setup()
    await flushPromises()
    expect(vm.layoutItems.map((t) => t.id)).toEqual(['a', 'c', 'b']) // newest first: the order needed every seal

    vm.sortDir = 'oldest'
    await flushPromises()
    expect(vm.layoutItems.map((t) => t.id)).toEqual(['b', 'c', 'a'])
    filter.value = (id) => id !== 'c'
    await flushPromises()
    expect(vm.layoutItems.map((t) => t.id)).toEqual(['b', 'a'])
    filter.value = null
    await vm.load() // a re-list of the same seals (an upload batch's settle)
    for (let i = 0; i < 3; i++) void vm.layoutItems.map((t) => t.aspectRatio)

    expect(opens(unsealMeta)).toEqual({ a: 1, b: 1, c: 1 })
    expect(opens(originalBlobMeta)).toEqual({ a: 1, b: 1, c: 1 })

    // a capture-time correction re-seals b's tier: b is opened anew, nobody else
    vm.onCapturedAtUpdated({ mediaId: 'b', wrappedMeta: 'seal-b-2', metaRevision: 2 })
    await flushPromises()
    void vm.layoutItems
    expect(opens(unsealMeta)).toEqual({ a: 1, b: 2, c: 1 })
  })

  it('a value asked for before its batch arrived is opened on the page once, and the batch does not open it again', async () => {
    const a = item('a')
    expect(openedMeta('scope-1', KEY, a)?.w).toBe(120)
    await openListing('scope-1', KEY, [a, item('b')])
    expect(openedMeta('scope-1', KEY, a)?.w).toBe(120)
    expect(openedBlobMeta('scope-1', KEY, a)?.name).toBe('a.jpg')
    expect(opens(unsealMeta)).toEqual({ a: 1, b: 1 })
  })

  it('two opens of the same media at once open it once', async () => {
    await Promise.all([openListing('scope-1', KEY, [item('a'), item('b')]), openListing('scope-1', KEY, [item('b'), item('c')])])
    expect(opens(unsealMeta)).toEqual({ a: 1, b: 1, c: 1 })
  })

  it('a closed listing keeps nothing: the next read opens again', async () => {
    await openListing('scope-1', KEY, [item('a')])
    closeListing('scope-1', KEY)
    openedMeta('scope-1', KEY, item('a'))
    expect(opens(unsealMeta)).toEqual({ a: 2 })
  })
})
