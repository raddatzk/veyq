// Core's audio player (en.platform.audio-player, ADR 0135 decisions 6-8): ONE queue at a time, played shuffled
// (`order.ts`), endlessly, crossfading at the tracks' trimmed edges (`fades.ts`), with the MediaSession's metadata and
// actions so the lock screen and the media keys work. An application hands over a queue of tracks; each track's `open()`
// is the application's - it resolves to a playable URL (a service-worker stream handle, or a whole-file `blob:`) and a
// `release()` - so **the player is handed URLs, never a key**. Every source it opened is released when it is stopped, and
// the bar (`AudioBar.vue`) stops it when it unmounts: a lock, a sign-out, leaving a share page.
//
// Two media elements alternate; each runs through its own `MediaElementAudioSourceNode` and `GainNode` in ONE
// `AudioContext`, because iOS keeps `HTMLMediaElement.volume` read-only, so a fade on the element would not fade there.
// The context is created or resumed **synchronously in the press's call stack** (`play`, `resume`): iOS refuses one resumed
// after an `await`. In that same stack each element is started on a silent `blob:` once, so a later `play()` after the
// source opened is no longer a fresh autoplay. Where Web Audio is missing or refuses an element, tracks change without a
// fade. A track whose `open()` fails, or that the element cannot play, is skipped; when no track of the queue plays, the
// queue ends and `notice` says so.
import { shallowRef, type ShallowRef } from 'vue'
import { PlayOrder, cryptoRandomInt, type RandomInt } from './order'
import { fadeLengthAt, fadeOutAtMs, pressFadeMs, type Span } from './fades'

/** A track as an application hands it over. Without a span, the whole file plays. */
export type PlayerTrack = {
  id: string
  title: string
  startMs?: number
  endMs?: number
  /** A playable source for the track - the application's, which holds the keys - and how to let it go. */
  open: () => Promise<{ url: string; release: () => void }>
}
/** A queue: `key` names whose it is (Memories: the node), `album` what the lock screen shows under the title. */
export type PlayerQueue = { key: string; album?: string; tracks: readonly PlayerTrack[]; startId?: string }

/** What plays now, for the bar and the header. Positions are within the span, in ms. */
export type NowPlaying = {
  key: string
  trackId: string
  title: string
  album?: string
  playing: boolean
  positionMs: number
  durationMs: number
}

// --- the browser, behind seams the tests stub ----------------------------------------------------------------------

export interface MediaElementLike {
  src: string
  currentTime: number
  readonly duration: number
  readonly paused: boolean
  preload: string
  play(): Promise<void>
  pause(): void
  load(): void
  removeAttribute(name: string): void
  addEventListener(type: string, fn: () => void): void
  removeEventListener(type: string, fn: () => void): void
}
export interface GainLike {
  gain: { value: number; setValueAtTime(v: number, t: number): void; linearRampToValueAtTime(v: number, t: number): void; cancelScheduledValues(t: number): void }
  connect(node: unknown): void
}
export interface AudioContextLike {
  readonly state: string
  readonly currentTime: number
  readonly destination: unknown
  resume(): Promise<void>
  createMediaElementSource(el: MediaElementLike): { connect(node: unknown): void }
  createGain(): GainLike
}
export type MediaSessionLike = {
  metadata: unknown
  playbackState?: string
  setActionHandler(action: string, handler: ((details: { seekTime?: number }) => void) | null): void
  setPositionState?(state?: { duration: number; position: number; playbackRate: number }): void
}
export type PlayerEnv = {
  createElement(): MediaElementLike
  /** The one AudioContext, or null where there is none (then no fades). */
  createContext(): AudioContextLike | null
  /** A silent sound's URL, played once per element in the press's stack. */
  silentUrl(): string
  mediaSession: MediaSessionLike | null
  metadata(init: { title: string; album?: string }): unknown
  random: RandomInt
  setInterval(fn: () => void, ms: number): unknown
  clearInterval(handle: unknown): void
  setTimeout(fn: () => void, ms: number): unknown
}

/** How often the player looks where the playing track stands. */
const TICK_MS = 250
/** How long before its fade the next track is opened and loaded, so it starts on time. */
const OPEN_AHEAD_MS = 15_000

type Source = { url: string; release: () => void }
type Deck = {
  el: MediaElementLike
  gain: GainLike | null
  wired: boolean
  unlocked: boolean
  track: PlayerTrack | null
  source: Source | null
  span: Span
  /** The fade out has started (the next track is on its way in). */
  leaving: boolean
  /** A prepared next track's id: opened and loaded ahead, not yet playing. */
  preparedId: string | null
  /** The load in flight or done for the track the deck holds. */
  loading: Promise<void> | null
  /** Bumped by every load and every clear: a load that comes back to another token lets its source go. */
  token: number
}

export type AudioPlayer = {
  readonly nowPlaying: ShallowRef<NowPlaying | null>
  /** Why the last queue ended by itself (`unplayable`: no track of it played here), until the next `play`. */
  readonly notice: ShallowRef<'unplayable' | null>
  play(queue: PlayerQueue): void
  stop(): void
  pause(): void
  resume(): void
  next(): void
  previous(): void
  /** Seek within the span, in ms from its start. */
  seek(positionMs: number): void
}

export function createAudioPlayer(env: PlayerEnv): AudioPlayer {
  const nowPlaying = shallowRef<NowPlaying | null>(null)
  const notice = shallowRef<'unplayable' | null>(null)
  let ctx: AudioContextLike | null | undefined
  const decks: [Deck, Deck] = [deckOf(), deckOf()]
  let active = 0
  let queue: PlayerQueue | null = null
  let order: PlayOrder | null = null
  /** Bumped by every new queue, stop and track start: an `await` that comes back to another generation lets go. */
  let generation = 0
  let failures = 0
  let ticker: unknown = null
  let lastPosition = 0

  function deckOf(): Deck {
    return {
      el: env.createElement(), gain: null, wired: false, unlocked: false, track: null, source: null, span: { startMs: 0, endMs: Infinity },
      leaving: false, preparedId: null, loading: null, token: 0,
    }
  }

  /** The context, created or resumed NOW - called first thing in a press's stack. */
  function wakeContext(): AudioContextLike | null {
    if (ctx === undefined) {
      try {
        ctx = env.createContext()
      } catch {
        ctx = null
      }
    }
    if (ctx && ctx.state !== 'running') void ctx.resume().catch(() => undefined)
    return ctx
  }

  /** Each element's route through the context, once; null where Web Audio refuses it (that deck then cuts, no fade). */
  function wire(deck: Deck): void {
    if (deck.wired) return
    deck.wired = true
    if (!ctx) return
    try {
      const gain = ctx.createGain()
      ctx.createMediaElementSource(deck.el).connect(gain)
      gain.connect(ctx.destination)
      deck.gain = gain
    } catch {
      deck.gain = null
    }
  }

  /** Start each element on silence inside the press's stack, so the later `play()` of a real source is allowed. */
  function unlock(): void {
    for (const deck of decks) {
      wire(deck)
      if (deck.unlocked || deck.source) continue
      deck.unlocked = true
      deck.el.src = env.silentUrl()
      void deck.el.play().catch(() => undefined)
    }
  }

  function setGain(deck: Deck, value: number): void {
    if (!deck.gain || !ctx) return
    deck.gain.gain.cancelScheduledValues(ctx.currentTime)
    deck.gain.gain.setValueAtTime(value, ctx.currentTime)
  }
  function ramp(deck: Deck, from: number, to: number, ms: number): void {
    if (!deck.gain || !ctx) return
    const t = ctx.currentTime
    deck.gain.gain.cancelScheduledValues(t)
    deck.gain.gain.setValueAtTime(from, t)
    deck.gain.gain.linearRampToValueAtTime(to, t + Math.max(0, ms) / 1000)
  }

  /** Let a deck go: its element stopped and emptied, its source released. */
  function clear(deck: Deck): void {
    deck.token++
    deck.loading = null
    deck.el.pause()
    if (deck.source) {
      deck.el.removeAttribute('src')
      deck.el.load()
      deck.source.release()
    }
    deck.source = null
    deck.track = null
    deck.leaving = false
    deck.preparedId = null
    setGain(deck, 0)
  }

  const spanOf = (track: PlayerTrack, el: MediaElementLike): Span => {
    const whole = Number.isFinite(el.duration) && el.duration > 0 ? el.duration * 1000 : Infinity
    const start = track.startMs ?? 0
    const end = track.endMs ?? whole
    return end > start ? { startMs: start, endMs: end } : { startMs: 0, endMs: whole }
  }

  function once(el: MediaElementLike, ok: string, fail = 'error'): Promise<void> {
    return new Promise((resolve, reject) => {
      const done = () => { el.removeEventListener(ok, done); el.removeEventListener(fail, bad); resolve() }
      const bad = () => { el.removeEventListener(ok, done); el.removeEventListener(fail, bad); reject(new Error('media_error')) }
      el.addEventListener(ok, done)
      el.addEventListener(fail, bad)
    })
  }

  /** Open `track` into `deck` and load it to its span's start. Rejects where it does not open or load, or was cleared. */
  function load(deck: Deck, track: PlayerTrack): Promise<void> {
    if (deck.source) clear(deck)
    const token = ++deck.token
    const loading = (async () => {
      const source = await track.open()
      if (token !== deck.token) {
        source.release()
        throw new Error('superseded')
      }
      deck.source = source
      deck.track = track
      deck.leaving = false
      setGain(deck, 0)
      deck.el.preload = 'auto'
      const loaded = once(deck.el, 'loadedmetadata')
      deck.el.src = source.url
      await loaded
      if (token !== deck.token) throw new Error('superseded')
      deck.span = spanOf(track, deck.el)
      deck.el.currentTime = deck.span.startMs / 1000
    })()
    deck.loading = loading
    return loading
  }

  function trackOf(id: string | null): PlayerTrack | null {
    return id === null ? null : queue?.tracks.find((t) => t.id === id) ?? null
  }

  /** Bring `id` in on the idle deck, fading the playing one out over `fadeMs`; skip it where it does not play. */
  async function bringIn(id: string | null, fadeMs: number): Promise<void> {
    const track = trackOf(id)
    if (!track || !queue || !order) return endQueue()
    const gen = ++generation
    const outgoing = decks[active]
    const incoming = decks[1 - active]
    const prepared = incoming.preparedId === track.id && incoming.loading
    show(track, false)
    try {
      await (prepared || load(incoming, track))
      if (gen !== generation) return
      incoming.preparedId = null
      ramp(incoming, 0, 1, incoming.gain ? fadeMs : 0)
      if (!incoming.gain) setGain(incoming, 1)
      await incoming.el.play()
    } catch (e) {
      if (gen !== generation || (e as Error).message === 'superseded') return
      clear(incoming)
      return skip(track.id)
    }
    if (gen !== generation) return
    failures = 0
    active = 1 - active
    if (outgoing.source) {
      outgoing.leaving = true
      if (outgoing.gain && fadeMs > 0) {
        ramp(outgoing, outgoing.gain.gain.value, 0, fadeMs)
        const leaving = outgoing.track
        env.setTimeout(() => { if (outgoing.track === leaving && decks[active] !== outgoing) clear(outgoing) }, fadeMs + 50)
      } else clear(outgoing)
    }
    show(track, true)
    startTicker()
  }

  /** A track that did not play: dropped from the order; the queue ends once none is left that plays. */
  function skip(id: string): Promise<void> | void {
    if (!order) return
    failures++
    order.drop(id)
    if (order.size === 0 || failures > (queue?.tracks.length ?? 0)) return endQueue('unplayable')
    return bringIn(order.next(), 0)
  }

  function endQueue(why?: 'unplayable'): void {
    player.stop() // never the bare `stop()`: the DOM's `window.stop()` would answer it and stop nothing here
    if (why) notice.value = why
  }

  function show(track: PlayerTrack, playing: boolean): void {
    const deck = decks.find((d) => d.track === track) ?? decks[active]
    const span = deck.track === track ? deck.span : { startMs: track.startMs ?? 0, endMs: track.endMs ?? Infinity }
    nowPlaying.value = {
      key: queue!.key, trackId: track.id, title: track.title, ...(queue!.album ? { album: queue!.album } : {}),
      playing, positionMs: 0, durationMs: Number.isFinite(span.endMs) ? span.endMs - span.startMs : 0,
    }
    const session = env.mediaSession
    if (session) {
      try {
        session.metadata = env.metadata({ title: track.title, ...(queue!.album ? { album: queue!.album } : {}) })
        session.playbackState = playing ? 'playing' : 'paused'
      } catch { /* a browser without metadata */ }
      positionState()
    }
  }

  function positionState(): void {
    const session = env.mediaSession
    const np = nowPlaying.value
    if (!session?.setPositionState || !np || !np.durationMs) return
    try {
      session.setPositionState({ duration: np.durationMs / 1000, position: Math.min(np.positionMs, np.durationMs) / 1000, playbackRate: 1 })
    } catch { /* a position outside the duration while it settles */ }
  }

  function tick(): void {
    const deck = decks[active]
    const track = deck.track
    if (!track || !order) return
    const pos = deck.el.currentTime * 1000
    const span = deck.span
    const np = nowPlaying.value
    if (np && np.trackId === track.id) {
      const positionMs = Math.max(0, Math.min(pos, span.endMs) - span.startMs)
      nowPlaying.value = { ...np, positionMs, playing: !deck.el.paused }
      if (Math.floor(positionMs / 1000) !== Math.floor(lastPosition / 1000)) positionState()
      lastPosition = positionMs
    }
    if (deck.el.paused || deck.leaving) return
    const fadeAt = deck.gain ? fadeOutAtMs(span) : span.endMs
    if (pos >= fadeAt - OPEN_AHEAD_MS) prepareNext()
    if (pos >= fadeAt) {
      deck.leaving = true
      void bringIn(order.next(), deck.gain ? fadeLengthAt(span, pos) : 0)
    }
  }

  /** Open and load the next track on the idle deck ahead of the fade. */
  function prepareNext(): void {
    const idle = decks[1 - active]
    if (idle.preparedId || idle.source || !order) return
    const track = trackOf(order.peek())
    if (!track) return
    idle.preparedId = track.id
    // a failed preparation is met again where the fade awaits it, and skipped there
    load(idle, track).catch(() => undefined)
  }

  function startTicker(): void {
    if (ticker === null) ticker = env.setInterval(tick, TICK_MS)
  }

  /** The playing deck's end reached without a fade (no Web Audio, or a span longer than the file): on to the next. */
  for (const deck of decks) {
    deck.el.addEventListener('ended', () => {
      if (deck === decks[active] && deck.track && !deck.leaving && order) {
        deck.leaving = true
        void bringIn(order.next(), 0)
      }
    })
  }

  function bindSession(): void {
    const session = env.mediaSession
    if (!session) return
    const set = (action: string, fn: ((d: { seekTime?: number }) => void) | null) => { try { session.setActionHandler(action, fn) } catch { /* unsupported action */ } }
    set('play', () => player.resume())
    set('pause', () => player.pause())
    set('nexttrack', () => player.next())
    set('previoustrack', () => player.previous())
    set('seekto', (d) => { if (typeof d.seekTime === 'number') player.seek(d.seekTime * 1000) })
  }
  function unbindSession(): void {
    const session = env.mediaSession
    if (!session) return
    for (const action of ['play', 'pause', 'nexttrack', 'previoustrack', 'seekto']) {
      try { session.setActionHandler(action, null) } catch { /* unsupported action */ }
    }
    try {
      session.metadata = null
      session.playbackState = 'none'
    } catch { /* no state */ }
  }

  const player: AudioPlayer = {
    nowPlaying,
    notice,
    play(next: PlayerQueue) {
      // first, in the press's own stack: the context and the elements (iOS)
      wakeContext()
      unlock()
      notice.value = null
      const ids = next.tracks.map((t) => t.id)
      if (ids.length === 0) return
      const replacing = queue !== null && !!decks[active].source
      const fade = replacing ? pressFadeMs(decks[active].span) : pressFadeMs({ startMs: 0, endMs: Infinity })
      queue = next
      order = new PlayOrder(ids, env.random, next.startId)
      failures = 0
      // the idle deck may hold a prepared track of the old queue
      const idle = decks[1 - active]
      if (idle.source) clear(idle)
      bindSession()
      void bringIn(order.current(), fade)
    },
    stop() {
      generation++
      if (ticker !== null) env.clearInterval(ticker)
      ticker = null
      for (const deck of decks) clear(deck)
      queue = null
      order = null
      nowPlaying.value = null
      unbindSession()
    },
    pause() {
      const deck = decks[active]
      const other = decks[1 - active]
      if (other.source && other.leaving) clear(other) // a crossfade in flight ends where it is
      deck.el.pause()
      if (nowPlaying.value) nowPlaying.value = { ...nowPlaying.value, playing: false }
      if (env.mediaSession) env.mediaSession.playbackState = 'paused'
    },
    resume() {
      wakeContext()
      const deck = decks[active]
      if (!deck.source) return
      setGain(deck, 1)
      void deck.el.play().catch(() => undefined)
      if (nowPlaying.value) nowPlaying.value = { ...nowPlaying.value, playing: true }
      if (env.mediaSession) env.mediaSession.playbackState = 'playing'
    },
    next() {
      wakeContext()
      if (!order) return
      decks[active].leaving = true
      void bringIn(order.next(), pressFadeMs(decks[active].span))
    },
    previous() {
      wakeContext()
      if (!order) return
      const deck = decks[active]
      const into = deck.el.currentTime * 1000 - deck.span.startMs
      const step = order.previous(into)
      if (step.restart) return player.seek(0)
      deck.leaving = true
      void bringIn(step.id, pressFadeMs(deck.span))
    },
    seek(positionMs: number) {
      const deck = decks[active]
      if (!deck.track) return
      const length = deck.span.endMs - deck.span.startMs
      const rel = Math.max(0, Math.min(positionMs, Number.isFinite(length) ? length : positionMs))
      deck.el.currentTime = (deck.span.startMs + rel) / 1000
      if (nowPlaying.value) nowPlaying.value = { ...nowPlaying.value, positionMs: rel }
      lastPosition = rel
      positionState()
    },
  }
  return player
}

// --- the one player of the page --------------------------------------------------------------------------------------

/** A silent WAV of 0.1 s, as a `blob:` (the page's CSP allows `media-src blob:`), made once. */
let silent: string | null = null
function silentWavUrl(): string {
  if (silent) return silent
  const rate = 8000
  const frames = 800
  const bytes = new Uint8Array(44 + frames * 2)
  const v = new DataView(bytes.buffer)
  const str = (at: number, s: string) => { for (let i = 0; i < 4; i++) bytes[at + i] = s.charCodeAt(i) }
  str(0, 'RIFF'); v.setUint32(4, 36 + frames * 2, true); str(8, 'WAVE'); str(12, 'fmt ')
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, rate, true)
  v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); str(36, 'data'); v.setUint32(40, frames * 2, true)
  silent = URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'audio/wav' }))
  return silent
}

export function browserPlayerEnv(): PlayerEnv {
  const Ctx = (globalThis.AudioContext ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext) as typeof AudioContext | undefined
  const Meta = (globalThis as { MediaMetadata?: typeof MediaMetadata }).MediaMetadata
  return {
    createElement: () => document.createElement('audio') as unknown as MediaElementLike,
    createContext: () => (Ctx ? (new Ctx() as unknown as AudioContextLike) : null),
    silentUrl: silentWavUrl,
    mediaSession: typeof navigator !== 'undefined' && 'mediaSession' in navigator ? (navigator.mediaSession as unknown as MediaSessionLike) : null,
    metadata: (init) => (Meta ? new Meta(init) : init),
    random: cryptoRandomInt,
    setInterval: (fn, ms) => setInterval(fn, ms),
    clearInterval: (h) => clearInterval(h as ReturnType<typeof setInterval>),
    setTimeout: (fn, ms) => setTimeout(fn, ms),
  }
}

let instance: AudioPlayer | null = null
/** The page's one player (decision 8: a new queue replaces the one playing). Created on first use. */
export function audioPlayer(): AudioPlayer {
  return (instance ??= createAudioPlayer(browserPlayerEnv()))
}
