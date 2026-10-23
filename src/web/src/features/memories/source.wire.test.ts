// @vitest-environment happy-dom
// A contributor's upload into a CHAPTER through the guest's data source (inbox #401 S4), on the wire: recorded at
// `fetch` — the JSON the real generated clients send, no mock of the share helpers or of either API client. The chapter
// is named as the target on the start, every window and the completion (the server then creates the guest's own bucket
// there), and what is sealed opens under the CHAPTER's key from the chain — not the share root's; an upload into the
// share root names no target at all, as a root upload always did.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { guestMemorySource } from './source'
import { CHUNK_SIZE } from './media'
import { readBlobMeta } from '../../core/blob/transport'
import { saveGuestSession, forgetGuestSession } from '../../core/sharing/guestIdentity'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare } from '../../api/generated/sharing'
import type { BlobRef } from '../../core/blob/types'
import { MEMORY_KIND } from './kinds'
import { guestAccessFor } from '../../test/models'

const SCOPE = '33333333-3333-4333-8333-333333333334'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_9'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000c1'
const CHAPTER = '6e5a0000-0000-4000-8000-0000000000c2'
const keys = { memory: v1.randomBytes(32), chapter: v1.randomBytes(32) }

type Sent = { path: string; json: Record<string, unknown> }

/** A server that hands out a 2-chunk window on start, signs the rest on a window request, and completes what it is told. */
function recordFetch(): Sent[] {
  const sent: Sent[] = []
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const puts = (uploadId: string, from: number, to: number) =>
    Array.from({ length: to - from }, (_, k) => ({ index: from + k, url: `mem://${uploadId}/${from + k}` }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('mem://')) return new Response(null, { status: 200 }) // a presigned PUT
    const body = JSON.parse(String(init?.body ?? 'null')) as Record<string, unknown>
    sent.push({ path: new URL(url, 'http://x').pathname, json: body })
    if (url.endsWith('/blob-uploads')) {
      return json({ results: (body.items as unknown[]).map((_, i) => ({ uploadId: `up-${i}`, presignedPuts: puts(`up-${i}`, 0, 2), error: null })) })
    }
    if (url.endsWith('/blob-upload-windows')) {
      const items = body.items as { uploadId: string; fromChunk: number }[]
      return json({ results: items.map(({ uploadId, fromChunk }) => ({ presignedPuts: puts(uploadId, fromChunk, 3), error: null })) })
    }
    if (url.endsWith('/media')) {
      const start = sent.find((s) => s.path.endsWith('/blob-uploads'))!.json.items as { blobId: string }[]
      return json({ results: [{ blobId: start[0].blobId, mediaId: (body.items as { mediaId: string }[])[0].mediaId, error: null }] })
    }
    throw new Error(`unexpected request ${url}`)
  }))
  return sent
}

const head = { shareId: SHARE, scopeId: SCOPE, role: ShareRole.ReadWrite, name: 'Rom', noteByLocale: {}, rootKind: MEMORY_KIND, kShare: v1.randomBytes(32), share: {} as PublicShare } as PublicShareHead
const source = () => guestMemorySource(() => guestAccessFor(head, { pwToken: 'cHc=' }), { rootId: MEMORY })
// A non-image: no derivative tiers to draw (no canvas here), three chunks so a window is signed.
const pdf = () => ({ blob: new Blob([new Uint8Array(2 * CHUNK_SIZE + 1)]), mediaType: 'application/pdf', name: 'plan.pdf' })

beforeEach(() => saveGuestSession(SHARE, { identityId: 'id-1', contributorToken: 'tok-1' }))
afterEach(() => {
  vi.unstubAllGlobals()
  forgetGuestSession(SHARE)
  localStorage.clear()
})

describe("a contributor's upload into a chapter, through the guest's source", () => {
  it("names the chapter on the start, the window and the completion, sealed under the chapter's key", async () => {
    const sent = recordFetch()
    await source().upload!.target(CHAPTER, keys.chapter, 0)!.upload!(pdf(), {})

    expect(sent.map((s) => s.path)).toEqual([
      `/api/shares/${SHARE}/blob-uploads`, `/api/shares/${SHARE}/blob-upload-windows`, `/api/shares/${SHARE}/media`,
    ])
    for (const s of sent) expect(s.json, s.path).toMatchObject({ contributorToken: 'tok-1', nodeId: CHAPTER })
    // The original as declared: its K_blob wrap and its meta open under the chapter's key — and not under the root's.
    const declared = (sent[0].json.items as BlobRef[])[0]
    expect(readBlobMeta(SCOPE, keys.chapter, declared)?.type).toBe('application/pdf')
    expect(readBlobMeta(SCOPE, keys.memory, declared)).toBeNull()
  })

  it('into the share root: no `nodeId` key on any request', async () => {
    const sent = recordFetch()
    await source().upload!.target(MEMORY, keys.memory, 0)!.upload!(pdf(), {})
    expect(sent).toHaveLength(3)
    for (const s of sent) expect(Object.keys(s.json), s.path).not.toContain('nodeId')
  })
})
