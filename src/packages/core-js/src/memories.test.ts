import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'memories.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const m = await import('@veyq/core-js/memories')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/memories/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/memories/v1-vectors.json'), 'utf8'))

const bytes = (b64: string) => Uint8Array.from(Buffer.from(b64, 'base64'))
const label = (c: any, i: number) => c.name ?? `#${i}`

// Memories' façade against every section of the vectors (ADR 0155 decisions 6 and 7.3): the shipped module answers every
// case the core's MemoriesVectorsTest answers on the JVM and natively.
describe('aspect', () => {
  ;(v.aspect as any[]).forEach((c, i) => {
    it(`${c.width} × ${c.height} (#${i})`, () => expect(m.memoriesDisplayAspect(c.width, c.height)).toBe(c.expected))
  })
})

describe('grid', () => {
  ;(v.grid as any[]).forEach((c) => {
    it(c.name, () => {
      const items = c.items.map((x: any) => ({ id: x.id, aspectRatio: x.aspect }))
      const l = m.memoriesGridLayout(items, c.containerWidth, c.targetHeight, c.gap, c.showWidows)
      expect({
        tiles: [...l.tiles].map((t) => ({ id: t.id, left: t.left, top: t.top, width: t.renderedWidth, height: t.renderedHeight, widow: t.widow })),
        height: l.height,
      }).toEqual(c.expected)
    })
  })
})

describe('medoid', () => {
  ;(v.medoid as any[]).forEach((c) => {
    it(c.name, () => {
      const p = m.memoriesMedoid(c.points.map(([lat, lng]: number[]) => ({ lat, lng })))
      expect(p ? [p.lat, p.lng] : null).toEqual(c.expected)
    })
  })
})

describe('participants', () => {
  ;(v.participants as any[]).forEach((c) => {
    it(c.name, () => expect([...m.memoriesMergeParticipants(c.stored, c.wanted, c.openedWith)]).toEqual(c.expected))
  })
})

describe('frames', () => {
  ;(v.mp3Frame as any[]).forEach((c, i) => {
    it(`mp3Frame: ${label(c, i)}`, () => expect(m.memoriesMp3FrameLength(bytes(c.bytes), c.at)).toBe(c.expected))
  })
  ;(v.adtsFrame as any[]).forEach((c, i) => {
    it(`adtsFrame: ${label(c, i)}`, () => {
      const f = m.memoriesAdtsFrame(bytes(c.bytes), c.at)
      expect(f ? { length: f.length, sampleRate: f.sampleRate, samples: f.samples } : null).toEqual(c.expected)
    })
  })
  ;(v.frameChain as any[]).forEach((c, i) => {
    it(`frameChain: ${label(c, i)}`, () => expect(m.memoriesFirstFrameChain(bytes(c.bytes), c.kind, c.chain)).toBe(c.expected))
  })
  ;(v.adtsCount as any[]).forEach((c, i) => {
    it(`adtsCount: ${label(c, i)}`, () => {
      const n = m.memoriesAdtsCount(bytes(c.chunk), c.at, c.size, c.seconds)
      expect({ read: n.read, seconds: n.seconds }).toEqual(c.expected)
    })
  })
  ;(v.adtsEnd as any[]).forEach((c, i) => {
    it(`adtsEnd: ${c.seconds} s, ${c.remaining} B left (#${i})`, () => expect(m.memoriesAdtsEnd(c.seconds, c.remaining)).toBe(c.expected))
  })
  it('a frame kind the rules do not know is refused', () => {
    expect(() => m.memoriesFirstFrameChain(new Uint8Array(8), 'ogg', 3)).toThrow('frame_kind')
  })
})

describe('wav', () => {
  ;(v.wavHeader as any[]).forEach((c, i) => {
    it(`wavHeader: ${label(c, i)}`, () => {
      let answer: unknown
      try {
        const f = m.memoriesWavHeader(bytes(c.head), c.fileSize)
        answer = f ? { ...f } : null
      } catch (e) {
        expect(e).toBeInstanceOf(Error)
        answer = { error: (e as Error).message }
      }
      expect(answer).toEqual(c.expected)
    })
  })
  ;(v.wavSamples as any[]).forEach((c, i) => {
    it(`wavSamples: ${label(c, i)}`, () => {
      const channels = m.memoriesWavSamples(bytes(c.bytes), c.format)
      channels.forEach((ch) => expect(ch).toBeInstanceOf(Float32Array))
      expect(channels.map((ch) => [...ch])).toEqual(c.expected)
    })
  })
  ;(v.wavEnds as any[]).forEach((c, i) => {
    it(`wavEnds #${i}`, () => expect(m.memoriesWavEndBytes(c.format, c.limitMs)).toBe(c.expected))
  })
})
