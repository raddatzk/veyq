// A WAV file's samples at both ends, read straight from the file (ADR 0135 decision 4): its `fmt ` and `data` chunks are
// parsed from its head, and only the bytes of the first and the last `limitMs` are sliced out of the file and converted -
// a 200 MiB WAV costs a few MiB here, where a decode cost 1.4 GiB. Integer PCM of 8, 16, 24 and 32 bit and 32-bit float,
// plain or as WAVE_FORMAT_EXTENSIBLE; anything else throws, and the caller then decides without it. No key, no request.
//
// The header, the samples and the ends' length are Memories' Kotlin `music/Wav` (ADR 0155 decision 7.3, § Memories' move),
// drawn through `@veyq/core-js/memories` and held by `contracts/memories/v1-vectors.json`: a change to one is made there,
// never here. This file keeps the reading of the file's slices (`Blob.slice`).
import * as core from '@veyq/core-js/memories'
import type { DecodedAudio } from './audioFacts'

/** What the scan needs of a WAV's header. */
export type WavFormat = { channels: number; sampleRate: number; bitsPerSample: number; float: boolean; blockAlign: number; dataOffset: number; dataBytes: number }

/** How much of the head is read to find the `data` chunk, at most (a `LIST` chunk of tags may stand before it). */
const HEADER_MAX = 1024 * 1024

/** Parse the header in `head` (the file's first bytes) for a file of `fileSize` bytes; null where `data` lies beyond it.
 *  Throws `Error('not_wav')` for no WAV, `Error('wav_format')` for one this does not read. */
export function parseWavHeader(head: Uint8Array, fileSize: number): WavFormat | null {
  const f = core.memoriesWavHeader(head, fileSize)
  return f
    ? { channels: f.channels, sampleRate: f.sampleRate, bitsPerSample: f.bitsPerSample, float: f.float, blockAlign: f.blockAlign, dataOffset: f.dataOffset, dataBytes: f.dataBytes }
    : null
}

/** Frames of `bytes` (whole blocks) as samples per channel in [-1, 1]. */
export function wavSamples(bytes: Uint8Array, fmt: WavFormat): DecodedAudio {
  const data = core.memoriesWavSamples(bytes, fmt)
  return { numberOfChannels: fmt.channels, sampleRate: fmt.sampleRate, length: data[0]?.length ?? 0, getChannelData: (c) => data[c] }
}

/** The samples of the first and the last `limitMs` of a WAV file's data (both the whole data where it is shorter). */
export async function readWavEnds(file: Blob, limitMs: number): Promise<{ head: DecodedAudio; tail: DecodedAudio }> {
  const headOf = async (size: number) => parseWavHeader(new Uint8Array(await file.slice(0, size).arrayBuffer()), file.size)
  const fmt = (await headOf(64 * 1024)) ?? (await headOf(HEADER_MAX))
  if (!fmt) throw new Error('wav_no_data')
  const want = core.memoriesWavEndBytes(fmt, limitMs)
  const end = fmt.dataOffset + fmt.dataBytes
  const bytesAt = async (from: number) => new Uint8Array(await file.slice(from, from + want).arrayBuffer())
  return { head: wavSamples(await bytesAt(fmt.dataOffset), fmt), tail: wavSamples(await bytesAt(end - want), fmt) }
}
