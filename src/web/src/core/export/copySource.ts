// What a copy hands an import hook as a payload (ADR 0082 decision 11): the original, read BY FRAME
// and decrypted on demand, never held whole. It is a `PayloadFile` - a size, a type, a name and `slice(start, end)` whose
// `arrayBuffer()` reads only the frames that range covers - so the one upload pump (`core/blob/transport.ts`), which asks
// one frame-sized slice at a time, re-encrypts the copy while it reads the original one frame at a time: what the tab holds
// is the pump's window of frames, not the file.
//
// The walk declared each payload's size; every frame read is checked against it (decision 2): the frame count the size
// implies, each frame the frame size but the last, the last the rest. A frame that disagrees refuses the read
// (`ExportError` `size_mismatch`), so a copy never uploads a different file than the one it said it would.
import type { PayloadFile, PlaintextFrames } from './hooks'
import { ExportError } from './manifest'

/** The frame count a payload of `size` bytes has in frames of `frameSize` (an empty payload is one empty frame). */
export const framesFor = (size: number, frameSize: number): number => Math.max(1, Math.ceil(size / frameSize))

/** A range of a framed payload: itself readable, and sliceable again. */
export interface Ranged extends PayloadFile {
  slice(start?: number, end?: number, contentType?: string): Ranged
  arrayBuffer(): Promise<ArrayBuffer>
}

type Shape = { size: number; name: string; type: string; lastModified: number; path: string }

/** The original's frames as a seekable payload of the size the walk declared. Nothing is read before a slice is. */
export function framedPayload(frames: PlaintextFrames, shape: Shape, signal?: AbortSignal): Ranged {
  const { size, path } = shape
  const frameSize = frames.frameSize
  if (!(frameSize > 0) || frames.chunkCount !== framesFor(size, frameSize)) {
    throw new ExportError('size_mismatch', `${path} has ${frames.chunkCount} frames, not those of ${size} bytes`, path)
  }
  const last = frames.chunkCount - 1
  const expected = (i: number) => (i < last ? frameSize : size - last * frameSize)

  async function frame(i: number): Promise<Uint8Array> {
    if (signal?.aborted) throw signal.reason ?? new DOMException('Copy cancelled', 'AbortError')
    const bytes = await frames.getPlaintextChunk(i)
    if (bytes.length !== expected(i)) throw new ExportError('size_mismatch', `${path} is not the size the walk declared`, path)
    return bytes
  }

  /** The bytes `[start, end)` of the payload (already clamped), read frame by frame. */
  async function read(start: number, end: number): Promise<ArrayBuffer> {
    const out = new Uint8Array(end - start)
    if (end <= start) return out.buffer
    for (let i = Math.floor(start / frameSize); i * frameSize < end; i++) {
      const bytes = await frame(i)
      const from = Math.max(start, i * frameSize)
      const to = Math.min(end, i * frameSize + bytes.length)
      out.set(bytes.subarray(from - i * frameSize, to - i * frameSize), from - start)
    }
    return out.buffer
  }

  const clamp = (v: number | undefined, fallback: number, length: number): number => {
    if (v === undefined) return fallback
    const n = Math.trunc(v)
    return n < 0 ? Math.max(0, length + n) : Math.min(n, length)
  }
  /** A range of the payload, as `Blob.slice` resolves its arguments (negative counts from the end). */
  function range(offset: number, length: number, type: string): Ranged {
    return {
      size: length,
      type,
      name: shape.name,
      lastModified: shape.lastModified,
      slice(start?: number, end?: number, contentType?: string) {
        const a = clamp(start, 0, length)
        const b = Math.max(a, clamp(end, length, length))
        return range(offset + a, b - a, contentType ?? '')
      },
      arrayBuffer: () => read(offset, offset + length),
    }
  }
  return range(0, size, shape.type)
}

/** Bytes already in memory as frames (one frame, the bytes themselves): a payload a hook produced whole, e.g. a cover. */
export function framesOfBytes(bytes: Uint8Array): PlaintextFrames {
  return { frameSize: Math.max(1, bytes.length), chunkCount: 1, getPlaintextChunk: async () => bytes }
}
