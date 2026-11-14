// uc.memory.background-music step 3 (ADR 0135 decisions 3-4) — a music track's facts, measured on the device once, at
// upload (or at an import whose archive lacks them): its length, and the span between the silence at its start and at its
// end, which is what plays, what the progress bar spans and where the crossfades sit. The facts are sealed into the
// track's listed tier at birth (`RestoredMediaFacts`), so no device reads a track to play it.
//
// Nothing is decoded whole but a short file of a container that needs it, because a decode costs far more memory than the
// buffer it keeps (measured at implement, 2026-11-14: a 10-minute MP3 grew Chrome by ~540 MiB, a 200 MiB WAV grew Chrome
// and Safari by 1.4-1.5 GiB, at an 8 kHz context as at 44.1 kHz):
// - **The length** is the `<audio>` element's, read from the local file's metadata ([elementDurationMs]). A file whose
//   metadata does not load is one this browser cannot play: refused ([AudioUnplayable]), the one refusal here.
//   A raw ADTS file is the exception: it has no index, and the element only estimates its length (measured: 18 minutes in
//   Safari, 8 hours in Chrome for a 10-minute file), so its length is counted from its frame headers (`frames.ts`) once
//   the element has shown that it plays.
// - **The trim** reads at most 30 s at each end ([SCAN_LIMIT_MS]): a WAV's PCM straight from the file (`wav.ts`), an MP3's
//   or an ADTS file's first and last 4 MiB decoded each on its own ([SLICE_BYTES]; the tail slice started at a frame
//   header, else it decodes in neither Safari nor, for ADTS, Chrome), any other container decoded whole only up to 7
//   minutes long ([WHOLE_DECODE_MAX_MS]). Beyond that, and wherever a read or a decode fails, the track has no trim and
//   plays whole: a failed trim never refuses a file.
import type { StreamKind } from '../../../core/blob/sniff'
import { readWavEnds } from './wav'
import { adtsDurationMs, adtsFrameLength, firstFrameChain, mp3FrameLength } from './frames'

/** A track's facts, integers in milliseconds of its file. */
export type AudioFacts = { durationMs: number; trimStartMs: number; trimEndMs: number }

/** The level under which a window counts as silent: -60 dBFS as a sample's peak. */
export const SILENCE_PEAK = 10 ** (-60 / 20)
/** The windows the scan steps through from each end. */
export const SCAN_WINDOW_MS = 20
/** How far into each end the scan looks: a silence longer than this is trimmed to this. */
export const SCAN_LIMIT_MS = 30_000
/** The shortest span a trim may leave; a trim that would leave less keeps the whole file. */
export const MIN_TRIMMED_MS = 1000
/** The rate a decode resamples to: ample for finding silence (it bounds the buffer kept, not the decode's peak). */
export const DECODE_SAMPLE_RATE = 8000
/** How much of each end of an MP3 or an ADTS file is decoded: well over 30 s at any bitrate either is made with. */
export const SLICE_BYTES = 4 * 1024 * 1024
/** The longest file of another container that is decoded whole for its trim. */
export const WHOLE_DECODE_MAX_MS = 7 * 60_000
/** How long the element may take to read a file's metadata before the file counts as unplayable here. */
export const METADATA_TIMEOUT_MS = 10_000

/** Samples the scan reads - the part of an `AudioBuffer` it needs; a WAV's ends are handed over in the same shape. */
export type DecodedAudio = { readonly numberOfChannels: number; readonly sampleRate: number; readonly length: number; getChannelData(channel: number): Float32Array }

/** A file whose metadata this browser's `<audio>` element cannot load: refused at upload (E1), imported without facts. */
export class AudioUnplayable extends Error {
  constructor(cause?: unknown) {
    super('audio_unplayable', { cause })
    this.name = 'AudioUnplayable'
  }
}

// --- the scan (pure) -------------------------------------------------------------------------------------------------

function windows(audio: DecodedAudio) {
  const win = Math.max(1, Math.round((audio.sampleRate * SCAN_WINDOW_MS) / 1000))
  const channels = Array.from({ length: audio.numberOfChannels }, (_, c) => audio.getChannelData(c))
  const loud = (from: number): boolean => {
    const to = Math.min(audio.length, from + win)
    for (const data of channels) for (let i = from; i < to; i++) if (Math.abs(data[i]) >= SILENCE_PEAK) return true
    return false
  }
  const toMs = (samples: number) => Math.round((samples / audio.sampleRate) * 1000)
  const limit = Math.min(audio.length, Math.ceil((audio.sampleRate * SCAN_LIMIT_MS) / 1000))
  return { win, loud, toMs, limit }
}

/** How long `audio` is silent from its start: up to its first window that is not, at most [SCAN_LIMIT_MS] (or its length). */
export function leadingSilenceMs(audio: DecodedAudio): number {
  if (audio.length === 0 || audio.sampleRate <= 0) return 0
  const { win, loud, toMs, limit } = windows(audio)
  let at = 0
  while (at < limit && !loud(at)) at += win
  return Math.min(toMs(Math.min(at, limit)), SCAN_LIMIT_MS)
}

/** How long `audio` is silent before its end, counted back from its last sample, at most [SCAN_LIMIT_MS]. */
export function trailingSilenceMs(audio: DecodedAudio): number {
  if (audio.length === 0 || audio.sampleRate <= 0) return 0
  const { win, loud, toMs, limit } = windows(audio)
  let back = 0
  while (back < limit && !loud(Math.max(0, audio.length - back - win))) back += win
  return Math.min(toMs(Math.min(back, limit)), SCAN_LIMIT_MS)
}

/** The facts from a length and the silence at each end: a span of at least [MIN_TRIMMED_MS], else the whole file. */
export function factsFromSilence(durationMs: number, leadMs: number, trailMs: number): AudioFacts {
  const d = Math.max(0, Math.round(durationMs))
  const start = Math.max(0, Math.round(leadMs))
  const end = d - Math.max(0, Math.round(trailMs))
  return end - start >= MIN_TRIMMED_MS ? { durationMs: d, trimStartMs: start, trimEndMs: end } : untrimmed(d)
}

/** A track with no trim: it plays whole. */
export const untrimmed = (durationMs: number): AudioFacts => ({ durationMs, trimStartMs: 0, trimEndMs: durationMs })

// --- the reads (the browser's, behind seams) ----------------------------------------------------------------------------

/** Decode `bytes` to samples. The buffer is detached by the decode: hand in one nothing else reads. */
export type AudioDecoder = (bytes: ArrayBuffer) => Promise<DecodedAudio>
/** A local file's length in ms as an `<audio>` element reads it; throws [AudioUnplayable] where it cannot. */
export type DurationReader = (file: Blob) => Promise<number>

/** The browser's decoder: a one-channel, one-frame `OfflineAudioContext` per call, whose only job is the decode. */
export const browserAudioDecoder: AudioDecoder = async (bytes) => {
  const Ctx = globalThis.OfflineAudioContext as typeof OfflineAudioContext | undefined
  if (!Ctx) throw new Error('no_offline_audio_context')
  return new Ctx(1, 1, DECODE_SAMPLE_RATE).decodeAudioData(bytes)
}

/**
 * The file's length as an `<audio preload="metadata">` on its object URL reads it - nothing is decoded for it. An error,
 * a duration that is not finite, or no answer within [METADATA_TIMEOUT_MS] is [AudioUnplayable]. The element and the URL
 * are let go either way.
 */
export const elementDurationMs: DurationReader = (file) =>
  new Promise<number>((resolve, reject) => {
    const audio = document.createElement('audio')
    const url = URL.createObjectURL(file)
    const done = (fn: () => void) => {
      clearTimeout(timer)
      audio.removeAttribute('src')
      audio.load()
      URL.revokeObjectURL(url)
      fn()
    }
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => {
      const d = audio.duration
      done(() => (Number.isFinite(d) && d > 0 ? resolve(Math.round(d * 1000)) : reject(new AudioUnplayable('duration'))))
    }
    audio.onerror = () => done(() => reject(new AudioUnplayable(audio.error)))
    const timer = setTimeout(() => done(() => reject(new AudioUnplayable('timeout'))), METADATA_TIMEOUT_MS)
    audio.src = url
  })

/** The seams [measureAudioFile] reads through: the browser's by default, stubs in the tests. */
export type AudioReaders = { duration: DurationReader; decode: AudioDecoder }
const browserReaders: AudioReaders = { duration: elementDurationMs, decode: browserAudioDecoder }

/** The slice decode of an MP3 or an ADTS file: whether `kind` is one. */
const decodesBySlice = (kind: StreamKind) => kind.type === 'audio/mpeg' || kind.type === 'audio/aac'

async function decodeOrNull(decode: AudioDecoder, blob: Blob): Promise<DecodedAudio | null> {
  try {
    return await decode(await blob.arrayBuffer())
  } catch {
    return null
  }
}

/** The last [SLICE_BYTES] of an MP3 or an ADTS file, decoded from its first frame header that begins a chain. */
async function decodeTailOrNull(decode: AudioDecoder, file: Blob, kind: StreamKind): Promise<DecodedAudio | null> {
  const tail = new Uint8Array(await file.slice(file.size - SLICE_BYTES).arrayBuffer())
  const at = firstFrameChain(tail, kind.type === 'audio/aac' ? adtsFrameLength : mp3FrameLength)
  if (at < 0) return null
  try {
    return await decode(tail.slice(at).buffer)
  } catch {
    return null
  }
}

/** The silence at each end as the file's container allows it cheaply; null where it does not (the track plays whole). */
async function silenceOf(file: Blob, kind: StreamKind, durationMs: number, readers: AudioReaders): Promise<{ lead: number; trail: number } | null> {
  if (kind.type === 'audio/wav') {
    const ends = await readWavEnds(file, SCAN_LIMIT_MS).catch(() => null)
    if (ends) return { lead: leadingSilenceMs(ends.head), trail: trailingSilenceMs(ends.tail) }
  } else if (decodesBySlice(kind)) {
    const head = await decodeOrNull(readers.decode, file.slice(0, SLICE_BYTES))
    const tail = head && file.size <= SLICE_BYTES ? head : head && (await decodeTailOrNull(readers.decode, file, kind))
    if (head && tail) return { lead: leadingSilenceMs(head), trail: trailingSilenceMs(tail) }
  }
  if (durationMs > WHOLE_DECODE_MAX_MS) return null
  const whole = await decodeOrNull(readers.decode, file)
  return whole ? { lead: leadingSilenceMs(whole), trail: trailingSilenceMs(whole) } : null
}

/**
 * A file's facts: its length from the element (throws [AudioUnplayable] where it cannot be read), and its trim where its
 * container allows it cheaply (see the header) - else none.
 */
export async function measureAudioFile(file: Blob, kind: StreamKind, readers: AudioReaders = browserReaders): Promise<AudioFacts> {
  let durationMs: number
  try {
    durationMs = await readers.duration(file)
  } catch (e) {
    throw e instanceof AudioUnplayable ? e : new AudioUnplayable(e)
  }
  if (kind.type === 'audio/aac') durationMs = (await adtsDurationMs(file).catch(() => null)) ?? durationMs
  const silence = await silenceOf(file, kind, durationMs, readers).catch(() => null)
  return silence ? factsFromSilence(durationMs, silence.lead, silence.trail) : untrimmed(durationMs)
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
