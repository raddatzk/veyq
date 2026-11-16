// Core's audio player (en.platform.audio-player, ADR 0135 decisions 6-8) against stubbed media elements, a stubbed
// AudioContext and a stubbed MediaSession: what it opens and releases, when it fades, what it skips, and that the context
// is woken inside the press's own stack.
import { describe, it, expect, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createAudioPlayer, type AudioContextLike, type GainLike, type MediaElementLike, type MediaSessionLike, type PlayerEnv, type PlayerTrack } from './player'

const SILENT = 'blob:silent'

class FakeElement implements MediaElementLike {
  private url = ''
  currentTime = 0
  paused = true
  preload = ''
  private listeners = new Map<string, Set<() => void>>()
  constructor(private readonly durations: Record<string, number>) {}
  get src() { return this.url }
  /** Setting a source: the element stops (a media element's load algorithm pauses it) and reads its metadata a moment
   *  later (an error for a broken one). */
  set src(v: string) {
    this.url = v
    this.paused = true
    queueMicrotask(() => {
      if (this.url !== v || !v || v === SILENT) return
      this.emit(v.includes('broken') ? 'error' : 'loadedmetadata')
    })
  }
  get duration() { return this.durations[this.url] ?? NaN }
  play() {
    if (this.url.includes('unplayable')) return Promise.reject(new Error('NotSupportedError'))
    this.paused = false
    return Promise.resolve()
  }
  pause() { this.paused = true }
  load() {}
  removeAttribute(name: string) { if (name === 'src') this.url = '' }
  addEventListener(type: string, fn: () => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type)!.add(fn)
  }
  removeEventListener(type: string, fn: () => void) { this.listeners.get(type)?.delete(fn) }
  emit(type: string) { for (const fn of [...(this.listeners.get(type) ?? [])]) fn() }
}

type Ramp = { deck: number; from: number; to: number; ms: number }
function fakeContext() {
  const ramps: Ramp[] = []
  let gains = 0
  const ctx = {
    state: 'suspended',
    currentTime: 0,
    destination: {},
    resume: vi.fn(async () => { ctx.state = 'running' }),
    createMediaElementSource: vi.fn(() => ({ connect: vi.fn() })),
    createGain: vi.fn((): GainLike => {
      const deck = gains++
      let from = 0
      const g = {
        value: 0,
        setValueAtTime: (v: number) => { g.value = v; from = v },
        linearRampToValueAtTime: (v: number, t: number) => { ramps.push({ deck, from, to: v, ms: Math.round(t * 1000) }); g.value = v },
        cancelScheduledValues: () => {},
      }
      return { gain: g, connect: vi.fn() }
    }),
  }
  return { ctx: ctx as AudioContextLike & typeof ctx, ramps }
}

function harness(opts: { durations?: Record<string, number>; webAudio?: boolean } = {}) {
  const durations = opts.durations ?? {}
  const elements: FakeElement[] = []
  const { ctx, ramps } = fakeContext()
  const ticks: (() => void)[] = []
  const timeouts: (() => void)[] = []
  const handlers = new Map<string, ((d: { seekTime?: number }) => void) | null>()
  const session: MediaSessionLike & { position?: unknown } = {
    metadata: null,
    setActionHandler: (a, h) => { handlers.set(a, h) },
    setPositionState: (s) => { session.position = s },
  }
  const createContext = vi.fn(() => (opts.webAudio === false ? null : ctx))
  const env: PlayerEnv = {
    createElement: () => { const el = new FakeElement(durations); elements.push(el); return el },
    createContext,
    silentUrl: () => SILENT,
    mediaSession: session,
    metadata: (init) => ({ ...init }),
    random: () => 0,
    setInterval: (fn) => { ticks.push(fn); return ticks.length },
    clearInterval: () => { ticks.length = 0 },
    setTimeout: (fn) => { timeouts.push(fn); return timeouts.length },
  }
  const player = createAudioPlayer(env)
  const tick = () => { for (const fn of ticks) fn() }
  const runTimeouts = () => { for (const fn of timeouts.splice(0)) fn() }
  return { player, elements, ctx, ramps, createContext, session, handlers, tick, runTimeouts }
}

/** A track whose `open()` resolves to `url` and counts its releases. */
function track(id: string, over: Partial<PlayerTrack> & { url?: string } = {}) {
  const release = vi.fn()
  const open = vi.fn(async () => {
    if (over.url === 'fail') throw new Error('open_failed')
    return { url: over.url ?? `blob:${id}`, release }
  })
  return { t: { id, title: `Song ${id}`, startMs: 1000, endMs: 61_000, ...over, open } as PlayerTrack, release, open }
}

const playing = (h: ReturnType<typeof harness>) => h.elements.find((e) => !e.paused && e.src !== SILENT)
const sounding = (h: ReturnType<typeof harness>) => h.elements.filter((e) => !e.paused && e.src !== SILENT).map((e) => e.src)
/** Whether the bar and the lock screen show it playing. */
const shown = (h: ReturnType<typeof harness>) => [h.player.nowPlaying.value?.playing, h.session.playbackState]

/** A track whose `open()` waits for the test: `resolve()` hands its source over. */
function slow(id: string) {
  let resolve!: () => void
  const release = vi.fn()
  const t: PlayerTrack = { id, title: `Song ${id}`, startMs: 1000, endMs: 61_000, open: () => new Promise((r) => { resolve = () => r({ url: `blob:${id}`, release }) }) }
  return { t, resolve: () => resolve(), release }
}

describe('the player', () => {
  it('wakes the AudioContext and each element in the press\'s own stack, before anything is awaited (iOS)', () => {
    const h = harness()
    const a = track('a')
    h.player.play({ key: 'n1', tracks: [a.t] })
    // nothing has been awaited yet: the press's stack is still running
    expect(h.createContext).toHaveBeenCalledTimes(1)
    expect(h.ctx.resume).toHaveBeenCalledTimes(1)
    expect(h.elements.map((e) => e.src)).toEqual([SILENT, SILENT])
    expect(a.open).toHaveBeenCalledTimes(1)
  })

  it('plays the first track from its trimmed start, fading in over 1 s, and says what plays', async () => {
    const h = harness({ durations: { 'blob:a': 62 } })
    const a = track('a')
    h.player.play({ key: 'n1', album: 'Lisbon', href: '/#memories/lisbon', tracks: [a.t] })
    await flushPromises()
    const el = playing(h)!
    expect([el.src, el.currentTime]).toEqual(['blob:a', 1])
    expect(h.ramps).toContainEqual({ deck: expect.any(Number), from: 0, to: 1, ms: 1000 })
    expect(h.player.nowPlaying.value).toMatchObject({ key: 'n1', trackId: 'a', title: 'Song a', album: 'Lisbon', href: '/#memories/lisbon', playing: true, durationMs: 60_000 })
    expect(h.session.metadata).toEqual({ title: 'Song a', album: 'Lisbon' })
    expect([...h.handlers.keys()].sort()).toEqual(['nexttrack', 'pause', 'play', 'previoustrack', 'seekto'])
  })

  it('crossfades into the next track over 5 s once the playing one reaches its fade, opening it ahead', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:b': 62 } })
    const a = track('a')
    const b = track('b')
    h.player.play({ key: 'n1', tracks: [a.t, b.t] })
    await flushPromises()
    const first = playing(h)!
    const firstId = first.src
    first.currentTime = 41 // 15 s before the fade at 56 s: the next one is opened and loaded
    h.tick()
    await flushPromises()
    expect([a.open.mock.calls.length + b.open.mock.calls.length]).toEqual([2])
    h.ramps.length = 0
    first.currentTime = 56 // the fade's start: trimmed end 61 s minus 5 s
    h.tick()
    await flushPromises()
    expect(h.ramps.map((r) => [r.from, r.to, r.ms])).toEqual([[0, 1, 5000], [1, 0, 5000]])
    expect(h.player.nowPlaying.value?.trackId).not.toBe(firstId.slice(5))
    // the faded-out source is let go once its fade has run
    const released = () => a.release.mock.calls.length + b.release.mock.calls.length
    expect(released()).toBe(0)
    h.runTimeouts()
    expect(released()).toBe(1)
  })

  it('a seek into the fade window shortens the fade to what is left', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:b': 62 } })
    h.player.play({ key: 'n1', tracks: [track('a').t, track('b').t] })
    await flushPromises()
    h.player.seek(58_000) // into the span: 1 s + 58 s = 59 s of the file, 2 s before the trimmed end
    expect(playing(h)!.currentTime).toBe(59)
    h.ramps.length = 0
    h.tick()
    await flushPromises()
    expect(h.ramps.map((r) => r.ms)).toEqual([2000, 2000])
  })

  it('a failing open() skips the track; a queue none of whose tracks plays ends and says so', async () => {
    const h = harness({ durations: { 'blob:b': 62 } })
    const bad = track('a', { url: 'fail' })
    const good = track('b')
    h.player.play({ key: 'n1', tracks: [bad.t, good.t], startId: 'a' })
    await flushPromises()
    expect(h.player.nowPlaying.value?.trackId).toBe('b')

    const none = harness()
    none.player.play({ key: 'n2', tracks: [track('x', { url: 'fail' }).t, track('y', { url: 'blob:broken' }).t, track('z', { url: 'blob:unplayable' }).t] })
    await flushPromises()
    expect([none.player.nowPlaying.value, none.player.notice.value]).toEqual([null, 'unplayable'])
  })

  it('a new queue replaces the one playing, crossfading over 1 s and releasing the old source', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:m': 62 } })
    const a = track('a')
    h.player.play({ key: 'n1', tracks: [a.t] })
    await flushPromises()
    h.ramps.length = 0
    const m = track('m')
    h.player.play({ key: 'n2', tracks: [m.t] })
    await flushPromises()
    expect(h.player.nowPlaying.value).toMatchObject({ key: 'n2', trackId: 'm' })
    expect(h.ramps.map((r) => [r.from, r.to, r.ms])).toEqual([[0, 1, 1000], [1, 0, 1000]])
    h.runTimeouts()
    expect(a.release).toHaveBeenCalledTimes(1)
  })

  it('stop releases every source it opened - the one playing and one opened ahead - and clears the lock screen', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:b': 62 } })
    const a = track('a')
    const b = track('b')
    h.player.play({ key: 'n1', tracks: [a.t, b.t] })
    await flushPromises()
    playing(h)!.currentTime = 45
    h.tick()
    await flushPromises()
    h.player.stop()
    expect([a.release.mock.calls.length, b.release.mock.calls.length]).toEqual([1, 1])
    expect(h.player.nowPlaying.value).toBeNull()
    expect([h.session.metadata, h.handlers.get('play')]).toEqual([null, null])
    expect(h.elements.every((e) => e.paused)).toBe(true)
  })

  it('a source that opens after the queue was stopped is released at once', async () => {
    const h = harness({ durations: { 'blob:a': 62 } })
    let resolve!: (v: { url: string; release: () => void }) => void
    const release = vi.fn()
    const slow: PlayerTrack = { id: 's', title: 'Slow', open: () => new Promise((r) => { resolve = r }) }
    h.player.play({ key: 'n1', tracks: [slow] })
    h.player.stop()
    resolve({ url: 'blob:a', release })
    await flushPromises()
    expect(release).toHaveBeenCalledTimes(1)
    expect(h.player.nowPlaying.value).toBeNull()
  })

  it('next and previous: next crossfades over 1 s; previous restarts after 3 s, goes back before', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:b': 62, 'blob:c': 62 } })
    h.player.play({ key: 'n1', tracks: [track('a').t, track('b').t, track('c').t] })
    await flushPromises()
    const first = h.player.nowPlaying.value!.trackId
    h.ramps.length = 0
    h.player.next()
    await flushPromises()
    const second = h.player.nowPlaying.value!.trackId
    expect(second).not.toBe(first)
    expect(h.ramps.map((r) => r.ms)).toEqual([1000, 1000])
    playing(h)!.currentTime = 1 + 4 // 4 s into the span
    h.player.previous()
    expect([h.player.nowPlaying.value!.trackId, playing(h)!.currentTime]).toEqual([second, 1])
    playing(h)!.currentTime = 1 + 2 // 2 s in
    h.player.previous()
    await flushPromises()
    expect(h.player.nowPlaying.value!.trackId).toBe(first)
    // the lock screen's keys reach the same doors
    h.handlers.get('nexttrack')!({})
    await flushPromises()
    expect(h.player.nowPlaying.value!.trackId).toBe(second)
  })

  it('without Web Audio, tracks change without a fade: at the trimmed end, or when the element ends', async () => {
    const h = harness({ durations: { 'blob:a': 62, 'blob:b': 62 }, webAudio: false })
    h.player.play({ key: 'n1', tracks: [track('a').t, track('b').t] })
    await flushPromises()
    const first = h.player.nowPlaying.value!.trackId
    const el = playing(h)!
    el.currentTime = 56
    h.tick()
    await flushPromises()
    expect(h.player.nowPlaying.value!.trackId, 'no early change: no fade to make').toBe(first)
    el.currentTime = 61
    h.tick()
    await flushPromises()
    expect(h.player.nowPlaying.value!.trackId).not.toBe(first)
    expect(el.paused, 'the old one stops at once').toBe(true)
    expect(h.ramps).toEqual([])
  })

  it('a track without facts plays its whole file, its length read from the element', async () => {
    const h = harness({ durations: { 'blob:a': 30 } })
    h.player.play({ key: 'n1', tracks: [track('a', { startMs: undefined, endMs: undefined }).t] })
    await flushPromises()
    expect([playing(h)!.currentTime, h.player.nowPlaying.value!.durationMs]).toEqual([0, 30_000])
  })

  it('pause and resume reach the playing element and the lock screen', async () => {
    const h = harness({ durations: { 'blob:a': 62 } })
    h.player.play({ key: 'n1', tracks: [track('a').t] })
    await flushPromises()
    const el = playing(h)!
    h.handlers.get('pause')!({})
    expect([el.paused, h.player.nowPlaying.value!.playing, h.session.playbackState]).toEqual([true, false, 'paused'])
    h.player.resume()
    expect([el.paused, h.player.nowPlaying.value!.playing, h.session.playbackState]).toEqual([false, true, 'playing'])
  })

  // ADR 0142 decision 13: a lightbox video interrupts the music; its end resumes only what it paused, and only when no
  // listener's play or pause, new queue or stop came between.
  describe('interrupt', () => {
    async function started() {
      const h = harness({ durations: { 'blob:a': 62, 'blob:m': 62 } })
      h.player.play({ key: 'n1', tracks: [track('a').t] })
      await flushPromises()
      return { h, el: playing(h)! }
    }

    it('pauses a playing queue, and the lock screen shows it paused', async () => {
      const { h, el } = await started()
      h.player.interrupt()
      expect([el.paused, ...shown(h)]).toEqual([true, false, 'paused'])
      h.tick()
      expect(h.player.nowPlaying.value?.playing, 'the tick reads it paused too').toBe(false)
    })

    it('its end resumes the queue, once', async () => {
      const { h, el } = await started()
      const end = h.player.interrupt()
      end()
      expect([el.paused, ...shown(h)]).toEqual([false, true, 'playing'])
      h.player.pause()
      end()
      expect(el.paused, 'a second call of the end does nothing').toBe(true)
    })

    it("a listener's pause during it keeps the music paused after", async () => {
      const { h, el } = await started()
      const end = h.player.interrupt()
      h.handlers.get('pause')!({}) // the lock screen's pause, the bar's the same door
      end()
      expect([el.paused, ...shown(h)]).toEqual([true, false, 'paused'])
    })

    it("a listener's play during it sounds both, and the end then does nothing", async () => {
      const { h, el } = await started()
      const end = h.player.interrupt()
      h.player.resume()
      expect(el.paused).toBe(false)
      h.player.pause() // paused again by the listener: the end must not take it back
      end()
      expect([el.paused, ...shown(h)]).toEqual([true, false, 'paused'])
    })

    it('a stop during it makes the end do nothing - it wakes not even the AudioContext', async () => {
      const { h } = await started()
      const end = h.player.interrupt()
      h.player.stop()
      h.ctx.state = 'suspended' // the browser let the idle context sleep (iOS does on an interruption of its own)
      h.ctx.resume.mockClear()
      end()
      expect([h.player.nowPlaying.value, h.session.playbackState, h.elements.every((e) => e.paused)]).toEqual([null, 'none', true])
      expect(h.ctx.resume, 'a live end wakes the context, as a play does; a dropped one touches nothing').not.toHaveBeenCalled()
    })

    it('a new queue during it makes the end do nothing', async () => {
      const { h } = await started()
      const end = h.player.interrupt()
      h.player.play({ key: 'n2', tracks: [track('m').t] })
      await flushPromises()
      const el = playing(h)!
      h.player.pause()
      end()
      expect([el.paused, ...shown(h)]).toEqual([true, false, 'paused'])
    })

    it("the queue's first track still loading when it comes stays silent until the end starts it", async () => {
      const h = harness({ durations: { 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [s.t] })
      const end = h.player.interrupt()
      s.resolve()
      await flushPromises()
      expect([sounding(h), ...shown(h)], 'loaded during the video: held, shown paused').toEqual([[], false, 'paused'])
      end()
      expect([sounding(h), ...shown(h)]).toEqual([['blob:s'], true, 'playing'])
    })

    it('the next track still loading when it comes (a crossfade ahead) stays silent until the end starts it', async () => {
      const h = harness({ durations: { 'blob:a': 62, 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [track('a').t, s.t], startId: 'a' })
      await flushPromises()
      playing(h)!.currentTime = 56 // the fade's start: the next one is opened and awaited
      h.tick()
      const end = h.player.interrupt()
      expect([sounding(h), ...shown(h)], 'the outgoing one paused').toEqual([[], false, 'paused'])
      s.resolve()
      await flushPromises()
      expect([sounding(h), ...shown(h)], 'loaded during the video: held, shown paused').toEqual([[], false, 'paused'])
      expect(h.player.nowPlaying.value?.trackId).toBe('s')
      end()
      expect([sounding(h), ...shown(h)]).toEqual([['blob:s'], true, 'playing'])
    })

    it("a listener's play while the track loads lets it start when it has loaded, and the end then does nothing", async () => {
      const h = harness({ durations: { 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [s.t] })
      const end = h.player.interrupt()
      h.player.resume() // the bar's play: the listener wants it now
      s.resolve()
      await flushPromises()
      expect([sounding(h), ...shown(h)]).toEqual([['blob:s'], true, 'playing'])
      h.player.pause()
      end()
      expect(sounding(h), 'the end, dropped by the play, does not take the pause back').toEqual([])
    })

    it('a queue not playing when interrupted is not started by the end', async () => {
      const { h, el } = await started()
      h.player.pause()
      const end = h.player.interrupt()
      end()
      expect([el.paused, ...shown(h)]).toEqual([true, false, 'paused'])
    })
  })

  // ADR 0142 decision 13's hold, for a listener's pause too: a track that finishes loading after the listener paused
  // becomes the active deck, paused, shown paused; only a play starts it.
  describe("a listener's pause while a track loads", () => {
    it('holds the first track: loaded, it is silent and shown paused; resume plays it', async () => {
      const h = harness({ durations: { 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [s.t] })
      h.player.pause()
      s.resolve()
      await flushPromises()
      expect([sounding(h), h.player.nowPlaying.value?.trackId, ...shown(h)]).toEqual([[], 's', false, 'paused'])
      h.tick()
      expect(h.player.nowPlaying.value?.playing, 'the tick reads it paused too').toBe(false)
      h.player.resume()
      expect([sounding(h), ...shown(h)]).toEqual([['blob:s'], true, 'playing'])
      expect(playing(h)!.currentTime).toBe(1)
    })

    it("during a video: the track stays silent after the video's end", async () => {
      const h = harness({ durations: { 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [s.t] })
      const end = h.player.interrupt()
      h.player.pause()
      s.resolve()
      await flushPromises()
      end()
      expect([sounding(h), ...shown(h)]).toEqual([[], false, 'paused'])
    })

    it("before a video: the video interrupted nothing that played, so its end starts nothing", async () => {
      const h = harness({ durations: { 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [s.t] })
      h.player.pause()
      const end = h.player.interrupt()
      s.resolve()
      await flushPromises()
      end()
      expect([sounding(h), ...shown(h)]).toEqual([[], false, 'paused'])
    })

    it('while the next track opens ahead: it stays silent, and the outgoing one paused', async () => {
      const h = harness({ durations: { 'blob:a': 62, 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [track('a').t, s.t], startId: 'a' })
      await flushPromises()
      const first = playing(h)!
      first.currentTime = 41 // 15 s before the fade: the next one is opened ahead
      h.tick()
      h.player.pause()
      first.currentTime = 56 // where the fade would start, were it playing
      s.resolve()
      await flushPromises()
      h.tick()
      await flushPromises()
      expect([sounding(h), h.player.nowPlaying.value?.trackId, ...shown(h)]).toEqual([[], 'a', false, 'paused'])
      h.player.resume()
      expect(sounding(h), 'resume plays the outgoing one, where it was paused').toEqual(['blob:a'])
    })

    it('while the next track is on its way in (the fade begun): it stays silent and paused; resume plays it', async () => {
      const h = harness({ durations: { 'blob:a': 62, 'blob:s': 62 } })
      const s = slow('s')
      h.player.play({ key: 'n1', tracks: [track('a').t, s.t], startId: 'a' })
      await flushPromises()
      playing(h)!.currentTime = 56 // the fade's start: the next one is opened and awaited
      h.tick()
      h.player.pause()
      s.resolve()
      await flushPromises()
      expect([sounding(h), h.player.nowPlaying.value?.trackId, ...shown(h)]).toEqual([[], 's', false, 'paused'])
      h.player.resume()
      expect([sounding(h), ...shown(h)]).toEqual([['blob:s'], true, 'playing'])
    })
  })
})
