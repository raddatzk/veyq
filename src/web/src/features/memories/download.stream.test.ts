// The streamed original download (`downloadOriginal`, inbox #371 S4) through the REAL transport — core's
// `makeChunkProvider` and `presignAllChunks`, the generated blob client — against a fake control plane and a fake store
// serving frames sealed the way the pump seals them. It saves the same bytes the old whole-original path (`openBlob`)
// returned, under the name it is handed, opens the save target before reading anything (the name is the original's blob
// meta, known with the listing — inbox #397), and aborts (never closes) on a failure. `originalDownloadName` — the
// name rule over the blob meta — is pinned at the bottom.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { CHUNK_SIZE, makeChunkProvider, openBlob, planBlob, presignAllChunks } from '../../core/blob/transport'
import type { BlobRef } from '../../core/blob/types'
import type { ByteSink } from '../../core/blob/stream'
import { openSaveTarget, SaveTooLarge } from '../../shared/saveStream'
import { session } from '../../app/session'
import { downloadOriginal, originalDownloadName, type OpenSaveTarget } from './download'

const SCOPE = '33333333-3333-4333-8333-333333333333'
const MEDIA = '5c09e000-0000-4000-8000-0000000000a1'
const JPEG_HEAD = [0xff, 0xd8, 0xff, 0xe0]

afterEach(() => vi.unstubAllGlobals())

/** `n` plaintext bytes starting with `head`, the rest a pattern. */
const original = (n: number, head: number[] = []) => {
  const b = new Uint8Array(n).map((_, i) => (i * 7 + 3) % 253)
  b.set(head)
  return b
}

/** An original stored as the upload pump stores it, and the real provider over it. */
function stored(key: Uint8Array, plain: Uint8Array) {
  const plan = planBlob(SCOPE, { ownerId: MEDIA, role: 'ORIGINAL' }, key, new Blob([plain as BlobPart]))
  const frames = Array.from({ length: plan.chunkCount }, (_, i) =>
    v1.sealFrame(plan.blobKey, i, plan.chunkCount, plain.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE), v1.randomBytes(12)))
  const ref: BlobRef = {
    blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey,
    ownerId: MEDIA, role: 'ORIGINAL',
  }
  const provider = () => makeChunkProvider(SCOPE, key, ref, () => presignAllChunks(SCOPE, ref.blobId))
  return { ref, frames, provider }
}

/** The fake store + control plane; `failAt` answers that chunk's GET with a 500. Records every GET by index. */
function fakeStore(blobId: string, frames: Uint8Array[], failAt?: number) {
  const log = { presign: 0, gets: [] as number[] }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    if (url.host === 'store.test') {
      const i = Number(url.pathname.split('/').pop())
      log.gets.push(i)
      if (i === failAt) return new Response('', { status: 500 })
      return new Response(frames[i] as BodyInit, { status: 200 })
    }
    if (init?.method === 'POST' && url.pathname === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      log.presign += 1
      return new Response(JSON.stringify({
        results: [{ presignedGets: frames.map((_, index) => ({ index, url: `https://store.test/${blobId}/${index}` })), error: null }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    throw new Error(`unexpected request ${init?.method ?? 'GET'} ${url}`)
  }))
  return log
}

/** A save target that collects what is written and records what the store had served when it was opened. */
function collectingTarget(log: { presign: number; gets: number[] }) {
  const file = { name: '', size: -1, written: [] as Uint8Array[], ended: '', getsBeforeOpen: -1, presignsBeforeOpen: -1 }
  const open: OpenSaveTarget = async (name, size) => {
    Object.assign(file, { name, size, getsBeforeOpen: log.gets.length, presignsBeforeOpen: log.presign })
    const sink: ByteSink = {
      write: async (b) => void file.written.push(b.slice()),
      close: async () => void (file.ended = 'close'),
      abort: async () => void (file.ended = 'abort'),
    }
    return sink
  }
  const bytes = () => {
    const all = new Uint8Array(file.written.reduce((n, b) => n + b.length, 0))
    let at = 0
    for (const b of file.written) { all.set(b, at); at += b.length }
    return all
  }
  return { file, open, bytes }
}

describe('downloadOriginal (streamed, inbox #371 S4)', () => {
  session.accessToken = 'access-token'

  it('saves exactly the bytes the old whole-original path returned, under the name it is handed', async () => {
    const key = v1.randomBytes(32)
    const plain = original(2 * CHUNK_SIZE + 4321, JPEG_HEAD)
    const { ref, frames, provider } = stored(key, plain)

    fakeStore(ref.blobId, frames)
    const old = await openBlob(SCOPE, key, ref) // the path the lightbox used to save from

    const log = fakeStore(ref.blobId, frames)
    const target = collectingTarget(log)
    await downloadOriginal(provider(), 'IMG_0001.JPG', target.open)
    expect(target.file.name).toBe('IMG_0001.JPG')
    expect(target.file.ended).toBe('close')
    expect(target.bytes()).toEqual(old)
    expect(target.bytes()).toEqual(plain)
    expect(target.file.size).toBe(plain.length) // the plaintext size, not the framed one
    expect([...log.gets].sort()).toEqual([0, 1, 2]) // every chunk fetched once
    expect(log.presign).toBe(1)
  })

  // inbox #397: the name is the blob meta's, known with the listing — nothing is read to name the file. Lever: read
  // chunk 0 before `open` (the pre-#397 untyped path).
  it('opens the save target before ANY request — the picker keeps the click', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(CHUNK_SIZE + 10, JPEG_HEAD))
    const log = fakeStore(ref.blobId, frames)
    const target = collectingTarget(log)

    await downloadOriginal(provider(), 'clip', target.open)
    expect(target.file).toMatchObject({ name: 'clip', getsBeforeOpen: 0, presignsBeforeOpen: 0 })
  })

  it('above the Blob cap with no streaming target it is refused before a single chunk is fetched', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames } = stored(key, original(64, JPEG_HEAD))
    // the real provider over a ref that CLAIMS 1 GiB — the cap is checked against the declared size, before any write
    const big: BlobRef = { ...ref, chunkCount: 4096, byteSize: 4096 * (CHUNK_SIZE + v1.FRAME_OVERHEAD) }
    const huge = () => makeChunkProvider(SCOPE, key, big, async () => frames.map((_, i) => `https://store.test/${ref.blobId}/${i}`))
    const noStream: OpenSaveTarget = (name, size) => openSaveTarget(name, size, { picker: null, registerDownload: async () => null })

    const log = fakeStore(ref.blobId, frames)
    await expect(downloadOriginal(huge(), 'Rom 1.jpg', noStream)).rejects.toBeInstanceOf(SaveTooLarge)
    expect(log.gets).toEqual([])
  })

  it('a chunk that fails mid-stream aborts the target — never closes it — and rethrows', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(3 * CHUNK_SIZE, JPEG_HEAD))
    const log = fakeStore(ref.blobId, frames, 2)
    const target = collectingTarget(log)

    await expect(downloadOriginal(provider(), 'Rom 1.jpg', target.open)).rejects.toThrow('chunk_get_failed:500')
    expect(target.file.ended).toBe('abort')
  })
})

describe('originalDownloadName (the original\'s blob meta, inbox #397)', () => {
  const meta = (m: { type: string; name?: string }) => ({ v: 1, ...m })

  // Lever: drop the sealed name (pass null as the meta to `downloadName`) — every named case goes red.
  it('names the original by its sealed file name verbatim — not the Memory name, not the position, not the type', () => {
    expect(originalDownloadName(meta({ type: 'image/jpeg', name: '  IMG_0042 Urlaub.HEIC ' }), 'Rom', 4)).toBe('IMG_0042 Urlaub.HEIC')
  })

  it('a sealed name without an extension gains the one its type names; an unnamed type adds none', () => {
    expect(originalDownloadName(meta({ type: 'video/mp4', name: 'clip' }), 'Rom', 0)).toBe('clip.mp4')
    expect(originalDownloadName(meta({ type: 'image/tiff', name: 'scan' }), 'Rom', 0)).toBe('scan')
  })

  it('without a sealed name: the Memory name + position, the extension from the sealed type, else .bin', () => {
    expect(originalDownloadName(meta({ type: 'image/jpeg' }), 'Trip/2026', 2)).toBe('Trip 2026 3.jpg')
    expect(originalDownloadName(meta({ type: 'application/octet-stream' }), 'Rom', 0)).toBe('Rom 1.bin')
    expect(originalDownloadName(meta({ type: 'toString' }), 'Rom', 0)).toBe('Rom 1.bin') // own rows only
    expect(originalDownloadName(null, 'Rom', 1)).toBe('Rom 2.bin') // no meta at all (a foreign ref opens to null)
    expect(originalDownloadName(null, '   ', 0)).toBe('memory 1.bin')
  })
})
