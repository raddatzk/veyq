// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { capabilityState } from '../../../shared/state/capabilities'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { musicHeaderActions } from './musicHeader'
import { closeBar, playlistInBar } from './musicPlayback'
import type { Playlist } from './musicModel'

// The header's music buttons (uc.memory.music, ADR 0131 decision 5): one per playlist the node names, for every viewer;
// a toggle that starts it in the shell's music bar where the server plays the service here, else the link into the
// service (A3).

const spotify: Playlist = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }
const apple: Playlist = { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }
const t = (key: string, v?: Record<string, unknown>) => `${key}${v ? `(${Object.values(v).join(',')})` : ''}`

function serve(on: boolean) {
  const entries = { ...capabilityState.entries }
  if (on) {
    entries[MEMORIES_MUSIC] = { id: MEMORIES_MUSIC, serviceUrl: 'https://music.example.org/music-frame' }
    entries[MEMORIES_MUSIC_SPOTIFY] = { id: MEMORIES_MUSIC_SPOTIFY, spotifyClientId: '0123456789abcdef' }
  } else {
    delete entries[MEMORIES_MUSIC]
    delete entries[MEMORIES_MUSIC_SPOTIFY]
  }
  delete entries[MEMORIES_MUSIC_APPLE_MUSIC]
  capabilityState.tenantEntries = null
  capabilityState.entries = entries
}

beforeEach(() => {
  closeBar()
  serve(true)
})

describe('musicHeaderActions', () => {
  it('no playlist, no button', () => {
    expect(musicHeaderActions([], t)).toEqual([])
  })

  it('a served service is a toggle that starts its playlist in the bar and, pressed, ends it', () => {
    const [play] = musicHeaderActions([spotify], t)
    expect(play).toMatchObject({ id: 'music-spotify', shown: true, icon: 'play', pressed: false })
    play.onClick()
    expect(playlistInBar.value).toEqual(spotify)
    const [pressed] = musicHeaderActions([spotify], t)
    expect(pressed).toMatchObject({ pressed: true, label: play.label })
    pressed.onClick()
    expect(playlistInBar.value).toBeNull()
  })

  it('another Memory\'s playlist in the bar leaves this button unpressed until it is pressed', () => {
    musicHeaderActions([{ service: 'spotify', id: '1111111111111111111111' }], t)[0].onClick()
    expect(musicHeaderActions([spotify], t)[0].pressed).toBe(false)
  })

  it('a service the server does not play here opens the playlist in the service instead, loading nothing into veyq', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const [s, a] = musicHeaderActions([spotify, apple], t)
    expect(s.icon, 'served: the toggle').toBe('play')
    expect(a).toMatchObject({ id: 'music-apple-music', icon: 'external-link', label: 'memory.music.open(memory.music.service.apple-music)' })
    a.onClick()
    expect(open).toHaveBeenCalledWith('https://music.apple.com/de/playlist/pl.u-AbC123', '_blank', 'noopener,noreferrer')
    expect(playlistInBar.value).toBeNull()
    serve(false)
    expect(musicHeaderActions([spotify], t)[0].icon).toBe('external-link')
    open.mockRestore()
  })
})
