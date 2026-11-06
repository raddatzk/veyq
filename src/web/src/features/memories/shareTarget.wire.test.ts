// A guest's upload into a node BELOW its link's root, on the wire
// (uc.sharing.share-contributor:rights-inherit-below-a-folder): recorded at `fetch` — the JSON the real generated
// clients send, no mock of either API client. An upload with a target names it on the start, every window and the
// completion (one target per request); an upload without one sends the bodies a root-level upload always sent — no
// `nodeId` key at all.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { addSharePhoto } from './share'
import { CHUNK_SIZE, type MediaDeriver } from './media'

const SCOPE_ID = '33333333-3333-4333-8333-333333333333'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000b1'

afterEach(() => vi.unstubAllGlobals())

type Sent = { url: string; method: string; json: Record<string, unknown> | null }

/** A server that hands out a 2-chunk window on start, signs the rest on a window request, and completes what it is told. */
function recordFetch(): Sent[] {
  const sent: Sent[] = []
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const puts = (uploadId: string, from: number, to: number) =>
    Array.from({ length: to - from }, (_, k) => ({ index: from + k, url: `mem://${uploadId}/${from + k}` }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.startsWith('mem://')) return new Response(null, { status: 200 }) // a presigned PUT
    const body = JSON.parse(String(init?.body ?? 'null')) as Record<string, unknown> | null
    sent.push({ url, method, json: body })
    if (url.endsWith('/blob-uploads')) {
      const items = body!.items as { blobId: string }[]
      return json({ results: items.map((_, i) => ({ uploadId: `up-${i}`, presignedPuts: puts(`up-${i}`, 0, 2), error: null })) })
    }
    if (url.endsWith('/blob-upload-windows')) {
      const items = body!.items as { uploadId: string; fromChunk: number }[]
      return json({ results: items.map(({ uploadId, fromChunk }) => ({ presignedPuts: puts(uploadId, fromChunk, 3), error: null })) })
    }
    if (url.endsWith('/media')) {
      const start = sent.find((s) => s.url.endsWith('/blob-uploads'))!.json!.items as { blobId: string }[]
      return json({ results: [{ blobId: start[0].blobId, mediaId: (body!.items as { mediaId: string }[])[0].mediaId, error: null }] })
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return sent
}

const noTiers = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn() } as unknown as MediaDeriver
const threeChunks = () => ({ blob: new Blob([new Uint8Array(2 * CHUNK_SIZE + 1)]), mediaType: 'application/pdf' })
const session = { identityId: 'id-1', contributorToken: 'tok-1', shareId: SHARE_ID, scopeId: SCOPE_ID, sealKey: v1.randomBytes(32), nodeEpoch: 0 }

describe('addSharePhoto names its target on the wire', () => {
  it('a target below the root: `nodeId` on the start, the window and the completion', async () => {
    const sent = recordFetch()
    await addSharePhoto({ ...session, nodeId: MEMORY }, threeChunks(), noTiers)

    const paths = sent.map((s) => new URL(s.url, 'http://x').pathname)
    expect(paths).toEqual([
      `/api/shares/${SHARE_ID}/blob-uploads`, `/api/shares/${SHARE_ID}/blob-upload-windows`, `/api/shares/${SHARE_ID}/media`,
    ])
    for (const s of sent) expect(s.json, s.url).toMatchObject({ contributorToken: 'tok-1', nodeId: MEMORY })
  })

  it('another instance\'s link (a bookmark\'s page, ADR 0121): the start, the window and the completion go to its origin', async () => {
    const away = 'https://photos.example.org'
    const sent = recordFetch()
    await addSharePhoto({ ...session, apiBase: away, nodeId: MEMORY }, threeChunks(), noTiers)
    expect(sent.map((s) => s.url)).toEqual([
      `${away}/api/shares/${SHARE_ID}/blob-uploads`, `${away}/api/shares/${SHARE_ID}/blob-upload-windows`, `${away}/api/shares/${SHARE_ID}/media`,
    ])
  })

  it('no target: the bodies a root-level upload always sent, without a `nodeId` key', async () => {
    const sent = recordFetch()
    await addSharePhoto(session, threeChunks(), noTiers)

    expect(sent).toHaveLength(3)
    for (const s of sent) {
      expect(s.json, s.url).toMatchObject({ contributorToken: 'tok-1' })
      expect(Object.keys(s.json!), s.url).not.toContain('nodeId')
    }
  })
})
