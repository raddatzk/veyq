// @vitest-environment node
// A copy's payload (cr-copy-node Decision 3, `copySource.ts`): the original read BY FRAME on demand, sliced the way the
// upload pump slices it, never held whole; every frame checked against the size the walk declared (Decision 2).
import { describe, it, expect, vi } from 'vitest'
import { framedPayload, framesFor, framesOfBytes } from './copySource'
import { ExportError } from './manifest'
import type { PlaintextFrames } from './hooks'

const FRAME = 4
const bytesOf = (n: number) => new Uint8Array(n).map((_, i) => (i * 7 + 3) % 251)
/** The frames of `bytes` (FRAME each, the last the rest), each read recorded. */
function framesOf(bytes: Uint8Array, frameSize = FRAME): PlaintextFrames & { reads: number[] } {
  const reads: number[] = []
  return {
    frameSize,
    chunkCount: framesFor(bytes.length, frameSize),
    reads,
    getPlaintextChunk: vi.fn(async (i: number) => {
      reads.push(i)
      return bytes.slice(i * frameSize, (i + 1) * frameSize)
    }),
  }
}
const shape = (size: number) => ({ size, name: 'doc.txt', type: 'text/plain', lastModified: 7, path: 'Reisen/doc.txt' })
const read = async (b: { arrayBuffer(): Promise<ArrayBuffer> }) => Array.from(new Uint8Array(await b.arrayBuffer()))

describe('framedPayload', () => {
  it('is a sized, named, typed payload that reads nothing until a slice is read', () => {
    const frames = framesOf(bytesOf(10))
    const p = framedPayload(frames, shape(10))
    expect([p.size, p.name, p.type, p.lastModified]).toEqual([10, 'doc.txt', 'text/plain', 7])
    p.slice(0, 4)
    expect(frames.reads).toEqual([])
  })

  it('a frame-aligned slice - the pump\'s - reads exactly that frame, once', async () => {
    const bytes = bytesOf(10)
    const frames = framesOf(bytes)
    const p = framedPayload(frames, shape(10))
    for (let i = 0; i < 3; i++) expect(await read(p.slice(i * FRAME, (i + 1) * FRAME))).toEqual(Array.from(bytes.slice(i * FRAME, (i + 1) * FRAME)))
    expect(frames.reads).toEqual([0, 1, 2])
  })

  it('a slice across frames reads only the frames it covers; slices of slices, negative offsets and the type as Blob.slice', async () => {
    const bytes = bytesOf(10)
    const frames = framesOf(bytes)
    const p = framedPayload(frames, shape(10))
    expect(await read(p.slice(3, 6))).toEqual(Array.from(bytes.slice(3, 6)))
    expect(frames.reads).toEqual([0, 1])
    const tail = p.slice(-3, undefined, 'image/jpeg')
    expect([tail.size, tail.type]).toEqual([3, 'image/jpeg'])
    expect(await read(tail)).toEqual(Array.from(bytes.slice(7)))
    expect(await read(p.slice(2, 9).slice(1, 3))).toEqual(Array.from(bytes.slice(3, 5)))
    expect(await read(p.slice(8, 2))).toEqual([])
  })

  it('an empty payload is one empty frame', async () => {
    const p = framedPayload(framesOf(new Uint8Array(0)), shape(0))
    expect(await read(p.slice(0, 0))).toEqual([])
    expect(p.size).toBe(0)
  })

  it('refuses frames that are not those of the declared size, before anything is read', () => {
    const frames = framesOf(bytesOf(10))
    expect(() => framedPayload(frames, shape(13))).toThrow(ExportError)
    expect(() => framedPayload({ ...frames, frameSize: 0 }, shape(10))).toThrow(ExportError)
    expect(frames.reads).toEqual([])
  })

  it('refuses a frame of another length than the declared size gives it (size_mismatch)', async () => {
    const frames = framesOf(bytesOf(10))
    // the walk declared 9 bytes in three frames: the last must be 1 byte, the original's is 2
    const p = framedPayload(frames, shape(9))
    await expect(p.slice(8, 9).arrayBuffer()).rejects.toMatchObject({ code: 'size_mismatch', detail: 'Reisen/doc.txt' })
    const short: PlaintextFrames = { ...frames, getPlaintextChunk: async () => new Uint8Array(1) }
    await expect(framedPayload(short, shape(10)).slice(0, 4).arrayBuffer()).rejects.toMatchObject({ code: 'size_mismatch' })
  })

  it('a stopped copy reads nothing more', async () => {
    const frames = framesOf(bytesOf(10))
    const control = new AbortController()
    const p = framedPayload(frames, shape(10), control.signal)
    control.abort()
    await expect(p.slice(0, 4).arrayBuffer()).rejects.toBeDefined()
    expect(frames.reads).toEqual([])
  })
})

describe('framesOfBytes', () => {
  it('bytes already in memory are one frame of themselves', async () => {
    const bytes = bytesOf(9)
    const p = framedPayload(framesOfBytes(bytes), shape(9))
    expect(await read(p.slice(2, 7))).toEqual(Array.from(bytes.slice(2, 7)))
    expect(await read(framedPayload(framesOfBytes(new Uint8Array(0)), shape(0)).slice(0))).toEqual([])
  })
})
