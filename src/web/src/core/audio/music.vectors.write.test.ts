// @vitest-environment node
// Phase 1 of cr-web-music-order-on-the-core: records contracts/platform/audio/v1-vectors.json from the web's own
// TypeScript (`order.ts`, `fades.ts`) — every case's input and the web's answer. Deleted in phase 2, when the core's
// MusicVectorsTest answers every case and writes the file. Runs only with VEYQ_WRITE_VECTORS=1.
import { describe, it, vi } from 'vitest'
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PlayOrder, PREVIOUS_RESTARTS_AFTER_MS, cryptoRandomInt, type RandomInt } from './order'
import { FADE_MS, PRESS_FADE_MS, autoFadeMs, fadeLengthAt, fadeOutAtMs, pressFadeMs, type Span } from './fades'

const FILE = resolve(__dirname, '../../../../../contracts/platform/audio/v1-vectors.json')

/** A seeded generator (mulberry32), so a case's draws are fixed by its seed. */
function mulberry32(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** The randomness a case scripts: explicit values in order, or a seed; every draw recorded as `[n, value]`. */
function scripted(source: { values?: number[]; seed?: number }) {
  const draws: [number, number][] = []
  const next = source.seed !== undefined ? mulberry32(source.seed) : null
  let at = 0
  const random: RandomInt = (n) => {
    let v: number
    if (next) v = Math.floor(next() * n)
    else {
      if (at >= source.values!.length) throw new Error(`the case's values ran out at draw ${at} (n = ${n})`)
      v = source.values![at++]
    }
    if (!(v >= 0 && v < n)) throw new Error(`draw ${draws.length}: ${v} is not in [0, ${n})`)
    draws.push([n, v])
    return v
  }
  return { random, draws }
}

type Step = { op: 'current' | 'next' | 'peek' | 'size' } | { op: 'previous'; positionMs: number } | { op: 'drop'; id: string }
const nexts = (k: number): Step[] => Array.from({ length: k }, () => ({ op: 'next' }))
const ids = (n: number) => Array.from({ length: n }, (_, i) => `t${i}`)

describe('records the music vectors from the web', () => {
  it.runIf(process.env.VEYQ_WRITE_VECTORS === '1')('writes contracts/platform/audio/v1-vectors.json', () => {
    const v: Record<string, unknown> = {
      format: 'veyq-music-order-v1',
      about:
        'The music player\'s order and fades (ADR 0135 decisions 6 and 7, ADR 0142 decision 1; ADR 0155 decision 7.2, the seventh slice). ' +
        '`randomInt`: the platform\'s unbiased draw in [0, n) from 32-bit words (the web\'s `crypto.getRandomValues` into a Uint32Array: ' +
        'a word is its four bytes little-endian), a word at or past the largest multiple of n below 2^32 drawn again; `expected` the value ' +
        'and how many words it took. `order`: a queue (`ids`, `startId`) under scripted randomness - `draws` lists every call in order as ' +
        '[n, value], and a client must ask exactly those n - then each `steps` op (`current`, `next`, `peek`, `previous` at `positionMs` into ' +
        'the track, `drop` an id, `size`) and its answer (`previous`: {restart: true} or {restart: false, id}; `drop`: null). `fades`: a span ' +
        '(`startMs`, `endMs`; an `endMs` of null is an unknown end, Infinity) and its fades in milliseconds of the file - an answer of null is ' +
        'Infinity - `fadeLengthAt` at each of `positionsMs`. Numbers are JavaScript\'s doubles.',
      facts: { fadeMs: FADE_MS, pressFadeMs: PRESS_FADE_MS, previousRestartsAfterMs: PREVIOUS_RESTARTS_AFTER_MS },
    }

    // --- randomInt -------------------------------------------------------------------------------------------------------
    const randomCases: { rule: string; n: number; words: number[] }[] = [
      { rule: 'n = 0 draws nothing and answers 0', n: 0, words: [] },
      { rule: 'n = 1 draws nothing and answers 0', n: 1, words: [] },
      { rule: 'a word below the limit: its remainder', n: 2, words: [7] },
      { rule: 'the top word lies past the largest multiple of 3: drawn again', n: 3, words: [0xffffffff, 5] },
      { rule: 'the last word below the limit of 3 is kept', n: 3, words: [0xfffffffe] },
      { rule: 'a word whose bytes differ: read little-endian', n: 1000, words: [0x01020304] },
      { rule: 'n = 2^31 - 1, the largest a queue can be: the two top words are drawn again', n: 0x7fffffff, words: [0xfffffffe, 0xffffffff, 0xfffffffd] },
      { rule: 'n = 2^30 + 1: three quarters of the words are kept', n: 0x40000001, words: [0xc0000003, 0xfedcba98, 0xc0000002] },
      { rule: 'n = 7, several rejections in a row', n: 7, words: [0xfffffffc, 0xfffffffd, 0xffffffff, 0xfffffffb] },
      { rule: 'zero is a word like any other', n: 5, words: [0] },
    ]
    const spy = vi.spyOn(globalThis.crypto, 'getRandomValues')
    v.randomInt = randomCases.map((c) => {
      let at = 0
      spy.mockImplementation(<T extends ArrayBufferView | null>(array: T): T => {
        const bytes = new Uint8Array(array!.buffer, array!.byteOffset, array!.byteLength)
        for (let i = 0; i < bytes.length; i += 4) {
          if (at >= c.words.length) throw new Error(`${c.rule}: the words ran out`)
          const w = c.words[at++]
          bytes.set([w & 0xff, (w >>> 8) & 0xff, (w >>> 16) & 0xff, (w >>> 24) & 0xff], i)
        }
        return array
      })
      const value = cryptoRandomInt(c.n)
      if (at !== c.words.length) throw new Error(`${c.rule}: ${c.words.length - at} words left unused`)
      return { rule: c.rule, n: c.n, words: c.words, expected: { value, drawn: at } }
    })
    spy.mockRestore()

    // --- order -----------------------------------------------------------------------------------------------------------
    type OrderCase = { rule: string; ids: string[]; startId?: string; values?: number[]; seed?: number; steps: Step[] }
    const orderCases: OrderCase[] = [
      { rule: 'Fisher-Yates by the draws handed in: always 0', ids: ['a', 'b', 'c'], values: [0, 0, 0, 0], steps: [{ op: 'current' }, ...nexts(5)] },
      { rule: 'Fisher-Yates by the draws handed in: always n - 1 keeps the order', ids: ['a', 'b', 'c'], values: [2, 1, 2, 1, 2, 1], steps: [{ op: 'current' }, ...nexts(8)] },
      { rule: 'a round that would start with the track that ended the last one swaps it with a later one', ids: ['a', 'b'], values: [1, 0, 0], steps: [{ op: 'current' }, ...nexts(2)] },
      { rule: 'the swap draws its partner among the rest of the round', ids: ['a', 'b', 'c', 'd'], values: [3, 2, 1, 0, 1, 1, 2], steps: [{ op: 'current' }, ...nexts(4)] },
      ...[2, 3, 4, 5].map((n): OrderCase => ({ rule: `${n} tracks, 60 rounds: each a permutation, none starting with the last one played`, ids: ids(n), seed: 1000 + n, steps: nexts(60 * n) })),
      { rule: 'seven tracks over three rounds', ids: ids(7), seed: 77, steps: [{ op: 'current' }, ...nexts(21), { op: 'size' }] },
      { rule: 'a start track plays first, the rest of its round shuffled', ids: ids(5), startId: 't3', seed: 31, steps: [{ op: 'current' }, ...nexts(9)] },
      { rule: 'a start track the queue does not hold is no start track', ids: ids(4), startId: 'zz', seed: 32, steps: [{ op: 'current' }, ...nexts(4)] },
      { rule: 'a queue holds an id once, in its first place', ids: ['a', 'b', 'a', 'c', 'b'], seed: 5, steps: [{ op: 'size' }, { op: 'current' }, ...nexts(5)] },
      { rule: 'a single track plays again and again, drawing nothing', ids: ['only'], values: [], steps: [{ op: 'current' }, ...nexts(3), { op: 'previous', positionMs: 0 }, { op: 'size' }] },
      { rule: 'an empty queue plays nothing', ids: [], values: [], steps: [{ op: 'current' }, { op: 'next' }, { op: 'peek' }, { op: 'previous', positionMs: 0 }, { op: 'size' }] },
      {
        rule: 'previous: more than 3 s in restarts, 3 s or less goes back, nothing before the first restarts, next goes forward again',
        ids: ids(4), seed: 9,
        steps: [{ op: 'current' }, { op: 'next' }, { op: 'next' }, { op: 'previous', positionMs: 4000 }, { op: 'current' }, { op: 'previous', positionMs: 3000.5 },
          { op: 'previous', positionMs: 3000 }, { op: 'current' }, { op: 'previous', positionMs: 2000 }, { op: 'current' }, { op: 'previous', positionMs: 0 },
          { op: 'current' }, { op: 'next' }, { op: 'next' }, { op: 'next' }, { op: 'next' }, { op: 'previous', positionMs: -50 }, { op: 'current' }],
      },
      {
        rule: 'peek fixes the next track without going there; next then goes to it',
        ids: ids(5), seed: 12,
        steps: [{ op: 'current' }, { op: 'peek' }, { op: 'peek' }, { op: 'current' }, { op: 'next' }, { op: 'peek' }, { op: 'previous', positionMs: 1000 },
          { op: 'peek' }, { op: 'next' }, { op: 'next' }, ...nexts(4), { op: 'peek' }, { op: 'next' }],
      },
      {
        rule: 'a dropped track leaves every round and the history; the queue empties when none is left',
        ids: ['a', 'b', 'c'], seed: 50,
        steps: [{ op: 'current' }, { op: 'drop', id: 'b' }, { op: 'current' }, { op: 'size' }, ...nexts(4), { op: 'drop', id: 'a' },
          { op: 'drop', id: 'b' }, { op: 'drop', id: 'c' }, { op: 'size' }, { op: 'current' }, { op: 'next' }, { op: 'peek' }],
      },
      {
        rule: 'dropping a track played before keeps the one playing; an unknown id changes nothing',
        ids: ids(5), seed: 44,
        steps: [{ op: 'current' }, { op: 'next' }, { op: 'next' }, { op: 'drop', id: 'nope' }, { op: 'size' }, { op: 'current' }, { op: 'drop', id: 't2' },
          { op: 'current' }, { op: 'size' }, { op: 'previous', positionMs: 0 }, { op: 'current' }, ...nexts(6)],
      },
      {
        rule: 'dropping the track playing goes back to the one before it',
        ids: ids(4), seed: 9,
        steps: [{ op: 'current' }, { op: 'next' }, { op: 'drop', id: 't3' }, { op: 'current' }, { op: 'size' }, { op: 'next' }, { op: 'next' }, { op: 'next' }],
      },
      {
        rule: 'dropping a peeked track takes it out of the history ahead',
        ids: ids(4), seed: 45,
        steps: [{ op: 'current' }, { op: 'peek' }, { op: 'drop', id: 't0' }, { op: 'drop', id: 't1' }, { op: 'drop', id: 't2' }, { op: 'drop', id: 't3' }, { op: 'peek' }, { op: 'current' }],
      },
      {
        rule: 'after going back, a dropped track ahead is skipped going forward',
        ids: ids(4), seed: 46,
        steps: [{ op: 'current' }, { op: 'next' }, { op: 'next' }, { op: 'previous', positionMs: 0 }, { op: 'previous', positionMs: 0 }, { op: 'current' },
          { op: 'drop', id: 't1' }, { op: 'next' }, { op: 'next' }, { op: 'next' }, { op: 'size' }],
      },
    ]
    v.order = orderCases.map((c) => {
      const { random, draws } = scripted(c)
      const order = new PlayOrder(c.ids, random, c.startId)
      const expected = c.steps.map((s) => {
        switch (s.op) {
          case 'current': return order.current()
          case 'next': return order.next()
          case 'peek': return order.peek()
          case 'size': return order.size
          case 'previous': return order.previous(s.positionMs)
          case 'drop': order.drop(s.id); return null
        }
      })
      if (c.values && draws.length !== c.values.length) throw new Error(`${c.rule}: ${c.values.length - draws.length} values left unused`)
      return { rule: c.rule, ids: c.ids, ...(c.startId !== undefined ? { startId: c.startId } : {}), draws, steps: c.steps, expected }
    })

    // --- fades -----------------------------------------------------------------------------------------------------------
    const fadeCases: { rule: string; span: Span; positionsMs: number[] }[] = [
      { rule: 'a song fades out over 5 s at its trimmed end; a press over 1 s; a seek into the window shortens the fade', span: { startMs: 400, endMs: 180_400 }, positionsMs: [178_400, 175_400, 175_400.5, 100_000, 180_400, 190_000, 0] },
      { rule: 'a track shorter than two fades fades over a third of its span', span: { startMs: 1000, endMs: 7000 }, positionsMs: [5000, 6000, 0] },
      { rule: 'a third of a span that does not divide by three is not rounded', span: { startMs: 0, endMs: 7000 }, positionsMs: [4666, 4667, 6999.5] },
      { rule: 'a tiny track: a press no longer than its own fade', span: { startMs: 0, endMs: 1500 }, positionsMs: [1000, 1499] },
      { rule: 'exactly two fades long: the whole 5 s', span: { startMs: 0, endMs: 10_000 }, positionsMs: [5000, 7500] },
      { rule: 'just under two fades: a third', span: { startMs: 0, endMs: 9999 }, positionsMs: [6666, 9000] },
      { rule: 'a press over a span of 3 s less a millisecond is a third of it', span: { startMs: 0, endMs: 2999 }, positionsMs: [2000] },
      { rule: 'fractional edges, as a file\'s duration in seconds times 1000 gives them', span: { startMs: 12.5, endMs: 9000.25 }, positionsMs: [6000.125, 9000.25, 3] },
      { rule: 'a millisecond long', span: { startMs: 0, endMs: 1 }, positionsMs: [0, 0.5] },
      { rule: 'an unknown end: the whole fade, never reached', span: { startMs: 0, endMs: Infinity }, positionsMs: [0, 1e12] },
      { rule: 'an unknown end after a leading silence', span: { startMs: 300, endMs: Infinity }, positionsMs: [300] },
      { rule: 'an empty span fades over nothing', span: { startMs: 500, endMs: 500 }, positionsMs: [0, 500, 600] },
      { rule: 'an inverted span is empty', span: { startMs: 800, endMs: 200 }, positionsMs: [0, 200, 900] },
      { rule: 'a long recording', span: { startMs: 0, endMs: 3 * 3600_000 }, positionsMs: [3 * 3600_000 - 1234] },
    ]
    v.fades = fadeCases.map((c) => ({
      rule: c.rule,
      span: c.span,
      positionsMs: c.positionsMs,
      expected: {
        autoFadeMs: autoFadeMs(c.span),
        pressFadeMs: pressFadeMs(c.span),
        fadeOutAtMs: fadeOutAtMs(c.span),
        fadeLengthAt: c.positionsMs.map((p) => fadeLengthAt(c.span, p)),
      },
    }))

    // JSON.stringify writes Infinity as null, which is what the format says
    writeFileSync(FILE, JSON.stringify(v, null, 2) + '\n')
  })
})
