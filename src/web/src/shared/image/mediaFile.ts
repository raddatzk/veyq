// A Blob as the platform's media headers read it (`@veyq/core-js/media`, ADR 0155 decision 7.2, the media headers' slice):
// its size, and each read a slice of it. Only the slices the core asks for are read, never the whole file — at pick time
// every picked file is read at once, so what one read held would be multiplied by the pick. A read the browser refuses
// rejects, and so does the core's answer.
import type { MediaFile, MediaInflater } from '@veyq/core-js/media'

export function mediaFileOf(blob: Blob): MediaFile {
  return {
    size: blob.size,
    read: async (from, length) => new Uint8Array(await blob.slice(from, from + length).arrayBuffer()),
  }
}

/**
 * The browser's zlib for the core's tag reader (a PNG's compressed XMP or raw profile; the platform module carries no
 * zlib on JavaScript): `bytes` inflated as a zlib stream, read a chunk at a time and given up past `cap` bytes, so a
 * bomb costs at most the cap; `null` for a broken, truncated or longer stream.
 */
export const mediaInflater: MediaInflater = {
  async inflate(bytes, cap) {
    try {
      const reader = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate')).getReader()
      const parts: Uint8Array[] = []
      let length = 0
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        length += value.length
        if (length > cap) {
          await reader.cancel()
          return null
        }
        parts.push(value)
      }
      const out = new Uint8Array(length)
      let at = 0
      for (const p of parts) {
        out.set(p, at)
        at += p.length
      }
      return out
    } catch {
      return null
    }
  },
}
