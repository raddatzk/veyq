// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

// The buckets data layer is mocked — this test drives the component's selection / delete-to-trash+undo / move-gating
// UX (cr-0122), not the crypto/network layer. Names come back via a stubbed openBucketName.
vi.mock('../media', () => ({
  listBuckets: vi.fn(),
  createBucket: vi.fn(),
  renameBucket: vi.fn(),
  deleteBucket: vi.fn(),
  moveBucketMedia: vi.fn(async () => {}),
  deleteMedia: vi.fn(async () => {}),
  restoreMedia: vi.fn(async () => {}),
  openBucketName: vi.fn(() => 'Aaa Contributor'),
  unsealMeta: vi.fn(() => ({ w: 100, h: 100 })),
  isVideoMediaType: vi.fn(() => false),
  hasDerivatives: vi.fn(() => true),
}))
vi.mock('../../spaces/members', () => ({ listScopeMembers: vi.fn(async () => []) }))

// The undo affordance is now a global toast (cr-0157): stub the shared composable so the test can capture the
// `showUndo({ onUndo })` call and invoke the restore action (the toast surface itself lives in App.vue).
const { showUndo } = vi.hoisted(() => ({ showUndo: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), showUndo, dismissUndo: vi.fn() }),
  UNDO_GROUP: 'undo',
}))

import MemoryBuckets from './MemoryBuckets.vue'
import { listBuckets, deleteMedia, restoreMedia, type MediaItem, type BucketItem } from '../media'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { save: 'Save', cancel: 'Cancel' },
      media: { loading: 'Loading…', unavailable: 'Unavailable', openItem: 'Open item {index}' },
      memory: {
        buckets: {
          title: 'Contributions', contributor: 'Contribution', member: 'Member', you: 'You',
          emptyBucket: 'Nothing here.', options: 'Options', upload: 'Upload', createBucket: 'New sub-bucket',
          rename: 'Rename', delete: 'Delete', move: 'Move', moveMedia: 'Move item', moveTitle: 'Move selection',
          deleteMediaAction: 'Move item to trash', selectAllVisible: 'Select all', deselectAll: 'Deselect all',
          selected: '{selected} of {visible} selected', undo: 'Undo',
          deletedToTrash: '{count} item moved to trash | {count} items moved to trash',
          dropTitle: 'Upload into “{bucket}”', dropCopy: 'Drop here',
        },
      },
    },
  },
})

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

const media = (id: string, ownerKey: string, bucketId: string): MediaItem =>
  ({ mediaId: id, ownerKey, bucketId, original: {}, derivatives: [] }) as unknown as MediaItem
const bucket = (bucketId: string, ownerKey: string, mediaCount: number, sealedName?: string, isPrimary = true, parentBucketId?: string): BucketItem =>
  ({ bucketId, ownerKey, isPrimary, mediaCount, sealedName, parentBucketId }) as BucketItem

function mountBuckets(mediaItems: MediaItem[]) {
  return mount(MemoryBuckets, {
    props: { scopeId: 's-1', nodeId: 'n-1', memoryKey: new Uint8Array(32), media: mediaItems, thumbs: new Map() },
    global: { plugins: [i18n], stubs: { Select: SelectStub, Menu: MenuStub, MediaGrid: MediaGridStub } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear() // the picker remembers the last bucket per node — reset between tests
})

const selectBucket = (w: Awaited<ReturnType<typeof mountBuckets>>, id: string) =>
  w.find(`.bucket-opt[data-val="${id}"]`).trigger('click')

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
