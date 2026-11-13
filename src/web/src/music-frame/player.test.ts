import { describe, expect, it } from 'vitest'
import { trackOf } from './player'
import { spotifyTrack } from './spotify'
import { appleTrack } from './apple'

// What plays, as the frame draws it (ADR 0131 decision 6): read from what a vendor's SDK hands over, every field checked -
// text bounded, a cover only at an https address - and a skip the vendor restricts marked so the frame does not offer it.
describe('the track the frame draws', () => {
  it('takes a title, an artist and an https cover; anything else is none', () => {
    expect(trackOf('Song', 'Band', 'https://i.scdn.co/image/ab67')).toEqual({
      title: 'Song', artist: 'Band', artwork: 'https://i.scdn.co/image/ab67', canPrevious: true, canNext: true,
    })
    expect(trackOf('', 'Band', null), 'no title: nothing to draw').toBeNull()
    expect(trackOf(42, 'Band', null)).toBeNull()
    expect(trackOf('Song', 7, 'javascript:alert(1)')).toMatchObject({ artist: '', artwork: null })
    expect(trackOf('Song', 'Band', 'http://i.scdn.co/x')?.artwork, 'never plain http').toBeNull()
    expect(trackOf('Song', 'Band', 'https://x" onerror="y')?.artwork).toBeNull()
    expect(trackOf('x'.repeat(5000), 'Band', null)?.title).toHaveLength(300)
  })

  it('reads Spotify\'s state: the artists joined, the smallest album image, its restricted skips', () => {
    const state = {
      paused: false,
      disallows: { skipping_prev: true },
      track_window: { current_track: { name: 'Song', artists: [{ name: 'A' }, { name: 'B' }], album: { images: [{ url: 'https://i.scdn.co/640' }, { url: 'https://i.scdn.co/64' }] } } },
    }
    expect(spotifyTrack(state)).toEqual({ title: 'Song', artist: 'A, B', artwork: 'https://i.scdn.co/64', canPrevious: false, canNext: true })
    expect(spotifyTrack({ paused: true }), 'no current track').toBeNull()
  })

  it('reads MusicKit\'s now-playing item through its own artwork formatter', () => {
    const format = (a: { url?: unknown }, w: number, h: number) => String(a.url).replace('{w}x{h}', `${w}x${h}`)
    const music = { nowPlayingItem: { title: 'Song', artistName: 'Band', artwork: { url: 'https://is1-ssl.mzstatic.com/a/{w}x{h}bb.jpg' } } }
    expect(appleTrack(music, format)).toMatchObject({ title: 'Song', artist: 'Band', artwork: 'https://is1-ssl.mzstatic.com/a/96x96bb.jpg' })
    expect(appleTrack({ nowPlayingItem: null }, format)).toBeNull()
    expect(appleTrack(music, () => { throw new Error('x') })?.artwork, 'a formatter that throws: no cover').toBeNull()
  })
})
