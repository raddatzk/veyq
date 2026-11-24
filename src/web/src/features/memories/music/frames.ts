// The frame headers of the two containers whose ends are decoded by slices (ADR 0135 decision 4): MPEG audio Layer III
// (MP3) and ADTS AAC. Measured at implement (2026-11-14): a tail slice cut anywhere in the file decodes in neither Safari
// (MP3, ADTS) nor Chrome (ADTS); one started at a frame header decodes in both. So a tail slice starts at the first
// header that begins a chain of three - one alone is a two-byte pattern any data may hold. And a raw ADTS file has no
// index, so the `<audio>` element only estimates its length (a 10-minute file read as 18 minutes in Safari, 8 hours in
// Chrome): its length is counted from its frames instead, header by header, a slice at a time. No key, no request.
//
// The rules are Memories' Kotlin `music/Frames` (ADR 0155 decision 7.3, § Memories' move), drawn through
// `@veyq/core-js/memories` and held by `contracts/memories/v1-vectors.json`: a change to one is made there, never here. This
// file keeps the reading of the file's slices (`Blob.slice`), which the core's count runs over its own source.
import * as core from '@veyq/core-js/memories'

/** The byte length of the MP3 (Layer III) frame whose header stands at `at`, or 0 where none does. */
export function mp3FrameLength(b: Uint8Array, at: number): number {
  return core.memoriesMp3FrameLength(b, at)
}

/** The ADTS frame whose header stands at `at`: its byte length, its sample rate and its samples; null where none does. */
export function adtsFrame(b: Uint8Array, at: number): { length: number; sampleRate: number; samples: number } | null {
  const f = core.memoriesAdtsFrame(b, at)
  return f ? { length: f.length, sampleRate: f.sampleRate, samples: f.samples } : null
}
export const adtsFrameLength = (b: Uint8Array, at: number): number => adtsFrame(b, at)?.length ?? 0

/** A frame reader the chain knows: one of the two above. */
export type FrameReader = typeof mp3FrameLength | typeof adtsFrameLength
const KIND = new Map<FrameReader, string>([[mp3FrameLength, 'mp3'], [adtsFrameLength, 'adts']])

/** The first offset in `b` where `frameAt` reads `chain` frames in a row, or -1. */
export function firstFrameChain(b: Uint8Array, frameAt: FrameReader, chain = 3): number {
  const kind = KIND.get(frameAt)
  if (!kind) throw new Error('frame_kind')
  return core.memoriesFirstFrameChain(b, kind, chain)
}

/** How much of the file each step of the ADTS count reads. */
const COUNT_STEP = 4 * 1024 * 1024

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
    const step = core.memoriesAdtsCount(chunk, at, file.size, seconds)
    seconds = step.seconds
    if (step.read === 0) return core.memoriesAdtsEnd(seconds, file.size - at) ?? null // a tag after the last frame, or a break
    at += step.read
  }
  return core.memoriesAdtsMs(seconds)
}
