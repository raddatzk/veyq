// The streamed original download (`downloadOriginal`, inbox #371 S4) through the REAL transport — core's
// `makeChunkProvider` and `presignAllChunks`, the generated blob client — against a fake control plane and a fake store
// serving frames sealed the way the pump seals them. It saves the same bytes the old whole-original path (`openBlob`)
// returned, names the file from the sealed type or else from chunk 0's sniff, opens the save target before reading
// anything when it can, reads chunk 0 only once when it must read it first, and aborts (never closes) on a failure.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { CHUNK_SIZE, makeChunkProvider, openBlob, planBlob, presignAllChunks } from '../../core/blob/transport'
import type { BlobRef } from '../../core/blob/types'
import type { ByteSink } from '../../core/blob/stream'
import { openSaveTarget, SaveTooLarge } from '../../shared/saveStream'
import { session } from '../../app/session'
import { downloadOriginal, type OpenSaveTarget } from './download'

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

  it('saves exactly the bytes the old whole-original path returned — untyped (sniffed) and typed alike', async () => {
    const key = v1.randomBytes(32)
    const plain = original(2 * CHUNK_SIZE + 4321, JPEG_HEAD)
    const { ref, frames, provider } = stored(key, plain)

    fakeStore(ref.blobId, frames)
    const old = await openBlob(SCOPE, key, ref) // the path the lightbox used to save from

    for (const sealedType of [undefined, 'image/jpeg']) {
      const log = fakeStore(ref.blobId, frames)
      const target = collectingTarget(log)
      await downloadOriginal(provider(), 'Rom 2026', 0, sealedType, target.open)
      expect(target.file.ended).toBe('close')
      expect(target.bytes()).toEqual(old)
      expect(target.bytes()).toEqual(plain)
      expect(target.file.size).toBe(plain.length) // the plaintext size, not the framed one
      expect([...log.gets].sort()).toEqual([0, 1, 2]) // every chunk fetched once — chunk 0 too, even when sniffed first
      expect(log.presign).toBe(1)
    }
  })

  it('names an untyped original from chunk 0 alone, read BEFORE the save target opens, and returns the sniffed type', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(CHUNK_SIZE + 10, JPEG_HEAD)) // the extension is ONLY in the bytes
    const log = fakeStore(ref.blobId, frames)
    const target = collectingTarget(log)

    const sniffed = await downloadOriginal(provider(), 'Trip/2026', 2, undefined, target.open)
    expect(sniffed).toBe('image/jpeg') // what the lightbox back-fills the sealed type with
    expect(target.file.name).toBe('Trip 2026 3.jpg')
    expect(target.file.getsBeforeOpen).toBe(1) // chunk 0, and nothing else, before the target
    expect(log.gets.filter((i) => i === 0)).toHaveLength(1) // …and not fetched again for the write
  })

  it('a type this client cannot name is sniffed like an untyped one; an unsniffable original is .bin', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(100, [0x89, 0x50, 0x4e, 0x47]))
    const target = collectingTarget(fakeStore(ref.blobId, frames))
    expect(await downloadOriginal(provider(), 'Rom', 0, 'image/tiff', target.open)).toBe('image/png')
    expect(target.file.name).toBe('Rom 1.png')

    const other = stored(key, original(100, [0x00, 0x01]))
    const target2 = collectingTarget(fakeStore(other.ref.blobId, other.frames))
    expect(await downloadOriginal(other.provider(), 'Rom', 0, undefined, target2.open)).toBeUndefined()
    expect(target2.file.name).toBe('Rom 1.bin')
  })

  it('a sealed type that names the file opens the save target before ANY request — the picker keeps the click', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(CHUNK_SIZE + 10, JPEG_HEAD))
    const log = fakeStore(ref.blobId, frames)
    const target = collectingTarget(log)

    const sniffed = await downloadOriginal(provider(), 'Rom 2026', 0, 'video/mp4', target.open)
    expect(target.file).toMatchObject({ name: 'Rom 2026 1.mp4', getsBeforeOpen: 0, presignsBeforeOpen: 0 })
    expect(sniffed).toBeUndefined() // nothing was sniffed: there is nothing to back-fill
  })

  it('above the Blob cap with no streaming target it is refused before a single chunk is fetched', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames } = stored(key, original(64, JPEG_HEAD))
    // the real provider over a ref that CLAIMS 1 GiB — the cap is checked against the declared size, before any write
    const big: BlobRef = { ...ref, chunkCount: 4096, byteSize: 4096 * (CHUNK_SIZE + v1.FRAME_OVERHEAD) }
    const huge = () => makeChunkProvider(SCOPE, key, big, async () => frames.map((_, i) => `https://store.test/${ref.blobId}/${i}`))
    const noStream: OpenSaveTarget = (name, size) => openSaveTarget(name, size, { picker: null, registerDownload: async () => null })

    const log = fakeStore(ref.blobId, frames)
    await expect(downloadOriginal(huge(), 'Rom', 0, 'image/jpeg', noStream)).rejects.toBeInstanceOf(SaveTooLarge)
    expect(log.gets).toEqual([])
  })

  it('a chunk that fails mid-stream aborts the target — never closes it — and rethrows', async () => {
    const key = v1.randomBytes(32)
    const { ref, frames, provider } = stored(key, original(3 * CHUNK_SIZE, JPEG_HEAD))
    const log = fakeStore(ref.blobId, frames, 2)
    const target = collectingTarget(log)

    await expect(downloadOriginal(provider(), 'Rom', 0, undefined, target.open)).rejects.toThrow('chunk_get_failed:500')
    expect(target.file.ended).toBe('abort')
  })
})
