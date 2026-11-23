// @vitest-environment node
// The music order and fades the player draws from the platform (`@veyq/core-js/audio`) against
// contracts/platform/audio/v1-vectors.json (ADR 0155 decision 7.2, the music order and fades' slice): what the web calls
// answers every case the core's MusicVectorsTest answers, so a rule mutated in Kotlin turns this red too — the draws
// scripted, each asked with the `n` the case lists. The player holds no rule; it calls these. And the order holds over the
// platform's own randomness: a round never starts with the track that ended the one before.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  autoFadeMs, cryptoRandomInt, fadeLengthAt, fadeOutAtMs, playOrder, playOrderCurrent, playOrderDrop, playOrderNext,
  playOrderPeek, playOrderPrevious, playOrderSize, pressFadeMs,
} from '@veyq/core-js/audio'

const v = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/platform/audio/v1-vectors.json'), 'utf8'))
/** A number as the vectors hold it: null is Infinity. */
const ms = (x: number | null) => (x === null ? Infinity : x)
/** An answer as the vectors hold it: Infinity is null (what `JSON.stringify` makes of it). */
const json = (x: number) => (Number.isFinite(x) ? x : null)

describe('the platform\'s draw (randomInt vectors)', () => {
  afterEach(() => vi.restoreAllMocks())
  ;(v.randomInt as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      let at = 0
      vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation(<T extends ArrayBufferView | null>(array: T): T => {
        const bytes = new Uint8Array(array!.buffer, array!.byteOffset, array!.byteLength)
        for (let k = 0; k < bytes.length; k += 4) {
          const w = c.words[at++] as number
          bytes.set([w & 0xff, (w >>> 8) & 0xff, (w >>> 16) & 0xff, (w >>> 24) & 0xff], k)
        }
        return array
      })
      expect({ value: cryptoRandomInt(c.n), drawn: at }).toEqual(c.expected)
    })
  })
})

describe('the order (order vectors)', () => {
  ;(v.order as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const draws = c.draws as [number, number][]
      let at = 0
      const random = (n: number) => {
        if (at >= draws.length) throw new Error(`draw ${at} (n = ${n}) is one the vectors do not list`)
        const [asked, value] = draws[at++]
        if (asked !== n) throw new Error(`draw ${at - 1} asked n = ${n} where the vectors ask n = ${asked}`)
        return value
      }
      const order = playOrder(c.ids, random, c.startId ?? null)
      const got = (c.steps as any[]).map((s) => {
        switch (s.op) {
          case 'current': return playOrderCurrent(order) ?? null
          case 'next': return playOrderNext(order) ?? null
          case 'peek': return playOrderPeek(order) ?? null
          case 'size': return playOrderSize(order)
          case 'previous': { const p = playOrderPrevious(order, s.positionMs); return p.restart ? { restart: true } : { restart: false, id: p.id } }
          case 'drop': playOrderDrop(order, s.id); return null
        }
        throw new Error(`no op ${s.op}`)
      })
      expect(got).toEqual(c.expected)
      expect(at).toBe(draws.length)
    })
  })

  it('over the platform\'s own randomness: 1,000 rounds of 2-5 tracks, a round\'s first is never the last one played', () => {
    for (let n = 2; n <= 5; n++) {
      const order = playOrder(Array.from({ length: n }, (_, k) => `t${k}`), cryptoRandomInt, null)
      let last = playOrderCurrent(order)
      const seen = new Set<string>()
      for (let played = 1; played < n * 1000; played++) {
        const next = playOrderNext(order)!
        if (played % n === 0) expect(next, `round ${played / n} of ${n}`).not.toBe(last)
        seen.add(next)
        last = next
      }
      expect(seen.size).toBe(n)
    }
  })
})

describe('the fades (fades vectors)', () => {
  ;(v.fades as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const span = { startMs: ms(c.span.startMs), endMs: ms(c.span.endMs) }
      expect({
        autoFadeMs: json(autoFadeMs(span)),
        pressFadeMs: json(pressFadeMs(span)),
        fadeOutAtMs: json(fadeOutAtMs(span)),
        fadeLengthAt: (c.positionsMs as number[]).map((p) => json(fadeLengthAt(span, p))),
      }).toEqual(c.expected)
    })
  })
})
