// Spotify in the music frame (ADR 0131): the PKCE sign-in (`spotifyAuth.ts`), then Spotify's Web Playback SDK, which
// plays in a frame of its own (`sdk.scdn.co`) inside this one, and Spotify's Web API to start the playlist on that
// player. Needs Spotify Premium (the SDK refuses any other account with `account_error`). The token lives in this page's
// memory only, so it is gone with the page; Spotify's own sign-in makes the next one a click.
import { loadScript, trackOf, type MusicPlayer, type Report, type Show } from './player'
import {
  asCallbackResult, asToken, authorizeUrl, callbackUrl, challengeOf, randomState, randomVerifier, refreshRequest,
  SPOTIFY_CHANNEL, SPOTIFY_TOKEN, tokenRequest, type CallbackResult, type SpotifyToken,
} from './spotifyAuth'

const SDK = 'https://sdk.scdn.co/spotify-player.js'
const API = 'https://api.spotify.com/v1'

interface SpotifySdkPlayer {
  connect(): Promise<boolean>
  disconnect(): void
  addListener(event: string, cb: (payload: never) => void): void
  togglePlay(): Promise<void>
  previousTrack(): Promise<void>
  nextTrack(): Promise<void>
  activateElement(): Promise<void>
}

interface SpotifyGlobal {
  Player: new (options: { name: string; getOAuthToken: (cb: (token: string) => void) => void; volume?: number }) => SpotifySdkPlayer
}

declare global {
  interface Window {
    Spotify?: SpotifyGlobal
    onSpotifyWebPlaybackSDKReady?: () => void
  }
}

/** Waits for the callback page's relay: its BroadcastChannel, or a message from a same-origin opener's popup. */
function awaitCallback(signal: AbortSignal): Promise<CallbackResult> {
  return new Promise((resolve, reject) => {
    const channel = new BroadcastChannel(SPOTIFY_CHANNEL)
    const done = (r: CallbackResult) => {
      channel.close()
      window.removeEventListener('message', onMessage)
      resolve(r)
    }
    const onMessage = (e: MessageEvent) => {
      if (e.origin !== location.origin) return
      const r = asCallbackResult(e.data)
      if (r) done(r)
    }
    channel.onmessage = (e) => {
      const r = asCallbackResult(e.data)
      if (r) done(r)
    }
    window.addEventListener('message', onMessage)
    signal.addEventListener('abort', () => {
      channel.close()
      window.removeEventListener('message', onMessage)
      reject(new Error('aborted'))
    })
  })
}

/** The part of the SDK's `player_state_changed` state the frame draws (its Web Playback SDK reference). */
interface SpotifyState {
  paused: boolean
  disallows?: { skipping_prev?: boolean; skipping_next?: boolean }
  track_window?: { current_track?: { name?: unknown; artists?: { name?: unknown }[]; album?: { images?: { url?: unknown }[] } } }
}

/** What [state] says plays, for the frame to draw (`trackOf` checks every field). */
export function spotifyTrack(state: SpotifyState) {
  const t = state.track_window?.current_track
  if (!t) return null
  const artists = Array.isArray(t.artists) ? t.artists.map((a) => (typeof a?.name === 'string' ? a.name : '')).filter(Boolean).join(', ') : ''
  const images = Array.isArray(t.album?.images) ? t.album!.images! : []
  // the SDK lists the album's images large first; the bar draws a small one, so the last
  const artwork = images.length > 0 ? images[images.length - 1]?.url : null
  return trackOf(t.name, artists, artwork, !state.disallows?.skipping_prev, !state.disallows?.skipping_next)
}

export function createSpotifyPlayer(clientId: string, initialPlaylistId: string, report: Report, show: Show): MusicPlayer {
  let playlistId = initialPlaylistId
  let token: SpotifyToken | null = null
  let player: SpotifySdkPlayer | null = null
  let deviceId: string | null = null
  let started = false
  let pending: AbortController | null = null

  async function accessToken(): Promise<string> {
    if (!token) throw new Error('signed out')
    if (Date.now() >= token.refreshAt && token.refreshToken) {
      const answer = await fetch(SPOTIFY_TOKEN, { method: 'POST', body: refreshRequest({ clientId, refreshToken: token.refreshToken }) })
      const next = answer.ok ? asToken(await answer.json(), Date.now(), token.refreshToken) : null
      if (!next) throw new Error('refresh failed')
      token = next
    }
    return token.accessToken
  }

  async function connectPlayer(): Promise<void> {
    if (!window.Spotify) {
      await new Promise<void>((resolve, reject) => {
        window.onSpotifyWebPlaybackSDKReady = () => resolve()
        loadScript(SDK).catch(reject)
      })
    }
    const sdk = new window.Spotify!.Player({
      name: 'veyq',
      getOAuthToken: (cb) => void accessToken().then(cb, () => report('error', 'sign-in-failed')),
      volume: 0.6,
    })
    sdk.addListener('ready', ({ device_id }: { device_id: string }) => {
      deviceId = device_id
      report('ready')
    })
    sdk.addListener('account_error', () => report('error', 'premium-required'))
    sdk.addListener('authentication_error', () => report('error', 'sign-in-failed'))
    sdk.addListener('initialization_error', () => report('error', 'unavailable'))
    sdk.addListener('player_state_changed', (state: SpotifyState | null) => {
      if (!state) return
      report(state.paused ? 'paused' : 'playing')
      show(spotifyTrack(state))
    })
    player = sdk
    if (!(await sdk.connect())) report('error', 'unavailable')
  }

  /** Start [playlistId] on this frame's player (Spotify's Web API: the SDK itself cannot pick what plays). */
  async function startPlaylist(): Promise<void> {
    const answer = await fetch(`${API}/me/player/play?device_id=${encodeURIComponent(deviceId!)}`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${await accessToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ context_uri: `spotify:playlist:${playlistId}` }),
    })
    if (answer.status === 403) return report('error', 'premium-required')
    if (!answer.ok) return report('error', 'unavailable')
    started = true
  }

  return {
    async signIn() {
      pending?.abort()
      pending = new AbortController()
      const verifier = randomVerifier()
      const state = randomState()
      const redirectUri = callbackUrl(location.origin)
      // Opened at once, in the click: a popup opened after an await is blocked. `noopener` is the app's COOP's to decide;
      // the answer comes back over the channel either way.
      const popup = window.open('about:blank', 'veyq-spotify-sign-in', 'popup,width=480,height=720')
      const url = authorizeUrl({ clientId, redirectUri, challenge: await challengeOf(verifier), state })
      if (popup) popup.location.href = url
      else window.open(url, 'veyq-spotify-sign-in', 'popup,width=480,height=720')
      report('loading')
      try {
        const result = await awaitCallback(pending.signal)
        if ('error' in result || result.state !== state) return report('error', 'sign-in-failed')
        const answer = await fetch(SPOTIFY_TOKEN, { method: 'POST', body: tokenRequest({ clientId, code: result.code, redirectUri, verifier }) })
        token = answer.ok ? asToken(await answer.json(), Date.now()) : null
        if (!token) return report('error', 'sign-in-failed')
        await connectPlayer()
      } catch {
        report('error', 'sign-in-failed')
      }
    },
    async toggle() {
      if (!player || !deviceId) return
      await player.activateElement() // in the click: what lets a browser that asks for a gesture start the audio
      if (started) return player.togglePlay()
      await startPlaylist()
    },
    async previous() {
      await player?.previousTrack()
    },
    async next() {
      await player?.nextTrack()
    },
    async switchTo(id: string) {
      playlistId = id
      // a playlist already started: the player is activated, so the new one starts without another click
      if (started && player && deviceId) await startPlaylist()
    },
    async forget() {
      pending?.abort()
      player?.disconnect()
      player = null
      token = null
      deviceId = null
      started = false
      show(null)
    },
  }
}
