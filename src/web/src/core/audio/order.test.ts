import { describe, it, expect } from 'vitest'
import { PlayOrder, cryptoRandomInt, nextRound, shuffled } from './order'

const ids = (n: number) => Array.from({ length: n }, (_, i) => `t${i}`)

describe('the order (ADR 0135 decision 6): shuffled, endless, never the same track twice across a round\'s edge', () => {
  it('a round is a permutation of the queue', () => {
    for (let n = 1; n <= 7; n++) {
      const round = nextRound(ids(n), null, cryptoRandomInt)
      expect(round.slice().sort()).toEqual(ids(n).slice().sort())
    }
  })

  it('across 1,000 rounds of 2-5 tracks, a round\'s first is never the last one played', () => {
    for (let n = 2; n <= 5; n++) {
      const order = new PlayOrder(ids(n))
      let last = order.current()
      for (let played = 1; played < n * 1000; played++) {
        const next = order.next()
        if (played % n === 0) expect(next, `round ${played / n} of ${n}`).not.toBe(last)
        last = next
      }
    }
  })

  it('each round plays every track once, then a fresh round follows', () => {
    const order = new PlayOrder(ids(4))
    const first = [order.current(), order.next(), order.next(), order.next()]
    const second = [order.next(), order.next(), order.next(), order.next()]
    expect(first.slice().sort()).toEqual(ids(4))
    expect(second.slice().sort()).toEqual(ids(4))
  })

  it('a single track plays again and again', () => {
    const order = new PlayOrder(['only'])
    expect([order.current(), order.next(), order.next()]).toEqual(['only', 'only', 'only'])
  })

  it('a start track plays first, the rest of its round shuffled (A1)', () => {
    for (let i = 0; i < 50; i++) {
      const order = new PlayOrder(ids(5), cryptoRandomInt, 't3')
      const round = [order.current(), order.next(), order.next(), order.next(), order.next()]
      expect(round[0]).toBe('t3')
      expect(round.slice().sort()).toEqual(ids(5))
    }
  })

  it('shuffles by the randomness handed in (Fisher-Yates)', () => {
    expect(shuffled(['a', 'b', 'c'], () => 0)).toEqual(['b', 'c', 'a'])
    expect(shuffled(['a', 'b', 'c'], (n) => n - 1)).toEqual(['a', 'b', 'c'])
  })

  it('cryptoRandomInt stays in range', () => {
    for (let i = 0; i < 1000; i++) {
      const v = cryptoRandomInt(3)
      expect(v >= 0 && v < 3 && Number.isInteger(v)).toBe(true)
    }
  })
})

describe('next and previous', () => {
  it('previous at 2 s goes back to the track played before; at 4 s it restarts the current one', () => {
    const order = new PlayOrder(ids(4))
    const a = order.current()
    const b = order.next()
    expect(order.previous(4000)).toEqual({ restart: true })
    expect(order.current()).toBe(b)
    expect(order.previous(2000)).toEqual({ restart: false, id: a })
    expect(order.current()).toBe(a)
    // nothing before the first: previous restarts it
    expect(order.previous(2000)).toEqual({ restart: true })
    // and next goes forward through what was played again
    expect(order.next()).toBe(b)
  })

  it('peek fixes the next track without going there; next then goes to it', () => {
    const order = new PlayOrder(ids(5))
    const peeked = order.peek()
    expect(order.peek()).toBe(peeked)
    expect(order.next()).toBe(peeked)
  })

  it('a dropped track leaves every round and the history; the queue empties when none is left', () => {
    const order = new PlayOrder(['a', 'b', 'c'], () => 0)
    const first = order.current()!
    order.drop(first)
    expect(order.size).toBe(2)
    const rest = [order.next(), order.next(), order.next(), order.next()]
    expect(rest).not.toContain(first)
    for (const id of ['a', 'b', 'c']) order.drop(id)
    expect([order.size, order.next()]).toEqual([0, null])
  })
})
