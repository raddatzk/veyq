import { describe, expect, it } from 'vitest'
import { asPlaylist, parseAppMessage, parseFrameMessage, playlistLink } from './protocol'

// The music frame's boundary (ADR 0131 decision 2): every message is parsed against exactly its shape on both sides, so
// nothing but a playlist, a public credential, a locale, a theme and a state ever crosses.
describe('the music frame protocol', () => {
  const spotify = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }
  const apple = { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }

  it('a playlist is a service and an id of its shape; anything else is none', () => {
    expect(asPlaylist(spotify)).toEqual(spotify)
    expect(asPlaylist(apple)).toEqual(apple)
    expect(asPlaylist({ ...apple, storefront: 'DEU' }), 'a storefront that is no country falls back').toEqual({ ...apple, storefront: 'us' })
    expect(asPlaylist({ service: 'spotify', id: '../../me' })).toBeNull()
    expect(asPlaylist({ service: 'spotify', id: 'pl.u-AbC123' })).toBeNull()
    expect(asPlaylist({ service: 'apple-music', id: '37i9dQZF1DXcBWIGoYBM5M' })).toBeNull()
    expect(asPlaylist({ service: 'tidal', id: '37i9dQZF1DXcBWIGoYBM5M' })).toBeNull()
    expect(asPlaylist(null)).toBeNull()
  })

  it('a playlist opens in its service by a link built from the id alone', () => {
    expect(playlistLink(asPlaylist(spotify)!)).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    expect(playlistLink(asPlaylist(apple)!)).toBe('https://music.apple.com/de/playlist/pl.u-AbC123')
  })

  it('the frame takes a load of one playlist with its credential, and a forget - and drops every other field', () => {
    const load = parseAppMessage({ type: 'veyq-music:load', playlist: spotify, credential: '0123456789abcdef', locale: 'de', key: 'secret', nodeId: 'n' })
    expect(load).toEqual({ type: 'veyq-music:load', playlist: spotify, credential: '0123456789abcdef', locale: 'de', theme: 'light' })
    expect(parseAppMessage({ type: 'veyq-music:load', playlist: spotify, credential: '0123456789abcdef', locale: 'en', theme: 'dark' }))
      .toMatchObject({ theme: 'dark' })
    expect(parseAppMessage({ type: 'veyq-music:forget', extra: 1 })).toEqual({ type: 'veyq-music:forget' })
    expect(parseAppMessage({ type: 'veyq-music:load', playlist: spotify, credential: 'with space', locale: 'en' })).toBeNull()
    expect(parseAppMessage({ type: 'veyq-music:load', playlist: { service: 'spotify' }, credential: '0123456789abcdef' })).toBeNull()
    expect(parseAppMessage({ type: 'veyq-music:play' })).toBeNull()
    expect(parseAppMessage('veyq-music:forget')).toBeNull()
  })

  it('the frame takes a theme as exactly light or dark, nothing it would write as given', () => {
    expect(parseAppMessage({ type: 'veyq-music:theme', theme: 'dark', css: 'body{}' })).toEqual({ type: 'veyq-music:theme', theme: 'dark' })
    expect(parseAppMessage({ type: 'veyq-music:theme', theme: 'light' })).toEqual({ type: 'veyq-music:theme', theme: 'light' })
    expect(parseAppMessage({ type: 'veyq-music:theme', theme: '" onload="x' })).toEqual({ type: 'veyq-music:theme', theme: 'light' })
    expect(parseAppMessage({ type: 'veyq-music:load', playlist: spotify, credential: '0123456789abcdef', theme: 'veyq-dark evil' }))
      .toMatchObject({ theme: 'light' })
  })

  it('the app takes a ready, a known state with a known error, and forgotten', () => {
    expect(parseFrameMessage({ type: 'veyq-music:ready' })).toEqual({ type: 'veyq-music:ready' })
    expect(parseFrameMessage({ type: 'veyq-music:state', status: 'error', error: 'premium-required', track: 'x' }))
      .toEqual({ type: 'veyq-music:state', status: 'error', error: 'premium-required' })
    expect(parseFrameMessage({ type: 'veyq-music:state', status: 'playing', error: 'made-up' })).toEqual({ type: 'veyq-music:state', status: 'playing' })
    expect(parseFrameMessage({ type: 'veyq-music:state', status: 'exploding' })).toBeNull()
    expect(parseFrameMessage({ type: 'veyq-music:forgotten' })).toEqual({ type: 'veyq-music:forgotten' })
    expect(parseFrameMessage({ type: 'something-else' })).toBeNull()
  })
})
