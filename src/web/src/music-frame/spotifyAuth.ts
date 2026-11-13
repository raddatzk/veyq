// Spotify's Authorization Code flow with PKCE, as the music frame runs it (ADR 0131 decision 3): the verifier is made and
// kept in the frame, the sign-in runs in Spotify's own popup, and the callback page - the frame's page under
// `/music-frame/spotify-callback`, on the music host - relays the code and the state back over a BroadcastChannel (and
// `postMessage` to an opener where one survived the app's COOP). The frame then trades the code for a token with the
// verifier only it holds. The veyq server is in none of it: the callback's URL carries the code once, worthless without
// the verifier. Pure, so the flow's every value is testable apart from the popup and the network.

export const SPOTIFY_AUTHORIZE = 'https://accounts.spotify.com/authorize'
export const SPOTIFY_TOKEN = 'https://accounts.spotify.com/api/token'
/** What the Web Playback SDK needs (`streaming`, the account reads it checks) and starting a playlist on its device. */
export const SPOTIFY_SCOPES = 'streaming user-read-email user-read-private user-modify-playback-state'
/** The channel the callback page and the frame share on the music origin. */
export const SPOTIFY_CHANNEL = 'veyq-music-spotify'
export const CALLBACK_PATH = '/music-frame/spotify-callback'

const VERIFIER_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~'

/** A fresh code verifier: 64 unreserved characters (RFC 7636 § 4.1 allows 43 to 128). */
export function randomVerifier(random: (n: number) => Uint8Array = (n) => crypto.getRandomValues(new Uint8Array(n))): string {
  return Array.from(random(64), (b) => VERIFIER_CHARS[b % VERIFIER_CHARS.length]).join('')
}

/** A fresh state: 32 base64url characters, matched on the way back. */
export function randomState(random: (n: number) => Uint8Array = (n) => crypto.getRandomValues(new Uint8Array(n))): string {
  return base64url(random(24))
}

export function base64url(bytes: Uint8Array): string {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** The S256 challenge of [verifier] (RFC 7636 § 4.2). */
export async function challengeOf(verifier: string): Promise<string> {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))))
}

export function callbackUrl(origin: string): string {
  return origin + CALLBACK_PATH
}

export function authorizeUrl(a: { clientId: string; redirectUri: string; challenge: string; state: string }): string {
  const q = new URLSearchParams({
    client_id: a.clientId,
    response_type: 'code',
    redirect_uri: a.redirectUri,
    code_challenge_method: 'S256',
    code_challenge: a.challenge,
    state: a.state,
    scope: SPOTIFY_SCOPES,
  })
  return `${SPOTIFY_AUTHORIZE}?${q.toString()}`
}

/** What the callback relays: a code and its state, or Spotify's error. */
export type CallbackResult = { code: string; state: string } | { error: string; state: string | null }

/** The callback page's query, read; null where it carries neither a code nor an error. */
export function parseCallback(search: string): CallbackResult | null {
  const q = new URLSearchParams(search)
  const code = q.get('code')
  const state = q.get('state')
  const error = q.get('error')
  if (error) return { error, state }
  if (code && state) return { code, state }
  return null
}

/** The relayed message, checked: the callback's shape, or null. */
export function asCallbackResult(data: unknown): CallbackResult | null {
  if (!data || typeof data !== 'object') return null
  const m = data as Record<string, unknown>
  if (m.kind !== 'veyq-spotify-callback') return null
  if (typeof m.code === 'string' && typeof m.state === 'string') return { code: m.code, state: m.state }
  if (typeof m.error === 'string') return { error: m.error, state: typeof m.state === 'string' ? m.state : null }
  return null
}

/** The body of the code-for-token exchange (a public client: no secret, the verifier instead). */
export function tokenRequest(a: { clientId: string; code: string; redirectUri: string; verifier: string }): URLSearchParams {
  return new URLSearchParams({
    grant_type: 'authorization_code',
    code: a.code,
    redirect_uri: a.redirectUri,
    client_id: a.clientId,
    code_verifier: a.verifier,
  })
}

/** The body of a refresh. */
export function refreshRequest(a: { clientId: string; refreshToken: string }): URLSearchParams {
  return new URLSearchParams({ grant_type: 'refresh_token', refresh_token: a.refreshToken, client_id: a.clientId })
}

export interface SpotifyToken {
  accessToken: string
  refreshToken: string | null
  /** Epoch milliseconds at which to refresh: a minute before Spotify's `expires_in`. */
  refreshAt: number
}

/** Spotify's token answer, read; null for anything else. */
export function asToken(body: unknown, now: number, previousRefresh: string | null = null): SpotifyToken | null {
  if (!body || typeof body !== 'object') return null
  const b = body as Record<string, unknown>
  if (typeof b.access_token !== 'string' || typeof b.expires_in !== 'number') return null
  return {
    accessToken: b.access_token,
    refreshToken: typeof b.refresh_token === 'string' ? b.refresh_token : previousRefresh,
    refreshAt: now + Math.max(0, b.expires_in - 60) * 1000,
  }
}
