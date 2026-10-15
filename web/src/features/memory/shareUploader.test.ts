// @vitest-environment happy-dom
// uc.sharing.share-uploader — the guest of a WRITE_ONLY link never holds K_mem (ADR 0031 point 7). The two negatives
// were seen red on the tree before the drop key (the resolve handed the guest K_mem, and its blob keys opened under
// it); each is anchored on the refusal the fixed tree answers with and carries a positive control. The last case is
// the positive that proves the key path reaches every reader: the curator's gallery and a viewer link both open
// what the guest uploaded, through the real read functions.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({
  sharesApi: vi.fn(),
  publicSharesApi: vi.fn(),
  blobApi: vi.fn(),
  tokenApi: vi.fn(),
}))
vi.mock('./api', () => ({ mediaApi: vi.fn(), bucketsApi: vi.fn(), publicShareMediaApi: vi.fn() }))

import { resolveShare, createContributorIdentity, sessionSealKey, addSharePhoto, openShareOriginal } from './share'
import { listMediaPage, openOriginal, originalBlobMeta, unsealMeta, setMediaMeta, listBuckets, openBucketName, type MediaDeriver } from './media'
import { publicSharesApi, blobApi } from '../../api/client'
import { mediaApi, bucketsApi, publicShareMediaApi } from './api'
import {
  ShareRole,
  type PublicShare,
  type ShareBlobUploadResultItem,
  type ShareBlobUploadsResponse,
  type SharingApi,
} from '../../api/generated/sharing'
import { MEMORY_KIND } from './kinds'
import type { MediaApi, MediaListPage } from '../../api/generated/media'
import type { ShareMediaApi, ShareMediaBatchResponse, ShareMediaListing } from '../../api/generated/share-media'
import type { BlobDownloadsBatchResponse, PresignedUrl } from '../../api/generated/blob'
import { fakeApi, stubApi } from '../../test/apiMock'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const UPLOADER_SHARE = 'U'.repeat(43)
const VIEWER_SHARE = 'V'.repeat(43)
const PHOTO = Uint8Array.from({ length: 16 }, (_, i) => i + 1)

/** Every Uint8Array reachable from [value] — what a resolved session actually holds, whatever its fields are named. */
function heldBytes(value: unknown, seen = new Set<unknown>()): Uint8Array[] {
  if (value instanceof Uint8Array) return [value]
  if (!value || typeof value !== 'object' || seen.has(value)) return []
  seen.add(value)
  return Object.values(value).flatMap((v) => heldBytes(v, seen))
}
const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i])

/** The Memory as its owner set it up: K_mem, a drop keypair (private half on the node), one WRITE_ONLY share. */
function uploaderShare() {
  const kMem = v1.randomBytes(32)
  const kShare = v1.randomBytes(32)
  const drop = v1.generateDropKey()
  const sealedDropPrivateKey = b64std(
    v1.seal({ purpose: 'drop-private-key', scopeId: SCOPE_ID, nodeId: NODE_ID }, kMem, v1.serializeDropKey(drop)),
  )
  const resolve: PublicShare = {
    role: ShareRole.WriteOnly,
    passwordRequired: false,
    scopeId: SCOPE_ID,
    nodeId: NODE_ID,
    sealedName: b64std(v1.seal({ purpose: 'share-memory-name' }, kShare, v1.utf8('Hochzeit'))),
    sealedDropPublicKey: b64std(
      v1.seal({ purpose: 'drop-public-key' }, kShare, v1.serializeHybridPublic(v1.dropKeyPublic(drop))),
    ),
    // A row stored before the drop key still carries the K_mem wrap. The guest must not open it.
    wrap: b64std(v1.seal({ purpose: 'share-key' }, kShare, kMem)),
    nodes: [],
  }
  return { kMem, kShare, sealedDropPrivateKey, resolve }
}

/** The object store the guest PUTs frames into and a reader GETs them back from. */
const store = new Map<string, Uint8Array>()

/** The share-scoped API as the guest sees it, recording what the guest sent. */
function guestApi(resolve: PublicShare) {
  const startShareBlobUploads = vi.fn<SharingApi['startShareBlobUploads']>(async ({ shareBlobUploadsRequest }): Promise<ShareBlobUploadsResponse> => ({
    results: shareBlobUploadsRequest.items.map((_, i): ShareBlobUploadResultItem => ({
      uploadId: `up-${i}`, presignedPuts: [{ index: 0, url: `mem://up-${i}/0` }], error: null,
    })),
  }))
  // Core's guest transport and identity (`sharing`), then Memories' guest completion (`share-media`). Its media read
  // is never asked for a WRITE_ONLY link (it serves the guest nothing), so it refuses here.
  const transport = fakeApi(publicSharesApi, {
    resolveShare: async () => resolve,
    createShareIdentity: vi.fn<SharingApi['createShareIdentity']>().mockResolvedValue({ identityId: 'ignored', contributorToken: 'tok-1' }),
    startShareBlobUploads,
  })
  const media = fakeApi(publicShareMediaApi, {
    listShareMedia: vi.fn<ShareMediaApi['listShareMedia']>().mockRejectedValue(new Error('a WRITE_ONLY link is not asked')),
    completeShareMedia: async ({ shareMediaBatchRequest }): Promise<ShareMediaBatchResponse> => ({
      results: [{
        blobId: startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items[0].blobId,
        mediaId: shareMediaBatchRequest.items[0].mediaId,
        error: null,
      }],
    }),
  })
  return { ...transport, ...media }
}

const noTiers = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn() } as unknown as MediaDeriver

/** The guest names itself and uploads [PHOTO]; returns what crossed the wire. */
async function guestUploads(kShare: Uint8Array, api: ReturnType<typeof guestApi>) {
  const resolved = await resolveShare(UPLOADER_SHARE, b64url(kShare))
  expect(api.listShareMedia).not.toHaveBeenCalled()
  const session = await createContributorIdentity(UPLOADER_SHARE, resolved.scopeId, resolved.access, 'Gast', b64url(kShare))
  const sealKey = sessionSealKey(session, resolved.access)!
  await addSharePhoto(
    { ...session, shareId: UPLOADER_SHARE, scopeId: resolved.scopeId, sealKey },
    { blob: new Blob([PHOTO as BlobPart]), mediaType: 'application/pdf', name: 'Gast-Scan.pdf' },
    noTiers,
  )
  const identity = api.createShareIdentity.mock.calls[0][0].createShareIdentityRequest
  const [declared] = api.startShareBlobUploads.mock.calls[0][0].shareBlobUploadsRequest.items
  const completed = api.completeShareMedia.mock.calls[0][0].shareMediaBatchRequest.items[0]
  return { identity, declared, completed }
}

beforeEach(() => {
  vi.clearAllMocks()
  store.clear()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') store.set(url, new Uint8Array(init.body as Uint8Array))
    const body = store.get(url)
    return { ok: true, status: 200, arrayBuffer: async () => body!.slice().buffer } as unknown as Response
  }))
})
afterEach(() => vi.unstubAllGlobals())

describe('a WRITE_ONLY guest never holds K_mem (ADR 0031 point 7)', () => {
  it('the resolved session holds no Memory key — even when the server still serves the wrap', async () => {
    const { kMem, kShare, resolve } = uploaderShare()
    guestApi(resolve)

    const resolved = await resolveShare(UPLOADER_SHARE, b64url(kShare))

    expect(resolved.role).toBe(ShareRole.WriteOnly)
    expect(heldBytes(resolved).some((b) => sameBytes(b, kMem))).toBe(false)
    // control, same predicate: what the guest does hold is the drop public key, to wrap its own key to
    expect(resolved.access.kind).toBe('drop')
    expect(heldBytes(resolved).length).toBeGreaterThan(0)
  })

  it('a blob key the guest sealed does not open under K_mem — it opens through the drop key and the guest\'s key', async () => {
    const { kMem, kShare, sealedDropPrivateKey, resolve } = uploaderShare()
    const api = guestApi(resolve)

    const { identity, declared } = await guestUploads(kShare, api)

    const blobKeyCtx = { purpose: 'blob-key', scopeId: SCOPE_ID, ownerId: declared.ownerId, blobId: declared.blobId, role: 'ORIGINAL' } as const
    const wrappedBlobKey = fromB64std(declared.wrappedBlobKey)
    expect(() => v1.open(blobKeyCtx, kMem, wrappedBlobKey)).toThrow(new v1.OpenRefused('blob-key'))
    // control: K_mem → drop private key → K_up opens it
    const drop = v1.deserializeDropKey(
      v1.open({ purpose: 'drop-private-key', scopeId: SCOPE_ID, nodeId: NODE_ID }, kMem, fromB64std(sealedDropPrivateKey)),
    )
    const kUp = v1.openAs(
      { purpose: 'upload-identity-key', scopeId: SCOPE_ID, nodeId: NODE_ID, identityId: identity.identityId },
      v1.dropKeySecret(drop), fromB64std(identity.wrappedIdentityKey!),
    )
    expect(v1.open(blobKeyCtx, kUp, wrappedBlobKey)).toHaveLength(32)
    // and the display name is the guest's too, not the Memory's
    const nameCtx = { purpose: 'share-identity-name', scopeId: SCOPE_ID, shareId: UPLOADER_SHARE, identityId: identity.identityId } as const
    expect(() => v1.open(nameCtx, kMem, fromB64std(identity.sealedDisplayName))).toThrow(new v1.OpenRefused('share-identity-name'))
    expect(new TextDecoder().decode(v1.open(nameCtx, kUp, fromB64std(identity.sealedDisplayName)))).toBe('Gast')
  })

  it('the curator\'s gallery and a viewer link both open the guest\'s upload, and a member edit keeps its path', async () => {
    const { kMem, kShare, sealedDropPrivateKey, resolve } = uploaderShare()
    const api = guestApi(resolve)
    const { identity, declared, completed } = await guestUploads(kShare, api)
    const identityId: string = identity.identityId
    const row = {
      mediaId: declared.ownerId, blobId: declared.blobId, chunkCount: declared.chunkCount, byteSize: declared.byteSize,
      wrappedBlobKey: declared.wrappedBlobKey, wrappedBlobMeta: declared.wrappedBlobMeta, derivatives: [], wrappedMeta: completed.wrappedMeta,
    }
    const presignedGets: PresignedUrl[] = [{ index: 0, url: 'mem://up-0/0' }]

    // the curator: a media page carries the node's drop key and the guest's wrapped key beside the row
    fakeApi(mediaApi, {
      listNodeMedia: async (): Promise<MediaListPage> => ({
        media: [{ ...row, updatedAt: new Date(), ownerKey: `share:${identityId}` }],
        mediaRevision: 1, deleteRevision: 0,
        sealedDropPrivateKey, uploaderKeys: [{ identityId, wrappedIdentityKey: identity.wrappedIdentityKey! }],
      }),
      updateMediaMeta: vi.fn<MediaApi['updateMediaMeta']>().mockResolvedValue({}),
    })
    fakeApi(blobApi, {
      presignBlobDownloads: async (): Promise<BlobDownloadsBatchResponse> => ({ results: [{ presignedGets, error: null }] }),
    })
    const page = await listMediaPage(SCOPE_ID, NODE_ID)
    if (page.notModified) throw new Error('unreachable')
    const [item] = page.items
    expect(await openOriginal(SCOPE_ID, kMem, item)).toEqual(PHOTO)
    // the guest's upload carries its blob meta (inbox #397), opened through the same chain as its K_blob — the guest's
    // key, never K_mem directly
    expect(originalBlobMeta(SCOPE_ID, kMem, item)).toMatchObject({ type: 'application/pdf', name: 'Gast-Scan.pdf' })

    // a member edits its capture time: the re-seal is under the guest's key, never K_mem — no media changes path
    const edited = await setMediaMeta(SCOPE_ID, item, kMem, 1_700_000_000_000)
    const metaCtx = { purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: item.mediaId } as const
    expect(() => v1.open(metaCtx, kMem, fromB64std(edited.wrappedMeta!))).toThrow(new v1.OpenRefused('media-meta'))
    expect(unsealMeta(SCOPE_ID, kMem, { ...item, wrappedMeta: edited.wrappedMeta! })?.capturedAtOverride).toBe(1_700_000_000_000)

    // the curator's buckets: the guest's primary carries its wrapped key, and its name opens through it
    stubApi(bucketsApi, {
      listNodeBuckets: {
        buckets: [{
          bucketId: crypto.randomUUID(), ownerKey: `share:${identityId}`, isPrimary: true, mediaCount: 1,
          shareId: UPLOADER_SHARE, sealedName: identity.sealedDisplayName, wrappedIdentityKey: identity.wrappedIdentityKey,
        }],
        sealedDropPrivateKey,
      },
    })
    const [primary] = await listBuckets(SCOPE_ID, NODE_ID)
    expect(openBucketName(SCOPE_ID, NODE_ID, kMem, primary)).toBe('Gast')

    // a viewer link of the same Memory: the resolve's node carries the drop key, Memories' media read the row and the
    // guest's wrapped key — and no name, which the contract has no field for (ADR 0030 point 8). Typed, so a shape
    // change is loud here.
    const kViewer = v1.randomBytes(32)
    const viewerResolve = {
      role: ShareRole.Read, passwordRequired: false, scopeId: SCOPE_ID, nodeId: NODE_ID,
      wrap: b64std(v1.seal({ purpose: 'share-key' }, kViewer, kMem)),
      sealedName: b64std(v1.seal({ purpose: 'share-memory-name' }, kViewer, v1.utf8('Hochzeit'))),
      nodes: [{ id: NODE_ID, kind: MEMORY_KIND, epoch: 0, sealedDropPrivateKey }],
    } satisfies PublicShare
    const viewerMedia = {
      media: [{ ...row, nodeId: NODE_ID, identityId }],
      identities: [{ identityId, wrappedIdentityKey: identity.wrappedIdentityKey! }],
    } satisfies ShareMediaListing
    stubApi(publicSharesApi, { resolveShare: viewerResolve, resolveShareBlob: { presignedGets } })
    stubApi(publicShareMediaApi, { listShareMedia: viewerMedia })
    const viewer = await resolveShare(VIEWER_SHARE, b64url(kViewer))
    expect(viewer.items).toHaveLength(1)
    expect(await openShareOriginal(VIEWER_SHARE, viewer.items[0])).toEqual(PHOTO)
    expect(originalBlobMeta(SCOPE_ID, viewer.items[0].memoryKey, viewer.items[0].item)).toMatchObject({ name: 'Gast-Scan.pdf' })
  })
})
