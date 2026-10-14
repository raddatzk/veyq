// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// The buckets data layer is mocked — this test drives the component's selection / delete-to-trash+undo / move-gating
// UX (cr-0122), not the crypto/network layer. Names come back via a stubbed openBucketName.
vi.mock('../media', () => ({
  listBuckets: vi.fn(),
  createBucket: vi.fn(),
  renameBucket: vi.fn(),
  deleteBucket: vi.fn(),
  moveBucketMedia: vi.fn(),
  deleteMedia: vi.fn(),
  restoreMedia: vi.fn(),
  openBucketName: vi.fn(),
  unsealMeta: vi.fn(),
  originalMediaType: vi.fn(),
  originalBlobMeta: vi.fn(),
  isVideoMediaType: vi.fn(),
  hasDerivatives: vi.fn(),
}))
vi.mock('../../spaces/members', () => ({ listScopeMembers: vi.fn() }))
// The member's epoch keys, for the chain to a guest's name sealed above this node (5b.4b); none unless a test sets them.
const space = vi.hoisted(() => ({ epochs: null as null | import('../../spaces/epochs').EpochKeys }))
vi.mock('../../../app/activeSpace', () => ({ activeEpochKeys: () => space.epochs }))

// The undo affordance is now a global toast (cr-0157): stub the shared composable so the test can capture the
// `showUndo({ onUndo })` call and invoke the restore action (the toast surface itself lives in App.vue).
const { showUndo, toastError } = vi.hoisted(() => ({ showUndo: vi.fn(), toastError: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo, dismissUndo: vi.fn() }),
  UNDO_GROUP: 'undo',
}))

import MemoryBuckets from './MemoryBuckets.vue'
import {
  listBuckets,
  deleteMedia,
  restoreMedia,
  moveBucketMedia,
  openBucketName,
  unsealMeta,
  originalBlobMeta,
  isVideoMediaType,
  hasDerivatives,
  type MediaItem,
  type BucketItem,
} from '../media'
import { listScopeMembers } from '../../spaces/members'
import { treeStore } from '../../../core/node/treeStore'
import type { TreeNode } from '../../../core/node/tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n } from '../../../test/i18n'
import { mediaItem, treeNode, memberMemoryCaps } from '../../../test/models'
import { computed } from 'vue'
import { MEMORY_VIEW, memberMemorySource } from '../source'

// Stub the PrimeVue Select + Menu (not under test) and MediaGrid (renders the tile slot per item so the tile chrome
// — checkbox + 3-dot — is exercised).
const SelectStub = {
  props: ['modelValue', 'options'],
  emits: ['update:modelValue'],
  template:
    '<div class="select-stub"><slot name="value" /><button v-for="o in options" :key="o.value" class="bucket-opt" :data-val="o.value" type="button" @click="$emit(\'update:modelValue\', o.value)" /></div>',
}
const MenuStub = { template: '<div class="menu-stub" />' }
const MediaGridStub = {
  props: ['items'],
  template:
    '<div class="grid-stub"><template v-for="it in items" :key="it.id"><slot name="tile" :tile="{ left: 0, top: 0, renderedWidth: 100, renderedHeight: 100 }" :item="it" /></template></div>',
}

const media = (id: string, ownerKey: string, bucketId: string): MediaItem => mediaItem({ mediaId: id, ownerKey, bucketId })
const bucket = (bucketId: string, ownerKey: string, mediaCount: number, sealedName?: string, isPrimary = true, parentBucketId?: string): BucketItem =>
  ({ bucketId, ownerKey, isPrimary, mediaCount, sealedName, parentBucketId }) as BucketItem

/** The member's Memory view the organizer reads its doors from (`useMemoryView`), as `MemoryDetail` provides it. */
const memberView = (scopeId: string) => ({
  [MEMORY_VIEW as symbol]: { source: memberMemorySource({ scopeId: () => scopeId }), caps: computed(() => memberMemoryCaps()) },
})

function mountBuckets(mediaItems: MediaItem[]) {
  return mount(MemoryBuckets, {
    props: { scopeId: 's-1', nodeId: 'n-1', memoryKey: new Uint8Array(32), media: mediaItems, thumbs: new Map() },
    global: { plugins: [realI18n()], stubs: { Select: SelectStub, Menu: MenuStub, MediaGrid: MediaGridStub }, provide: memberView('s-1') },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear() // the picker remembers the last bucket per node — reset between tests
  vi.mocked(moveBucketMedia).mockResolvedValue(undefined)
  vi.mocked(deleteMedia).mockResolvedValue(undefined)
  vi.mocked(restoreMedia).mockResolvedValue(undefined)
  vi.mocked(openBucketName).mockReturnValue('Aaa Contributor')
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, w: 100, h: 100 })
  vi.mocked(isVideoMediaType).mockReturnValue(false)
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(originalBlobMeta).mockReturnValue(null)
  vi.mocked(listScopeMembers).mockResolvedValue([])
})

const selectBucket = (w: Awaited<ReturnType<typeof mountBuckets>>, id: string) =>
  w.find(`.bucket-opt[data-val="${id}"]`).trigger('click')

describe('MemoryBuckets — a guest named above this node (cr-node-key-hierarchy 5b.4b)', () => {
  it("opens a contributor's name with the keys of the node's ancestors, from the loaded tree", async () => {
    const SCOPE = '5c09e000-0000-4000-8000-0000000005c0'
    const FOLDER = '5c09e000-0000-4000-8000-0000000000f1'
    const MEMORY = '5c09e000-0000-4000-8000-0000000000e1'
    const scopeKey = v1.randomBytes(32)
    const folderKey = v1.randomBytes(32)
    const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array) =>
      b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE, nodeId, epoch: 0, placementVersion: 0 }, kek, k))
    const node = (id: string, parentId: string | null, wrappedContentKey: string): TreeNode =>
      treeNode({ id, parentId, kind: 'FOLDER', orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaVersion: 0, placementVersion: 0 })
    const memoryKey = v1.randomBytes(32)
    space.epochs = epochsOf(scopeKey)
    treeStore.nodes = [node(FOLDER, null, wrap(scopeKey, FOLDER, folderKey)), node(MEMORY, FOLDER, wrap(folderKey, MEMORY, memoryKey))]
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-share', 'share:x', 1, 'ct')])
    try {
      mount(MemoryBuckets, {
        props: { scopeId: SCOPE, nodeId: MEMORY, memoryKey, media: [media('m3', 'share:x', 'b-share')], thumbs: new Map() },
        global: { plugins: [realI18n()], stubs: { Select: SelectStub, Menu: MenuStub, MediaGrid: MediaGridStub }, provide: memberView(SCOPE) },
      })
      await flushPromises()
      const [, , , row, above] = vi.mocked(openBucketName).mock.calls[0]
      expect(row.bucketId).toBe('b-share')
      expect(above?.map((k) => Array.from(k)), "the folder's key, above the Memory").toEqual([Array.from(folderKey)])
    } finally {
      space.epochs = null
      treeStore.nodes = []
    }
  })
})

/** A generated-client refusal: the `ResponseError` shape `decodeHttpError` reads (`.response` with a JSON `code`). */
const refusal = (status: number, code: string) =>
  Object.assign(new Error(code), { response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

describe('MemoryBuckets — a selection whose delete is partly refused (inbox #427)', () => {
  async function deleteAllOf(ids: string[]) {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', ids.length)])
    const wrapper = mountBuckets(ids.map((id) => media(id, 'user:me', 'b-own')))
    await flushPromises()
    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
    await wrapper.find('.bucket-selection-bar .bucket-action-danger').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('names the refused one with its reason, counts the two that went, and Undo restores those two only', async () => {
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, item) => ({ v: 1, type: 'image/jpeg', name: `${item.mediaId}.jpg` }))
    vi.mocked(deleteMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm2') throw refusal(409, 'scope_deleting')
    })
    await deleteAllOf(['m1', 'm2', 'm3'])

    expect(deleteMedia).toHaveBeenCalledTimes(3) // the refusal did not stop the rest
    expect(toastError).toHaveBeenCalledTimes(1)
    expect(toastError.mock.calls[0][0]).toBe('Not moved to trash: m2.jpg. This space is being deleted.')
    expect(showUndo).toHaveBeenCalledTimes(1)
    const undoArg = showUndo.mock.calls[0][0]
    expect(undoArg.summary).toBe('2 items moved to trash')

    undoArg.onUndo()
    await flushPromises()
    expect(vi.mocked(restoreMedia).mock.calls.map(([, id]) => id).sort()).toEqual(['m1', 'm3']) // never the refused m2
  })

  it('names a medium without a sealed file name by its place in the grid, one line per reason', async () => {
    vi.mocked(deleteMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm1') throw refusal(404, 'media_not_found')
      if (id === 'm3') throw new TypeError('Failed to fetch') // no response: a network failure
    })
    await deleteAllOf(['m1', 'm2', 'm3'])

    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not moved to trash: Item 1. Already gone from this Memory, or you no longer have access.',
      'Not moved to trash: Item 3. Please try again.',
    ])
    expect(showUndo.mock.calls[0][0].summary).toBe('1 item moved to trash')
  })

  it('offers no Undo when nothing went', async () => {
    vi.mocked(deleteMedia).mockRejectedValue(refusal(409, 'scope_deleting'))
    await deleteAllOf(['m1', 'm2'])

    expect(showUndo).not.toHaveBeenCalled()
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['Not moved to trash: Item 1, Item 2. This space is being deleted.'])
  })
})

describe('MemoryBuckets — selection + delete-to-trash + undo (cr-0122)', () => {
  it('own bucket: select-all selects every tile, then delete soft-deletes each and offers undo → restore', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 2)])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), media('m2', 'user:me', 'b-own')])
    await flushPromises()

    expect(wrapper.findAll('.media-tile-check').length).toBe(2) // selection UI present
    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
    expect(wrapper.findAll('.media-tile.is-selected').length).toBe(2)

    const del = wrapper.find('.bucket-selection-bar .bucket-action-danger')
    expect(del.attributes('disabled')).toBeUndefined() // enabled with a selection
    await del.trigger('click')
    await flushPromises()

    expect(deleteMedia).toHaveBeenCalledTimes(2) // straight to trash, no confirm dialog
    expect(showUndo).toHaveBeenCalledTimes(1) // offers an undo-toast instead of an inline bar
    const undoArg = showUndo.mock.calls[0][0]
    expect(undoArg.summary).toContain('2 items moved to trash')

    undoArg.onUndo() // the toast's "Rückgängig" action
    await flushPromises()
    expect(restoreMedia).toHaveBeenCalledTimes(2) // undo restores each id
    expect(toastError).not.toHaveBeenCalled() // nothing refused, nothing named
  })

  it('contributor bucket: selection shown; Move disabled with no same-owner sibling, Delete still works', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 0), bucket('b-share', 'share:x', 1, 'ct')])
    const wrapper = mountBuckets([media('m3', 'share:x', 'b-share')])
    await flushPromises()
    await selectBucket(wrapper, 'b-share') // the picker defaults to your own bucket — switch into the contributor's

    expect(wrapper.find('.select-stub').text()).toContain('Aaa Contributor') // the contributor bucket is active
    expect(wrapper.findAll('.media-tile-check').length).toBe(1) // selection ungated for a contributor bucket

    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // select all → 1 selected
    const move = wrapper.find('.bucket-selection-bar .bucket-action-button:not(.bucket-action-danger)')
    expect(move.attributes('disabled')).toBeDefined() // no same-owner sibling to move into
    const del = wrapper.find('.bucket-selection-bar .bucket-action-danger')
    expect(del.attributes('disabled')).toBeUndefined() // delete still works (any member soft-deletes)
  })

  it('contributor root: Move enables to a same-owner sibling sub-bucket (cr-0123)', async () => {
    // A contributor primary + a sub under it (same owner) → the sub is a valid same-owner move target.
    vi.mocked(listBuckets).mockResolvedValue([
      bucket('b-own', 'user:me', 0),
      bucket('b-share', 'share:x', 1, 'ct'),
      bucket('s-share', 'share:x', 0, 'sub', false, 'b-share'),
    ])
    const wrapper = mountBuckets([media('m3', 'share:x', 'b-share')])
    await flushPromises()
    await selectBucket(wrapper, 'b-share') // switch into the contributor root (own is the default)

    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // select all → 1 selected
    const move = wrapper.find('.bucket-selection-bar .bucket-action-button:not(.bucket-action-danger)')
    expect(move.attributes('disabled')).toBeUndefined() // a same-owner sibling exists → Move is available
  })

  it('opens with YOUR OWN bucket preselected — not the alphabetically-first contributor (cr-0122 f/u)', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 2), bucket('b-share', 'share:x', 1, 'ct')])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), media('m2', 'user:me', 'b-own'), media('m3', 'share:x', 'b-share')])
    await flushPromises()
    // 'Aaa Contributor' sorts first, but the picker defaults to your own bucket → its 2 media, not the contributor's 1.
    expect(wrapper.findAll('.media-tile-check').length).toBe(2)
  })

  it('remembers the last chosen bucket per node (cr-0122 f/u)', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 0), bucket('b-share', 'share:x', 1, 'ct')])
    const w1 = mountBuckets([media('m3', 'share:x', 'b-share')])
    await flushPromises()
    await selectBucket(w1, 'b-share') // choose the contributor bucket
    w1.unmount()

    const w2 = mountBuckets([media('m3', 'share:x', 'b-share')]) // re-enter the same node → restores the last pick
    await flushPromises()
    expect(w2.find('.select-stub').text()).toContain('Aaa Contributor')
  })
})
