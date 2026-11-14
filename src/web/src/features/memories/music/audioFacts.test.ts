import { describe, it, expect } from 'vitest'
import {
  audioFactsFrom, audioFactsOf, measureAudio, AudioUndecodable, browserAudioDecoder, SILENCE_PEAK, type DecodedAudio,
} from './audioFacts'

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

describe('audioFactsOf: the length and the span between the silence at both ends (ADR 0135 decision 4)', () => {
  it('trims leading and trailing silence to the 20 ms windows that are not silent', () => {
    expect(audioFactsOf(pcm([[0.5, 0], [3, 0.5], [1, 0]]))).toEqual({ durationMs: 4500, trimStartMs: 500, trimEndMs: 3500 })
  })

  it('a file of all silence keeps the whole file', () => {
    expect(audioFactsOf(pcm([[3, 0]]))).toEqual({ durationMs: 3000, trimStartMs: 0, trimEndMs: 3000 })
  })

  it('under one second of content keeps the whole file, one second or more is trimmed to it', () => {
    expect(audioFactsOf(pcm([[1, 0], [0.5, 0.5], [1, 0]]))).toEqual({ durationMs: 2500, trimStartMs: 0, trimEndMs: 2500 })
    expect(audioFactsOf(pcm([[1, 0], [1, 0.5], [1, 0]]))).toEqual({ durationMs: 3000, trimStartMs: 1000, trimEndMs: 2000 })
  })

  it('a file shorter than a second is played whole; an empty one has no length', () => {
    expect(audioFactsOf(pcm([[0.4, 0.5]]))).toEqual({ durationMs: 400, trimStartMs: 0, trimEndMs: 400 })
    expect(audioFactsOf(pcm([]))).toEqual({ durationMs: 0, trimStartMs: 0, trimEndMs: 0 })
  })

  it('-60 dBFS is the line: a peak just under it is silence, just over it is not', () => {
    expect(audioFactsOf(pcm([[1, 0], [2, SILENCE_PEAK * 0.9], [1, 0]]))).toEqual({ durationMs: 4000, trimStartMs: 0, trimEndMs: 4000 })
    expect(audioFactsOf(pcm([[1, 0], [2, SILENCE_PEAK * 1.1], [1, 0]]))).toEqual({ durationMs: 4000, trimStartMs: 1000, trimEndMs: 3000 })
  })

  it('a window is loud when any channel is: a tone on the second channel alone is not trimmed away', () => {
    expect(audioFactsOf(pcm([[0.5, 0], [2, 0.5], [0.5, 0]], 2, 1))).toEqual({ durationMs: 3000, trimStartMs: 500, trimEndMs: 2500 })
  })

  it('every fact is an integer of milliseconds', () => {
    const facts = audioFactsOf(pcm([[0.3333, 0], [2.1717, 0.5], [0.4141, 0]]))
    expect(Object.values(facts).every(Number.isInteger)).toBe(true)
  })
})

describe('measureAudio: a decode, then the scan', () => {
  it('scans what the decoder hands back', async () => {
    const audio = pcm([[0.5, 0], [2, 0.5]])
    await expect(measureAudio(new ArrayBuffer(4), async () => audio)).resolves.toEqual({ durationMs: 2500, trimStartMs: 500, trimEndMs: 2500 })
  })

  it('a decode that fails is AudioUndecodable (E1), whatever the decoder threw', async () => {
    await expect(measureAudio(new ArrayBuffer(4), async () => { throw new DOMException('bad', 'EncodingError') })).rejects.toBeInstanceOf(AudioUndecodable)
  })

  it('without an OfflineAudioContext (this suite\'s node) the browser decoder refuses', async () => {
    await expect(browserAudioDecoder(new ArrayBuffer(4))).rejects.toBeInstanceOf(AudioUndecodable)
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
