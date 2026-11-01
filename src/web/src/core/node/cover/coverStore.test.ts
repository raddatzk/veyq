// @vitest-environment happy-dom
// The resolved covers of the nodes on screen (inbox #411): one store for every kind, a member's and a share guest's. What is
// pinned is what a surface relies on: a cover is decrypted once per sealed meta, a REPLACED or REMOVED cover drops its old
// object URL at once (no stale image, no leaked blob), a cover that fails to open is the icon (never a throw), a node whose key
// is not ready is retried, and a guest reads only through the link's own presign.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope, nextTick } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../blob/transport', async (orig) => ({ ...(await orig<object>()), openBlob: vi.fn(), openBlobVia: vi.fn() }))
vi.mock('../../sharing/guestBlob', async (orig) => ({ ...(await orig<object>()), shareChunkUrls: vi.fn(async () => ['https://store.test/c0']) }))

import { createCoverStore, memberCoverStore, resetCovers, useCovers } from './coverStore'
import { FULL_FRAME, uploadedCover } from './coverModel'
import { FOLDER_KIND } from '../kind'
import { registry } from '../../applications/registry'
import { openBlob, openBlobVia } from '../../blob/transport'
import { shareChunkUrls } from '../../sharing/guestBlob'
import { guestAccessOf } from '../../../test/models'
import type { PublicShareHead } from '../../sharing/resolvePublicShare'
import type { GuestAccess } from '../../access'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const key = v1.randomBytes(32)
const sealMeta = (blob: object, nodeId = NODE) => b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId }, key, new TextEncoder().encode(JSON.stringify(blob))))
const planOf = (blobId: string, nodeId = NODE) => ({ blobId, chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: nodeId })
const withCover = (blobId: string, extra: object = {}) => sealMeta({ v: 1, ...extra, cover: uploadedCover(planOf(blobId)) })
const node = (wrappedMeta: string | null, over: Partial<{ id: string; kind: string }> = {}) => ({ id: NODE, kind: FOLDER_KIND, wrappedMeta, ...over })
const keyOf = (): Uint8Array | null => key

let created: string[] = []
let revoked: string[] = []
beforeEach(() => {
  vi.clearAllMocks()
  created = []
  revoked = []
  let n = 0
  Object.assign(URL, {
    createObjectURL: vi.fn(() => (created.push(`blob:cover-${++n}`), `blob:cover-${n}`)),
    revokeObjectURL: vi.fn((u: string) => void revoked.push(u)),
  })
  vi.mocked(openBlob).mockResolvedValue(new Uint8Array([1, 2, 3]))
  // like the real one, it asks its presign (the link's gated read) before it reads
  vi.mocked(openBlobVia).mockImplementation(async (_s, _k, _r, presign) => (await presign({}), new Uint8Array([4, 5, 6])))
  resetCovers()
})
afterEach(() => vi.restoreAllMocks())

const settle = async () => {
  for (let i = 0; i < 6; i++) await nextTick()
  await new Promise((r) => setTimeout(r, 0))
}

describe('the member\'s covers', () => {
  it('opens a folder\'s uploaded cover under the node\'s key once, and holds it with its crop', async () => {
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(withCover('b-1'))], keyOf)
    await settle()

    expect(openBlob).toHaveBeenCalledOnce()
    const [scope, openedWith, ref] = vi.mocked(openBlob).mock.calls[0]
    expect(scope).toBe(SCOPE)
    expect(Array.from(openedWith)).toEqual(Array.from(key))
    expect(ref).toMatchObject({ blobId: 'b-1', ownerId: NODE, role: 'COVER' })
    expect(store.covers.value.get(NODE)).toEqual({ url: 'blob:cover-1', crop: FULL_FRAME })
    expect(store.declared.has(NODE)).toBe(true)

    // the same sealed meta again is a read, not a decrypt
    const same = node(withCover('b-1'))
    store.ensure(SCOPE, [same], keyOf)
    store.ensure(SCOPE, [same], keyOf)
    await settle()
    expect(vi.mocked(openBlob)).toHaveBeenCalledTimes(2) // the first seal, then `same` once - never one per call
  })

  it('a replaced cover drops the old image at once and shows the new one; a removed one shows none', async () => {
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(withCover('b-1'))], keyOf)
    await settle()
    const first = store.covers.value.get(NODE)!.url

    store.ensure(SCOPE, [node(withCover('b-2'))], keyOf)
    await settle()
    expect(store.covers.value.get(NODE)!.url).not.toBe(first)
    expect(revoked, 'the replaced image\'s object URL is revoked').toContain(first)

    const second = store.covers.value.get(NODE)!.url
    store.ensure(SCOPE, [node(sealMeta({ v: 1 }))], keyOf) // the cover field is gone
    await settle()
    expect(store.covers.value.has(NODE), 'the icon returns at once').toBe(false)
    expect(store.declared.has(NODE)).toBe(false)
    expect(revoked).toContain(second)

    store.ensure(SCOPE, [node(null)], keyOf) // and a node that never had meta
    await settle()
    expect(store.covers.value.size).toBe(0)
  })

  it('a cover that does not open is the icon, silently - and still named, so it can be removed', async () => {
    vi.mocked(openBlob).mockRejectedValue(new Error('offline'))
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(withCover('b-1'))], keyOf)
    await settle()
    expect(store.covers.value.size).toBe(0)
    expect(store.declared.has(NODE)).toBe(true)
  })

  it('a node whose key is not ready is left un-tried and resolved by a later pass', async () => {
    const store = createCoverStore(null)
    const n = node(withCover('b-1'))
    store.ensure(SCOPE, [n], () => null)
    await settle()
    expect(openBlob).not.toHaveBeenCalled()
    store.ensure(SCOPE, [n], keyOf)
    await settle()
    expect(store.covers.value.has(NODE)).toBe(true)
    store.ensure(null, [node(withCover('b-9'))], keyOf) // no scope open: nothing
    expect(openBlob).toHaveBeenCalledOnce()
  })

  it('a newer meta wins a race: the slower older resolve neither shows nor leaks its image', async () => {
    let release!: (b: Uint8Array) => void
    vi.mocked(openBlob).mockImplementationOnce(() => new Promise((r) => (release = r)))
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(withCover('slow'))], keyOf)
    store.ensure(SCOPE, [node(withCover('fast'))], keyOf)
    await settle()
    const shown = store.covers.value.get(NODE)!.url
    release(new Uint8Array([9]))
    await settle()
    expect(store.covers.value.get(NODE)!.url).toBe(shown)
    expect(created).toHaveLength(1)
  })

  it('reset revokes every held image (a scope switch, a rotation, sign-out)', async () => {
    memberCoverStore.ensure(SCOPE, [node(withCover('b-1'))], keyOf)
    await settle()
    const url = memberCoverStore.covers.value.get(NODE)!.url
    resetCovers()
    expect(revoked).toContain(url)
    expect(memberCoverStore.covers.value.size).toBe(0)
    expect(memberCoverStore.declared.size).toBe(0)
  })
})

describe('which nodes carry a cover, and how a source core cannot open is read', () => {
  const kindWith = (cover: object | undefined) =>
    vi.spyOn(registry, 'kind').mockReturnValue({ supported: true, kind: 'X', icon: 'times', ...(cover ? { cover } : {}) } as unknown as ReturnType<typeof registry.kind>)

  it('a kind that did not opt in has no cover: nothing is read for it', async () => {
    kindWith(undefined)
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(withCover('b-1'), { kind: 'X' })], keyOf)
    await settle()
    expect(openBlob).not.toHaveBeenCalled()
    expect(store.declared.size).toBe(0)
  })

  it('any other source (an older client\'s photo reference) is the icon: nothing is read for it', async () => {
    kindWith({})
    const store = createCoverStore(null)
    store.ensure(SCOPE, [node(sealMeta({ v: 1, cover: { source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME } }), { kind: 'X' })], keyOf)
    await settle()
    expect(store.covers.value.size).toBe(0)
    expect(store.declared.has(NODE), 'it is still declared, so it can be removed').toBe(true)
    expect(openBlob).not.toHaveBeenCalled()
  })
})

describe('a share guest\'s covers', () => {
  const guest = (o: Partial<GuestAccess> = {}): GuestAccess => guestAccessOf('READ', { shareId: 'sh', head: { scopeId: SCOPE } as PublicShareHead, ...o })

  it('read through the link\'s own gated presign with the guest\'s password token, never the member\'s', async () => {
    const access = guest({ pwToken: 'pw-1' })
    const store = createCoverStore(access)
    store.ensure(SCOPE, [node(withCover('b-1'))], keyOf)
    await settle()
    expect(shareChunkUrls).toHaveBeenCalledWith(access.shareId, 'b-1', 'pw-1')
    expect(openBlob, 'no authenticated presign').not.toHaveBeenCalled()
    const [scope, openedWith, ref, presign] = vi.mocked(openBlobVia).mock.calls[0]
    expect(scope).toBe(SCOPE)
    expect(Array.from(openedWith)).toEqual(Array.from(key))
    expect(ref).toMatchObject({ blobId: 'b-1', ownerId: NODE, role: 'COVER' })
    expect(await presign({})).toEqual(['https://store.test/c0'])
    expect(store.covers.value.has(NODE)).toBe(true)
  })

  it('a page\'s covers are its own and revoked when it unmounts; the member\'s are never touched', async () => {
    const scope = effectScope()
    const covers = scope.run(() => useCovers(guest()))!
    covers.ensure([node(withCover('b-1'))], keyOf)
    await settle()
    expect(covers.covers.value.has(NODE)).toBe(true)
    expect(memberCoverStore.covers.value.size).toBe(0)
    expect(memberCoverStore.declared.size).toBe(0)
  })
})
