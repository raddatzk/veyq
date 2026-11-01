// @vitest-environment happy-dom
// Memories' data source (inbox #401 S4, `./source.ts`): the guest's door by door against a faked share transport (the
// link's public clients stubbed, every authenticated one THROWING — `test/guestMode.ts`; the share helpers that fetch
// bytes stubbed to record what they are asked), the member's door by door against its `media.ts`-and-friends calls, and
// the invariant every view rests on: a door the source does not carry is a right the view does not show.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('./api', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('./share', async (orig) => ({
  ...(await orig<typeof import('./share')>()),
  openShareGrid: vi.fn(),
  openShareLightbox: vi.fn(),
  openShareOriginal: vi.fn(),
  shareChunkProvider: vi.fn(),
  addSharePhoto: vi.fn(),
  deleteSharePhoto: vi.fn(),
}))
vi.mock('../../core/sharing/guestBlob', async (orig) => ({ ...(await orig<object>()), shareChunkUrls: vi.fn(async () => ['https://store.test/0']) }))
vi.mock('../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), openBlobVia: vi.fn(async (_s: unknown, _k: unknown, _r: unknown, presign: (o: object) => Promise<string[]>) => (await presign({}), new Uint8Array([9]))) }))
// The member's doors, each a recorder: the source must hand every call on with the scope it was built for.
vi.mock('./media', async (orig) => {
  const real = await orig<typeof import('./media')>()
  const names = [
    'openGrid', 'openLightbox', 'openOriginal', 'openBlob', 'chunkProvider', 'loadMediaExif', 'listMediaPages', 'deleteMedia',
    'restoreMedia', 'listTrash', 'purgeMedia', 'setMediaMeta', 'backfillPlaceName',
    'listBuckets', 'createBucket', 'renameBucket', 'deleteBucket', 'moveBucketMedia',
  ] as const
  return { ...real, ...Object.fromEntries(names.map((n) => [n, vi.fn(async () => ({ notModified: true }))])) }
})
vi.mock('./memoryDate', async (orig) => ({ ...(await orig<object>()), setMemoryDate: vi.fn(), reconcileMemoryDate: vi.fn() }))
vi.mock('./memoryLocation', async (orig) => ({ ...(await orig<object>()), setMemoryLocation: vi.fn() }))
vi.mock('../../core/node/nodeMetaWrite', () => ({ writeNodeMeta: vi.fn() }))
vi.mock('./itemFavoritesStore', async (orig) => ({ ...(await orig<object>()), loadItemFavoritesFor: vi.fn(), toggleItemFavorite: vi.fn() }))
vi.mock('../../core/people/directory', async (orig) => ({ ...(await orig<object>()), loadScopePeople: vi.fn() }))

import {
  guestMemorySource,
  memberMemorySource,
  sourceRights,
  type MemorySource,
} from './source'
import { NO_MEMORY_RIGHTS, type MemoryRights } from './rights'
import * as share from './share'
import * as media from './media'
import { setMemoryDate, reconcileMemoryDate } from './memoryDate'
import { setMemoryLocation } from './memoryLocation'
import { writeNodeMeta } from '../../core/node/nodeMetaWrite'
import { loadItemFavoritesFor, toggleItemFavorite } from './itemFavoritesStore'
import { loadScopePeople } from '../../core/people/directory'
import { publicShareMediaApi } from './api'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { openBlobVia } from '../../core/blob/transport'
import { saveGuestSession, forgetGuestSession } from '../../core/sharing/guestIdentity'
import { loadOwnShareMedia } from './ownShareMedia'
import { sealName, wrapContentKey } from '../../core/node/tree'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import type { ShareMedia } from '../../api/generated/share-media'
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import { stubApi } from '../../test/apiMock'
import { apiResponse } from '../../test/apiResponse'
import { httpError } from '../../test/httpError'
import { guestAccessFor, mediaItem } from '../../test/models'
import { authenticatedCalls } from '../../test/guestMode'
import type { GuestAccess } from '../../core/access'
import type { GuestIdentity } from '../../core/sharing/guestIdentity'

const SCOPE = '5c09e000-0000-4000-8000-0000000000aa'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_7'
const MEMORY = '5c09e000-0000-4000-8000-0000000000a1'
const CHAPTER = '5c09e000-0000-4000-8000-0000000000a2'
const PW = 'cHc='
const K_SHARE = v1.randomBytes(32)
const keys = { memory: v1.randomBytes(32), chapter: v1.randomBytes(32) }
const GUEST: GuestIdentity = { identityId: 'id-1', contributorToken: 'tok-1' }

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementRevision: 0 }
  return { blockedByOperator: false, id, parentId, kind, epoch: 0, placementRevision: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
/** A Memory link with one chapter. */
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name: 'Rom 2026', noteByLocale: {}, role, rootKind: MEMORY_KIND,
    share: {
      role, scopeId: SCOPE, rootKind: MEMORY_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.memory)),
      nodes: [{ id: MEMORY, parentId: null, kind: MEMORY_KIND, epoch: 0 }, child(CHAPTER, MEMORY, keys.memory, keys.chapter, 'Tag 1', CHAPTER_KIND)],
    } as PublicShare,
  }
}
/** What the share's chunk provider hands back, as a marker (its own suite opens the bytes). */
const CHUNKS = { meta: vi.fn(), chunk: vi.fn() } as unknown as ReturnType<typeof share.shareChunkProvider>
/** The tag the link's listing names (a digest of what it serves, ADR 0081 decision 4 — opaque to the client). */
const TAG = '"2a9b1d4a6e8b2c1f2a9b1d4a6e8b2c1f"'
const row = (mediaId: string, nodeId: string): ShareMedia => ({ blockedByOperator: false, mediaId, nodeId, blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 3, wrappedBlobKey: 'eA==' })

let requestGuest: ReturnType<typeof vi.fn<() => Promise<GuestIdentity | null>>>
function guest(role: ShareRole): { source: MemorySource; access: GuestAccess } {
  requestGuest = vi.fn(async () => GUEST)
  const access = guestAccessFor(head(role), { pwToken: PW, requestGuest })
  return { access, source: guestMemorySource(() => access, { rootId: MEMORY }) }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  forgetGuestSession(SHARE)
  authenticatedCalls.length = 0
  const listing = { media: [row('m-root', MEMORY), row('m-ch', CHAPTER)], identities: [], more: false }
  stubApi(publicShareMediaApi, { listShareMedia: listing, listShareMediaRaw: apiResponse(listing, { ETag: TAG }) })
  vi.mocked(share.openShareGrid).mockResolvedValue(new Uint8Array([1]))
  vi.mocked(share.openShareLightbox).mockResolvedValue(new Uint8Array([2]))
  vi.mocked(share.openShareOriginal).mockResolvedValue(new Uint8Array([3]))
  vi.mocked(share.shareChunkProvider).mockReturnValue(CHUNKS)
  vi.mocked(share.addSharePhoto).mockResolvedValue({ blobId: 'b-new', mediaId: 'm-new' })
  vi.mocked(share.deleteSharePhoto).mockResolvedValue()
})

/** Every door a member's source carries and a guest's must not, by name. */
const MEMBER_ONLY = ['trash', 'nodeMeta', 'mediaMeta', 'marks', 'buckets'] as const

describe("the guest's source reads the link, and only the link", () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: lists the node's own media from the link's listing — the root's, a chapter's — and keeps its tag for the poll`, async () => {
      const { source } = guest(role)
      const atRoot = await source.listMedia(MEMORY)
      const atChapter = await source.listMedia(CHAPTER)
      expect(atRoot.items.map((i) => i.mediaId)).toEqual(['m-root'])
      expect(atChapter.items.map((i) => i.mediaId)).toEqual(['m-ch'])
      expect(atRoot.revision, "the listing's tag, verbatim; no delete revision, no cursor").toEqual({ tag: TAG, delete: 0 })
      expect(atRoot.cursor).toBeNull()
      expect(vi.mocked(publicShareMediaApi().listShareMediaRaw).mock.calls.map(([r]) => r), 'its open node, a page each').toEqual([
        { shareId: SHARE, xSharePassword: PW, nodeId: MEMORY, limit: media.MEDIA_PAGE_LIMIT, ifNoneMatch: undefined },
        { shareId: SHARE, xSharePassword: PW, nodeId: CHAPTER, limit: media.MEDIA_PAGE_LIMIT, ifNoneMatch: undefined },
      ])
      expect(authenticatedCalls).toEqual([])
    })

    it(`${role}: polls its open node conditionally on the tag it holds — a 304 is nothing, a change is the whole listing`, async () => {
      const { source } = guest(role)
      const raw = vi.mocked(publicShareMediaApi().listShareMediaRaw)
      raw.mockRejectedValueOnce(httpError(304))
      expect(await source.poll!(CHAPTER, { tag: TAG, deleteRevision: 0 })).toEqual({ notModified: true })
      expect(raw.mock.calls.at(-1)![0], 'the tag echoed verbatim, for the node on screen').toEqual({
        shareId: SHARE, xSharePassword: PW, nodeId: CHAPTER, limit: media.MEDIA_PAGE_LIMIT, ifNoneMatch: TAG,
      })

      const changed = { media: [row('m-ch', CHAPTER), row('m-new', CHAPTER), row('m-root', MEMORY)], identities: [], more: false }
      raw.mockResolvedValueOnce(apiResponse(changed, { ETag: '"next"' }))
      const page = await source.poll!(CHAPTER, { tag: TAG, deleteRevision: 0 })
      expect(page.notModified).toBe(false)
      if (page.notModified) return
      expect(page.items.map((i) => i.mediaId), "the node's own media only").toEqual(['m-ch', 'm-new'])
      expect(page.mediaIds, 'every id it holds — so a medium gone from the link drops').toEqual(['m-ch', 'm-new'])
      expect([page.tag, page.deleteRevision, page.cursor]).toEqual(['"next"', 0, null])
      expect(authenticatedCalls).toEqual([])
    })

    it(`${role}: carries none of the member's doors`, () => {
      const { source } = guest(role)
      for (const door of MEMBER_ONLY) expect(source[door], door).toBeUndefined()
      expect(source.loaders.exif, 'no heavy EXIF endpoint serves a guest').toBeUndefined()
    })
  }

  it('WRITE_ONLY: nothing to read (the link is not even asked), no upload, no delete — its page is the drop', async () => {
    const { source } = guest(ShareRole.WriteOnly)
    expect((await source.listMedia(MEMORY)).items).toEqual([])
    expect(publicShareMediaApi).not.toHaveBeenCalled()
    expect(source.upload).toBeUndefined()
    expect(source.deleteMedia).toBeUndefined()
  })

  it('opens every tier through the gated share presign, under the key the view hands in, with the password token', async () => {
    const { source } = guest(ShareRole.Read)
    const item = mediaItem({ mediaId: 'm-ch' })
    expect(await source.loaders.grid(item, keys.chapter)).toEqual(new Uint8Array([1]))
    expect(await source.loaders.preview(item, keys.chapter)).toEqual(new Uint8Array([2]))
    expect(await source.loaders.original(item, keys.chapter)).toEqual(new Uint8Array([3]))
    expect(source.loaders.chunk(item, keys.chapter)).toBe(CHUNKS)
    for (const fn of [share.openShareGrid, share.openShareLightbox, share.openShareOriginal]) {
      const [shareId, si, pw] = vi.mocked(fn).mock.calls[0]
      expect([shareId, si.item, si.memoryKey, si.scopeId, pw]).toEqual([SHARE, item, keys.chapter, SCOPE, PW])
    }
    const [shareId, si, ref, pw] = vi.mocked(share.shareChunkProvider).mock.calls[0]
    expect([shareId, si.memoryKey, ref, pw]).toEqual([SHARE, keys.chapter, item.original, PW])
    // the listed seals open on the device, no fetch: a tier that is not there is null, never a throw
    expect(source.loaders.meta(item, keys.chapter)).toBeNull()
    expect(source.loaders.blobMeta(item, keys.chapter)).toBeNull()
    expect(authenticatedCalls).toEqual([])
  })

  it("opens the node's uploaded cover blob through the share presign", async () => {
    const { source } = guest(ShareRole.Read)
    const ref = { blobId: 'b-cover', chunkCount: 1, byteSize: 3, wrappedBlobKey: 'eA==', ownerId: MEMORY, role: 'COVER' as const }
    expect(await source.loaders.coverBlob(ref, keys.memory)).toEqual(new Uint8Array([9]))
    expect(shareChunkUrls).toHaveBeenCalledWith(SHARE, 'b-cover', PW)
    expect(openBlobVia).toHaveBeenCalledWith(SCOPE, keys.memory, ref, expect.any(Function))
  })

  it('its upload batches never group with a member\'s of the same node', () => {
    expect(guest(ShareRole.Read).source.batchKey(MEMORY)).not.toBe(memberMemorySource({ scopeId: () => SCOPE }).batchKey(MEMORY))
  })
})

describe("a contributor's upload and delete (READ_WRITE)", () => {
  it('READ has neither', () => {
    const { source } = guest(ShareRole.Read)
    expect(source.upload).toBeUndefined()
    expect(source.deleteMedia).toBeUndefined()
  })

  it('an unnamed guest is not ready and sends nothing; preparing asks the page\'s one naming flow', async () => {
    const { source } = guest(ShareRole.ReadWrite)
    expect(source.upload!.ready()).toBe(false)
    expect(source.upload!.target(CHAPTER, keys.chapter, 3)).toBeNull()
    expect(await source.upload!.prepare()).toBe(true)
    expect(requestGuest).toHaveBeenCalledTimes(1)
  })

  it("into a chapter: names the chapter as the target and seals under the CHAPTER's key from the chain", async () => {
    const { source } = guest(ShareRole.ReadWrite)
    saveGuestSession(SHARE, GUEST)
    expect(source.upload!.ready()).toBe(true)
    const target = source.upload!.target(CHAPTER, keys.chapter, 3)!
    expect(target.key).toBe(source.batchKey(CHAPTER))
    const file = { blob: new Blob([new Uint8Array(3)]), mediaType: 'image/jpeg' }
    await target.upload!(file, {})
    const [session, sent] = vi.mocked(share.addSharePhoto).mock.calls[0]
    expect(session).toEqual({ ...GUEST, shareId: SHARE, scopeId: SCOPE, sealKey: keys.chapter, nodeEpoch: 3, pwToken: PW, nodeId: CHAPTER })
    expect(sent).toBe(file)
    expect(loadOwnShareMedia(SHARE, GUEST.identityId), 'the device remembers what it sent (#361)').toEqual(new Set(['m-new']))
    expect(source.deleteMedia!.reach).toBe('own')
    if (source.deleteMedia!.reach === 'own') expect(source.deleteMedia!.isOwn('m-new')).toBe(true)
  })

  it('into the share root: no target node, as every root upload always sent', async () => {
    const { source } = guest(ShareRole.ReadWrite)
    saveGuestSession(SHARE, GUEST)
    await source.upload!.target(MEMORY, keys.memory, 0)!.upload!({ blob: new Blob([]), mediaType: 'image/jpeg' }, {})
    const [session] = vi.mocked(share.addSharePhoto).mock.calls[0]
    expect(session).not.toHaveProperty('nodeId')
    expect(session.sealKey).toBe(keys.memory)
  })

  it('deletes its own media for good, with its token and the password, and forgets it', async () => {
    const { source } = guest(ShareRole.ReadWrite)
    saveGuestSession(SHARE, GUEST)
    await source.upload!.target(CHAPTER, keys.chapter, 0)!.upload!({ blob: new Blob([]), mediaType: 'image/jpeg' }, {})
    const del = source.deleteMedia!
    if (del.reach !== 'own') throw new Error('a guest purges its own')
    expect(del.isOwn('m-other'), "another's media is not this guest's").toBe(false)
    await del.purge('m-new')
    expect(share.deleteSharePhoto).toHaveBeenCalledWith(SHARE, GUEST.contributorToken, 'm-new', PW)
    expect(del.isOwn('m-new')).toBe(false)
  })

  it('"Not you?" forgets the session: nothing is own any more, a delete is refused', async () => {
    const { source } = guest(ShareRole.ReadWrite)
    saveGuestSession(SHARE, GUEST)
    await source.upload!.target(CHAPTER, keys.chapter, 0)!.upload!({ blob: new Blob([]), mediaType: 'image/jpeg' }, {})
    forgetGuestSession(SHARE)
    const del = source.deleteMedia!
    if (del.reach !== 'own') throw new Error('a guest purges its own')
    expect(del.isOwn('m-new')).toBe(false)
    await expect(del.purge('m-new')).rejects.toThrow('no_guest_session')
    expect(share.deleteSharePhoto).not.toHaveBeenCalled()
  })
})

describe("the member's source is today's calls, with its scope", () => {
  const S = 'scope-m'
  const key = v1.randomBytes(32)
  const item = mediaItem({ mediaId: 'm-1' })
  const state = { wrappedMeta: null, metaRevision: 0, nodeEpoch: 0 }
  // what a member's meta write hands on beside its arguments: the floor before each attempt, and the pull of its path and
  // the re-read after an epoch refusal (ADR 0109 point 6, ADR 0111 decision 11)
  const metaOpts = { atFloor: expect.any(Function), reread: expect.any(Function), pull: expect.any(Function) }
  const src = memberMemorySource({ scopeId: () => S })
  const edit = () => null
  const gridSignal = new AbortController().signal // a grid's read can be dropped while it waits (inbox #488)
  // [what the view calls, the module function it must reach, the arguments it must reach it with]
  const doors: [string, () => unknown, (...a: never[]) => unknown, unknown[]][] = [
    ['listMedia', () => src.listMedia('n'), media.listMediaPages, [S, 'n']],
    ['loaders.grid', () => src.loaders.grid(item, key, gridSignal), media.openGrid, [S, key, item, gridSignal, expect.any(Function)]], // + the grid's frame opener (openedListing.ts)
    ['loaders.preview', () => src.loaders.preview(item, key), media.openLightbox, [S, key, item]],
    ['loaders.original', () => src.loaders.original(item, key), media.openOriginal, [S, key, item]],
    ['loaders.chunk', () => src.loaders.chunk(item, key), media.chunkProvider, [S, key, item]],
    ['loaders.coverBlob', () => src.loaders.coverBlob(item.original, key), media.openBlob, [S, key, item.original]],
    ['loaders.exif', () => src.loaders.exif!(item, key), media.loadMediaExif, [S, item, key]],
    ['poll', () => src.poll!('n', { tag: '7', since: 'c', deleteRevision: 2 }), media.listMediaPages, [S, 'n', { ifNoneMatch: '7', since: 'c', sinceDelete: 2 }]],
    ['deleteMedia.trash', () => (src.deleteMedia!.reach === 'any' ? src.deleteMedia!.trash('m-1') : null), media.deleteMedia, [S, 'm-1']],
    ['deleteMedia.restore', () => (src.deleteMedia!.reach === 'any' ? src.deleteMedia!.restore('m-1') : null), media.restoreMedia, [S, 'm-1']],
    ['trash.list', () => src.trash!.list('n'), media.listTrash, [S, 'n']],
    ['trash.restore', () => src.trash!.restore('m-1'), media.restoreMedia, [S, 'm-1']],
    ['trash.purge', () => src.trash!.purge('m-1'), media.purgeMedia, [S, 'm-1']],
    ['nodeMeta.setDate', () => src.nodeMeta!.setDate('n', key, { mode: 'auto' }, state), setMemoryDate, [S, 'n', key, { mode: 'auto' }, state, metaOpts]],
    ['nodeMeta.reconcileDate', () => src.nodeMeta!.reconcileDate('n', key, [item], state), reconcileMemoryDate, [S, 'n', key, [item], state, metaOpts]],
    ['nodeMeta.setLocation', () => src.nodeMeta!.setLocation('n', key, null, state), setMemoryLocation, [S, 'n', key, null, state, metaOpts]],
    ['nodeMeta.write', () => src.nodeMeta!.write('n', key, state, edit), writeNodeMeta, [S, 'n', key, state, edit, undefined, metaOpts]],
    ['mediaMeta.set', () => src.mediaMeta!.set(item, key, { id: 'n', epoch: 2 }, 5), media.setMediaMeta, [S, item, key, 2, 5, metaOpts]],
    ['mediaMeta.backfillPlace', () => src.mediaMeta!.backfillPlace(item, key, { id: 'n', epoch: 2 }, 'Rom', 'city'), media.backfillPlaceName, [S, item, key, 2, 'Rom', 'city', metaOpts]],
    ['marks.load', () => src.marks!.load('n', key), loadItemFavoritesFor, [S, 'n', key]],
    ['marks.toggle', () => src.marks!.toggle('m-1', 'n', key), toggleItemFavorite, ['m-1', S, 'n', key]],
    ['buckets.list', () => src.buckets!.list('n'), media.listBuckets, [S, 'n']],
    ['buckets.people', () => src.buckets!.people(), loadScopePeople, [S]],
    ['buckets.create', () => src.buckets!.create('n', key, 2, 'A', 'p'), media.createBucket, [S, 'n', key, 2, 'A', 'p']],
    ['buckets.rename', () => src.buckets!.rename('n', key, 2, 'b', 'B'), media.renameBucket, [S, 'n', key, 2, 'b', 'B']],
    ['buckets.remove', () => src.buckets!.remove('n', 'b'), media.deleteBucket, [S, 'n', 'b']],
    ['buckets.move', () => src.buckets!.move('n', ['m-1'], null), media.moveBucketMedia, [S, 'n', ['m-1'], null]],
    ['buckets.trash', () => src.buckets!.trash('m-1'), media.deleteMedia, [S, 'm-1']],
    ['buckets.restore', () => src.buckets!.restore('m-1'), media.restoreMedia, [S, 'm-1']],
  ]
  for (const [name, call, fn, args] of doors) {
    it(`${name} hands its call on with the member's scope`, async () => {
      await call()
      expect(fn).toHaveBeenCalledWith(...args)
    })
  }

  it('uploads through the authed `addMedia` target, grouped by the node as before', () => {
    expect(src.upload!.ready()).toBe(true)
    expect(src.upload!.target('n', key, 2)).toEqual({
      scopeId: S, nodeId: 'n', memoryKey: key, nodeEpoch: 2, atFloor: expect.any(Function), pull: expect.any(Function), reread: expect.any(Function),
    })
    expect(src.batchKey('n')).toBe('n')
  })
})

describe('no door, no right (the invariant every view rests on)', () => {
  const ALL: MemoryRights = {
    ...NO_MEMORY_RIGHTS, navigate: true, download: true, upload: true, deleteMedia: 'any', createMemory: true, createChapter: true,
    editMeta: true, setBanner: true, mark: true, share: true, shareUpload: true, rename: true, delete: true,
    mediaTrash: true, buckets: true,
  }
  const member = memberMemorySource({ scopeId: () => SCOPE })
  // [the right, the door it rests on]
  const rests: [keyof MemoryRights, keyof MemorySource][] = [
    ['upload', 'upload'], ['deleteMedia', 'deleteMedia'], ['editMeta', 'nodeMeta'], ['mark', 'marks'],
    ['mediaTrash', 'trash'], ['buckets', 'buckets'], ['setBanner', 'banner'],
  ]
  for (const [right, door] of rests) {
    it(`${right} needs the source's ${door}`, () => {
      expect(sourceRights(ALL, member)[right], 'granted and carried').toBe(right === 'deleteMedia' ? 'any' : true)
      const without = { ...member, [door]: undefined } as MemorySource
      expect(sourceRights(ALL, without)[right], 'granted, not carried').toBe(right === 'deleteMedia' ? 'none' : false)
      expect(sourceRights({ ...ALL, [right]: right === 'deleteMedia' ? 'none' : false }, member)[right], 'carried, not granted')
        .toBe(right === 'deleteMedia' ? 'none' : false)
    })
  }

  it("a delete keeps its reach only through a door of that reach: no 'any' through a guest's purge, no 'own' through a trash", () => {
    const g = guest(ShareRole.ReadWrite).source
    expect(sourceRights({ ...ALL, deleteMedia: 'any' }, g).deleteMedia).toBe('none')
    expect(sourceRights({ ...ALL, deleteMedia: 'own' }, g).deleteMedia).toBe('own')
    expect(sourceRights({ ...ALL, deleteMedia: 'own' }, member).deleteMedia).toBe('none')
  })

  it('the rights no source door carries pass through unchanged (they are the host\'s dialogs and the tree\'s links)', () => {
    const narrowed = sourceRights(ALL, guest(ShareRole.Read).source)
    for (const k of ['navigate', 'download', 'createMemory', 'createChapter', 'share', 'shareUpload', 'rename', 'delete'] as const) {
      expect(narrowed[k], k).toBe(true)
    }
    for (const k of ['upload', 'editMeta', 'setBanner', 'mark', 'mediaTrash', 'buckets'] as const) expect(narrowed[k], k).toBe(false)
    expect(narrowed.deleteMedia).toBe('none')
  })
})
