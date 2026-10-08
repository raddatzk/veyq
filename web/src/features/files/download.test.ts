// A file's download (`download.ts`) through the REAL transport — core's `presignAllChunks` and `makeChunkProvider`,
// the generated blob client — against a fake control plane and a fake store serving frames sealed the way the pump
// seals them. It decrypts to the original bytes, presigns once, keeps a bounded number of frames in flight, and refuses
// a ref the server served for another node.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { CHUNK_SIZE, planBlob } from '../../core/blob/transport'
import { fetchFileContent, DOWNLOAD_IN_FLIGHT } from './download'
import { session } from '../../app/session'

const SCOPE = '33333333-3333-4333-8333-333333333333'
const NODE = '5c09e000-0000-4000-8000-0000000000e1'
const OTHER = '5c09e000-0000-4000-8000-0000000000e2'

afterEach(() => vi.unstubAllGlobals())

const bytesOf = (n: number) => new Uint8Array(n).map((_, i) => (i * 7 + 3) % 253)

/** A blob stored as the upload pump stores it: planned under the node key, every frame sealed under its K_blob. */
async function storedFile(nodeKey: Uint8Array, plain: Uint8Array) {
  const plan = planBlob(SCOPE, { ownerId: NODE, role: 'FILE_CONTENT' }, nodeKey, new Blob([plain as BlobPart]))
  const frames = Array.from({ length: plan.chunkCount }, (_, i) =>
    v1.sealFrame(plan.blobKey, i, plan.chunkCount, plain.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE), v1.randomBytes(12)))
  const ref = { blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey }
  return { ref, frames }
}

function fakeStore(blobId: string, frames: Uint8Array[]) {
  const counts = { presign: 0, gets: 0, inFlight: 0, maxInFlight: 0 }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    if (url.host === 'store.test') {
      counts.gets += 1
      counts.inFlight += 1
      counts.maxInFlight = Math.max(counts.maxInFlight, counts.inFlight)
      await new Promise((r) => setTimeout(r, 1))
      counts.inFlight -= 1
      return new Response(frames[Number(url.pathname.split('/').pop())] as BodyInit, { status: 200 })
    }
    if (init?.method === 'POST' && url.pathname === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      counts.presign += 1
      const items = (JSON.parse(String(init.body)) as { items: { blobId: string }[] }).items
      expect(items).toEqual([{ blobId }])
      return new Response(JSON.stringify({
        results: [{ presignedGets: frames.map((_, index) => ({ index, url: `https://store.test/${blobId}/${index}` })).reverse(), error: null }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    throw new Error(`unexpected request ${init?.method ?? 'GET'} ${url}`)
  }))
  return counts
}

describe('a file download', () => {
  session.accessToken = 'access-token'

  it('decrypts a multi-chunk file to its original bytes, presigning once and holding a bounded number of frames in flight', async () => {
    const nodeKey = v1.randomBytes(32)
    const plain = bytesOf(5 * CHUNK_SIZE + 17)
    const { ref, frames } = await storedFile(nodeKey, plain)
    const counts = fakeStore(ref.blobId, frames)

    const blob = await fetchFileContent(SCOPE, nodeKey, NODE, ref)

    expect(Array.from(new Uint8Array(await blob.arrayBuffer()))).toEqual(Array.from(plain))
    expect(blob.type).toBe('application/octet-stream')
    expect(counts.presign).toBe(1)
    expect(counts.gets).toBe(6)
    expect(counts.maxInFlight).toBeLessThanOrEqual(DOWNLOAD_IN_FLIGHT)
    expect(counts.maxInFlight).toBeGreaterThan(1)
  })

  it('an empty file is one empty frame and decrypts to nothing', async () => {
    const nodeKey = v1.randomBytes(32)
    const { ref, frames } = await storedFile(nodeKey, new Uint8Array(0))
    fakeStore(ref.blobId, frames)
    expect((await fetchFileContent(SCOPE, nodeKey, NODE, ref)).size).toBe(0)
  })

  it('refuses a blob ref listed for another node, or opened under another key (the wrap is bound to the node)', async () => {
    const nodeKey = v1.randomBytes(32)
    const { ref, frames } = await storedFile(nodeKey, bytesOf(10))
    fakeStore(ref.blobId, frames)
    await expect(fetchFileContent(SCOPE, nodeKey, OTHER, ref)).rejects.toThrow(v1.OpenRefused)
    await expect(fetchFileContent(SCOPE, v1.randomBytes(32), NODE, ref)).rejects.toThrow(v1.OpenRefused)
  })
})
