import { describe, it, expect, vi } from 'vitest'
import {
  audioFactsFrom, factsFromSilence, leadingSilenceMs, measureAudioFile, trailingSilenceMs, untrimmed, AudioUnplayable, browserAudioDecoder,
  SILENCE_PEAK, SLICE_BYTES, WHOLE_DECODE_MAX_MS, type AudioReaders, type DecodedAudio,
} from './audioFacts'
import { wavFile } from '../../../test/wavFile'
import { MP3_FRAME, adtsStream, mp3Stream } from '../../../test/audioFrames'

const RATE = 8000

/** Generated PCM: each part `[seconds, amplitude]` - 0 is digital silence, anything else a 440 Hz tone at that peak. */
function pcm(parts: [number, number][], channels = 1, onlyChannel?: number): DecodedAudio {
  const length = Math.round(parts.reduce((n, [sec]) => n + sec, 0) * RATE)
  const data = Array.from({ length: channels }, () => new Float32Array(length))
  let at = 0
  for (const [sec, amp] of parts) {
    const n = Math.round(sec * RATE)
    for (let i = 0; i < n; i++) {
      const v = amp * Math.sin((2 * Math.PI * 440 * i) / RATE)
      for (let c = 0; c < channels; c++) if (onlyChannel === undefined || onlyChannel === c) data[c][at + i] = v
    }
    at += n
  }
  return { numberOfChannels: channels, sampleRate: RATE, length, getChannelData: (c) => data[c] }
}

/** The facts the whole of `audio` gives, as a whole-file decode hands it to the scan. */
const factsOfWhole = (audio: DecodedAudio) =>
  factsFromSilence(Math.round((audio.length / audio.sampleRate) * 1000), leadingSilenceMs(audio), trailingSilenceMs(audio))

describe('the scan: the silence at each end in 20 ms windows, at most 30 s (ADR 0135 decision 4)', () => {
  it('trims leading and trailing silence to the windows that are not silent', () => {
    expect(factsOfWhole(pcm([[0.5, 0], [3, 0.5], [1, 0]]))).toEqual({ durationMs: 4500, trimStartMs: 500, trimEndMs: 3500 })
  })

  it('a file of all silence keeps the whole file', () => {
    expect(factsOfWhole(pcm([[3, 0]]))).toEqual({ durationMs: 3000, trimStartMs: 0, trimEndMs: 3000 })
  })

  it('under one second of content keeps the whole file, one second or more is trimmed to it', () => {
    expect(factsOfWhole(pcm([[1, 0], [0.5, 0.5], [1, 0]]))).toEqual({ durationMs: 2500, trimStartMs: 0, trimEndMs: 2500 })
    expect(factsOfWhole(pcm([[1, 0], [1, 0.5], [1, 0]]))).toEqual({ durationMs: 3000, trimStartMs: 1000, trimEndMs: 2000 })
  })

  it('a file shorter than a second is played whole; an empty one has no length', () => {
    expect(factsOfWhole(pcm([[0.4, 0.5]]))).toEqual({ durationMs: 400, trimStartMs: 0, trimEndMs: 400 })
    expect(factsOfWhole(pcm([]))).toEqual({ durationMs: 0, trimStartMs: 0, trimEndMs: 0 })
  })

  it('-60 dBFS is the line: a peak just under it is silence, just over it is not', () => {
    expect(factsOfWhole(pcm([[1, 0], [2, SILENCE_PEAK * 0.9], [1, 0]]))).toEqual({ durationMs: 4000, trimStartMs: 0, trimEndMs: 4000 })
    expect(factsOfWhole(pcm([[1, 0], [2, SILENCE_PEAK * 1.1], [1, 0]]))).toEqual({ durationMs: 4000, trimStartMs: 1000, trimEndMs: 3000 })
  })

  it('a window is loud when any channel is: a tone on the second channel alone is not trimmed away', () => {
    expect(factsOfWhole(pcm([[0.5, 0], [2, 0.5], [0.5, 0]], 2, 1))).toEqual({ durationMs: 3000, trimStartMs: 500, trimEndMs: 2500 })
  })

  it('looks at most 30 s into each end: a longer silence is trimmed to 30 s', () => {
    const audio = pcm([[40, 0], [5, 0.5], [35, 0]])
    expect([leadingSilenceMs(audio), trailingSilenceMs(audio)]).toEqual([30_000, 30_000])
  })

  it('every fact is an integer of milliseconds', () => {
    const facts = factsOfWhole(pcm([[0.3333, 0], [2.1717, 0.5], [0.4141, 0]]))
    expect(Object.values(facts).every(Number.isInteger)).toBe(true)
  })
})

describe('measureAudioFile: the length from the element, the trim only where it is cheap', () => {
  const blob = (bytes: number) => new Blob([new Uint8Array(bytes) as BlobPart])
  /** Readers whose element answers `durationMs` and whose decoder hands back `decoded` per call (null: the decode fails). */
  function readers(durationMs: number | Error, decoded: (bytes: number, call: number) => DecodedAudio | null) {
    const sizes: number[] = []
    const r: AudioReaders = {
      duration: vi.fn(async () => {
        if (durationMs instanceof Error) throw durationMs
        return durationMs
      }),
      decode: vi.fn(async (buf: ArrayBuffer) => {
        sizes.push(buf.byteLength)
        const out = decoded(buf.byteLength, sizes.length)
        if (!out) throw new DOMException('bad', 'EncodingError')
        return out
      }),
    }
    return { r, sizes }
  }
  const MP3 = { element: 'audio' as const, type: 'audio/mpeg' }
  const ADTS = { element: 'audio' as const, type: 'audio/aac' }
  const M4A = { element: 'audio' as const, type: 'audio/mp4' }
  const WAV = { element: 'audio' as const, type: 'audio/wav' }

  it('a file whose length the element cannot read is AudioUnplayable (E1); nothing is decoded for it', async () => {
    const { r } = readers(new Error('no metadata'), () => pcm([[1, 0.5]]))
    await expect(measureAudioFile(blob(10), M4A, r)).rejects.toBeInstanceOf(AudioUnplayable)
    expect(r.decode).not.toHaveBeenCalled()
  })

  // The tail slice starts at the first frame header of a chain (`frames.ts`): cut anywhere, it decoded in neither Safari
  // (MP3, ADTS) nor Chrome (ADTS), measured 2026-11-14. Lever: decode `file.slice(size - SLICE_BYTES)` as it is.
  it('an MP3 and an ADTS file: the first 4 MiB, and the last 4 MiB from their first frame header, decoded each on its own', async () => {
    const mp3 = mp3Stream(3 * SLICE_BYTES)
    const mp3TailAt = (MP3_FRAME - ((mp3.length - SLICE_BYTES) % MP3_FRAME)) % MP3_FRAME
    const adts = adtsStream(Math.ceil((3 * SLICE_BYTES) / 512) + 1, 512)
    const adtsTailAt = (512 - ((adts.length - SLICE_BYTES) % 512)) % 512
    for (const [kind, bytes, tailAt] of [[MP3, mp3, mp3TailAt], [ADTS, adts, adtsTailAt]] as const) {
      const { r, sizes } = readers(600_000, (_b, call) => (call === 1 ? pcm([[2, 0], [5, 0.5]]) : pcm([[5, 0.5], [3, 0]])))
      await expect(measureAudioFile(new Blob([bytes as BlobPart]), kind, r)).resolves.toMatchObject({ trimStartMs: 2000 })
      expect(sizes, kind.type).toEqual([SLICE_BYTES, SLICE_BYTES - tailAt])
    }
  })

  it('a tail slice with no frame chain in it is no trim from slices: the file falls under the 7-minute rule', async () => {
    const { r, sizes } = readers(WHOLE_DECODE_MAX_MS + 1, () => pcm([[2, 0], [5, 0.5]]))
    await expect(measureAudioFile(blob(3 * SLICE_BYTES), MP3, r)).resolves.toEqual(untrimmed(WHOLE_DECODE_MAX_MS + 1))
    expect(sizes, 'the head only').toEqual([SLICE_BYTES])
  })

  it('an MP3\'s trim counts its tail back from the end of the file', async () => {
    const { r } = readers(600_000, (_b, call) => (call === 1 ? pcm([[2, 0], [5, 0.5]]) : pcm([[5, 0.5], [3, 0]])))
    await expect(measureAudioFile(new Blob([mp3Stream(3 * SLICE_BYTES) as BlobPart]), MP3, r)).resolves.toEqual({ durationMs: 600_000, trimStartMs: 2000, trimEndMs: 597_000 })
  })

  // A raw ADTS file has no index; the element only estimates its length (18 min in Safari, 8 h in Chrome for a 10-minute
  // file, measured 2026-11-14), so it is counted from the frames. Lever: drop the count, keep the element's.
  it('an ADTS file\'s length is counted from its frames, not taken from the element\'s estimate', async () => {
    const frames = 2000
    const { r } = readers(28_762_637, () => pcm([[1, 0.5]]))
    const facts = await measureAudioFile(new Blob([adtsStream(frames) as BlobPart]), ADTS, r)
    expect(facts.durationMs).toBe(Math.round((frames * 1024 * 1000) / 44100))
    expect(r.duration, 'the element still decides whether it plays').toHaveBeenCalledTimes(1)
  })

  it('a short MP3 is one slice, read once for both ends', async () => {
    const { r, sizes } = readers(9000, () => pcm([[1, 0], [7, 0.5], [1, 0]]))
    await expect(measureAudioFile(blob(1000), MP3, r)).resolves.toEqual({ durationMs: 9000, trimStartMs: 1000, trimEndMs: 8000 })
    expect(sizes).toEqual([1000])
  })

  it('an MP3 whose slice does not decode falls under the 7-minute rule: decoded whole up to 7 minutes, else untrimmed', async () => {
    const short = readers(WHOLE_DECODE_MAX_MS, (bytes) => (bytes === SLICE_BYTES ? null : pcm([[1, 0], [5, 0.5]])))
    await expect(measureAudioFile(blob(SLICE_BYTES + 10), MP3, short.r)).resolves.toMatchObject({ trimStartMs: 1000 })
    expect(short.sizes).toEqual([SLICE_BYTES, SLICE_BYTES + 10])
    const long = readers(WHOLE_DECODE_MAX_MS + 1, (bytes) => (bytes === SLICE_BYTES ? null : pcm([[1, 0], [5, 0.5]])))
    await expect(measureAudioFile(blob(SLICE_BYTES + 10), MP3, long.r)).resolves.toEqual(untrimmed(WHOLE_DECODE_MAX_MS + 1))
    expect(long.sizes, 'never decoded whole').toEqual([SLICE_BYTES])
  })

  it('another container (M4A, FLAC, Ogg) is decoded whole only up to 7 minutes; beyond, it has no trim', async () => {
    const short = readers(5000, () => pcm([[1, 0], [3, 0.5], [1, 0]]))
    await expect(measureAudioFile(blob(500), M4A, short.r)).resolves.toEqual({ durationMs: 5000, trimStartMs: 1000, trimEndMs: 4000 })
    expect(short.sizes).toEqual([500])
    const long = readers(WHOLE_DECODE_MAX_MS + 1, () => pcm([[1, 0.5]]))
    await expect(measureAudioFile(blob(500), M4A, long.r)).resolves.toEqual(untrimmed(WHOLE_DECODE_MAX_MS + 1))
    expect(long.r.decode).not.toHaveBeenCalled()
  })

  it('a trim decode that fails gives no trim, never a refusal', async () => {
    const { r } = readers(5000, () => null)
    await expect(measureAudioFile(blob(500), M4A, r)).resolves.toEqual(untrimmed(5000))
  })

  it('a WAV is read from its PCM, whatever its length - nothing is decoded', async () => {
    const file = wavFile({ bits: 16, channels: 2, rate: 8000, parts: [[1.5, 0], [6, 0.5], [0.5, 0]] })
    const { r } = readers(WHOLE_DECODE_MAX_MS * 3, () => pcm([[1, 0.5]]))
    await expect(measureAudioFile(file, WAV, r)).resolves.toEqual({ durationMs: WHOLE_DECODE_MAX_MS * 3, trimStartMs: 1500, trimEndMs: WHOLE_DECODE_MAX_MS * 3 - 500 })
    expect(r.decode).not.toHaveBeenCalled()
  })

  it('a WAV whose format is not read is decoded whole up to 7 minutes, as any other container', async () => {
    const file = wavFile({ bits: 16, channels: 1, rate: 8000, parts: [[2, 0.5]], format: 2 /* ADPCM */ })
    const { r } = readers(2000, () => pcm([[0.5, 0], [1.5, 0.5]]))
    await expect(measureAudioFile(file, WAV, r)).resolves.toEqual({ durationMs: 2000, trimStartMs: 500, trimEndMs: 2000 })
    expect(r.decode).toHaveBeenCalledTimes(1)
  })

  it('without an OfflineAudioContext (this suite\'s node) the browser decoder refuses', async () => {
    await expect(browserAudioDecoder(new ArrayBuffer(4))).rejects.toThrow()
  })
})

describe('audioFactsFrom: a sealed tier\'s facts, or none', () => {
  it('takes three sound integers and refuses anything else, so a broken tier plays the whole file', () => {
    expect(audioFactsFrom({ durationMs: 9000, trimStartMs: 10, trimEndMs: 8000 })).toEqual({ durationMs: 9000, trimStartMs: 10, trimEndMs: 8000 })
    expect(audioFactsFrom(null)).toBeNull()
    expect(audioFactsFrom({ durationMs: 9000, trimStartMs: 10 })).toBeNull()
    expect(audioFactsFrom({ durationMs: 9000, trimStartMs: 10.5, trimEndMs: 8000 })).toBeNull()
    expect(audioFactsFrom({ durationMs: 9000, trimStartMs: -1, trimEndMs: 8000 })).toBeNull()
    expect(audioFactsFrom({ durationMs: 9000, trimStartMs: 8000, trimEndMs: 8000 })).toBeNull()
    expect(audioFactsFrom({ durationMs: 7000, trimStartMs: 10, trimEndMs: 8000 })).toBeNull()
    expect(audioFactsFrom({ durationMs: '9000', trimStartMs: 10, trimEndMs: 8000 })).toBeNull()
  })
})
