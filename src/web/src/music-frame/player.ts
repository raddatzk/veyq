// What the music frame's page asks of a vendor's player (ADR 0131): sign in from the viewer's click, play or pause, the
// previous and the next track, switch to another playlist of the same service (the sign-in kept), and forget the sign-in
// when the consent is withdrawn. Each vendor's adapter (`spotify.ts`, `apple.ts`) reports its state through `report` and
// what plays through `show`; the page draws both and tells the app the state alone - what plays never leaves the frame.
import type { FrameError, FrameStatus } from './protocol'

export interface MusicPlayer {
  signIn(): Promise<void>
  toggle(): Promise<void>
  previous(): Promise<void>
  next(): Promise<void>
  /** Play [playlistId] of this service from now on: at once where a playlist already plays, else at the next play. */
  switchTo(playlistId: string): Promise<void>
  forget(): Promise<void>
}

export type Report = (status: FrameStatus, error?: FrameError) => void

/** The track that plays, as the vendor named it: drawn in the frame only. `canPrevious` / `canNext` are the vendor's
 *  restrictions (Spotify's `disallows`, its Design Guidelines: a control the vendor restricts is not offered). */
export interface Track {
  title: string
  artist: string
  /** The cover's address at the vendor's image host, https only; null for none. */
  artwork: string | null
  canPrevious: boolean
  canNext: boolean
}

export type Show = (track: Track | null) => void

/** The longest title or artist drawn: the vendor's text as given, bounded so a hostile one cannot flood the bar. */
const MAX_TEXT = 300

/** A [Track] from what a vendor handed over, or null where it names no title: every field checked, never trusted. */
export function trackOf(title: unknown, artist: unknown, artwork: unknown, canPrevious = true, canNext = true): Track | null {
  if (typeof title !== 'string' || title.trim() === '') return null
  const text = (v: unknown) => (typeof v === 'string' ? v.trim().slice(0, MAX_TEXT) : '')
  return {
    title: text(title),
    artist: text(artist),
    artwork: typeof artwork === 'string' && /^https:\/\/[^\s"'<>]+$/.test(artwork) ? artwork : null,
    canPrevious,
    canNext,
  }
}

/** Load a vendor's script into THIS page - the music frame's, never the app's - once. The music host's policy names the
 *  two hosts it may come from (cc.security.csp § The music frame). */
export function loadScript(src: string, doc: Document = document): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = doc.createElement('script')
    script.src = src
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error(`could not load ${src}`))
    doc.head.appendChild(script)
  })
}
