// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  sharesApi: vi.fn(),
  publicSharesApi: vi.fn(),
  tokenApi: vi.fn(),
}))

import { createShare, resolveShare, createContributorIdentity, addSharePhoto, deleteSharePhoto, listSharesFor, reactivateShare, updateShare, readShareNoteMap, readShareName, pickLocaleText, derivePasswordToken, readSharePassword, shareChunkProvider, ShareUnavailableError, SharePasswordRequiredError } from './share'
// The link builders live in `shareLink.ts` (the UI edge, the only part that needs `window`) — see its header.
import { buildShareLink, recoverShareLink } from './shareLink'
import { unsealMeta, CHUNK_SIZE, type MediaDeriver } from './media'
import { sharesApi, publicSharesApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const CHAPTER_ID = '2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e'
const MEDIA_1 = '5c09e000-0000-4000-8000-00000000ae01'
const MEDIA_2 = '5c09e000-0000-4000-8000-00000000ae02'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/ // 256 random bits, base64url — minted by the client (ADR 0072)
/** A fixed share address for tests that seal their own fixtures. */
const ADDR = { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: 'S'.repeat(43) }
/** The address of the share a createShare call minted (the recovery copy of K_share is bound to it). */
const addressOf = (req: { shareId: string }) => ({ scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: req.shareId })

/** Seal a media's listed-metadata tier under a K_mem exactly as the media layer does (bound to scope + media). */
function sealMeta(key: Uint8Array, mediaId: string, meta: object): string {
  return b64std(v1.seal({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId }, key, v1.utf8(JSON.stringify(meta))))
}

function mockCurator(createNode = vi.fn().mockResolvedValue({ shareId: 'share-1' })) {
  vi.mocked(sharesApi).mockReturnValue({ createShare: createNode } as unknown as ReturnType<typeof sharesApi>)
}
function mockGuest(resolve: unknown) {
  vi.mocked(publicSharesApi).mockReturnValue({
    resolveShare: vi.fn().mockResolvedValue(resolve),
  } as unknown as ReturnType<typeof publicSharesApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('createShare (curator)', () => {
  it('wraps K_mem under a fresh K_share, seals the name, and returns a fragment-key link (operator-blind)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)

    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom 2026')

    const req = create.mock.calls[0][0].createShareRequest
    // the client mints the share id and sends it — the recovery copy of K_share is sealed bound to it
    expect(req.shareId).toMatch(SHARE_ID)
    expect(minted.shareId).toBe(req.shareId)
    // the link the UI assembles carries the share id in the path and K_share ONLY in the #fragment
    expect(buildShareLink(minted.shareId, minted.kShare)).toContain(`/s/${req.shareId}#k=`)
    expect(req.nodeId).toBe(NODE_ID)
    expect(req.role).toBe('VIEWER')
    // the request carries opaque ciphertext only — no plaintext name, no key
    expect(req.wrap).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('Rom 2026')
  })
})

describe('resolveShare (anonymous viewer)', () => {
  it('unwraps K_mem from the fragment key, decrypts the name, and keys each media to the right K_mem', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom 2026')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest

    // the media's listed metadata is sealed under the Memory's K_mem
    const wrappedMeta = sealMeta(kMem, MEDIA_1, { v: 1, w: 1600, h: 1200 })
    mockGuest({
      role: 'VIEWER',
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }],
      media: [{ nodeId: NODE_ID, mediaId: MEDIA_1, blobId: 'b1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta }],
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
    const create = vi.fn().mockResolvedValue({ shareId: 'share-2' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Trip')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest

    // a chapter's K_mem is wrapped under the Memory's K_mem (purpose `content-key`, bound to the chapter + its epoch)
    const chapterKey = v1.randomBytes(32)
    const wrappedContentKey = b64std(
      v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: CHAPTER_ID, epoch: 0 }, kMem, chapterKey),
    )
    const chapterMeta = sealMeta(chapterKey, MEDIA_2, { v: 1, w: 800, h: 600 })
    mockGuest({
      role: 'VIEWER',
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, wrappedContentKey },
      ],
      media: [{ nodeId: CHAPTER_ID, mediaId: MEDIA_2, blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: chapterMeta }],
    })

    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.items).toHaveLength(1)
    // the chapter media's meta unseals only if the viewer walked wrappedContentKey → the chapter's K_mem
    expect(unsealMeta(SCOPE_ID, resolved.items[0].memoryKey, resolved.items[0].item)).toMatchObject({ w: 800, h: 600 })
  })

  it('drops a chapter whose K_mem wrap was sealed for another node (a moved wrap yields no key)', async () => {
    const create = vi.fn().mockResolvedValue({})
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Trip')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest
    const chapterKey = v1.randomBytes(32)
    const OTHER_CHAPTER = '5c09e000-0000-4000-8000-00000000ce99'
    // sealed for OTHER_CHAPTER, served on CHAPTER_ID's row
    const movedWrap = b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: OTHER_CHAPTER, epoch: 0 }, kMem, chapterKey))
    mockGuest({
      role: 'VIEWER',
      scopeId: SCOPE_ID,
      wrap,
      sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', epoch: 0, wrappedContentKey: movedWrap },
      ],
      media: [{ nodeId: CHAPTER_ID, mediaId: MEDIA_2, blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: null }],
    })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(resolved.items).toHaveLength(0)
  })

  it('refuses an unlocked share that carries no scope (every seal it serves is bound to one)', async () => {
    const create = vi.fn().mockResolvedValue({})
    mockCurator(create)
    const minted = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), 'Rom')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest
    mockGuest({ role: 'VIEWER', wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }], media: [] })
    await expect(resolveShare(minted.shareId, b64url(minted.kShare))).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('rejects a wrong fragment key — the id alone is inert (E1)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-3' })
    mockCurator(create)
    await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), 'Rom')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest
    mockGuest({ role: 'VIEWER', scopeId: SCOPE_ID, wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }], media: [] })

    // a DIFFERENT (well-formed) 32-byte key can't open the wrap
    const wrongKey = 'A'.repeat(43) // 32 zero-ish bytes worth of base64url
    await expect(resolveShare('share-3', wrongKey)).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('maps a 404 (revoked/expired/unknown) to ShareUnavailableError', async () => {
    vi.mocked(publicSharesApi).mockReturnValue({
      resolveShare: vi.fn().mockRejectedValue(new Error('404')),
    } as unknown as ReturnType<typeof publicSharesApi>)
    await expect(resolveShare('nope', 'A'.repeat(43))).rejects.toBeInstanceOf(ShareUnavailableError)
  })
})

describe('createContributorIdentity (cr-0101)', () => {
  it('seals the display name under K_mem (operator-blind) and returns the anonymous session', async () => {
    const create = vi.fn().mockResolvedValue({ identityId: 'id-1', contributorToken: 'tok-1' })
    vi.mocked(publicSharesApi).mockReturnValue({ createShareIdentity: create } as unknown as ReturnType<typeof publicSharesApi>)
    const kMem = v1.randomBytes(32)

    const shareId = ADDR.shareId
    const session = await createContributorIdentity(shareId, SCOPE_ID, { kind: 'read', memoryKey: kMem }, 'Oma')

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
})

describe('shared-scope management (cr-0104)', () => {
  it('listSharesFor scopes by nodeId server-side and threads each row’s `mine` flag', async () => {
    const list = vi.fn().mockResolvedValue([
      { shareId: 's1', scopeId: SCOPE_ID, nodeId: NODE_ID, role: 'VIEWER', sealedName: 'x', enabled: true, mine: true, createdAt: '2026-07-07T12:00:00Z', expiresAt: null },
      { shareId: 's2', scopeId: SCOPE_ID, nodeId: NODE_ID, role: 'VIEWER', sealedName: 'x', enabled: false, mine: false, createdAt: '2026-07-06T12:00:00Z', expiresAt: null },
    ])
    vi.mocked(sharesApi).mockReturnValue({ listShares: list } as unknown as ReturnType<typeof sharesApi>)

    const rows = await listSharesFor(NODE_ID)

    expect(list).toHaveBeenCalledWith({ nodeId: NODE_ID }) // server-scoped to the Memory, not a client filter
    // co-members' shares come back too (mine=false), disabled ones included — newest first
    expect(rows.map((r) => [r.shareId, r.mine, r.enabled])).toEqual([
      ['s1', true, true],
      ['s2', false, false],
    ])
    expect(rows.every((r) => r.scopeId === SCOPE_ID)).toBe(true) // the recovery copy binds it — carried per row
  })

  it('reactivateShare adopts a disabled share via the member-gated endpoint', async () => {
    const reactivate = vi.fn().mockResolvedValue(undefined)
    // NB: wire the mock to the local `reactivate` fn — a bare `{ reactivateShare }` shorthand would resolve to the
    // imported module function under test, so `sharesApi().reactivateShare` would recurse into it (stack overflow).
    vi.mocked(sharesApi).mockReturnValue({ reactivateShare: reactivate } as unknown as ReturnType<typeof sharesApi>)

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
    const api: Record<string, ReturnType<typeof vi.fn>> = {
      startShareBlobUploads: vi.fn(async ({ shareBlobUploadsRequest }: any) => ({
        results: shareBlobUploadsRequest.items.map((item: any, i: number) => ({
          uploadId: `up-${i}`, presignedPuts: puts(`up-${i}`, 0, Math.min(2, item.chunkCount)), error: null,
        })),
      })),
      presignShareBlobUploadWindows: vi.fn(async ({ shareBlobUploadWindowsRequest }: any) => ({
        results: shareBlobUploadWindowsRequest.items.map(({ uploadId, fromChunk }: any) => ({ presignedPuts: puts(uploadId, fromChunk, 3), error: null })),
      })),
      // An honest server stores the media and its original under the ids the client minted, and echoes them.
      completeShareMedia: vi.fn(async ({ shareMediaBatchRequest }: any) => ({
        results: [{
          blobId: api.startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items[0].blobId,
          mediaId: shareMediaBatchRequest.items[0].mediaId,
          error: null,
        }],
      })),
    }
    vi.mocked(publicSharesApi).mockReturnValue(api as unknown as ReturnType<typeof publicSharesApi>)
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
    expect(api.completeShareMedia.mock.calls[0][0].shareMediaBatchRequest.items[0].mediaId).toBe(declared.ownerId)
    vi.unstubAllGlobals()
  })
})

describe('deleteSharePhoto (cr-0102 uploader delete-own)', () => {
  it('calls the token-gated delete endpoint with the contributor token in the header', async () => {
    const del = vi.fn().mockResolvedValue(undefined)
    vi.mocked(publicSharesApi).mockReturnValue({ deleteShareMedia: del } as unknown as ReturnType<typeof publicSharesApi>)

    await deleteSharePhoto('share-1', 'tok-1', 'media-9')

    expect(del).toHaveBeenCalledWith({ shareId: 'share-1', mediaId: 'media-9', xContributorToken: 'tok-1' })
  })
})

describe('share note + name (cr-0115 / cr-0116)', () => {
  it('seals an optional per-locale note + a recoverable K_share; the viewer reads the right locale', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-n1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)

    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom 2026', undefined, null, { de: 'Viel Spaß!', en: 'Enjoy our trip!' })
    const req = create.mock.calls[0][0].createShareRequest
    // all ciphertexts are opaque base64; neither locale's plaintext appears in the request
    expect(req.sealedNote).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedShareKey).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('Enjoy our trip')
    expect(JSON.stringify(req)).not.toContain('Viel Spa')

    // the anonymous viewer opens the note map with the fragment key, then picks its locale
    mockGuest({
      role: 'VIEWER', scopeId: SCOPE_ID, wrap: req.wrap, sealedName: req.sealedName, sealedNote: req.sealedNote,
      nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }], media: [],
    })
    const resolved = await resolveShare(minted.shareId, b64url(minted.kShare))
    expect(pickLocaleText(resolved.noteByLocale, 'de')).toBe('Viel Spaß!')
    expect(pickLocaleText(resolved.noteByLocale, 'en')).toBe('Enjoy our trip!')
    expect(pickLocaleText(resolved.noteByLocale, 'fr')).toBe('Enjoy our trip!') // no fr → falls back to en
  })

  it('edits the note + name in place: recovers K_share via K_mem, re-seals both, PATCHes (shareId unchanged)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-n2' })
    const update = vi.fn().mockResolvedValue(undefined)
    vi.mocked(sharesApi).mockReturnValue({ createShare: create, updateShare: update } as unknown as ReturnType<typeof sharesApi>)
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom', undefined, null, { en: 'v1 note' }, 'For friends')
    const req = create.mock.calls[0][0].createShareRequest
    const { sealedShareKey } = req
    const share = addressOf(req)

    await updateShare(share, kMem, sealedShareKey, { en: 'v2 note' }, 'For Grandma', null)
    const patch = update.mock.calls[0][0]
    expect(patch.shareId).toBe(req.shareId)
    // both ciphertexts round-trip to the new values via the recovered K_share
    expect(readShareNoteMap(kMem, share, sealedShareKey, patch.updateShareRequest.sealedNote)).toEqual({ en: 'v2 note' })
    expect(readShareName(kMem, share, sealedShareKey, patch.updateShareRequest.sealedShareName)).toBe('For Grandma')
    // the recovery copy is bound to its share: listed on another share's row it recovers nothing (ADR 0072)
    const elsewhere = { ...share, shareId: ADDR.shareId }
    expect(readShareName(kMem, elsewhere, sealedShareKey, patch.updateShareRequest.sealedShareName)).toBe('')
    await expect(updateShare(elsewhere, kMem, sealedShareKey, { en: 'x' }, 'x', null)).rejects.toBeInstanceOf(v1.OpenRefused)
  })

  it('clears note + name with blank edits (PATCH nulls)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-n3' })
    const update = vi.fn().mockResolvedValue(undefined)
    vi.mocked(sharesApi).mockReturnValue({ createShare: create, updateShare: update } as unknown as ReturnType<typeof sharesApi>)
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom', undefined, null, { en: 'note' }, 'name')
    const req = create.mock.calls[0][0].createShareRequest

    await updateShare(addressOf(req), kMem, req.sealedShareKey, { en: '  ' }, '   ', null)
    expect(update.mock.calls[0][0].updateShareRequest.sealedNote).toBeNull()
    expect(update.mock.calls[0][0].updateShareRequest.sealedShareName).toBeNull()
  })

  it('seals + reads the private share name (management-only, never on the viewer resolve)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-n5' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom', undefined, null, {}, 'For Grandma')
    const req = create.mock.calls[0][0].createShareRequest
    expect(req.sealedShareName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('For Grandma')
    expect(readShareName(kMem, addressOf(req), req.sealedShareKey, req.sealedShareName)).toBe('For Grandma')
  })

  it('readShareNoteMap / readShareName return empty when there is no ciphertext or recoverable key', () => {
    const kMem = v1.randomBytes(32)
    expect(readShareNoteMap(kMem, ADDR, null, 'x')).toEqual({})
    expect(readShareNoteMap(kMem, ADDR, 'x', null)).toEqual({})
    expect(readShareName(kMem, ADDR, null, 'x')).toBe('')
    expect(readShareName(kMem, ADDR, 'x', null)).toBe('')
  })

  it('recoverShareLink rebuilds the full link (with the #k= fragment) for any existing share', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-n4' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, 'Rom', undefined, null, {})
    const req = create.mock.calls[0][0].createShareRequest
    const { sealedShareKey } = req

    // recovering K_share via K_mem reconstructs exactly the minted link — so any old link is re-copyable
    expect(recoverShareLink(addressOf(req), kMem, sealedShareKey)).toBe(buildShareLink(minted.shareId, minted.kShare))
    // a pre-cr-0115 share carries no recoverable key → null (falls back to the fresh link in the UI)
    expect(recoverShareLink(addressOf(req), kMem, null)).toBeNull()
    // a recovery copy served on another share's row rebuilds no link (bound to its share, ADR 0072)
    expect(recoverShareLink(ADDR, kMem, sealedShareKey)).toBeNull()
  })
})

// The share-password gate runs Argon2id at production params inside the code under test — `createShare(…,
// password)` and `derivePasswordToken` take no params argument, and giving them one for a test's benefit is
// exactly the affordance we don't add. So the cost is inherent and the ceiling is stated here, on this block
// only. See packages/crypto/src/testArgon.ts.
describe('password protection (cr-0118)', () => {
  it('createShare with a password sends the gate fields; the verifier = SHA-256(Argon2id(pw, salt))', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-p1' })
    mockCurator(create)
    await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), 'Rom', undefined, null, {}, '', 'hunter2')
    const req = create.mock.calls[0][0].createShareRequest
    expect(req.passwordRequired).toBe(true)
    expect(req.pwSalt).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    // the stored verifier must equal SHA-256(Argon2id(pw, salt)) so the server accepts the right password's token
    expect(req.pwVerifier).toBe(b64std(v1.sha256Digest(v1.sharesPwToken('hunter2', fromB64std(req.pwSalt)))))
    // the raw password never travels in the clear
    expect(JSON.stringify(req)).not.toContain('hunter2')
  })

  it('derivePasswordToken is deterministic for the same password + salt', () => {
    const salt = b64std(v1.randomBytes(16))
    expect(derivePasswordToken('pw', salt)).toBe(derivePasswordToken('pw', salt))
  })

  it('readSharePassword reveals the plaintext via the K_mem-recovered K_share', () => {
    const kMem = v1.randomBytes(32)
    const kShare = v1.randomBytes(32)
    const sealedShareKey = b64std(v1.seal({ purpose: 'share-key-recovery', ...ADDR }, kMem, kShare))
    const sealedPassword = b64std(v1.seal({ purpose: 'share-password' }, kShare, v1.utf8('s3cret')))
    expect(readSharePassword(kMem, ADDR, sealedShareKey, sealedPassword)).toBe('s3cret')
    expect(readSharePassword(kMem, ADDR, null, sealedPassword)).toBe('') // no recoverable key → ''
    // the recovery copy is bound to its share — addressed as another share, nothing is revealed
    expect(readSharePassword(kMem, { ...ADDR, shareId: 'T'.repeat(43) }, sealedShareKey, sealedPassword)).toBe('')
  })

  it('resolveShare throws SharePasswordRequiredError on a locked shell, then unlocks with the token', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-p2' })
    mockCurator(create)
    const minted = await createShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), 'Rom', undefined, null, {}, '', 'hunter2')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest
    const resolveMock = vi.fn()
      .mockResolvedValueOnce({ passwordRequired: true, pwSalt: 'c2FsdA==', role: 'VIEWER', nodes: [], media: [] })
      .mockResolvedValueOnce({
        passwordRequired: true, pwSalt: 'c2FsdA==', role: 'VIEWER', scopeId: SCOPE_ID, wrap, sealedName,
        nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY', epoch: 0 }], media: [],
      })
    vi.mocked(publicSharesApi).mockReturnValue({ resolveShare: resolveMock } as unknown as ReturnType<typeof publicSharesApi>)
    const fragment = b64url(minted.kShare)
    await expect(resolveShare('share-p2', fragment)).rejects.toBeInstanceOf(SharePasswordRequiredError)
    const resolved = await resolveShare('share-p2', fragment, 'token')
    expect(resolved.memoryName).toBe('Rom')
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
    const si = { item: { mediaId: MEDIA_1, original: ref, derivatives: [] }, memoryKey, scopeId: SCOPE_ID }

    const resolveShareBlob = vi.fn(async () => ({
      presignedGets: frames.map((_, i) => ({ index: i, url: `u/${i}` })),
    }))
    vi.mocked(publicSharesApi).mockReturnValue({ resolveShareBlob } as unknown as ReturnType<typeof publicSharesApi>)
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
