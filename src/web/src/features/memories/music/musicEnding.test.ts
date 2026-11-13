// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { capabilityState } from '../../../shared/state/capabilities'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { setMusicConsent } from './useMusicConsent'
import { FORGET_WAIT_MS, forgetMusicSignIn, setLiveFrameForget } from './musicEnding'
import { memoriesLifecycle } from '../lifecycle'

// A veyq sign-out forgets the vendor's sign-in in the music frame (ADR 0131 decision 5, owner 2026-11-13): a mounted frame
// is asked through the bar; with none mounted, a hidden frame is mounted to forget - only in the tab that started the
// ending, and only for a browser that agreed to Apple Music at the music host served now (the one service whose sign-in
// outlives its frame).

const FRAME = 'https://music.example.org/music-frame'
const ORIGIN = 'https://music.example.org'

function serve() {
  const entries = { ...capabilityState.entries }
  entries[MEMORIES_MUSIC] = { id: MEMORIES_MUSIC, serviceUrl: FRAME }
  entries[MEMORIES_MUSIC_SPOTIFY] = { id: MEMORIES_MUSIC_SPOTIFY, spotifyClientId: '0123456789abcdef0123456789abcdef' }
  entries[MEMORIES_MUSIC_APPLE_MUSIC] = { id: MEMORIES_MUSIC_APPLE_MUSIC, appleDeveloperToken: 'a.b.c-token' }
  capabilityState.tenantEntries = null
  capabilityState.entries = entries
}

/** Catch the hidden frame instead of attaching it (happy-dom would load its page), and give it a window to talk with. */
function catchFrames() {
  const frames: { el: HTMLIFrameElement; win: { postMessage: ReturnType<typeof vi.fn> } }[] = []
  vi.spyOn(document.body, 'appendChild').mockImplementation(<T extends Node>(node: T): T => {
    const el = node as unknown as HTMLIFrameElement
    const win = { postMessage: vi.fn() }
    Object.defineProperty(el, 'contentWindow', { value: win, configurable: true })
    frames.push({ el, win })
    return node
  })
  const say = (win: unknown, data: unknown, origin = ORIGIN) =>
    window.dispatchEvent(new MessageEvent('message', { data, origin, source: win as Window }))
  return { frames, say }
}

beforeEach(() => {
  serve()
  setMusicConsent('spotify', false)
  setMusicConsent('apple-music', false)
  setLiveFrameForget(null)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  setLiveFrameForget(null)
})

describe('a sign-out forgets the music sign-in', () => {
  it('is Memories\' ending step', () => {
    expect(memoriesLifecycle.onSessionEnding).toBe(forgetMusicSignIn)
  })

  it('asks the mounted frame, through the bar, and mounts no other', async () => {
    const { frames } = catchFrames()
    setMusicConsent('apple-music', true)
    const live = vi.fn(async () => {})
    setLiveFrameForget(live)
    await forgetMusicSignIn({ startedHere: true })
    expect(live).toHaveBeenCalledOnce()
    expect(frames).toHaveLength(0)
  })

  it('with no frame mounted and Apple Music agreed to, mounts a hidden frame, tells it to forget once ready, and removes it once it did', async () => {
    const { frames, say } = catchFrames()
    setMusicConsent('apple-music', true)
    let done = false
    const forgetting = forgetMusicSignIn({ startedHere: true }).then(() => (done = true))
    expect(frames).toHaveLength(1)
    const [{ el, win }] = frames
    expect(el.src).toBe(FRAME)
    expect(el.hidden).toBe(true)
    expect(win.postMessage, 'nothing is sent before the frame listens').not.toHaveBeenCalled()

    say({}, { type: 'veyq-music:ready' }) // another window: ignored
    say(win, { type: 'veyq-music:ready' }, 'https://evil.example') // another origin: ignored
    expect(win.postMessage).not.toHaveBeenCalled()
    say(win, { type: 'veyq-music:ready' })
    expect(win.postMessage).toHaveBeenCalledExactlyOnceWith({ type: 'veyq-music:forget' }, ORIGIN)

    const remove = vi.spyOn(el, 'remove')
    await Promise.resolve()
    expect(done).toBe(false)
    say(win, { type: 'veyq-music:forgotten' })
    await forgetting
    expect(remove).toHaveBeenCalledOnce()
  })

  it('gives a silent hidden frame up after the wait', async () => {
    vi.useFakeTimers()
    const { frames } = catchFrames()
    setMusicConsent('apple-music', true)
    let done = false
    void forgetMusicSignIn({ startedHere: true }).then(() => (done = true))
    const remove = vi.spyOn(frames[0].el, 'remove')
    await vi.advanceTimersByTimeAsync(FORGET_WAIT_MS - 1)
    expect(done).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(done).toBe(true)
    expect(remove).toHaveBeenCalledOnce()
  })

  it('mounts nothing for a browser that agreed to Spotify only: its token died with its frame', async () => {
    const { frames } = catchFrames()
    setMusicConsent('spotify', true)
    await forgetMusicSignIn({ startedHere: true })
    expect(frames).toHaveLength(0)
  })

  it('mounts nothing for a browser that never agreed', async () => {
    const { frames } = catchFrames()
    await forgetMusicSignIn({ startedHere: true })
    expect(frames).toHaveLength(0)
  })

  it('mounts nothing in a tab told by another: the tab that started the ending forgets for the browser', async () => {
    const { frames } = catchFrames()
    setMusicConsent('apple-music', true)
    await forgetMusicSignIn({ startedHere: false })
    expect(frames).toHaveLength(0)
  })

  it('mounts nothing while the server names no music host', async () => {
    const { frames } = catchFrames()
    setMusicConsent('apple-music', true)
    const entries = { ...capabilityState.entries }
    delete entries[MEMORIES_MUSIC]
    capabilityState.entries = entries
    await forgetMusicSignIn({ startedHere: true })
    expect(frames).toHaveLength(0)
  })
})
