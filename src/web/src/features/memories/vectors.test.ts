// Memories' rules against contracts/memories/v1-vectors.json, through the web's own glue over `@veyq/core-js/memories` (ADR
// 0155 decision 7.3, § Memories' move: the rules are the Kotlin core's) — a medium's display aspect and the justified grid
// (`mediaGridLayout.ts`), a Memory's medoid (`medoid.ts`), the participants' merge (`participantsWrite.ts`), the audio frame
// headers and a raw ADTS file's count (`music/frames.ts`) and a WAV's header, samples and ends (`music/wav.ts`). The core
// answers every case of the same file on the JVM and natively (`MemoriesVectorsTest`); a case that fails here names its
// section and name.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from './mediaGridLayout'
import { computeMedoid } from './medoid'
import { mergeParticipants } from './participantsWrite'
import { adtsFrame, adtsFrameLength, firstFrameChain, mp3FrameLength } from './music/frames'
import { parseWavHeader, readWavEnds, wavSamples } from './music/wav'

const v = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/memories/v1-vectors.json'), 'utf8')) as any
const bytes = (b64: string) => Uint8Array.from(Buffer.from(b64, 'base64'))
const label = (c: any, i: number) => c.name ?? `#${i}`

describe('aspect', () => {
  ;(v.aspect as any[]).forEach((c, i) => {
    it(`${c.width} × ${c.height} (#${i})`, () => expect(galleryDisplayAspectRatio(c.width, c.height)).toBe(c.expected))
  })
})

describe('grid', () => {
  ;(v.grid as any[]).forEach((c) => {
    it(c.name, () => {
      // an unknown aspect is an absent one, as a tile without sealed dimensions has it
      const items = c.items.map((x: any) => (x.aspect === null ? { id: x.id } : { id: x.id, aspectRatio: x.aspect }))
      const l = calculateJustifiedMediaLayout({ items, containerWidth: c.containerWidth, targetHeight: c.targetHeight, gap: c.gap, showWidows: c.showWidows })
      expect({
        tiles: l.tiles.map((t) => ({ id: t.id, left: t.left, top: t.top, width: t.renderedWidth, height: t.renderedHeight, widow: t.widow })),
        height: l.height,
      }).toEqual(c.expected)
    })
  })
})

describe('medoid', () => {
  ;(v.medoid as any[]).forEach((c) => {
    it(c.name, () => {
      const p = computeMedoid(c.points.map(([lat, lng]: number[]) => ({ lat, lng })))
      expect(p ? [p.lat, p.lng] : null).toEqual(c.expected)
    })
  })
})

describe('participants', () => {
  ;(v.participants as any[]).forEach((c) => {
    it(c.name, () => expect(mergeParticipants(c.stored, c.wanted, c.openedWith)).toEqual(c.expected))
  })
})

describe('frames', () => {
  ;(v.mp3Frame as any[]).forEach((c, i) => {
    it(`mp3Frame: ${label(c, i)}`, () => expect(mp3FrameLength(bytes(c.bytes), c.at)).toBe(c.expected))
  })
  ;(v.adtsFrame as any[]).forEach((c, i) => {
    it(`adtsFrame: ${label(c, i)}`, () => expect(adtsFrame(bytes(c.bytes), c.at)).toEqual(c.expected))
  })
  ;(v.frameChain as any[]).forEach((c, i) => {
    it(`frameChain: ${label(c, i)}`, () => {
      expect(firstFrameChain(bytes(c.bytes), c.kind === 'adts' ? adtsFrameLength : mp3FrameLength, c.chain)).toBe(c.expected)
    })
  })
  // `adtsCount` and `adtsEnd` are the steps of `adtsDurationMs`' loop: the package's `memories.test.ts` answers them, and
  // `frames.test.ts` counts whole files through the loop.
})

describe('wav', () => {
  ;(v.wavHeader as any[]).forEach((c, i) => {
    it(`wavHeader: ${label(c, i)}`, () => {
      let answer: unknown
      try {
        answer = parseWavHeader(bytes(c.head), c.fileSize)
      } catch (e) {
        answer = { error: (e as Error).message }
      }
      expect(answer).toEqual(c.expected)
    })
  })
  ;(v.wavSamples as any[]).forEach((c, i) => {
    it(`wavSamples: ${label(c, i)}`, () => {
      const d = wavSamples(bytes(c.bytes), c.format)
      expect(d.numberOfChannels).toBe(c.format.channels)
      expect(d.sampleRate).toBe(c.format.sampleRate)
      expect(d.length).toBe(c.expected[0].length)
      expect(Array.from({ length: d.numberOfChannels }, (_, ch) => [...d.getChannelData(ch)])).toEqual(c.expected)
    })
  })
  ;(v.wavEnds as any[]).forEach((c, i) => {
    it(`wavEnds #${i}: the ends read are the bytes the rule names`, async () => {
      const { format: f } = c
      // a file whose header is the case's format and whose data is that long, of zeros: the ends sliced are `expected` bytes
      const header = new Uint8Array(44)
      const view = new DataView(header.buffer)
      header.set([...'RIFF'].map((x) => x.charCodeAt(0)), 0)
      header.set([...'WAVEfmt '].map((x) => x.charCodeAt(0)), 8)
      view.setUint32(16, 16, true)
      view.setUint16(20, 1, true)
      view.setUint16(22, f.channels, true)
      view.setUint32(24, f.sampleRate, true)
      view.setUint16(32, f.blockAlign, true)
      view.setUint16(34, f.bitsPerSample, true)
      header.set([...'data'].map((x) => x.charCodeAt(0)), 36)
      view.setUint32(40, f.dataBytes, true)
      if (f.dataOffset !== 44 || f.dataBytes > 2 ** 24) return // a large or offset format: the package's case
      const file = new Blob([header as BlobPart, new Uint8Array(f.dataBytes) as BlobPart])
      const { head, tail } = await readWavEnds(file, c.limitMs)
      expect(head.length * f.blockAlign).toBe(c.expected)
      expect(tail.length * f.blockAlign).toBe(c.expected)
    })
  })
})
