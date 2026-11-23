// A Blob as the platform's media headers read it (`@veyq/core-js/media`, ADR 0155 decision 7.2, the media headers' slice):
// its size, and each read a slice of it. Only the slices the core asks for are read, never the whole file — at pick time
// every picked file is read at once, so what one read held would be multiplied by the pick. A read the browser refuses
// rejects, and so does the core's answer.
import type { MediaFile } from '@veyq/core-js/media'

export function mediaFileOf(blob: Blob): MediaFile {
  return {
    size: blob.size,
    read: async (from, length) => new Uint8Array(await blob.slice(from, from + length).arrayBuffer()),
  }
}
