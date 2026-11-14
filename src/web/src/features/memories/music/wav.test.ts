import { describe, it, expect, vi } from 'vitest'
import { parseWavHeader, readWavEnds } from './wav'
import { leadingSilenceMs, trailingSilenceMs } from './audioFacts'
import { wavBytes, wavFile, type WavSpec } from '../../../test/wavFile'

const PARTS: [number, number][] = [[1, 0], [2, 0.5], [0.5, 0]]

describe('a WAV\'s ends read from its PCM (ADR 0135 decision 4): no decode, whatever its size', () => {
  const formats: [string, Partial<WavSpec>][] = [
    ['8-bit', { bits: 8 }], ['16-bit', { bits: 16 }], ['24-bit', { bits: 24 }], ['32-bit', { bits: 32 }],
    ['32-bit float', { bits: 32, float: true }], ['extensible 16-bit', { bits: 16, format: 0xfffe }],
    ['extensible float', { bits: 32, float: true, format: 0xfffe }], ['a LIST chunk before the data', { bits: 16, listBytes: 3001 }],
  ]
  for (const [what, spec] of formats) {
    it(`finds the silence at both ends of ${what} PCM`, async () => {
      const file = wavFile({ bits: 16, channels: 2, rate: 8000, parts: PARTS, ...spec } as WavSpec)
      const { head, tail } = await readWavEnds(file, 30_000)
      expect([head.numberOfChannels, head.sampleRate, leadingSilenceMs(head), trailingSilenceMs(tail)]).toEqual([2, 8000, 1000, 500])
    })
  }

  it('reads only the first and the last 30 s of a long file, by slices', async () => {
    const file = wavFile({ bits: 16, channels: 1, rate: 8000, parts: [[1, 0], [100, 0.5], [2, 0]] })
    const slice = vi.spyOn(file, 'slice')
    const { head, tail } = await readWavEnds(file, 30_000)
    expect([head.length, tail.length]).toEqual([240_000, 240_000])
    expect([leadingSilenceMs(head), trailingSilenceMs(tail)]).toEqual([1000, 2000])
    const read = slice.mock.calls.map(([from, to]) => (to ?? file.size) - (from ?? 0))
    expect(Math.max(...read), 'no slice larger than the header read').toBeLessThanOrEqual(64 * 1024 > 480_000 ? 64 * 1024 : 480_000)
  })

  it('refuses a format it does not read, and a file that is no WAV', () => {
    const adpcm = wavBytes({ bits: 16, channels: 1, rate: 8000, parts: PARTS, format: 2 })
    expect(() => parseWavHeader(adpcm, adpcm.length)).toThrow('wav_format')
    const float64 = wavBytes({ bits: 32, channels: 1, rate: 8000, parts: PARTS, format: 3 })
    new DataView(float64.buffer).setUint16(34, 64, true) // bits 64, the block no longer agrees
    expect(() => parseWavHeader(float64, float64.length)).toThrow('wav_format')
    expect(() => parseWavHeader(new Uint8Array(64), 64)).toThrow('not_wav')
  })

  it('takes a data size of 0 or past the end (a streamed writer\'s) as running to the file\'s end', () => {
    const bytes = wavBytes({ bits: 16, channels: 1, rate: 8000, parts: PARTS })
    const at = bytes.length - 3.5 * 8000 * 2 - 4 // the data chunk's size field
    new DataView(bytes.buffer).setUint32(at, 0, true)
    expect(parseWavHeader(bytes, bytes.length)?.dataBytes).toBe(3.5 * 8000 * 2)
  })
})
