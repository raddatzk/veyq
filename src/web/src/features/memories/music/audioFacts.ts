// uc.memory.background-music step 3 (ADR 0135 decision 4) — a music track's facts, measured on the device once, at upload
// (or at an import whose archive lacks them): its length, and the span between the silence at its start and at its end,
// which is what plays, what the progress bar spans and where the crossfades sit. The facts are sealed into the track's
// listed tier at birth (`RestoredMediaFacts`), so no device decodes a track to play it.
//
// The scan is pure ([audioFactsOf]); the decode is the browser's ([measureAudio]): an `OfflineAudioContext` at a low
// sample rate, because `decodeAudioData` resamples to its context's rate - a minute of a channel is 8000 × 60 floats
// (1.9 MB) instead of 44100 × 60 (10.6 MB). What a decode of a long file costs was measured in Chrome and WebKit
// (cr-background-music-from-own-audio phase 2: the commit records the numbers).

/** A track's facts, integers in milliseconds of its file. */
export type AudioFacts = { durationMs: number; trimStartMs: number; trimEndMs: number }

/** The level under which a window counts as silent: -60 dBFS as a sample's peak. */
export const SILENCE_PEAK = 10 ** (-60 / 20)
/** The windows the scan steps through from each end. */
export const SCAN_WINDOW_MS = 20
/** The shortest span a trim may leave; a trim that would leave less keeps the whole file. */
export const MIN_TRIMMED_MS = 1000
/** The rate the decode resamples to: low, for the memory a long file costs; ample for finding silence. */
export const DECODE_SAMPLE_RATE = 8000

/** What the scan reads of a decoded file - the part of an `AudioBuffer` it needs. */
export type DecodedAudio = { readonly numberOfChannels: number; readonly sampleRate: number; readonly length: number; getChannelData(channel: number): Float32Array }

/** A file this browser could not decode: refused at upload (E1), imported without facts. */
export class AudioUndecodable extends Error {
  constructor(cause?: unknown) {
    super('audio_undecodable', { cause })
    this.name = 'AudioUndecodable'
  }
}

/** The facts of decoded samples: the length, and the span from the first window to the last that is not silent. */
export function audioFactsOf(audio: DecodedAudio): AudioFacts {
  const { length, sampleRate } = audio
  const toMs = (samples: number) => Math.round((samples / sampleRate) * 1000)
  const durationMs = sampleRate > 0 ? toMs(length) : 0
  const whole: AudioFacts = { durationMs, trimStartMs: 0, trimEndMs: durationMs }
  if (length === 0 || sampleRate <= 0) return whole
  const channels = Array.from({ length: audio.numberOfChannels }, (_, c) => audio.getChannelData(c))
  const win = Math.max(1, Math.round((sampleRate * SCAN_WINDOW_MS) / 1000))
  const windows = Math.ceil(length / win)
  const loud = (w: number): boolean => {
    const from = w * win
    const to = Math.min(length, from + win)
    for (const data of channels) {
      for (let i = from; i < to; i++) if (Math.abs(data[i]) >= SILENCE_PEAK) return true
    }
    return false
  }
  let first = 0
  while (first < windows && !loud(first)) first++
  if (first === windows) return whole // all silence: nothing to trim to
  let last = windows - 1
  while (last > first && !loud(last)) last--
  const trimStartMs = toMs(first * win)
  const trimEndMs = Math.min(durationMs, toMs(Math.min(length, (last + 1) * win)))
  return trimEndMs - trimStartMs < MIN_TRIMMED_MS ? whole : { durationMs, trimStartMs, trimEndMs }
}

/** Decode `bytes` to samples. The buffer is detached by the decode: hand in one nothing else reads. */
export type AudioDecoder = (bytes: ArrayBuffer) => Promise<DecodedAudio>

/** The browser's decoder: a one-channel, one-frame `OfflineAudioContext` whose only job is the resampling decode. */
export const browserAudioDecoder: AudioDecoder = async (bytes) => {
  const Ctx = globalThis.OfflineAudioContext as typeof OfflineAudioContext | undefined
  if (!Ctx) throw new AudioUndecodable()
  try {
    return await new Ctx(1, 1, DECODE_SAMPLE_RATE).decodeAudioData(bytes)
  } catch (e) {
    throw new AudioUndecodable(e)
  }
}

/** A file's facts: decoded by `decode`, then scanned. Throws [AudioUndecodable] where it does not decode. */
export async function measureAudio(bytes: ArrayBuffer, decode: AudioDecoder = browserAudioDecoder): Promise<AudioFacts> {
  let audio: DecodedAudio
  try {
    audio = await decode(bytes)
  } catch (e) {
    throw e instanceof AudioUndecodable ? e : new AudioUndecodable(e)
  }
  return audioFactsOf(audio)
}

/** A sealed tier's facts, where all three are there and make a span; else null (the whole file plays, decision 6). */
export function audioFactsFrom(meta: { durationMs?: unknown; trimStartMs?: unknown; trimEndMs?: unknown } | null | undefined): AudioFacts | null {
  const n = (v: unknown) => (typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : null)
  const durationMs = n(meta?.durationMs)
  const trimStartMs = n(meta?.trimStartMs)
  const trimEndMs = n(meta?.trimEndMs)
  if (durationMs === null || trimStartMs === null || trimEndMs === null) return null
  if (trimEndMs <= trimStartMs || trimEndMs > durationMs) return null
  return { durationMs, trimStartMs, trimEndMs }
}
