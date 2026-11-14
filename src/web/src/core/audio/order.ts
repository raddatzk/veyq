// Core's audio player (en.platform.audio-player, ADR 0135 decision 6): the order a queue plays in. Always shuffled and
// endless, no toggle: each round is a fresh permutation (Fisher–Yates over `crypto.getRandomValues`), and with two or more
// tracks a round never starts with the track that ended the one before. Next goes forward; previous restarts the current
// track when it is more than 3 s in, and otherwise goes back through the tracks played. A start track plays first, the
// rest of its round shuffled. Pure: the randomness is handed in.

/** A uniformly random integer in `[0, n)`. */
export type RandomInt = (n: number) => number

/** How far into a track "previous" restarts it instead of going back. */
export const PREVIOUS_RESTARTS_AFTER_MS = 3000

/** `crypto.getRandomValues`, unbiased by rejection. */
export const cryptoRandomInt: RandomInt = (n) => {
  if (n <= 1) return 0
  const limit = Math.floor(0x1_0000_0000 / n) * n
  const word = new Uint32Array(1)
  for (;;) {
    crypto.getRandomValues(word)
    if (word[0] < limit) return word[0] % n
  }
}

/** A fresh permutation of `ids` (Fisher–Yates). */
export function shuffled<T>(ids: readonly T[], random: RandomInt): T[] {
  const out = ids.slice()
  for (let i = out.length - 1; i > 0; i--) {
    const j = random(i + 1)
    ;[out[i], out[j]] = [out[j], out[i]]
  }
  return out
}

/** One round: a permutation of `ids` that does not start with `last` (where there are two or more), or `first` first. */
export function nextRound(ids: readonly string[], last: string | null, random: RandomInt, first?: string): string[] {
  if (first !== undefined && ids.includes(first)) return [first, ...shuffled(ids.filter((id) => id !== first), random)]
  const round = shuffled(ids, random)
  if (round.length >= 2 && round[0] === last) {
    const j = 1 + random(round.length - 1)
    ;[round[0], round[j]] = [round[j], round[0]]
  }
  return round
}

/** What "previous" does: restart the track playing, or go back to the one played before it. */
export type PreviousStep = { restart: true } | { restart: false; id: string }

/** The order of one queue: the tracks played (to go back through), the rest of the round, and the next rounds. */
export class PlayOrder {
  private ids: string[]
  private readonly played: string[] = []
  private at = -1
  private pending: string[]

  constructor(ids: readonly string[], private readonly random: RandomInt = cryptoRandomInt, startId?: string) {
    this.ids = [...new Set(ids)]
    this.pending = nextRound(this.ids, null, random, startId)
    this.next()
  }

  /** The track playing, or null when the queue holds none. */
  current(): string | null {
    return this.at >= 0 ? this.played[this.at] : null
  }

  /** Go forward: through tracks gone back over first, then the round, then a fresh round. Null when no track is left. */
  next(): string | null {
    if (this.at < this.played.length - 1) return this.played[++this.at]
    if (this.ids.length === 0) return null
    if (this.pending.length === 0) this.pending = nextRound(this.ids, this.current(), this.random)
    const id = this.pending.shift()!
    this.played.push(id)
    this.at = this.played.length - 1
    return id
  }

  /** The track `next` will go to, without going there: it is fixed now, so the player can open it ahead. */
  peek(): string | null {
    if (this.at < this.played.length - 1) return this.played[this.at + 1]
    if (this.ids.length === 0) return null
    if (this.pending.length === 0) this.pending = nextRound(this.ids, this.current(), this.random)
    const id = this.pending.shift()!
    this.played.push(id)
    return id
  }

  /** Go back - or restart, where the track is more than 3 s in or nothing was played before it. */
  previous(positionMs: number): PreviousStep {
    if (positionMs > PREVIOUS_RESTARTS_AFTER_MS || this.at <= 0) return { restart: true }
    return { restart: false, id: this.played[--this.at] }
  }

  /** Drop a track that does not play here: it leaves every round, and the history. */
  drop(id: string): void {
    this.ids = this.ids.filter((x) => x !== id)
    this.pending = this.pending.filter((x) => x !== id)
    for (let i = this.played.length - 1; i >= 0; i--) {
      if (this.played[i] !== id) continue
      this.played.splice(i, 1)
      if (i <= this.at) this.at--
    }
  }

  /** How many tracks the queue still holds. */
  get size(): number {
    return this.ids.length
  }
}
