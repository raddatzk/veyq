// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest'
import { capabilityState } from '../../../shared/state/capabilities'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { setMusicConsent, useMusicConsent } from './useMusicConsent'

// The viewer's opt-in to background music (ADR 0131 decision 5): per browser and per service, naming the music host, off
// until given, withdrawn at once; what is offered is what the server's capabilities carry.

function serve(frameUrl: string | null, spotify = true, apple = true) {
  const entries = { ...capabilityState.entries }
  delete entries[MEMORIES_MUSIC]
  delete entries[MEMORIES_MUSIC_SPOTIFY]
  delete entries[MEMORIES_MUSIC_APPLE_MUSIC]
  if (frameUrl) {
    entries[MEMORIES_MUSIC] = { id: MEMORIES_MUSIC, serviceUrl: frameUrl }
    if (spotify) entries[MEMORIES_MUSIC_SPOTIFY] = { id: MEMORIES_MUSIC_SPOTIFY, spotifyClientId: '0123456789abcdef' }
    if (apple) entries[MEMORIES_MUSIC_APPLE_MUSIC] = { id: MEMORIES_MUSIC_APPLE_MUSIC, appleDeveloperToken: 'a.b.c-token' }
  }
  capabilityState.tenantEntries = null
  capabilityState.entries = entries
}

beforeEach(() => {
  serve('https://music.example.org/music-frame')
  setMusicConsent('spotify', false)
  setMusicConsent('apple-music', false)
})

describe('useMusicConsent', () => {
  it('is off by default; a service is offered where the frame and its credential are served', () => {
    const c = useMusicConsent()
    expect(c.consented('spotify')).toBe(false)
    expect(c.offered('spotify')).toBe(true)
    expect(c.credential('spotify')).toBe('0123456789abcdef')
    expect(c.frameOrigin.value).toBe('https://music.example.org')
    serve('https://music.example.org/music-frame', false, true)
    expect(c.offered('spotify'), 'no client ID: Spotify is not played here').toBe(false)
    expect(c.offered('apple-music')).toBe(true)
    serve(null)
    expect(c.offered('apple-music'), 'no frame: nothing is played here').toBe(false)
  })

  it('a consent covers the service it was given for, at the host served now, and is stored in this browser', () => {
    const c = useMusicConsent()
    setMusicConsent('spotify', true)
    expect(c.consented('spotify')).toBe(true)
    expect(c.consented('apple-music'), 'another service needs its own').toBe(false)
    expect(JSON.parse(localStorage.getItem('veyq:music-consent')!)).toEqual({ host: 'music.example.org', services: ['spotify'] })
  })

  it('a new music host asks again (A2), and a consent given there replaces the old one', () => {
    const c = useMusicConsent()
    setMusicConsent('spotify', true)
    serve('https://other-music.example.org/music-frame')
    expect(c.consented('spotify')).toBe(false)
    setMusicConsent('apple-music', true)
    expect(JSON.parse(localStorage.getItem('veyq:music-consent')!)).toEqual({ host: 'other-music.example.org', services: ['apple-music'] })
  })

  it('a withdrawal takes effect at once and the last one removes the key (A1)', () => {
    const c = useMusicConsent()
    setMusicConsent('spotify', true)
    setMusicConsent('apple-music', true)
    setMusicConsent('spotify', false)
    expect(c.consented('spotify')).toBe(false)
    expect(c.consented('apple-music')).toBe(true)
    setMusicConsent('apple-music', false)
    expect(localStorage.getItem('veyq:music-consent')).toBeNull()
  })

  it('another tab\'s withdrawal applies here (the storage event)', () => {
    const c = useMusicConsent()
    setMusicConsent('spotify', true)
    localStorage.removeItem('veyq:music-consent')
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:music-consent' }))
    expect(c.consented('spotify')).toBe(false)
  })
})
