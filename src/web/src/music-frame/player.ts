// What the music frame's page asks of a vendor's player (ADR 0131): sign in from the viewer's click, play or pause, the
// next track, and forget the sign-in when the consent is withdrawn. Each vendor's adapter (`spotify.ts`, `apple.ts`)
// reports its state through `report`; the page draws it and tells the app.
import type { FrameError, FrameStatus } from './protocol'

export interface MusicPlayer {
  signIn(): Promise<void>
  toggle(): Promise<void>
  next(): Promise<void>
  forget(): Promise<void>
}

export type Report = (status: FrameStatus, error?: FrameError) => void

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
