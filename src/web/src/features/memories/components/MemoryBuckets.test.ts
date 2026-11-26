// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// The buckets data layer is mocked — this test drives the component's selection / delete-to-trash+undo / move-gating
// UX (cr-0122), not the crypto/network layer. Names come back via a stubbed openBucketName.
// Every authenticated client throws and is recorded (`test/guestMode.ts`): the guest's mount below asserts none was called.
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
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
  mediaTypeOf: vi.fn(),
  originalBlobMeta: vi.fn(),
  isVideoMediaType: vi.fn(),
  hasDerivatives: vi.fn(),
}))
// The scope's people as core's directory names them (ADR 0083 decision 9) — its own suite proves the rule; here, who shows.
vi.mock('../../../core/people/directory', async (orig) => ({
  ...(await orig<typeof import('../../../core/people/directory')>()), loadScopePeople: vi.fn(),
}))
// The member's epoch keys, for the chain to a guest's name sealed above this node; none unless a test sets them.
const space = vi.hoisted(() => ({ epochs: null as null | import('../../spaces/epochs').ScopeKey }))
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
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { treeStore } from '../../../core/node/treeStore'
import type { TreeNode } from '../../../core/node/tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n, t } from '../../../test/i18n'
import { mediaItem, treeNode, memberMemoryRights, guestAccessFor } from '../../../test/models'
import { computed } from 'vue'
import { MEMORY_VIEW, memberMemorySource, guestMemorySource } from '../source'
import { ShareRole } from '../../../api/generated/sharing'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { authenticatedCalls } from '../../../test/guestMode'
import { clearAccount, setAccountUserId } from '../../../app/account'

// Stub the layer's Select + Menu (not under test) and MediaGrid (renders the tile slot per item so the tile chrome
// — checkbox + 3-dot — is exercised).
const SelectStub = {
  props: ['modelValue', 'options'],
  emits: ['update:modelValue'],
  template:
    '<div class="select-stub"><slot name="value" /><button v-for="o in options" :key="o.value" class="bucket-opt" :data-val="o.value" type="button" @click="$emit(\'update:modelValue\', o.value)" /></div>',
}
// A menu's entries drawn as buttons, so a test reads and picks them without the layer's popup.
const MenuStub = {
  props: ['items'],
  methods: { toggle() {} },
  template:
    '<div class="menu-stub"><button v-for="i in items" :key="i.label" type="button" class="menu-entry" :disabled="i.disabled" @click="i.command && i.command()">{{ i.label }}</button></div>',
}
const MediaGridStub = {
  props: ['items'],
  template:
    '<div class="grid-stub"><template v-for="it in items" :key="it.id"><slot name="tile" :tile="{ left: 0, top: 0, renderedWidth: 100, renderedHeight: 100 }" :item="it" /></template></div>',
}

const media = (id: string, ownerKey: string, bucketId: string): MediaItem => mediaItem({ mediaId: id, ownerKey, bucketId })
/** A member as core's directory hands them: the display name is the real name when one opened, else the username. */
const person = (userId: string, username: string, realName: string | null = null): Person =>
  ({ userId, username, realName, displayName: realName ?? username })
const bucket = (bucketId: string, ownerKey: string, mediaCount: number, sealedName?: string, isPrimary = true, parentBucketId?: string): BucketItem =>
  ({ bucketId, ownerKey, isPrimary, mediaCount, sealedName, parentBucketId }) as BucketItem

/** The member's Memory view the organizer reads its doors from (`useMemoryView`), as `MemoryDetail` provides it. */
const memberView = (scopeId: string) => ({
  [MEMORY_VIEW as symbol]: { source: memberMemorySource({ scopeId: () => scopeId }), rights: computed(() => memberMemoryRights()) },
})

function mountBuckets(mediaItems: MediaItem[], provide: Record<symbol, unknown> = memberView('s-1')) {
  return mount(MemoryBuckets, {
    props: { scopeId: 's-1', nodeId: 'n-1', nodeEpoch: 0, memoryKey: new Uint8Array(32), media: mediaItems, thumbs: new Map() },
    global: { plugins: [realI18n()], stubs: { VeyqSelect: SelectStub, VeyqMenu: MenuStub, MediaGrid: MediaGridStub }, provide },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear() // the picker remembers the last bucket per node — reset between tests
  setAccountUserId('me') // the caller: `user:me` is their own root (the buckets door's `self()`, inbox #1003)
  vi.mocked(moveBucketMedia).mockResolvedValue(undefined)
  vi.mocked(deleteMedia).mockResolvedValue(undefined)
  vi.mocked(restoreMedia).mockResolvedValue(undefined)
  vi.mocked(openBucketName).mockReturnValue('Aaa Contributor')
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, w: 100, h: 100 })
  vi.mocked(isVideoMediaType).mockReturnValue(false)
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(originalBlobMeta).mockReturnValue(null)
  vi.mocked(loadScopePeople).mockResolvedValue([])
})

const selectBucket = (w: Awaited<ReturnType<typeof mountBuckets>>, id: string) =>
  w.find(`.bucket-opt[data-val="${id}"]`).trigger('click')

describe('MemoryBuckets — a guest named above this node (uc.memory.buckets:guest-named-above-the-node)', () => {
  it("opens a contributor's name with the keys of the node's ancestors, from the loaded tree", async () => {
    const SCOPE = '5c09e000-0000-4000-8000-0000000005c0'
    const FOLDER = '5c09e000-0000-4000-8000-0000000000f1'
    const MEMORY = '5c09e000-0000-4000-8000-0000000000e1'
    const scopeKey = v1.randomBytes(32)
    const folderKey = v1.randomBytes(32)
    const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array) =>
      b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE, nodeId, epoch: 0, placementRevision: 0 }, kek, k))
    const node = (id: string, parentId: string | null, wrappedContentKey: string): TreeNode =>
      treeNode({ id, parentId, kind: 'FOLDER', orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
    const memoryKey = v1.randomBytes(32)
    space.epochs = epochsOf(scopeKey)
    treeStore.nodes = [node(FOLDER, null, wrap(scopeKey, FOLDER, folderKey)), node(MEMORY, FOLDER, wrap(folderKey, MEMORY, memoryKey))]
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-share', 'share:x', 1, 'ct')])
    try {
      mount(MemoryBuckets, {
        props: { scopeId: SCOPE, nodeId: MEMORY, nodeEpoch: 0, memoryKey, media: [media('m3', 'share:x', 'b-share')], thumbs: new Map() },
        global: { plugins: [realI18n()], stubs: { VeyqSelect: SelectStub, VeyqMenu: MenuStub, MediaGrid: MediaGridStub }, provide: memberView(SCOPE) },
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

describe('MemoryBuckets — an Undo whose restore is partly refused (inbox #429)', () => {
  /** Trash all of `ids` (every delete goes), then hit the undo toast's Undo. */
  async function deleteAndUndo(ids: string[]) {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', ids.length)])
    const wrapper = mountBuckets(ids.map((id) => media(id, 'user:me', 'b-own')))
    await flushPromises()
    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
    await wrapper.find('.bucket-selection-bar .bucket-action-danger').trigger('click')
    await flushPromises()
    expect(toastError).not.toHaveBeenCalled() // every delete went
    showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
  }
  const restoredOk = async () =>
    (await Promise.allSettled(vi.mocked(restoreMedia).mock.results.map((r) => r.value))).filter((r) => r.status === 'fulfilled').length

  it('names the one restore of three that was refused, with its reason; the two others are restored', async () => {
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, item) => ({ v: 1, type: 'image/jpeg', name: `${item.mediaId}.jpg` }))
    vi.mocked(restoreMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm2') throw refusal(409, 'scope_deleting')
    })
    await deleteAndUndo(['m1', 'm2', 'm3'])

    expect(restoreMedia).toHaveBeenCalledTimes(3) // the refusal did not stop the rest
    expect(await restoredOk()).toBe(2)
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['Not restored: m2.jpg. This space is being deleted.'])
  })

  it('names a refused restore without a sealed file name by the place it had in the grid, one line per reason', async () => {
    vi.mocked(restoreMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm1') throw refusal(404, 'media_not_found') // purged meanwhile
      if (id === 'm3') throw new TypeError('Failed to fetch')
    })
    await deleteAndUndo(['m1', 'm2', 'm3'])

    expect(await restoredOk()).toBe(1)
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not restored: Item 1. Already gone from this Memory, or you no longer have access.',
      'Not restored: Item 3. Please try again.',
    ])
  })

  it('after a partly refused delete, names the refused restore by its own name, not its neighbour\'s', async () => {
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, item) => ({ v: 1, type: 'image/jpeg', name: `${item.mediaId}.jpg` }))
    vi.mocked(deleteMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm2') throw refusal(409, 'scope_deleting')
    })
    vi.mocked(restoreMedia).mockImplementation(async (_scope, id) => {
      if (id === 'm3') throw refusal(404, 'media_not_found')
    })
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 3)])
    const wrapper = mountBuckets(['m1', 'm2', 'm3'].map((id) => media(id, 'user:me', 'b-own')))
    await flushPromises()
    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
    await wrapper.find('.bucket-selection-bar .bucket-action-danger').trigger('click')
    await flushPromises()
    toastError.mockClear() // the delete's own refusal (m2) is #427's
    showUndo.mock.calls[0][0].onUndo()
    await flushPromises()

    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'Not restored: m3.jpg. Already gone from this Memory, or you no longer have access.',
    ])
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

    const del = wrapper.find('.bucket-selection-bar .bucket-delete-button')
    expect(del.attributes('aria-label')).toBe(t('memory.buckets.deleteSelection')) // an icon: its words the name (and tooltip)
    expect(del.attributes('aria-disabled')).toBeUndefined() // enabled with a selection
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
    const move = wrapper.find('.bucket-selection-bar .bucket-move-button')
    expect(move.attributes('aria-disabled')).toBe('true') // no same-owner sibling to move into
    await move.trigger('click') // aria-disabled, not disabled (its tooltip shows): the click itself must do nothing
    expect(wrapper.findAllComponents({ name: 'VeyqDialog' }).some((d) => d.props('visible')), 'no move dialog').toBe(false)
    const del = wrapper.find('.bucket-selection-bar .bucket-delete-button')
    expect(del.attributes('aria-disabled')).toBeUndefined() // delete still works (any member soft-deletes)
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
    const move = wrapper.find('.bucket-selection-bar .bucket-move-button')
    expect(move.attributes('aria-disabled')).toBeUndefined() // a same-owner sibling exists → Move is available
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

describe("MemoryBuckets — another member's primary is never the caller's (inbox #1003, core's ContributionsTest)", () => {
  const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dialog-stub"><slot /></div>' }
  function mountWithDialogs(mediaItems: MediaItem[]) {
    return mount(MemoryBuckets, {
      props: { scopeId: 's-1', nodeId: 'n-1', nodeEpoch: 0, memoryKey: new Uint8Array(32), media: mediaItems, thumbs: new Map() },
      global: {
        plugins: [realI18n()],
        stubs: { VeyqSelect: SelectStub, VeyqMenu: MenuStub, MediaGrid: MediaGridStub, VeyqDialog: DialogStub },
        provide: memberView('s-1'),
      },
    })
  }
  const pickerIds = (w: ReturnType<typeof mountWithDialogs>) =>
    w.findAll('.bucket-picker .bucket-opt').map((o) => o.attributes('data-val'))
  const activeName = (w: ReturnType<typeof mountWithDialogs>) => w.find('.bucket-select-value > span:not(.veyq-icon)').text()
  const activeCount = (w: ReturnType<typeof mountWithDialogs>) => w.find('.bucket-select-value small').text()

  it("shows the virtual \"You\" beside another member's materialized primary, opens on it, and counts only the caller's unbucketed media", async () => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer'), person('me', 'me', 'Zoe Self')])
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-anna', 'user:u-anna', 1)])
    const wrapper = mountWithDialogs([
      media('m-anna', 'user:u-anna', 'b-anna'),
      mediaItem({ mediaId: 'm-anna-loose', ownerKey: 'user:u-anna' }), // Anna's, in no bucket: never "You"'s
      mediaItem({ mediaId: 'm-mine', ownerKey: 'user:me' }),
    ])
    await flushPromises()

    expect(pickerIds(wrapper)).toHaveLength(2) // "You" and Anna's — Anna's primary did not replace the caller's
    expect(activeName(wrapper)).toBe(t('memory.buckets.you'))
    expect(activeCount(wrapper)).toBe('1')
    expect(wrapper.findAll('.media-tile-check')).toHaveLength(1) // m-mine only

    await selectBucket(wrapper, 'b-anna')
    expect(activeName(wrapper)).toBe('Anna Meyer') // Anna's bucket is Anna's
  })

  it("with the caller's own primary materialized, no virtual \"You\" — and the picker opens on it, not on another member's that sorts first", async () => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer'), person('me', 'me', 'Zoe Self')])
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-anna', 'user:u-anna', 1), bucket('b-me', 'user:me', 2)])
    const wrapper = mountWithDialogs([
      media('m-anna', 'user:u-anna', 'b-anna'), media('m1', 'user:me', 'b-me'), media('m2', 'user:me', 'b-me'),
    ])
    await flushPromises()

    expect(pickerIds(wrapper)).toEqual(['b-anna', 'b-me']) // Anna sorts first; no virtual row
    expect(activeName(wrapper)).toBe('Zoe Self')
    expect(wrapper.findAll('.media-tile-check')).toHaveLength(2)
  })

  it("offers a sub-bucket under any primary, a rename and delete on a sub-bucket only, and a move within the chosen root only", async () => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer'), person('me', 'me', 'Zoe Self')])
    vi.mocked(openBucketName).mockImplementation((_s, _n, _k, b) => `Sub ${b.bucketId}`)
    vi.mocked(listBuckets).mockResolvedValue([
      bucket('b-anna', 'user:u-anna', 1),
      bucket('s-anna', 'user:u-anna', 1, 'ct', false, 'b-anna'),
      bucket('s-anna-2', 'user:u-anna', 0, 'ct', false, 'b-anna'),
      bucket('b-me', 'user:me', 0),
      bucket('s-me', 'user:me', 0, 'ct', false, 'b-me'),
    ])
    const wrapper = mountWithDialogs([media('m-anna', 'user:u-anna', 'b-anna'), media('m-sub', 'user:u-anna', 's-anna')])
    await flushPromises()
    const offered = (label: string) => wrapper.find(`.bucket-picker .bucket-action-button[aria-label="${t(label)}"]`).exists()

    await selectBucket(wrapper, 'b-anna') // another member's primary: a sub-bucket under it, never a rename or delete
    expect(offered('memory.buckets.createBucket')).toBe(true)
    expect(offered('memory.buckets.rename')).toBe(false)
    expect(offered('memory.buckets.delete')).toBe(false)

    await selectBucket(wrapper, 's-anna') // another member's sub-bucket: renamed or deleted, never nested further
    expect(offered('memory.buckets.createBucket')).toBe(false)
    expect(offered('memory.buckets.rename')).toBe(true)
    expect(offered('memory.buckets.delete')).toBe(true)

    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // select all
    await wrapper.find('.bucket-selection-bar .bucket-action-button:not(.bucket-action-danger)').trigger('click') // Move
    const targets = wrapper.findAll('.dialog-stub .bucket-opt').map((o) => o.attributes('data-val'))
    expect(targets.sort()).toEqual(['b-anna', 's-anna-2']) // Anna's root only — never the caller's b-me / s-me
  })
})

// Inbox #1065: a drop on the Beiträge view used to emit `uploadBucket` alone, which the detail answered by opening the
// picker — the dropped files and the bucket they landed on were both lost. Levers: emit `uploadBucket` again (no
// `dropFiles`), drop the file list, or take a drop onto another uploader's root.
describe('MemoryBuckets — a drop goes into the bucket it landed on (inbox #1065)', () => {
  const drop = (w: ReturnType<typeof mountBuckets>, files: File[]) =>
    w.find('.bucket-detail').trigger('drop', { dataTransfer: { files } })
  const photo = new File([new Uint8Array([1])], 'a.jpg', { type: 'image/jpeg' })
  const heic = new File([new Uint8Array([2])], 'b.HEIC', { type: '' }) // Safari's Photos: no type, the extension decides
  const text = new File(['x'], 'notes.txt', { type: 'text/plain' })

  beforeEach(() => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer'), person('me', 'me', 'Zoe Self')])
    vi.mocked(openBucketName).mockImplementation((_s, _n, _k, b) => `Sub ${b.bucketId}`)
    vi.mocked(listBuckets).mockResolvedValue([
      bucket('b-anna', 'user:u-anna', 0),
      bucket('s-anna', 'user:u-anna', 0, 'ct', false, 'b-anna'),
      bucket('b-me', 'user:me', 0),
      bucket('s-me', 'user:me', 0, 'ct', false, 'b-me'),
    ])
  })

  it("hands the dropped photos and videos up with the caller's sub-bucket, never the picker, the rest left out and counted", async () => {
    const w = mountBuckets([])
    await flushPromises()
    await selectBucket(w, 's-me')
    await drop(w, [photo, text, heic])
    expect(w.emitted('uploadBucket'), 'the picker is not opened').toBeUndefined()
    expect(w.emitted('dropFiles')).toEqual([[[photo, heic], 's-me']])
    // every app's drop says the same of what it leaves out (inbox #1081, `memory_not_media`)
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['1 file was not added: only photos and videos go into a Memory.'])
  })

  it('says nothing of a drop that is photos and videos alone', async () => {
    const w = mountBuckets([])
    await flushPromises()
    await selectBucket(w, 's-me')
    await drop(w, [photo, heic])
    expect(toastError).not.toHaveBeenCalled()
  })

  it("files a drop onto the caller's own primary as an upload with no move", async () => {
    const w = mountBuckets([])
    await flushPromises()
    await selectBucket(w, 'b-me')
    await drop(w, [photo])
    expect(w.emitted('dropFiles')).toEqual([[[photo], null]])
  })

  it("refuses a drop onto another uploader's root, says why, and offers no upload there", async () => {
    const w = mountBuckets([])
    await flushPromises()
    for (const id of ['b-anna', 's-anna']) {
      await selectBucket(w, id)
      await drop(w, [photo])
      expect(w.find(`.bucket-picker .bucket-action-button[aria-label="${t('memory.buckets.upload')}"]`).exists(), id).toBe(false)
    }
    expect(w.emitted('dropFiles')).toBeUndefined()
    expect(toastError).toHaveBeenCalledTimes(2)
    expect(toastError).toHaveBeenCalledWith(t('memory.buckets.dropNotOwn'))
    await selectBucket(w, 's-me')
    expect(w.find(`.bucket-picker .bucket-action-button[aria-label="${t('memory.buckets.upload')}"]`).exists(), 'control').toBe(true)
  })

  it('takes nothing from a drop without a photo or video, and says how many it left out', async () => {
    const w = mountBuckets([])
    await flushPromises()
    await selectBucket(w, 's-me')
    await drop(w, [text, new File(['y'], 'list.pdf', { type: 'application/pdf' })])
    expect(w.emitted('dropFiles')).toBeUndefined()
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['2 files were not added: only photos and videos go into a Memory.'])
  })
})

// uc.memory.buckets § Correct a selection's capture time: the selection reaches the lightbox, and "Shift capture time" — an
// icon of the selection bar — opens it over the selected media alone, in grid order, with the editor open; offered from two
// selected on, and only where a capture time may be edited (editMeta and the source's media-meta door).
describe('MemoryBuckets — a selection\'s capture-time shift', () => {
  const shiftButton = (w: ReturnType<typeof mountBuckets>) => w.find('.bucket-selection-bar .bucket-shift-button')
  const ORDER = ['m1', 'm2', 'm3'] // the grid's order: the tiles carry no id of their own here
  const check = (w: ReturnType<typeof mountBuckets>, id: string) => w.findAll('.media-tile-check input')[ORDER.indexOf(id)]

  async function threeOwn(provide?: Record<symbol, unknown>) {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 3)])
    const w = mountBuckets(ORDER.map((id) => media(id, 'user:me', 'b-own')), provide)
    await flushPromises()
    return w
  }

  it('opens the lightbox over the selected media alone, in grid order, with their grid numbers and the editor open', async () => {
    const w = await threeOwn()
    expect(shiftButton(w).attributes('aria-label')).toBe(t('memory.buckets.shiftCapturedAt'))
    expect(shiftButton(w).text(), 'an icon, no label').toBe('')
    expect(shiftButton(w).attributes('aria-disabled'), 'nothing selected').toBe('true')
    await check(w, 'm3').trigger('change')
    expect(shiftButton(w).attributes('aria-disabled'), 'one selected: there is nothing to shift with it').toBe('true')
    await shiftButton(w).trigger('click')
    expect(w.emitted('openLightbox'), 'an inactive click does nothing').toBeUndefined()
    await check(w, 'm2').trigger('change')
    expect(shiftButton(w).attributes('aria-disabled'), 'control: two selected').toBeUndefined()
    await shiftButton(w).trigger('click')
    const [payload] = w.emitted('openLightbox')!.at(-1) as [
      { items: MediaItem[]; index: number; selected: string[]; editCapturedAt?: boolean; numbers?: Record<string, number> },
    ]
    expect(payload.items.map((m) => m.mediaId), 'the selection alone, in grid order, not in the order clicked').toEqual(['m2', 'm3'])
    expect(payload.index).toBe(0)
    expect([...payload.selected].sort()).toEqual(['m2', 'm3'])
    expect(payload.numbers, 'each keeps its number in the grid').toEqual({ m2: 2, m3: 3 })
    expect(payload.editCapturedAt).toBe(true)
  })

  it('a tile opened while a selection stands hands the selection to the lightbox, the editor closed', async () => {
    const w = await threeOwn()
    await check(w, 'm1').trigger('change')
    await check(w, 'm3').trigger('change')
    await w.findAll('.media-tile-open')[ORDER.indexOf('m3')].trigger('click')
    const [payload] = w.emitted('openLightbox')!.at(-1) as [{ index: number; selected: string[]; editCapturedAt?: boolean }]
    expect(payload.index).toBe(2)
    expect([...payload.selected].sort()).toEqual(['m1', 'm3'])
    expect(payload.editCapturedAt).toBeFalsy()
  })

  it('is not offered where the capture time may not be edited: no editMeta, or a source without the media-meta door', async () => {
    const source = memberMemorySource({ scopeId: () => 's-1' })
    const views = {
      'no editMeta': { source, rights: computed(() => ({ ...memberMemoryRights(), editMeta: false })) },
      'no door': { source: { ...source, mediaMeta: undefined }, rights: computed(() => memberMemoryRights()) },
    }
    for (const [why, view] of Object.entries(views)) {
      const w = await threeOwn({ [MEMORY_VIEW as symbol]: view })
      await w.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
      expect(shiftButton(w).exists(), why).toBe(false)
      w.unmount()
    }
    const control = await threeOwn()
    await control.find('.bucket-selection-bar .veyq-text-button').trigger('click')
    expect(shiftButton(control).attributes('aria-disabled'), 'control: a member').toBeUndefined()
  })
})

describe('MemoryBuckets — the counts follow the live media (an upload while the tab is open)', () => {
  /** The count the picker shows beside the active bucket. */
  const shownCount = (w: ReturnType<typeof mountBuckets>) => w.find('.bucket-select-value small').text()

  it("the active bucket's count grows with the media the gallery hands in, not the count the list was read with", async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 1)])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own')])
    await flushPromises()
    expect(shownCount(wrapper)).toBe('1')

    await wrapper.setProps({ media: ['m1', 'm2', 'm3'].map((id) => media(id, 'user:me', 'b-own')) }) // two uploads landed
    expect(shownCount(wrapper)).toBe('3')
    expect(wrapper.findAll('.media-tile-check').length).toBe(3)
    expect(listBuckets, 'a known bucket needs no re-read').toHaveBeenCalledTimes(1)
  })

  it('a stale server count never shows: each bucket counts its own media only', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 7), bucket('b-share', 'share:x', 9, 'ct')])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), media('m2', 'user:me', 'b-own'), media('m3', 'share:x', 'b-share')])
    await flushPromises()
    expect(shownCount(wrapper)).toBe('2')
    await selectBucket(wrapper, 'b-share')
    expect(shownCount(wrapper)).toBe('1')
  })

  it('the first upload that materializes your primary re-reads the list once, quietly, and shows the medium under it', async () => {
    vi.mocked(listBuckets).mockResolvedValueOnce([]) // no primary of yours yet: the virtual one
    const wrapper = mountBuckets([])
    await flushPromises()
    expect(shownCount(wrapper)).toBe('0')

    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 1)])
    await wrapper.setProps({ media: [media('m1', 'user:me', 'b-own')] }) // the server put it into the new primary
    expect(wrapper.find('.media-grid-placeholder').exists(), 'the grid stays, no spinner').toBe(false)
    await flushPromises()
    expect(listBuckets).toHaveBeenCalledTimes(2)
    expect(shownCount(wrapper)).toBe('1')
    expect(wrapper.findAll('.media-tile-check').length).toBe(1)

    await wrapper.setProps({ media: [media('m1', 'user:me', 'b-own'), media('m2', 'user:me', 'b-own')] })
    await flushPromises()
    expect(listBuckets, 'a bucket the list now holds is not read for again').toHaveBeenCalledTimes(2)
  })
})

describe('MemoryBuckets — an uploader whose every medium was deleted leaves the picker (as the server lists it)', () => {
  const options = (w: ReturnType<typeof mountBuckets>) => w.findAll('.bucket-opt').map((o) => o.attributes('data-val'))
  beforeEach(() => setAccountUserId('me'))
  afterEach(() => clearAccount())

  it("a guest's last photo deleted while the tab is open: the guest is gone at once, and a restore brings them back", async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 1), bucket('b-share', 'share:x', 1, 'ct')])
    const guestPhoto = media('m2', 'share:x', 'b-share')
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), guestPhoto])
    await flushPromises()
    expect(options(wrapper)).toEqual(['b-share', 'b-own']) // the positive control: the guest is listed

    await wrapper.setProps({ media: [media('m1', 'user:me', 'b-own')] })
    expect(options(wrapper)).toEqual(['b-own'])

    await wrapper.setProps({ media: [media('m1', 'user:me', 'b-own'), guestPhoto] })
    expect(options(wrapper)).toEqual(['b-share', 'b-own'])
    expect(listBuckets, 'the bucket was kept: no re-read').toHaveBeenCalledTimes(1)
  })

  it("another member's emptied primary leaves too; your own stays, empty, where your next upload goes", async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 1), bucket('b-bob', 'user:bob', 1)])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), media('m2', 'user:bob', 'b-bob')])
    await flushPromises()
    expect(options(wrapper)).toHaveLength(2)

    await wrapper.setProps({ media: [] })
    expect(options(wrapper)).toEqual(['b-own'])
  })

  it('a sub-bucket curated under the emptied guest keeps the guest listed', async () => {
    vi.mocked(listBuckets).mockResolvedValue([
      bucket('b-own', 'user:me', 1), bucket('b-share', 'share:x', 1, 'ct'), bucket('s-share', 'share:x', 0, 'sub', false, 'b-share'),
    ])
    const wrapper = mountBuckets([media('m1', 'user:me', 'b-own'), media('m2', 'share:x', 'b-share')])
    await flushPromises()
    await wrapper.setProps({ media: [media('m1', 'user:me', 'b-own')] })
    expect(options(wrapper)).toContain('b-share')
    expect(options(wrapper)).toContain('s-share')
  })

  it("a guest whose photos have not arrived yet stays: only a bucket emptied on screen leaves", async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 0), bucket('b-share', 'share:x', 1, 'ct')])
    const wrapper = mountBuckets([]) // the gallery's pages are still on their way
    await flushPromises()
    expect(options(wrapper)).toContain('b-share')
  })
})

describe('MemoryBuckets — a guest\'s name is marked as a guest\'s (inbox #433)', () => {
  it('a contributor primary reads "<name> (guest)"; a member\'s primary and a member\'s sub-bucket stay as they are', async () => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer')])
    vi.mocked(listBuckets).mockResolvedValue([
      bucket('b-own', 'user:u-anna', 0),
      bucket('b-share', 'share:x', 1, 'ct'),
      bucket('s-share', 'share:x', 0, 'sub', false, 'b-share'),
    ])
    // The guest named itself like the member; the sub-bucket under its contributions is a member's own folder name.
    vi.mocked(openBucketName).mockImplementation((_s, _n, _k, b) => (b.bucketId === 's-share' ? 'Best of' : 'Anna Meyer'))
    const wrapper = mountBuckets([media('m3', 'share:x', 'b-share')])
    await flushPromises()
    const shown = async (id: string) => {
      await selectBucket(wrapper, id)
      return wrapper.find('.bucket-select-value > span:not(.veyq-icon)').text() // the active bucket's name
    }
    expect(await shown('b-own')).toBe('Anna Meyer')
    expect(await shown('b-share')).toBe('Anna Meyer (guest)')
    expect(await shown('s-share')).toBe('Best of')
  })

  it('a contributor whose name does not open keeps the plain fallback, which already says what it is', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 0), bucket('b-share', 'share:x', 1, 'ct')])
    vi.mocked(openBucketName).mockReturnValue(null)
    const wrapper = mountBuckets([media('m3', 'share:x', 'b-share')])
    await flushPromises()
    await selectBucket(wrapper, 'b-share')
    expect(wrapper.find('.bucket-select-value').text()).toContain('Contribution')
    expect(wrapper.find('.bucket-select-value').text()).not.toContain('(guest)')
  })
})

describe('MemoryBuckets — a member is named by the display rule (uc.memory.buckets:members-named-by-the-display-rule)', () => {
  it("a member's primary reads their real name, one without a real name their username; the names are the scope's", async () => {
    vi.mocked(loadScopePeople).mockResolvedValue([person('u-anna', 'anna', 'Anna Meyer'), person('u-ben', 'ben')])
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-anna', 'user:u-anna', 1), bucket('b-ben', 'user:u-ben', 1)])
    const wrapper = mountBuckets([media('m1', 'user:u-anna', 'b-anna'), media('m2', 'user:u-ben', 'b-ben')])
    await flushPromises()
    expect(loadScopePeople).toHaveBeenCalledWith('s-1')
    const shown = async (id: string) => {
      await selectBucket(wrapper, id)
      return wrapper.find('.bucket-select-value > span:not(.veyq-icon)').text()
    }
    expect(await shown('b-anna')).toBe('Anna Meyer')
    expect(await shown('b-ben')).toBe('ben')
  })

  it('a member the directory does not list, or a directory that cannot be read, leaves the neutral "Member"', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-gone', 'user:u-gone', 1)])
    const reads = [async () => [person('u-anna', 'anna', 'Anna Meyer')], async (): Promise<Person[]> => { throw new Error('offline') }]
    for (const read of reads) {
      vi.mocked(loadScopePeople).mockImplementationOnce(read)
      const wrapper = mountBuckets([media('m1', 'user:u-gone', 'b-gone')])
      await flushPromises()
      await selectBucket(wrapper, 'b-gone')
      expect(wrapper.find('.bucket-select-value > span:not(.veyq-icon)').text()).toBe(t('memory.buckets.member'))
      wrapper.unmount()
    }
  })
})

describe("MemoryBuckets — a link's guest (ADR 0030 point 8, ADR 0083 decision 7)", () => {
  it("is never drawn on the guest's source, and reads no member's name and no authenticated client", async () => {
    authenticatedCalls.length = 0
    const head = { shareId: 'sh-1', scopeId: 's-1', role: ShareRole.Read, share: { role: ShareRole.Read, scopeId: 's-1', nodes: [] } } as unknown as PublicShareHead
    const source = guestMemorySource(() => guestAccessFor(head), { rootId: 'n-1' })
    expect(source.buckets, "the guest's source carries no buckets door").toBeUndefined()
    // it refuses to render rather than reach for a member's read
    expect(() => mount(MemoryBuckets, {
      props: { scopeId: 's-1', nodeId: 'n-1', nodeEpoch: 0, memoryKey: new Uint8Array(32), media: [], thumbs: new Map() },
      global: {
        plugins: [realI18n()], stubs: { VeyqSelect: SelectStub, VeyqMenu: MenuStub, MediaGrid: MediaGridStub },
        provide: { [MEMORY_VIEW as symbol]: { source, rights: computed(() => memberMemoryRights()) } },
      },
    })).toThrow('without a buckets door')
    await flushPromises()
    expect(loadScopePeople).not.toHaveBeenCalled()
    expect(listBuckets).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })
})

// ADR 0105: the contributors' view is the same windowed grid — the REAL MediaGrid here, not the stub — so a bucket of
// 2,000 mounts about a window of tiles, while the selection is by id: select-all selects all 2,000, and Space on a
// focused tile toggles its checkbox as a click does. happy-dom lays nothing out: every box is 960 px wide, the window
// is happy-dom's own viewport.
describe('MemoryBuckets — a bucket of 2,000 on the windowed grid (ADR 0105)', () => {
  function mountReal(mediaItems: MediaItem[]) {
    return mount(MemoryBuckets, {
      props: { scopeId: 's-1', nodeId: 'n-1', nodeEpoch: 0, memoryKey: new Uint8Array(32), media: mediaItems, thumbs: new Map() },
      global: { plugins: [realI18n()], stubs: { VeyqSelect: SelectStub, VeyqMenu: MenuStub }, provide: memberView('s-1') },
      attachTo: document.body,
    })
  }
  beforeEach(() => {
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('mounts about a window of tiles, and select-all selects every one of the 2,000 by id', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 2000)])
    const all = Array.from({ length: 2000 }, (_, i) => media(`m${i}`, 'user:me', 'b-own'))
    const wrapper = mountReal(all)
    await flushPromises()
    const windowed = wrapper.findAll('.media-tile').length
    expect(windowed).toBeGreaterThan(0)
    expect(windowed).toBeLessThan(200) // three viewports of 768 px at ~200 px rows of ~4: far below 2,000
    await wrapper.find('.bucket-selection-bar .veyq-text-button').trigger('click') // "Select all"
    expect(wrapper.find('.bucket-selection-bar').text()).toContain(t('memory.buckets.selected', { selected: 2000, visible: 2000 }))
    expect(wrapper.findAll('.media-tile.is-selected').length).toBe(windowed) // every mounted tile shows it
  })

  it('Space on a focused tile toggles its selection; Enter is still its open button', async () => {
    vi.mocked(listBuckets).mockResolvedValue([bucket('b-own', 'user:me', 3)])
    const wrapper = mountReal([media('m0', 'user:me', 'b-own'), media('m1', 'user:me', 'b-own'), media('m2', 'user:me', 'b-own')])
    await flushPromises()
    const open = wrapper.find('[data-grid-id="m1"] .media-tile-open').element as HTMLButtonElement
    open.focus()
    open.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.find('[data-grid-id="m1"] .media-tile').classes()).toContain('is-selected')
    open.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.find('[data-grid-id="m1"] .media-tile').classes()).not.toContain('is-selected')
    expect(wrapper.emitted('openLightbox')).toBeUndefined()
  })
})
