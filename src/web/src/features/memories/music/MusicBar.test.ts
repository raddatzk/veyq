// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MusicBar from './MusicBar.vue'
import { realI18n } from '../../../test/i18n'
import { capabilityState } from '../../../shared/state/capabilities'
import { setThemePreference } from '../../../shared/state/useThemeState'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { setMusicConsent } from './useMusicConsent'
import { closeBar, playInBar, playlistInBar } from './musicPlayback'
import type { Playlist } from './musicModel'

// The music bar (uc.memory.music, ADR 0131 decision 5) - Memories' footer bar, one per shell, the same for a member and a
// share guest. It draws nothing until a header starts a playlist; nothing of the service loads before the viewer's "Play
// here"; then the music frame (an iframe of the music host) is handed the playlist, the public credential and the app's
// theme once it says ready; a later playlist of the same service goes to the same frame, another service's gets a new one;
// "Turn music off" withdraws, tells the frame to forget and keeps it until it said so; Close ends the music, keeping the
// consent; the bar's unmount ends it too.

const FRAME = 'https://music.example.org/music-frame'
const ORIGIN = 'https://music.example.org'
const CLIENT_ID = '0123456789abcdef0123456789abcdef'
const spotify: Playlist = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }
const spotify2: Playlist = { service: 'spotify', id: '1111111111111111111111' }
const apple: Playlist = { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }

const stubs = {
  VeyqIcon: { template: '<i />' },
  VeyqButton: { props: ['label'], template: `<button type="button" @click="$emit('click', $event)">{{ label }}</button>` },
}

function serve(spotifyOn = true, appleOn = false) {
  const entries = { ...capabilityState.entries }
  entries[MEMORIES_MUSIC] = { id: MEMORIES_MUSIC, serviceUrl: FRAME }
  if (spotifyOn) entries[MEMORIES_MUSIC_SPOTIFY] = { id: MEMORIES_MUSIC_SPOTIFY, spotifyClientId: CLIENT_ID }
  else delete entries[MEMORIES_MUSIC_SPOTIFY]
  if (appleOn) entries[MEMORIES_MUSIC_APPLE_MUSIC] = { id: MEMORIES_MUSIC_APPLE_MUSIC, appleDeveloperToken: 'a.b.c-token' }
  else delete entries[MEMORIES_MUSIC_APPLE_MUSIC]
  capabilityState.tenantEntries = null
  capabilityState.entries = entries
}

// Never attached to the document: happy-dom loads an attached iframe's page, and nothing here needs layout.
const mountBar = () => mount(MusicBar, { global: { plugins: [realI18n()], stubs } })

/** Give the mounted iframe a window the test can see messages to and from. */
function frameWindow(w: ReturnType<typeof mountBar>) {
  const el = w.find('[data-testid="music-frame"]').element
  const win = { postMessage: vi.fn() }
  Object.defineProperty(el, 'contentWindow', { value: win, configurable: true })
  const say = (data: unknown, origin = ORIGIN, source: unknown = win) =>
    window.dispatchEvent(new MessageEvent('message', { data, origin, source: source as Window }))
  return { win, say, el }
}

beforeEach(() => {
  serve()
  setMusicConsent('spotify', false)
  setMusicConsent('apple-music', false)
  setThemePreference('light')
  closeBar()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('MusicBar', () => {
  it('draws nothing while no header started a playlist', () => {
    const w = mountBar()
    expect(w.find('[data-testid="music-bar"]').exists()).toBe(false)
    w.unmount()
  })

  it('before the opt-in it names the service, the music host and what the service learns, and loads nothing', async () => {
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    expect(w.find('[data-testid="music-consent"]').text()).toContain('music.example.org')
    expect(w.find('[data-testid="music-consent"]').text()).toContain('IP address')
    expect(w.find('iframe').exists(), 'no frame of any origin').toBe(false)
    expect(w.find<HTMLAnchorElement>('[data-testid="music-open"]').element.href).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    await w.find('[data-testid="music-close"]').trigger('click')
    expect(playlistInBar.value, 'Close ends it without an opt-in').toBeNull()
    expect(localStorage.getItem('veyq:music-consent')).toBeNull()
    w.unmount()
  })

  it('"Play here" records the consent and mounts the frame, which gets the playlist, the credential and the theme once ready', async () => {
    setThemePreference('dark')
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    await w.find('[data-testid="music-play-here"]').trigger('click')
    const iframe = w.find('[data-testid="music-frame"]')
    expect(iframe.attributes('src')).toBe(FRAME)
    expect(iframe.attributes('allow')).toBe('encrypted-media; autoplay')
    expect(iframe.attributes('sandbox'), 'a sandbox would make it opaque: no storage, no DRM (ADR 0131 § Context)').toBeUndefined()
    expect(JSON.parse(localStorage.getItem('veyq:music-consent')!)).toEqual({ host: 'music.example.org', services: ['spotify'] })
    const { win, say } = frameWindow(w)
    say({ type: 'veyq-music:ready' }, 'https://evil.example')
    say({ type: 'veyq-music:ready' }, ORIGIN, {})
    expect(win.postMessage, 'nothing goes to a message of another origin or another window').not.toHaveBeenCalled()
    say({ type: 'veyq-music:ready' })
    expect(win.postMessage).toHaveBeenCalledWith(
      { type: 'veyq-music:load', playlist: spotify, credential: CLIENT_ID, locale: 'en', theme: 'dark' }, ORIGIN,
    )
    setThemePreference('light')
    await flushPromises()
    expect(win.postMessage, 'the frame follows the app\'s theme').toHaveBeenLastCalledWith({ type: 'veyq-music:theme', theme: 'light' }, ORIGIN)
    w.unmount()
  })

  it('a later playlist of the same service goes to the same frame; another service\'s gets a new frame', async () => {
    setMusicConsent('spotify', true)
    setMusicConsent('apple-music', true)
    serve(true, true)
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    const first = frameWindow(w)
    first.say({ type: 'veyq-music:ready' })
    playInBar(spotify2)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').element, 'the one frame stays').toBe(first.el)
    expect(first.win.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'veyq-music:load', playlist: spotify2 }), ORIGIN)
    playInBar(apple)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').element, 'another vendor: a new frame').not.toBe(first.el)
    expect(w.find('[data-testid="music-bar"]').attributes('data-service')).toBe('apple-music')
    w.unmount()
  })

  it('"Turn music off" withdraws, tells the frame to forget, keeps it until it said so, then ends the music (A1)', async () => {
    setMusicConsent('spotify', true)
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    const { win, say } = frameWindow(w)
    await w.find('[data-testid="music-off"]').trigger('click')
    expect(win.postMessage).toHaveBeenCalledWith({ type: 'veyq-music:forget' }, ORIGIN)
    expect(localStorage.getItem('veyq:music-consent')).toBeNull()
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists(), 'the frame stays to forget the sign-in').toBe(true)
    say({ type: 'veyq-music:forgotten' })
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    expect(playlistInBar.value).toBeNull()
    w.unmount()
  })

  it('a frame that never says forgotten is removed after the wait, never kept', async () => {
    vi.useFakeTimers()
    setMusicConsent('spotify', true)
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    frameWindow(w)
    await w.find('[data-testid="music-off"]').trigger('click')
    vi.advanceTimersByTime(1600)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    w.unmount()
  })

  it('Close ends the music and keeps the consent; a withdrawal in another tab removes the frame here', async () => {
    setMusicConsent('spotify', true)
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    await w.find('[data-testid="music-close"]').trigger('click')
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    expect(JSON.parse(localStorage.getItem('veyq:music-consent')!).services).toEqual(['spotify'])
    playInBar(spotify)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists(), 'standing consent: the frame at once').toBe(true)
    localStorage.removeItem('veyq:music-consent')
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:music-consent' }))
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    w.unmount()
  })

  it('a service the server stops playing here leaves the bar; its unmount ends the music', async () => {
    setMusicConsent('spotify', true)
    const w = mountBar()
    playInBar(spotify)
    await flushPromises()
    serve(false, false)
    await flushPromises()
    expect(playlistInBar.value).toBeNull()
    serve()
    playInBar(spotify)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(true) // control
    w.unmount()
    expect(playlistInBar.value, 'the shell unmounting the bar ends it').toBeNull()
  })
})
