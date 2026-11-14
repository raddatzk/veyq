// The frame headers of the two containers whose ends are decoded by slices (ADR 0135 decision 4): MPEG audio Layer III
// (MP3) and ADTS AAC. Measured at implement (2026-11-14): a tail slice cut anywhere in the file decodes in neither Safari
// (MP3, ADTS) nor Chrome (ADTS); one started at a frame header decodes in both. So a tail slice starts at the first
// header that begins a chain of three - one alone is a two-byte pattern any data may hold. And a raw ADTS file has no
// index, so the `<audio>` element only estimates its length (a 10-minute file read as 18 minutes in Safari, 8 hours in
// Chrome): its length is counted from its frames instead, header by header, a slice at a time. Pure; no key, no request.

const MPEG1_KBPS = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
const MPEG2_KBPS = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
const MPEG_RATES: Record<number, number[]> = { 3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000] }
const ADTS_RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]

/** The byte length of the MP3 (Layer III) frame whose header stands at `at`, or 0 where none does. */
export function mp3FrameLength(b: Uint8Array, at: number): number {
  if (at + 4 > b.length || b[at] !== 0xff || (b[at + 1] & 0xe0) !== 0xe0) return 0
  const version = (b[at + 1] >> 3) & 3
  const layer = (b[at + 1] >> 1) & 3
  const bitrate = b[at + 2] >> 4
  const rate = (b[at + 2] >> 2) & 3
  if (version === 1 || layer !== 1 || bitrate === 0 || bitrate === 15 || rate === 3) return 0
  const kbps = (version === 3 ? MPEG1_KBPS : MPEG2_KBPS)[bitrate]
  const padding = (b[at + 2] >> 1) & 1
  return Math.floor(((version === 3 ? 144 : 72) * kbps * 1000) / MPEG_RATES[version][rate]) + padding
}

/** The ADTS frame whose header stands at `at`: its byte length, its sample rate and its samples; null where none does. */
export function adtsFrame(b: Uint8Array, at: number): { length: number; sampleRate: number; samples: number } | null {
  if (at + 7 > b.length || b[at] !== 0xff || (b[at + 1] & 0xf6) !== 0xf0) return null
  const rate = ADTS_RATES[(b[at + 2] >> 2) & 0x0f]
  const length = ((b[at + 3] & 0x03) << 11) | (b[at + 4] << 3) | (b[at + 5] >> 5)
  const headerBytes = b[at + 1] & 1 ? 7 : 9
  if (!rate || length < headerBytes) return null
  return { length, sampleRate: rate, samples: 1024 * ((b[at + 6] & 0x03) + 1) }
}
export const adtsFrameLength = (b: Uint8Array, at: number): number => adtsFrame(b, at)?.length ?? 0

/** The first offset in `b` where `frameAt` reads `chain` frames in a row, or -1. */
export function firstFrameChain(b: Uint8Array, frameAt: (b: Uint8Array, at: number) => number, chain = 3): number {
  for (let at = 0; at + 4 <= b.length; at++) {
    let p = at
    let n = 0
    for (; n < chain; n++) {
      const len = frameAt(b, p)
      if (!len) break
      p += len
    }
    if (n === chain) return at
  }
  return -1
}

/** How much of the file each step of the ADTS count reads. */
const COUNT_STEP = 4 * 1024 * 1024
/** What may follow the last frame (an ID3v1 or APE tag) without the count giving up. */
const TRAILER_MAX = 64 * 1024

/**
 * A raw ADTS file's length in ms, counted from its frame headers: from its first chain of frames, each header read and
 * the next one found by its length, a slice at a time; a tag of up to 64 KiB after the last frame ends it. Null where the
 * chain breaks before that (the caller then keeps the element's length).
 */
export async function adtsDurationMs(file: Blob): Promise<number | null> {
  const first = new Uint8Array(await file.slice(0, Math.min(file.size, 64 * 1024)).arrayBuffer())
  let at = firstFrameChain(first, adtsFrameLength)
  if (at < 0) return null
  let seconds = 0
  while (at < file.size) {
    const chunk = new Uint8Array(await file.slice(at, Math.min(file.size, at + COUNT_STEP)).arrayBuffer())
    let p = 0
    for (;;) {
      const frame = adtsFrame(chunk, p)
      if (!frame) break
      if (p + frame.length > chunk.length && at + p + frame.length < file.size) break // read on from this header
      seconds += frame.samples / frame.sampleRate
      p += frame.length
      if (at + p >= file.size) break
    }
    if (p === 0) return file.size - at <= TRAILER_MAX ? Math.round(seconds * 1000) : null // a tag after the last frame, or a break
    at += p
  }
  return Math.round(seconds * 1000)
}
