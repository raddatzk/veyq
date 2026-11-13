// Apple Music in the music frame (ADR 0131): MusicKit JS v3 from Apple's CDN (its licence forbids re-hosting it),
// configured with the Server Owner's developer token, signed in through MusicKit's own `authorize()` popup - whose answer
// reaches its opener, which is why the app's COOP is `same-origin-allow-popups` while the frame is served. MusicKit keeps
// its sign-in in this origin's storage, as it does on any page. Full tracks need an Apple Music subscription.
import { loadScript, type MusicPlayer, type Report } from './player'

const MUSICKIT = 'https://js-cdn.music.apple.com/musickit/v3/musickit.js'

interface MusicKitInstance {
  isAuthorized: boolean
  isPlaying: boolean
  authorize(): Promise<string>
  unauthorize(): Promise<void>
  setQueue(options: { playlist: string; startPlaying?: boolean }): Promise<unknown>
  play(): Promise<void>
  pause(): void
  skipToNextItem(): Promise<void>
  addEventListener(event: string, cb: (payload: { state?: number }) => void): void
}

interface MusicKitGlobal {
  configure(options: { developerToken: string; app: { name: string; build: string } }): Promise<MusicKitInstance>
  getInstance(): MusicKitInstance
}

declare global {
  interface Window {
    MusicKit?: MusicKitGlobal
  }
}

/** MusicKit's `PlaybackStates`: 2 playing, 3 paused (MusicKit JS v3). */
const PLAYING = 2
const PAUSED = 3

export function createApplePlayer(developerToken: string, playlistId: string, report: Report): MusicPlayer {
  let music: MusicKitInstance | null = null
  let queued = false

  const ready: Promise<MusicKitInstance | null> = (async () => {
    try {
      if (!window.MusicKit) {
        const loaded = new Promise<void>((resolve) => document.addEventListener('musickitloaded', () => resolve(), { once: true }))
        await loadScript(MUSICKIT)
        if (!window.MusicKit) await loaded
      }
      music = await window.MusicKit!.configure({ developerToken, app: { name: 'veyq', build: '1' } })
      music.addEventListener('playbackStateDidChange', ({ state }) => {
        if (state === PLAYING) report('playing')
        else if (state === PAUSED) report('paused')
      })
      report(music.isAuthorized ? 'ready' : 'signed-out')
      return music
    } catch {
      report('error', 'unavailable')
      return null
    }
  })()

  return {
    async signIn() {
      const m = await ready
      if (!m) return
      try {
        await m.authorize()
        report(m.isAuthorized ? 'ready' : 'signed-out')
      } catch {
        report('error', 'sign-in-failed')
      }
    },
    async toggle() {
      const m = await ready
      if (!m || !m.isAuthorized) return
      try {
        if (!queued) {
          await m.setQueue({ playlist: playlistId, startPlaying: true })
          queued = true
        } else if (m.isPlaying) m.pause()
        else await m.play()
      } catch {
        // MusicKit refuses playback to an Apple ID without a subscription (and plays nothing but previews to it).
        report('error', 'subscription-required')
      }
    },
    async next() {
      await music?.skipToNextItem()
    },
    async forget() {
      const m = await ready
      queued = false
      await m?.unauthorize().catch(() => undefined)
    },
  }
}
