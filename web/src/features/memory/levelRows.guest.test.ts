// @vitest-environment happy-dom
// Memories' rows of the unified level list for a SHARE GUEST (inbox #401 S2, #407): the member's row factory, handed a guest's
// access and the tree its link opened (`guestTreeSource` over real crypto). It replaces `MemoriesSection.guest.test.ts`. The
// guest sees what a member sees — the rows with their sealed dates and covers, the map of the opened subtree — with every
// member-only function removed (no heart:
// pins are the member's own, sealed under the Master Key) and NOTHING of the member's world touched: every authenticated
// client throws (`test/guestMode.ts`), and the member's cover and pin stores must not be asked. Covers come through the
// link's own reads; only their transport (the presigned GETs) is stubbed.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('./api', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('./favoritesStore', () => ({ isFavorite: vi.fn(), toggleFavorite: vi.fn() }))
vi.mock('./coverStore', async (orig) => ({ ...(await orig<typeof import('./coverStore')>()), ensureCovers: vi.fn() }))
vi.mock('../../core/sharing/guestBlob', async (orig) => ({ ...(await orig<object>()), shareChunkUrls: vi.fn(async () => ['https://store/c0']) }))
vi.mock('../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), openBlobFromUrls: vi.fn(async () => new Uint8Array([1, 2, 3])) }))
vi.mock('./share', async (orig) => ({ ...(await orig<typeof import('./share')>()), openShareGrid: vi.fn() }))

import { memoryRows } from './levelRows'
import MemoryRowCover from './components/MemoryRowCover.vue'
import { FOLDER_KIND } from '../../core/node/kind'
import { sealName, wrapContentKey, type TreeNode } from '../../core/node/tree'
import { guestTreeSource } from '../../core/sharing/guestAccess'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { openBlobFromUrls } from '../../core/blob/transport'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { MEMORY_KIND } from './kinds'
import { sealNodeMeta } from './nodeMeta'
import { publicShareMediaApi } from './api'
import { openShareGrid } from './share'
import { isFavorite, toggleFavorite } from './favoritesStore'
import { ensureCovers } from './coverStore'
import type { ViewMode } from '../../shared/state/useViewMode'
import { formatClosedRange } from './memoryDateRange'
import { stubApi } from '../../test/apiMock'
import { t } from '../../test/i18n'
import { guestAccessFor } from '../../test/models'
import { authenticatedCalls } from '../../test/guestMode'
import { actionIdsOf, clickAction, mountRows, rowOf, titlesOf, type RowsHarness } from '../../test/levelRows'

const SCOPE_ID = '77777777-7777-4777-8777-777777777777'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_g'
const ROOT = '6e5a0000-0000-4000-8000-000000000500'
const SUB = '6e5a0000-0000-4000-8000-000000000501'
const OLD = '6e5a0000-0000-4000-8000-000000000502'
const NEW = '6e5a0000-0000-4000-8000-000000000503'
const DEEP = '6e5a0000-0000-4000-8000-000000000504'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), sub: v1.randomBytes(32), old: v1.randomBytes(32), new: v1.randomBytes(32), deep: v1.randomBytes(32) }
const UPLOAD_REF = { blobId: 'cover-blob', chunkCount: 1, byteSize: 3, wrappedBlobKey: 'eA==' }

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string, meta?: object): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return {
    id, parentId, kind, epoch: 0, placementVersion: 0,
    encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key),
    wrappedMeta: meta ? sealNodeMeta(key, { scopeId: SCOPE_ID, nodeId: id }, { v: 1, ...meta }) : null,
  }
}

function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(SUB, ROOT, keys.root, keys.sub, 'Reisen', FOLDER_KIND),
        // Dated, its cover an uploaded image (a blob of the node's own).
        child(OLD, ROOT, keys.root, keys.old, 'Ostsee', MEMORY_KIND, {
          date: { mode: 'single', from: '2019-07-01', to: '2019-07-01' },
          cover: { source: { kind: 'upload', ref: UPLOAD_REF }, crop: { x: 0, y: 0, width: 1, height: 1 } },
        }),
        // Dated later, its cover one of its photos (read from the link's media listing).
        child(NEW, ROOT, keys.root, keys.new, 'Rom', MEMORY_KIND, {
          date: { mode: 'single', from: '2024-04-02', to: '2024-04-02' },
          cover: { source: { kind: 'media', mediaId: 'm-rom' }, crop: { x: 0, y: 0, width: 1, height: 1 } },
          location: { name: 'Rom', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 41.9, lng: 12.5 } },
        }),
        // Located, in the sub-folder: on the root's map.
        child(DEEP, SUB, keys.sub, keys.deep, 'Wien', MEMORY_KIND, {
          location: { name: 'Wien', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 48.2, lng: 16.4 } },
        }),
      ],
    } as PublicShare,
  }
}

const SpaceMapStub = { name: 'SpaceMap', props: ['pins', 'covers'], emits: ['open'], template: '<div class="space-map-stub">{{ pins.length }}</div>' }

let harness: RowsHarness | null = null
/** `viewMode`: the mode the level hands in (inbox #404). */
function mountGuest(role: ShareRole, viewMode: ViewMode = 'grid') {
  const h = head(role)
  const tree = guestTreeSource(h, reactive({ hash: `#k=${b64url(K_SHARE)}` }))
  const root = tree.node(ROOT)!
  const nodes = tree.children(ROOT).filter((n) => n.kind === MEMORY_KIND)
  harness = mountRows(memoryRows, { folder: root, nodes, epochs: null, access: guestAccessFor(h), tree, viewMode, liveTick: undefined }, 'grid', {
    stubs: { SpaceMap: SpaceMapStub },
  })
  return harness
}
const metaOf = (h: RowsHarness, title: string) => rowOf(h.w, title).find('.veyq-level-item-meta').text()

afterEach(() => {
  harness?.w.unmount()
  harness = null
})

beforeEach(() => {
  authenticatedCalls.length = 0
  vi.mocked(ensureCovers).mockClear()
  vi.mocked(isFavorite).mockReset().mockReturnValue(false)
  vi.mocked(toggleFavorite).mockClear()
  vi.mocked(shareChunkUrls).mockClear()
  vi.mocked(openBlobFromUrls).mockClear()
  vi.mocked(openShareGrid).mockReset().mockResolvedValue(new Uint8Array([4, 5, 6]))
  stubApi(publicShareMediaApi, {
    listShareMedia: {
      media: [{
        nodeId: NEW, mediaId: 'm-rom', blobId: 'b-rom', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'eA==',
        derivatives: [{ kind: 'THUMBNAIL', blobId: 't-rom', chunkCount: 1, byteSize: 5, wrappedBlobKey: 'eA==' }],
      }],
      identities: [],
    },
  })
})

describe('Memories rows for a share guest', () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: rows with their sealed dates, opened under the chain's keys — no heart, no rename, no move`, async () => {
      const h = mountGuest(role)
      await flushPromises()
      expect(titlesOf(h.w)).toEqual(['Ostsee', 'Rom'])
      expect(metaOf(h, 'Rom')).toBe(`${t('memory.kindMemory')} · ${formatClosedRange('2024-04-02', '2024-04-02', 'en')}`)
      expect(metaOf(h, 'Ostsee')).toBe(`${t('memory.kindMemory')} · ${formatClosedRange('2019-07-01', '2019-07-01', 'en')}`)
      // no pin (the member’s own, under the Master Key), no rename; a contributor may move a Memory a folder holds
      expect(await actionIdsOf(h.w, 'Rom')).toEqual(role === ShareRole.Read ? [] : ['move'])
      expect(isFavorite).not.toHaveBeenCalled()
      expect(ensureCovers, "the member's cover store is not asked").not.toHaveBeenCalled()
      expect(authenticatedCalls).toEqual([])
    })
  }

  it("resolves each cover through the link's own reads: an uploaded cover blob, a chosen photo's thumbnail", async () => {
    const h = mountGuest(ShareRole.Read)
    await flushPromises()
    await vi.waitFor(() => expect(h.w.findAllComponents(MemoryRowCover).every((c) => c.props('src'))).toBe(true))
    // The uploaded cover: the gated share presign of its blob, opened under the Memory's key from the chain.
    expect(shareChunkUrls).toHaveBeenCalledWith(SHARE_ID, 'cover-blob', undefined)
    const [scope, key, ref] = vi.mocked(openBlobFromUrls).mock.calls[0]
    expect(scope).toBe(SCOPE_ID)
    expect(Array.from(key)).toEqual(Array.from(keys.old))
    expect(ref).toMatchObject({ blobId: 'cover-blob', ownerId: OLD, role: 'COVER' })
    // The chosen photo: the link's listing, read once, its thumbnail opened as the guest's gallery opens it.
    expect(vi.mocked(publicShareMediaApi().listShareMedia)).toHaveBeenCalledOnce()
    const [shareId, item] = vi.mocked(openShareGrid).mock.calls[0]
    expect(shareId).toBe(SHARE_ID)
    expect(item.item.mediaId).toBe('m-rom')
    expect(Array.from(item.memoryKey)).toEqual(Array.from(keys.new))
    expect(authenticatedCalls).toEqual([])
  })

  it('no role gets a create tile from the rows: the entry is core\'s "New" menu, gated by the same matrix (inbox #406)', () => {
    for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
      expect(mountGuest(role).w.find('.veyq-create-card').exists()).toBe(false)
    }
  })

  it("the map spans the opened subtree — a sub-folder's Memory too — keyed by the chain, and a pin opens its node", async () => {
    const h = mountGuest(ShareRole.Read, 'map')
    await flushPromises()
    const map = h.w.findComponent(SpaceMapStub)
    expect((map.props('pins') as { node: TreeNode }[]).map((p) => p.node.name).sort()).toEqual(['Rom', 'Wien'])
    map.vm.$emit('open', DEEP)
    await flushPromises()
    expect(h.events.open[0].id).toBe(DEEP)
    expect(authenticatedCalls).toEqual([])
  })

  it('a row click asks the host to open the node (the host links it through the tree)', async () => {
    const h = mountGuest(ShareRole.Read)
    await rowOf(h.w, 'Rom').find('button.veyq-level-item-main').trigger('click')
    expect(h.events.open[0].id).toBe(NEW)
    expect(toggleFavorite).not.toHaveBeenCalled()
  })

  // inbox #358: a contributor moves a Memory of a folder (core's move dialog, the host's - asked through the host); a READ
  // guest has no such action (the loop above lists every action a row has).
  it('a contributor\'s row offers the move, which asks the host for core\'s move dialog', async () => {
    const h = mountGuest(ShareRole.ReadWrite)
    await clickAction(h.w, 'Rom', 'move')
    expect(h.events.move.map((n) => n.id)).toEqual([NEW])
    expect(authenticatedCalls).toEqual([])
  })
})
