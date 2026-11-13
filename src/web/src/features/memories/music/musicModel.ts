// uc.memory.music (web) — a node's background music in its sealed meta (ADR 0131 decision 1): at most one playlist per
// service, `music: [{ service: 'spotify', id }, { service: 'apple-music', id, storefront }]`, sealed under the node's key
// like the date and the place, so the server never learns which playlist. This module is pure: it reads a share link the
// member pastes, normalizes what the meta holds and tells which playlists the viewer's server plays in the music frame.
// The shapes are the music frame's protocol's (`src/music-frame/protocol.ts`), checked on both sides of that boundary.
import { unsealNodeMeta } from '../../../core/node/nodeMeta'
import type { TreeNode } from '../../../core/node/tree'
import { asPlaylist, MUSIC_SERVICES, type MusicService, type Playlist } from '../../../music-frame/protocol'

export type { MusicService, Playlist }
export { MUSIC_SERVICES }

/**
 * The playlist a pasted [input] names, or null where it names none: an `open.spotify.com` playlist link (with or without
 * a locale segment and a `?si=` query), a `spotify:playlist:` URI, or a `music.apple.com/<country>/playlist/<name>/pl.…`
 * link (the name may be absent). Anything else - an album, a track, another site - is null: the dialog refuses it.
 */
export function parsePlaylistLink(input: string): Playlist | null {
  const text = input.trim()
  const uri = /^spotify:playlist:([0-9A-Za-z]+)$/.exec(text)
  if (uri) return asPlaylist({ service: 'spotify', id: uri[1] })
  let url: URL
  try {
    url = new URL(text)
  } catch {
    return null
  }
  if (url.protocol !== 'https:') return null
  const parts = url.pathname.split('/').filter(Boolean)
  if (url.hostname === 'open.spotify.com') {
    const at = parts.indexOf('playlist')
    // `/playlist/<id>`, `/intl-de/playlist/<id>`, `/embed/playlist/<id>`
    if (at < 0 || at > 1 || parts.length !== at + 2) return null
    return asPlaylist({ service: 'spotify', id: parts[at + 1] })
  }
  if (url.hostname === 'music.apple.com' || url.hostname === 'embed.music.apple.com') {
    // `/<country>/playlist/<name>/<id>` or `/<country>/playlist/<id>`
    if (parts.length < 3 || parts[1] !== 'playlist') return null
    return asPlaylist({ service: 'apple-music', storefront: parts[0], id: parts[parts.length - 1] })
  }
  return null
}

/** The stored `music` value read: each valid entry, the first per service, in the services' order (E3: an invalid one is
 *  skipped, never shown). */
export function normalizeMusic(raw: unknown): Playlist[] {
  if (!Array.isArray(raw)) return []
  const byService = new Map<MusicService, Playlist>()
  for (const entry of raw) {
    const p = asPlaylist(entry)
    if (p && !byService.has(p.service)) byService.set(p.service, p)
  }
  return MUSIC_SERVICES.flatMap((s) => (byService.has(s) ? [byService.get(s)!] : []))
}

/** The value to seal for [list]: null (the field dropped, A5) when it is empty. */
export function musicField(list: readonly Playlist[]): Playlist[] | null {
  const normalized = normalizeMusic(list)
  return normalized.length > 0 ? normalized : null
}

/** A node's music, opened for display under its key (best effort: a meta that does not open has none). */
export function unsealMemoryMusic(scopeId: string, key: Uint8Array, node: Pick<TreeNode, 'id' | 'wrappedMeta'>): Playlist[] {
  return normalizeMusic(unsealNodeMeta(key, { scopeId, nodeId: node.id }, node.wrappedMeta).music)
}
