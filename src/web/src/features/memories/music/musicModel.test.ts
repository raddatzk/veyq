import { describe, expect, it } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { sealNodeMeta } from '../../../core/node/nodeMeta'
import { musicField, normalizeMusic, parsePlaylistLink, unsealMemoryMusic } from './musicModel'

// uc.memory.music: the playlist references a node's sealed meta holds (ADR 0131 decision 1).
describe('musicModel', () => {
  it.each([
    ['https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123', { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }],
    ['https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M', { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }],
    ['  spotify:playlist:37i9dQZF1DXcBWIGoYBM5M ', { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }],
    ['https://music.apple.com/de/playlist/sommer/pl.u-AbC123', { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }],
    ['https://music.apple.com/us/playlist/pl.b28c3a5975b04436b42680595f6983ad', { service: 'apple-music', id: 'pl.b28c3a5975b04436b42680595f6983ad', storefront: 'us' }],
  ])('reads %s as a playlist', (link, expected) => {
    expect(parsePlaylistLink(link)).toEqual(expected)
  })

  it.each([
    'https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M',
    'https://open.spotify.com/track/37i9dQZF1DXcBWIGoYBM5M',
    'http://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M',
    'https://open.spotify.com.evil.example/playlist/37i9dQZF1DXcBWIGoYBM5M',
    'https://music.apple.com/de/album/x/123456',
    'https://example.org/playlist/37i9dQZF1DXcBWIGoYBM5M',
    'not a link',
  ])('refuses %s (E1)', (link) => {
    expect(parsePlaylistLink(link)).toBeNull()
  })

  it('a stored value keeps the first valid playlist per service, in the services\' order, and skips the rest (E3)', () => {
    const spotify = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' } as const
    const apple = { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }
    expect(normalizeMusic([apple, { service: 'spotify', id: '!' }, spotify, { ...spotify, id: '0123456789abcdef' }])).toEqual([spotify, apple])
    expect(normalizeMusic('spotify')).toEqual([])
    expect(musicField([]), 'an empty list drops the field (A5)').toBeNull()
    expect(musicField([spotify])).toEqual([spotify])
  })

  it('a node\'s music opens under its key, and a meta of another node or none shows none', () => {
    const key = v1.randomBytes(32)
    const address = { scopeId: crypto.randomUUID(), nodeId: crypto.randomUUID() }
    const music = [{ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }]
    const wrappedMeta = sealNodeMeta(key, address, { v: 1, music })
    expect(unsealMemoryMusic(address.scopeId, key, { id: address.nodeId, wrappedMeta })).toEqual(music)
    expect(unsealMemoryMusic(address.scopeId, key, { id: crypto.randomUUID(), wrappedMeta })).toEqual([])
    expect(unsealMemoryMusic(address.scopeId, key, { id: address.nodeId, wrappedMeta: null })).toEqual([])
  })
})
