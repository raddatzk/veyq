import { describe, it, expect } from 'vitest'
import { adtsDurationMs, adtsFrame, adtsFrameLength, firstFrameChain, mp3FrameLength } from './frames'
import { MP3_FRAME, adtsHeader, adtsStream, mp3Stream } from '../../../test/audioFrames'

const blob = (b: Uint8Array) => new Blob([b as BlobPart])

describe('the frame headers a tail slice is aligned on (ADR 0135 decision 4)', () => {
  it('reads an MP3 frame\'s length from its header, and nothing where no header stands', () => {
    expect(mp3FrameLength(Uint8Array.from([0xff, 0xfb, 0x90, 0x00]), 0)).toBe(MP3_FRAME)
    expect(mp3FrameLength(Uint8Array.from([0xff, 0xfb, 0x92, 0x00]), 0), 'the padding byte').toBe(MP3_FRAME + 1)
    expect(mp3FrameLength(Uint8Array.from([0xff, 0xf3, 0x90, 0x00]), 0), 'MPEG-2: index 9 is 80 kbit/s, 72 x 80000 / 22050').toBe(261)
    for (const bad of [[0xff, 0xfb, 0xf0, 0x00], [0xff, 0xfb, 0x0c, 0x00], [0xff, 0xfd, 0x90, 0x00], [0xff, 0xeb, 0x90, 0x00], [0xfe, 0xfb, 0x90, 0x00]]) {
      expect(mp3FrameLength(Uint8Array.from(bad), 0), bad.join(' ')).toBe(0)
    }
  })

  it('reads an ADTS frame\'s length, rate and samples', () => {
    expect(adtsFrame(Uint8Array.from(adtsHeader(512)), 0)).toEqual({ length: 512, sampleRate: 44100, samples: 1024 })
    expect(adtsFrameLength(Uint8Array.from([0xff, 0xf1, 0x3c, 0x80, 0, 0, 0]), 0), 'a rate index past the table').toBe(0)
  })

  it('aligns on the first header that begins a chain of three, not on a lone two-byte pattern in the data', () => {
    const b = mp3Stream(20 * MP3_FRAME, 200)
    b.set([0xff, 0xfb, 0x90, 0x00], 50) // a lone frame header in the tag's body: its successor is no header
    expect(firstFrameChain(b, mp3FrameLength)).toBe(200)
    expect(firstFrameChain(new Uint8Array(4096), mp3FrameLength)).toBe(-1)
    const cut = adtsStream(40).subarray(300) // a slice cut inside a frame
    expect(firstFrameChain(cut, adtsFrameLength)).toBe(512 - 300)
  })
})

describe('adtsDurationMs: a raw ADTS file\'s length counted from its frames', () => {
  it('counts every frame, across the slices it reads', async () => {
    // 10 000 frames of 1024 samples at 44.1 kHz = 232.2 s, 5.1 MB: read in two steps
    await expect(adtsDurationMs(blob(adtsStream(10_000)))).resolves.toBe(Math.round((10_000 * 1024 * 1000) / 44100))
  })

  it('ends at a tag after the last frame, and gives up where the frames break', async () => {
    await expect(adtsDurationMs(blob(adtsStream(100, 512, 128)))).resolves.toBe(Math.round((100 * 1024 * 1000) / 44100))
    const broken = new Uint8Array([...adtsStream(100), ...new Uint8Array(200_000), ...adtsStream(100)])
    await expect(adtsDurationMs(blob(broken))).resolves.toBeNull()
    await expect(adtsDurationMs(blob(new Uint8Array(1000)))).resolves.toBeNull()
  })
})
