// Memories' part of a node's re-key (ADR 0109 point 2) through the REAL crypto: only the transport is stubbed. Every
// medium's meta and EXIF re-seal byte for byte under the new key, every `K_blob` keeps its value with its wrap moved,
// the banner's wraps in the node's meta move in place, each bucket name re-seals — and a contributor's primary, whose
// name the server copies again, is not staged.
import { describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { blobKeyContext, unwrapBlobKey } from '../../core/blob/keys'
import { mediaMetaContext } from './mediaOpen'
import { fakeApi } from '../../test/apiMock'
import type { MediaRekeyPage, MediaRekeyStage } from '../../api/generated/media'

const SCOPE = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const MEDIUM = '2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e'
const net = vi.hoisted(() => ({ pages: [] as MediaRekeyPage[], staged: [] as MediaRekeyStage[] }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('./api', () => ({ mediaApi: vi.fn() }))
const { mediaApi } = await import('./api')
const { rekeyMemoryContent } = await import('./rekey')
fakeApi(mediaApi, {
  listMediaRekey: async ({ since }) => net.pages[since ? Number(since) : 0],
  stageMediaRekey: async ({ mediaRekeyStage }) => { net.staged.push(mediaRekeyStage) },
})

const exifContext = { purpose: 'media-exif', scopeId: SCOPE, mediaId: MEDIUM } as const
const bucketContext = (bucketId: string) => ({ purpose: 'bucket-name', scopeId: SCOPE, nodeId: NODE, bucketId }) as const

describe('rekeyMemoryContent (ADR 0109, real crypto)', () => {
  it('re-seals every Memories field under the new key and leaves a contributor primary to the server', async () => {
    const oldKey = v1.randomBytes(32)
    const newKey = v1.randomBytes(32)
    const kBanner = v1.randomBytes(32)
    const banner = { blobId: crypto.randomUUID(), ownerId: NODE, role: 'BANNER' }
    const sub = crypto.randomUUID()
    const contributor = crypto.randomUUID()
    net.staged = []
    net.pages = [
      {
        media: [{
          mediaId: MEDIUM, metaRevision: 3,
          wrappedMeta: b64std(v1.sealField(mediaMetaContext(SCOPE, MEDIUM), oldKey, v1.utf8('{"t":1}'))),
          wrappedExif: b64std(v1.sealField(exifContext, oldKey, v1.utf8('{"Make":"x"}'))),
        }],
        buckets: [
          { bucketId: sub, sealedName: b64std(v1.sealField(bucketContext(sub), oldKey, v1.utf8('Tag 1'))) },
          { bucketId: contributor, sealedName: 'Y29weQ==', shareId: 'A'.repeat(43), identityId: crypto.randomUUID() },
        ],
        more: true, cursor: '1',
      },
      { media: [], buckets: [], more: false, cursor: null },
    ]
    const meta: Record<string, unknown> = {
      v: 1, banner: { medium: 'image', blob: { blobId: banner.blobId, chunkCount: 1, byteSize: 1, wrappedBlobKey: b64std(v1.seal(blobKeyContext(SCOPE, banner), oldKey, kBanner)) } },
    }
    await rekeyMemoryContent({
      scopeId: SCOPE, rekeyId: crypto.randomUUID(), node: { id: NODE, kind: 'MEMORY', epoch: 0, trashed: false },
      oldKey, newKey, meta, shareRootKeys: () => null,
    })

    // A medium's blob wraps are core's blob service's (ADR 0113): Memories stages its meta and EXIF only.
    const staged = net.staged.flatMap((s) => (s.media ?? []) as { wrappedMeta: string; wrappedExif: string; metaRevision: number }[])
    expect(staged).toHaveLength(1)
    const m = staged[0]
    expect(m.metaRevision).toBe(3)
    expect(new TextDecoder().decode(v1.openField(mediaMetaContext(SCOPE, MEDIUM), newKey, fromB64std(m.wrappedMeta)))).toBe('{"t":1}')
    expect(() => v1.openField(mediaMetaContext(SCOPE, MEDIUM), oldKey, fromB64std(m.wrappedMeta)), 'not under the old key').toThrow(v1.OpenRefused)
    expect(new TextDecoder().decode(v1.openField(exifContext, newKey, fromB64std(m.wrappedExif)))).toBe('{"Make":"x"}')
    expect(Object.keys(m)).not.toContain('blobs')

    const buckets = net.staged.flatMap((s) => (s.buckets ?? []) as { bucketId: string; sealedName: string }[])
    expect(buckets.map((b) => b.bucketId), 'the contributor primary is the server\'s copy: not staged').toEqual([sub])
    expect(new TextDecoder().decode(v1.openField(bucketContext(sub), newKey, fromB64std(buckets[0].sealedName)))).toBe('Tag 1')

    const bannerWrap = ((meta.banner as { blob: { wrappedBlobKey: string } }).blob).wrappedBlobKey
    expect(unwrapBlobKey(SCOPE, newKey, { ...banner, wrappedBlobKey: bannerWrap })).toEqual(kBanner)
    expect(() => unwrapBlobKey(SCOPE, oldKey, { ...banner, wrappedBlobKey: bannerWrap })).toThrow(v1.OpenRefused)
    expect(net.staged.every((s) => s.fromEpoch === 0)).toBe(true)
  })
})
