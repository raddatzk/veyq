// The music frame's protocol (ADR 0131 decision 2): what the app and the frame say to each other by `postMessage`, and
// nothing else. The frame runs on the instance's music host, a different origin from the app, and loads a vendor's SDK
// (Spotify's Web Playback SDK, Apple's MusicKit JS) there; the app origin, which holds the viewer's keys, never runs vendor
// code. So this protocol is the whole boundary: the app hands the frame one playlist and the service's PUBLIC credential
// (the Server Owner's Spotify client ID, the MusicKit developer token - every viewer's browser receives them anyway), and
// the frame answers with its state. No key, no node id, no other content ever crosses; every message is parsed against
// exactly these shapes, and anything else is dropped on both sides.
//
// The playlist reference shapes live here too, because both sides check them: the app before it seals one into a node's
// meta, the frame before it hands one to a vendor.

export type MusicService = 'spotify' | 'apple-music'
export const MUSIC_SERVICES: readonly MusicService[] = ['spotify', 'apple-music']

/** One playlist as the service names it: a Spotify playlist's base-62 id; an Apple Music playlist's `pl.` id and the
 *  storefront (country) its link named. */
export type Playlist = { service: 'spotify'; id: string } | { service: 'apple-music'; id: string; storefront: string }

export const SPOTIFY_PLAYLIST_ID = /^[0-9A-Za-z]{16,24}$/
export const APPLE_PLAYLIST_ID = /^pl\.[0-9A-Za-z-]{1,48}$/
export const STOREFRONT = /^[a-z]{2}$/

/** The playlist [value] names, or null where it is not one: the one check both sides run. */
export function asPlaylist(value: unknown): Playlist | null {
  if (!value || typeof value !== 'object') return null
  const v = value as Record<string, unknown>
  if (v.service === 'spotify' && typeof v.id === 'string' && SPOTIFY_PLAYLIST_ID.test(v.id)) return { service: 'spotify', id: v.id }
  if (v.service === 'apple-music' && typeof v.id === 'string' && APPLE_PLAYLIST_ID.test(v.id)) {
    const storefront = typeof v.storefront === 'string' && STOREFRONT.test(v.storefront) ? v.storefront : 'us'
    return { service: 'apple-music', id: v.id, storefront }
  }
  return null
}

/** The address that opens [p] in the service's own app or site: a link the viewer follows, nothing loaded into veyq. */
export function playlistLink(p: Playlist): string {
  return p.service === 'spotify'
    ? `https://open.spotify.com/playlist/${p.id}`
    : `https://music.apple.com/${p.storefront}/playlist/${p.id}`
}

export type Locale = 'en' | 'de'

/** The app → frame messages. `load` mounts one service's player for one playlist; `forget` signs the viewer out with
 *  the vendor (the consent was withdrawn) and is answered by `forgotten`. */
export type AppMessage =
  | { type: 'veyq-music:load'; playlist: Playlist; credential: string; locale: Locale }
  | { type: 'veyq-music:forget' }

export type FrameStatus = 'loading' | 'signed-out' | 'ready' | 'playing' | 'paused' | 'error'
export type FrameError = 'premium-required' | 'subscription-required' | 'sign-in-failed' | 'unavailable'

/** The frame → app messages: `ready` once it listens, its `state` after every change, `forgotten` after a `forget`. */
export type FrameMessage =
  | { type: 'veyq-music:ready' }
  | { type: 'veyq-music:state'; status: FrameStatus; error?: FrameError }
  | { type: 'veyq-music:forgotten' }

const STATUSES: readonly FrameStatus[] = ['loading', 'signed-out', 'ready', 'playing', 'paused', 'error']
const ERRORS: readonly FrameError[] = ['premium-required', 'subscription-required', 'sign-in-failed', 'unavailable']

/** A credential is a client ID or a JWT: printable, no whitespace, bounded like the contract's (2048). */
const CREDENTIAL = /^[A-Za-z0-9._-]{8,2048}$/

/** [data] as an app message, or null: what the frame accepts from its parent. */
export function parseAppMessage(data: unknown): AppMessage | null {
  if (!data || typeof data !== 'object') return null
  const m = data as Record<string, unknown>
  if (m.type === 'veyq-music:forget') return { type: 'veyq-music:forget' }
  if (m.type !== 'veyq-music:load') return null
  const playlist = asPlaylist(m.playlist)
  if (!playlist || typeof m.credential !== 'string' || !CREDENTIAL.test(m.credential)) return null
  return { type: 'veyq-music:load', playlist, credential: m.credential, locale: m.locale === 'de' ? 'de' : 'en' }
}

/** [data] as a frame message, or null: what the app accepts from the frame it mounted. */
export function parseFrameMessage(data: unknown): FrameMessage | null {
  if (!data || typeof data !== 'object') return null
  const m = data as Record<string, unknown>
  if (m.type === 'veyq-music:ready') return { type: 'veyq-music:ready' }
  if (m.type === 'veyq-music:forgotten') return { type: 'veyq-music:forgotten' }
  if (m.type !== 'veyq-music:state' || !STATUSES.includes(m.status as FrameStatus)) return null
  const error = ERRORS.includes(m.error as FrameError) ? (m.error as FrameError) : undefined
  return { type: 'veyq-music:state', status: m.status as FrameStatus, ...(error ? { error } : {}) }
}
