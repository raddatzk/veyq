import { describe, it, expect, vi, afterEach } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'audio.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const a = await import('@veyq/core-js/audio')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/audio/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/audio/v1-vectors.json'), 'utf8'))

/** A number as the vectors hold it: null is Infinity. */
const ms = (x: number | null) => (x === null ? Infinity : x)
/** An answer as the vectors hold it: Infinity is null (what `JSON.stringify` makes of it). */
const json = (x: number) => (Number.isFinite(x) ? x : null)

// The music order and fades' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers
// every case the core's MusicVectorsTest answers on the JVM and natively.
describe('randomInt', () => {
  afterEach(() => vi.restoreAllMocks())
  ;(v.randomInt as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      let at = 0
      // the platform's draw reads `crypto.getRandomValues`; each word scripted, its bytes little-endian
      vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation(<T extends ArrayBufferView | null>(array: T): T => {
        const bytes = new Uint8Array(array!.buffer, array!.byteOffset, array!.byteLength)
        for (let k = 0; k < bytes.length; k += 4) {
          const w = c.words[at++] as number
          bytes.set([w & 0xff, (w >>> 8) & 0xff, (w >>> 16) & 0xff, (w >>> 24) & 0xff], k)
        }
        return array
      })
      expect({ value: a.cryptoRandomInt(c.n), drawn: at }).toEqual(c.expected)
    })
  })

  it('the platform\'s own randomness stays in range and reaches every value', () => {
    const seen = new Set<number>()
    for (let k = 0; k < 500; k++) {
      const x = a.cryptoRandomInt(5)
      expect(Number.isInteger(x) && x >= 0 && x < 5).toBe(true)
      seen.add(x)
    }
    expect([...seen].sort()).toEqual([0, 1, 2, 3, 4])
  })
})

describe('order', () => {
  ;(v.order as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const draws = c.draws as [number, number][]
      let at = 0
      const random = (n: number) => {
        if (at >= draws.length) throw new Error(`draw ${at} (n = ${n}) is one the web did not make`)
        const [asked, value] = draws[at++]
        if (asked !== n) throw new Error(`draw ${at - 1} asked n = ${n} where the web asked n = ${asked}`)
        return value
      }
      const order = a.playOrder(c.ids, random, c.startId ?? null)
      const got = (c.steps as any[]).map((s) => {
        switch (s.op) {
          case 'current': return a.playOrderCurrent(order) ?? null
          case 'next': return a.playOrderNext(order) ?? null
          case 'peek': return a.playOrderPeek(order) ?? null
          case 'size': return a.playOrderSize(order)
          case 'previous': { const p = a.playOrderPrevious(order, s.positionMs); return p.restart ? { restart: true } : { restart: false, id: p.id } }
          case 'drop': a.playOrderDrop(order, s.id); return null
        }
        throw new Error(`no op ${s.op}`)
      })
      expect(got).toEqual(c.expected)
      expect(at).toBe(draws.length)
    })
  })
})

describe('fades', () => {
  ;(v.fades as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const span = { startMs: ms(c.span.startMs), endMs: ms(c.span.endMs) }
      expect({
        autoFadeMs: json(a.autoFadeMs(span)),
        pressFadeMs: json(a.pressFadeMs(span)),
        fadeOutAtMs: json(a.fadeOutAtMs(span)),
        fadeLengthAt: (c.positionsMs as number[]).map((p) => json(a.fadeLengthAt(span, p))),
      }).toEqual(c.expected)
    })
  })
})
