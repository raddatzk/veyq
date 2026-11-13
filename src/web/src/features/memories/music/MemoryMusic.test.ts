// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MemoryMusic from './MemoryMusic.vue'
import { realI18n } from '../../../test/i18n'
import { capabilityState } from '../../../shared/state/capabilities'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { setMusicConsent } from './useMusicConsent'
import type { Playlist } from './musicModel'

// The music bar (uc.memory.music, ADR 0131 decisions 2 and 5) - the same component for a member and a share guest. Nothing
// of the service loads before the viewer's "Play here"; then the music frame (an iframe of the music host) is mounted and
// handed the playlist and the public credential once it says ready; "Turn music off" withdraws, tells it to forget and
// removes it. Where the server does not play a service here, only the link that opens it in the service is offered.

const FRAME = 'https://music.example.org/music-frame'
const ORIGIN = 'https://music.example.org'
const CLIENT_ID = '0123456789abcdef0123456789abcdef'
const spotify: Playlist = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }
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

// Never attached to the document: happy-dom loads an attached iframe's page (or logs a refusal per frame when that is
// switched off), and nothing here needs layout.
const mountBar = (music: Playlist[]) => mount(MemoryMusic, { props: { music }, global: { plugins: [realI18n()], stubs } })

/** Give the mounted iframe a window the test can see messages to and from. */
function frameWindow(w: ReturnType<typeof mountBar>) {
  const el = w.find('[data-testid="music-frame"]').element
  const win = { postMessage: vi.fn() }
  Object.defineProperty(el, 'contentWindow', { value: win, configurable: true })
  const say = (data: unknown, origin = ORIGIN, source: unknown = win) =>
    window.dispatchEvent(new MessageEvent('message', { data, origin, source: source as Window }))
  return { win, say }
}

beforeEach(() => {
  serve()
  setMusicConsent('spotify', false)
  setMusicConsent('apple-music', false)
})

describe('MemoryMusic', () => {
  it('draws nothing without a playlist', () => {
    expect(mountBar([]).find('[data-testid="memory-music"]').exists()).toBe(false)
  })

  it('before the opt-in it names the service, the music host and what the service learns, and loads nothing', () => {
    const w = mountBar([spotify])
    expect(w.find('[data-testid="music-consent"]').text()).toContain('music.example.org')
    expect(w.find('[data-testid="music-consent"]').text()).toContain('IP address')
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    expect(w.find('iframe').exists(), 'no frame of any origin').toBe(false)
    expect(w.find<HTMLAnchorElement>('[data-testid="music-open"]').element.href).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    w.unmount()
  })

  it('"Play here" records the consent and mounts the frame, which gets the playlist and the credential once it is ready', async () => {
    const w = mountBar([spotify])
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
      { type: 'veyq-music:load', playlist: spotify, credential: CLIENT_ID, locale: 'en' }, ORIGIN,
    )
    w.unmount()
  })

  it('a browser that agreed before mounts the frame at once (standing consent)', () => {
    setMusicConsent('spotify', true)
    const w = mountBar([spotify])
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(true)
    w.unmount()
  })

  it('"Turn music off" withdraws, tells the frame to forget, and removes it (A1)', async () => {
    setMusicConsent('spotify', true)
    const w = mountBar([spotify])
    const { win } = frameWindow(w)
    await w.find('[data-testid="music-off"]').trigger('click')
    expect(win.postMessage).toHaveBeenCalledWith({ type: 'veyq-music:forget' }, ORIGIN)
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    expect(localStorage.getItem('veyq:music-consent')).toBeNull()
    expect(w.find('[data-testid="music-play-here"]').exists(), 'the opt-in stands where the frame was').toBe(true)
    w.unmount()
  })

  it('a withdrawal in another tab removes the frame here', async () => {
    setMusicConsent('spotify', true)
    const w = mountBar([spotify])
    localStorage.removeItem('veyq:music-consent')
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:music-consent' }))
    await flushPromises()
    expect(w.find('[data-testid="music-frame"]').exists()).toBe(false)
    w.unmount()
  })

  it('a service the server does not play here offers only its link (A3)', () => {
    serve(false, false)
    const w = mountBar([spotify, apple])
    expect(w.find('[data-testid="music-play-here"]').exists()).toBe(false)
    expect(w.find('[data-testid="music-consent"]').exists()).toBe(false)
    expect(w.findAll('[data-testid="music-open"]').map((a) => (a.element as HTMLAnchorElement).href)).toEqual([
      'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M', 'https://music.apple.com/de/playlist/pl.u-AbC123',
    ])
    // control: served, the same Memory offers the opt-in
    serve(true, true)
    const served = mountBar([spotify, apple])
    expect(served.findAll('[data-testid="music-play-here"]')).toHaveLength(2)
    w.unmount()
    served.unmount()
  })
})
