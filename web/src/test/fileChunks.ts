// A decrypted file as a viewer reads it, without the crypto or the network: a chunk provider over plain bytes, cut into the
// real chunk size (`sw/range`), recording which chunks were asked for and which reads were aborted. A test of the file page
// hands it where the app would hand the source's `chunks()` (the decrypt itself is `transport.ts`'s, proven there).
import type { BlobChunkProvider } from '../core/blob/transport'
import { FRAME_OVERHEAD, PLAINTEXT_CHUNK_SIZE } from '../sw/range'

export type FakeProvider = BlobChunkProvider & { read: number[] }

export function providerOf(bytes: Uint8Array, over: Partial<BlobChunkProvider> = {}): FakeProvider {
  const chunkCount = Math.max(1, Math.ceil(bytes.length / PLAINTEXT_CHUNK_SIZE))
  const read: number[] = []
  return {
    read,
    chunkCount,
    byteSize: bytes.length + chunkCount * FRAME_OVERHEAD,
    meta: () => null,
    getPlaintextChunk: async (i) => {
      read.push(i)
      return bytes.slice(i * PLAINTEXT_CHUNK_SIZE, (i + 1) * PLAINTEXT_CHUNK_SIZE)
    },
    ...over,
  }
}

export function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s)
}

export const PNG_BYTES = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52])
export const PDF_BYTES = Uint8Array.from([...utf8('%PDF-1.7\n'), 0x25, 0xe2, 0xe3, 0xcf, 0xd3, 0x0a])
export const MP4_BYTES = Uint8Array.from([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 0, 0x69, 0x73, 0x6f, 0x6d])
export const MP3_BYTES = Uint8Array.from([0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0])
