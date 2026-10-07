// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'

vi.mock('../../api/client', () => ({
  sharesApi: vi.fn(),
  scopesApi: vi.fn(),
  publicSharesApi: vi.fn(),
  contentApi: vi.fn(),
  tokenApi: vi.fn(),
}))
// The guest's media read and writes are Memories' own client (`share-media`), built in `./api`, not core's.
vi.mock('./api', () => ({ publicShareMediaApi: vi.fn() }))
// Only the scope RE-OPEN a `stale_epoch` retry performs is stubbed; `isStaleEpoch` stays real, because which refusal
// the create retries on is the thing under test.
vi.mock('../spaces/scopes', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../spaces/scopes')>()),
  openScopeById: vi.fn(),
}))

import { createShare, reissueShare, isShareDeleted, type ReissuableShare, resolveShare, createContributorIdentity, addSharePhoto, deleteSharePhoto, listSharesFor, reactivateShare, updateShare, readShareNoteMap, readShareName, readSharePassword, shareChunkProvider, openSharedMemory } from './share'
import { pickLocaleText, derivePasswordToken, resolvePublicShare, ShareUnavailableError, SharePasswordRequiredError } from '../../core/sharing/resolvePublicShare'
// The link builders live in `shareLink.ts` (the UI edge, the only part that needs `window`) — see its header.
import { buildShareLink, recoverShareLink } from './shareLink'
import { unsealMeta, CHUNK_SIZE, type MediaDeriver } from './media'
import { sharesApi, publicSharesApi, contentApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { isStaleEpoch, openScopeById } from '../spaces/scopes'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import type { EpochKeys } from '../spaces/epochs'
import { httpError } from '../../test/httpError'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import { ShareRole, type PublicShare, type SharingApi } from '../../api/generated/sharing'
import type { ShareMediaListing } from '../../api/generated/share-media'
import { fakeApi, stubApi } from '../../test/apiMock'
import { openDropKey } from './mediaKey'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const CHAPTER_ID = '2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e'
const MEDIA_1 = '5c09e000-0000-4000-8000-00000000ae01'
const MEDIA_2 = '5c09e000-0000-4000-8000-00000000ae02'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/ // 256 random bits, base64url — minted by the client (ADR 0072)
/** A fixed share address for tests that seal their own fixtures. */
const ADDR = { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: 'S'.repeat(43) }
/** The scope's epoch keys a member holds: `K_SC` at epochs 0..2, the write key at 2 (ADR 0024). A share's recovery copy
 *  of `K_share` seals under the write key and opens at its own epoch (ADR 0036 point 4). */
const EPOCHS: EpochKeys = { current: 2, keys: new Map([[0, v1.randomBytes(32)], [1, v1.randomBytes(32)], [2, v1.randomBytes(32)]]) }
/** A share with no recovery copy. */
const NO_COPY = { sealedShareKey: null, sealedShareKeyEpoch: null }
/** The address of the share a createShare call minted (the recovery copy of K_share is bound to it). */
const addressOf = (req: { shareId: string }) => ({ scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: req.shareId })

/** Seal a media's listed-metadata tier under a K_mem exactly as the media layer does (bound to scope + media). */
function sealMeta(key: Uint8Array, mediaId: string, meta: object): string {
  return b64std(v1.seal({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId }, key, v1.utf8(JSON.stringify(meta))))
}

/** A node's name sealed under the key its directory entry lives under — its parent's (ADR 0080 Decision 2). */
function nodeName(parentKey: Uint8Array, nodeId: string, name: string): string {
  return b64std(v1.seal({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 }, parentKey, v1.utf8(name)))
}

/** A create endpoint that answers as an honest server does: with the share id the client minted. */
const echoCreate = () => vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
/** The `i`-th create request the curator posted. createShare always seals the recovery copy of K_share, so it is
 *  read here as present — what the copy-reading functions take. */
function posted(create: Mock<SharingApi['createShare']>, i = 0) {
  const req = create.mock.calls[i][0].createShareRequest
  return { ...req, sealedShareKey: req.sealedShareKey!, sealedShareKeyEpoch: req.sealedShareKeyEpoch! }
}
function mockCurator(createNode: Mock<SharingApi['createShare']> = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-1' })) {
  fakeApi(sharesApi, { createShare: createNode })
}
/** A guest's two reads: core's resolve (the link + nodes) and Memories' media read (the rows + uploader keys). */
function mockGuest(resolve: PublicShare, listing: ShareMediaListing = { media: [], identities: [] }) {
  stubApi(publicSharesApi, { resolveShare: resolve })
  return stubApi(publicShareMediaApi, { listShareMedia: listing })
}

beforeEach(() => vi.clearAllMocks())

describe('createShare (curator)', () => {
  it('wraps K_mem under a fresh K_share, seals the name, and returns a fragment-key link (operator-blind)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)

    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom 2026')

    const req = posted(create)
    // the client mints the share id and sends it — the recovery copy of K_share is sealed bound to it
    expect(req.shareId).toMatch(SHARE_ID)
    expect(minted.shareId).toBe(req.shareId)
    // the link the UI assembles carries the share id in the path and K_share ONLY in the #fragment
    expect(buildShareLink(minted.shareId, minted.kShare)).toContain(`/s/${req.shareId}#k=`)
    expect(req.nodeId).toBe(NODE_ID)
    expect(req.role).toBe('READ')
    // the request carries opaque ciphertext only — no plaintext name, no key
    expect(req.wrap).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('Rom 2026')
  })

  it('seals the recovery copy under the write key at its epoch, never under K_mem (ADR 0036 point 4)', async () => {
    const create = echoCreate()
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom')
    const req = posted(create)
    expect(req.sealedShareKeyEpoch).toBe(EPOCHS.current)
    const ctx = { purpose: 'share-key-recovery', ...addressOf(req), epoch: EPOCHS.current } as const
    expect(v1.open(ctx, EPOCHS.keys.get(2)!, fromB64std(req.sealedShareKey))).toEqual(minted.kShare)
    expect(() => v1.open(ctx, kMem, fromB64std(req.sealedShareKey))).toThrow(v1.OpenRefused)
  })

  it('on a 409 stale_epoch re-opens the scope key and re-seals the copy at the new epoch, once', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockRejectedValueOnce(httpError(409, 'stale_epoch')).mockImplementationOnce(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    mockCurator(create)
    const fresh: EpochKeys = { current: 3, keys: new Map([...EPOCHS.keys, [3, v1.randomBytes(32)]]) }
    const k3 = Uint8Array.from(fresh.keys.get(3)!) // the re-opened keys are wiped after the re-seal
    vi.mocked(openScopeById).mockResolvedValueOnce({ epochs: fresh } as unknown as Awaited<ReturnType<typeof openScopeById>>)

    const minted = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom')

    expect(openScopeById).toHaveBeenCalledWith(SCOPE_ID)
    expect(create).toHaveBeenCalledTimes(2)
    const [first, second] = create.mock.calls.map((_, i) => posted(create, i))
    expect(first.shareId).toBe(second.shareId) // the same share, re-sealed — not a second one
    expect(second.sealedShareKeyEpoch).toBe(3)
    const ctx = { purpose: 'share-key-recovery', ...addressOf(second), epoch: 3 } as const
    expect(v1.open(ctx, k3, fromB64std(second.sealedShareKey))).toEqual(minted.kShare)
    expect(fresh.keys.get(3)!.every((b) => b === 0)).toBe(true)
  })

  it('a second stale_epoch is thrown, and any other refusal is never retried', async () => {
    const stale = vi.fn<SharingApi['createShare']>().mockRejectedValue(httpError(409, 'stale_epoch'))
    mockCurator(stale)
    vi.mocked(openScopeById).mockResolvedValue({ epochs: { current: 3, keys: new Map([[3, v1.randomBytes(32)]]) } } as unknown as Awaited<ReturnType<typeof openScopeById>>)
    const second = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom').catch((e: unknown) => e)
    expect(await isStaleEpoch(second)).toBe(true)
    expect(stale).toHaveBeenCalledTimes(2)

    const taken = vi.fn<SharingApi['createShare']>().mockRejectedValue(httpError(409, 'id_taken'))
    mockCurator(taken)
    vi.mocked(openScopeById).mockClear()
    const refused = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom').catch((e: unknown) => e)
    expect(await isCodedError(refused, HttpStatus.conflict, 'id_taken')).toBe(true)
    expect(taken).toHaveBeenCalledTimes(1)
    expect(openScopeById).not.toHaveBeenCalled()
  })
})

describe('resolveShare (anonymous viewer)', () => {
  it('unwraps K_mem from the fragment key, decrypts the name, and keys each media to the right K_mem', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom 2026')
    const { wrap, sealedName } = posted(create)

    // the media's listed metadata is sealed under the Memory's K_mem
    const wrappedMeta = sealMeta(kMem, MEDIA_1, { v: 1, w: 1600, h: 1200 })
    mockGuest({
      role: 'READ',
      passwordRequired: false,
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }],
    }, {
      media: [{ nodeId: NODE_ID, mediaId: MEDIA_1, blobId: 'b1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta }],
      identities: [],
    })

    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))

    expect(resolved.memoryName).toBe('Rom 2026') // decrypted from sealedName with the fragment key
    expect(resolved.scopeId).toBe(SCOPE_ID)
    expect(resolved.items).toHaveLength(1)
    expect(resolved.items[0].scopeId).toBe(SCOPE_ID)
    // the viewer derived the correct K_mem — proof: the media's meta unseals to its geometry
    const meta = unsealMeta(resolved.items[0].scopeId, resolved.items[0].memoryKey, resolved.items[0].item)
    expect(meta).toMatchObject({ w: 1600, h: 1200 })
    // each ref is owned by its listed media, in the role it is listed in
    expect(resolved.items[0].item.original).toMatchObject({ ownerId: MEDIA_1, role: 'ORIGINAL' })
  })

  it('derives a chapter K_mem down the subtree so a chapter media decrypts too (recursive K_mem)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-2' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Trip')
    const { wrap, sealedName } = posted(create)

    // a chapter's K_mem is wrapped under the Memory's K_mem (purpose `content-key`, bound to the chapter + its epoch)
    const chapterKey = v1.randomBytes(32)
    const wrappedContentKey = b64std(
      v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: CHAPTER_ID, epoch: 0, placementVersion: 0 }, kMem, chapterKey),
    )
    const chapterMeta = sealMeta(chapterKey, MEDIA_2, { v: 1, w: 800, h: 600 })
    mockGuest({
      role: 'READ',
      passwordRequired: false,
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0, wrappedContentKey, encryptedName: nodeName(kMem, CHAPTER_ID, 'Day 1') },
      ],
    }, {
      media: [{ nodeId: CHAPTER_ID, mediaId: MEDIA_2, blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: chapterMeta }],
      identities: [],
    })

    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.items).toHaveLength(1)
    // the chapter media's meta unseals only if the viewer walked wrappedContentKey → the chapter's K_mem
    expect(unsealMeta(SCOPE_ID, resolved.items[0].memoryKey, resolved.items[0].item)).toMatchObject({ w: 800, h: 600 })
    // …and it carries the title of the chapter it sits in, opened under the Memory's K_mem (ADR 0080 Decision 2)
    expect(resolved.items[0].chapterName).toBe('Day 1')
  })

  // Bite test (b): a Memory-share guest opens every chapter title it is served — nested ones through the chain it
  // derives — from a resolve that carries no name for the root (its title is `sealedName`).
  it('opens every served chapter title down the chain; the root carries none, a K_SC-sealed one does not open', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-3' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Trip')
    const { wrap, sealedName } = posted(create)
    const NESTED = '5c09e000-0000-4000-8000-00000000ce02'
    const STALE = '5c09e000-0000-4000-8000-00000000ce03'
    const chapterKey = v1.randomBytes(32)
    const contentKey = (kek: Uint8Array, nodeId: string, k = v1.randomBytes(32)) =>
      b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 }, kek, k))
    mockGuest({
      role: 'READ', passwordRequired: false, scopeId: SCOPE_ID, wrap, sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0, wrappedContentKey: contentKey(kMem, CHAPTER_ID, chapterKey), encryptedName: nodeName(kMem, CHAPTER_ID, 'Day 1') },
        { id: NESTED, parentId: CHAPTER_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0, wrappedContentKey: contentKey(chapterKey, NESTED), encryptedName: nodeName(chapterKey, NESTED, 'Morning') },
        // the pre-ADR-0080 entry: a chapter title sealed under a scope key the guest does not hold
        { id: STALE, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0, wrappedContentKey: contentKey(kMem, STALE), encryptedName: nodeName(v1.randomBytes(32), STALE, 'Hidden') },
      ],
    })
    const head = await resolvePublicShare(minted.shareId, b64url(minted.kShare))
    expect(head.share.nodes.find((n) => n.id === NODE_ID)?.encryptedName).toBeUndefined()

    const { chapterNames } = openSharedMemory(head)
    expect(Object.fromEntries(chapterNames)).toEqual({ [CHAPTER_ID]: 'Day 1', [NESTED]: 'Morning' })
  })

  // ADR 0080 Decision 3 / bite test 4, the guest's half: a chapter's name and wrap open at the placement version
  // served WITH them. A chapter renamed to version 2 opens there; the same entry served at version 1 (a replay of the
  // row a rename superseded) opens neither its title nor its key — and nothing below it.
  it('opens a chapter at the placement version it is served with, and nothing at another', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-pv' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Trip')
    const { wrap, sealedName } = posted(create)
    const chapterKey = v1.randomBytes(32)
    const sealedAt = (purpose: 'content-key' | 'node-name', placementVersion: number, payload: Uint8Array) =>
      b64std(v1.seal({ purpose, scopeId: SCOPE_ID, nodeId: CHAPTER_ID, epoch: 0, placementVersion }, kMem, payload))
    const entry = { wrappedContentKey: sealedAt('content-key', 2, chapterKey), encryptedName: sealedAt('node-name', 2, v1.utf8('Tag 2')) }
    const servedAt = async (placementVersion: number) => {
      mockGuest({
        role: 'READ', passwordRequired: false, scopeId: SCOPE_ID, wrap, sealedName,
        nodes: [
          { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
          { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, placementVersion, ...entry },
        ],
      })
      return openSharedMemory(await resolvePublicShare(minted.shareId, b64url(minted.kShare))).chapterNames
    }

    expect(Object.fromEntries(await servedAt(2))).toEqual({ [CHAPTER_ID]: 'Tag 2' }) // control
    expect(Object.fromEntries(await servedAt(1))).toEqual({})
  })

  it('drops a chapter whose K_mem wrap was sealed for another node (a moved wrap yields no key)', async () => {
    const create = echoCreate()
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Trip')
    const { wrap, sealedName } = posted(create)
    const chapterKey = v1.randomBytes(32)
    const OTHER_CHAPTER = '5c09e000-0000-4000-8000-00000000ce99'
    // sealed for OTHER_CHAPTER, served on CHAPTER_ID's row
    const movedWrap = b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: OTHER_CHAPTER, epoch: 0, placementVersion: 0 }, kMem, chapterKey))
    mockGuest({
      role: 'READ',
      passwordRequired: false,
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0, wrappedContentKey: movedWrap },
      ],
    }, {
      media: [{ nodeId: CHAPTER_ID, mediaId: MEDIA_2, blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: null }],
      identities: [],
    })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.items).toHaveLength(0)
  })

  it('refuses an unlocked share that carries no scope (every seal it serves is bound to one)', async () => {
    const create = echoCreate()
    mockCurator(create)
    const minted = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom')
    const { wrap, sealedName } = posted(create)
    mockGuest({ role: 'READ', passwordRequired: false, wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }] })
    await expect(resolveShare(minted.shareId, b64url(minted.kShare))).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('rejects a wrong fragment key — the id alone is inert (E1)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-3' })
    mockCurator(create)
    await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom')
    const { wrap, sealedName } = posted(create)
    mockGuest({ role: 'READ', passwordRequired: false, scopeId: SCOPE_ID, wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }] })

    // a DIFFERENT (well-formed) 32-byte key can't open the wrap
    const wrongKey = 'A'.repeat(43) // 32 zero-ish bytes worth of base64url
    await expect(resolveShare('share-3', wrongKey)).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('maps a 404 (revoked/expired/unknown) to ShareUnavailableError', async () => {
    fakeApi(publicSharesApi, { resolveShare: () => Promise.reject(new Error('404')) })
    await expect(resolveShare('nope', 'A'.repeat(43))).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('reads the media from Memories\' own read, not the resolve, and refuses when that read is refused', async () => {
    const create = echoCreate()
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom')
    const { wrap, sealedName } = posted(create)
    const head = { role: 'READ' as const, passwordRequired: false, scopeId: SCOPE_ID, wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }] }
    const row = { nodeId: NODE_ID, mediaId: MEDIA_1, blobId: 'b1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: null }
    const { listShareMedia } = mockGuest(head, { media: [row], identities: [] })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.items.map((i) => i.item.mediaId)).toEqual([MEDIA_1])
    expect(listShareMedia).toHaveBeenCalledWith({ shareId: minted.shareId, xSharePassword: undefined })

    // the link went away between the resolve and the media read: the share is unavailable, not an empty Memory
    fakeApi(publicShareMediaApi, { listShareMedia: () => Promise.reject(new Error('404')) })
    await expect(resolveShare(minted.shareId, b64url(minted.kShare))).rejects.toBeInstanceOf(ShareUnavailableError)
  })
})

describe('createContributorIdentity (cr-0101)', () => {
  it('seals the display name under K_mem (operator-blind) and returns the anonymous session', async () => {
    const { createShareIdentity: create } = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'id-1', contributorToken: 'tok-1' } })
    const kMem = v1.randomBytes(32)

    const shareId = ADDR.shareId
    const session = await createContributorIdentity(shareId, SCOPE_ID, { kind: 'read', memoryKey: kMem }, 'Oma', b64url(v1.randomBytes(32)))

    const req = create.mock.calls[0][0].createShareIdentityRequest
    // the client mints the identity id (the name is bound to it) and the session carries exactly that id
    expect(req.identityId).toMatch(UUID)
    expect(session).toEqual({ identityId: req.identityId, contributorToken: 'tok-1' })
    // the name crosses the wire only as opaque ciphertext — never in clear
    expect(req.sealedDisplayName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedDisplayName).not.toContain('Oma')
    // and it unwraps back under K_mem (proves the seal is correct + operator-blind), bound to (scope, share, identity)
    const ctx = { purpose: 'share-identity-name', scopeId: SCOPE_ID, shareId, identityId: req.identityId } as const
    const opened = new TextDecoder().decode(v1.open(ctx, kMem, fromB64std(req.sealedDisplayName)))
    expect(opened).toBe('Oma')
    expect(() => v1.open({ ...ctx, identityId: crypto.randomUUID() }, kMem, fromB64std(req.sealedDisplayName))).toThrow(v1.OpenRefused)
  })

  // ADR 0076: the share stores the hash of a token only the whole link derives — so its id alone names no guest.
  it('proves the link key: the token it presents hashes to the verifier the share was created with, and only K_share derives it', async () => {
    const createShareCall = echoCreate()
    mockCurator(createShareCall)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom')
    const { writeVerifier } = posted(createShareCall)
    const nameGuest = vi.fn<SharingApi['createShareIdentity']>().mockResolvedValue({ identityId: 'ignored', contributorToken: 'tok-1' })
    fakeApi(publicSharesApi, { createShareIdentity: nameGuest })
    const presented = async (fragmentKey: string): Promise<string> => {
      nameGuest.mockClear()
      await createContributorIdentity(minted.shareId, SCOPE_ID, { kind: 'read', memoryKey: kMem }, 'Oma', fragmentKey)
      return nameGuest.mock.calls[0][0].xShareWrite!
    }
    const hashOf = (token: string) => b64std(v1.sha256Digest(fromB64std(token)))

    const token = await presented(b64url(minted.kShare))
    expect(hashOf(token)).toBe(writeVerifier) // what the server compares
    expect(token).not.toBe(writeVerifier) // the share stores the hash, never the token
    // the same id with another K_share is another proof: nothing about it follows from the id alone
    expect(hashOf(await presented(b64url(v1.randomBytes(32))))).not.toBe(writeVerifier)
  })
})

describe('shared-scope management (cr-0104)', () => {
  it('listSharesFor scopes by nodeId server-side and threads each row’s `mine`, `reissueRequired`, `deletable`, `newGuestsRefused` and `writeVerifierMissing` flags', async () => {
    const { listShares: list } = stubApi(sharesApi, {
      listShares: [
        { shareId: 's1', scopeId: SCOPE_ID, nodeId: NODE_ID, role: 'READ', sealedName: 'x', enabled: true, mine: true, reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, createdAt: new Date('2026-07-07T12:00:00Z'), expiresAt: null },
        { shareId: 's2', scopeId: SCOPE_ID, nodeId: NODE_ID, role: 'READ', sealedName: 'x', enabled: false, mine: false, reissueRequired: true, deletable: false, newGuestsRefused: true, writeVerifierMissing: true, createdAt: new Date('2026-07-06T12:00:00Z'), expiresAt: null },
      ],
    })

    const rows = await listSharesFor(NODE_ID)

    expect(list).toHaveBeenCalledWith({ nodeId: NODE_ID }) // server-scoped to the Memory, not a client filter
    // co-members' shares come back too (mine=false), disabled ones included — newest first
    expect(rows.map((r) => [r.shareId, r.mine, r.enabled, r.reissueRequired, r.deletable, r.newGuestsRefused, r.writeVerifierMissing])).toEqual([
      ['s1', true, true, false, true, false, false],
      ['s2', false, false, true, false, true, true], // the server's verdicts (ADR 0075, ADR 0029 §7, ADR 0076), carried as they came
    ])
    expect(rows.every((r) => r.scopeId === SCOPE_ID)).toBe(true) // the recovery copy binds it — carried per row
  })

  it('reactivateShare adopts a disabled share via the member-gated endpoint', async () => {
    const { reactivateShare: reactivate } = stubApi(sharesApi, { reactivateShare: undefined })

    await reactivateShare('share-9')

    expect(reactivate).toHaveBeenCalledWith({ shareId: 'share-9' })
  })
})

describe('addSharePhoto — the contributor transport signs each next window (ADR 0013 §2)', () => {
  it('asks the share-gated window endpoint for the rest of the blob, with the token and the password', async () => {
    // A server window of 2 chunks and a 3-chunk original: the third URL can only come from a window request, and
    // that request must carry the link's whole gate — it is where a revoked link stops the upload.
    const puts = (uploadId: string, from: number, to: number) =>
      Array.from({ length: to - from }, (_, k) => ({ index: from + k, url: `mem://${uploadId}/${from + k}` }))
    const startShareBlobUploads = vi.fn<SharingApi['startShareBlobUploads']>(async ({ shareBlobUploadsRequest }) => ({
      results: shareBlobUploadsRequest.items.map((item, i) => ({
        uploadId: `up-${i}`, presignedPuts: puts(`up-${i}`, 0, Math.min(2, item.chunkCount)), error: null,
      })),
    }))
    const api = fakeApi(publicSharesApi, {
      startShareBlobUploads,
      presignShareBlobUploadWindows: async ({ shareBlobUploadWindowsRequest }) => ({
        results: shareBlobUploadWindowsRequest.items.map(({ uploadId, fromChunk }) => ({ presignedPuts: puts(uploadId, fromChunk!, 3), error: null })),
      }),
    })
    const media = fakeApi(publicShareMediaApi, {
      // An honest server stores the media and its original under the ids the client minted, and echoes them.
      completeShareMedia: async ({ shareMediaBatchRequest }) => ({
        results: [{
          blobId: startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items[0].blobId,
          mediaId: shareMediaBatchRequest.items[0].mediaId,
          error: null,
        }],
      }),
    })
    const putUrls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { putUrls.push(url); return { ok: true, status: 200 } as Response }))
    const noTiers = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn() } as unknown as MediaDeriver

    const done = await addSharePhoto(
      { identityId: 'id-1', contributorToken: 'tok-1', shareId: 'share-1', scopeId: SCOPE_ID, sealKey: v1.randomBytes(32), pwToken: 'pw-1' },
      { blob: new Blob([new Uint8Array(2 * CHUNK_SIZE + 1)]), mediaType: 'application/pdf' },
      noTiers,
    )

    const [minted] = api.startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items
    expect(done).toEqual({ blobId: minted.blobId, mediaId: minted.ownerId })
    expect(api.presignShareBlobUploadWindows).toHaveBeenCalledOnce()
    expect(api.presignShareBlobUploadWindows).toHaveBeenCalledWith({
      shareId: 'share-1',
      shareBlobUploadWindowsRequest: { contributorToken: 'tok-1', items: [{ uploadId: 'up-0', fromChunk: 2 }] },
      xSharePassword: 'pw-1',
    })
    expect(putUrls.sort()).toEqual(['mem://up-0/0', 'mem://up-0/1', 'mem://up-0/2'])
    // the blob is declared with the ids its wrap is bound to, and completed for the same client-minted media
    const [declared] = api.startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items
    expect(declared).toMatchObject({ blobId: expect.stringMatching(UUID), ownerId: expect.stringMatching(UUID), role: 'ORIGINAL' })
    expect(media.completeShareMedia.mock.calls[0][0].shareMediaBatchRequest.items[0].mediaId).toBe(declared.ownerId)
    vi.unstubAllGlobals()
  })
})

describe('deleteSharePhoto (cr-0102 uploader delete-own)', () => {
  it('calls the token-gated delete endpoint with the contributor token in the header', async () => {
    const { deleteShareMedia: del } = stubApi(publicShareMediaApi, { deleteShareMedia: undefined })

    await deleteSharePhoto('share-1', 'tok-1', 'media-9')

    expect(del).toHaveBeenCalledWith({ shareId: 'share-1', mediaId: 'media-9', xContributorToken: 'tok-1' })
  })
})

describe('share note + name (cr-0115 / cr-0116)', () => {
  it('seals an optional per-locale note + a recoverable K_share; the viewer reads the right locale', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-n1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)

    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom 2026', undefined, null, { de: 'Viel Spaß!', en: 'Enjoy our trip!' })
    const req = posted(create)
    // all ciphertexts are opaque base64; neither locale's plaintext appears in the request
    expect(req.sealedNote).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedShareKey).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('Enjoy our trip')
    expect(JSON.stringify(req)).not.toContain('Viel Spa')

    // the anonymous viewer opens the note map with the fragment key, then picks its locale
    mockGuest({
      role: 'READ', passwordRequired: false, scopeId: SCOPE_ID, wrap: req.wrap, sealedName: req.sealedName, sealedNote: req.sealedNote,
      nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }],
    })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(pickLocaleText(resolved.noteByLocale, 'de')).toBe('Viel Spaß!')
    expect(pickLocaleText(resolved.noteByLocale, 'en')).toBe('Enjoy our trip!')
    expect(pickLocaleText(resolved.noteByLocale, 'fr')).toBe('Enjoy our trip!') // no fr → falls back to en
  })

  it('edits the note + name in place: recovers K_share via K_SC(epoch), re-seals both, PATCHes (shareId unchanged)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-n2' })
    const update = vi.fn<SharingApi['updateShare']>().mockResolvedValue(undefined)
    fakeApi(sharesApi, { createShare: create, updateShare: update })
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', undefined, null, { en: 'v1 note' }, 'For friends')
    const req = posted(create)
    const share = addressOf(req)

    await updateShare(share, EPOCHS, req, { en: 'v2 note' }, 'For Grandma', null)
    const patch = update.mock.calls[0][0]
    expect(patch.shareId).toBe(req.shareId)
    // both ciphertexts round-trip to the new values via the recovered K_share
    expect(readShareNoteMap(EPOCHS, share, req, patch.updateShareRequest.sealedNote!)).toEqual({ en: 'v2 note' })
    expect(readShareName(EPOCHS, share, req, patch.updateShareRequest.sealedShareName!)).toBe('For Grandma')
    // the recovery copy is bound to its share: listed on another share's row it recovers nothing (ADR 0072)
    const elsewhere = { ...share, shareId: ADDR.shareId }
    expect(readShareName(EPOCHS, elsewhere, req, patch.updateShareRequest.sealedShareName!)).toBe('')
    await expect(updateShare(elsewhere, EPOCHS, req, { en: 'x' }, 'x', null)).rejects.toBeInstanceOf(v1.OpenRefused)
    // …and to its epoch: relabelled as another epoch it opens under neither key (the right key, the wrong label)
    const relabelled = { ...req, sealedShareKeyEpoch: 1 }
    await expect(updateShare(share, EPOCHS, relabelled, { en: 'x' }, 'x', null)).rejects.toBeInstanceOf(v1.OpenRefused)
  })

  it('clears note + name with blank edits (PATCH nulls)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-n3' })
    const update = vi.fn<SharingApi['updateShare']>().mockResolvedValue(undefined)
    fakeApi(sharesApi, { createShare: create, updateShare: update })
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', undefined, null, { en: 'note' }, 'name')
    const req = posted(create)

    await updateShare(addressOf(req), EPOCHS, req, { en: '  ' }, '   ', null)
    expect(update.mock.calls[0][0].updateShareRequest.sealedNote).toBeNull()
    expect(update.mock.calls[0][0].updateShareRequest.sealedShareName).toBeNull()
  })

  it('seals + reads the private share name (management-only, never on the viewer resolve)', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-n5' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', undefined, null, {}, 'For Grandma')
    const req = posted(create)
    expect(req.sealedShareName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('For Grandma')
    expect(readShareName(EPOCHS, addressOf(req), req, req.sealedShareName!)).toBe('For Grandma')
  })

  it('readShareNoteMap / readShareName return empty when there is no ciphertext or recoverable key', () => {
    const copy = { sealedShareKey: 'x', sealedShareKeyEpoch: 0 }
    expect(readShareNoteMap(EPOCHS, ADDR, NO_COPY, 'x')).toEqual({})
    expect(readShareNoteMap(EPOCHS, ADDR, copy, null)).toEqual({})
    expect(readShareName(EPOCHS, ADDR, NO_COPY, 'x')).toBe('')
    expect(readShareName(EPOCHS, ADDR, copy, null)).toBe('')
  })

  it('recoverShareLink rebuilds the full link (with the #k= fragment) for any existing share', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-n4' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', undefined, null, {})
    const req = posted(create)

    // recovering K_share via K_SC(epoch) reconstructs exactly the minted link — so any old link is re-copyable
    expect(recoverShareLink(addressOf(req), EPOCHS, req)).toBe(buildShareLink(minted.shareId, minted.kShare))
    // a share without a recoverable key → null (falls back to the fresh link in the UI)
    expect(recoverShareLink(addressOf(req), EPOCHS, NO_COPY)).toBeNull()
    // a recovery copy served on another share's row rebuilds no link (bound to its share, ADR 0072)
    expect(recoverShareLink(ADDR, EPOCHS, req)).toBeNull()
  })
})

// The share-password gate runs Argon2id at production params inside the code under test — `createShare(…,
// password)` and `derivePasswordToken` take no params argument, and giving them one for a test's benefit is
// exactly the affordance we don't add. So the cost is inherent and the ceiling is stated here, on this block
// only. See packages/crypto/src/testArgon.ts.
describe('password protection (cr-0118)', () => {
  it('createShare with a password sends the gate fields; the verifier = SHA-256(Argon2id(pw, salt))', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-p1' })
    mockCurator(create)
    await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom', undefined, null, {}, '', 'hunter2')
    const req = posted(create)
    expect(req.passwordRequired).toBe(true)
    expect(req.pwSalt).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    // the stored verifier must equal SHA-256(Argon2id(pw, salt)) so the server accepts the right password's token
    expect(req.pwVerifier).toBe(b64std(v1.sha256Digest(v1.sharesPwToken('hunter2', fromB64std(req.pwSalt!)))))
    // the raw password never travels in the clear
    expect(JSON.stringify(req)).not.toContain('hunter2')
  })

  it('derivePasswordToken is deterministic for the same password + salt', async () => {
    const salt = b64std(v1.randomBytes(16))
    expect(await derivePasswordToken('pw', salt)).toBe(await derivePasswordToken('pw', salt))
  })

  it('readSharePassword reveals the plaintext via the K_SC(epoch)-recovered K_share', () => {
    const kShare = v1.randomBytes(32)
    // a copy sealed at epoch 1 — before a rotation to 2 — still opens: the member holds the whole epoch chain
    const copy = { sealedShareKey: b64std(v1.seal({ purpose: 'share-key-recovery', ...ADDR, epoch: 1 }, EPOCHS.keys.get(1)!, kShare)), sealedShareKeyEpoch: 1 }
    const sealedPassword = b64std(v1.seal({ purpose: 'share-password' }, kShare, v1.utf8('s3cret')))
    expect(readSharePassword(EPOCHS, ADDR, copy, sealedPassword)).toBe('s3cret')
    expect(readSharePassword(EPOCHS, ADDR, NO_COPY, sealedPassword)).toBe('') // no recoverable key → ''
    // the recovery copy is bound to its share — addressed as another share, nothing is revealed
    expect(readSharePassword(EPOCHS, { ...ADDR, shareId: 'T'.repeat(43) }, copy, sealedPassword)).toBe('')
    // a copy sealed under K_mem, as before ADR 0036 point 4, degrades to nothing rather than refusing the list
    const kMem = v1.randomBytes(32)
    const legacy = { sealedShareKey: b64std(v1.seal({ purpose: 'share-key-recovery', ...ADDR, epoch: 1 }, kMem, kShare)), sealedShareKeyEpoch: 1 }
    expect(readSharePassword(EPOCHS, ADDR, legacy, sealedPassword)).toBe('')
  })

  it('resolveShare throws SharePasswordRequiredError on a locked shell, then unlocks with the token', async () => {
    const create = vi.fn<SharingApi['createShare']>().mockResolvedValue({ shareId: 'share-p2' })
    mockCurator(create)
    const minted = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'Rom', undefined, null, {}, '', 'hunter2')
    const { wrap, sealedName } = posted(create)
    const resolveMock = vi.fn<SharingApi['resolveShare']>()
      .mockResolvedValueOnce({ passwordRequired: true, pwSalt: 'c2FsdA==', role: 'READ', nodes: [] })
      .mockResolvedValueOnce({
        passwordRequired: true, pwSalt: 'c2FsdA==', role: 'READ', scopeId: SCOPE_ID, wrap, sealedName,
        nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }],
      })
    fakeApi(publicSharesApi, { resolveShare: resolveMock })
    const { listShareMedia } = stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [] } })
    const fragment = b64url(minted.kShare)
    await expect(resolveShare('share-p2', fragment)).rejects.toBeInstanceOf(SharePasswordRequiredError)
    expect(listShareMedia).not.toHaveBeenCalled() // a locked link's media are not asked for
    const resolved = await resolveShare('share-p2', fragment, 'token')
    expect(resolved.memoryName).toBe('Rom')
    // the media read carries the same guest token as the resolve
    expect(listShareMedia).toHaveBeenCalledWith({ shareId: 'share-p2', xSharePassword: 'token' })
  })
}, PRODUCTION_ARGON_TIMEOUT_MS)

// W1 (ADR 0036 point 4): a READ or READ_WRITE link hands its holder K_mem — and, with a database dump, every
// other share's row of the same Memory, share id included. The recovery copy of each sibling's K_share must open
// under none of the keys that link yields, or the dump reads that share's password, label and note, and rebuilds
// its link. Runs the REAL create path, so a regression that seals the copy under the Memory key again reddens here.
describe('a link opens nothing of a sibling share (W1)', () => {
  it("neither the link's K_mem nor its own K_share opens another share's recovery copy; a member's key does", async () => {
    const create = echoCreate()
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    // B — the sibling with a password, a private label and a note; A — the viewer link the attacker holds.
    await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', undefined, null, { en: 'note for B' }, 'For Grandma', 'correct horse battery')
    const b = posted(create)
    const a = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', ShareRole.Read)
    const aReq = posted(create, 1)
    const linkKmem = v1.open({ purpose: 'share-key' }, a.kShare, fromB64std(aReq.wrap!)) // what link A hands over
    const bContext = { purpose: 'share-key-recovery', scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: b.shareId, epoch: b.sealedShareKeyEpoch } as const

    for (const key of [linkKmem, a.kShare]) {
      expect(() => v1.open(bContext, key, fromB64std(b.sealedShareKey))).toThrow(v1.OpenRefused)
    }
    // Positive control, same predicate: a member of the scope opens B's copy at its epoch and reveals its password.
    const kShareB = v1.open(bContext, EPOCHS.keys.get(b.sealedShareKeyEpoch)!, fromB64std(b.sealedShareKey))
    expect(new TextDecoder().decode(v1.open({ purpose: 'share-password' }, kShareB, fromB64std(b.sealedPassword!)))).toBe('correct horse battery')
    expect(readSharePassword(EPOCHS, addressOf(b), b, b.sealedPassword!)).toBe('correct horse battery')
  })
}, PRODUCTION_ARGON_TIMEOUT_MS)

// W1 (ADR 0075): a re-issue carries the old share's settings under a FRESH K_share. The old K_share is exactly what a
// departed member may hold, so it must open nothing the new share carries. Through the real reissueShare, capturing
// the request it posts.
describe('reissueShare (ADR 0075, W1)', () => {
  /** A share minted through the real create path, returned the way the manage list serves it, plus its K_share. */
  async function oldShare(kMem: Uint8Array, role: ShareRole = ShareRole.Read, password = 'correct horse battery') {
    const create = echoCreate()
    const reissue = vi.fn<SharingApi['reissueShare']>(async ({ reissueShareRequest }) => ({ shareId: reissueShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create, reissueShare: reissue })
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, EPOCHS, 'Rom', role, null, { en: 'note for B' }, 'For Grandma', password)
    const c = posted(create, create.mock.calls.length - 1)
    const old: ReissuableShare = {
      ...addressOf(c), role, expiresAt: null, sealedShareKey: c.sealedShareKey, sealedShareKeyEpoch: c.sealedShareKeyEpoch,
      sealedNote: c.sealedNote ?? null, sealedShareName: c.sealedShareName ?? null,
      passwordRequired: !!c.passwordRequired, sealedPassword: c.sealedPassword ?? null,
    }
    return { old, oldKey: minted.kShare, reissue }
  }

  it('re-seals note, label and password under the new K_share, and the old K_share opens none of it', async () => {
    const kMem = v1.randomBytes(32)
    const { old, oldKey, reissue } = await oldShare(kMem)

    const minted = await reissueShare(old, kMem, EPOCHS, 'Rom')

    expect(reissue).toHaveBeenCalledTimes(1)
    expect(reissue.mock.calls[0][0].shareId).toBe(old.shareId) // the share it replaces
    const r = reissue.mock.calls[0][0].reissueShareRequest
    expect(r.shareId).toMatch(SHARE_ID)
    expect(r.shareId).not.toBe(old.shareId)
    expect(minted.shareId).toBe(r.shareId)
    const dec = (b: Uint8Array) => new TextDecoder().decode(b)
    // the settings open under the NEW key
    expect(JSON.parse(dec(v1.open({ purpose: 'share-note' }, minted.kShare, fromB64std(r.sealedNote!))))).toEqual({ en: 'note for B' })
    expect(dec(v1.open({ purpose: 'share-name' }, minted.kShare, fromB64std(r.sealedShareName!)))).toBe('For Grandma')
    expect(dec(v1.open({ purpose: 'share-password' }, minted.kShare, fromB64std(r.sealedPassword!)))).toBe('correct horse battery')
    expect(r.pwVerifier).toBe(b64std(v1.sha256Digest(v1.sharesPwToken('correct horse battery', fromB64std(r.pwSalt!)))))
    // the write proof is the NEW link's: its key and its id (ADR 0076)
    expect(r.writeVerifier).toBe(b64std(v1.sha256Digest(v1.shareWriteToken(minted.kShare, r.shareId))))
    // the recovery copy is at the write epoch, bound to the NEW share id
    expect(r.sealedShareKeyEpoch).toBe(EPOCHS.current)
    const ctx = { purpose: 'share-key-recovery', scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: r.shareId, epoch: EPOCHS.current } as const
    expect(v1.open(ctx, EPOCHS.keys.get(EPOCHS.current)!, fromB64std(r.sealedShareKey))).toEqual(minted.kShare)
    // the old K_share — what a departed member may hold — opens none of the new fields
    const fields: [Parameters<typeof v1.open>[0], string][] = [
      [{ purpose: 'share-note' }, r.sealedNote!], [{ purpose: 'share-name' }, r.sealedShareName!],
      [{ purpose: 'share-password' }, r.sealedPassword!], [{ purpose: 'share-key' }, r.wrap!],
      [{ purpose: 'share-memory-name' }, r.sealedName],
    ]
    for (const [ctx, field] of fields) {
      expect(() => v1.open(ctx, oldKey, fromB64std(field))).toThrow(v1.OpenRefused)
    }
    // positive control: a member holding the epoch keys reveals the new password through the new copy
    expect(readSharePassword(EPOCHS, addressOf(r), r, r.sealedPassword!)).toBe('correct horse battery')
  })

  it('a WRITE_ONLY re-issue seals the Memory’s existing drop public key and carries no wrap and no private key', async () => {
    const kMem = v1.randomBytes(32)
    const drop = v1.generateDropKey()
    const sealedDropPrivateKey = b64std(v1.seal({ purpose: 'drop-private-key', scopeId: SCOPE_ID, nodeId: NODE_ID }, kMem, v1.serializeDropKey(drop)))
    stubApi(contentApi, {
      listNodes: [{ id: NODE_ID, epoch: 0, parentId: null, kind: 'MEMORY', encryptedName: 'AQ==', slugToken: 'AQ==', orderIndex: 0, wrappedContentKey: 'AQ==', sealedDropPrivateKey, metaVersion: 0, placementVersion: 0 }],
    })
    const { old, reissue } = await oldShare(kMem, ShareRole.WriteOnly, '')

    const minted = await reissueShare(old, kMem, EPOCHS, 'Rom')

    const r = reissue.mock.calls[0][0].reissueShareRequest
    expect(r.wrap).toBeUndefined()
    expect(r).not.toHaveProperty('sealedDropPrivateKey')
    expect(v1.open({ purpose: 'drop-public-key' }, minted.kShare, fromB64std(r.sealedDropPublicKey!)))
      .toEqual(v1.serializeHybridPublic(v1.dropKeyPublic(drop)))
  })

  it('refuses to carry a password it cannot read rather than dropping the protection', async () => {
    const kMem = v1.randomBytes(32)
    const { old, reissue } = await oldShare(kMem)
    await expect(reissueShare({ ...old, sealedPassword: null }, kMem, EPOCHS, 'Rom')).rejects.toThrow('share_password_not_recoverable')
    expect(reissue).not.toHaveBeenCalled()
  })

  // Inbox #252: a share is re-issued at most once. The loser of two renews — or a renew after a delete — is
  // `409 share_deleted`, and it is never retried: a retry under a fresh id would be refused the same way.
  it('a 409 share_deleted is thrown as is and never retried; another 409 is not it', async () => {
    const kMem = v1.randomBytes(32)
    const { old, reissue } = await oldShare(kMem)
    reissue.mockReset().mockRejectedValue(httpError(409, 'share_deleted'))
    const lost = await reissueShare(old, kMem, EPOCHS, 'Rom').catch((e: unknown) => e)
    expect(await isShareDeleted(lost)).toBe(true)
    expect(reissue).toHaveBeenCalledTimes(1)
    expect(openScopeById).not.toHaveBeenCalled()

    expect(await isShareDeleted(httpError(409, 'id_taken'))).toBe(false)
    expect(await isShareDeleted(httpError(404, 'share_deleted'))).toBe(false)
  })

}, PRODUCTION_ARGON_TIMEOUT_MS)

describe('shareChunkProvider (Slice 2 SW range seam for the anonymous viewer, ADR 0047)', () => {
  // A HARDCODED blob-key context (== media.ts blobKeyContext): the viewer binds the wrap to the listed media + role.
  const BLOB = { blobId: '5c09e000-0000-4000-8000-00000000bb01', ownerId: MEDIA_1, role: 'ORIGINAL' as const }

  it('presigns via the gated share endpoint and opens exactly the requested chunk to plaintext', async () => {
    const memoryKey = v1.randomBytes(32)
    const blobKey = v1.randomBytes(32)
    const chunks = [new Uint8Array([10, 20, 30]), new Uint8Array([40, 50])]
    const frames = chunks.map((pt, i) => v1.sealFrame(blobKey, i, chunks.length, pt, v1.randomBytes(12)))
    const wrappedBlobKey = b64std(v1.seal({ purpose: 'blob-key', scopeId: SCOPE_ID, ...BLOB }, memoryKey, blobKey))
    const byteSize = frames.reduce((n, f) => n + f.length, 0)
    const ref = { ...BLOB, chunkCount: chunks.length, byteSize, wrappedBlobKey }
    const si = { item: { mediaId: MEDIA_1, original: ref, derivatives: [] }, memoryKey, scopeId: SCOPE_ID, chapterName: null }

    const { resolveShareBlob } = stubApi(publicSharesApi, {
      resolveShareBlob: { presignedGets: frames.map((_, i) => ({ index: i, url: `u/${i}` })) },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const i = Number(/^u\/(\d+)$/.exec(url)![1])
        return { ok: true, status: 200, arrayBuffer: async () => frames[i].buffer } as unknown as Response
      }),
    )

    const provider = shareChunkProvider('share-1', si, ref, 'pw-token')
    expect(provider.chunkCount).toBe(2)
    expect(Array.from(await provider.getPlaintextChunk(1))).toEqual([40, 50])
    // the anonymous viewer's gated presign was used, forwarding the guest password token
    expect(resolveShareBlob).toHaveBeenCalledWith({ shareId: 'share-1', blobId: BLOB.blobId, xSharePassword: 'pw-token' })
    // a wrap served for another scope (the one the resolved share names) is refused, not opened
    expect(() => shareChunkProvider('share-1', { ...si, scopeId: crypto.randomUUID() }, ref)).toThrow(v1.OpenRefused)
  })
})

// ADR 0080 Decision 13: a chapter is a share root of its own. Its link wraps the CHAPTER's K_mem (never its Memory's),
// its title is the chapter's name re-sealed under K_share, the server roots the served subtree at the chapter (no
// parent id, no entry of its own), and the same `openSharedMemory` walks it — the chapters below it and their media.
describe('a chapter share (ADR 0080 Decision 13)', () => {
  const NESTED = '5c09e000-0000-4000-8000-00000000ce11'
  const contentKey = (kek: Uint8Array, nodeId: string, k: Uint8Array) =>
    b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 }, kek, k))

  it("a viewer link opens the chapter's own K_mem and title and the chapters below it", async () => {
    const create = echoCreate()
    mockCurator(create)
    const chapterKey = v1.randomBytes(32)
    const nestedKey = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, CHAPTER_ID, chapterKey, EPOCHS, 'Day 1')
    const { wrap, sealedName, nodeId } = posted(create)
    expect(nodeId, 'the share is rooted at the chapter').toBe(CHAPTER_ID)
    expect(v1.open({ purpose: 'share-key' }, minted.kShare, fromB64std(wrap!)), "the wrap carries the chapter's key").toEqual(chapterKey)

    mockGuest({
      role: 'READ', passwordRequired: false, scopeId: SCOPE_ID, wrap, sealedName, rootKind: 'CHAPTER',
      nodes: [
        { id: CHAPTER_ID, parentId: null, kind: 'CHAPTER', epoch: 0 }, // the root: no entry, no parent served
        {
          id: NESTED, parentId: CHAPTER_ID, kind: 'CHAPTER', epoch: 0, placementVersion: 0,
          wrappedContentKey: contentKey(chapterKey, NESTED, nestedKey), encryptedName: nodeName(chapterKey, NESTED, 'Morning'),
        },
      ],
    }, {
      media: [
        { nodeId: CHAPTER_ID, mediaId: MEDIA_1, blobId: 'b1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: sealMeta(chapterKey, MEDIA_1, { v: 1, w: 1600, h: 1200 }) },
        { nodeId: NESTED, mediaId: MEDIA_2, blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: sealMeta(nestedKey, MEDIA_2, { v: 1, w: 800, h: 600 }) },
      ],
      identities: [],
    })

    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.memoryName, "the chapter's title, opened from the share's sealed name").toBe('Day 1')
    expect(resolved.access).toEqual({ kind: 'read', memoryKey: chapterKey })
    const byId = new Map(resolved.items.map((i) => [i.item.mediaId, i]))
    const own = byId.get(MEDIA_1)!
    const nested = byId.get(MEDIA_2)!
    expect(unsealMeta(SCOPE_ID, own.memoryKey, own.item), "the chapter's own media opens").toMatchObject({ w: 1600 })
    expect(own.chapterName, 'a media on the shared root carries no chapter title').toBeNull()
    expect(unsealMeta(SCOPE_ID, nested.memoryKey, nested.item), 'a nested chapter opens down the chain').toMatchObject({ w: 800 })
    expect(nested.chapterName).toBe('Morning')
  })

  it("an uploader link seals the chapter's drop key under the chapter's K_mem, bound to the chapter", async () => {
    const create = echoCreate()
    mockCurator(create)
    const chapterKey = v1.randomBytes(32)
    stubApi(contentApi, {
      listNodes: [{ id: CHAPTER_ID, epoch: 0, parentId: NODE_ID, kind: 'CHAPTER', encryptedName: 'AQ==', slugToken: 'AQ==', orderIndex: 0, wrappedContentKey: 'AQ==', sealedDropPrivateKey: null, metaVersion: 0, placementVersion: 0 }],
    })

    const minted = await createShare(SCOPE_ID, CHAPTER_ID, chapterKey, EPOCHS, 'Day 1', ShareRole.WriteOnly)

    const req = posted(create)
    expect(req.nodeId).toBe(CHAPTER_ID)
    expect(req.wrap, 'an uploader link carries no K_mem wrap').toBeUndefined()
    // the private half opens for a member of the chapter, under its K_mem, bound to the chapter's id …
    const drop = openDropKey(SCOPE_ID, CHAPTER_ID, chapterKey, req.sealedDropPrivateKey!)
    expect(v1.open({ purpose: 'drop-public-key' }, minted.kShare, fromB64std(req.sealedDropPublicKey!)))
      .toEqual(v1.serializeHybridPublic(v1.dropKeyPublic(drop)))
    // … and not as the parent Memory's drop key
    expect(() => openDropKey(SCOPE_ID, NODE_ID, chapterKey, req.sealedDropPrivateKey!)).toThrow(v1.OpenRefused)

    // the guest of that link opens the chapter's drop public key and nothing else
    mockGuest({
      role: 'WRITE_ONLY', passwordRequired: false, scopeId: SCOPE_ID, nodeId: CHAPTER_ID, rootKind: 'CHAPTER',
      sealedName: req.sealedName, sealedDropPublicKey: req.sealedDropPublicKey, nodes: [],
    })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.memoryName).toBe('Day 1')
    expect(resolved.access).toMatchObject({ kind: 'drop', nodeId: CHAPTER_ID })
  })
})
